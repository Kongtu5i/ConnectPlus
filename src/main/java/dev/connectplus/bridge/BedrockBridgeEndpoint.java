package dev.connectplus.bridge;

import dev.connectplus.logging.DebugLog;
import dev.connectplus.config.CPConfig;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.ProfileKey;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.time.Duration;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ConnectPlus side of the Geyser identity bridge protocol v1
 * (docs/integrations/geyser-bridge-v1.md §4). Owns the single in-process provider
 * registration, validates every descriptor/event/response field (external maps are
 * never trusted), enforces the response-side timeouts (RESOLVE/VALIDATE 3s,
 * DISCONNECT 5s) and guarantees that a late or spoofed response cannot change any
 * state. The public surface is exposed by CoreMain
 * ({@code registerBedrockBridgeV1} / {@code bedrockBridgeEventV1}); only JDK types
 * cross the boundary.
 *
 * <p>Not part of this class (task 4): the serial per-XUID duplicate-login
 * coordination — {@link #setDuplicateCandidateCoordinator} routes the event once a
 * coordinator is registered, and answers STALE while there is none.</p>
 */
public final class BedrockBridgeEndpoint {

    public static final int PROTOCOL_VERSION = 1;
    public static final String PROVIDER_ID = "connectplus-geyser-bridge";

    /** Capabilities a provider must declare for v1 to be considered supported. */
    public static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            "verified-xuid", "exact-channel-binding", "targeted-disconnect");

    /** Optional future official-API support; baseline providers must never emit its events. */
    public static final String CAP_AUTHENTICATED_DUPLICATE_ADMISSION = "authenticated-duplicate-admission";

    /** §4.2: the message that must reach the player verbatim on a REPLACED disconnect. */
    public static final String REPLACED_KICK_MESSAGE =
            "检测到异地登录，你的账号已在其他客户端登录，当前连接已断开。";

    public static final Duration DEFAULT_RESOLVE_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration DEFAULT_VALIDATE_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration DEFAULT_DISCONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Reason codes of a REJECTED registration (§4.1). */
    public static final String REASON_DISABLED = "DISABLED";
    public static final String REASON_UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL";
    public static final String REASON_UNSUPPORTED_RUNTIME = "UNSUPPORTED_RUNTIME";
    public static final String REASON_MISSING_CAPABILITY = "MISSING_CAPABILITY";
    public static final String REASON_PROVIDER_ALREADY_REGISTERED = "PROVIDER_ALREADY_REGISTERED";
    public static final String REASON_INVALID_REQUEST = "INVALID_REQUEST";

    /** The identity a VERIFIED resolve response carries; display-only name. */
    public record ResolvedBedrock(String xuid, String bedrockUsername, String bridgeSessionId) {
    }

    public enum ResolveStatus { VERIFIED, NO_MATCH, REJECTED, UNAVAILABLE, TIMEOUT }

    /**
     * A resolve outcome; {@code identity} is only set for VERIFIED. The
     * {@code reasonCode} carries the bridge's REJECTED reason verbatim (null
     * otherwise) so consumers can tell a matched-but-untrusted bedrock session
     * from a request-level refusal without a protocol change.
     */
    public record ResolveResult(ResolveStatus status, ResolvedBedrock identity, UUID providerEpoch, String reasonCode) {
        static ResolveResult of(final ResolveStatus status, final UUID epoch) {
            return new ResolveResult(status, null, epoch, null);
        }

        static ResolveResult rejected(final UUID epoch, final String reasonCode) {
            return new ResolveResult(ResolveStatus.REJECTED, null, epoch, reasonCode);
        }
    }

    /**
     * REJECTED reasons that imply the bridge DID match a bedrock session on the
     * raw address pair: the platform is known bedrock even though no trusted
     * XUID is available (capability report 2026-10-07 §3.1).
     */
    public static final java.util.Set<String> SESSION_MATCHED_REJECT_REASONS = java.util.Set.of(
            "UNTRACKED_SESSION", "IDENTITY_UNTRUSTED", "INVALID_IDENTITY", "SESSION_ID_MISMATCH",
            "CHANNEL_CONFLICT", "CONNECTION_ID_CONFLICT", "WIRE_MISMATCH");

    public enum ValidationStatus { VALID, INVALID, UNAVAILABLE, TIMEOUT }

    public record ValidationResult(ValidationStatus status) {
    }

    public enum DisconnectReason { REPLACED, BRIDGE_UNAVAILABLE }

    public enum DisconnectStatus { CLOSED, ALREADY_CLOSED, STALE, UNAVAILABLE, TIMEOUT }

    public record DisconnectResult(DisconnectStatus status) {
    }

    /** Routes a VERIFIED_DUPLICATE_CANDIDATE to the per-XUID coordinator (task 4). */
    @FunctionalInterface
    public interface DuplicateCandidateCoordinator {
        CompletionStage<Map<String, Object>> onDuplicateCandidate(
                String xuid, String oldBridgeSessionId, String newBridgeSessionId);
    }

    /** One live bridge session this endpoint verified, keyed by bridgeSessionId. */
    private record ManagedSession(String connectionId, String xuid, Channel channel) {
    }

    /** A stopped provider or closed bridge session immediately revokes its connection proof. */
    public boolean isCurrentIdentity(final ClientIdentity identity, final Channel channel) {
        final Registration current = this.registration;
        if (current == null || identity == null || channel == null || !channel.isActive()
                || identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK
                || !current.epoch.equals(identity.providerEpoch())) return false;
        final UUID connectionId = channel.attr(CPAttributeKeys.CONNECTION_ID).get();
        final ManagedSession session = current.sessions.get(identity.bridgeSessionId());
        return connectionId != null && session != null && session.channel() == channel
                && session.connectionId().equals(connectionId.toString())
                && session.xuid().equals(identity.xuid()) && this.registration == current;
    }

    /**
     * An immutable, read-only view of the live registration for the console
     * version query: the versions the bridge itself declared at registration
     * time. Null (no snapshot) means no live registration — the information
     * dies with the registration and is never retained after PROVIDER_STOPPING.
     * Contains no session secrets.
     */
    public record RegistrationSnapshot(UUID epoch, String registrationId,
                                       String bridgeVersion, String geyserVersion) {
    }

    /**
     * The live registration's version snapshot, or null when no provider is
     * registered (never registered, stopped, or replaced).
     */
    @Nullable
    public RegistrationSnapshot registrationSnapshot() {
        final Registration current = this.registration;
        return current == null ? null
                : new RegistrationSnapshot(current.epoch, current.registrationId,
                        current.bridgeVersion, current.geyserVersion);
    }

    private static final class Registration {
        final UUID epoch;
        final String registrationId;
        final Set<String> capabilities;
        final String bridgeVersion;
        final String geyserVersion;
        final Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler;
        final Map<String, ManagedSession> sessions = new ConcurrentHashMap<>();

        Registration(final UUID epoch, final String registrationId, final Set<String> capabilities,
                     final String bridgeVersion, final String geyserVersion,
                     final Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler) {
            this.epoch = epoch;
            this.registrationId = registrationId;
            this.capabilities = Set.copyOf(capabilities);
            this.bridgeVersion = bridgeVersion;
            this.geyserVersion = geyserVersion;
            this.handler = handler;
        }
    }

    private final Duration resolveTimeout;
    private final Duration validateTimeout;
    private final Duration disconnectTimeout;
    private final Supplier<String> runningVersion;

    private volatile Registration registration;
    private volatile DuplicateCandidateCoordinator duplicateCandidateCoordinator;
    /** Epochs that were stopped via PROVIDER_STOPPING and may never register again. */
    private final Set<UUID> retiredEpochs = ConcurrentHashMap.newKeySet();

    /** Production endpoint with the §4.4 default timeouts. */
    public BedrockBridgeEndpoint() {
        this(() -> null);
    }

    /** Production endpoint; {@code runningVersion} supplies the running ViaProxy version if resolvable. */
    public BedrockBridgeEndpoint(final Supplier<String> runningVersion) {
        this(DEFAULT_RESOLVE_TIMEOUT, DEFAULT_VALIDATE_TIMEOUT, DEFAULT_DISCONNECT_TIMEOUT, runningVersion);
    }

    /** Explicit timeouts (tests); production callers use the spec defaults instead. */
    public BedrockBridgeEndpoint(final Duration resolveTimeout, final Duration validateTimeout,
                                 final Duration disconnectTimeout) {
        this(resolveTimeout, validateTimeout, disconnectTimeout, () -> null);
    }

    private BedrockBridgeEndpoint(final Duration resolveTimeout, final Duration validateTimeout,
                                  final Duration disconnectTimeout, final Supplier<String> runningVersion) {
        this.resolveTimeout = Objects.requireNonNull(resolveTimeout, "resolveTimeout");
        this.validateTimeout = Objects.requireNonNull(validateTimeout, "validateTimeout");
        this.disconnectTimeout = Objects.requireNonNull(disconnectTimeout, "disconnectTimeout");
        this.runningVersion = Objects.requireNonNull(runningVersion, "runningVersion");
    }

    /**
     * registerBedrockBridgeV1: validates the descriptor and installs the single
     * provider. Never overwrites a live registration and never mutates any admin
     * switch; returns the REJECTED map with a reasonCode on every refusal.
     */
    public Map<String, Object> register(final Map<String, Object> descriptor,
                                        final Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler) {
        if (!CPConfig.GeyserSupport.enabled) {
            return rejected(REASON_DISABLED);
        }
        final Registration current = this.registration;
        if (current != null) {
            return rejected(REASON_PROVIDER_ALREADY_REGISTERED);
        }
        if (descriptor == null || handler == null) {
            return rejected(REASON_INVALID_REQUEST);
        }
        final Object protocolVersion = descriptor.get("protocolVersion");
        if (!(protocolVersion instanceof Integer)) {
            return rejected(REASON_INVALID_REQUEST);
        }
        if ((Integer) protocolVersion != PROTOCOL_VERSION) {
            return rejected(REASON_UNSUPPORTED_PROTOCOL);
        }
        if (!PROVIDER_ID.equals(descriptor.get("providerId"))) {
            return rejected(REASON_INVALID_REQUEST);
        }
        final UUID epoch = parseEpoch(descriptor.get("providerEpoch"));
        if (epoch == null) {
            return rejected(REASON_INVALID_REQUEST);
        }
        if (this.retiredEpochs.contains(epoch)) {
            // PROVIDER_STOPPING retired this epoch: reusing it after a restart would
            // revive old connection proofs (§4.4), so it is an invalid request here.
            LOGGER.warn("Bedrock bridge registration reuses the epoch retired by PROVIDER_STOPPING");
            return rejected(REASON_INVALID_REQUEST);
        }
        for (final String versionField : List.of("bridgeVersion", "geyserVersion", "viaproxyVersion")) {
            if (!(descriptor.get(versionField) instanceof final String version) || version.isBlank()) {
                return rejected(REASON_INVALID_REQUEST);
            }
        }
        final String declaredHost = (String) descriptor.get("viaproxyVersion");
        final String running = this.runningVersion.get();
        if (running != null && !running.isBlank() && !running.equals(declaredHost)) {
            // §2.1: a version mismatch must be refused, never guessed around.
            LOGGER.warn("Bedrock bridge declares host version {} but this process runs {}", declaredHost, running);
            return rejected(REASON_UNSUPPORTED_RUNTIME);
        }
        if (!(descriptor.get("capabilities") instanceof final List<?> capabilities)) {
            return rejected(REASON_INVALID_REQUEST);
        }
        for (final Object capability : capabilities) {
            if (!(capability instanceof String)) {
                return rejected(REASON_INVALID_REQUEST);
            }
        }
        for (final String required : REQUIRED_CAPABILITIES) {
            if (!capabilities.contains(required)) {
                return rejected(REASON_MISSING_CAPABILITY);
            }
        }

        final String registrationId = UUID.randomUUID().toString();
        synchronized (this) {
            if (this.registration != null) {
                // Lost a race against a second register call.
                return rejected(REASON_PROVIDER_ALREADY_REGISTERED);
            }
            this.registration = new Registration(epoch, registrationId, capabilities.stream()
                    .map(String.class::cast).collect(java.util.stream.Collectors.toSet()),
                    (String) descriptor.get("bridgeVersion"), (String) descriptor.get("geyserVersion"), handler);
        }
        // registrationId deliberately never appears in logs (§4.1).
        LOGGER.info("Bedrock bridge provider registered (epoch={}, bridgeVersion={}, geyserVersion={})",
                epoch, descriptor.get("bridgeVersion"), descriptor.get("geyserVersion"));
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "REGISTERED");
        response.put("protocolVersion", PROTOCOL_VERSION);
        response.put("registrationId", registrationId);
        return response;
    }

    /**
     * bedrockBridgeEventV1: routes a provider event. Calls from other (old or forged)
     * registrations answer STALE and never change state; field errors answer
     * REJECTED/INVALID_REQUEST (§4.3).
     */
    public CompletionStage<Map<String, Object>> handleEvent(final String registrationId,
                                                            final Map<String, Object> event) {
        final Registration current = this.registration;
        if (current == null || registrationId == null || !registrationId.equals(current.registrationId)) {
            return CompletableFuture.completedFuture(stale(current, event));
        }
        if (event == null || !(event.get("op") instanceof String)) {
            return CompletableFuture.completedFuture(rejectedEvent(current, null));
        }
        final Object protocolVersion = event.get("protocolVersion");
        if (!(protocolVersion instanceof Integer)) {
            return CompletableFuture.completedFuture(rejectedEvent(current, current.epoch));
        }
        if ((Integer) protocolVersion != PROTOCOL_VERSION) {
            return CompletableFuture.completedFuture(stale(current, event));
        }
        final UUID epoch = parseEpoch(event.get("providerEpoch"));
        if (epoch == null) {
            return CompletableFuture.completedFuture(rejectedEvent(current, current.epoch));
        }
        if (!epoch.equals(current.epoch)) {
            return CompletableFuture.completedFuture(stale(current, event));
        }

        return switch ((String) event.get("op")) {
            case "SESSION_CLOSED" -> this.sessionClosed(current, event);
            case "PROVIDER_STOPPING" -> this.providerStopping(current, event);
            case "VERIFIED_DUPLICATE_CANDIDATE" -> this.duplicateCandidate(current, event);
            default -> CompletableFuture.completedFuture(rejectedEvent(current, current.epoch));
        };
    }

    private CompletionStage<Map<String, Object>> sessionClosed(final Registration registration,
                                                               final Map<String, Object> event) {
        final Object connectionId = event.get("connectionId");
        final Object bridgeSessionId = event.get("bridgeSessionId");
        if (!(connectionId instanceof final String cid) || cid.isBlank()
                || !(bridgeSessionId instanceof final String sid) || !isUuid(sid)) {
            return CompletableFuture.completedFuture(rejectedEvent(registration, registration.epoch));
        }
        final ManagedSession managed = registration.sessions.get(sid);
        if (managed != null && managed.connectionId().equals(cid)) {
            registration.sessions.remove(sid);
        }
        // Idempotent: duplicate notifications replay safely (§4.3).
        return CompletableFuture.completedFuture(eventResponse(registration, "ACK", null));
    }

    private CompletionStage<Map<String, Object>> providerStopping(final Registration registration,
                                                                  final Map<String, Object> event) {
        if (!(event.get("reasonCode") instanceof final String reason) || reason.isBlank()) {
            return CompletableFuture.completedFuture(rejectedEvent(registration, registration.epoch));
        }
        synchronized (this) {
            if (this.registration == registration) {
                this.retiredEpochs.add(registration.epoch);
                this.registration = null;
            }
        }
        LOGGER.info("Bedrock bridge provider stopped (epoch={}, reason={})", registration.epoch, reason);
        return CompletableFuture.completedFuture(eventResponse(registration, "ACK", null));
    }

    private CompletionStage<Map<String, Object>> duplicateCandidate(final Registration registration,
                                                                    final Map<String, Object> event) {
        if (!registration.capabilities.contains(CAP_AUTHENTICATED_DUPLICATE_ADMISSION)) {
            return CompletableFuture.completedFuture(rejectedEvent(registration, registration.epoch));
        }
        final Object xuid = event.get("xuid");
        final Object oldSession = event.get("oldBridgeSessionId");
        final Object newSession = event.get("newBridgeSessionId");
        if (!(xuid instanceof final String x) || !isCanonicalXuid(x)
                || !(oldSession instanceof final String old) || !isUuid(old)
                || !(newSession instanceof final String newSid) || !isUuid(newSid)
                || old.equals(newSid)) {
            return CompletableFuture.completedFuture(rejectedEvent(registration, registration.epoch));
        }
        final DuplicateCandidateCoordinator coordinator = this.duplicateCandidateCoordinator;
        if (coordinator == null) {
            // Task 4's coordinator is not running: claim nothing, keep the extension's
            // own duplicate rejection in place (nobody is kicked).
            return CompletableFuture.completedFuture(stale(registration, event));
        }
        final CompletableFuture<Map<String, Object>> routed = new CompletableFuture<>();
        try {
            coordinator.onDuplicateCandidate(x, old, newSid).whenComplete((response, error) -> {
                if (error != null) {
                    routed.complete(denied(registration, newSid, "TIMEOUT"));
                    return;
                }
                if (response == null) {
                    routed.complete(denied(registration, newSid, "TIMEOUT"));
                    return;
                }
                final Map<String, Object> merged = new LinkedHashMap<>(response);
                // Carry-forward (task 2 review): the coordinator's answer is relayed only
                // when it is a usable READY or DENIED — anything else (hostile or
                // half-formed map content) is normalized to DENIED/TIMEOUT, never relayed.
                final Object status = merged.get("status");
                if (!"READY".equals(status) && !"DENIED".equals(status)) {
                    merged.put("status", "DENIED");
                    merged.put("reasonCode", "TIMEOUT");
                }
                if ("DENIED".equals(status) && !(merged.get("reasonCode") instanceof String)) {
                    merged.put("reasonCode", "TIMEOUT");
                }
                merged.put("protocolVersion", PROTOCOL_VERSION);
                merged.put("providerEpoch", registration.epoch.toString());
                merged.putIfAbsent("newBridgeSessionId", newSid);
                routed.complete(merged);
            });
        } catch (final Throwable t) {
            routed.complete(denied(registration, newSid, "TIMEOUT"));
        }
        return routed.orTimeout(this.disconnectTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> error == null ? response
                        : denied(registration, newSid, "TIMEOUT"));
    }

    private static Map<String, Object> denied(final Registration registration, final String newSessionId,
                                              final String reasonCode) {
        final Map<String, Object> response = eventResponse(registration, "DENIED", registration.epoch);
        response.put("reasonCode", reasonCode);
        response.put("newBridgeSessionId", newSessionId);
        return response;
    }

    /**
     * RESOLVE: asks the provider whether this exact c2p connection belongs to a
     * verified Bedrock session. Completes with a non-VERIFIED status (never
     * exceptionally) unless the provider answers VERIFIED for THIS connectionId in
     * time; a late answer cannot complete an already-timed-out stage, so it can
     * never grant an identity.
     */
    public CompletionStage<ResolveResult> resolve(final Channel channel, final SocketAddress rawLocalAddress,
                                                  final SocketAddress rawRemoteAddress, final UUID connectionId,
                                                  final UUID wireUuid, final String wireName) {
        final Registration registration = this.registration;
        if (registration == null) {
            return CompletableFuture.completedStage(ResolveResult.of(ResolveStatus.UNAVAILABLE, null));
        }
        final Map<String, Object> request = baseRequest("RESOLVE", registration);
        request.put("connectionId", connectionId.toString());
        request.put("channel", channel);
        if (rawLocalAddress != null) {
            request.put("rawLocalAddress", rawLocalAddress);
        }
        if (rawRemoteAddress != null) {
            request.put("rawRemoteAddress", rawRemoteAddress);
        }
        if (wireUuid != null) {
            request.put("wireUuid", wireUuid.toString());
        }
        if (wireName != null) {
            request.put("wireName", wireName);
        }
        return call(registration, request, this.resolveTimeout).handle((response, error) -> {
            if (error != null) {
                if (error instanceof TimeoutException) {
                    LOGGER.warn("Bedrock bridge RESOLVE for connection {} did not answer in {}s",
                            connectionId, this.resolveTimeout.toSeconds());
                    return ResolveResult.of(ResolveStatus.TIMEOUT, registration.epoch);
                }
                return ResolveResult.of(ResolveStatus.UNAVAILABLE, registration.epoch);
            }
            return mapResolveResponse(registration, request, response);
        });
    }

    private ResolveResult mapResolveResponse(final Registration registration, final Map<String, Object> request,
                                             final Object response) {
        try {
            final Map<?, ?> map = validatedResponse(registration, request, response,
                    Set.of("VERIFIED", "NO_MATCH", "REJECTED", "UNAVAILABLE"));
            if (map == null) {
                return ResolveResult.of(ResolveStatus.UNAVAILABLE, registration.epoch);
            }
            final UUID epoch = registration.epoch;
            return switch ((String) map.get("status")) {
                case "NO_MATCH" -> ResolveResult.of(ResolveStatus.NO_MATCH, epoch);
                case "REJECTED" -> ResolveResult.rejected(epoch, reasonCodeOf(map));
                case "UNAVAILABLE" -> ResolveResult.of(ResolveStatus.UNAVAILABLE, epoch);
                case "VERIFIED" -> {
                    final ResolvedBedrock identity = parseVerified(registration, request, map);
                    yield identity != null
                            ? new ResolveResult(ResolveStatus.VERIFIED, identity, epoch, null)
                            : ResolveResult.rejected(epoch, "INVALID_IDENTITY");
                }
                default -> ResolveResult.of(ResolveStatus.UNAVAILABLE, epoch);
            };
        } catch (final Throwable t) {
            // A hostile map may throw from get(); it must never grant anything.
            return ResolveResult.of(ResolveStatus.UNAVAILABLE, registration.epoch);
        }
    }

    private static String reasonCodeOf(final Map<?, ?> map) {
        final Object reason = map.get("reasonCode");
        return reason instanceof final String s && !s.isBlank() ? s : null;
    }

    /** Extracts and cross-checks the VERIFIED identity; null when untrustworthy. */
    private ResolvedBedrock parseVerified(final Registration registration, final Map<String, Object> request,
                                          final Map<?, ?> response) {
        final Object xuid = response.get("xuid");
        final Object username = response.get("bedrockUsername");
        final Object bridgeSessionId = response.get("bridgeSessionId");
        if (!(xuid instanceof final String x) || !isCanonicalXuid(x)
                || !(username instanceof final String name) || name.isBlank()
                || !(bridgeSessionId instanceof final String sid) || !isUuid(sid)) {
            LOGGER.warn("Bedrock bridge VERIFIED response for connection {} has unusable fields",
                    request.get("connectionId"));
            return null;
        }
        // Negative answers never carry a usable identity; VERIFIED registers the session.
        final Channel channel = (Channel) request.get("channel");
        final ManagedSession managed = new ManagedSession((String) request.get("connectionId"), x, channel);
        registration.sessions.put(sid, managed);
        channel.closeFuture().addListener(ignored -> registration.sessions.remove(sid, managed));
        DebugLog.log("Bedrock bridge verified connection {} as xuid {} (session {})",
                request.get("connectionId"), maskXuid(x), sid);
        return new ResolvedBedrock(x, name, sid);
    }

    /**
     * VALIDATE: confirms a previously verified session is still bound to the same
     * live Bedrock session and channel.
     */
    public CompletionStage<ValidationResult> validate(final String connectionId, final String bridgeSessionId,
                                                      final Channel channel) {
        final Registration registration = this.registration;
        if (registration == null) {
            return CompletableFuture.completedStage(new ValidationResult(ValidationStatus.UNAVAILABLE));
        }
        final Map<String, Object> request = baseRequest("VALIDATE", registration);
        request.put("connectionId", connectionId);
        request.put("bridgeSessionId", bridgeSessionId);
        request.put("channel", channel);
        return call(registration, request, this.validateTimeout).handle((response, error) -> {
            if (error != null) {
                return new ValidationResult(error instanceof TimeoutException
                        ? ValidationStatus.TIMEOUT : ValidationStatus.UNAVAILABLE);
            }
            try {
                final Map<?, ?> map = validatedResponse(registration, request, response,
                        Set.of("VALID", "INVALID", "UNAVAILABLE"));
                if (map == null) {
                    return new ValidationResult(ValidationStatus.UNAVAILABLE);
                }
                return switch ((String) map.get("status")) {
                    case "VALID" -> new ValidationResult(ValidationStatus.VALID);
                    case "INVALID" -> new ValidationResult(ValidationStatus.INVALID);
                    default -> new ValidationResult(ValidationStatus.UNAVAILABLE);
                };
            } catch (final Throwable t) {
                return new ValidationResult(ValidationStatus.UNAVAILABLE);
            }
        });
    }

    /**
     * DISCONNECT: asks the provider to close exactly the named old Bedrock session
     * (top-order flow, task 4). STALE must not change any state — the id may already
     * belong to a new session.
     */
    public CompletionStage<DisconnectResult> disconnect(final String connectionId, final String bridgeSessionId,
                                                        final DisconnectReason reason, final String message) {
        final Registration registration = this.registration;
        if (registration == null) {
            return CompletableFuture.completedStage(new DisconnectResult(DisconnectStatus.UNAVAILABLE));
        }
        final Map<String, Object> request = baseRequest("DISCONNECT", registration);
        request.put("connectionId", connectionId);
        request.put("bridgeSessionId", bridgeSessionId);
        request.put("reasonCode", reason.name());
        request.put("message", message);
        return call(registration, request, this.disconnectTimeout).handle((response, error) -> {
            if (error != null) {
                if (error instanceof TimeoutException) {
                    LOGGER.warn("Bedrock bridge DISCONNECT of session {} did not answer in {}s",
                            bridgeSessionId, this.disconnectTimeout.toSeconds());
                    return new DisconnectResult(DisconnectStatus.TIMEOUT);
                }
                return new DisconnectResult(DisconnectStatus.UNAVAILABLE);
            }
            try {
                final Map<?, ?> map = validatedResponse(registration, request, response,
                        Set.of("CLOSED", "ALREADY_CLOSED", "STALE", "UNAVAILABLE"));
                if (map == null) {
                    return new DisconnectResult(DisconnectStatus.UNAVAILABLE);
                }
                return switch ((String) map.get("status")) {
                    case "CLOSED" -> {
                        registration.sessions.remove(bridgeSessionId);
                        yield new DisconnectResult(DisconnectStatus.CLOSED);
                    }
                    case "ALREADY_CLOSED" -> {
                        registration.sessions.remove(bridgeSessionId);
                        yield new DisconnectResult(DisconnectStatus.ALREADY_CLOSED);
                    }
                    case "STALE" -> new DisconnectResult(DisconnectStatus.STALE);
                    default -> new DisconnectResult(DisconnectStatus.UNAVAILABLE);
                };
            } catch (final Throwable t) {
                return new DisconnectResult(DisconnectStatus.UNAVAILABLE);
            }
        });
    }

    /**
     * Dispatches the request to the provider with the response-side timeout guard.
     * The provider's answer is carried as an untyped {@link Object} until
     * {@link #checkResponse} validated it: a hostile stage may hold a non-Map value
     * despite its declared type, and any cast before validation could throw a
     * ClassCastException inside the completion bridge — silently killing the bridge
     * to {@code raw} and hanging us until the timeout.
     */
    private CompletionStage<Object> call(final Registration registration,
                                         final Map<String, Object> request,
                                         final Duration timeout) {
        final CompletableFuture<Object> raw = new CompletableFuture<>();
        final CompletionStage<?> providerStage;
        try {
            providerStage = registration.handler.apply(request);
        } catch (final Throwable t) {
            raw.completeExceptionally(t);
            return raw.orTimeout(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        if (providerStage == null) {
            raw.completeExceptionally(new IllegalStateException("bridge provider returned null"));
            return raw.orTimeout(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        providerStage.whenComplete((response, error) -> {
            if (error != null) {
                raw.completeExceptionally(error);
            } else {
                raw.complete(response);
            }
        });
        return raw.orTimeout(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Shared response validation: shape (the answer is untyped until here), epoch and
     * the §4.2 echo rules (connectionId/bridgeSessionId must be echoed verbatim).
     * Returns the validated map, or null when the answer is untrustworthy.
     */
    private Map<?, ?> validatedResponse(final Registration registration, final Map<String, Object> request,
                                        final Object response, final Set<String> allowedStatuses) {
        if (!(response instanceof final Map<?, ?> map)
                || !(map.get("status") instanceof final String status)
                || !allowedStatuses.contains(status)
                || !Integer.valueOf(PROTOCOL_VERSION).equals(map.get("protocolVersion"))) {
            LOGGER.warn("Bedrock bridge provider answered with a malformed response for op={}", request.get("op"));
            return null;
        }
        final UUID responseEpoch = parseEpoch(map.get("providerEpoch"));
        if (responseEpoch == null || !responseEpoch.equals(registration.epoch)) {
            return null;
        }
        if (request.containsKey("connectionId")
                && !request.get("connectionId").equals(map.get("connectionId"))) {
            // The response describes a different connection: it verifies nothing.
            return null;
        }
        if (request.containsKey("bridgeSessionId")
                && !request.get("bridgeSessionId").equals(map.get("bridgeSessionId"))) {
            return null;
        }
        return map;
    }

    /** True when the current registration has verified (and not lost) the session. */
    public boolean isSessionManaged(final String bridgeSessionId) {
        final Registration registration = this.registration;
        return registration != null && registration.sessions.containsKey(bridgeSessionId);
    }

    /**
     * The verified identity of a managed bridge session, or null when the current
     * registration has not verified it. The session-exclusivity coordinator (task 4)
     * reads this to bind a duplicate-candidate's old session to its ConnectPlus
     * connection id and XUID; the id is never treated as a Java UUID anywhere.
     */
    public ManagedSessionInfo managedSession(final String bridgeSessionId) {
        final Registration registration = this.registration;
        if (registration == null || bridgeSessionId == null) return null;
        final ManagedSession managed = registration.sessions.get(bridgeSessionId);
        return managed == null ? null : new ManagedSessionInfo(managed.connectionId(), managed.xuid());
    }

    /** Read-only view of one managed bridge session (the endpoint never hands out internals). */
    public record ManagedSessionInfo(String connectionId, String xuid) {
    }

    /** The epoch of the live registration; null when no provider is registered. */
    public UUID providerEpoch() {
        final Registration registration = this.registration;
        return registration == null ? null : registration.epoch;
    }

    /** Registers the per-XUID duplicate-login coordinator (task 4); null clears it. */
    public void setDuplicateCandidateCoordinator(final DuplicateCandidateCoordinator coordinator) {
        this.duplicateCandidateCoordinator = coordinator;
    }

    private static Map<String, Object> baseRequest(final String op, final Registration registration) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", op);
        request.put("protocolVersion", PROTOCOL_VERSION);
        request.put("providerEpoch", registration.epoch.toString());
        return request;
    }

    private static Map<String, Object> rejected(final String reasonCode) {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "REJECTED");
        response.put("reasonCode", reasonCode);
        return response;
    }

    private static Map<String, Object> eventResponse(final Registration registration, final String status,
                                                     final UUID epoch) {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("protocolVersion", PROTOCOL_VERSION);
        if (epoch != null) {
            response.put("providerEpoch", epoch.toString());
        }
        return response;
    }

    private static Map<String, Object> rejectedEvent(final Registration registration, final UUID epoch) {
        final Map<String, Object> response = eventResponse(registration, "REJECTED", epoch);
        response.put("reasonCode", REASON_INVALID_REQUEST);
        return response;
    }

    private static Map<String, Object> stale(final Registration registration, final Map<String, Object> event) {
        final UUID echo = registration != null ? registration.epoch
                : event != null ? parseEpoch(event.get("providerEpoch")) : null;
        return eventResponse(registration, "STALE", echo);
    }

    private static UUID parseEpoch(final Object value) {
        if (!(value instanceof final String s) || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** Canonical XUID check without parsing into a signed long (ProfileKey's rule). */
    private static boolean isCanonicalXuid(final String value) {
        try {
            ProfileKey.bedrockProfile(value);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isUuid(final String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /** Diagnostics-only XUID prefix; full XUIDs never reach the log. */
    private static String maskXuid(final String xuid) {
        return xuid.length() <= 2 ? "**" : xuid.substring(0, 2) + "***";
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockBridgeEndpoint.class);
}
