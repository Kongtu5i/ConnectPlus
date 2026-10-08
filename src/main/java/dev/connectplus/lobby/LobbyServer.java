package dev.connectplus.lobby;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.identity.DefaultIdentityLinkService;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.IdentityLinkService;
import dev.connectplus.identity.LinkTransactionJournal;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.PlayerVisitStore;
import dev.connectplus.session.SessionLeaseGranter;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.switching.SwitchInitiator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import net.raphimc.netminecraft.netty.connection.NetServer;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The built-in lobby: a NetMinecraft server bound to a local ephemeral port.
 * ViaProxy routes players here through PreConnectEvent; the protocol handlers
 * live in LobbyChannelInitializer. M2: owns the {@link SessionRegistry} so
 * tests and later milestones can observe the players in the GUI lobby.
 * M3: owns the per-player stores.
 */
public class LobbyServer {

    private final Set<Channel> sessions = ConcurrentHashMap.newKeySet();
    private final SessionRegistry sessionRegistry;
    private final PlayerStore playerStore;
    private final IdentityLinkStore identityLinkStore;
    private final SessionLeaseGranter leaseGranter;
    private final AccountSessionCoordinator coordinator;
    private final PlayerVisitStore visitStore;
    @Nullable
    private final IdentityLinkService linkService;
    /**
     * Storage executor for everything blocking that must stay off the lobby
     * event loops: profile loads (task 3), account token restores and the
     * binding transaction's blocking IO (task 5 — the one serialization story:
     * the transaction's persistence steps all run here, its exclusivity comes
     * from the coordinator's claim).
     */
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ConnectPlus-Storage");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger uncaughtExceptions = new AtomicInteger();
    private final ChannelInitializer<Channel> channelInitializer;
    private NetServer netServer;

    public LobbyServer(final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore, final SwitchInitiator switchInitiator) {
        this(sessionRegistry, tokenStore, playerStore, switchInitiator, null);
    }

    /**
     * Full constructor: {@code bridgeEndpoint} supplies the bridge endpoint the
     * coordinator uses for targeted bedrock DISCONNECTs (task 4 displacement);
     * null is allowed for tests without a bridge. The coordinator owns its own
     * serial execution domain — it never shares the lobby storage executor
     * (carry-forward (c): a claim must not serialize behind a slow save).
     */
    public LobbyServer(final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore,
                       final SwitchInitiator switchInitiator, final Supplier<BedrockBridgeEndpoint> bridgeEndpoint) {
        this(sessionRegistry, tokenStore, playerStore, switchInitiator, bridgeEndpoint, true);
    }

    /**
     * Full constructor with the link-service switch: {@code withLinkService}
     *=false (tests of unrelated flows) builds the lobby without the binding
     * transaction service.
     */
    public LobbyServer(final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore,
                       final SwitchInitiator switchInitiator, final Supplier<BedrockBridgeEndpoint> bridgeEndpoint,
                       final boolean withLinkService) {
        this.sessionRegistry = sessionRegistry;
        this.playerStore = playerStore;
        //The link index shares the players directory with the profiles (plan layout).
        this.identityLinkStore = new IdentityLinkStore(playerStore.playersDir());
        //The visit index lives in the CP data directory itself, deliberately
        //separate from the profile/token/link-transaction files.
        this.visitStore = new PlayerVisitStore(
                new java.io.File(playerStore.playersDir().getParentFile(), "visits-index.json"));
        this.coordinator = new AccountSessionCoordinator(playerStore, bridgeEndpoint);
        this.leaseGranter = this.coordinator;
        //Task 5: the binding transaction service runs its blocking IO on the
        //lobby's storage executor (the same serialization story as every other
        //persistence path; exclusivity comes from the coordinator's claim).
        this.linkService = withLinkService ? new DefaultIdentityLinkService(playerStore, this.identityLinkStore,
                this.coordinator, tokenStore, this.storageExecutor) : null;
        this.channelInitializer = new LobbyChannelInitializer(this.sessions, sessionRegistry, tokenStore, playerStore, this.identityLinkStore, this.leaseGranter, switchInitiator, this.storageExecutor, this.uncaughtExceptions, this.linkService, this.visitStore);
    }

    /**
     * The session-exclusivity coordinator of this lobby (task 4); CoreMain
     * registers it as the bridge's duplicate-candidate coordinator.
     */
    public AccountSessionCoordinator sessionCoordinator() {
        return this.coordinator;
    }

