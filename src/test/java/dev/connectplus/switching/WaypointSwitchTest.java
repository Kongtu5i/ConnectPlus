package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.commands.LobbyCommands;
import dev.connectplus.testutil.ModernWorldPackets;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.*;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class WaypointSwitchTest {
    private EmbeddedChannel client, backend;
    private SwitchSuppressionHandlerTest.TestProxyConnection connection;
    private SwitchSuppressionHandler handler;
    private int version;
    private final UUID oldPlayer = UUID.randomUUID(), newPlayer = UUID.randomUUID();

    @BeforeAll static void boot() { ViaProxyTestConfig.init(); }
    @AfterEach void close() {
        if (client != null) client.finishAndReleaseAll();
        if (backend != null) backend.finishAndReleaseAll();
    }

    private void setup(int version) {
        this.version = version;
        client = new EmbeddedChannel();
        backend = new EmbeddedChannel();
        backend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, version));
        connection = new SwitchSuppressionHandlerTest.TestProxyConnection(client);
        connection.attachP2s(backend);
        connection.setClientVersion(ProtocolVersion.getProtocol(version));
        connection.setC2pConnectionState(ConnectionState.PLAY);
        connection.setP2sConnectionState(ConnectionState.PLAY);
        handler = new SwitchSuppressionHandler(connection, owner());
        connection.getPacketHandlers().add(handler);
    }

    private SwitchSuppressionHandler.Owner owner() {
        return new SwitchSuppressionHandler.Owner() {
            public void onSwitchComplete(ProxyConnection pc, SwitchJob job) { }
            public void onSwitchFailed(ProxyConnection pc, SwitchJob job, String reason) { fail(reason); }
            public void onTargetCommand(ProxyConnection pc, LobbyCommands.Command command) { }
            public void onForwardKick(ProxyConnection pc, SwitchJob job, String reason) { }
            public void onForwardDeath(ProxyConnection pc, SwitchJob job) { }
            public void onTargetTransfer(ProxyConnection pc, SwitchJob job, String host, int port) { }
        };
    }

    private void forward(Packet packet) throws Exception {
        var listeners = new ArrayList<ChannelFutureListener>();
        for (var next : connection.getPacketHandlers()) {
            if (!next.handleP2S(packet, listeners)) { client.runPendingTasks(); return; }
        }
        client.writeAndFlush(packet).addListeners(listeners.toArray(new ChannelFutureListener[0]));
        client.runPendingTasks();
    }

    private void begin(boolean lobby) {
        assertNotNull(handler.begin(SwitchJob.starting(new SwitchJob.Target("next", connection.getClientVersion(),
                UUID.randomUUID(), "Player", lobby, 1))));
    }

    private void join(boolean lobby) throws Exception {
        begin(lobby);
        forward(ModernWorldPackets.join(version));
    }

    // Independent wire fixtures: TRACK=0, UNTRACK=1, UPDATE=2. A source can be
    // a player UUID or a string; every operation also carries icon and position.
    private UnknownPacket waypoint(int action, Object source, int positionType) {
        var data = Unpooled.buffer();
        try {
            PacketTypes.writeVarInt(data, action);
            data.writeBoolean(source instanceof UUID);
            if (source instanceof UUID uuid) data.writeLong(uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits());
            else PacketTypes.writeString(data, (String) source);
            PacketTypes.writeString(data, "minecraft:default");
            data.writeBoolean(true).writeByte(0x12).writeByte(0x34).writeByte(0x56);
            PacketTypes.writeVarInt(data, positionType);
            switch (positionType) {
                case 1 -> { PacketTypes.writeVarInt(data, 100); PacketTypes.writeVarInt(data, 64); PacketTypes.writeVarInt(data, -200); }
                case 2 -> { PacketTypes.writeVarInt(data, 6); PacketTypes.writeVarInt(data, -13); }
                case 3 -> data.writeFloat(1.5F);
            }
            return new UnknownPacket(MCPackets.S2C_WAYPOINT.getId(version), ByteBufUtil.getBytes(data));
        } finally { data.release(); }
    }

    private Object removedSource() {
        return removedSource(client.readOutbound());
    }

    private Object removedSource(UnknownPacket packet) {
        assertNotNull(packet, "The old locator marker needs an explicit removal before the new world");
        assertEquals(MCPackets.S2C_WAYPOINT.getId(version), packet.packetId);
        var data = Unpooled.wrappedBuffer(packet.data);
        try {
            assertEquals(1, PacketTypes.readVarInt(data), "UNTRACK operation");
            Object source = data.readBoolean() ? new UUID(data.readLong(), data.readLong()) : PacketTypes.readString(data, 32767);
            assertEquals("minecraft:default", PacketTypes.readString(data, 32767));
            assertFalse(data.readBoolean(), "An empty marker has no color override");
            assertEquals(0, PacketTypes.readVarInt(data), "EMPTY waypoint data");
            assertFalse(data.isReadable());
            return source;
        } finally { data.release(); }
    }

    private void readWorldReset() {
        assertEquals(MCPackets.S2C_LOGIN.getId(version), client.<UnknownPacket>readOutbound().packetId);
        assertEquals(MCPackets.S2C_RESPAWN.getId(version), client.<UnknownPacket>readOutbound().packetId);
        assertNull(client.readOutbound());
    }

    @ParameterizedTest @ValueSource(ints = {771, 772, 773, 774, 775, 776, 777})
    void lobbyReturnClearsPlayerAndNamedMarkersBeforeJoiningAnotherServer(int version) throws Exception {
        setup(version);
        forward(waypoint(0, oldPlayer, 1));
        forward(waypoint(0, "server-marker", 2));
        client.outboundMessages().clear();
        join(true);
        assertEquals(oldPlayer, removedSource());
        assertEquals("server-marker", removedSource());
        readWorldReset();
        join(false);
        readWorldReset(); // No markers from the previous backend remain to remove.
        forward(waypoint(0, newPlayer, 3));
        client.outboundMessages().clear();
        join(true);
        assertEquals(newPlayer, removedSource(), "The next cleanup belongs only to the new server");
        readWorldReset();
    }

    @Test void naturalRemovalAndUpdatesLeaveOnlyExistingMarkersToClear() throws Exception {
        setup(777);
        forward(waypoint(0, oldPlayer, 1));
        forward(waypoint(0, newPlayer, 2));
        forward(waypoint(1, oldPlayer, 0));
        forward(waypoint(2, newPlayer, 3));
        forward(waypoint(0, newPlayer, 1));
        client.outboundMessages().clear();
        join(false);
        assertEquals(newPlayer, removedSource());
        readWorldReset();
    }

    @Test void suppressedConfigurationAndCancelledPacketsDoNotBecomeCleanupEntries() throws Exception {
        setup(777);
        forward(waypoint(0, oldPlayer, 1));
        Packet cancelled = waypoint(0, "cancelled", 1);
        connection.getPacketHandlers().add(new PacketHandler(connection) {
            @Override public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) { return packet != cancelled; }
        });
        forward(cancelled);
        connection.setP2sConnectionState(ConnectionState.CONFIGURATION);
        forward(waypoint(0, "config", 1));
        connection.setP2sConnectionState(ConnectionState.PLAY);
        client.outboundMessages().clear();
        begin(true);
        forward(waypoint(0, "suppressed", 1));
        forward(ModernWorldPackets.join(version));
        assertEquals(oldPlayer, removedSource());
        readWorldReset();
    }

    @Test void truncatedAndUnknownPayloadsCannotCreatePhantomMarkersOrRemoveRealOnes() throws Exception {
        setup(777);
        forward(waypoint(0, oldPlayer, 1));
        var truncated = waypoint(0, newPlayer, 1);
        forward(new UnknownPacket(truncated.packetId, Arrays.copyOf(truncated.data, truncated.data.length - 1)));
        var invalidRemoval = waypoint(1, oldPlayer, 0);
        forward(new UnknownPacket(invalidRemoval.packetId, Arrays.copyOf(invalidRemoval.data, invalidRemoval.data.length - 1)));
        forward(waypoint(0, "unknown-type", 99));
        forward(waypoint(99, "unknown-action", 0));
        client.outboundMessages().clear();
        join(true);
        assertEquals(oldPlayer, removedSource());
        readWorldReset();
    }

    @Test void nativeWaypointWritesAndRemainingHandlersRunExactlyOnce() throws Exception {
        setup(777);
        Packet marker = waypoint(0, oldPlayer, 1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        connection.getPacketHandlers().add(new PacketHandler(connection) {
            @Override public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) {
                if (packet == marker) { calls.incrementAndGet(); listeners.add(f -> writes.incrementAndGet()); }
                return true;
            }
        });
        forward(marker);
        assertEquals(1, calls.get());
        assertEquals(1, writes.get());
        assertSame(marker, client.readOutbound());
        assertNull(client.readOutbound());
    }

    @Test void clientsBeforeLocatorBarsReceiveNoSyntheticWaypointTraffic() throws Exception {
        setup(770);
        join(true);
        readWorldReset();
    }

    @Test @Timeout(20)
    void queuedMarkersAreRemovedInOrderAcrossLobbyAndServerSwitches() throws Exception {
        version = 777;
        var clientLoops = new DefaultEventLoopGroup(1);
        var peerLoops = new DefaultEventLoopGroup(1);
        var received = new LinkedBlockingQueue<UnknownPacket>();
        var release = new CountDownLatch(1);
        var target = new EmbeddedChannel();
        Channel server = null, liveClient = null;
        try {
            server = new ServerBootstrap().group(peerLoops).channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override protected void initChannel(LocalChannel channel) {
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<UnknownPacket>() {
                                @Override protected void channelRead0(ChannelHandlerContext ctx, UnknownPacket packet) { received.add(packet); }
                            });
                        }
                    }).bind(new LocalAddress("waypoints-" + UUID.randomUUID())).sync().channel();
            liveClient = new Bootstrap().group(clientLoops).channel(LocalChannel.class)
                    .handler(new ChannelInboundHandlerAdapter()).connect(server.localAddress()).sync().channel();
            target.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, version));
            var pc = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), liveClient) {
                { channelFuture = target.newSucceededFuture(); }
            };
            pc.setClientVersion(ProtocolVersion.v26_3);
            pc.setC2pConnectionState(ConnectionState.PLAY);
            pc.setP2sConnectionState(ConnectionState.PLAY);
            var suppression = new SwitchSuppressionHandler(pc, owner());
            pc.getPacketHandlers().add(suppression);
            var paused = new CountDownLatch(1);
            liveClient.eventLoop().execute(() -> {
                paused.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Client loop did not resume"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(paused.await(3, TimeUnit.SECONDS));
            assertFalse(liveClient.eventLoop().inEventLoop());
            assertFalse(suppression.handleP2S(waypoint(0, oldPlayer, 1), new ArrayList<>()));
            for (boolean lobby : new boolean[]{true, false}) {
                assertNotNull(suppression.begin(SwitchJob.starting(new SwitchJob.Target("next", ProtocolVersion.v26_3,
                        UUID.randomUUID(), "Player", lobby, 1))));
                assertFalse(suppression.handleP2S(ModernWorldPackets.join(version), new ArrayList<>()));
            }
            assertFalse(suppression.handleP2S(waypoint(0, newPlayer, 3), new ArrayList<>()));
            assertNotNull(suppression.begin(SwitchJob.starting(new SwitchJob.Target("lobby", ProtocolVersion.v26_3,
                    UUID.randomUUID(), "Player", true, 1))));
            assertFalse(suppression.handleP2S(ModernWorldPackets.join(version), new ArrayList<>()));
            release.countDown();
            int marker = MCPackets.S2C_WAYPOINT.getId(version);
            int join = MCPackets.S2C_LOGIN.getId(version), respawn = MCPackets.S2C_RESPAWN.getId(version);
            int[] expected = {marker, marker, join, respawn, join, respawn, marker, marker, join, respawn};
            for (int i = 0; i < expected.length; i++) {
                var packet = received.poll(3, TimeUnit.SECONDS);
                assertNotNull(packet, "Queued packet " + i);
                assertEquals(expected[i], packet.packetId, "Queued packet " + i);
                if (i == 1 || i == 7) assertEquals(i == 1 ? oldPlayer : newPlayer, removedSource(packet));
            }
            assertTrue(received.isEmpty());
        } finally {
            release.countDown();
            if (liveClient != null) liveClient.close().syncUninterruptibly();
            if (server != null) server.close().syncUninterruptibly();
            target.finishAndReleaseAll();
            clientLoops.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            peerLoops.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
