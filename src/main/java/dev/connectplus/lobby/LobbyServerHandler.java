package dev.connectplus.lobby;

import dev.connectplus.logging.DebugLog;
import dev.connectplus.accounts.CPAccount;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.IdentityLinkService;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.IdentityResolver;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.session.PlayerVisitStore;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.CpAccounts;
import dev.connectplus.compat.AccountLoginPolicy;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.lobby.model.HandshakeData;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionLease;
import dev.connectplus.session.SessionLeaseGranter;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.switching.SwitchInitiator;
import dev.connectplus.lobby.states.HandshakeStateHandler;
import dev.connectplus.lobby.states.PlayStateHandler;
import dev.connectplus.lobby.states.StateHandler;
import dev.connectplus.lobby.states.StatusStateHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.util.concurrent.ScheduledFuture;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.Packet;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-connection handler dispatching packets into the per-state handlers.
 * Derived from MiniConnect's LobbyServerHandler (MIT, Copyright (c) 2024 Lenni0451),
 * reduced to the minimal M1 flow: sessions are tracked by channel identity
 * (never by IP) so multiple players behind one NAT stay independent.
 * M2: owns the connection's {@link PlayerSession}; loadSession fills the handshake
 * information from the channel's HANDSHAKE_DATA attribute and registers the session.
 * M3: loadSession also loads the per-player data (bookmarks, account).
 * M4: exposes the switch initiator to the connect flow.
 * Task 3: sessions are keyed by the c2p connection id (stable across switches
 * and lobby returns); the profile flow is split into identity resolution,
 * lease claim and profile load.
 */
public class LobbyServerHandler extends SimpleChannelInboundHandler<Packet> {

    /**
     * Whether the corrupt-state of identity-links.json has already been reported
     * (plan §3: report the repair requirement instead of treating it as "no
     * links" — once, not once per join). Reset when a healthy index is read again.
     */
    private static final AtomicBoolean INDEX_REPAIR_REPORTED = new AtomicBoolean();

    private final Set<Channel> sessions;
    private final SessionRegistry sessionRegistry;
    private final TokenStore tokenStore;
    private final PlayerStore playerStore;
    private final IdentityLinkStore identityLinkStore;
    private final SessionLeaseGranter leaseGranter;
    private final IdentityResolver identityResolver = new IdentityResolver();
    private final IdentityLinkService linkService;
    @Nullable
    private final PlayerVisitStore visitStore;
    private final SwitchInitiator switchInitiator;
    private final ExecutorService storageExecutor;
    private final AtomicInteger uncaughtExceptions;
    private StateHandler handler;
    private volatile PlayerSession session;
    private PlayerSession loadingProfileSession;
    private ScheduledFuture<?> tickTask;

    public LobbyServerHandler(final Set<Channel> sessions, final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore, final IdentityLinkStore identityLinkStore, final SessionLeaseGranter leaseGranter, final SwitchInitiator switchInitiator, final ExecutorService storageExecutor, final AtomicInteger uncaughtExceptions) {
        this(sessions, sessionRegistry, tokenStore, playerStore, identityLinkStore, leaseGranter, switchInitiator, storageExecutor, uncaughtExceptions, null);
    }

    public LobbyServerHandler(final Set<Channel> sessions, final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore, final IdentityLinkStore identityLinkStore, final SessionLeaseGranter leaseGranter, final SwitchInitiator switchInitiator, final ExecutorService storageExecutor, final AtomicInteger uncaughtExceptions, @Nullable final IdentityLinkService linkService) {
        this(sessions, sessionRegistry, tokenStore, playerStore, identityLinkStore, leaseGranter, switchInitiator, storageExecutor, uncaughtExceptions, linkService, null);
    }

    public LobbyServerHandler(final Set<Channel> sessions, final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore, final IdentityLinkStore identityLinkStore, final SessionLeaseGranter leaseGranter, final SwitchInitiator switchInitiator, final ExecutorService storageExecutor, final AtomicInteger uncaughtExceptions, @Nullable final IdentityLinkService linkService, @Nullable final PlayerVisitStore visitStore) {
        this.sessions = sessions;
        this.sessionRegistry = sessionRegistry;
        this.tokenStore = tokenStore;
        this.playerStore = playerStore;
        this.identityLinkStore = identityLinkStore;
        this.leaseGranter = leaseGranter;
        this.switchInitiator = switchInitiator;
        this.storageExecutor = storageExecutor;
        this.uncaughtExceptions = uncaughtExceptions;
        this.linkService = linkService;
        this.visitStore = visitStore;
    }