    /**
     * The production §6 switch-cache validator (task 6, Review round 1): the
     * switch engine's retry/reconnect/transfer paths re-validate the session's
     * lease currency through THIS coordinator before a cached account rides
     * another backend connection. The validator keys on the CONNECTION id (the
     * registry and lease key) — the engine carries it through the switch flow
     * from ConnectFlow's handoff; a null/unknown connection id is refused
     * (fail-closed, no cached credential without a verifiable right of use).
     */
    public dev.connectplus.switching.SwitchEngine.AccountLeaseValidator accountLeaseValidator() {
        return connectionId -> connectionId != null && this.coordinator.accountMayConnect(connectionId);
    }

    /**
     * The binding transaction service (task 5); null when the lobby was built
     * without it.
     */
    @Nullable
    public IdentityLinkService linkService() {
        return this.linkService;
    }

    /**
     * The link index store this lobby's binding service commits against (the
     * access binding coordinator shares the SAME instance so their revisions
     * never diverge, task 6).
     */
    public IdentityLinkStore identityLinkStore() {
        return this.identityLinkStore;
    }

    /**
     * The registry of players currently in the lobby.
     */
    public SessionRegistry sessionRegistry() {
        return this.sessionRegistry;
    }

    /**
     * The per-player data store shared by the whole lobby.
     */
    public PlayerStore playerStore() {
        return this.playerStore;
    }

    /**
     * §4.1: 启动时先完成事务恢复，再开放相关身份的档案加载. Runs the binding
     * transaction recovery on the storage executor (blocking IO) before the
     * server starts accepting connections; the returned stage completes when
     * every recoverable transaction reached its resolved state. CoreMain awaits
     * this before {@link #start}, so no profile loading can race the recovery.
     */
    public java.util.concurrent.CompletionStage<DefaultIdentityLinkService.RecoveryOutcome> recoverLinkTransactions() {
        return this.recoverLinkTransactions(null);
    }

    /**
     * Recovery with the access-list binding coordinator (task 6): committed
     * bindings with a pending blacklist delta replay it before the cleanup.
     */
    public java.util.concurrent.CompletionStage<DefaultIdentityLinkService.RecoveryOutcome> recoverLinkTransactions(
            @Nullable final dev.connectplus.access.AccessBindingCoordinator accessBindingCoordinator) {
        if (this.linkService == null) {
            return java.util.concurrent.CompletableFuture.completedStage(
                    new DefaultIdentityLinkService.RecoveryOutcome(Set.of(), Set.of(), java.util.Map.of()));
        }
        final java.util.concurrent.CompletableFuture<DefaultIdentityLinkService.RecoveryOutcome> done =
                new java.util.concurrent.CompletableFuture<>();
        final LinkTransactionJournal journal = new LinkTransactionJournal(this.playerStore.playersDir());
        this.storageExecutor.execute(() -> {
            try {
                done.complete(DefaultIdentityLinkService.recoverAtStartup(this.playerStore, this.identityLinkStore,
                        journal, accessBindingCoordinator));
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    public void start() {
        this.start(0);
    }

    public void start(final int port) {
        if (this.netServer != null) {
            throw new IllegalStateException("Lobby server already started");
        }
        final NetServer server = new NetServer(this.channelInitializer);
        try {
            server.bind(new InetSocketAddress("127.0.0.1", port), false);
        } catch (final Throwable t) {
            throw new LobbyBindException(port, t);
        }
        this.netServer = server;
    }

    public void stop() {
        if (this.netServer != null) {
            this.netServer.getChannel().close();
            this.netServer = null;
        }
        for (final Channel channel : this.sessions) {
            channel.close();
        }
        this.sessions.clear();
        this.storageExecutor.shutdownNow();
        this.coordinator.shutdown();
    }

    public SocketAddress localAddress() {
        if (this.netServer == null) {
            throw new IllegalStateException("Lobby server not started");
        }
        return this.netServer.getChannel().localAddress();
    }

    public int sessionCount() {
        return this.sessions.size();
    }

    /**
     * The accepted lobby channels (including connections still in handshake); used by
     * tests to observe the LobbyLink bridging side of a connection.
     */
    public java.util.Set<Channel> acceptedChannels() {
        return this.sessions;
    }

    /**
     * The number of unexpected (non-protocol) exceptions the lobby handlers have hit;
     * expected clean kicks (garbage input, closed channels, decoder failures) don't count.
     */
    public int uncaughtExceptionCount() {
        return this.uncaughtExceptions.get();
    }

    /**
     * The persistent visit and name index backing the console's accounts/info
     * queries; owned by the lobby like the other stores.
     */
    public PlayerVisitStore visitStore() {
        return this.visitStore;
    }
}
