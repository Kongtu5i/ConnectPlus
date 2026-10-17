package dev.connectplus.commands;

import dev.connectplus.access.AccessEntry;
import dev.connectplus.access.AccessKey;
import dev.connectplus.access.AccessListStore;
import dev.connectplus.access.AccessService;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.commands.ConsoleCommands.Command;
import dev.connectplus.commands.ConsoleCommands.Type;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.logging.DebugLog;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.PlayerVisitStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.config.CPConfig;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.OnlineConnection;
import dev.connectplus.compat.OnlinePlayerQuery;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.access.AccessConnectionRegistry;
import dev.connectplus.access.AccessSubject;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the console query service: real stores, real sessions, real
 * visit index — the eight subcommands produce the designed outputs, and a
 * corrupt index can never degrade into a zero count.
 */
class ConsoleCommandServiceTest {

    @TempDir
    File dataDir;

    private ExecutorService executor;
    private PlayerStore playerStore;
    private IdentityLinkStore linkStore;
    private PlayerVisitStore visits;
    private dev.connectplus.lobby.LobbyServer lobbyServer;
    private SessionRegistry registry;
    private BedrockBridgeEndpoint endpoint;
    private boolean oldDebug;
    private String oldLanguage;
    private org.apache.logging.log4j.core.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        this.oldLanguage = CPConfig.language;
        CPConfig.language = "zh";
        Languages.init(null);
        this.executor = Executors.newSingleThreadExecutor();
        this.playerStore = new PlayerStore(new File(this.dataDir, "players"));
        this.linkStore = new IdentityLinkStore(this.playerStore.playersDir());
        this.visits = new PlayerVisitStore(new File(this.dataDir, "visits-index.json"));
        this.endpoint = new BedrockBridgeEndpoint(() -> "3.4.13");
        this.oldDebug = CPConfig.debug;
        CPConfig.debug = false;
        DebugLog.setRuntimeEnabled(null);
        this.logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger("ConnectPlus");
        this.originalLevel = this.logger.getLevel();
        this.logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        CPConfig.language = this.oldLanguage;
        Languages.init(null);
        DebugLog.setRuntimeEnabled(null);
        CPConfig.debug = this.oldDebug;
        this.logger.setLevel(this.originalLevel);
        this.executor.shutdownNow();
        if (this.lobbyServer != null) {
            this.lobbyServer.stop();
        }
    }

    private ConsoleCommandService service() throws IOException {
        return service(this::lobbySnapshot);
    }

    private OnlinePlayerQuery.Snapshot lobbySnapshot() {
        if (this.lobbyServer == null) return new OnlinePlayerQuery.Snapshot(List.of(), true);
        return new OnlinePlayerQuery.Snapshot(this.registry.snapshot().stream().map(session -> {
            final ClientIdentity identity = dev.connectplus.compat.LobbyLink.sessionIdentity(session);
            final boolean bedrock = identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_BEDROCK;
            return new OnlinePlayerQuery.Player(session.connectionId, session.name, session.uuid, identity,
                    bedrock ? "基岩（版本未知）" : session.clientVersion == null ? "Java（版本未知）" : "Java " + session.clientVersion.getName(),
                    OnlinePlayerQuery.Location.LOBBY, null, null, session);
        }).toList(), true);
    }

    private ConsoleCommandService service(final java.util.function.Supplier<OnlinePlayerQuery.Snapshot> online) throws IOException {
        return new ConsoleCommandService(
                () -> this.lobbyServer,
                () -> this.endpoint,
                new ConsoleCommandService.Runtime("1.0.0-test", "3.4.13",
                        () -> InetSocketAddress.createUnresolved("127.0.0.1", 25565)),
                () -> 0,
                this.playerStore,
                this.linkStore,
                () -> this.visits,
                accessConsoleStub(),
                this.accessService(),
                this.executor, online);
    }

    private AccessService cachedAccessService;

    /** The access rule service over the temp dir (ONE instance per test instance). */
    private AccessService accessService() throws IOException {
        if (this.cachedAccessService != null) {
            return this.cachedAccessService;
        }
        final java.nio.file.Path wl = java.nio.file.Path.of(dataDir.getAbsolutePath(), "wl-test.json");
        final java.nio.file.Path bl = java.nio.file.Path.of(dataDir.getAbsolutePath(), "bl-test.json");
        if (!java.nio.file.Files.exists(wl)) {
            Files.writeString(wl, "{\"schemaVersion\": 1, \"players\": []}");
            Files.writeString(bl, "{\"schemaVersion\": 1, \"players\": []}");
        }
        final AccessService service = new AccessService(new AccessListStore(wl), new AccessListStore(bl),
                new AccessService.ConfiguredFlags(false, false), Runnable::run);
        service.initialize().toCompletableFuture().join();
        this.cachedAccessService = service;
        return service;
    }

    /** A read-only access console stub: existing status/list behaviour keeps working. */
    private AccessConsoleService accessConsoleStub() {
        final dev.connectplus.access.AccessListStore white = new dev.connectplus.access.AccessListStore(
                java.nio.file.Path.of("unused-whitelist.json"));
        final dev.connectplus.access.AccessListStore black = new dev.connectplus.access.AccessListStore(
                java.nio.file.Path.of("unused-blacklist.json"));
        final dev.connectplus.access.AccessService stubService = new dev.connectplus.access.AccessService(
                white, black, new dev.connectplus.access.AccessService.ConfiguredFlags(false, false),
                Runnable::run);
        final dev.connectplus.access.AccessTargetResolver stubResolver = new dev.connectplus.access.AccessTargetResolver(
                stubService, dev.connectplus.access.AccessConnectionRegistry::new, () -> this.visits);
        return new AccessConsoleService(stubService, stubResolver,
                (kind, add, target) -> java.util.concurrent.CompletableFuture.failedStage(
                        new UnsupportedOperationException("not wired in this test")),
                Runnable::run);
    }

    @Test
    void statusReadsTheHostListenAddressAtQueryTimeNotAtStartup() throws Exception {
        //The host's proxy server only exists after plugin enable, so the listen
        //address must be read when the query runs, not captured once at startup
        final java.util.concurrent.atomic.AtomicReference<SocketAddress> live =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        final ConsoleCommandService lateBindingService = new ConsoleCommandService(
                () -> null,
                () -> this.endpoint,
                new ConsoleCommandService.Runtime("1.0.0-test", "3.4.13", live::get),
                () -> 0,
                this.playerStore,
                this.linkStore,
                () -> this.visits,
                accessConsoleStub(),
                this.accessService(),
                this.executor, this::lobbySnapshot);

        final String before = String.join("\n",
                lateBindingService.execute(new Command(Type.STATUS, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(before.contains("不可用"), "Before the host starts listening the address is unavailable: " + before);

        live.set(InetSocketAddress.createUnresolved("127.0.0.1", 25565));
        final String after = String.join("\n",
                lateBindingService.execute(new Command(Type.STATUS, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(after.contains("25565"), "A started host's listen address must appear: " + after);
    }

    private List<String> run(final Type type, final String argument) throws Exception {
        return this.service().execute(new Command(type, argument)).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private SessionRegistry lobby() {
        if (this.lobbyServer == null) {
            this.registry = new SessionRegistry();
            this.lobbyServer = new dev.connectplus.lobby.LobbyServer(this.registry,
                    new TokenStore(new File(this.dataDir, "lobby-data")),
                    new PlayerStore(new File(this.dataDir, "lobby-data/players")),
                    new dev.connectplus.testutil.RecordingSwitchInitiator());
            this.lobbyServer.start();
        }
        return this.registry;
    }

    // ---- status ----------------------------------------------------------

    @Test
    void englishConfigurationLocalizesLiveQueriesAndRootCommandOutput() throws Exception {
        CPConfig.language = "en";
        final AccessConnectionRegistry connections = new AccessConnectionRegistry();
        final OnlinePlayerQuery query = new OnlinePlayerQuery(() -> connections, () -> null);
        final UUID uuid = UUID.randomUUID();
        final ClientIdentity identity = ClientIdentity.verifiedJava(uuid, "Traveler", uuid);
        this.visits.recordVisit(uuid, "Traveler", identity);
        try (final OnlineConnection pc = new OnlineConnection(connections,
                new AccessSubject(AccessKey.ClientType.JAVA, "Traveler", uuid, null), identity, ProtocolVersion.v1_20_2)) {
            pc.serverVersion = ProtocolVersion.v1_21_4;
            final ConsoleCommandService service = this.service(query::snapshot);
            final String list = String.join("\n", service.execute(new Command(Type.LIST, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(list.contains("Traveler | Client version: Java 1.20.2 | Server version: 1.21.4"), list);
            final String info = String.join("\n", service.execute(new Command(Type.INFO, "Traveler")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(info.contains("Traveler [Online]") && info.contains("Current location: Other server (server version: 1.21.4)"), info);
            assertTrue(info.contains("Current server: private.example.com:25565"), info);
            for (final Type type : List.of(Type.STATUS, Type.LIST, Type.INFO, Type.HELP, Type.USAGE, Type.LINKS, Type.ACCOUNTS, Type.VERSION)) {
                final String output = String.join("\n", service.execute(new Command(type, "Traveler")).toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertFalse(java.util.regex.Pattern.compile("\\p{IsHan}").matcher(output).find(), type + ": " + output);
            }
        }
    }

    @Test
    void existingAndCustomLanguagePacksOverrideConsoleLinesWithoutLosingTranslations() throws Exception {
        final java.nio.file.Path languages = this.dataDir.toPath().resolve("languages");
        Files.createDirectories(languages);
        final String oldEnglish = "# Keep this comment\nConsole:\n  OnlineTotal: 'Custom total: {0}'\n";
        final String oldChinese = "# 保留此注释\nConsole:\n  OnlineTotal: '自定义在线：{0}'\n";
        Files.writeString(languages.resolve("en.yml"), oldEnglish);
        Files.writeString(languages.resolve("zh.yml"), oldChinese);
        Files.writeString(languages.resolve("fr.yml"), "Console:\n  OnlineTotal: 'Joueurs : {0}'\n");
        Languages.init(this.dataDir);
        CPConfig.language = "en";
        final List<String> english = this.run(Type.STATUS, null);
        assertTrue(english.contains("Custom total: 0"), english.toString());
        assertTrue(english.contains("    Players in lobby (0)"), english.toString());
        CPConfig.language = "zh";
        final List<String> chinese = this.run(Type.STATUS, null);
        assertTrue(chinese.contains("自定义在线：0"), chinese.toString());
        assertTrue(chinese.contains("    大厅中的玩家（0 人）"), chinese.toString());
        CPConfig.language = "fr";
        final List<String> custom = this.run(Type.STATUS, null);
        assertTrue(custom.contains("Joueurs : 0"), custom.toString());
        assertTrue(custom.contains("    Players in lobby (0)"), custom.toString());
        CPConfig.language = "auto";
        assertTrue(this.run(Type.STATUS, null).contains("Custom total: 0"), "A console without a locale falls back to English");
        assertTrue(Files.readString(languages.resolve("en.yml")).startsWith(oldEnglish));
        assertTrue(Files.readString(languages.resolve("zh.yml")).startsWith(oldChinese));
    }

    @Test
    void statusWorksWithoutALobbyAndReportsDisabledParts() throws Exception {
        final String oldMode = CPConfig.mode;
        CPConfig.mode = "proxy";
        try {
        final List<String> out = this.run(Type.STATUS, null);
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("proxy"), "The configured mode is shown: " + joined);
        assertTrue(joined.contains("未启用"), "The disabled lobby is labelled: " + joined);
        assertTrue(joined.contains("25565"), "The host listen address is shown: " + joined);
        assertTrue(out.contains("Geyser桥接插件: 未注册"), "The unregistered bridge is labelled: " + joined);
        } finally {
            CPConfig.mode = oldMode;
        }
    }

    @Test
    void statusShowsLobbyCountsAndBridgeRegistration() throws Exception {
        final String oldMode = CPConfig.mode;
        CPConfig.mode = "lobby";
        this.lobby();
        final String epoch = UUID.randomUUID().toString();
        final boolean oldGeyser = CPConfig.GeyserSupport.enabled;
        CPConfig.GeyserSupport.enabled = true;
        this.endpoint.register(Map.of("protocolVersion", 1, "providerId", BedrockBridgeEndpoint.PROVIDER_ID,
                "providerEpoch", epoch, "bridgeVersion", "1.0.2", "geyserVersion", "2.11.3-b1249 (git-master-f66329d)",
                "viaproxyVersion", "3.4.13", "capabilities", List.copyOf(BedrockBridgeEndpoint.REQUIRED_CAPABILITIES)),
                request -> java.util.concurrent.CompletableFuture.completedFuture(Map.of()));
        try {
            final List<String> out = this.run(Type.STATUS, null);
            final String joined = String.join("\n", out);
            assertTrue(joined.contains("lobby"), "The configured mode is shown: " + joined);
            assertTrue(out.contains("Geyser桥接插件: 已注册 (桥接插件版本 1.0.2, Geyser 版本 2.11.3-b1249 (git-master-f66329d))"), joined);
        } finally {
            CPConfig.GeyserSupport.enabled = oldGeyser;
            CPConfig.mode = oldMode;
        }
    }

    // ---- list ------------------------------------------------------------

    @Test
    void statusUsesTheSameOnlineGroupsAsListWithoutPlayerDetails() throws Exception {
        final var players = List.of(
                new OnlinePlayerQuery.Player(UUID.randomUUID(), "LobbyOnlyName", UUID.randomUUID(), null,
                        "Java 1.20.2", OnlinePlayerQuery.Location.LOBBY, null, null, null),
                new OnlinePlayerQuery.Player(UUID.randomUUID(), "ServerOnlyName", UUID.randomUUID(), null,
                        "基岩 1.21.50", OnlinePlayerQuery.Location.SERVER, "private.example.com:25565", "1.21.4", null),
                new OnlinePlayerQuery.Player(UUID.randomUUID(), "AnotherServerName", UUID.randomUUID(), null,
                        "Java 1.20.2", OnlinePlayerQuery.Location.SERVER, "private.example.com:25565", "1.21.4", null),
                new OnlinePlayerQuery.Player(UUID.randomUUID(), "SwitchingOnlyName", UUID.randomUUID(), null,
                        "Java 1.20.2", OnlinePlayerQuery.Location.SWITCHING, null, null, null));
        final ConsoleCommandService service = this.service(() -> new OnlinePlayerQuery.Snapshot(players, true));
        final List<String> status = service.execute(new Command(Type.STATUS, null)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        final List<String> list = service.execute(new Command(Type.LIST, null)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(status.contains("当前在线：4 人"), status.toString());
        assertTrue(list.contains("当前在线：4 人"), list.toString());
        for (final String count : List.of("大厅中的玩家（1 人）", "已连接其他服务器的玩家（2 人）", "切服中的玩家（1 人）")) {
            assertTrue(status.contains("    " + count), status.toString());
            assertTrue(list.contains(count), list.toString());
        }
        assertTrue(status.contains("    目标服务器在途连接: 未启用"), status.toString());
        final String joined = String.join("\n", status);
        assertFalse(joined.contains("大厅会话数") || joined.contains("private.example.com") || joined.contains("客户端版本："), joined);
        for (final var player : players) assertFalse(joined.contains(player.name()), joined);
    }

    @Test
    void statusShowsEmptyGroupCountsAndPreservesUnavailableQueryNotice() throws Exception {
        final ConsoleCommandService empty = this.service(() -> new OnlinePlayerQuery.Snapshot(List.of(), true));
        final List<String> status = empty.execute(new Command(Type.STATUS, null)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(status.contains("当前在线：0 人"), status.toString());
        assertTrue(status.contains("    大厅中的玩家（0 人）"), status.toString());
        assertTrue(status.contains("    已连接其他服务器的玩家（0 人）"), status.toString());
        assertFalse(status.stream().anyMatch(line -> line.contains("暂无玩家")), status.toString());
        final ConsoleCommandService unavailable = this.service(() -> new OnlinePlayerQuery.Snapshot(List.of(), false));
        final List<String> failed = unavailable.execute(new Command(Type.STATUS, null)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(failed.stream().anyMatch(line -> line.contains("部分连接状态不可用")), failed.toString());
    }

    @Test
    void listGroupsLobbyPlayersAndShowsOnlyClientVersions() throws Exception {
        this.lobby();

        final PlayerSession idle = new PlayerSession(UUID.nameUUIDFromBytes("a".getBytes(StandardCharsets.UTF_8)), "Zeta");
        final PlayerSession loggedIn = new PlayerSession(UUID.nameUUIDFromBytes("b".getBytes(StandardCharsets.UTF_8)), "Alpha");
        loggedIn.account = new StubAccount();
        final PlayerSession loggingIn = new PlayerSession(UUID.nameUUIDFromBytes("c".getBytes(StandardCharsets.UTF_8)), "Mike");
        loggingIn.account = new StubAccount();
        loggingIn.loginInProgress = true;
        idle.clientVersion = com.viaversion.viaversion.api.protocol.version.ProtocolVersion.v1_20;
        loggedIn.clientVersion = com.viaversion.viaversion.api.protocol.version.ProtocolVersion.v1_20_2;

        idle.connectionId = UUID.randomUUID();
        loggedIn.connectionId = UUID.randomUUID();
        loggingIn.connectionId = UUID.randomUUID();
        this.lobby().register(idle);
        this.lobby().register(loggedIn);
        this.lobby().register(loggingIn);

        final List<String> out = this.run(Type.LIST, null);
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("大厅中的玩家（3 人）"), joined);
        assertTrue(joined.contains("已连接其他服务器的玩家（0 人）"), joined);
        assertTrue(joined.contains("Alpha | 客户端版本：Java 1.20.2"), joined);
        assertTrue(joined.contains("Zeta | 客户端版本：Java 1.20-1.20.1"), joined);
        assertTrue(joined.indexOf("Alpha") < joined.indexOf("Mike") && joined.indexOf("Mike") < joined.indexOf("Zeta"), joined);
        assertFalse(joined.contains("微软") || joined.contains("后端") || joined.contains("登录中"), joined);
    }

    // ---- links -----------------------------------------------------------

    @Test
    void listHidesAddressesWhileInfoFollowsCurrentServerAcrossSwitchesAndDisconnects() throws Exception {
        this.lobby();
        final AccessConnectionRegistry connections = new AccessConnectionRegistry();
        final OnlinePlayerQuery query = new OnlinePlayerQuery(() -> connections, () -> this.lobbyServer);
        final UUID uuid = UUID.randomUUID();
        final ClientIdentity identity = ClientIdentity.verifiedJava(uuid, "Traveler", uuid);
        this.visits.recordVisit(uuid, "Traveler", identity);
        final ConsoleCommandService service = this.service(query::snapshot);
        try (final OnlineConnection pc = new OnlineConnection(connections,
                new AccessSubject(AccessKey.ClientType.JAVA, "Traveler", uuid, null), identity, ProtocolVersion.v1_20_2)) {
            final PlayerSession session = new PlayerSession(uuid, "Traveler");
            session.connectionId = pc.connectionId;
            session.c2pChannel = pc.client;
            session.lobbyChannel = pc.backend;
            pc.client.attr(dev.connectplus.compat.CPAttributeKeys.LOBBY_SESSION).set(session);
            this.registry.register(session);
            pc.address = this.lobbyServer.localAddress();
            pc.setP2sConnectionState(net.raphimc.netminecraft.constants.ConnectionState.LOGIN);
            final String joining = String.join("\n", service.execute(new Command(Type.LIST, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(joining.contains("大厅中的玩家（0 人）") && joining.contains("切服中的玩家（1 人）"), joining);
            pc.setP2sConnectionState(net.raphimc.netminecraft.constants.ConnectionState.PLAY);
            String list = String.join("\n", service.execute(new Command(Type.LIST, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(list.contains("大厅中的玩家（1 人）"), list);
            assertTrue(list.contains("已连接其他服务器的玩家（0 人）"), list);
            final String lobbyInfo = String.join("\n", service.execute(new Command(Type.INFO, "Traveler")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertFalse(lobbyInfo.contains("当前服务器"), lobbyInfo);
            assertFalse(lobbyInfo.contains("服务器版本"), lobbyInfo);
            assertFalse(list.contains("服务器版本"), list);

            this.registry.release(session, session.generation);
            pc.address = InetSocketAddress.createUnresolved("private.example.com", 25565);
            pc.serverVersion = ProtocolVersion.v1_21_4;
            list = String.join("\n", service.execute(new Command(Type.LIST, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(list.contains("大厅中的玩家（0 人）"), list);
            assertTrue(list.contains("已连接其他服务器的玩家（1 人）"), list);
            assertEquals(1, list.lines().filter(line -> line.contains("Traveler")).count(), list);
            assertTrue(list.contains("Traveler | 客户端版本：Java 1.20.2 | 服务器版本：1.21.4"), list);
            assertFalse(list.contains("private.example.com") || list.contains("25565") || list.contains("微软") || list.contains("后端"), list);
            final String info = String.join("\n", service.execute(new Command(Type.INFO, "traveler")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(info.contains("Traveler [在线]") && info.contains("当前位置：其他服务器（服务器版本：1.21.4）"), info);
            assertTrue(info.contains("当前服务器：private.example.com:25565"), info);

            pc.serverVersion = null;
            final String unknown = String.join("\n", service.execute(new Command(Type.INFO, "Traveler")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(unknown.contains("当前位置：其他服务器（服务器版本：未知）"), unknown);
            assertTrue(unknown.contains("客户端：Java 1.20.2"), unknown);

            pc.address = this.lobbyServer.localAddress();
            this.registry.register(session);
            list = String.join("\n", service.execute(new Command(Type.LIST, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(list.contains("大厅中的玩家（1 人）") && list.contains("已连接其他服务器的玩家（0 人）"), list);
            final String returned = String.join("\n", service.execute(new Command(Type.INFO, "Traveler")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertFalse(returned.contains("当前服务器"), returned);
            assertFalse(returned.contains("服务器版本"), returned);

            pc.client.close();
            list = String.join("\n", service.execute(new Command(Type.LIST, null)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertFalse(list.contains("Traveler"), list);
            final String after = String.join("\n", service.execute(new Command(Type.INFO, "Traveler")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(after.contains("Traveler [离线]"), after);
            assertFalse(after.contains("当前服务器"), after);
        }
    }

    @Test
    void infoFindsBackendPlayerByOldNameWithoutBorrowingTheStatusOfALinkedAccount() throws Exception {
        final AccessConnectionRegistry connections = new AccessConnectionRegistry();
        final OnlinePlayerQuery query = new OnlinePlayerQuery(() -> connections, () -> null);
        final UUID java = UUID.randomUUID();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordKnownJavaName(java, "OldName");
        this.visits.recordVisit(wire, "LinkedBedrock", ClientIdentity.verifiedBedrock(wire, "LinkedBedrock", xuid, UUID.randomUUID(), "s"));
        this.linkStore.replace(0, Map.of(xuid, java));
        try (final OnlineConnection pc = new OnlineConnection(connections,
                new AccessSubject(AccessKey.ClientType.JAVA, "CurrentName", java, null),
                ClientIdentity.verifiedJava(java, "CurrentName", java), ProtocolVersion.v1_20_2)) {
            final ConsoleCommandService service = this.service(query::snapshot);
            final String javaInfo = String.join("\n", service.execute(new Command(Type.INFO, "OldName")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(javaInfo.contains("CurrentName [在线]") && javaInfo.contains("当前位置：其他服务器"), javaInfo);
            final String bedrockInfo = String.join("\n", service.execute(new Command(Type.INFO, "LinkedBedrock")).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(bedrockInfo.contains("LinkedBedrock [离线]"), bedrockInfo);
        }
    }

    @Test
    void failedOnlineQueryDoesNotReportHistoricalPlayersAsOffline() throws Exception {
        final UUID uuid = UUID.randomUUID();
        this.visits.recordKnownJavaName(uuid, "MaybeOnline");
        final ConsoleCommandService service = this.service(() -> { throw new IllegalStateException("host unavailable"); });
        final String info = String.join("\n", service.execute(new Command(Type.INFO, "MaybeOnline")).toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(info.contains("MaybeOnline [在线状态不可用]"), info);
        assertFalse(info.contains("[离线]"), info);
    }

    @Test
    void linksListsTheCommittedSnapshot() throws Exception {
        final UUID javaUuid = UUID.nameUUIDFromBytes("link-a".getBytes(StandardCharsets.UTF_8));
        this.linkStore.replace(0, Map.of("12345678901234567", javaUuid));
        final List<String> out = this.run(Type.LINKS, null);
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("12345678901234567"), joined);
        assertTrue(joined.contains(javaUuid.toString()), joined);
    }

    @Test
    void corruptLinkIndexIsReportedNotSilentlyEmpty() throws Exception {
        this.playerStore.playersDir().mkdirs();
        Files.writeString(new File(this.playerStore.playersDir(), "identity-links.json").toPath(),
                "{ not valid json", StandardCharsets.UTF_8);
        final List<String> out = this.run(Type.LINKS, null);
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("不可用") || joined.contains("损坏"), joined);
        assertFalse(joined.contains("暂无"), "A corrupt index must not read as 'no links': " + joined);
    }

    // ---- info ------------------------------------------------------------

    private PlayerSession onlineIdentity(final String name, final ClientIdentity identity, final ProfileKey profile) {
        final PlayerSession session = new PlayerSession(identity.wireUuid(), name);
        session.connectionId = UUID.randomUUID();
        session.c2pChannel = new io.netty.channel.embedded.EmbeddedChannel();
        session.c2pChannel.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).set(identity);
        session.profileKey = profile;
        session.playerData = new PlayerData(profile);
        this.lobby().register(session);
        return session;
    }

    @Test
    void infoMergesOnlineVisitAndKnownNameIntoFourJavaLines() throws Exception {
        final UUID java = UUID.randomUUID();
        final UUID bedrockWire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordVisit(java, "JavaPlayer", ClientIdentity.verifiedJava(java, "JavaPlayer", java));
        this.visits.recordKnownJavaName(java, "JavaPlayer");
        this.visits.recordVisit(bedrockWire, "Bedrock Player", ClientIdentity.verifiedBedrock(bedrockWire, "Bedrock Player", xuid, UUID.randomUUID(), "s"));
        this.linkStore.replace(0, Map.of(xuid, java));
        final PlayerSession session = onlineIdentity("JavaPlayer", ClientIdentity.verifiedJava(java, "JavaPlayer", java), ProfileKey.javaProfile(java));
        try {
            assertEquals(List.of("JavaPlayer [在线]", "  当前位置：大厅", "  客户端：Java（版本未知）", "  JavaUUID: " + java, "  书签数: 0",
                    "  关联: 已关联 ← Bedrock Player(XUID: " + xuid + ")",
                    "  是否在白名单：否", "  是否在黑名单：否",
                    "  已绑定账号：Bedrock Player", "    平台：基岩",
                    "    是否在白名单：否", "    是否在黑名单：否"), this.run(Type.INFO, "javaplayer"));
        } finally { session.c2pChannel.close(); }
    }

    @Test
    void linkedOnlineBedrockShowsItsXuidAndCurrentJavaName() throws Exception {
        final UUID java = UUID.randomUUID();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordVisit(wire, "Bedrock Player", ClientIdentity.verifiedBedrock(wire, "Bedrock Player", xuid, UUID.randomUUID(), "s"));
        this.visits.recordKnownJavaName(java, "KnownJavaName");
        this.linkStore.replace(0, Map.of(xuid, java));
        final PlayerSession session = onlineIdentity("Bedrock Player", ClientIdentity.verifiedBedrock(wire, "Bedrock Player", xuid, UUID.randomUUID(), "s"), ProfileKey.javaProfile(java));
        try {
            assertEquals(List.of("Bedrock Player [在线]", "  当前位置：大厅", "  客户端：基岩（版本未知）", "  XUID: " + xuid, "  书签数: 0",
                    "  关联: 已关联 → KnownJavaName(JavaUUID: " + java + ")",
                    "  是否在白名单：否", "  是否在黑名单：否",
                    "  已绑定账号：KnownJavaName", "    平台：Java",
                    "    是否在白名单：否", "    是否在黑名单：否"), this.run(Type.INFO, "BEDROCK PLAYER"));
        } finally { session.c2pChannel.close(); }
    }

    @Test
    void offlineJavaVisitAndKnownNameAreOneIdentity() throws Exception {
        final UUID java = UUID.randomUUID();
        this.visits.recordVisit(java, "OfflinePlayer", ClientIdentity.verifiedJava(java, "OfflinePlayer", java));
        this.visits.recordKnownJavaName(java, "OfflinePlayer");
        this.playerStore.save(new PlayerData(ProfileKey.javaProfile(java)));
        assertEquals(List.of("OfflinePlayer [离线]", "  JavaUUID: " + java, "  书签数: 0", "  关联: 未关联",
                "  是否在白名单：否", "  是否在黑名单：否", "  绑定账号：未绑定"), this.run(Type.INFO, "offlineplayer"));
    }

    @Test
    void linksDisplaysNamesAndIdsWithoutCreatingProfiles() throws Exception {
        final UUID java = UUID.randomUUID();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordKnownJavaName(java, "JavaName");
        this.visits.recordVisit(wire, "Bedrock Name", ClientIdentity.verifiedBedrock(wire, "Bedrock Name", xuid, UUID.randomUUID(), "s"));
        this.linkStore.replace(0, Map.of(xuid, java));
        assertEquals(List.of("Bedrock Name(XUID: " + xuid + ") → JavaName(JavaUUID: " + java + ")"), this.run(Type.LINKS, null));
        assertFalse(this.playerStore.exists(ProfileKey.javaProfile(java)));
        assertFalse(this.playerStore.exists(ProfileKey.bedrockProfile(xuid)));
    }

    @Test
    void corruptVisitIndexDoesNotAlsoClaimThePlayerDoesNotExist() throws Exception {
        Files.writeString(new File(this.dataDir, "visits-index.json").toPath(), "{ bad json", StandardCharsets.UTF_8);
        final String result = String.join("\n", this.run(Type.INFO, "NoPlayer"));
        assertTrue(result.contains("不可用"));
        assertFalse(result.contains("未找到"));
    }

    @Test
    void debugOnEmitsAnImmediateDiagnosticToProveTheSwitchWorks() throws Exception {
        final java.util.List<org.apache.logging.log4j.core.LogEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final var capture = new org.apache.logging.log4j.core.appender.AbstractAppender("ConsoleDebugImmediate", null,
                org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) { events.add(event.toImmutable()); }
        };
        capture.start(); this.logger.addAppender(capture);
        try {
            this.run(Type.DEBUG, "on");
            assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                    && e.getMessage().getFormattedMessage().contains("ConnectPlus diagnostic logging enabled")));
            events.clear();
            this.run(Type.DEBUG, "off");
            DebugLog.log("CPDEBUG_OFF_MUST_NOT_APPEAR");
            assertTrue(events.isEmpty());
        } finally { this.logger.removeAppender(capture); capture.stop(); }
    }

    @Test
    void debugOnDoesNotClaimSuccessWhenTheBackendBlocksDebug() throws Exception {
        this.logger.setLevel(Level.INFO);
        final String result = String.join("\n", this.run(Type.DEBUG, "on"));
        assertTrue(result.contains("未允许 DEBUG"), result);
        assertFalse(result.contains("诊断日志已开启"), result);
        assertFalse(DebugLog.enabled());
        assertEquals(Level.INFO, this.logger.getLevel(), "The command must not alter host logging configuration");
    }

    @Test
    void infoFindsOfflineIdentitiesByNameCaseInsensitively() throws Exception {
        final UUID javaUuid = UUID.nameUUIDFromBytes("info-java".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(javaUuid, "JavaPlayer", ClientIdentity.verifiedJava(javaUuid, "JavaPlayer", javaUuid));
        final UUID wire = UUID.nameUUIDFromBytes("info-bedrock".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(wire, "Bedrock Player", ClientIdentity.verifiedBedrock(wire, "Bedrock Player", "98765432109876543", UUID.randomUUID(), "s"));

        final List<String> javaHit = this.run(Type.INFO, "javaplayer");
        final String javaJoined = String.join("\n", javaHit);
        assertTrue(javaJoined.contains("JavaPlayer"), javaJoined);
        assertTrue(javaJoined.contains(javaUuid.toString()), javaJoined);

        final List<String> bedrockHit = this.run(Type.INFO, "BEDROCK PLAYER");
        final String bedrockJoined = String.join("\n", bedrockHit);
        assertTrue(bedrockJoined.contains("Bedrock Player"), bedrockJoined);
        assertTrue(bedrockJoined.contains("98765432109876543"), bedrockJoined);
    }

    @Test
    void infoListsEverySameNameIdentity() throws Exception {
        final UUID javaUuid = UUID.nameUUIDFromBytes("dup-1".getBytes(StandardCharsets.UTF_8));
        final UUID bedrockWire = UUID.nameUUIDFromBytes("dup-2".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(javaUuid, "SameName", ClientIdentity.verifiedJava(javaUuid, "SameName", javaUuid));
        this.visits.recordVisit(bedrockWire, "SameName", ClientIdentity.verifiedBedrock(bedrockWire, "SameName", "11111111111111111", UUID.randomUUID(), "s"));

        final List<String> out = this.run(Type.INFO, "samename");
        final String joined = String.join("\n", out);
        assertTrue(joined.contains(javaUuid.toString()), joined);
        assertTrue(joined.contains("11111111111111111"), joined);
        assertTrue(joined.contains("JavaUUID: " + javaUuid), "Both original identities remain distinct: " + joined);
        assertTrue(joined.contains("XUID: 11111111111111111"), joined);
    }

    @Test
    void infoShowsTheLinkedProfilesBookmarksAndTheLinkState() throws Exception {
        final UUID javaUuid = UUID.nameUUIDFromBytes("link-info".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(UUID.nameUUIDFromBytes("link-wire".getBytes(StandardCharsets.UTF_8)), "BedrockPlayer",
                ClientIdentity.verifiedBedrock(UUID.nameUUIDFromBytes("link-wire".getBytes(StandardCharsets.UTF_8)), "BedrockPlayer", "12345678901234567", UUID.randomUUID(), "s"));
        this.linkStore.replace(0, Map.of("12345678901234567", javaUuid));
        this.visits.recordKnownJavaName(javaUuid, "JavaLinked");
        final PlayerData data = new PlayerData(ProfileKey.javaProfile(javaUuid));
        data.bookmarks.add(new dev.connectplus.session.Bookmark("bm1", "bm.example.net", null, 1, 2));
        data.bookmarks.add(new dev.connectplus.session.Bookmark("bm2", "bm2.example.net", null, 1, 2));
        this.playerStore.save(data);

        final List<String> out = this.run(Type.INFO, "javalinked");
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("2"), "The linked Java profile's bookmark count is shown: " + joined);
        assertTrue(joined.contains("已关联") || joined.contains("12345678901234567"),
                "The link state is shown: " + joined);
    }

    @Test
    void missingProfileIsReportedUnavailableWithoutCreatingAFile() throws Exception {
        final UUID javaUuid = UUID.nameUUIDFromBytes("no-profile".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(javaUuid, "NoProfile", ClientIdentity.verifiedJava(javaUuid, "NoProfile", javaUuid));
        final File profileFile = new File(new File(this.playerStore.playersDir(), "java"), javaUuid + ".json");

        final List<String> out = this.run(Type.INFO, "noprofile");
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("不可用"), "A missing profile is unavailable, not zero: " + joined);
        assertFalse(profileFile.exists(), "A query must never create the profile file");
    }

    // ---- accounts --------------------------------------------------------

    @Test
    void namelessVisitsUseUnknownNamesInsteadOfNullText() throws Exception {
        final UUID java = UUID.randomUUID();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordVisit(java, null, ClientIdentity.verifiedJava(java, null, java));
        this.visits.recordVisit(wire, null, ClientIdentity.verifiedBedrock(wire, null, xuid, UUID.randomUUID(), "s"));
        this.linkStore.replace(0, Map.of(xuid, java));
        assertEquals(List.of("未知玩家名(XUID: " + xuid + ") → 未知玩家名(JavaUUID: " + java + ")"), this.run(Type.LINKS, null));
    }

    @Test
    void linksKeepsIdsWhenCounterpartyNamesHaveNeverBeenRecorded() throws Exception {
        final UUID java = UUID.randomUUID();
        this.linkStore.replace(0, Map.of("2535429616021489", java));
        assertEquals(List.of("未知玩家名(XUID: 2535429616021489) → 未知玩家名(JavaUUID: " + java + ")"), this.run(Type.LINKS, null));
    }

    @Test
    void knownOldNameFindsTheCurrentOnlineJavaIdentityOnce() throws Exception {
        final UUID java = UUID.randomUUID();
        this.visits.recordKnownJavaName(java, "OldName");
        final PlayerSession session = onlineIdentity("CurrentName", ClientIdentity.verifiedJava(java, "CurrentName", java), ProfileKey.javaProfile(java));
        try {
            assertEquals(List.of("CurrentName [在线]", "  当前位置：大厅", "  客户端：Java（版本未知）", "  JavaUUID: " + java, "  书签数: 0", "  关联: 未关联",
                    "  是否在白名单：否", "  是否在黑名单：否", "  绑定账号：未绑定"), this.run(Type.INFO, "OldName"));
        } finally { session.c2pChannel.close(); }
    }

    @Test
    void linkedOfflineBedrockNeverUsesItsDeletedIndependentProfile() throws Exception {
        final UUID java = UUID.randomUUID();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordVisit(wire, "Bedrock", ClientIdentity.verifiedBedrock(wire, "Bedrock", xuid, UUID.randomUUID(), "s"));
        this.visits.recordKnownJavaName(java, "JavaName");
        this.linkStore.replace(0, Map.of(xuid, java));
        this.playerStore.save(new PlayerData(ProfileKey.javaProfile(java)));
        assertEquals(List.of("Bedrock [离线]", "  XUID: " + xuid, "  书签数: 0",
                "  关联: 已关联 → JavaName(JavaUUID: " + java + ")",
                "  是否在白名单：否", "  是否在黑名单：否",
                "  已绑定账号：JavaName", "    平台：Java",
                "    是否在白名单：否", "    是否在黑名单：否"), this.run(Type.INFO, "bedrock"));
        assertFalse(this.playerStore.exists(ProfileKey.bedrockProfile(xuid)));
    }

    @Test
    void currentSessionNamesWinOverHistoricalLinkNames() throws Exception {
        final UUID java = UUID.randomUUID();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordKnownJavaName(java, "OldJava");
        this.visits.recordVisit(wire, "OldBedrock", ClientIdentity.verifiedBedrock(wire, "OldBedrock", xuid, UUID.randomUUID(), "s"));
        this.linkStore.replace(0, Map.of(xuid, java));
        final PlayerSession javaSession = onlineIdentity("LiveJava", ClientIdentity.verifiedJava(java, "LiveJava", java), ProfileKey.javaProfile(java));
        final PlayerSession bedrockSession = onlineIdentity("LiveBedrock", ClientIdentity.verifiedBedrock(wire, "LiveBedrock", xuid, UUID.randomUUID(), "s"), ProfileKey.javaProfile(java));
        try {
            assertEquals(List.of("LiveBedrock(XUID: " + xuid + ") → LiveJava(JavaUUID: " + java + ")"), this.run(Type.LINKS, null));
        } finally { javaSession.c2pChannel.close(); bedrockSession.c2pChannel.close(); }
    }

    @Test
    void unverifiedOnlineIdentityDoesNotBorrowAJavaIdentityByName() throws Exception {
        final UUID java = UUID.randomUUID();
        final UUID wire = UUID.randomUUID();
        this.visits.recordKnownJavaName(java, "SameName");
        final PlayerSession session = new PlayerSession(wire, "SameName");
        session.connectionId = UUID.randomUUID();
        this.lobby().register(session);
        final List<String> output = this.run(Type.INFO, "samename");
        final int onlineHeader = output.indexOf("SameName [在线]");
        final int offlineHeader = output.indexOf("SameName [离线]");
        assertTrue(onlineHeader >= 0 && onlineHeader < offlineHeader, String.join("\n", output));
        assertEquals("  协议UUID: " + wire + " (身份未确认)", output.get(onlineHeader + 3));
        assertTrue(output.get(onlineHeader + 4).contains("不可用"));
        // The offline KNOWN-NAME match keeps its lines after the online block.
        assertEquals("  JavaUUID: " + java, output.get(offlineHeader + 1));
        assertFalse(this.playerStore.exists(ProfileKey.javaProfile(java)));
    }

    @Test
    void corruptLinksDoNotSendOfflineBedrockToAnUnlinkedProfile() throws Exception {
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        this.visits.recordVisit(wire, "Bedrock", ClientIdentity.verifiedBedrock(wire, "Bedrock", xuid, UUID.randomUUID(), "s"));
        this.playerStore.playersDir().mkdirs();
        Files.writeString(new File(this.playerStore.playersDir(), "identity-links.json").toPath(), "{ bad json");
        assertEquals(List.of("Bedrock [离线]", "  XUID: " + xuid, "  书签数: 不可用", "  关联: 不可用 (关联索引损坏)",
                "  是否在白名单：否", "  是否在黑名单：否", "  绑定账号：不可用 (关联索引损坏)"), this.run(Type.INFO, "bedrock"));
        assertFalse(this.playerStore.exists(ProfileKey.bedrockProfile(xuid)));
    }

    @Test
    void accountsUsesTheManualTwoOneTwoNumbers() throws Exception {
        final UUID javaUuid = UUID.nameUUIDFromBytes("acc-java".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(javaUuid, "JavaPlayer", ClientIdentity.verifiedJava(javaUuid, "JavaPlayer", javaUuid));
        final UUID wire = UUID.nameUUIDFromBytes("acc-bedrock".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(wire, "BedrockPlayer", ClientIdentity.verifiedBedrock(wire, "BedrockPlayer", "12345678901234567", UUID.randomUUID(), "s"));

        findLine(this.run(Type.ACCOUNTS, null), "总数 2");
        this.linkStore.replace(0, Map.of("12345678901234567", javaUuid));
        findLine(this.run(Type.ACCOUNTS, null), "总数 1");
        this.linkStore.replace(1, Map.of());
        findLine(this.run(Type.ACCOUNTS, null), "总数 2");
    }

    @Test
    void corruptVisitIndexNeverReportsZeroVisitors() throws Exception {
        Files.writeString(new File(this.dataDir, "visits-index.json").toPath(),
                "{\"schemaVersion\":9}", StandardCharsets.UTF_8);
        final List<String> out = this.run(Type.ACCOUNTS, null);
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("不可用") || joined.contains("损坏"), joined);
        assertFalse(joined.contains("总数 0"), "A corrupt index must never claim zero visitors: " + joined);
    }

    // ---- version ---------------------------------------------------------

    @Test
    void versionReadsRuntimeSuppliedValuesAndBridgeRegistration() throws Exception {
        final String epoch = UUID.randomUUID().toString();
        final boolean oldGeyser = CPConfig.GeyserSupport.enabled;
        CPConfig.GeyserSupport.enabled = true;
        this.endpoint.register(Map.of("protocolVersion", 1, "providerId", BedrockBridgeEndpoint.PROVIDER_ID,
                "providerEpoch", epoch, "bridgeVersion", "9.9.9-bridge", "geyserVersion", "9.9.9-geyser",
                "viaproxyVersion", "3.4.13", "capabilities", List.copyOf(BedrockBridgeEndpoint.REQUIRED_CAPABILITIES)),
                request -> java.util.concurrent.CompletableFuture.completedFuture(Map.of()));
        final List<String> out = this.run(Type.VERSION, null);
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("1.0.0-test"), "The running plugin version is shown: " + joined);
        assertTrue(joined.contains("3.4.13"), "The running host version is shown: " + joined);
        assertTrue(joined.contains("9.9.9-bridge") && joined.contains("9.9.9-geyser"), joined);
        assertTrue(joined.contains("3.4.13"), joined);
        CPConfig.GeyserSupport.enabled = oldGeyser;
    }

    @Test
    void versionShowsUnregisteredAfterTheBridgeStops() throws Exception {
        final String epoch = UUID.randomUUID().toString();
        final boolean oldGeyser = CPConfig.GeyserSupport.enabled;
        CPConfig.GeyserSupport.enabled = true;
        final String registrationId = (String) this.endpoint.register(Map.of("protocolVersion", 1,
                "providerId", BedrockBridgeEndpoint.PROVIDER_ID, "providerEpoch", epoch,
                "bridgeVersion", "9.9.9", "geyserVersion", "9.9.9", "viaproxyVersion", "3.4.13",
                "capabilities", List.copyOf(BedrockBridgeEndpoint.REQUIRED_CAPABILITIES)),
                request -> java.util.concurrent.CompletableFuture.completedFuture(Map.of())).get("registrationId");
        this.endpoint.handleEvent(registrationId, Map.of("protocolVersion", 1, "providerEpoch", epoch,
                "op", "PROVIDER_STOPPING", "reasonCode", "TEST")).toCompletableFuture().join();

        final List<String> out = this.run(Type.VERSION, null);
        final String joined = String.join("\n", out);
        assertTrue(joined.contains("未注册"), "A stopped bridge is unregistered: " + joined);
        assertFalse(joined.contains("9.9.9"), "Stopped bridge versions are not shown: " + joined);
        CPConfig.GeyserSupport.enabled = oldGeyser;
    }

    // ---- debug -----------------------------------------------------------

    @Test
    void debugSwitchTakesEffectImmediatelyWithoutTouchingTheConfigFile() throws Exception {
        final File configFile = new File(this.dataDir, "config.yml");
        Files.writeString(configFile.toPath(), "debug: false\n", StandardCharsets.UTF_8);
        final byte[] before = Files.readAllBytes(configFile.toPath());
        CPConfig.debug = false;

        final List<String> on = this.run(Type.DEBUG, "on");
        assertTrue(String.join("\n", on).contains("true") || String.join("\n", on).contains("开启"), String.join("\n", on));
        assertTrue(DebugLog.enabled(), "The runtime switch must gate DebugLog immediately");

        final List<String> off = this.run(Type.DEBUG, "off");
        assertFalse(DebugLog.enabled(), "The runtime switch must disable diagnostics immediately");
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(configFile.toPath())),
                "The config file must never be rewritten by the command");
    }

    // ---- token hygiene ---------------------------------------------------

    @Test
    void accountOutputsNeverContainStoredTokenMaterial() throws Exception {
        final UUID javaUuid = UUID.nameUUIDFromBytes("tok".getBytes(StandardCharsets.UTF_8));
        this.visits.recordVisit(javaUuid, "TokPlayer", ClientIdentity.verifiedJava(javaUuid, "TokPlayer", javaUuid));
        this.visits.recordKnownJavaName(javaUuid, "TokPlayer");
        final PlayerData data = new PlayerData(ProfileKey.javaProfile(javaUuid));
        data.accountBlob = "SECRET-TOKEN-MARKER-123";
        this.playerStore.save(data);

        final String accounts = String.join("\n", this.run(Type.ACCOUNTS, null));
        final String info = String.join("\n", this.run(Type.INFO, "tokplayer"));
        final String links = String.join("\n", this.run(Type.LINKS, null));
        for (final String out : List.of(accounts, info, links)) {
            assertFalse(out.contains("SECRET-TOKEN-MARKER"), "No token material may leak into console output");
        }
    }

    private static String findLine(final List<String> lines, final String needle) {
        return lines.stream().filter(line -> line.contains(needle)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing a line containing '" + needle + "' in " + lines));
    }

    // ---- access membership display (task 7) ----------------------------------

    private static final java.util.UUID MEMBER_JAVA =
            java.util.UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final String MEMBER_XUID = "2533274790000001";
    private static final java.util.UUID MEMBER_WIRE =
            java.util.UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa");

    @Test
    void infoUsesExactMembershipLabelsForBothLinkedAccounts() throws Exception {
        final AccessService access = this.accessService();
        this.linkStore.replace(this.linkStore.snapshot().revision(), Map.of(MEMBER_XUID, MEMBER_JAVA));
        // The java side is on the whitelist only; the bedrock side on neither list.
        access.mutateNow(dev.connectplus.access.AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "B", MEMBER_JAVA, null)), java.util.Set.of());
        this.visits.recordKnownJavaName(MEMBER_JAVA, "B");
        this.visits.recordVisit(MEMBER_WIRE, "A", ClientIdentity.verifiedBedrock(
                MEMBER_WIRE, "A", MEMBER_XUID, UUID.randomUUID(), "s"));

        final String out = String.join("\n", this.service().execute(
                new Command(Type.INFO, "B")).toCompletableFuture().get(10, TimeUnit.SECONDS));

        // The queried (java) side sits ABOVE the bound (bedrock) side, each with its
        // own real membership, exact prefixes and the platform/identifier fields.
        assertTrue(out.contains("是否在白名单：是"), out);
        assertTrue(out.contains("是否在黑名单：否"), out);
        assertTrue(out.contains("已绑定账号："), out);
        assertTrue(out.contains(String.valueOf(MEMBER_XUID)), out);
        final int javaMembership = out.indexOf("是否在白名单：是");
        final int boundBlock = out.indexOf("已绑定账号：");
        assertTrue(javaMembership >= 0 && boundBlock > javaMembership, "查询侧在上，绑定侧在下: " + out);
    }

    @Test
    void infoShowsMembershipWhenSwitchesAreOff() throws Exception {
        final AccessService access = this.accessService(); // both switches off
        access.mutateNow(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "B", MEMBER_JAVA, null)), java.util.Set.of());
        this.visits.recordKnownJavaName(MEMBER_JAVA, "B");

        final String out = String.join("\n", this.service().execute(
                new Command(Type.INFO, "B")).toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertTrue(out.contains("是否在黑名单：是"), "membership is factual even with the switch off: " + out);
    }

    @Test
    void unavailableDataNeverAppearsAsNoMembership() throws Exception {
        // A missing ENABLED whitelist has no valid data: the display says so instead
        // of the misleading "否".
        final java.nio.file.Path wl = java.nio.file.Path.of(dataDir.getAbsolutePath(), "wl-missing.json");
        final java.nio.file.Path bl = java.nio.file.Path.of(dataDir.getAbsolutePath(), "bl-missing.json");
        Files.writeString(bl, "{\"schemaVersion\": 1, \"players\": []}");
        final AccessService access = new AccessService(new AccessListStore(wl), new AccessListStore(bl),
                new AccessService.ConfiguredFlags(true, false), Runnable::run);
        access.initialize().toCompletableFuture().join();
        this.visits.recordKnownJavaName(MEMBER_JAVA, "B");
        final ConsoleCommandService lateBindingService = new ConsoleCommandService(
                () -> null,
                () -> this.endpoint,
                new ConsoleCommandService.Runtime("1.0.0-test", "3.4.13", () -> null),
                () -> 0,
                this.playerStore,
                this.linkStore,
                () -> this.visits,
                accessConsoleStub(),
                access,
                this.executor, this::lobbySnapshot);

        final String out = String.join("\n", lateBindingService.execute(
                new Command(Type.INFO, "B")).toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertTrue(out.contains("是否在白名单：不可用"), out);
        assertFalse(out.contains("是否在白名单：否"), out);
    }

    @Test
    void infoWithoutBedrockXuidReportsUnavailableMembership() throws Exception {
        final AccessService access = this.accessService();
        // A wire-only visitor has no confirmed XUID: no membership can be derived.
        this.visits.recordVisit(UUID.randomUUID(), "WireGuy", null);

        final String out = String.join("\n", this.service().execute(
                new Command(Type.INFO, "WireGuy")).toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertTrue(out.contains("是否在白名单：不可用"), out);
        assertTrue(out.contains("是否在黑名单：不可用"), out);
    }

    @Test
    void statusUsesFourIndentedLinesForEachAccessList() throws Exception {
        final AccessService access = this.accessService();
        access.mutateNow(dev.connectplus.access.AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "A", MEMBER_JAVA, null)), java.util.Set.of());
        access.setEnabled(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST, true).toCompletableFuture().join();

        final List<String> out = this.run(Type.STATUS, null);
        final int white = out.indexOf("白名单状态:");
        final int black = out.indexOf("黑名单状态:");
        assertTrue(white >= 0 && black > white, out.toString());
        assertEquals(List.of("白名单状态:", "    配置文件: off,", "    当前状态: off,",
                "    白名单账号数: 1"), out.subList(white, white + 4));
        assertEquals(List.of("黑名单状态:", "    配置文件: off,", "    当前状态: on,",
                "    黑名单账号数: 0"), out.subList(black, black + 4));
        assertFalse(String.join("\n", out).contains("数据:"));
    }

    @Test
    void helpIncludesShortAccessAliasesAndMandatoryPlatform() throws Exception {
        final String out = String.join("\n", this.run(Type.HELP, null));
        assertTrue(out.contains("cp wl"), out);
        assertTrue(out.contains("cp bl"), out);
        assertTrue(out.contains("java|bedrock"), out);
        assertTrue(out.contains("cp access check"), out);
    }
}
