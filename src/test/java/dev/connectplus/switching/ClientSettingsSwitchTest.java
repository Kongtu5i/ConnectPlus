package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.commands.LobbyCommands;
import dev.connectplus.testutil.ModernWorldPackets;
import dev.connectplus.testutil.OlderWorldPackets;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayStartConfigurationPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.packethandler.ConfigurationPacketHandler;
import net.raphimc.viaproxy.proxy.packethandler.LoginPacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClientSettingsSwitchTest {
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 47, 107, 340, 404, 498, 578, 735, 754, 758, 763,
            764, 765, 769, 774, 776, 777})
    void newBackendReceivesTheOriginalSettingsWithoutAClientResend(final int version) {
        try (final var rig = new Rig(version)) {
            final var original = settings(version, version >= 764, 12);
            final byte[] expected = original.data.clone();
            rig.sendSettings(original, true);
            // Later handlers may mutate a packet; the retained copy must stay intact.
            Arrays.fill(original.data, (byte) 0);
            rig.startSwitch(false);
            rig.login();
            if (rig.modern()) {
                rig.expectSettings(expected, true);
                rig.finishConfiguration();
            } else {
                assertNull(rig.backend.readOutbound(), "Do not send PLAY settings during backend login");
            }
            final var join = rig.join();
            if (!rig.modern()) rig.expectSettings(expected, false);
            assertNull(rig.backend.readOutbound(), "Replay the settings once per new backend");
            if (rig.modern()) {
                final var forwarded = assertInstanceOf(UnknownPacket.class, rig.client.readOutbound());
                assertArrayEquals(join.data, forwarded.data, "Keep the destination's view and simulation distances intact");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 774})
    void laterSwitchesAndLobbyReturnsUseTheLatestPlayerSettings(final int version) {
        try (final var rig = new Rig(version)) {
            final var first = settings(version, rig.modern(), 12);
            rig.sendSettings(first, true);
            rig.switchAndExpect(false, first.data);
            final var changed = settings(version, false, 24);
            rig.sendSettings(changed, true);
            rig.switchAndExpect(true, changed.data);
            assertNull(rig.handler.currentJob(), "Lobby return should release the switch");
            rig.switchAndExpect(false, changed.data);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 774})
    void settingsChangedDuringSuppressionReachTheNewBackend(final int version) {
        try (final var rig = new Rig(version)) {
            final var first = settings(version, rig.modern(), 12);
            rig.sendSettings(first, true);
            rig.startSwitch(false);
            rig.login();
            if (rig.modern()) rig.expectSettings(first.data, true);
            // Still in the old frontend PLAY state; the switch suppresses this packet.
            final var changed = settings(version, false, 24);
            rig.sendSettings(changed, false);
            if (rig.modern()) {
                rig.finishConfiguration(changed.data);
            } else {
                rig.join();
                rig.expectSettings(changed.data, false);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 774})
    void unrelatedLoginPacketsCannotReplaceTheSavedSettings(final int version) {
        try (final var rig = new Rig(version)) {
            final var original = settings(version, rig.modern(), 12);
            rig.sendSettings(original, true);
            rig.pc.setC2pConnectionState(ConnectionState.LOGIN);
            rig.handler.handleC2P(new UnknownPacket(MCPackets.C2S_CLIENT_INFORMATION.getId(version), new byte[]{99}), new ArrayList<>());
            rig.pc.setC2pConnectionState(ConnectionState.PLAY);
            rig.switchAndExpect(false, original.data);
        }
    }

    @Test
    void replayedPacketMutationDoesNotCorruptTheNextSwitch() {
        try (final var rig = new Rig(774)) {
            final var original = settings(774, true, 12);
            rig.sendSettings(original, true);
            rig.startSwitch(false);
            rig.login();
            final var replay = rig.expectSettings(original.data, true);
            Arrays.fill(replay.data, (byte) 0);
            rig.finishConfiguration();
            rig.join();
            rig.switchAndExpect(false, original.data);
        }
    }

    @Test
    void configurationSettingsUpdatedByTheClientRemainCurrentOnTheNextServer() {
        try (final var rig = new Rig(774)) {
            final var original = settings(774, true, 12);
            rig.sendSettings(original, true);
            rig.startSwitch(false);
            rig.login();
            rig.expectSettings(original.data, true);
            rig.acknowledgeConfiguration();
            final var changed = settings(774, true, 20);
            rig.sendSettings(changed, true);
            rig.finishConfigurationAck(changed.data);
            rig.join();
            rig.switchAndExpect(false, changed.data);
        }
    }

    @Test
    void settingsChangedAfterConfigurationFinishAreReplayedInPlayBeforeForwarding() {
        try (final var rig = new Rig(774)) {
            final var original = settings(774, true, 12);
            rig.sendSettings(original, true);
            rig.startSwitch(false);
            rig.login();
            rig.expectSettings(original.data, true);
            rig.finishConfiguration();
            final var changed = settings(774, false, 24);
            rig.sendSettings(changed, false);
            rig.join();
            rig.expectSettings(changed.data, false);
        }
    }

    @Test
    void delayedLoginAcknowledgementCannotReplayIntoAReplacementAttempt() {
        try (final var rig = new Rig(774)) {
            rig.sendSettings(settings(774, true, 12), true);
            rig.startSwitch(false);
            final var acknowledgement = new AtomicReference<ChannelPromise>();
            rig.backend.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
                    if (packet instanceof C2SLoginAcknowledgedPacket) acknowledgement.set(promise);
                    else ctx.write(packet, promise);
                }
            });
            assertFalse(rig.handler.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "DistancePlayer", new ArrayList<>()), new ArrayList<>()));
            assertNotNull(acknowledgement.get());
            assertEquals(ConnectionState.LOGIN, rig.pc.getP2sConnectionState());
            assertTrue(rig.handler.currentJob().fail("Superseded attempt"));
            rig.startSwitch(false);
            acknowledgement.get().setSuccess();
            assertEquals(ConnectionState.LOGIN, rig.pc.getP2sConnectionState(),
                    "An old ACK must not put the replacement backend in configuration early");
            assertNull(rig.backend.readOutbound(), "Never send the old attempt's settings into replacement login");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 774})
    void oversizedSettingsDoNotDisplaceTheSavedPlayerPreferences(final int version) {
        try (final var rig = new Rig(version)) {
            final var original = settings(version, rig.modern(), 12);
            rig.sendSettings(original, true);
            rig.pc.setC2pConnectionState(ConnectionState.PLAY);
            rig.sendSettings(new UnknownPacket(MCPackets.C2S_CLIENT_INFORMATION.getId(version), new byte[1024 * 1024]), true);
            rig.switchAndExpect(false, original.data);
        }
    }

    private static UnknownPacket settings(final int version, final boolean configuration, final int distance) {
        final ByteBuf data = Unpooled.buffer();
        try {
            PacketTypes.writeString(data, "zh_cn");
            data.writeByte(distance);
            PacketTypes.writeVarInt(data, 0); // chat visibility (zero is also a byte on 1.7)
            data.writeBoolean(true);
            if (version < 47) {
                data.writeByte(0); // difficulty
                data.writeBoolean(true); // cape
            } else {
                data.writeByte(127); // skin parts
                if (version >= 107) PacketTypes.writeVarInt(data, 1); // main hand
                if (version >= 755) data.writeBoolean(false); // text filtering
                if (version >= 757) data.writeBoolean(true); // server listings
                if (version >= 768) PacketTypes.writeVarInt(data, 0); // particles
            }
            return new UnknownPacket((configuration ? MCPackets.C2S_CONFIG_CLIENT_INFORMATION
                    : MCPackets.C2S_CLIENT_INFORMATION).getId(version), ByteBufUtil.getBytes(data));
        } finally { data.release(); }
    }

    private static final class Rig implements AutoCloseable, SwitchSuppressionHandler.Owner {
        final int version;
        final EmbeddedChannel client = new EmbeddedChannel();
        final SwitchSuppressionHandlerTest.TestProxyConnection pc = new SwitchSuppressionHandlerTest.TestProxyConnection(client);
        final SwitchSuppressionHandler handler;
        final List<EmbeddedChannel> backends = new ArrayList<>();
        EmbeddedChannel backend;

        Rig(final int version) {
            ViaProxyTestConfig.init();
            this.version = version;
            client.attr(MCPipeline.COMPRESSION_THRESHOLD_ATTRIBUTE_KEY).set(256);
            pc.setClientVersion(ProtocolVersion.getProtocol(version));
            pc.setGameProfile(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "DistancePlayer"));
            attachBackend();
            pc.setC2pConnectionState(modern() ? ConnectionState.CONFIGURATION : ConnectionState.PLAY);
            pc.setP2sConnectionState(modern() ? ConnectionState.CONFIGURATION : ConnectionState.PLAY);
            handler = new SwitchSuppressionHandler(pc, this);
            pc.getPacketHandlers().add(handler);
            pc.getPacketHandlers().add(new LoginPacketHandler(pc));
            pc.getPacketHandlers().add(new ConfigurationPacketHandler(pc));
        }

        boolean modern() { return version >= 764; }

        void attachBackend() {
            backend = new EmbeddedChannel();
            backends.add(backend);
            backend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, version));
            pc.attachP2s(backend);
        }

        void sendSettings(final Packet packet, final boolean forward) {
            assertEquals(forward, handler.handleC2P(packet, new ArrayList<>()));
            if (forward) {
                backend.writeAndFlush(packet);
                assertSame(packet, backend.readOutbound());
            }
        }

        void startSwitch(final boolean lobby) {
            client.outboundMessages().clear();
            pc.setC2pConnectionState(ConnectionState.PLAY);
            attachBackend();
            pc.setP2sConnectionState(ConnectionState.LOGIN);
            assertNotNull(handler.begin(SwitchJob.starting(new SwitchJob.Target("example.invalid:25565",
                    pc.getClientVersion(), UUID.randomUUID(), "DistancePlayer", lobby, 1))));
        }

        void login() {
            assertFalse(handler.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "DistancePlayer", new ArrayList<>()), new ArrayList<>()));
            if (modern()) {
                assertInstanceOf(C2SLoginAcknowledgedPacket.class, backend.readOutbound());
                assertInstanceOf(S2CPlayStartConfigurationPacket.class, client.readOutbound());
            }
        }

        UnknownPacket expectSettings(final byte[] expected, final boolean configuration) {
            final var sent = assertInstanceOf(UnknownPacket.class, backend.readOutbound(),
                    "New backend must receive the player's saved view distance without opening client options");
            assertEquals((configuration ? MCPackets.C2S_CONFIG_CLIENT_INFORMATION : MCPackets.C2S_CLIENT_INFORMATION).getId(version), sent.packetId);
            assertArrayEquals(expected, sent.data, "Preserve the complete client settings payload");
            return sent;
        }

        void acknowledgeConfiguration() {
            assertFalse(handler.handleC2P(new C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>()));
        }

        void finishConfiguration() { finishConfiguration(null); }

        void finishConfiguration(final byte[] changed) {
            acknowledgeConfiguration();
            finishConfigurationAck(changed);
        }

        void finishConfigurationAck(final byte[] changed) {
            assertFalse(handler.handleP2S(new S2CConfigFinishConfigurationPacket(), new ArrayList<>()));
            assertInstanceOf(S2CConfigFinishConfigurationPacket.class, client.readOutbound());
            final var finish = new C2SConfigFinishConfigurationPacket();
            final List<ChannelFutureListener> listeners = new ArrayList<>();
            assertTrue(handler.handleC2P(finish, listeners));
            new ConfigurationPacketHandler(pc).handleC2P(finish, listeners);
            backend.writeAndFlush(finish).addListeners(listeners.toArray(new ChannelFutureListener[0]));
            if (changed != null) expectSettings(changed, true);
            assertSame(finish, backend.readOutbound(), "Settings must be sent before the backend finishes configuration");
            assertEquals(ConnectionState.PLAY, pc.getP2sConnectionState());
        }

        UnknownPacket join() {
            final var join = modern() ? ModernWorldPackets.join(version) : OlderWorldPackets.join(version);
            assertFalse(handler.handleP2S(join, new ArrayList<>()));
            return join;
        }

        void switchAndExpect(final boolean lobby, final byte[] expected) {
            startSwitch(lobby);
            login();
            if (modern()) {
                expectSettings(expected, true);
                finishConfiguration();
            }
            join();
            if (!modern()) expectSettings(expected, false);
            assertNull(backend.readOutbound());
        }

        @Override public void close() {
            client.finishAndReleaseAll();
            backends.forEach(EmbeddedChannel::finishAndReleaseAll);
        }

        @Override public void onSwitchComplete(ProxyConnection pc, SwitchJob job) { }
        @Override public void onSwitchFailed(ProxyConnection pc, SwitchJob job, String reason) { fail(reason); }
        @Override public void onTargetCommand(ProxyConnection pc, LobbyCommands.Command command) { fail("Unexpected command"); }
        @Override public void onForwardKick(ProxyConnection pc, SwitchJob job, String reason) { fail(reason); }
        @Override public void onForwardDeath(ProxyConnection pc, SwitchJob job) { fail("Unexpected backend death"); }
        @Override public void onTargetTransfer(ProxyConnection pc, SwitchJob job, String host, int port) { fail("Unexpected transfer"); }
    }
}