    public StateHandler getStateHandler() {
        return this.handler;
    }

    /**
     * The player session of this connection; null until the login hello was handled.
     */
    public PlayerSession getSession() {
        return this.session;
    }

    /**
     * The switch initiator of this lobby (the hot switch engine in production).
     */
    public SwitchInitiator getSwitchInitiator() {
        return this.switchInitiator;
    }

    /**
     * The per-player data store shared by the whole lobby.
     */
    public PlayerStore getPlayerStore() {
        return this.playerStore;
    }

    /**
     * The account token encryption store shared by the whole lobby.
     */
    public TokenStore getTokenStore() {
        return this.tokenStore;
    }

    /**
     * The binding transaction service (task 5); null when the lobby was built
     * without it (tests of unrelated flows) — the GUI link entry then stays off.
     */
    @Nullable
    public IdentityLinkService getLinkService() {
        return this.linkService;
    }

    /**
     * The lease granter of this lobby (task 6): the §6 write paths (account
     * install, restore landing, settings save, connect target selection, switch
     * cache use, failure fallback) validate the lease currency through it.
     */
    public SessionLeaseGranter getLeaseGranter() {
        return this.leaseGranter;
    }

    /**
     * Creates the player session for this connection: the handshake information comes
     * from the HANDSHAKE_DATA channel attribute (TLV or raw handshake fallback), the
     * per-player data (bookmarks, account) is loaded and the session is registered
     * under its connection id in the lobby's session registry.
     */
    public void loadSession(final Channel channel, final UUID uuid, final String name) {
        final HandshakeData handshakeData = channel.attr(CPAttributeKeys.HANDSHAKE_DATA).get();
        final PlayerSession resumed = LobbyLink.resumeSession(channel, uuid, name);
        this.session = resumed != null ? resumed : new PlayerSession(uuid, name);
        final PlayerSession session = this.session;
        //The connection id — not the session uuid — keys this session in the registry
        //(§5): it is the c2p channel's id and stays the same across switches and returns.
        session.connectionId = LobbyLink.connectionIdOf(channel, uuid);
        //The lease binds this channel's lifetime (§5), so the session carries the c2p
        //channel for the coordinator's displacement and exit handling.
        session.c2pChannel = LobbyLink.c2pOf(channel);
        synchronized (session) {
            session.lobbyChannel = channel;
            session.chatListener = null;
            session.loginInProgress = false;
        }
        if (handshakeData != null) {
            session.handshakeAddress = handshakeData.host();
            session.handshakePort = handshakeData.port();
            session.clientVersion = handshakeData.clientVersion();
        }
        if (session.playerData == null) {
            this.loadProfile(channel, session);
        }
        if (!AccountLoginPolicy.isAllowedForSession(session)) session.account = null;
        if (session.playerData != null) {
            this.setupAccountRestore(channel, session, session.playerData);
        }
        this.sessionRegistry.register(session);
    }

