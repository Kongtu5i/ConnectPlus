package dev.connectplus.bridge;

import dev.connectplus.config.CPConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bridge protocol v1 endpoint behaviour (docs/integrations/geyser-bridge-v1.md §4):
 * registration validation, event routing and response-side enforcement. The
 * "provider handler" is the external Geyser extension side of the protocol and is
 * stubbed here; the stubs only prove ConnectPlus's half of the contract.
 */
class BedrockBridgeEndpointTest {

    private static final List<String> REQUIRED_CAPABILITIES =
            List.of("verified-xuid", "exact-channel-binding", "targeted-disconnect", "authenticated-duplicate-admission");

    private static final String VERIFIED_XUID = "18446744073709551615";
    private static final String BRIDGE_SESSION_A = UUID.randomUUID().toString();
    private static final String BRIDGE_SESSION_B = UUID.randomUUID().toString();

    private boolean geyserSupportWasEnabled;

    @BeforeEach
    void enableGeyserSupport() {
        this.geyserSupportWasEnabled = CPConfig.GeyserSupport.enabled;
        CPConfig.GeyserSupport.enabled = true;
    }

    @AfterEach
    void restoreGeyserSupport() {
        CPConfig.GeyserSupport.enabled = this.geyserSupportWasEnabled;
    }

    /** The live registration of a test endpoint: id and epoch as handed out by register(). */
    private record LiveRegistration(String registrationId, String providerEpoch) {
    }

    /** A descriptor that satisfies every §4.1 requirement. */
    private static Map<String, Object> descriptor() {
        return descriptor(UUID.randomUUID().toString());
    }

    private static Map<String, Object> descriptor(final String providerEpoch) {
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("protocolVersion", 1);
        d.put("providerId", "connectplus-geyser-bridge");
        d.put("providerEpoch", providerEpoch);
        d.put("bridgeVersion", "1.0.0");
        d.put("geyserVersion", "2.8.2");
        d.put("viaproxyVersion", "3.4.13");
        d.put("capabilities", new ArrayList<>(REQUIRED_CAPABILITIES));
        return d;
    }

    /** Registers a fresh provider with a known epoch and returns the live registration. */
    private static LiveRegistration register(final BedrockBridgeEndpoint endpoint, final StubProvider provider) {
        final String epoch = UUID.randomUUID().toString();
        final Map<String, Object> response = endpoint.register(descriptor(epoch), provider);
        assertEquals("REGISTERED", status(response), () -> "full response: " + response);
        return new LiveRegistration((String) response.get("registrationId"), epoch);
    }

    /**
     * Minimal well-shaped provider response: echoes protocol/epoch and the request's
     * connectionId/bridgeSessionId as §4.2 requires, then applies the extra fields.
     */
    private static Map<String, Object> response(final Map<String, Object> request, final String status,
                                                final Object... extra) {
        final Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("protocolVersion", 1);
        r.put("providerEpoch", request.get("providerEpoch"));
        if (request.containsKey("connectionId")) {
            r.put("connectionId", request.get("connectionId"));
        }
        if (request.containsKey("bridgeSessionId")) {
            r.put("bridgeSessionId", request.get("bridgeSessionId"));
        }
        for (int i = 0; i < extra.length; i += 2) {
            r.put((String) extra[i], extra[i + 1]);
        }
        return r;
    }

    private static Map<String, Object> unavailable(final Map<String, Object> request) {
        return response(request, "UNAVAILABLE");
    }

    private static String status(final Map<String, Object> response) {
        return (String) response.get("status");
    }

    private static String status(final CompletionStage<Map<String, Object>> responseStage) {
        return status(await(responseStage));
    }

    private static String reason(final Map<String, Object> response) {
        return (String) response.get("reasonCode");
    }

    private static String reason(final CompletionStage<Map<String, Object>> responseStage) {
        return reason(await(responseStage));
    }

    private static void assertRejected(final Map<String, Object> response, final String reasonCode) {
        assertEquals("REJECTED", status(response), () -> "full response: " + response);
        assertEquals(reasonCode, reason(response));
    }

    private static void assertRejected(final CompletionStage<Map<String, Object>> responseStage,
                                       final String reasonCode) {
        assertRejected(await(responseStage), reasonCode);
    }

    /** Provider stub: records every request, answers through a replaceable responder. */
    static final class StubProvider implements Function<Map<String, Object>, CompletionStage<Map<String, Object>>> {
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        volatile Function<Map<String, Object>, CompletionStage<Map<String, Object>>> responder =
                request -> CompletableFuture.completedFuture(unavailable(request));

