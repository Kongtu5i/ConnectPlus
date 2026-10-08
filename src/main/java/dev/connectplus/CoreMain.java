package dev.connectplus;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.compat.LobbyHaProxy;
import dev.connectplus.compat.AccountLoginPolicy;
import dev.connectplus.compat.ClientIdentityCapture;
import dev.connectplus.compat.LobbyRedirect;
import dev.connectplus.compat.SwitchableSessionInstaller;
import dev.connectplus.compat.ViaProxyCompat;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.LobbyBindException;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.switching.SwitchEngine;
import net.lenni0451.optconfig.ConfigLoader;
import net.lenni0451.optconfig.ConfigContext;
import net.lenni0451.optconfig.provider.ConfigProvider;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public class CoreMain extends ViaProxyPlugin {

    private static CoreMain instance;

    public static CoreMain get() {
        return instance;
    }

    private final List<Object> listeners = new ArrayList<>();

    private LobbyServer lobbyServer;
    private SwitchEngine switchEngine;
    private ConfigContext<CPConfig> configContext;
    private BedrockBridgeEndpoint bedrockBridgeEndpoint;
    private java.util.concurrent.ExecutorService consoleExecutor;
    private java.util.concurrent.ExecutorService storageExecutor;
    private dev.connectplus.access.AccessService accessService;
    private dev.connectplus.access.AccessConnectionRegistry accessRegistry;
    private dev.connectplus.access.AccessGate accessGate;
    private dev.connectplus.access.AccessMutationCoordinator accessCommitLock;
    private dev.connectplus.access.AccessTargetResolver accessResolver;
    private dev.connectplus.access.AccessBindingCoordinator accessBindingCoordinator;
    private java.util.concurrent.CompletionStage<dev.connectplus.access.AccessPolicy.State> accessInitStage;
    private dev.connectplus.session.PlayerVisitStore proxyVisitStore;

    private dev.connectplus.session.PlayerVisitStore accessNames() {
        return this.lobbyServer != null ? this.lobbyServer.visitStore() : this.proxyVisitStore;
    }

    public LobbyServer getLobbyServer() {
        return this.lobbyServer;
    }

    public dev.connectplus.access.AccessService getAccessService() {
        return this.accessService;
    }

    public dev.connectplus.access.AccessConnectionRegistry getAccessRegistry() {
        return this.accessRegistry;
    }

    public dev.connectplus.access.AccessGate getAccessGate() {
        return this.accessGate;
    }

    public SwitchEngine getSwitchEngine() {
        return this.switchEngine;
    }

    public BedrockBridgeEndpoint getBedrockBridgeEndpoint() {
        return this.bedrockBridgeEndpoint;
    }

    private void registerListener(final Object listener) {
        ViaProxyCompat.registerListener(listener);
        this.listeners.add(listener);
    }

    @Override
    public void onEnable() {
        instance = this;
        ViaProxyCompat.init(this);

        final File dataFolder = ViaProxyCompat.dataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        try {
            final File configFile = new File(dataFolder, "config.yml");
            try {
                //The targeted two-switch upgrade runs BEFORE optconfig: an older but
                //complete config.yml gains whitelist/blacklist and merges cleanly
                //instead of tripping the DiffMerger crash below.
                dev.connectplus.config.AccessConfigDefaults.addMissingOptions(configFile.toPath());
                this.configContext = new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(configFile));
            } catch (final RuntimeException e) {
                //optconfig 1.1.1's DiffMerger crashes (IndexOutOfBoundsException) when an
                //older config.yml holds fewer options than the current config class — the
                //normal upgrade path (M7 manual verification finding). Back the file up
                //and regenerate defaults instead of failing to enable the plugin; the
                //user can merge their old values from the backup.
                if (configFile.exists()) {
                    final File backup = new File(dataFolder, "config.yml.backup-" + System.currentTimeMillis());
                    if (!configFile.renameTo(backup)) {
                        logger().warn("Could not back up the broken config.yml");
                    }
                    logger().error("config.yml could not be merged (outdated format?), a backup was saved and defaults regenerated: {}", e.toString());
                }
                this.configContext = new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(configFile));
            }
        } catch (final IOException e) {
            logger().error("Failed to load config.yml", e);
        }

        dev.connectplus.lobby.screen.Languages.init(dataFolder);
        this.registerListener(new AccountLoginPolicy(this.configContext));

        // Access lists (task 3): the two JSON stores plus the rule service. All
        // disk work for the lists runs on the storage executor; the startup load
        // already decides availability — an enabled list without valid data stays
        // in the rejecting LIST_UNAVAILABLE state until a successful reload.
        this.storageExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ConnectPlus-Storage");
            thread.setDaemon(true);
            return thread;
        });
        this.accessService = new dev.connectplus.access.AccessService(
                new dev.connectplus.access.AccessListStore(new File(dataFolder, "whitelist.json").toPath()),
                new dev.connectplus.access.AccessListStore(new File(dataFolder, "blacklist.json").toPath()),
                new dev.connectplus.access.AccessService.ConfiguredFlags(CPConfig.whitelist, CPConfig.blacklist),
                this.storageExecutor);
        // Connection registry + admission gate (task 4): every player-to-proxy
        // connection is registered from the official channel-init event and judged
        // from the official ClientLoggedInEvent; every published rule state triggers
        // an online recheck. The gate listener is registered AFTER the identity
        // capture so identity attributes exist when the gate runs.
        this.accessRegistry = new dev.connectplus.access.AccessConnectionRegistry();
        this.accessGate = new dev.connectplus.access.AccessGate(this.accessService, this.accessRegistry,
                new dev.connectplus.access.AccessIdentitySource(new dev.connectplus.compat.ClientPlatformInspector()),
                (channel, task) -> channel.eventLoop().execute(task));
        this.accessGate.install();
        this.accessService.subscribe(state -> this.accessGate.recheck(state));
        this.accessCommitLock = new dev.connectplus.access.AccessMutationCoordinator();
        this.proxyVisitStore = new dev.connectplus.session.PlayerVisitStore(new File(dataFolder, "visits-index.json"));
        this.accessResolver = new dev.connectplus.access.AccessTargetResolver(this.accessService,
                () -> this.accessRegistry,
                this::accessNames);
        this.accessGate.setSubjectObserver(subject -> {
            if (subject.clientType() == dev.connectplus.access.AccessKey.ClientType.JAVA && subject.name() != null) {
                this.storageExecutor.execute(() -> {
                    try {
                        this.accessNames().recordKnownJavaName(subject.entryUuid(), subject.name());
                    } catch (final IOException | RuntimeException failure) {
                        logger().warn("Could not record the Java entry name metadata", failure);
                    }
                });
            }
        });
        this.accessInitStage = this.accessService.initialize().whenComplete((state, error) -> {
            if (error != null) {
                logger().error("Access list initialization failed", error);
            } else {
                if (state.whitelistEnabled() && !state.whitelist().available()) {
                    logger().error("whitelist.json is enabled but has no valid data; new connections are rejected"
                            + " until the file is repaired and reloaded");
                }
                if (state.blacklistEnabled() && !state.blacklist().available()) {
                    logger().error("blacklist.json is enabled but has no valid data; new connections are rejected"
                            + " until the file is repaired and reloaded");
                }
            }
        });

        // Bedrock identity bridge (protocol v1): endpoint first, the capture listener
        // resolves new connections through it. The public methods below are the only
        // protocol surface the external Geyser extension is meant to call.
        this.bedrockBridgeEndpoint = new BedrockBridgeEndpoint(ViaProxyCompat::runningVersion);
        this.registerListener(new ClientIdentityCapture(() -> this.bedrockBridgeEndpoint));
        this.registerListener(this.accessGate);

        this.switchEngine = new SwitchEngine(() -> this.lobbyServer != null ? this.lobbyServer.localAddress() : null);
        this.registerListener(new SwitchableSessionInstaller(this.switchEngine));
        this.registerListener(new LobbyRedirect(() -> this.lobbyServer));
        this.registerListener(new LobbyHaProxy(() -> this.lobbyServer != null ? this.lobbyServer.localAddress() : null));

        if ("lobby".equalsIgnoreCase(CPConfig.mode)) {
            try {
                final TokenStore tokenStore = new TokenStore(dataFolder);
                final PlayerStore playerStore = new PlayerStore(new File(dataFolder, "players"));
                // The coordinator receives the endpoint supplier so displacements can
                // reach managed bedrock clients through the targeted DISCONNECT op (task 4).
                this.lobbyServer = new LobbyServer(new SessionRegistry(), tokenStore, playerStore, this.switchEngine,
                        () -> this.bedrockBridgeEndpoint);
                // §4.1: complete the binding transaction recovery BEFORE the lobby
                // starts accepting connections (profile loading opens for the affected
                // identities only after the recovery resolved every journal entry).
                // Task 6: the binding coordinator shares the lobby's link index and
                // the commit lock; the lists must be loaded BEFORE the recovery
                // replays a committed binding's pending blacklist delta.
                this.accessBindingCoordinator = new dev.connectplus.access.AccessBindingCoordinator(
                        this.accessService, this.accessCommitLock, this.lobbyServer::identityLinkStore,
                        this.accessResolver);
                if (this.lobbyServer.linkService() instanceof dev.connectplus.identity.DefaultIdentityLinkService concrete) {
                    concrete.setAccessBindingCoordinator(this.accessBindingCoordinator);
                }
                this.accessInitStage.toCompletableFuture().join();
                try {
                    final dev.connectplus.identity.DefaultIdentityLinkService.RecoveryOutcome recovery =
                            this.lobbyServer.recoverLinkTransactions(this.accessBindingCoordinator)
                                    .toCompletableFuture().join();
                    if (!recovery.cleanedUp().isEmpty() || !recovery.failures().isEmpty()) {
                        logger().info("Link transaction recovery: {} cleaned up, {} aborted, {} failed (failed stay"
                                        + " blocked from link/unlink until the next startup)",
                                recovery.cleanedUp().size(), recovery.aborted().size(), recovery.failures().size());
                    }
                } catch (final RuntimeException e) {
                    logger().error("The link transaction recovery could not run; link operations stay blocked"
                            + " until the next restart", e.getCause() != null ? e.getCause() : e);
                }
                // Route VERIFIED_DUPLICATE_CANDIDATE events into the session-exclusivity
                // coordinator (task 4): the per-XUID serial safe-exit handshake.
                this.bedrockBridgeEndpoint.setDuplicateCandidateCoordinator(
                        this.lobbyServer.sessionCoordinator());
                // Task 6 §6 (switch cache use): the switch-account lease validator is
                // wired here from the lobby's coordinator (see
                // LobbyServer#accountLeaseValidator); disabling the plugin clears it.
                SwitchEngine.setAccountLeaseValidator(this.lobbyServer.accountLeaseValidator());
                this.lobbyServer.start();
                logger().info("Lobby server listening on {}", this.lobbyServer.localAddress());
            } catch (final LobbyBindException e) {
                logger().error("Lobby server could not be started, lobby features are disabled", e);
                this.lobbyServer = null;
            }
        }

        // Console command entry (both modes): the official event only matches the
        // cp/connectplus roots; queries run on the plugin's own executor so the
        // console thread never blocks on store IO.
        this.consoleExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ConnectPlus-Console");
            thread.setDaemon(true);
            return thread;
        });
        // The access console shares the storage executor for its disk reads; the
        // mutation port is the binding coordinator's change() (task 6): both
        // currently linked accounts in one list commit.
        if (this.accessBindingCoordinator == null) {
            // Proxy mode has no binding UI, but existing disk links and interrupted
            // transactions must still govern proxy-wide access checks.
            final dev.connectplus.identity.IdentityLinkStore proxyLinks =
                    new dev.connectplus.identity.IdentityLinkStore(new File(dataFolder, "players"));
            this.accessBindingCoordinator = new dev.connectplus.access.AccessBindingCoordinator(
                    this.accessService, this.accessCommitLock, () -> proxyLinks, this.accessResolver);
            this.accessInitStage.toCompletableFuture().join();
            final var recovery = dev.connectplus.identity.DefaultIdentityLinkService.recoverAtStartup(
                    new PlayerStore(new File(dataFolder, "players")), proxyLinks,
                    new dev.connectplus.identity.LinkTransactionJournal(new File(dataFolder, "players")),
                    this.accessBindingCoordinator);
            if (!recovery.failures().isEmpty()) {
                logger().error("Proxy-mode link transaction recovery has {} pending failure(s)", recovery.failures().size());
            }
        }
        final dev.connectplus.commands.AccessConsoleService accessConsole =
                new dev.connectplus.commands.AccessConsoleService(this.accessService, this.accessResolver,
                        this.accessBindingCoordinator::change,
                        this.storageExecutor);
        final dev.connectplus.commands.ConsoleCommandService consoleService =
                new dev.connectplus.commands.ConsoleCommandService(
                        () -> this.lobbyServer,
                        () -> this.bedrockBridgeEndpoint,
                        new dev.connectplus.commands.ConsoleCommandService.Runtime(
                                ViaProxyCompat.pluginVersion(), ViaProxyCompat.runningVersion(),
                                ViaProxyCompat::hostListenAddress),
                        this.switchEngine::inFlightTargetCount,
                        new PlayerStore(new File(dataFolder, "players")),
                        new dev.connectplus.identity.IdentityLinkStore(new File(dataFolder, "players")),
                        this::accessNames,
                        accessConsole,
                        this.accessService,
                        this.consoleExecutor);
        this.registerListener(new dev.connectplus.commands.ConsoleCommandListener(consoleService::execute));
    }

    @Override
    public void onDisable() {
        for (final Object listener : this.listeners) {
            ViaProxyCompat.unregisterListener(listener);
        }
        this.listeners.clear();
        if (this.lobbyServer != null) {
            this.lobbyServer.stop();
            this.lobbyServer = null;
        }
        if (this.consoleExecutor != null) {
            this.consoleExecutor.shutdownNow();
            this.consoleExecutor = null;
        }
        if (this.storageExecutor != null) {
            this.storageExecutor.shutdownNow();
            this.storageExecutor = null;
        }
        if (this.accessGate != null) {
            dev.connectplus.access.AccessGate.uninstall();
            this.accessGate = null;
        }
        this.accessRegistry = null;
        SwitchEngine.setAccountLeaseValidator(null);
        if (this.bedrockBridgeEndpoint != null) {
            this.bedrockBridgeEndpoint.setDuplicateCandidateCoordinator(null);
            this.bedrockBridgeEndpoint = null;
        }
        instance = null;
    }

    /**
     * Bridge protocol v1 (§4.1): the external Geyser extension registers itself here
     * through reflection on the plugin instance. Only JDK types cross the boundary;
     * the endpoint validates everything and answers REGISTERED or REJECTED.
     */
    public Map<String, Object> registerBedrockBridgeV1(final Map<String, Object> descriptor,
                                                       final Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler) {
        final BedrockBridgeEndpoint endpoint = this.bedrockBridgeEndpoint;
        if (endpoint == null) {
            // The plugin is shutting down: nothing may register anymore.
            return Map.of("status", "REJECTED", "reasonCode", BedrockBridgeEndpoint.REASON_DISABLED);
        }
        return endpoint.register(descriptor, handler);
    }

    /**
     * Bridge protocol v1 (§4.3): the registered extension reports session and
     * provider lifecycle events here. Calls from anything but the current
     * registration answer STALE.
     */
    public CompletionStage<Map<String, Object>> bedrockBridgeEventV1(final String registrationId,
                                                                     final Map<String, Object> event) {
        final BedrockBridgeEndpoint endpoint = this.bedrockBridgeEndpoint;
        if (endpoint == null) {
            return java.util.concurrent.CompletableFuture.completedStage(Map.of("status", "STALE"));
        }
        return endpoint.handleEvent(registrationId, event);
    }

    public static Logger logger() {
        return LoggerFactory.getLogger("ConnectPlus");
    }
}
