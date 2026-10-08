package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.commands.LobbyCommands;
import dev.connectplus.testutil.ModernWorldPackets;
import dev.connectplus.testutil.OlderWorldPackets;
import dev.connectplus.testutil.PlayerListPackets;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TabListSwitchTest {
    private EmbeddedChannel client, backend;
    private SwitchSuppressionHandlerTest.TestProxyConnection connection;
    private SwitchSuppressionHandler handler;
    private int version;
    private final UUID oldPlayer = UUID.randomUUID(), otherOldPlayer = UUID.randomUUID(), newPlayer = UUID.randomUUID();

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
        handler = new SwitchSuppressionHandler(connection, new SwitchSuppressionHandler.Owner() {
            public void onSwitchComplete(ProxyConnection pc, SwitchJob job) { }
            public void onSwitchFailed(ProxyConnection pc, SwitchJob job, String reason) { fail(reason); }
            public void onTargetCommand(ProxyConnection pc, LobbyCommands.Command command) { }
            public void onForwardKick(ProxyConnection pc, SwitchJob job, String reason) { }
            public void onForwardDeath(ProxyConnection pc, SwitchJob job) { }
            public void onTargetTransfer(ProxyConnection pc, SwitchJob job, String host, int port) { }
        });
        connection.getPacketHandlers().add(handler);
    }

    private void forward(Packet packet) throws Exception {
        var listeners = new ArrayList<ChannelFutureListener>();
        for (var next : connection.getPacketHandlers()) {
            if (!next.handleP2S(packet, listeners)) { client.runPendingTasks(); return; }
        }
        client.writeAndFlush(packet).addListeners(listeners.toArray(new ChannelFutureListener[0]));
        client.runPendingTasks();
    }

    private void join(boolean lobby) throws Exception {
        assertNotNull(handler.begin(SwitchJob.starting(new SwitchJob.Target("127.0.0.1:25565", connection.getClientVersion(), UUID.randomUUID(), "Player", lobby, 1))));
        forward(version >= 764 ? ModernWorldPackets.join(version) : OlderWorldPackets.join(version));
    }

    private List<UUID> removedPlayers(UnknownPacket packet) {
        assertEquals((version >= 761 ? MCPackets.S2C_PLAYER_INFO_REMOVE : MCPackets.S2C_PLAYER_INFO).getId(version), packet.packetId);
        var bytes = io.netty.buffer.Unpooled.wrappedBuffer(packet.data);
        try {
            if (version < 47) {
                assertEquals("SameName", PacketTypes.readString(bytes, 16));
                assertFalse(bytes.readBoolean());
                assertEquals(0, bytes.readShort());
                assertEquals(0, bytes.readableBytes());
                return List.of();
            }
            if (version < 761) assertEquals(4, PacketTypes.readVarInt(bytes));
            int count = PacketTypes.readVarInt(bytes);
            List<UUID> result = new ArrayList<>();
            for (int i = 0; i < count; i++) result.add(new UUID(bytes.readLong(), bytes.readLong()));
            assertEquals(0, bytes.readableBytes());
            return result;
        } finally { bytes.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {4, 5, 47, 340, 758, 759, 760, 761, 764, 765, 768, 769, 775, 776, 777})
    void returningToLobbyRemovesThePreviousServersPlayerEntriesBeforeItsWorldReset(int version) throws Exception {
        setup(version);
        forward(PlayerListPackets.add(version, oldPlayer));
        client.outboundMessages().clear();
        join(true);
        var remove = client.<UnknownPacket>readOutbound();
        assertNotNull(remove);
        var removed = removedPlayers(remove);
        if (version >= 47) assertEquals(List.of(oldPlayer), removed);
        assertFalse(removed.contains(newPlayer));
        assertNull(handler.currentJob());
    }

    @ParameterizedTest @ValueSource(ints = {47, 759, 761, 765, 769, 776, 777})
    void removePacketsAndRepeatedAddsLeaveOnlyStillPresentEntriesToClear(int version) throws Exception {
        setup(version);
        forward(PlayerListPackets.add(version, oldPlayer, otherOldPlayer));
        forward(PlayerListPackets.add(version, oldPlayer));
        forward(PlayerListPackets.remove(version, otherOldPlayer));
        client.outboundMessages().clear();
        join(false);
        assertEquals(List.of(oldPlayer), removedPlayers(client.readOutbound()));
    }

    @Test void failedOrSuppressedAndConfigurationPacketsNeverBecomeClientTabEntries() throws Exception {
        setup(769);
        forward(PlayerListPackets.add(version, oldPlayer));
        connection.setP2sConnectionState(ConnectionState.CONFIGURATION);
        forward(PlayerListPackets.add(version, newPlayer));
        connection.setP2sConnectionState(ConnectionState.PLAY);
        client.outboundMessages().clear();
        assertNotNull(handler.begin(SwitchJob.starting(new SwitchJob.Target("next", connection.getClientVersion(), UUID.randomUUID(), "Player", false, 1))));
        forward(PlayerListPackets.add(version, newPlayer));
        forward(ModernWorldPackets.join(version));
        assertEquals(List.of(oldPlayer), removedPlayers(client.readOutbound()));
    }

    @Test void aLaterHandlerCanCancelAPlayerPacketWithoutCreatingACleanupEntry() throws Exception {
        setup(769);
        Packet cancelled = PlayerListPackets.add(version, newPlayer);
        connection.getPacketHandlers().add(new PacketHandler(connection) {
            @Override public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) { return packet != cancelled; }
        });
        forward(PlayerListPackets.add(version, oldPlayer));
        forward(cancelled);
        client.outboundMessages().clear();
        join(false);
        assertEquals(List.of(oldPlayer), removedPlayers(client.readOutbound()));
    }

    @Test void nextServersSameNamePlayerSurvivesThePreviousServerCleanup() throws Exception {
        setup(769);
        forward(PlayerListPackets.add(version, oldPlayer));
        client.outboundMessages().clear();
        join(false);
        assertEquals(List.of(oldPlayer), removedPlayers(client.readOutbound()));
        client.outboundMessages().clear();
        forward(PlayerListPackets.add(version, newPlayer));
        var forwarded = client.<UnknownPacket>readOutbound();
        assertEquals(MCPackets.S2C_PLAYER_INFO_UPDATE.getId(version), forwarded.packetId);
        join(true);
        assertEquals(List.of(newPlayer), removedPlayers(client.readOutbound()), "Only the new server's profile belongs to the next cleanup");
    }

    @Test void malformedMultiEntryPacketCannotPartiallyAddPhantomProfiles() throws Exception {
        setup(769);
        forward(PlayerListPackets.add(version, oldPlayer));
        var complete = PlayerListPackets.add(version, newPlayer, otherOldPlayer);
        forward(new UnknownPacket(complete.packetId, Arrays.copyOf(complete.data, complete.data.length - 1)));
        client.outboundMessages().clear();
        join(false);
        assertEquals(List.of(oldPlayer), removedPlayers(client.readOutbound()));
    }

    @Test void laterHandlersObservePlayerPacketsAndTheirWriteListenersExactlyOnce() throws Exception {
        setup(769);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        Packet playerPacket = PlayerListPackets.add(version, oldPlayer);
        connection.getPacketHandlers().add(new PacketHandler(connection) {
            @Override public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) {
                if (packet == playerPacket) { calls.incrementAndGet(); listeners.add(f -> writes.incrementAndGet()); }
                return true;
            }
        });
        forward(playerPacket);
        assertEquals(1, calls.get());
        assertEquals(1, writes.get());
        assertEquals(1, client.outboundMessages().size());
    }

    @Test void removedAndEmptyPlayerListsDoNotAddSyntheticTraffic() throws Exception {
        setup(769);
        forward(PlayerListPackets.add(version, oldPlayer));
        forward(PlayerListPackets.remove(version, oldPlayer));
        client.outboundMessages().clear();
        join(false);
        assertEquals(MCPackets.S2C_LOGIN.getId(version), client.<UnknownPacket>readOutbound().packetId);
        assertEquals(MCPackets.S2C_RESPAWN.getId(version), client.<UnknownPacket>readOutbound().packetId);
        assertTrue(client.outboundMessages().isEmpty());
    }
}
