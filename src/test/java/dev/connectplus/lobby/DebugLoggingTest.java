package dev.connectplus.lobby;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.session.*;
import dev.connectplus.switching.SwitchJob;
import dev.connectplus.switching.SwitchSuppressionHandler;
import dev.connectplus.testutil.ModernWorldPackets;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.*;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebugLoggingTest {
    @TempDir File directory;
    private boolean originalDebug;
    private Logger logger;
    private Level originalLevel;
    private final List<LogEvent> events = new ArrayList<>();
    private AbstractAppender capture;
    private Connection backendConnection;

    @BeforeEach void capturePluginLogs() {
        ViaProxyTestConfig.init();
        originalDebug = CPConfig.debug;
        CPConfig.debug = false;
        logger = (Logger) LogManager.getLogger("ConnectPlus");
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG); // Debug-capable backend must still respect the plugin switch.
        capture = new AbstractAppender("ConnectPlusDebugCapture", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach void restoreLogs() {
        logger.removeAppender(capture);
        capture.stop();
        logger.setLevel(originalLevel);
        CPConfig.debug = originalDebug;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void clientFrameDumpIsOptInDebugAndLeavesThePacketUntouched(boolean debug) throws Exception {
        CPConfig.debug = debug;
        PlayerStore store = new PlayerStore(new File(directory, "players"));
        LobbyChannelInitializer initializer = new LobbyChannelInitializer(new HashSet<>(), new SessionRegistry(),
                new TokenStore(directory), store, new IdentityLinkStore(new File(directory, "players")),
                new AccountSessionCoordinator(store, () -> null), null, null, new AtomicInteger());
        EmbeddedChannel channel = new EmbeddedChannel(initializer);
        ByteBuf payload = Unpooled.buffer(298).writeByte(7).writeZero(297);
        final List<Object> forwarded = new ArrayList<>();
        try {
            channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().setConnectionState(ConnectionState.PLAY);
            channel.pipeline().addAfter("connectplus-c2s-dump", "test-frame-sink", new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) { forwarded.add(message); }
            });
            events.clear();
            final ChannelHandlerContext ctx = channel.pipeline().context("connectplus-c2s-dump");
            ((ChannelInboundHandlerAdapter) ctx.handler()).channelRead(ctx, payload);
            assertEquals(1, forwarded.size());
            assertSame(payload, forwarded.get(0));
            assertEquals(0, payload.readerIndex());
            assertEquals(298, payload.readableBytes());
            assertEquals(1, payload.refCnt());
            if (debug) {
                assertEquals(1, events.size());
                assertEquals(Level.DEBUG, events.get(0).getLevel());
                assertTrue(events.get(0).getMessage().getFormattedMessage().contains("[C2S dump] state=PLAY id=0x7 298B"));
            } else {
                assertTrue(events.isEmpty(), "Large packets must not create diagnostics when debug is false");
            }
        } finally {
            payload.release();
            channel.finishAndReleaseAll();
        }
    }

    private final class Connection extends ProxyConnection {
        Connection(EmbeddedChannel client, EmbeddedChannel backend) {
            super(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            channelFuture = backend.newSucceededFuture();
        }
    }

    private SwitchSuppressionHandler handler(EmbeddedChannel client, EmbeddedChannel backend) {
        backend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 774));
        Connection connection = this.backendConnection = new Connection(client, backend);
        connection.setClientVersion(ProtocolVersion.getProtocol(774));
        connection.setC2pConnectionState(ConnectionState.PLAY);
        connection.setP2sConnectionState(ConnectionState.CONFIGURATION);
        SwitchSuppressionHandler handler = new SwitchSuppressionHandler(connection, new SwitchSuppressionHandler.Owner() {
            public void onSwitchComplete(ProxyConnection pc, SwitchJob job) {}
            public void onSwitchFailed(ProxyConnection pc, SwitchJob job, String reason) {}
            public void onTargetCommand(ProxyConnection pc, dev.connectplus.commands.LobbyCommands.Command command) {}
            public void onForwardKick(ProxyConnection pc, SwitchJob job, String reason) {}
            public void onForwardDeath(ProxyConnection pc, SwitchJob job) {}
            public void onTargetTransfer(ProxyConnection pc, SwitchJob job, String host, int port) {}
        });
        connection.getPacketHandlers().add(handler);
        handler.begin(SwitchJob.starting(new SwitchJob.Target("example.org:25565", ProtocolVersion.getProtocol(774),
                UUID.randomUUID(), "DebugPlayer", false, 1)));
        return handler;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configurationPacketTraceUsesOnlyDebugLevelAndRespectsTheSwitch(boolean debug) {
        CPConfig.debug = debug;
        EmbeddedChannel client = new EmbeddedChannel(), backend = new EmbeddedChannel();
        try {
            SwitchSuppressionHandler handler = handler(client, backend);
            events.clear();
            handler.handleP2S(new UnknownPacket(MCPackets.S2C_CONFIG_REGISTRY_DATA.getId(774), new byte[3710]), new ArrayList<>());
            if (debug) {
                assertEquals(2, events.size());
                assertTrue(events.stream().allMatch(event -> event.getLevel() == Level.DEBUG));
                assertTrue(events.stream().anyMatch(event -> event.getMessage().getFormattedMessage().contains("raw config packet id=0x7 (3710B)")));
            } else {
                assertTrue(events.isEmpty(), "Configuration packets must not pollute INFO or DEBUG logs when the switch is off");
            }
            assertTrue(handler.currentJob().isSwitching(), "Diagnostic suppression must not alter the switch state");
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test void successfulSwitchStillProducesItsOperationalInfoWhenDebugIsOff() {
        EmbeddedChannel client = new EmbeddedChannel(), backend = new EmbeddedChannel();
        try {
            SwitchSuppressionHandler handler = handler(client, backend);
            backendConnection.setP2sConnectionState(ConnectionState.PLAY);
            events.clear();
            handler.handleP2S(ModernWorldPackets.join(774), new ArrayList<>());
            client.runPendingTasks();
            assertTrue(events.stream().anyMatch(event -> event.getLevel() == Level.INFO
                    && event.getMessage().getFormattedMessage().contains("completed in")));
            assertFalse(events.stream().anyMatch(event -> event.getMessage().getFormattedMessage().startsWith("[Switch:")));
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test void protocolFailureStillProducesAWarningWhenDebugIsOff() {
        EmbeddedChannel client = new EmbeddedChannel(), backend = new EmbeddedChannel();
        try {
            SwitchSuppressionHandler handler = handler(client, backend);
            events.clear();
            handler.handleP2S(new UnknownPacket(MCPackets.S2C_CONFIG_PING.getId(774), new byte[8]), new ArrayList<>());
            assertTrue(events.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getMessage().getFormattedMessage().contains("Invalid configuration ping payload size")));
            assertFalse(handler.currentJob().isSwitching());
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void guiStateDiagnosticsAlsoRespectTheDebugSwitch(boolean debug) {
        CPConfig.debug = debug;
        assertNotNull(com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            PlayerStore store = new PlayerStore(new File(directory, "players"));
            LobbyServerHandler handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(directory), store,
                    new IdentityLinkStore(new File(directory, "players")), new AccountSessionCoordinator(store, () -> null),
                    null, null, new AtomicInteger());
            handler.loadSession(channel, UUID.randomUUID(), "DebugPlayer");
            handler.getSession().playerData = new PlayerData(handler.getSession().uuid);
            events.clear();
            new dev.connectplus.lobby.states.PlayStateHandler(handler, channel);
            if (debug) {
                assertTrue(events.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                        && event.getMessage().getFormattedMessage().startsWith("[Lobby GUI]")));
            } else {
                assertTrue(events.isEmpty(), "GUI state details must not appear with debug disabled");
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