    /**
     * The profile flow of one lobby join (plan §2/§5/§7). The three identifiers
     * stay separate end to end: the session keeps the entry connection's WIRE
     * identity ({@code session.uuid}, echoed in every protocol packet), the
     * protected profile is selected by the ProfileKey resolved from the verified
     * {@link ClientIdentity} — never from the wire uuid — and the right to use
     * it is granted by a lease before anything is read or written.
     *
     * <ul>
     *   <li>Stage 1 — identity resolution: {@link IdentityResolver#resolve}
     *       against the committed link snapshot. A verified Java identity owns
     *       its JAVA profile; a verified Bedrock identity uses the linked Java
     *       profile when one exists and its own BEDROCK profile otherwise.</li>
     *   <li>Stage 2 — lease claim: no profile read or write before the grant.</li>
     *   <li>Stage 3 — profile load, on the storage executor (blocking IO never
     *       runs on the event loop); the loaded data lands on the session
     *       through the event loop.</li>
     * </ul>
     *
     * <p>An UNVERIFIED identity gets no protected path at all: no profile file,
     * no lease, no credentials. A bare diagnostic connection without a captured
     * ClientIdentity (a client talking to the lobby port directly, e.g. tests)
     * keeps the legacy diagnostic load keyed by the entry uuid — no lease, no
     * protected profile, existing behavior unchanged.</p>
     */
    private void loadProfile(final Channel channel, final PlayerSession session) {
        final ClientIdentity identity = LobbyLink.clientIdentityOf(channel);
        if (identity == null && session.c2pChannel == null) {
            //Bare diagnostic connection (no captured ClientIdentity): the legacy
            //diagnostic profile keyed by the entry uuid. A corrupt file fails loudly.
            try {
                session.playerData = this.playerStore.load(ProfileKey.javaProfile(session.uuid));
            } catch (final IOException e) {
                throw new UncheckedIOException("The player profile of " + session.uuid + " could not be loaded", e);
            }
            return;
        }
        if (identity == null || identity.kind() == ClientIdentity.Kind.UNVERIFIED) {
            // The lobby may log in before RESOLVE answers. Subscribe to the
            // connection's result; a completed future also covers an immediate reply.
            final Channel c2p = session.c2pChannel;
            final var resolution = c2p == null ? null : c2p.attr(CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).get();
            if (resolution != null) {
                resolution.thenAccept(resolved -> {
                    if (resolved.kind() != ClientIdentity.Kind.UNVERIFIED) {
                        channel.eventLoop().execute(() -> this.startProtectedProfileLoad(channel, session, resolved));
                    }
                });
            }
            return;
        }
        this.startProtectedProfileLoad(channel, session, identity);
    }

    /** Starts once on the owning lobby's event loop, including after a late identity reply. */
    private void startProtectedProfileLoad(final Channel channel, final PlayerSession session,
                                          final ClientIdentity identity) {
        final dev.connectplus.access.AccessGate gate = dev.connectplus.access.AccessGate.installed();
        if (gate != null && session.c2pChannel != null && !gate.protectedOperationsAllowed(session.c2pChannel)) {
            gate.whenAdmitted(session.c2pChannel).thenAccept(allowed -> {
                if (allowed && gate.protectedOperationsAllowed(session.c2pChannel)) {
                    channel.eventLoop().execute(() -> this.startProtectedProfileLoad(channel, session, identity));
                }
            });
            return;
        }
        synchronized (session) {
            if (!this.isCurrentProfileOwner(channel, session, identity) || session.playerData != null
                    || this.loadingProfileSession == session) return;
            this.loadingProfileSession = session;
        }
        //The identity is now verified: the earlier temporary visit of this same
        //connection (recorded at play-state entry) folds into the confirmed identity.
        this.recordLobbyVisit(session, identity);
        this.storageExecutor.execute(() -> this.loadProtectedProfile(channel, session, identity));
    }

