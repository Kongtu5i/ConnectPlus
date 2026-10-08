package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.compat.SwitchableProxyConnection;
import dev.connectplus.lobby.LobbyNotices;
import dev.connectplus.session.ConnectionInfo;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.*;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import org.junit.jupiter.api.*;

import java.net.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SwitchCallbackOwnershipTest {
    private static final InetSocketAddress LOBBY = new InetSocketAddress("127.0.0.1", 25570);
    private final UUID player = UUID.randomUUID();
    private EmbeddedChannel client, origin;
    private Connection connection;
    private SwitchEngine engine;
    private SwitchSuppressionHandler suppression;
    private boolean holdHandshake;
    private ChannelPromise pendingHandshake;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<ChannelPromise> connects = new ArrayList<>();

    private final class Connection extends SwitchableProxyConnection {
        SocketAddress address = LOBBY;
        Connection() {
            super(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            channelFuture = origin.newSucceededFuture();
        }
        @Override public SocketAddress getServerAddress() { return address; }
        @Override public ChannelFuture connectToServer(SocketAddress address, ProtocolVersion version) {
            this.address = address;
            var channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    if (holdHandshake && message instanceof C2SHandshakingClientIntentionPacket) pendingHandshake = promise;
                    else ctx.write(message, promise);
                }
            });
            channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
            var promise = channel.newPromise();
            channels.add(channel);
            connects.add(promise);
            return channelFuture = promise;
        }
    }

    @BeforeEach void setup() {
        ViaProxyTestConfig.init();
        client = new EmbeddedChannel();
        origin = new EmbeddedChannel();
        origin.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
        connection = new Connection();
        connection.setClientVersion(ProtocolVersion.v1_21_4);
        connection.setC2pConnectionState(ConnectionState.PLAY);
        connection.setLoginHelloPacket(new C2SLoginHelloPacket("Rejoin", null, null, null, player));
        engine = new SwitchEngine(() -> LOBBY);
        suppression = new SwitchSuppressionHandler(connection, engine);
        connection.getPacketHandlers().add(suppression);
    }

    @AfterEach void close() {
        if (suppression.currentJob() != null) {
            suppression.currentJob().fail("fixture ended");
            engine.onSwitchComplete(connection, suppression.currentJob());
        }
        holdHandshake = false;
        for (var channel : channels) channel.finishAndReleaseAll();
        origin.finishAndReleaseAll();
        client.finishAndReleaseAll();
        LobbyNotices.consume(player);
    }

    private SwitchJob startA() {
        assertEquals(SwitchInitiator.StartResult.STARTED, engine.startSwitch(connection,
                new ConnectionInfo("127.0.0.1:25566", ProtocolVersion.v1_21_4, player), null, "Rejoin"));
        client.runPendingTasks();
        assertEquals(1, connects.size());
        return suppression.currentJob();
    }

    private void fallBack(SwitchJob previous) {
        assertTrue(previous.fail("controlled login timeout"));
        engine.onSwitchFailed(connection, previous, previous.failureReason());
        client.runPendingTasks();
        assertEquals(2, connects.size());
        assertNotSame(previous, suppression.currentJob());
        assertTrue(suppression.currentJob().target().lobby());
        assertSame(channels.get(1), connection.getChannel());
    }

    @Test void anOldConnectCompletionAfterFallbackCannotSendItsHandshakeIntoTheNewChannel() {
        SwitchJob previous = startA();
        fallBack(previous);
        connects.get(0).setSuccess(); // deliver the old completion after the fallback took ownership
        channels.get(0).runPendingTasks();
        assertTrue(channels.get(1).outboundMessages().isEmpty(), "Old target handshake must not enter the current lobby channel");
        assertEquals(ConnectionState.HANDSHAKING, connection.getP2sConnectionState());
        assertTrue(suppression.currentJob().isSwitching());
    }

    @Test void anOldHandshakeWriteCompletionCannotChangeTheNewChannelsLoginState() {
        holdHandshake = true;
        SwitchJob previous = startA();
        connects.get(0).setSuccess();
        channels.get(0).runPendingTasks();
        assertNotNull(pendingHandshake);
        fallBack(previous);
        pendingHandshake.setSuccess();
        client.runPendingTasks();
        assertEquals(ConnectionState.HANDSHAKING, connection.getP2sConnectionState(), "A stale write completion must not advance the fallback registry to LOGIN");
        assertTrue(channels.get(1).outboundMessages().isEmpty());
        assertTrue(suppression.currentJob().isSwitching());
    }

    private SwitchJob forwardingA() {
        SwitchJob job = startA();
        assertTrue(job.markForward());
        engine.onSwitchComplete(connection, job);
        return job;
    }

    @Test void clientExitBeforeBackendCloseCannotLeaveANoticeForTheNextLogin() {
        forwardingA();
        client.close();
        suppression.guardChannelDeath(channels.get(0));
        client.runPendingTasks();
        assertNull(LobbyNotices.consume(player), "A normal client exit must not leave a backend death notice for the next login");
        assertEquals(1, connects.size(), "The disconnected client must not reconnect to the lobby");
    }

    @Test void clientExitImmediatelyAfterBackendDeathCannotLeaveANotice() {
        forwardingA();
        assertTrue(suppression.guardChannelDeath(channels.get(0)));
        client.close();
        final int connectionsAtExit = connects.size();
        client.runPendingTasks();
        assertNull(LobbyNotices.consume(player), "A queued backend recovery cannot survive its client connection");
        assertEquals(connectionsAtExit, connects.size(), "No further lobby connection may start after client exit");
    }

    @Test void clientExitDuringAnUnfinishedLobbyLoginRemovesItsPendingNotice() {
        forwardingA();
        assertTrue(engine.switchToLobby(connection, LobbyNotices.Notice.raw("old backend died"), player, "Rejoin"));
        client.runPendingTasks();
        assertEquals(2, connects.size());
        client.close();
        client.runPendingTasks();
        assertNull(LobbyNotices.consume(player), "An unfinished lobby login must not leak its notice into a fresh login");
    }

    @Test void anOldClientClosingCannotRemoveANewerNoticeForTheSamePlayer() {
        forwardingA();
        assertTrue(engine.switchToLobby(connection, LobbyNotices.Notice.raw("old backend died"), player, "Rejoin"));
        assertEquals("old backend died", LobbyNotices.consume(player).get(0).text("en"));
        LobbyNotices.postRaw(player, List.of("new connection notice"));
        client.close();
        client.runPendingTasks();
        assertEquals("new connection notice", LobbyNotices.consume(player).get(0).text("en"));
    }

    @Test void anExhaustedOldRecoveryCannotDiscardANewerLoginNotice() {
        final String originalPolicy = dev.connectplus.config.CPConfig.backendDownPolicy;
        final int originalAttempts = dev.connectplus.config.CPConfig.reconnectAttempts;
        try {
            dev.connectplus.config.CPConfig.backendDownPolicy = "reconnect";
            dev.connectplus.config.CPConfig.reconnectAttempts = 1;
            engine = new SwitchEngine(() -> {
                // Simulate the old client closing and a fresh login posting its
                // notice while the original recovery resolves its lobby address.
                client.close();
                LobbyNotices.postRaw(player, List.of("new connection notice"));
                return null;
            });
            SwitchJob old = SwitchJob.starting(new SwitchJob.Target("127.0.0.1:25566",
                    ProtocolVersion.v1_21_4, player, "Rejoin", false, 2));
            assertTrue(old.fail("backend unavailable"));
            engine.onSwitchFailed(connection, old, old.failureReason());
            final List<LobbyNotices.Notice> newer = LobbyNotices.consume(player);
            assertNotNull(newer, "An old recovery that posts nothing must not consume a newer login's notice");
            assertEquals("new connection notice", newer.get(0).text("en"));
        } finally {
            dev.connectplus.config.CPConfig.backendDownPolicy = originalPolicy;
            dev.connectplus.config.CPConfig.reconnectAttempts = originalAttempts;
        }
    }
}
