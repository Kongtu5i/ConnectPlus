package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.*;
import net.raphimc.netminecraft.constants.*;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.*;
import net.raphimc.netminecraft.packet.impl.play.*;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientConfigurationBufferTest {
    private static final class Connection extends ProxyConnection {
        Connection(Channel client) { super(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"8,4097", "1048576,33"})
    @Timeout(20)
    void pendingCrossThreadTasksShareTheBufferBudgetAndReleaseItWhenSuperseded(int payloadSize, int packets) throws Exception {
        ViaProxyTestConfig.init();
        final var clientLoops = new DefaultEventLoopGroup(1);
        final var peerLoops = new DefaultEventLoopGroup(1);
        final var received = new LinkedBlockingQueue<Packet>();
        final var release = new CountDownLatch(1);
        Channel server = null, client = null;
        try {
            server = new ServerBootstrap().group(peerLoops).channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override protected void initChannel(LocalChannel channel) {
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<Packet>() {
                                @Override protected void channelRead0(ChannelHandlerContext ctx, Packet packet) { received.add(packet); }
                            });
                        }
                    }).bind(new LocalAddress("configuration-budget-" + UUID.randomUUID())).sync().channel();
            client = new Bootstrap().group(clientLoops).channel(LocalChannel.class).handler(new ChannelInboundHandlerAdapter())
                    .connect(server.localAddress()).sync().channel();
            client.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
            final var connection = new Connection(client);
            connection.setClientVersion(ProtocolVersion.v1_21_4);
            connection.setC2pConnectionState(ConnectionState.PLAY);
            final var current = new AtomicReference<>(job());
            final var failures = new AtomicInteger();
            final var configuration = new ClientConfiguration(connection, current::get, (owner, reason) -> { if (owner.fail(reason)) failures.incrementAndGet(); });
            configuration.begin(current.get());
            client.eventLoop().submit(() -> {}).sync();
            final var blocked = new CountDownLatch(1);
            client.eventLoop().execute(() -> {
                blocked.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            final var raw = new UnknownPacket(MCPackets.S2C_CONFIG_REGISTRY_DATA.getId(769), new byte[payloadSize]);
            for (int i = 0; i < packets; i++) configuration.accept(current.get(), raw);
            assertEquals(1, failures.get(), "The limit must apply before a stalled frontend executes the queued tasks");
            release.countDown();
            client.eventLoop().submit(() -> {}).sync();
            assertTrue(received.isEmpty());
            current.set(job());
            configuration.begin(current.get());
            configuration.loggedIn(current.get());
            configuration.accept(current.get(), raw);
            client.eventLoop().submit(() -> configuration.handleClient(new C2SPlayConfigurationAcknowledgedPacket(), current.get())).sync();
            assertInstanceOf(S2CPlayStartConfigurationPacket.class, received.poll(5, TimeUnit.SECONDS));
            assertInstanceOf(UnknownPacket.class, received.poll(5, TimeUnit.SECONDS));
            assertEquals(1, failures.get(), "Superseded tasks must release their reservation for recovery");
        } finally {
            release.countDown();
            if (client != null) client.close().syncUninterruptibly();
            if (server != null) server.close().syncUninterruptibly();
            clientLoops.shutdownGracefully().syncUninterruptibly();
            peerLoops.shutdownGracefully().syncUninterruptibly();
        }
    }
    private static SwitchJob job() { return SwitchJob.starting(new SwitchJob.Target("example.invalid:25565", null, UUID.randomUUID(), "BudgetPlayer", false, 1)); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Timeout(20)
    void forwardedConfigurationObservationSurvivesBackendOwnershipChanges(boolean finish) throws Exception {
        ViaProxyTestConfig.init();
        final var clientLoops = new DefaultEventLoopGroup(1);
        final var peerLoops = new DefaultEventLoopGroup(1);
        final var received = new LinkedBlockingQueue<Packet>();
        final var release = new CountDownLatch(1);
        Channel server = null, client = null;
        try {
            server = new ServerBootstrap().group(peerLoops).channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override protected void initChannel(LocalChannel channel) {
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<Packet>() {
                                @Override protected void channelRead0(ChannelHandlerContext ctx, Packet packet) { received.add(packet); }
                            });
                        }
                    }).bind(new LocalAddress("configuration-observe-" + UUID.randomUUID())).sync().channel();
            client = new Bootstrap().group(clientLoops).channel(LocalChannel.class).handler(new ChannelInboundHandlerAdapter())
                    .connect(server.localAddress()).sync().channel();
            client.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
            final var connection = new Connection(client);
            connection.setClientVersion(ProtocolVersion.v1_21_4);
            connection.setC2pConnectionState(finish ? ConnectionState.CONFIGURATION : ConnectionState.PLAY);
            final var old = job(); old.markForward();
            final var current = new AtomicReference<>(old);
            final var configuration = new ClientConfiguration(connection, current::get, (owner, reason) -> fail(reason));
            if (finish) client.eventLoop().submit(() -> configuration.observeClient(new C2SPlayConfigurationAcknowledgedPacket())).sync();
            final var blocked = new CountDownLatch(1);
            client.eventLoop().execute(() -> {
                blocked.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            configuration.observeServer(finish ? new net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket()
                    : new S2CPlayStartConfigurationPacket(), old);
            //The native host's write remains queued even though its backend job has gone.
            current.set(null);
            release.countDown();
            client.eventLoop().submit(() -> {
                assertFalse(configuration.handleClient(finish
                        ? new net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket()
                        : new C2SPlayConfigurationAcknowledgedPacket(), null));
                assertEquals(finish ? ConnectionState.PLAY : ConnectionState.CONFIGURATION, connection.getC2pConnectionState());
            }).sync();
            current.set(job()); configuration.begin(current.get()); configuration.loggedIn(current.get());
            configuration.accept(current.get(), new UnknownPacket(MCPackets.S2C_CONFIG_REGISTRY_DATA.getId(769), new byte[]{1}));
            client.eventLoop().submit(() -> {
                if (finish) configuration.handleClient(new C2SPlayConfigurationAcknowledgedPacket(), current.get());
            }).sync();
            if (finish) assertInstanceOf(S2CPlayStartConfigurationPacket.class, received.poll(5, TimeUnit.SECONDS));
            assertInstanceOf(UnknownPacket.class, received.poll(5, TimeUnit.SECONDS));
            assertTrue(received.isEmpty(), "Recovery must not repeat an already delivered start");
        } finally {
            release.countDown();
            if (client != null) client.close().syncUninterruptibly();
            if (server != null) server.close().syncUninterruptibly();
            clientLoops.shutdownGracefully().syncUninterruptibly(); peerLoops.shutdownGracefully().syncUninterruptibly();
        }
    }
}