    /** Every async stage rechecks the channel, session and live proof that authorized the load. */
    private boolean isCurrentProfileOwner(final Channel channel, final PlayerSession session,
                                          final ClientIdentity identity) {
        final Channel c2p = session.c2pChannel;
        final dev.connectplus.access.AccessGate gate = dev.connectplus.access.AccessGate.installed();
        if (gate != null && !gate.protectedOperationsAllowed(c2p)) return false;
        if (this.session != session || session.lobbyChannel != channel || !channel.isActive()
                || session.displaced || c2p == null || !c2p.isActive()
                || c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).get() != identity
                || !Objects.equals(session.connectionId, c2p.attr(CPAttributeKeys.CONNECTION_ID).get())) return false;
        if (identity.kind() == ClientIdentity.Kind.VERIFIED_JAVA) return true;
        if (identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK) return false;
        final var endpoint = c2p.attr(CPAttributeKeys.BEDROCK_BRIDGE_ENDPOINT).get();
        return endpoint != null && endpoint.isCurrentIdentity(identity, c2p);
    }

    /**
     * Stages 1-3 for a verified identity. Stage 1 (resolve) runs on the storage
     * executor (blocking snapshot IO); the claim wait and the profile load are
     * chained asynchronously so the storage executor is never parked inside a
     * claim — a slow displacement elsewhere must not stall every other join
     * (task 4 carry-forward: the shared storage executor must not serialize
     * behind a claim).
     */
    private void loadProtectedProfile(final Channel channel, final PlayerSession session, final ClientIdentity identity) {
        try {
            if (!this.isCurrentProfileOwner(channel, session, identity)) return;
            //Stage 1: resolve the profile owner. The link snapshot is blocking disk
            //IO as well, so the whole resolution happens off the event loop.
            final ProfileKey profileKey = this.resolveProfileKey(identity);
            //Stage 2: claim the right of use — nothing below may run before the grant.
            //The resource set covers the profile AND the actually-used Java accounts (§7).
            final Set<UUID> accountUuids = profileKey.kind() == ProfileKey.Kind.JAVA
                    ? Set.of(profileKey.javaUuid())
                    : Set.of();
            final java.util.concurrent.CompletionStage<SessionLease> claim;
            // Serialize the ownership check and claim submission with lobby returns.
            // An old lobby must not submit its claim after the new owner has done so.
            synchronized (session) {
                if (!this.isCurrentProfileOwner(channel, session, identity) || session.playerData != null) return;
                claim = this.leaseGranter.claim(session, profileKey, accountUuids);
            }
            final var pendingClaim = claim.toCompletableFuture();
            // The producer may still grant after our deadline. Keep its future
            // untouched so even a late lease can be returned to the coordinator.
            pendingClaim.copy()
                    .orTimeout(30, TimeUnit.SECONDS)
                    .thenAcceptAsync(lease -> this.loadAndLandProfile(channel, session, identity, profileKey, lease),
                            this.storageExecutor)
                    .exceptionally(error -> {
                        pendingClaim.thenAccept(lease -> this.leaseGranter.release(lease));
                        //Explicit failure: a corrupt protected profile, a save failure
                        //during a displacement or a link index problem must never
                        //degrade into empty data that could later overwrite the
                        //original — the connection is dropped loudly instead.
                        this.failProfileLoad(channel, session, identity, error);
                        return null;
                    });
        } catch (final Exception e) {
            this.failProfileLoad(channel, session, identity, e);
        }
    }

    private void failProfileLoad(final Channel channel, final PlayerSession session,
                                 final ClientIdentity identity, final Throwable error) {
        if (this.isCurrentProfileOwner(channel, session, identity)) {
            this.uncaughtExceptions.incrementAndGet();
            LoggerFactory.getLogger("ConnectPlus").error("The protected profile of {} could not be loaded", session.uuid, error);
            channel.close();
        }
    }

    /**
     * Stage 3: load the granted profile (blocking IO on the storage executor) and
     * land it on the session via the event loop.
     */
    private void loadAndLandProfile(final Channel channel, final PlayerSession session,
                                    final ClientIdentity identity, final ProfileKey profileKey, final SessionLease lease) {
        if (!this.isCurrentProfileOwner(channel, session, identity) || session.playerData != null
                || !this.leaseGranter.isCurrent(lease)) {
            this.leaseGranter.release(lease);
            return;
        }
        final PlayerData data;
        try {
            data = this.playerStore.load(profileKey);
            if (profileKey.kind() == ProfileKey.Kind.BEDROCK && !this.playerStore.exists(profileKey)) {
                // First entry must persist the independent Bedrock profile before
                // account login or any settings change. Recheck after blocking IO.
                if (!this.isCurrentProfileOwner(channel, session, identity) || !this.leaseGranter.isCurrent(lease)) {
                    this.leaseGranter.release(lease);
                    return;
                }
                this.playerStore.save(profileKey, data);
            }
        } catch (final IOException e) {
            //The lease must not leak: a load that cannot deliver the profile hands
            //the right of use straight back.
            this.leaseGranter.release(lease);
            throw new UncheckedIOException("The protected profile of " + profileKey.value() + " could not be loaded", e);
        }
        channel.eventLoop().execute(() -> {
            synchronized (session) {
                //A disconnect, a switch, a displacement or a newer load must make this
                //late result inert — and a lease that can no longer be used must be
                //handed back, so the profile is never leaked to a dead connection.
                if (this.isCurrentProfileOwner(channel, session, identity) && session.playerData == null
                        && this.leaseGranter.isCurrent(lease)) {
                    session.profileKey = profileKey;
                    session.lease = lease;
                    session.generation = lease.generation();
                    session.playerData = data;
                    this.attachLeaseReleaseHook(session, lease);
                    this.setupAccountRestore(channel, session, data);
                    if (this.handler instanceof PlayStateHandler play) play.refreshAccountDisplay();
                } else {
                    this.leaseGranter.release(lease);
                }
            }
        });
    }

    /**
     * Releases the lease when the real client's c2p connection ends (§5: the
     * lease binds the c2p — switches, lobby returns and p2s disconnects keep it;
     * the c2p exit frees it). The hook captures the lease it lands with; a stale
     * release through the coordinator is a no-op by design.
     */
    private void attachLeaseReleaseHook(final PlayerSession session, final SessionLease lease) {
        final Channel c2p = session.c2pChannel;
        if (c2p == null) return;
        c2p.closeFuture().addListener(future -> this.leaseGranter.release(lease));
    }

    /**
     * Stage 1: resolves the {@link ProfileKey} of a verified identity through
     * {@link IdentityResolver#resolve} and the committed link snapshot. A corrupt
     * index stays blocking for every LINK-DEPENDENT path (plan §3: 停止关联档案
     * 访问并报告修复需求，不当作"没有绑定") — but a VERIFIED_JAVA identity owns
     * its JAVA profile outright without needing any link data (plan §2.3: 已验证
     * Java 玩家正常工作), so its join proceeds and the repair requirement is
     * reported once instead.
     */
    private ProfileKey resolveProfileKey(final ClientIdentity identity) {
        try {
            final ProfileKey key = this.identityResolver.resolve(identity, this.identityLinkStore.snapshot());
            INDEX_REPAIR_REPORTED.set(false); // healthy again: report a later corruption once more
            return key;
        } catch (final IdentityLinkStore.CorruptIndexException e) {
            if (identity.kind() == ClientIdentity.Kind.VERIFIED_JAVA) {
                if (INDEX_REPAIR_REPORTED.compareAndSet(false, true)) {
                    LoggerFactory.getLogger("ConnectPlus").warn(
                            "identity-links.json is corrupt: linked bedrock profiles and link/unlink stay blocked until it is repaired", e);
                }
                return ProfileKey.javaProfile(identity.verifiedJavaUuid());
            }
            throw e;
        }
    }

    /**
     * Restores the stored account of {@code session} from its persisted token
     * blob, if one exists and may be restored. The token restore may hit the
     * network (MicrosoftAccount refresh): it runs on the storage executor and
     * the account lands on the session via the event loop afterwards. An
     * already-open main screen is refreshed when restoration completes.
     *
     * <p>Task 6: the restore captures the session's lease at start and the
     * landing validates it — a result that arrives after a displacement, a
     * release or a lobby return is dropped entirely. A failed restore NEVER
     * clears the blob (that is the explicit logout's job alone): a temporary
     * refresh failure keeps the ciphertext so a later join retries.</p>
     */
    private void setupAccountRestore(final Channel channel, final PlayerSession session, final PlayerData data) {
        //The token restore may hit the network (MicrosoftAccount refresh): keep it off
        //the event loop; the account lands on the session via the event loop afterwards.
        //An already-open main screen is refreshed when restoration completes.
        final String accountBlob = data.accountBlob;
        final Channel ownedChannel = channel;
        //A player with save-login off keeps no stored login: nothing may be restored
        if (AccountLoginPolicy.isAllowedForSession(session) && session.account == null && accountBlob != null && data.saveLoginInfo) {
            //Capture the lease generation this restore was started under (§6): the
            //landing re-checks it, so a stale result can never revive a dead session.
            final SessionLease restoreLease = session.lease;
            this.storageExecutor.execute(() -> {
                if (!AccountLoginPolicy.isAllowedForSession(session) || !this.restoreLeaseCurrent(session, restoreLease)) return;
                final String json = this.tokenStore.decrypt(accountBlob);
                // Cancellation is not a restore failure. Do not enqueue a null
                // result that could delete the token if policy is enabled again.
                // Deserialization may refresh Microsoft tokens over the network.
                if (!AccountLoginPolicy.isAllowedForSession(session) || !this.restoreLeaseCurrent(session, restoreLease)) return;
                final CpAccounts.RestoreResult result = this.restoreAccountFromJson(json, data.uuid());
                ownedChannel.eventLoop().execute(() -> {
                    // A mode change, disconnect, fresh login or logout must make
                    // this old restore result inert. The blob is never cleared
                    // here: only an explicit logout removes stored credentials.
                    synchronized (session) {
                        if (ownedChannel.isActive() && session.lobbyChannel == ownedChannel
                                && AccountLoginPolicy.isAllowedForSession(session)
                                && this.session == session && session.playerData == data
                                && Objects.equals(data.accountBlob, accountBlob)
                                && !session.displaced
                                && this.restoreLeaseCurrent(session, restoreLease)) {
                            if (result.status() == CpAccounts.RestoreResult.Status.RESTORED) {
                                session.account = result.account();
                                //The restored account's real profile name joins the
                                //visit index's known names (never a lobby visit)
                                this.recordKnownAccountName(result.account());
                                if (this.handler instanceof PlayStateHandler play) play.refreshAccountDisplay();
                            } else {
                                this.logRestoreOutcome(session, result);
                            }
                        } else if (result.status() != CpAccounts.RestoreResult.Status.RESTORED) {
                            this.logRestoreOutcome(session, result);
                        }
                    }
                });
            });
        }
    }

    /**
     * The §6 lease check of the restore landing: a restore started under a
     * lease may only land while that same lease is still the session's current
     * one; a restore started without a protected profile (bare diagnostic
     * connection) keeps the legacy channel-ownership behavior.
     */
    private boolean restoreLeaseCurrent(final PlayerSession session, @Nullable final SessionLease restoreLease) {
        if (restoreLease == null) {
            return session.lease == null;
        }
        return restoreLease == session.lease && this.leaseGranter.isCurrent(restoreLease);
    }

    private void logRestoreOutcome(final PlayerSession session, final CpAccounts.RestoreResult result) {
        final var logger = LoggerFactory.getLogger("ConnectPlus");
        switch (result.status()) {
            case CORRUPT -> logger.error("The stored account of {} is unreadable ({}); the encrypted blob stays"
                    + " and re-authorization is required", session.uuid, result.reasonCode());
            case REAUTH_REQUIRED -> logger.warn("The stored account of {} was rejected ({}); the player must log in again"
                    + " — the encrypted blob stays", session.uuid, result.reasonCode());
            case RETRYABLE_FAILURE -> logger.warn("The stored account of {} could not be restored temporarily ({});"
                    + " the encrypted blob stays and a later join retries", session.uuid, result.reasonCode());
            default -> { /* RESTORED never logs here */ }
        }
    }

    /**
     * Restores the account from decrypted JSON with a typed outcome (§6): an
     * undecryptable blob is CORRUPT, a rejected refresh is REAUTH_REQUIRED, a
     * temporary failure is RETRYABLE_FAILURE — none of them clears the blob or
     * installs an account.
     */
    private CpAccounts.RestoreResult restoreAccountFromJson(final String json, final UUID playerId) {
        if (json == null) {
            LoggerFactory.getLogger("ConnectPlus").error("Failed to decrypt the account token of {} (secret.key rotated?)", playerId);
            return CpAccounts.RestoreResult.corrupt("undecryptable_blob");
        }
        return CpAccounts.restore(json);
    }

    public void update(final Channel channel, final ConnectionState state) {
        this.handler = switch (state) {
            case HANDSHAKING -> new HandshakeStateHandler(this, channel);
            case CONFIGURATION -> new dev.connectplus.lobby.states.ConfigurationStateHandler(this, channel);
            case LOGIN -> new dev.connectplus.lobby.states.LoginStateHandler(this, channel);
            case PLAY -> {
                //A connection that reached the play state actually entered the lobby:
                //handshakes, failed logins and refused connections never get here.
                this.recordLobbyVisit(this.session, null);
                yield new PlayStateHandler(this, channel);
            }
            case STATUS -> new StatusStateHandler(this, channel);
        };
    }

    /**
     * Records the lobby visit of {@code session} in the visit index (console
     * accounts/info queries) on the storage executor. {@code confirmed} passes a
     * verified identity explicitly (the late-verification path); otherwise the
     * identity currently captured on the connection decides — a null/unverified
     * identity stores a temporary protocol-identity record that a later
     * confirmation folds into the verified identity. Never throws into the
     * caller: a corrupt index or a failed commit must not break the join.
     */
    private void recordLobbyVisit(@Nullable final PlayerSession session, @Nullable final ClientIdentity confirmed) {
        if (session == null || this.visitStore == null || this.storageExecutor == null) {
            return; //bare handler rigs without a visit store keep the old behavior
        }
        final ClientIdentity identity = confirmed != null ? confirmed
                : LobbyLink.clientIdentityOf(session.lobbyChannel);
        final UUID wireUuid = session.uuid;
        final String name = session.name;
        this.storageExecutor.execute(() -> {
            try {
                this.visitStore.recordVisit(wireUuid, name, identity);
            } catch (final dev.connectplus.session.PlayerVisitStore.CorruptIndexException e) {
                LoggerFactory.getLogger("ConnectPlus").warn("The visit index is corrupt; statistics and offline name queries stay blocked", e);
            } catch (final Exception e) {
                LoggerFactory.getLogger("ConnectPlus").warn("Recording the visit of {} failed", session.uuid, e);
            }
        });
    }

    /**
     * Records the most recent known Java profile name of {@code account} (its
     * real profile name, never the decorative display string) without marking
     * it as a lobby visit: logging into a linked account is not a lobby entry.
     * Runs on the storage executor; never throws into the caller.
     */
    public void recordKnownAccountName(@Nullable final dev.connectplus.accounts.CPAccount account) {
        if (account == null || this.visitStore == null || this.storageExecutor == null
                || account.uuid() == null) {
            return;
        }
        final UUID javaUuid = account.uuid();
        final String profileName;
        try {
            profileName = account.profileName();
        } catch (final Throwable t) {
            LoggerFactory.getLogger("ConnectPlus").warn("Reading the profile name of {} failed", javaUuid, t);
            return;
        }
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        this.storageExecutor.execute(() -> {
            try {
                this.visitStore.recordKnownJavaName(javaUuid, profileName);
            } catch (final dev.connectplus.session.PlayerVisitStore.CorruptIndexException e) {
                LoggerFactory.getLogger("ConnectPlus").warn("The visit index is corrupt; statistics and offline name queries stay blocked", e);
            } catch (final Exception e) {
                LoggerFactory.getLogger("ConnectPlus").warn("Recording the known name of {} failed", javaUuid, e);
            }
        });
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        this.sessions.add(ctx.channel());
        this.handler = new HandshakeStateHandler(this, ctx.channel());
        this.tickTask = ctx.channel().eventLoop().scheduleAtFixedRate(() -> this.handler.tick(), 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        this.sessions.remove(ctx.channel());
        final PlayerSession session = this.session;
        if (session != null) {
            synchronized (session) {
                //Task 3 review carry-forward: a lobby channel that closes AFTER a lobby
                //return has re-registered the session must not remove the freshly
                //re-registered slot. The slot is only released when this channel still
                //owns the session — or the c2p has already ended and cleared it — and
                //the check runs under the same monitor loadSession assigns it under.
                if (session.lobbyChannel == null || session.lobbyChannel == ctx.channel()) {
                    //Release by connection id; the holder+generation comparison inside
                    //release makes a stale release a no-op instead of removing blindly.
                    this.sessionRegistry.release(session, session.generation);
                    //Save-login off: the login was valid for this session only — wipe every
                    //account remnant now that the player disconnected (also cleans up a
                    //stale blob from before the toggle existed). A displaced session must
                    //not write the profile anymore: the new holder owns it (Review Focus 1).
                    if (!session.displaced && session.playerData != null && !session.playerData.saveLoginInfo
                            && session.playerData.accountBlob != null) {
                        session.playerData.accountBlob = null;
                        session.account = null;
                        this.playerStore.save(session.playerData);
                    }
                }
            }
        }
        if (this.tickTask != null) {
            this.tickTask.cancel(false);
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Packet packet) {
        if (this.handler instanceof HandshakeStateHandler && packet instanceof net.raphimc.netminecraft.packet.UnknownPacket) {
            //Malformed data instead of a handshake: kick cleanly instead of silently ignoring
            ctx.close();
            return;
        }
        this.handler.handle(packet);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        if (cause instanceof ClosedChannelException) return;
        if (cause instanceof DecoderException) {
            //Netty wraps every codec/protocol failure (bad varint/length, CorruptedFrameException,
            //HAProxyProtocolException, packet read() errors) into DecoderException: expected
            //garbage-input kicks, so log a single debug line (no stack) and don't count them.
            DebugLog.log("Lobby connection protocol error: {}: {}",
                    cause.getClass().getSimpleName(), String.valueOf(cause.getMessage()));
            ctx.close();
            return;
        }
        this.uncaughtExceptions.incrementAndGet();
        LoggerFactory.getLogger("ConnectPlus").error("Lobby connection error", cause);
        ctx.close();
    }
}