        @Override
        public CompletionStage<Map<String, Object>> apply(final Map<String, Object> request) {
            this.requests.add(request);
            return this.responder.apply(request);
        }

        String lastEpoch() {
            return (String) this.requests.get(this.requests.size() - 1).get("providerEpoch");
        }
    }

    private static <T> T await(final CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException("the bridge stage did not complete", e);
        }
    }

    private static Map<String, Object> event(final LiveRegistration registration, final String op) {
        final Map<String, Object> event = new LinkedHashMap<>();
        event.put("op", op);
        event.put("protocolVersion", 1);
        event.put("providerEpoch", registration.providerEpoch());
        return event;
    }

    /** A well-formed PROVIDER_STOPPING event. */
    private static Map<String, Object> stoppingEvent(final LiveRegistration registration) {
        final Map<String, Object> event = event(registration, "PROVIDER_STOPPING");
        event.put("reasonCode", "EXTENSION_DISABLED");
        return event;
    }

    @Test
    void validDescriptorRegistersExactlyOneProvider() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final String epoch = UUID.randomUUID().toString();
        final Map<String, Object> response = endpoint.register(descriptor(epoch), provider);
        assertEquals("REGISTERED", status(response));
        assertEquals(1, response.get("protocolVersion"));
        final Object registrationId = response.get("registrationId");
        assertTrue(registrationId instanceof String, "registrationId must be a String");
        final UUID parsedRegistrationId = UUID.fromString((String) registrationId);
        assertEquals(parsedRegistrationId.toString(), registrationId);

        // Only one provider: a retry (even with the same epoch) must not overwrite
        // the live registration.
        assertRejected(endpoint.register(descriptor(epoch), provider), "PROVIDER_ALREADY_REGISTERED");
        // The original registration is still the live one and still answers events.
        final LiveRegistration live = new LiveRegistration((String) registrationId, epoch);
        assertEquals("ACK", status(endpoint.handleEvent(live.registrationId(),
                stoppingEvent(live))));
        assertEquals("STALE", status(endpoint.handleEvent(live.registrationId(),
                stoppingEvent(live))),
                "after PROVIDER_STOPPING the registration is dead: a replayed event must be STALE");
    }

    @Test
    void registrationSnapshotExposesVersionsAndInvalidatesOnStop() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        assertNull(endpoint.registrationSnapshot(), "Without a registration there is no version snapshot");

        final LiveRegistration live = register(endpoint, new StubProvider());
        final BedrockBridgeEndpoint.RegistrationSnapshot snapshot = endpoint.registrationSnapshot();
        assertNotNull(snapshot, "A live registration exposes its version snapshot");
        assertEquals(UUID.fromString(live.providerEpoch()), snapshot.epoch());
        assertEquals("1.0.0", snapshot.bridgeVersion(), "The descriptor's bridge version is exposed");
        assertEquals("2.8.2", snapshot.geyserVersion(), "The descriptor's Geyser version is exposed");
        assertNotNull(snapshot.registrationId());

        endpoint.handleEvent(live.registrationId(), stoppingEvent(live)).toCompletableFuture().join();
        assertNull(endpoint.registrationSnapshot(),
                "After PROVIDER_STOPPING the registration and its version information are invalid");
    }

    @Test
    void registrationIsRejectedWhileGeyserSupportIsDisabled() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        CPConfig.GeyserSupport.enabled = false;
        assertRejected(endpoint.register(descriptor(), new StubProvider()), "DISABLED");
        CPConfig.GeyserSupport.enabled = true;
        assertEquals("REGISTERED", status(endpoint.register(descriptor(), new StubProvider())));
    }

    @Test
    void registeringNeverTouchesTheAdminAccountLoginSwitch() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        CPConfig.allowAccountLogin = true;
        try {
            assertEquals("REGISTERED", status(endpoint.register(descriptor(), new StubProvider())));
            assertEquals(true, CPConfig.allowAccountLogin,
                    "enabling the bridge must not auto-flip the admin account login switch");
            CPConfig.allowAccountLogin = false;
            assertRejected(endpoint.register(descriptor(), new StubProvider()), "PROVIDER_ALREADY_REGISTERED");
            assertEquals(false, CPConfig.allowAccountLogin,
                    "a rejected registration must not enable the admin account login switch either");
        } finally {
            CPConfig.allowAccountLogin = true;
        }
    }

    @Test
    void malformedDescriptorsAreRejectedSafely() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();

        // Wrong value types must be rejected without ClassCastException leaking.
        final Map<String, Object> stringProtocol = descriptor();
        stringProtocol.put("protocolVersion", "1");
        assertRejected(endpoint.register(stringProtocol, provider), "INVALID_REQUEST");

        final Map<String, Object> floatProtocol = descriptor();
        floatProtocol.put("protocolVersion", 2.0);
        assertRejected(endpoint.register(floatProtocol, provider), "INVALID_REQUEST");

        final Map<String, Object> wrongProviderId = descriptor();
        wrongProviderId.put("providerId", "someone-elses-bridge");
        assertRejected(endpoint.register(wrongProviderId, provider), "INVALID_REQUEST");

        final Map<String, Object> intEpoch = descriptor();
        intEpoch.put("providerEpoch", 12345);
        assertRejected(endpoint.register(intEpoch, provider), "INVALID_REQUEST");

        final Map<String, Object> garbageEpoch = descriptor();
        garbageEpoch.put("providerEpoch", "not-a-uuid");
        assertRejected(endpoint.register(garbageEpoch, provider), "INVALID_REQUEST");

        final Map<String, Object> intVersion = descriptor();
        intVersion.put("bridgeVersion", 100);
        assertRejected(endpoint.register(intVersion, provider), "INVALID_REQUEST");

        final Map<String, Object> missingVersion = descriptor();
        missingVersion.remove("geyserVersion");
        assertRejected(endpoint.register(missingVersion, provider), "INVALID_REQUEST");

        final Map<String, Object> blankVersion = descriptor();
        blankVersion.put("viaproxyVersion", "  ");
        assertRejected(endpoint.register(blankVersion, provider), "INVALID_REQUEST");

        final Map<String, Object> capabilitiesString = descriptor();
        capabilitiesString.put("capabilities", "verified-xuid");
        assertRejected(endpoint.register(capabilitiesString, provider), "INVALID_REQUEST");

        final Map<String, Object> capabilitiesWithInteger = descriptor();
        capabilitiesWithInteger.put("capabilities", List.of("verified-xuid", 2, "exact-channel-binding",
                "targeted-disconnect", "authenticated-duplicate-admission"));
        assertRejected(endpoint.register(capabilitiesWithInteger, provider), "INVALID_REQUEST");

        assertRejected(endpoint.register(null, provider), "INVALID_REQUEST");
        assertRejected(endpoint.register(descriptor(), null), "INVALID_REQUEST");

        assertEquals(0, provider.requests.size(), "a rejected registration must never reach the provider handler");
    }

    @Test
    void unsupportedProtocolAndMissingCapabilitiesAreDistinguished() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();

        final Map<String, Object> protocol2 = descriptor();
        protocol2.put("protocolVersion", 2);
        assertRejected(endpoint.register(protocol2, provider), "UNSUPPORTED_PROTOCOL");

        final Map<String, Object> missingCapability = descriptor();
        missingCapability.put("capabilities",
                new ArrayList<>(List.of("verified-xuid", "targeted-disconnect", "authenticated-duplicate-admission")));
        assertRejected(endpoint.register(missingCapability, provider), "MISSING_CAPABILITY");

        final Map<String, Object> emptyCapabilities = descriptor();
        emptyCapabilities.put("capabilities", new ArrayList<String>());
        assertRejected(endpoint.register(emptyCapabilities, provider), "MISSING_CAPABILITY");
    }

    @Test
    void runtimeVersionMismatchIsRejected() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint(() -> "3.4.13");
        final Map<String, Object> wrongHost = descriptor();
        wrongHost.put("viaproxyVersion", "3.4.12");
        assertRejected(endpoint.register(wrongHost, new StubProvider()), "UNSUPPORTED_RUNTIME");

        assertEquals("REGISTERED", status(endpoint.register(descriptor(), new StubProvider())),
                "the declared host version matching the running one registers fine");
    }

    @Test
    void anEpochRetiredThroughProviderStoppingCannotRegisterAgain() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final LiveRegistration first = register(endpoint, provider);

        // Stop the provider through the event the extension sends on disable.
        assertEquals("ACK", status(endpoint.handleEvent(first.registrationId(),
                stoppingEvent(first))));

        final Map<String, Object> sameEpochAgain = descriptor(first.providerEpoch());
        assertRejected(endpoint.register(sameEpochAgain, provider), "INVALID_REQUEST");

        assertEquals("REGISTERED", status(endpoint.register(descriptor(), provider)),
                "a genuinely new epoch (extension restart) registers again");
    }

    @Test
    void eventsFromUnknownRegistrationsAreStaleAndChangeNothing() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();

        assertEquals("STALE", status(endpoint.handleEvent(UUID.randomUUID().toString(),
                event(new LiveRegistration("irrelevant", UUID.randomUUID().toString()), "PROVIDER_STOPPING"))));

        final BedrockBridgeEndpoint endpoint2 = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final LiveRegistration live = register(endpoint2, provider);
        // A different registrationId (old or forged) is stale even with valid fields.
        assertEquals("STALE", status(endpoint2.handleEvent(UUID.randomUUID().toString(),
                event(live, "PROVIDER_STOPPING"))));

        // A mismatched providerEpoch must not affect the live registration either.
        final Map<String, Object> wrongEpoch = event(live, "SESSION_CLOSED");
        wrongEpoch.put("providerEpoch", UUID.randomUUID().toString());
        wrongEpoch.put("connectionId", UUID.randomUUID().toString());
        wrongEpoch.put("bridgeSessionId", BRIDGE_SESSION_A);
        assertEquals("STALE", status(endpoint2.handleEvent(live.registrationId(), wrongEpoch)));
        assertFalse(endpoint2.isSessionManaged(BRIDGE_SESSION_A));
        assertNotNull(endpoint2.providerEpoch(), "the live registration must survive stale calls");
    }

    @Test
    void sessionClosedInvalidatesOnlyTheNamedSessionAndReplaysSafely() throws Exception {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final LiveRegistration live = register(endpoint, provider);
        final String connectionA = UUID.randomUUID().toString();
        final String connectionB = UUID.randomUUID().toString();

        provider.responder = request -> {
            if ("RESOLVE".equals(request.get("op"))) {
                final String connectionId = (String) request.get("connectionId");
                return CompletableFuture.completedFuture(response(request, "VERIFIED",
                        "xuid", VERIFIED_XUID, "bedrockUsername", "BedrockPlayer",
                        "bridgeSessionId", connectionId.equals(connectionA) ? BRIDGE_SESSION_A : BRIDGE_SESSION_B));
            }
            return CompletableFuture.completedFuture(unavailable(request));
        };
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.VERIFIED,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionA), null, null)).status());
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.VERIFIED,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionB), null, null)).status());
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_A));
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B));

        final Map<String, Object> closedA = event(live, "SESSION_CLOSED");
        closedA.put("connectionId", connectionA);
        closedA.put("bridgeSessionId", BRIDGE_SESSION_A);
        assertEquals("ACK", status(endpoint.handleEvent(live.registrationId(), closedA)));
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A), "only the named session is invalidated");
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B));

        // Duplicate notifications replay safely.
        assertEquals("ACK", status(endpoint.handleEvent(live.registrationId(), closedA)));
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A));
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B));

        // Malformed field errors never invalidate anything.
        final Map<String, Object> missingFields = event(live, "SESSION_CLOSED");
        assertRejected(endpoint.handleEvent(live.registrationId(), missingFields), "INVALID_REQUEST");
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B));

        final Map<String, Object> wrongTypes = event(live, "SESSION_CLOSED");
        wrongTypes.put("connectionId", 42);
        wrongTypes.put("bridgeSessionId", BRIDGE_SESSION_B);
        assertRejected(endpoint.handleEvent(live.registrationId(), wrongTypes), "INVALID_REQUEST");
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B));

        // A stopping event without its reasonCode is a field error, not a stop.
        final Map<String, Object> malformedStop = event(live, "PROVIDER_STOPPING");
        assertRejected(endpoint.handleEvent(live.registrationId(), malformedStop), "INVALID_REQUEST");
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B), "the provider must still be live");
    }

    @Test
    void providerStoppingInvalidatesTheRegistration() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final LiveRegistration live = register(endpoint, provider);
        assertNotNull(endpoint.providerEpoch());

        assertEquals("ACK", status(endpoint.handleEvent(live.registrationId(),
                stoppingEvent(live))));
        assertNull(endpoint.providerEpoch(), "PROVIDER_STOPPING invalidates the registration");
        // And the dead registration's id no longer answers events.
        assertEquals("STALE", status(endpoint.handleEvent(live.registrationId(),
                stoppingEvent(live))));
    }

    @Test
    void duplicateCandidateRequiresCoordination() throws Exception {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final LiveRegistration live = register(endpoint, provider);

        final Map<String, Object> candidate = event(live, "VERIFIED_DUPLICATE_CANDIDATE");
        candidate.put("xuid", VERIFIED_XUID);
        candidate.put("oldBridgeSessionId", BRIDGE_SESSION_A);
        candidate.put("newBridgeSessionId", BRIDGE_SESSION_B);

        // No coordinator registered yet (task 4's serial per-XUID coordinator): stale,
        // so the extension keeps its own duplicate rejection and nobody is kicked.
        assertEquals("STALE", status(endpoint.handleEvent(live.registrationId(), candidate)));

        // Malformed candidates are field errors.
        final Map<String, Object> badXuid = new LinkedHashMap<>(candidate);
        badXuid.put("xuid", "0123");
        assertRejected(endpoint.handleEvent(live.registrationId(), badXuid), "INVALID_REQUEST");
        final Map<String, Object> sameSessions = new LinkedHashMap<>(candidate);
        sameSessions.put("oldBridgeSessionId", BRIDGE_SESSION_B);
        assertRejected(endpoint.handleEvent(live.registrationId(), sameSessions), "INVALID_REQUEST");
        final Map<String, Object> intXuid = new LinkedHashMap<>(candidate);
        intXuid.put("xuid", 4242);
        assertRejected(endpoint.handleEvent(live.registrationId(), intXuid), "INVALID_REQUEST");

        // With a coordinator the event is routed and its answer passed through.
        endpoint.setDuplicateCandidateCoordinator((xuid, oldSession, newSession) -> {
            assertEquals(VERIFIED_XUID, xuid);
            assertEquals(BRIDGE_SESSION_A, oldSession);
            assertEquals(BRIDGE_SESSION_B, newSession);
            final Map<String, Object> ready = new LinkedHashMap<>();
            ready.put("status", "READY");
            return CompletableFuture.completedFuture(ready);
        });
        final Map<String, Object> routed = await(endpoint.handleEvent(live.registrationId(), candidate));
        assertEquals("READY", status(routed));
        assertEquals(1, routed.get("protocolVersion"));
        assertEquals(live.providerEpoch(), routed.get("providerEpoch"));
        assertEquals(BRIDGE_SESSION_B, routed.get("newBridgeSessionId"),
                "duplicate-login responses must echo the new session id");

        // The coordinator's DENIED passes through with its reasonCode.
        endpoint.setDuplicateCandidateCoordinator((xuid, oldSession, newSession) -> {
            final Map<String, Object> denied = new LinkedHashMap<>();
            denied.put("status", "DENIED");
            denied.put("reasonCode", "OLD_SESSION_NOT_MANAGED");
            return CompletableFuture.completedFuture(denied);
        });
        final Map<String, Object> denied = await(endpoint.handleEvent(live.registrationId(), candidate));
        assertEquals("DENIED", status(denied));
        assertEquals("OLD_SESSION_NOT_MANAGED", reason(denied));
    }

    @Test
    void resolveMapsProviderResponsesWithoutTrustingThem() throws Exception {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final LiveRegistration live = register(endpoint, provider);
        final String connectionId = UUID.randomUUID().toString();

        provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                "xuid", VERIFIED_XUID, "bedrockUsername", "BedrockPlayer", "bridgeSessionId", BRIDGE_SESSION_A));
        final BedrockBridgeEndpoint.ResolveResult verified =
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.VERIFIED, verified.status());
        assertNotNull(verified.identity());
        assertEquals(VERIFIED_XUID, verified.identity().xuid());
        assertEquals("BedrockPlayer", verified.identity().bedrockUsername());
        assertEquals(BRIDGE_SESSION_A, verified.identity().bridgeSessionId());
        assertEquals(live.providerEpoch(), verified.providerEpoch().toString(),
                "the identity snapshot records the epoch of the registration that verified it");
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_A), "a VERIFIED session becomes managed");

        provider.responder = request -> CompletableFuture.completedFuture(response(request, "NO_MATCH"));
        final BedrockBridgeEndpoint.ResolveResult noMatch =
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.NO_MATCH, noMatch.status());
        assertNull(noMatch.identity(), "NO_MATCH must not carry any identity");

        provider.responder = request -> CompletableFuture.completedFuture(response(request, "REJECTED"));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.REJECTED,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());

        // Provider throws synchronously / returns null / returns garbage: unavailable.
        provider.responder = request -> {
            throw new IllegalStateException("provider exploded");
        };
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.UNAVAILABLE,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        provider.responder = request -> null;
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.UNAVAILABLE,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        provider.responder = request -> (CompletionStage<Map<String, Object>>) null;
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.UNAVAILABLE,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        provider.responder = request -> (CompletionStage<Map<String, Object>>) (CompletionStage<?>)
                CompletableFuture.completedFuture("not-a-map");
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.UNAVAILABLE,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        provider.responder = request -> CompletableFuture.<Map<String, Object>>completedFuture(null);
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.UNAVAILABLE,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
    }

    @Test
    void resolveRejectsSpoofedOrUntrustedResponses() throws Exception {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        register(endpoint, provider);
        final String connectionId = UUID.randomUUID().toString();
        final String otherConnection = UUID.randomUUID().toString();

        // The response claims a different connection: it must never verify this one.
        provider.responder = request -> {
            final Map<String, Object> r = response(request, "VERIFIED",
                    "xuid", VERIFIED_XUID, "bedrockUsername", "BedrockPlayer", "bridgeSessionId", BRIDGE_SESSION_A);
            r.put("connectionId", otherConnection);
            return CompletableFuture.completedFuture(r);
        };
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.UNAVAILABLE,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A));

        // A response from another provider epoch (stale registration) is ignored.
        provider.responder = request -> {
            final Map<String, Object> r = response(request, "VERIFIED",
                    "xuid", VERIFIED_XUID, "bedrockUsername", "BedrockPlayer", "bridgeSessionId", BRIDGE_SESSION_A);
            r.put("providerEpoch", UUID.randomUUID().toString());
            return CompletableFuture.completedFuture(r);
        };
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.UNAVAILABLE,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A));

        // VERIFIED with a non-canonical XUID is rejected, never turned into an identity.
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                "xuid", "0123", "bedrockUsername", "BedrockPlayer", "bridgeSessionId", BRIDGE_SESSION_A));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.REJECTED,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                "xuid", "18446744073709551616", "bedrockUsername", "BedrockPlayer",
                "bridgeSessionId", BRIDGE_SESSION_A));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.REJECTED,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        // VERIFIED without the mandatory display name or session id.
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                "xuid", VERIFIED_XUID, "bridgeSessionId", BRIDGE_SESSION_A));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.REJECTED,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                "xuid", VERIFIED_XUID, "bedrockUsername", "BedrockPlayer"));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.REJECTED,
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null)).status());
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A));
    }

    @Test
    void resolveTimesOutAndLateResponsesChangeNothing() throws Exception {
        final BedrockBridgeEndpoint endpoint =
                new BedrockBridgeEndpoint(Duration.ofMillis(80), Duration.ofMillis(80), Duration.ofMillis(80));
        final StubProvider provider = new StubProvider();
        register(endpoint, provider);
        final CompletableFuture<Map<String, Object>> lateResponse = new CompletableFuture<>();
        provider.responder = request -> lateResponse;

        final BedrockBridgeEndpoint.ResolveResult timedOut =
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.randomUUID(), null, null));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.TIMEOUT, timedOut.status());
        assertNull(timedOut.identity());

        // The provider answers after the timeout: the late VERIFIED response must not
        // register the session or produce an identity.
        lateResponse.complete(response(provider.requests.get(0), "VERIFIED",
                "xuid", VERIFIED_XUID, "bedrockUsername", "LatePlayer", "bridgeSessionId", BRIDGE_SESSION_A));
        Thread.sleep(100);
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A), "a late response must not change any state");

        // A fresh, timely connection still resolves normally.
        provider.responder = request1 -> CompletableFuture.completedFuture(response(request1, "VERIFIED",
                "xuid", VERIFIED_XUID, "bedrockUsername", "OnTimePlayer", "bridgeSessionId", BRIDGE_SESSION_B));
        final BedrockBridgeEndpoint.ResolveResult fresh =
                await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.randomUUID(), null, null));
        assertEquals(BedrockBridgeEndpoint.ResolveStatus.VERIFIED, fresh.status());
        assertEquals("OnTimePlayer", fresh.identity().bedrockUsername());
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B));
    }

    @Test
    void specTimeoutsAreThreeAndFiveSeconds() {
        assertEquals(Duration.ofSeconds(3), BedrockBridgeEndpoint.DEFAULT_RESOLVE_TIMEOUT);
        assertEquals(Duration.ofSeconds(3), BedrockBridgeEndpoint.DEFAULT_VALIDATE_TIMEOUT);
        assertEquals(Duration.ofSeconds(5), BedrockBridgeEndpoint.DEFAULT_DISCONNECT_TIMEOUT);
    }

    @Test
    void disconnectTargetsOnlyTheNamedSession() throws Exception {
        final BedrockBridgeEndpoint endpoint =
                new BedrockBridgeEndpoint(Duration.ofMillis(120), Duration.ofMillis(120), Duration.ofMillis(120));
        final StubProvider provider = new StubProvider();
        register(endpoint, provider);
        final String connectionId = UUID.randomUUID().toString();

        // Establish a managed session first.
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                "xuid", VERIFIED_XUID, "bedrockUsername", "BedrockPlayer", "bridgeSessionId", BRIDGE_SESSION_A));
        await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null));
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_A));

        provider.responder = request -> CompletableFuture.completedFuture(response(request, "CLOSED"));
        assertEquals(BedrockBridgeEndpoint.DisconnectStatus.CLOSED,
                await(endpoint.disconnect(connectionId, BRIDGE_SESSION_A,
                        BedrockBridgeEndpoint.DisconnectReason.REPLACED, "msg")).status());
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A), "CLOSED removes the managed session");

        // ALREADY_CLOSED only answers for a session that is really gone.
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "ALREADY_CLOSED"));
        assertEquals(BedrockBridgeEndpoint.DisconnectStatus.ALREADY_CLOSED,
                await(endpoint.disconnect(connectionId, BRIDGE_SESSION_A,
                        BedrockBridgeEndpoint.DisconnectReason.BRIDGE_UNAVAILABLE, "msg")).status());
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A));

        // STALE means the id now belongs to a different (new) session: no state change.
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                "xuid", VERIFIED_XUID, "bedrockUsername", "BedrockPlayer", "bridgeSessionId", BRIDGE_SESSION_B));
        await(endpoint.resolve(new EmbeddedChannel(), null, null, UUID.fromString(connectionId), null, null));
        provider.responder = request -> CompletableFuture.completedFuture(response(request, "STALE"));
        assertEquals(BedrockBridgeEndpoint.DisconnectStatus.STALE,
                await(endpoint.disconnect(connectionId, BRIDGE_SESSION_B,
                        BedrockBridgeEndpoint.DisconnectReason.REPLACED, "msg")).status());
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B), "a STALE answer must not unregister the session");

        // Disconnect times out at the disconnect deadline; a late CLOSED changes nothing.
        final CompletableFuture<Map<String, Object>> never = new CompletableFuture<>();
        provider.responder = request -> never;
        assertEquals(BedrockBridgeEndpoint.DisconnectStatus.TIMEOUT,
                await(endpoint.disconnect(connectionId, BRIDGE_SESSION_B,
                        BedrockBridgeEndpoint.DisconnectReason.REPLACED, "msg")).status());
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B));
        never.complete(response(provider.requests.get(provider.requests.size() - 1), "CLOSED"));
        Thread.sleep(100);
        assertTrue(endpoint.isSessionManaged(BRIDGE_SESSION_B), "a late DISCONNECT answer must not change any state");
    }

    @Test
    void validateAsksTheProviderAboutALiveBinding() throws Exception {
        final BedrockBridgeEndpoint endpoint =
                new BedrockBridgeEndpoint(Duration.ofMillis(120), Duration.ofMillis(120), Duration.ofMillis(120));
        final StubProvider provider = new StubProvider();
        register(endpoint, provider);

        provider.responder = request -> {
            assertEquals("VALIDATE", request.get("op"));
            assertEquals(BRIDGE_SESSION_A, request.get("bridgeSessionId"));
            return CompletableFuture.completedFuture(response(request, "VALID"));
        };
        assertEquals(BedrockBridgeEndpoint.ValidationStatus.VALID,
                await(endpoint.validate(UUID.randomUUID().toString(), BRIDGE_SESSION_A, new EmbeddedChannel())).status());

        provider.responder = request -> CompletableFuture.completedFuture(response(request, "INVALID"));
        assertEquals(BedrockBridgeEndpoint.ValidationStatus.INVALID,
                await(endpoint.validate(UUID.randomUUID().toString(), BRIDGE_SESSION_A, new EmbeddedChannel())).status());

        provider.responder = request -> CompletableFuture.completedFuture(response(request, "UNAVAILABLE"));
        assertEquals(BedrockBridgeEndpoint.ValidationStatus.UNAVAILABLE,
                await(endpoint.validate(UUID.randomUUID().toString(), BRIDGE_SESSION_A, new EmbeddedChannel())).status());
    }

    @Test
    void requestsCarryTheProtocolFieldsAndTheRawConnectionData() throws Exception {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final LiveRegistration live = register(endpoint, provider);
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UUID connectionId = UUID.randomUUID();
        final String wireUuid = UUID.randomUUID().toString();

        provider.responder = request -> CompletableFuture.completedFuture(response(request, "NO_MATCH"));
        await(endpoint.resolve(channel, channel.localAddress(), channel.remoteAddress(),
                connectionId, UUID.fromString(wireUuid), "WireName"));
        final Map<String, Object> request = provider.requests.get(0);
        assertEquals("RESOLVE", request.get("op"));
        assertEquals(1, request.get("protocolVersion"));
        assertEquals(live.providerEpoch(), request.get("providerEpoch"));
        assertEquals(connectionId.toString(), request.get("connectionId"));
        assertSame(channel, request.get("channel"), "the channel must be passed as the actual host object");
        assertEquals(channel.localAddress(), request.get("rawLocalAddress"));
        assertEquals(channel.remoteAddress(), request.get("rawRemoteAddress"));
        assertEquals(wireUuid, request.get("wireUuid"));
        assertEquals("WireName", request.get("wireName"));
        assertFalse(request.containsKey("bridgeSessionId"), "RESOLVE requests carry no session id");
        channel.finishAndReleaseAll();
    }

    @Test
    void baselineProviderResolvesValidatesAndDisconnectsWithoutDuplicateAdmission() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final Map<String, Object> d = descriptor();
        d.put("capabilities", List.of("verified-xuid", "exact-channel-binding", "targeted-disconnect"));
        assertEquals("REGISTERED", status(endpoint.register(d, provider)));
        final EmbeddedChannel channel = new EmbeddedChannel();
        final String connectionId = UUID.randomUUID().toString();
        try {
            provider.responder = request -> CompletableFuture.completedFuture(response(request, "VERIFIED",
                    "xuid", VERIFIED_XUID, "bedrockUsername", "OfficialPlayer", "bridgeSessionId", BRIDGE_SESSION_A));
            assertEquals(BedrockBridgeEndpoint.ResolveStatus.VERIFIED,
                    await(endpoint.resolve(channel, null, null, UUID.fromString(connectionId), null, null)).status());
            provider.responder = request -> CompletableFuture.completedFuture(response(request, "VALID"));
            assertEquals(BedrockBridgeEndpoint.ValidationStatus.VALID,
                    await(endpoint.validate(connectionId, BRIDGE_SESSION_A, channel)).status());
            provider.responder = request -> CompletableFuture.completedFuture(response(request, "CLOSED"));
            assertEquals(BedrockBridgeEndpoint.DisconnectStatus.CLOSED,
                    await(endpoint.disconnect(connectionId, BRIDGE_SESSION_A,
                            BedrockBridgeEndpoint.DisconnectReason.REPLACED, "msg")).status());
            assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION_A));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void baselineProviderCannotInvokeDuplicateCoordinatorOrGainCapabilityAfterRegistration() {
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        final StubProvider provider = new StubProvider();
        final Map<String, Object> d = descriptor();
        final List<String> caps = new ArrayList<>(List.of(
                "verified-xuid", "exact-channel-binding", "targeted-disconnect"));
        d.put("capabilities", caps);
        final Map<String, Object> registered = endpoint.register(d, provider);
        assertEquals("REGISTERED", status(registered));
        final LiveRegistration live = new LiveRegistration((String) registered.get("registrationId"),
                (String) d.get("providerEpoch"));
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        endpoint.setDuplicateCandidateCoordinator((xuid, oldSession, newSession) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(Map.of("status", "READY"));
        });
        final Map<String, Object> candidate = event(live, "VERIFIED_DUPLICATE_CANDIDATE");
        candidate.put("xuid", VERIFIED_XUID);
        candidate.put("oldBridgeSessionId", BRIDGE_SESSION_A);
        candidate.put("newBridgeSessionId", BRIDGE_SESSION_B);
        assertRejected(endpoint.handleEvent(live.registrationId(), candidate), "INVALID_REQUEST");
        caps.add("authenticated-duplicate-admission");
        assertRejected(endpoint.handleEvent(live.registrationId(), candidate), "INVALID_REQUEST");
        assertEquals(0, calls.get());
        assertNotNull(endpoint.providerEpoch());
    }

}
