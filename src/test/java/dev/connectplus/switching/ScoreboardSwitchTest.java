package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.commands.LobbyCommands;
import dev.connectplus.testutil.*;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.*;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScoreboardSwitchTest {
    private EmbeddedChannel client, backend;
    private SwitchSuppressionHandlerTest.TestProxyConnection connection;
    private SwitchSuppressionHandler handler;
    private int version;

    @BeforeAll static void boot() { ViaProxyTestConfig.init(); }
    @AfterEach void close() { if (client != null) client.finishAndReleaseAll(); if (backend != null) backend.finishAndReleaseAll(); }

    private void setup(int version) {
        this.version = version;
        client = new EmbeddedChannel(); backend = new EmbeddedChannel();
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
        for (var next : connection.getPacketHandlers()) if (!next.handleP2S(packet, listeners)) { client.runPendingTasks(); return; }
        client.writeAndFlush(packet).addListeners(listeners.toArray(new ChannelFutureListener[0]));
        client.runPendingTasks();
    }

    private void join(boolean lobby) throws Exception {
        assertNotNull(handler.begin(SwitchJob.starting(new SwitchJob.Target("A", connection.getClientVersion(), UUID.randomUUID(), "Player", lobby, 1))));
        forward(version >= 764 ? ModernWorldPackets.join(version) : OlderWorldPackets.join(version));
    }

    private String readRemoval(UnknownPacket packet, boolean team) {
        assertEquals((team ? MCPackets.S2C_SET_PLAYER_TEAM : MCPackets.S2C_SET_OBJECTIVE).getId(version), packet.packetId);
        var bytes = io.netty.buffer.Unpooled.wrappedBuffer(packet.data);
        try {
            String name = PacketTypes.readString(bytes, 32767);
            if (!team && version < 47) assertEquals("", PacketTypes.readString(bytes, 32));
            assertEquals(1, bytes.readUnsignedByte());
            assertEquals(0, bytes.readableBytes());
            return name;
        } finally { bytes.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {4, 5, 47, 340, 393, 759, 764, 765, 769, 774, 776, 777})
    void returningAndRejoiningARemovesTheOldHpObjectiveBeforeItIsCreatedAgain(int version) throws Exception {
        setup(version);
        forward(ScoreboardPackets.objective(version, "HP", 0));
        forward(ScoreboardPackets.team(version, "hp-team", 0));
        client.outboundMessages().clear();
        join(true);
        assertEquals("HP", readRemoval(client.readOutbound(), false), "The recorded duplicate HP failure needs an objective removal before the lobby world");
        assertEquals("hp-team", readRemoval(client.readOutbound(), true));
        client.outboundMessages().clear();
        join(false);
        forward(ScoreboardPackets.objective(version, "HP", 0));
        client.outboundMessages().clear();
        join(true);
        assertEquals("HP", readRemoval(client.readOutbound(), false), "The new A objective must be tracked for the next return too");
    }

    @Test void alreadyRemovedNamesAndMetadataUpdatesDoNotProducePhantomRemovals() throws Exception {
        setup(774);
        forward(ScoreboardPackets.objective(version, "HP", 0));
        forward(ScoreboardPackets.objective(version, "HP", 2));
        forward(ScoreboardPackets.objective(version, "HP", 1));
        forward(ScoreboardPackets.team(version, "hp-team", 0));
        forward(ScoreboardPackets.team(version, "hp-team", 1));
        client.outboundMessages().clear();
        join(false);
        assertEquals(MCPackets.S2C_LOGIN.getId(version), client.<UnknownPacket>readOutbound().packetId);
    }
}
