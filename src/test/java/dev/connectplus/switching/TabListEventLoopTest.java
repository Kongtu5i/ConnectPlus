package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.commands.LobbyCommands;
import dev.connectplus.testutil.ModernWorldPackets;
import dev.connectplus.testutil.PlayerListPackets;
import dev.connectplus.testutil.ScoreboardPackets;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.*;
import net.raphimc.netminecraft.constants.*;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class TabListEventLoopTest {
    private static final class Connection extends ProxyConnection {
        Connection(Channel client, EmbeddedChannel backend) {
            super(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            channelFuture = backend.newSucceededFuture();
        }
    }

    @Test @Timeout(20)
    void deferredClientWritesRemoveOldProfilesBeforeWorldAndNextProfileWrites() throws Exception {
        ViaProxyTestConfig.init();
        var clientLoops = new DefaultEventLoopGroup(1);
        var peerLoops = new DefaultEventLoopGroup(1);
        var received = new LinkedBlockingQueue<UnknownPacket>();
        var release = new CountDownLatch(1);
        var backend = new EmbeddedChannel();
        Channel server = null, client = null;
        try {
            server = new ServerBootstrap().group(peerLoops).channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override protected void initChannel(LocalChannel channel) {
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<UnknownPacket>() {
                                @Override protected void channelRead0(ChannelHandlerContext ctx, UnknownPacket packet) { received.add(packet); }
                            });
                        }
                    }).bind(new LocalAddress("tab-" + UUID.randomUUID())).sync().channel();
            client = new Bootstrap().group(clientLoops).channel(LocalChannel.class)
                    .handler(new ChannelInboundHandlerAdapter()).connect(server.localAddress()).sync().channel();
            backend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
            var connection = new Connection(client, backend);
            connection.setClientVersion(ProtocolVersion.v1_21_4);
            connection.setC2pConnectionState(ConnectionState.PLAY);
            connection.setP2sConnectionState(ConnectionState.PLAY);
            var handler = new SwitchSuppressionHandler(connection, new SwitchSuppressionHandler.Owner() {
                public void onSwitchComplete(ProxyConnection pc, SwitchJob job) { }
                public void onSwitchFailed(ProxyConnection pc, SwitchJob job, String reason) { fail(reason); }
                public void onTargetCommand(ProxyConnection pc, LobbyCommands.Command command) { }
                public void onForwardKick(ProxyConnection pc, SwitchJob job, String reason) { }
                public void onForwardDeath(ProxyConnection pc, SwitchJob job) { }
                public void onTargetTransfer(ProxyConnection pc, SwitchJob job, String host, int port) { }
            });
            connection.getPacketHandlers().add(handler);
            var paused = new CountDownLatch(1);
            client.eventLoop().execute(() -> {
                paused.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Client loop did not resume"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(paused.await(3, TimeUnit.SECONDS));
            assertFalse(client.eventLoop().inEventLoop(), "Exercise actual deferred scheduling rather than EmbeddedEventLoop's inline path");
            UUID oldProfile = UUID.randomUUID(), nextProfile = UUID.randomUUID();
            assertFalse(handler.handleP2S(PlayerListPackets.add(769, oldProfile), new ArrayList<>()));
            assertFalse(handler.handleP2S(ScoreboardPackets.objective(769, "HP", 0), new ArrayList<>()));
            assertFalse(handler.handleP2S(ScoreboardPackets.team(769, "hp-team", 0), new ArrayList<>()));
            assertNotNull(handler.begin(SwitchJob.starting(new SwitchJob.Target("next", ProtocolVersion.v1_21_4, UUID.randomUUID(), "Player", false, 1))));
            assertFalse(handler.handleP2S(ModernWorldPackets.join(769), new ArrayList<>()));
            assertFalse(handler.handleP2S(PlayerListPackets.add(769, nextProfile), new ArrayList<>()));
            assertFalse(handler.handleP2S(ScoreboardPackets.objective(769, "HP", 0), new ArrayList<>()));
            assertFalse(handler.handleP2S(ScoreboardPackets.team(769, "hp-team", 0), new ArrayList<>()));
            assertNotNull(handler.begin(SwitchJob.starting(new SwitchJob.Target("lobby", ProtocolVersion.v1_21_4, UUID.randomUUID(), "Player", true, 1))));
            assertFalse(handler.handleP2S(ModernWorldPackets.join(769), new ArrayList<>()));
            release.countDown();
            int[] expected = {MCPackets.S2C_PLAYER_INFO_UPDATE.getId(769), MCPackets.S2C_SET_OBJECTIVE.getId(769), MCPackets.S2C_SET_PLAYER_TEAM.getId(769),
                    MCPackets.S2C_SET_OBJECTIVE.getId(769), MCPackets.S2C_SET_PLAYER_TEAM.getId(769), MCPackets.S2C_PLAYER_INFO_REMOVE.getId(769),
                    MCPackets.S2C_LOGIN.getId(769), MCPackets.S2C_RESPAWN.getId(769), MCPackets.S2C_PLAYER_INFO_UPDATE.getId(769),
                    MCPackets.S2C_SET_OBJECTIVE.getId(769), MCPackets.S2C_SET_PLAYER_TEAM.getId(769), MCPackets.S2C_SET_OBJECTIVE.getId(769),
                    MCPackets.S2C_SET_PLAYER_TEAM.getId(769), MCPackets.S2C_PLAYER_INFO_REMOVE.getId(769), MCPackets.S2C_LOGIN.getId(769), MCPackets.S2C_RESPAWN.getId(769)};
            for (int i = 0; i < expected.length; i++) {
                var packet = received.poll(3, TimeUnit.SECONDS);
                assertNotNull(packet, "Queued packet " + i);
                assertEquals(expected[i], packet.packetId, "Queued packet " + i);
                if (i == 5 || i == 13) {
                    var data = java.nio.ByteBuffer.wrap(packet.data);
                    assertEquals(1, data.get());
                    assertEquals(i == 5 ? oldProfile : nextProfile, new UUID(data.getLong(), data.getLong()));
                    assertFalse(data.hasRemaining());
                }
                if (i == 3 || i == 4 || i == 11 || i == 12) {
                    var data = io.netty.buffer.Unpooled.wrappedBuffer(packet.data);
                    try {
                        assertEquals(i == 3 || i == 11 ? "HP" : "hp-team", net.raphimc.netminecraft.packet.PacketTypes.readString(data, 16));
                        assertEquals(1, data.readUnsignedByte());
                        assertEquals(0, data.readableBytes());
                    } finally { data.release(); }
                }
            }
            assertTrue(received.isEmpty());
        } finally {
            release.countDown();
            if (client != null) client.close().syncUninterruptibly();
            if (server != null) server.close().syncUninterruptibly();
            backend.finishAndReleaseAll();
            clientLoops.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            peerLoops.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
