package dev.connectplus.lobby;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.lobby.model.HandshakeData;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyTLV;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: joins the lobby with a scripted NetMinecraft client and
 * asserts the minimal join flow (handshake -> login -> configuration -> play).
 */
class LobbyJoinIT {

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(2);

    @AfterAll
    static void shutdownGroup() {
        GROUP.shutdownGracefully();
    }

    private LobbyServer server;

    @TempDir
    File tempDir;

    private LobbyServer startServer() {
        final LobbyServer server = new LobbyServer(new SessionRegistry(), new TokenStore(this.tempDir), new PlayerStore(new File(this.tempDir, "players")), null);
        server.start();
        this.server = server;
        return server;
    }

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop();
            this.server = null;
        }
    }

    private InetSocketAddress serverAddress() {
        return (InetSocketAddress) this.server.localAddress();
    }

    @Test
    @Timeout(15)
    void joinsAndReceivesJoinGame() throws Exception {
        startServer();
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        client.login("TestPlayer");

        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());

        final Packet joinGame = client.firstPlayPacket();
        assertTrue(joinGame instanceof dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket,
                "First play packet must be the JoinGame packet, got: " + joinGame.getClass().getName());
        final dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket welcome =
                client.await(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class);
        assertTrue(welcome.message != null);
    }

    @Test
    @Timeout(15)
    void joinAdvertisesExitCommandsWithoutACompletionRequest() throws Exception {
        startServer();
        final LobbyTestClient client = new LobbyTestClient();
        try {
            client.connect(this.serverAddress());
            client.login("CommandPlayer");
            client.await(S2CLoginGameProfilePacket.class);
            client.write(new C2SLoginAcknowledgedPacket());
            client.await(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class);
            final var commands = client.receivedSnapshot().stream()
                    .filter(packet -> packet instanceof net.raphimc.netminecraft.packet.UnknownPacket raw
                            && raw.packetId == net.raphimc.netminecraft.constants.MCPackets.S2C_COMMANDS.getId(769))
                    .map(packet -> (net.raphimc.netminecraft.packet.UnknownPacket) packet).toList();
            assertEquals(1, commands.size(), "Entering the lobby must publish a command tree before any Tab request");
            assertEquals(List.of("disconnect", "dc"),
                    dev.connectplus.testutil.CommandTreeAssertions.executableRoots(commands.get(0).data));
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(15)
    void withHaProxyPreambleJoinsFine() throws Exception {
        startServer();
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());

        final ByteBuf handshakeBuf = Unpooled.buffer();
        new HandshakeData("lobbytest.example.org", 25565, LobbyProtocol.VERSION).write(handshakeBuf);
        final HAProxyMessage preamble = new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "203.0.113.7", "203.0.113.7", 40000, this.serverAddress().getPort(),
                List.of(new HAProxyTLV((byte) 0xE0, handshakeBuf)));
        client.writeRaw(encodeHaProxyPreamble(preamble));

        client.login("TestPlayer");
        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());

        final Packet joinGame = client.firstPlayPacket();
        assertTrue(joinGame instanceof dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket,
                "Join behind an HAProxy preamble must still receive the JoinGame packet, got: " + joinGame.getClass().getName());
        client.await(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class);
    }

    private static ByteBuf encodeHaProxyPreamble(final HAProxyMessage message) {
        final EmbeddedChannel encoder = new EmbeddedChannel(HAProxyMessageEncoder.INSTANCE);
        try {
            encoder.writeOutbound(message);
            return encoder.readOutbound();
        } finally {
            encoder.finishAndReleaseAll();
        }
    }

    @Test
    @Timeout(15)
    void twoClientsJoinIndependently() throws Exception {
        startServer();
        final LobbyTestClient first = new LobbyTestClient();
        final LobbyTestClient second = new LobbyTestClient();
        first.connect(this.serverAddress());
        second.connect(this.serverAddress());
        first.login("PlayerOne");
        second.login("PlayerTwo");

        first.await(S2CLoginGameProfilePacket.class);
        second.await(S2CLoginGameProfilePacket.class);
        first.write(new C2SLoginAcknowledgedPacket());
        second.write(new C2SLoginAcknowledgedPacket());

        first.await(dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket.class);
        second.await(dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket.class);
        first.await(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class);
        second.await(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class);

        //M2: each client independently gets the main GUI screen
        final dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket firstScreen =
                first.await(dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket.class);
        final dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket secondScreen =
                second.await(dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket.class);
        assertEquals(3, firstScreen.type);
        assertEquals(3, secondScreen.type);
    }

    @Test
    @Timeout(10)
    void handlesMalformedHandshake() throws Exception {
        startServer();
        final Bootstrap bootstrap = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            boolean closed = false;

                            @Override
                            public void channelActive(final ChannelHandlerContext ctx) {
                                final byte[] garbage = new byte[64];
                                new java.util.Random(1234).nextBytes(garbage);
                                garbage[0] = 0x12;
                                ctx.writeAndFlush(Unpooled.wrappedBuffer(garbage));
                            }

                            @Override
                            public void channelInactive(final ChannelHandlerContext ctx) {
                                this.closed = true;
                            }
                        });
                    }
                });
        final Channel channel = bootstrap.connect(this.serverAddress()).syncUninterruptibly().channel();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && channel.isActive()) {
            Thread.sleep(50);
        }
        assertTrue(!channel.isActive(), "Server must close connections sending malformed data");
        //decoder failures are expected protocol kicks: debug-only path, must not count as unexpected
        assertEquals(0, this.server.uncaughtExceptionCount());
    }

    @Test
    @Timeout(10)
    void clientDisconnectMidLogin() throws Exception {
        startServer();
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        client.login("DroppingPlayer");

        client.await(S2CLoginGameProfilePacket.class);
        assertEquals(1, this.server.sessionCount());
        client.disconnect();

        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && this.server.sessionCount() > 0) {
            Thread.sleep(50);
        }
        assertEquals(0, this.server.sessionCount());
    }
}
