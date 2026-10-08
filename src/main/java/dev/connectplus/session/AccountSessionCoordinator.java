package dev.connectplus.session;

import dev.connectplus.logging.DebugLog;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import io.netty.channel.Channel;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The serial account-session coordinator (plan §5, task 4): exclusive leases
 * per resource set (the profile AND the actually-used Java accounts), the
 * displacement (顶号) of a current holder by a later verified connection, and
 * the bridge duplicate-candidate handshake (§4.3).
 *
 * <p><b>Serial execution domain.</b> All arbitration decisions run on this
 * coordinator's own single thread ("ConnectPlus-SessionCoord") — never on the
 * shared lobby storage executor, whose threads may themselves be parked inside
 * a claim wait (a claim serialized there would deadlock against its own
 * displacement). Blocking IO of a displacement (the old holder's final save)
 * runs on a second dedicated thread ("ConnectPlus-SessionCoord-IO"); the state
 * machine waits asynchronously, so the serial thread never blocks.</p>
 *
 * <p><b>Displacement order (§5, binding):</b> trusted identity check (the
 * callers only claim for verified identities; the bridge path additionally
 * proves the XUID) → freeze the old holder (its lease stops being current, so
 * new writes/refreshes/switches are refused while accepted modifications are
 * saved) → invalidate the old lease → notify + close the old c2p (bedrock
 * clients additionally through the bridge DISCONNECT op) → confirm the exit →
 * grant the new generation (the caller then loads the latest profile and
 * restores credentials). A save failure grants the new connection nothing;
 * there is never a second writable session.</p>
 */
public final class AccountSessionCoordinator implements SessionLeaseGranter,
        BedrockBridgeEndpoint.DuplicateCandidateCoordinator {

    /**
     * §4.4: DISCONNECT and duplicate-login coordination run with a 5s budget.
     * The old lease is always invalidated before this timeout matters, so a
     * timeout never resurrects the old holder's write permission.
     */
    public static final long EXIT_CONFIRMATION_TIMEOUT_MILLIS = 5000;

    /**
     * §5 step 2 budget (review fix): the old holder's final save is local disk
     * IO; if it has not finished within this window (hung disk), the machine
     * stops waiting — the old holder is still invalidated and closed, the
     * claimant is denied with a clear bounded failure, and a grant can never
     * come out of a stalled chain. Same discipline as the exit confirmation.
     */
    public static final long SAVE_PHASE_TIMEOUT_MILLIS = 2000;

    /**
     * The whole per-candidate coordination (freeze → save → evict) of the bridge
     * path must fit inside the endpoint's 5s VERIFIED_DUPLICATE_CANDIDATE relay
     * window (§4.4), otherwise a successful coordination would still be relayed
     * as DENIED/TIMEOUT. Save (≤2s) + eviction share this single budget.
     */
    public static final long DUPLICATE_CANDIDATE_BUDGET_MILLIS = 4500;

    /** Pluggable displacement IO (message + c2p close + bridge DISCONNECT). */
    @FunctionalInterface
    public interface Displacement {

        /**
         * Ends the displaced session's usage: delivers {@code message} (null =
         * close without the displacement message) and closes the session's c2p;
         * the returned stage completes when the exit is confirmed (bounded).
         */
        CompletionStage<Void> evict(PlayerSession session, @Nullable String message);
    }

    /**
     * The production displacement: the bridge DISCONNECT op (REPLACED, with the
     * §5 message verbatim) for managed bedrock sessions, a best-effort system
     * chat line through the session's lobby channel, then the c2p close. The
     * returned stage completes when the c2p is confirmed closed and the
     * disconnect settled — or after the exit-confirmation timeout, which force
     * -closes the explicit old c2p (§5: 关闭超时可关闭明确的旧 c2p).
     */
    public static class DefaultDisplacement implements Displacement {

        private final Supplier<BedrockBridgeEndpoint> bridgeEndpoint;

        public DefaultDisplacement(final Supplier<BedrockBridgeEndpoint> bridgeEndpoint) {
            // The supplier itself may be null (deployments/tests without a bridge):
            // disconnectThroughBridge then always answers empty, and Java clients
            // are reached through the lobby channel regardless.
            this.bridgeEndpoint = bridgeEndpoint;
        }

        @Override
        public CompletionStage<Void> evict(final PlayerSession session, @Nullable final String message) {
            final CompletableFuture<Void> closed = new CompletableFuture<>();
            final Channel c2p = session.c2pChannel;
            final List<CompletionStage<?>> parts = new ArrayList<>();
            if (message != null) {
                this.disconnectThroughBridge(session, message).ifPresent(parts::add);
                this.sendMessage(session, message);
            }
            if (c2p != null && c2p.isActive()) {
                c2p.close();
                c2p.closeFuture().addListener(future -> closed.complete(null));
            } else {
                closed.complete(null); // already gone: the exit is trivially confirmed
            }
            final CompletableFuture<Void> all = parts.isEmpty() ? closed
                    : CompletableFuture.allOf(closed,
                            CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).handle((v, e) -> null));
            return all.orTimeout(EXIT_CONFIRMATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .handle((v, error) -> {
                        if (c2p != null && c2p.isActive()) c2p.close(); // timeout: force the explicit close
                        return null;
                    });
        }

        /**
         * The targeted bridge DISCONNECT of the old bedrock session; empty when
         * the old client is not a bridge-managed bedrock session (Java clients
         * are reached through the lobby channel, and an unmanaged id would hit
         * STALE anyway).
         */
        private java.util.Optional<CompletionStage<?>> disconnectThroughBridge(final PlayerSession session,
                                                                               final String message) {
            final Channel c2p = session.c2pChannel;
            final ClientIdentity identity = c2p != null ? c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).get() : null;
            if (identity == null || identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK
                    || identity.bridgeSessionId() == null || session.connectionId == null) {
                return java.util.Optional.empty();
            }
            final BedrockBridgeEndpoint endpoint = this.bridgeEndpoint != null ? this.bridgeEndpoint.get() : null;
            if (endpoint == null || !endpoint.isSessionManaged(identity.bridgeSessionId())) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(endpoint.disconnect(session.connectionId.toString(),
                    identity.bridgeSessionId(), BedrockBridgeEndpoint.DisconnectReason.REPLACED, message));
        }

        /**
         * Best-effort delivery of the displacement message to a Java client
         * sitting in the lobby; a client on a backend (or a half-open channel)
         * is reached by the c2p close alone.
         */
        private void sendMessage(final PlayerSession session, final String message) {
            try {
                final Channel lobby = session.lobbyChannel;
                if (lobby == null || !lobby.isActive()) return;
                final var registry = lobby.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get();
                if (registry == null || registry.getConnectionState() != ConnectionState.PLAY) return;
                lobby.writeAndFlush(new S2CSystemChatPacket(new StringComponent(message), false))
                        .addListener(future -> {
                            if (!future.isSuccess()) {
                                DebugLog.log("The displacement message could not be delivered", future.cause());
                            }
                        });
            } catch (final Throwable t) {
                DebugLog.log("The displacement message could not be delivered", t);
            }
        }
    }

    /** The resources one connection currently holds through a lease. */
    private static final class Holder {
        final LeaseImpl lease;
        final PlayerSession session;

        Holder(final LeaseImpl lease, final PlayerSession session) {
            this.lease = lease;
            this.session = session;
        }
    }

    /** The connection's currently tracked lease (exactly one after every decision). */
    private static final class Tracked {
        final PlayerSession session;
        volatile LeaseImpl current;

        Tracked(final PlayerSession session, final LeaseImpl current) {
            this.session = session;
            this.current = current;
        }
    }

    private enum LeaseState { ACTIVE, FROZEN, SUPERSEDED, INVALID }

    private static final class LeaseImpl implements SessionLease {

        final UUID connectionId;
        final long generation;
        final Set<String> resources;
        volatile LeaseState state = LeaseState.ACTIVE;

        LeaseImpl(final UUID connectionId, final long generation, final Set<String> resources) {
            this.connectionId = connectionId;
            this.generation = generation;
            this.resources = resources;
        }

        @Override
        public UUID connectionId() {
            return this.connectionId;
        }

        @Override
        public long generation() {
            return this.generation;
        }
    }

    private record ClaimRequest(PlayerSession session, TreeSet<String> resources,
                                CompletableFuture<SessionLease> result) {
    }

    private final PlayerStore playerStore;
    private final Supplier<BedrockBridgeEndpoint> bridgeEndpoint;
    private final Displacement displacement;
    /** The serial arbitration domain: every decision and release runs here, in order. */
    private final ExecutorService serial;
    /**
     * Blocking IO of displacements (the old holder's final save); never the shared
     * storage executor. Volatile + swappable: a save phase that times out (hung
     * disk) rotates in a fresh domain so the stuck thread cannot head-of-line-block
     * every future displacement (review fix).
     */
    private volatile ExecutorService storage;
    /** Guards a storage rotation against double-rotation by concurrent timeouts. */
    private final AtomicBoolean rotating = new AtomicBoolean();
    /** connectionId -> the connection's tracked current lease (exists iff a current lease exists). */
    private final ConcurrentHashMap<UUID, Tracked> tracked = new ConcurrentHashMap<>();
    /** resourceId -> the holder of the resource. */
    private final ConcurrentHashMap<String, Holder> holders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    /** Serial-domain only: an older pending claim must never supersede a later lobby return. */
    private final Map<UUID, ClaimRequest> pendingClaims = new HashMap<>();

    /** Diagnostics logger (no CoreMain dependency: the coordinator also runs in tests). */
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountSessionCoordinator.class);

    public AccountSessionCoordinator(final PlayerStore playerStore,
                                     final Supplier<BedrockBridgeEndpoint> bridgeEndpoint) {
        this(playerStore, bridgeEndpoint, new DefaultDisplacement(bridgeEndpoint));
    }

    /** Explicit displacement (tests); production callers use the two-arg constructor. */
    public AccountSessionCoordinator(final PlayerStore playerStore,
                                     final Supplier<BedrockBridgeEndpoint> bridgeEndpoint,
                                     final Displacement displacement) {
        this.playerStore = Objects.requireNonNull(playerStore, "playerStore");
        // The supplier may be null (deployments/tests without a bridge); every use
        // null-guards before touching the endpoint.
        this.bridgeEndpoint = bridgeEndpoint;
        this.displacement = Objects.requireNonNull(displacement, "displacement");
        this.serial = singleThreadExecutor("ConnectPlus-SessionCoord");
        this.storage = singleThreadExecutor("ConnectPlus-SessionCoord-IO");
    }

    private static ExecutorService singleThreadExecutor(final String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---- §7: claim / isCurrent / release ------------------------------------

    @Override
    public CompletionStage<SessionLease> claim(final PlayerSession session, final ProfileKey profile,
                                               final Set<UUID> accountUuids) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(session.connectionId, "session.connectionId");
        Objects.requireNonNull(profile, "profile");
        final TreeSet<String> resources = new TreeSet<>();
        resources.add(profileResource(profile));
        if (accountUuids != null) {
            for (final UUID account : accountUuids) resources.add(accountResource(account));
        }
        final CompletableFuture<SessionLease> result = new CompletableFuture<>();
        this.serial.execute(() -> this.decideClaim(new ClaimRequest(session, resources, result)));
        return result;
    }

    /**
     * Whether {@code lease} is still the current right of use for every one of
     * its resources. Lock-free: the lease state is volatile, the holder map is
     * a concurrent map, so write paths can check it from any thread.
     */
    @Override
    public boolean isCurrent(final SessionLease lease) {
        if (!(lease instanceof final LeaseImpl impl) || impl.state != LeaseState.ACTIVE) return false;
        for (final String resource : impl.resources) {
            final Holder holder = this.holders.get(resource);
            if (holder == null || holder.lease != impl) return false;
        }
        return true;
    }

    /**
     * A non-displacing claim: the lease is granted ONLY when no other
     * connection currently holds any of the resources; when someone else holds
     * one, the stage completes with null instead of running the displacement
     * machine. A same-connection re-claim still supersedes the orphaned lease
     * (exactly like {@link #claim}).
     *
     * <p>Review Focus 3 fix: an aborted transaction's re-acquisition of the
     * session's own usage must never take the resources away from a newer
     * verified login that won them in the meantime — "后来通过身份验证的连接
     * 顶掉旧连接" is one-directional, so the loser of that race simply gets
     * nothing (its re-join claims through the normal flow if it is still
     * around). Runs in the same serial domain as every other decision.</p>
     */
    public CompletionStage<SessionLease> claimNonDisplacing(final PlayerSession session, final ProfileKey profile,
                                                            final Set<UUID> accountUuids) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(session.connectionId, "session.connectionId");
        Objects.requireNonNull(profile, "profile");
        final TreeSet<String> resources = new TreeSet<>();
        resources.add(profileResource(profile));
        if (accountUuids != null) {
            for (final UUID account : accountUuids) resources.add(accountResource(account));
        }
        final CompletableFuture<SessionLease> result = new CompletableFuture<>();
        this.serial.execute(() -> {
            try {
                final UUID connectionId = session.connectionId;
                this.supersedePendingClaim(connectionId);
                //A same-connection re-claim supersedes the orphaned lease (same rule as decideClaim).
                this.supersedeCurrent(connectionId);
                for (final String resource : resources) {
                    final Holder holder = this.holders.get(resource);
                    if (holder != null && !holder.lease.connectionId.equals(connectionId)) {
                        //Someone else holds a resource: the re-acquisition loses —
                        //never displace a newer verified login (Review Focus 3).
                        result.complete(null);
                        return;
                    }
                }
                final Channel c2p = session.c2pChannel;
                if (c2p != null && !c2p.isActive()) {
                    result.complete(null);
                    return;
                }
                final LeaseImpl lease = this.grant(session, resources);
                if (!result.complete(lease)) this.doRelease(lease);
            } catch (final Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Override
    public CompletionStage<Void> release(final SessionLease lease) {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        this.serial.execute(() -> {
            try {
                this.doRelease(lease);
                done.complete(null);
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    /**
     * Frees the resources of a still-current lease; a stale release (already
     * displaced, superseded or released) is a no-op — exactly the property the
     * late close callbacks of a displaced connection rely on (Review Focus 1).
     */
    private void doRelease(final SessionLease lease) {
        if (!(lease instanceof final LeaseImpl impl) || impl.state != LeaseState.ACTIVE) return;
        impl.state = LeaseState.INVALID;
        this.freeResources(impl);
        final Tracked tracked = this.tracked.get(impl.connectionId);
        if (tracked != null && tracked.current == impl) {
            this.tracked.remove(impl.connectionId, tracked);
        }
    }

    /**
     * Whether the session currently registered under {@code connectionId} may
     * still put its account on a backend connection (§6 switch-cache rule):
     * the session must exist, not be displaced, and its tracked lease must be
     * current for all of its resources. The volatile lease state makes a
     * displacement that ran after a switch job started visible here, so the
     * engine's own retry/reconnect/transfer paths can never reconnect a
     * backend with the displaced session's credentials.
     */
    public boolean accountMayConnect(final UUID connectionId) {
        final Tracked tracked = this.tracked.get(connectionId);
        if (tracked == null || tracked.current == null || tracked.session.displaced) return false;
        return this.isCurrent(tracked.current);
    }

    // ---- bridge duplicate-candidate coordination (§4.3) ----------------------

    @Override
    public CompletionStage<Map<String, Object>> onDuplicateCandidate(final String xuid,
                                                                     final String oldBridgeSessionId,
                                                                     final String newBridgeSessionId) {
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        this.serial.execute(() -> {
            try {
                this.decideDuplicateCandidate(xuid, oldBridgeSessionId, newBridgeSessionId, result);
            } catch (final Throwable t) {
                result.complete(denied("TIMEOUT"));
            }
        });
        return result;
    }

    /**
     * The per-XUID coordination of a verified duplicate login: the old session
     * must complete a safe exit (freeze → save → invalidate → targeted bridge
     * DISCONNECT + c2p close) before the answer is READY; the arriving candidate
     * still claims its profile through the normal flow (spec §4.3). Candidates
     * are serialized by the coordinator's single decision thread.
     */
    private void decideDuplicateCandidate(final String xuid, final String oldBridgeSessionId,
                                          final String newBridgeSessionId,
                                          final CompletableFuture<Map<String, Object>> result) {
        final BedrockBridgeEndpoint endpoint = this.bridgeEndpoint.get();
        final BedrockBridgeEndpoint.ManagedSessionInfo old =
                endpoint != null ? endpoint.managedSession(oldBridgeSessionId) : null;
        if (old == null || !old.xuid().equals(xuid)) {
            // Not managed by ConnectPlus: keep Geyser's own duplicate rejection (§4.3).
            result.complete(denied("OLD_SESSION_NOT_MANAGED"));
            return;
        }
        final Tracked tracked;
        try {
            tracked = this.tracked.get(UUID.fromString(old.connectionId()));
        } catch (final IllegalArgumentException e) {
            result.complete(denied("INVALID_IDENTITY"));
            return;
        }
        if (tracked == null || tracked.current == null) {
            // Bridge-managed but ConnectPlus granted it no protected usage: nothing to
            // freeze or save — the safe exit is the targeted bridge DISCONNECT itself.
            this.closeThroughBridge(endpoint, old.connectionId(), oldBridgeSessionId)
                    .whenComplete((v, e) ->
                            this.serial.execute(() -> this.answerReady(result)));
            return;
        }
        final List<Holder> displaced = List.of(new Holder(tracked.current, tracked.session));
        // The whole coordination (save phase ≤2s + eviction) shares one budget
        // that stays inside the endpoint's 5s relay window (§4.4): READY may be
        // late only if the machine is genuinely broken — then the endpoint
        // relays DENIED/TIMEOUT, which is the spec's degraded-not-corrupt answer.
        this.displace(displaced, true, DUPLICATE_CANDIDATE_BUDGET_MILLIS)
                .whenComplete((v, error) -> this.serial.execute(() -> {
            if (error != null) {
                result.complete(denied("SAVE_FAILED"));
                return;
            }
            this.answerReady(result);
        }));
    }

    /**
     * READY means exactly that the named old connection completed its safe exit —
     * it is not an account authorization and grants no profile rights (spec §4.3).
     * The arriving candidate's liveness and its later RESOLVE + claim are the
     * extension's and the normal flow's job; the candidate usually has not gone
     * through RESOLVE yet when this answer travels back.
     */
    private void answerReady(final CompletableFuture<Map<String, Object>> result) {
        result.complete(Map.of("status", "READY"));
    }

    private CompletableFuture<Void> closeThroughBridge(final BedrockBridgeEndpoint endpoint,
                                                       final String connectionId, final String bridgeSessionId) {
        return endpoint.disconnect(connectionId, bridgeSessionId,
                        BedrockBridgeEndpoint.DisconnectReason.REPLACED, BedrockBridgeEndpoint.REPLACED_KICK_MESSAGE)
                .toCompletableFuture()
                .handle((v, e) -> null)
                .orTimeout(EXIT_CONFIRMATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .handle((v, e) -> null);
    }

    // ---- claim decision ------------------------------------------------------

    private void decideClaim(final ClaimRequest request) {
        try {
            final PlayerSession session = request.session();
            if (!claimAccessAllowed(session)) {
                this.failClaim(request, new IllegalStateException("Access admission no longer permits this profile claim"));
                return;
            }
            final UUID connectionId = session.connectionId;
            this.supersedePendingClaim(connectionId);
            this.pendingClaims.put(connectionId, request);
            // A same-connection re-claim (e.g. a lobby return while the first load is
            // still in flight) supersedes the orphaned lease instead of double-granting.
            this.supersedeCurrent(connectionId);
            final Channel c2p = session.c2pChannel;
            if (c2p != null && !c2p.isActive()) {
                this.failClaim(request,
                        new IllegalStateException("The connection exited before its lease was granted"));
                return;
            }
            this.decideClaimAgainstHolders(request);
        } catch (final Throwable t) {
            this.failClaim(request, t);
        }
    }

    private void supersedePendingClaim(final UUID connectionId) {
        final ClaimRequest previous = this.pendingClaims.remove(connectionId);
        if (previous != null) {
            previous.result().completeExceptionally(new CancellationException("A newer claim superseded this connection's pending claim"));
        }
    }

    private boolean isPendingClaim(final ClaimRequest request) {
        final UUID connectionId = request.session().connectionId;
        if (this.pendingClaims.get(connectionId) != request) return false;
        if (!request.result().isDone()) return true;
        this.pendingClaims.remove(connectionId, request);
        return false;
    }

    private void failClaim(final ClaimRequest request, final Throwable error) {
        this.pendingClaims.remove(request.session().connectionId, request);
        request.result().completeExceptionally(error);
    }

    /**
     * Collects the current holders of the requested resources and either grants
     * directly (nothing to displace) or runs the displacement machine first.
     * While a displacement is in flight another claim can win a resource on the
     * serial thread — so after every displacement the holder scan repeats (the
     * loop): the later claimant displaces whoever took the resources meanwhile
     * (§5: the later VERIFIED connection wins), never overwriting a live holder
     * behind its back.
     */
    private void decideClaimAgainstHolders(final ClaimRequest request) {
        if (!this.isPendingClaim(request)) return;
        final PlayerSession session = request.session();
        if (!claimAccessAllowed(session)) {
            this.failClaim(request, new IllegalStateException("Access admission no longer permits this profile claim"));
            return;
        }
        final UUID connectionId = session.connectionId;
        final List<Holder> displaced = new ArrayList<>();
        for (final String resource : request.resources()) {
            final Holder holder = this.holders.get(resource);
            if (holder != null && !holder.lease.connectionId.equals(connectionId)) {
                if (displaced.stream().noneMatch(d -> d.lease == holder.lease)) displaced.add(holder);
            }
        }
        if (displaced.isEmpty()) {
            final LeaseImpl lease = this.grant(session, request.resources());
            this.pendingClaims.remove(connectionId, request);
            if (!request.result().complete(lease)) this.doRelease(lease);
            return;
        }
        this.displace(displaced, true).whenComplete((v, error) -> this.serial.execute(() -> {
            if (!this.isPendingClaim(request)) return;
            if (error != null) {
                // §5: a save failure grants the new connection nothing and reports a
                // clear failure — never a second writable session.
                this.failClaim(request, new IOException(
                        "The previous holder's profile could not be saved; the new connection is denied", error));
                return;
            }
            final Channel live = session.c2pChannel;
            if (live != null && !live.isActive()) {
                this.failClaim(request,
                        new IllegalStateException("The connection exited while the previous holder was being displaced"));
                return;
            }
            this.decideClaimAgainstHolders(request);
        }));
    }

    private static boolean claimAccessAllowed(final PlayerSession session) {
        final Channel channel = session.c2pChannel;
        if (channel != null && (!channel.isActive()
                || Boolean.TRUE.equals(channel.attr(dev.connectplus.compat.CPAttributeKeys.ACCESS_DENIED).get()))) {
            return false;
        }
        final dev.connectplus.access.AccessGate gate = dev.connectplus.access.AccessGate.installed();
        return gate == null || gate.protectedOperationsAllowed(channel);
    }

    /**
     * The displacement state machine for one or more old holders (§5 steps 2-4):
     * freeze → save the accepted modifications → invalidate → notify + close →
     * wait for the confirmed exits. Never runs blocking waits on the serial
     * thread; every step continues asynchronously on the coordinator thread.
     *
     * @param withMessage whether the old clients get the verbatim displacement
     *                    message (false when nobody is taking over and the old
     *                    holders are only being shut down safely)
     */
    private CompletableFuture<Void> displace(final List<Holder> displaced, final boolean withMessage) {
        return this.displace(displaced, withMessage, EXIT_CONFIRMATION_TIMEOUT_MILLIS);
    }

    /**
     * Overload for the bridge coordination path: the eviction budget is whatever
     * remains of the path's total coordination budget after the (bounded) save
     * phase, so READY can never outrun the endpoint's relay window.
     */
    private CompletableFuture<Void> displace(final List<Holder> displaced, final boolean withMessage,
                                             final long evictBudgetMillis) {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        // Step 2: freeze — the old leases stop being current, so any new write,
        // refresh or switch of the old holders is refused from here on.
        for (final Holder holder : displaced) {
            holder.lease.state = LeaseState.FROZEN;
            holder.session.displaced = true;
        }
        // Step 2 (cont.): let the accepted modifications finish saving. Blocking IO
        // on the coordinator's own IO executor; the machine waits asynchronously —
        // and only for a bounded window (review fix: a hung disk save must stall
        // neither this claim nor the whole displacement mechanism; see below).
        final List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (final Holder holder : displaced) {
            final CompletableFuture<Void> save = new CompletableFuture<>();
            saves.add(save);
            this.storage.execute(() -> {
                try {
                    this.saveAcceptedModifications(holder.session);
                    save.complete(null);
                } catch (final Throwable t) {
                    save.completeExceptionally(t);
                }
            });
        }
        // Bounded exactly like the exit confirmation: a save that misses the window
        // counts as failed. The old leases are invalidated and the old clients are
        // closed either way (§5 step 3 happens before the close), so a hung save
        // degrades into "old holder is out, claimant denied" — never a grant out of
        // a stalled chain and never a resurrected old holder.
        CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                .orTimeout(SAVE_PHASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((v, saveError) -> this.serial.execute(() -> {
                    // Step 3: invalidate the old leases (the resources stay untouched
                    // until the grants below), then notify + close the old clients.
                    for (final Holder holder : displaced) {
                        holder.lease.state = LeaseState.INVALID;
                        this.freeResources(holder.lease);
                        this.dropTracked(holder.lease);
                    }
                    // The displacement message goes out only when the save succeeded —
                    // after a failed (or timed-out) save nobody took over, so the
                    // "remote login" notice would be wrong; the old holder is still
                    // closed (not left frozen as a zombie), it just loses its write
                    // permission without a successor.
                    this.evictAll(displaced, saveError == null && withMessage
                                    ? BedrockBridgeEndpoint.REPLACED_KICK_MESSAGE : null, evictBudgetMillis)
                            .whenComplete((v2, evictError) -> this.serial.execute(() -> {
                                if (saveError != null) {
                                    done.completeExceptionally(saveError);
                                } else {
                                    done.complete(null);
                                }
                            }));
                }));
        // A timed-out save thread stays parked in disk IO; a single-thread IO
        // executor would queue every future displacement save behind it and
        // degrade the whole mechanism permanently. Swap in a fresh domain as
        // soon as the timeout fires — the stalled thread (if the IO ever
        // returns) finishes its save into a completed future, which is a no-op.
        // Only a TIMEOUT rotates: a normally-completing phase must never tear
        // its (still healthy) executor down.
        CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                .orTimeout(SAVE_PHASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((v, e) -> {
                    if (e instanceof TimeoutException) this.rotateStorageIfTimedOut();
                });
        return done;
    }

    /**
     * Replaces the IO executor when a save phase timed out (review fix): the
     * stuck thread can no longer head-of-line-block future displacements. The
     * abandoned task, if it ever completes, writes its result into an already
     * settled future — harmless. Not synchronised: races simply rotate twice.
     */
    private void rotateStorageIfTimedOut() {
        final ExecutorService stale = this.storage;
        // Already rotated for this stuck phase (or never timed out): nothing to do.
        if (this.rotating.compareAndSet(false, true)) {
            this.storage = singleThreadExecutor("ConnectPlus-SessionCoord-IO");
            stale.shutdownNow();
            this.rotating.set(false);
        }
    }

    /** Waits for every eviction to confirm (bounded); eviction errors never block the machine. */
    private CompletableFuture<Void> evictAll(final List<Holder> displaced, @Nullable final String message) {
        return this.evictAll(displaced, message, EXIT_CONFIRMATION_TIMEOUT_MILLIS);
    }

    /**
     * Waits for every eviction to confirm within {@code budgetMillis}; eviction
     * errors never block the machine (the §5 关闭超时可关闭明确的旧 c2p rule — the
     * timeout path force-closes the explicit old c2p inside the eviction itself).
     * The budget is a parameter so the bridge coordination path can keep its whole
     * freeze→save→evict machine inside the endpoint's 5s relay window.
     */
    private CompletableFuture<Void> evictAll(final List<Holder> displaced, @Nullable final String message,
                                             final long budgetMillis) {
        final List<CompletableFuture<?>> exits = new ArrayList<>();
        for (final Holder holder : displaced) {
            try {
                exits.add(this.displacement.evict(holder.session, message).toCompletableFuture());
            } catch (final Throwable t) {
                LOGGER.warn("The displacement of connection {} failed", holder.lease.connectionId, t);
            }
        }
        return CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new))
                .orTimeout(budgetMillis, TimeUnit.MILLISECONDS)
                .handle((v, error) -> null);
    }

    /**
     * The old holder's final save: everything it had accepted before the freeze
     * reaches disk, so the new holder's fresh load sees it (§5 step 4). A
     * session-only login (saveLoginInfo=false) never persists its token — the
     * same rule as the disconnect wipe.
     */
    private void saveAcceptedModifications(final PlayerSession holderSession) throws IOException {
        final PlayerData data = holderSession.playerData;
        if (data == null) return;
        if (!data.saveLoginInfo && data.accountBlob != null) {
            data.accountBlob = null;
            holderSession.account = null;
        }
        this.playerStore.save(data.key, data);
    }

    private LeaseImpl grant(final PlayerSession session, final Set<String> resources) {
        final long generation = this.generations
                .computeIfAbsent(session.connectionId, key -> new AtomicLong())
                .incrementAndGet();
        final LeaseImpl lease = new LeaseImpl(session.connectionId, generation, Set.copyOf(resources));
        for (final String resource : resources) {
            this.holders.put(resource, new Holder(lease, session));
        }
        final Tracked tracked = new Tracked(session, lease);
        this.tracked.put(session.connectionId, tracked);
        return lease;
    }

    private void supersedeCurrent(final UUID connectionId) {
        final Tracked tracked = this.tracked.get(connectionId);
        if (tracked == null || tracked.current == null) return;
        final LeaseImpl superseded = tracked.current;
        superseded.state = LeaseState.SUPERSEDED;
        this.freeResources(superseded);
        this.dropTracked(superseded);
    }

    private void freeResources(final LeaseImpl lease) {
        for (final String resource : lease.resources) {
            this.holders.compute(resource, (key, holder) -> holder != null && holder.lease == lease ? null : holder);
        }
    }

    private void dropTracked(final LeaseImpl lease) {
        final Tracked tracked = this.tracked.get(lease.connectionId);
        if (tracked != null && tracked.current == lease) {
            this.tracked.remove(lease.connectionId, tracked);
        }
    }

    private static String profileResource(final ProfileKey profile) {
        return "profile:" + profile.kind().name() + ":" + profile.value();
    }

    private static String accountResource(final UUID account) {
        return "account:" + account;
    }

    private static Map<String, Object> denied(final String reasonCode) {
        final Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("status", "DENIED");
        response.put("reasonCode", reasonCode);
        return response;
    }

    /** Stops the coordinator's executor domains (plugin disable / test teardown). */
    public void shutdown() {
        this.serial.shutdownNow();
        this.storage.shutdownNow();
    }

    /**
     * Test determinism helper: completes once every task that was queued on the
     * serial or IO domain at call time has finished. Production code never waits
     * on this — it exists so tests can pump the machine to quiescence even when
     * a completion on one domain schedules follow-up work on the other.
     */
    CompletableFuture<Void> awaitIdle() {
        final CompletableFuture<Void> serialIdle = new CompletableFuture<>();
        final CompletableFuture<Void> storageIdle = new CompletableFuture<>();
        this.serial.execute(() -> serialIdle.complete(null));
        this.storage.execute(() -> storageIdle.complete(null));
        return CompletableFuture.allOf(serialIdle, storageIdle);
    }

}
