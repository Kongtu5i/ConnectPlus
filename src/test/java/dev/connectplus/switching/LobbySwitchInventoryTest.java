package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.compat.SwitchableProxyConnection;
import dev.connectplus.config.CPConfig;
import dev.connectplus.session.ConnectionInfo;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LobbySwitchInventoryTest {
    private static final SocketAddress LOBBY = new InetSocketAddress("127.0.0.1", 25570);
    private final UUID player = UUID.randomUUID();
    private EmbeddedChannel client, oldBackend, pendingBackend;
    private TestConnection connection;
    private SwitchEngine engine;
    private SwitchSuppressionHandler suppression;
    private int oldRateLimit;
    @TempDir File dataDir;

    private final class TestConnection extends SwitchableProxyConnection {
        SocketAddress currentAddress = LOBBY;
        Throwable connectFailure;
        TestConnection() {
            super(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            this.channelFuture = oldBackend.newSucceededFuture();
        }
        @Override public SocketAddress getServerAddress() { return this.currentAddress; }
        @Override public ChannelFuture connectToServer(final SocketAddress address, final ProtocolVersion version) {
            this.currentAddress = address;
            if (connectFailure != null) return this.channelFuture = pendingBackend.newFailedFuture(connectFailure);
            // Keep login pending: the test observes the entire wait before JoinGame.
            return this.channelFuture = pendingBackend.newPromise();
        }
    }

    @BeforeEach
    void setUp() {
        ViaProxyTestConfig.init();
        this.oldRateLimit = CPConfig.maxConnectAttemptsPerMinute;
        CPConfig.maxConnectAttemptsPerMinute = 0;
        this.client = new EmbeddedChannel();
        this.oldBackend = new EmbeddedChannel();
        this.pendingBackend = new EmbeddedChannel();
        this.oldBackend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
        this.connection = new TestConnection();
        this.connection.setClientVersion(ProtocolVersion.v1_21_4);
        this.connection.setC2pConnectionState(ConnectionState.PLAY);
        this.engine = new SwitchEngine(() -> LOBBY);
        this.suppression = new SwitchSuppressionHandler(this.connection, this.engine);
        this.connection.getPacketHandlers().add(this.suppression);
    }

    @AfterEach
    void cleanUp() {
        this.client.finishAndReleaseAll();
        this.oldBackend.finishAndReleaseAll();
        this.pendingBackend.finishAndReleaseAll();
        CPConfig.maxConnectAttemptsPerMinute = this.oldRateLimit;
    }

    static Stream<Integer> supportedVersions() {
        return new java.util.ArrayList<>(ProtocolVersion.getProtocols()).stream()
                .filter(v -> v.getVersionType() == com.viaversion.viaversion.api.protocol.version.VersionType.RELEASE
                        && !v.isSnapshot() && v.newerThanOrEqualTo(ProtocolVersion.getProtocol(4))
                        && v.olderThanOrEqualTo(ProtocolVersion.getProtocol(777)))
                .map(ProtocolVersion::getVersion).distinct();
    }

    @ParameterizedTest
    @MethodSource("supportedVersions")
    void acceptedLobbySwitchClearsCursorAndClosesWindowBeforeTeardown(final int version) {
        this.connection.setClientVersion(ProtocolVersion.getProtocol(version));
        this.client.attr(CPAttributeKeys.LOBBY_WINDOW_ID).set(100);
        final AtomicInteger writesBeforeClose = new AtomicInteger();
        this.oldBackend.closeFuture().addListener(f -> writesBeforeClose.set(this.client.outboundMessages().size()));
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player"));
        this.client.runPendingTasks();
        assertTrue(this.suppression.currentJob().isSwitching());
        assertEquals(5, writesBeforeClose.get(), "Cursor, GUI, shortcut slots and selection must reset before the old backend is torn down");
        assertInventoryClosed(version, 100);
        final UnknownPacket click = new UnknownPacket(MCPackets.C2S_CONTAINER_CLICK.getId(version), new byte[]{1});
        assertFalse(this.suppression.handleC2P(click, new java.util.ArrayList<>()));
        assertTrue(this.pendingBackend.outboundMessages().isEmpty(), "Old GUI clicks must not leak into backend login");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mainAndBookmarkConnectButtonsCloseTheClientWindow(final boolean bookmark) {
        if (com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME == null) throw new IllegalStateException();
        final var lobby = new EmbeddedChannel();
        final UUID link = UUID.randomUUID();
        LobbyLink.register(link, this.connection);
        lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(link);
        try {
            final var sessionStore = new dev.connectplus.session.PlayerStore(new File(this.dataDir, "players"));
            final var handler = new dev.connectplus.lobby.LobbyServerHandler(java.util.Set.of(),
                    new dev.connectplus.session.SessionRegistry(), new dev.connectplus.accounts.TokenStore(this.dataDir),
                    sessionStore,
                    new dev.connectplus.identity.IdentityLinkStore(new File(this.dataDir, "players")),
                    new dev.connectplus.session.AccountSessionCoordinator(sessionStore, () -> null),
                    this.engine, null, new AtomicInteger());
            handler.loadSession(lobby, this.player, "Player");
            // This fixture tests inventory cleanup with no authentication flow.
            // Prepare its GUI data explicitly; real proxied joins wait for identity.
            handler.getSession().playerData = new dev.connectplus.session.PlayerData(this.player);
            handler.getSession().serverAddress = "127.0.0.1:25566";
            handler.getSession().targetVersion = ProtocolVersion.v1_21_4;
            handler.getSession().playerData.bookmarks.add(new dev.connectplus.session.Bookmark("Saved server", "127.0.0.1:25566", "1.21.4", 1, 2));
            final var screen = new dev.connectplus.lobby.screen.ScreenHandler(new dev.connectplus.lobby.states.StateHandler(handler, lobby));
            screen.openScreen(new dev.connectplus.lobby.screen.impl.MainScreen(dev.connectplus.lobby.screen.Lang.EN));
            if (bookmark) screen.openScreen(new dev.connectplus.lobby.screen.impl.BookmarkDetailScreen(dev.connectplus.lobby.screen.Lang.EN, "Saved server", 0));
            final int windowId = lobby.outboundMessages().stream()
                    .filter(dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket.class::isInstance)
                    .map(dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket.class::cast)
                    .reduce((first, last) -> last).orElseThrow().id;
            final var click = new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket();
            click.containerId = windowId;
            click.slot = bookmark ? 25 : 16;
            screen.handle(click);
            this.client.runPendingTasks();
            assertNotNull(this.suppression.currentJob());
            assertInventoryClosed(769, windowId);
        } finally {
            LobbyLink.unregister(link);
            lobby.finishAndReleaseAll();
        }
    }

    @Test
    void switchingAfterGuiCloseDoesNotClosePlayerInventory() {
        this.client.attr(CPAttributeKeys.LOBBY_WINDOW_ID).set(0);
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player"));
        this.client.runPendingTasks();
        assertEquals(4, this.client.outboundMessages().size());
        assertTrue(this.client.outboundMessages().stream().noneMatch(p -> p instanceof UnknownPacket packet
                && packet.packetId == MCPackets.S2C_CONTAINER_CLOSE.getId(769)), "There is no active chest to close");
        assertFalse(this.oldBackend.isActive());
    }

    @Test
    void rejectedSwitchLeavesTheGuiUntouched() {
        this.suppression.begin(SwitchJob.starting(new SwitchJob.Target("example.net", ProtocolVersion.v1_21_4,
                this.player, "Player", false, 1)));
        assertEquals(SwitchInitiator.StartResult.REJECTED_BUSY, this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player"));
        this.client.runPendingTasks();
        assertTrue(this.client.outboundMessages().isEmpty());
        assertTrue(this.oldBackend.isActive());
    }

    @ParameterizedTest
    @ValueSource(ints = {48, 778, 800, 1073742601})
    void unauditedClientIsRejectedBeforeTheLobbyOrInventoryIsTouched(final int version) {
        this.connection.setClientVersion(ProtocolVersion.getProtocol(version));
        this.client.attr(CPAttributeKeys.LOBBY_WINDOW_ID).set(100);
        final var result = this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player");
        assertEquals("REJECTED_UNSUPPORTED_PROTOCOL", result.name());
        this.client.runPendingTasks();
        assertNull(this.suppression.currentJob(), "An unsupported client must not start a login/recovery cycle");
        assertTrue(this.client.isActive());
        assertTrue(this.oldBackend.isActive(), "The working lobby connection must be preserved");
        assertEquals(LOBBY, this.connection.currentAddress);
        assertEquals(100, this.client.attr(CPAttributeKeys.LOBBY_WINDOW_ID).get());
        assertTrue(this.client.outboundMessages().isEmpty(), "Keep the menu, cursor and shortcut items untouched");
        assertTrue(this.pendingBackend.outboundMessages().isEmpty());
    }

    @Test
    void snapshotCannotReuseItsAssociatedReleaseLayout() {
        this.connection.setClientVersion(new ProtocolVersion(
                com.viaversion.viaversion.api.protocol.version.VersionType.RELEASE, 777, 12345, "26.3 snapshot", null));
        final var result = this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player");
        assertEquals(SwitchInitiator.StartResult.REJECTED_UNSUPPORTED_PROTOCOL, result);
        this.client.runPendingTasks();
        assertNull(this.suppression.currentJob());
        assertTrue(this.oldBackend.isActive());
        assertTrue(this.client.outboundMessages().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("legacyProtocolsWithOverlappingReleaseIds")
    void pre1_7ProtocolCannotReuseAModernReleaseWireId(final ProtocolVersion legacy) {
        this.connection.setClientVersion(legacy);
        final var result = this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player");
        assertEquals(SwitchInitiator.StartResult.REJECTED_UNSUPPORTED_PROTOCOL, result, legacy.toString());
        this.client.runPendingTasks();
        assertNull(this.suppression.currentJob());
        assertTrue(this.oldBackend.isActive());
        assertTrue(this.client.outboundMessages().isEmpty());
    }

    static Stream<ProtocolVersion> legacyProtocolsWithOverlappingReleaseIds() {
        return Stream.of(net.raphimc.vialegacy.api.LegacyProtocolVersion.r1_4_2,
                net.raphimc.vialegacy.api.LegacyProtocolVersion.a1_2_2);
    }

    @Test
    void unsupportedClientDoesNotConsumeAConnectAttempt() {
        CPConfig.maxConnectAttemptsPerMinute = 1;
        this.engine = new SwitchEngine(() -> LOBBY);
        final var target = new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player);
        this.connection.setClientVersion(ProtocolVersion.getProtocol(778));
        assertEquals(SwitchInitiator.StartResult.REJECTED_UNSUPPORTED_PROTOCOL,
                this.engine.startSwitch(this.connection, target, null, "Player"));
        this.connection.setClientVersion(ProtocolVersion.v1_21_4);
        assertEquals(SwitchInitiator.StartResult.STARTED,
                this.engine.startSwitch(this.connection, target, null, "Player"));
    }

    @Test
    void serverToServerSwitchDoesNotClearRealInventory() {
        this.connection.currentAddress = new InetSocketAddress("127.0.0.1", 25571);
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player"));
        this.client.runPendingTasks();
        assertTrue(this.client.outboundMessages().isEmpty());
    }

    @ParameterizedTest @MethodSource("connectionFailures")
    void connectFailureRetainsItsExceptionTypeAndNestedCause(Throwable cause, String expected) {
        var failure = new java.util.concurrent.atomic.AtomicReference<String>();
        this.connection.connectFailure = cause;
        this.engine = new SwitchEngine(() -> LOBBY) {
            @Override public void onSwitchFailed(net.raphimc.viaproxy.proxy.session.ProxyConnection pc, SwitchJob job, String reason) {
                failure.set(reason);
                super.onSwitchComplete(pc, job); // cancel watchdogs without the unrelated fallback in this diagnostic test
            }
        };
        this.connection.getPacketHandlers().clear();
        this.suppression = new SwitchSuppressionHandler(this.connection, this.engine);
        this.connection.getPacketHandlers().add(this.suppression);
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, this.player), null, "Player"));
        this.client.runPendingTasks();
        assertNotNull(failure.get());
        assertTrue(failure.get().contains(expected), failure.get());
        assertFalse(failure.get().endsWith("null"));
        assertTrue(this.client.isActive(), "A backend TCP failure must not disconnect the client");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> connectionFailures() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(new java.nio.channels.UnresolvedAddressException(), "UnresolvedAddressException"),
                org.junit.jupiter.params.provider.Arguments.of(new java.net.ConnectException("Connection refused"), "ConnectException: Connection refused"),
                org.junit.jupiter.params.provider.Arguments.of(new io.netty.channel.ConnectTimeoutException("connection timed out"), "ConnectTimeoutException: connection timed out"),
                org.junit.jupiter.params.provider.Arguments.of(new java.util.concurrent.CompletionException(new java.net.UnknownHostException("bad.invalid")), "caused by UnknownHostException: bad.invalid"));
    }

    @Test
    void unresolvedTargetStillReachesTheOfficialConnectionPathForRemoteDns() {
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.connection,
                new ConnectionInfo("remote-only.invalid:25566", ProtocolVersion.v1_21_4, this.player), null, "Player"));
        this.client.runPendingTasks();
        assertTrue(this.connection.currentAddress instanceof InetSocketAddress);
        assertTrue(((InetSocketAddress) this.connection.currentAddress).isUnresolved());
        assertTrue(this.suppression.currentJob().isSwitching(),
                "Leave resolution to ViaProxy: a configured SOCKS proxy can resolve a hostname remotely");
        assertTrue(this.pendingBackend.isActive());
    }

    private void assertInventoryClosed(final int version) {
        assertInventoryClosed(version, 1);
    }

    private void assertInventoryClosed(final int version, final int windowId) {
        final UnknownPacket cursor = this.client.readOutbound();
        assertNotNull(cursor, "Switch start must correct the locally predicted cursor item");
        assertEquals((version >= 768 ? MCPackets.S2C_SET_CURSOR_ITEM : MCPackets.S2C_CONTAINER_SET_SLOT).getId(version), cursor.packetId);
        final byte[] expected = version >= 768 ? new byte[]{0}
                : version >= 756 ? new byte[]{-1, 0, -1, -1, 0}
                : version >= 404 ? new byte[]{-1, -1, -1, 0} : new byte[]{-1, -1, -1, -1, -1};
        assertArrayEquals(expected, cursor.data);
        final UnknownPacket close = this.client.readOutbound();
        assertNotNull(close);
        assertEquals(MCPackets.S2C_CONTAINER_CLOSE.getId(version), close.packetId);
        assertArrayEquals(new byte[]{(byte) windowId}, close.data, "Close the currently displayed CP window");
        for (int slot : new int[]{40, 44}) {
            final UnknownPacket clear = this.client.readOutbound();
            assertNotNull(clear);
            assertEquals(MCPackets.S2C_CONTAINER_SET_SLOT.getId(version), clear.packetId);
            assertArrayEquals(version >= 756 ? new byte[]{0, 0, 0, (byte) slot, 0}
                    : version >= 404 ? new byte[]{0, 0, (byte) slot, 0}
                    : new byte[]{0, 0, (byte) slot, -1, -1}, clear.data);
        }
        final UnknownPacket selected = this.client.readOutbound();
        assertNotNull(selected, "The backend starts with its first hotbar slot selected");
        assertEquals((version >= 768 ? MCPackets.S2C_SET_HELD_SLOT : MCPackets.S2C_SET_CARRIED_ITEM).getId(version), selected.packetId);
        assertArrayEquals(new byte[]{0}, selected.data);
        assertNull(this.client.readOutbound());
    }
}
