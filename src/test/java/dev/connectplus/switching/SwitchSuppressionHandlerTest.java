package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.commands.LobbyCommands;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigKeepAlivePacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigKeepAlivePacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigSelectKnownPacksPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigSelectKnownPacksPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginDisconnectPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.netminecraft.packet.impl.play.C2SPlayKeepAlivePacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayStartConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayTransferPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import dev.connectplus.testutil.ViaProxyTestConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit tests for the suppression handler, one per row of the handler x switching
 * behavior matrix (docs/superpowers/notes/handler-switch-matrix.md). Uses real
 * ViaProxy PacketHandlers behind the suppression to verify the manual-invocation
 * semantics (state machines advance, nothing reaches the client).
 */
class SwitchSuppressionHandlerTest {

    private static final ProtocolVersion CLIENT_VERSION = ProtocolVersion.v1_21_4;

    private EmbeddedChannel c2p;
    private EmbeddedChannel p2s;
    private TestProxyConnection pc;
    private RecordingOwner owner;
    private SwitchSuppressionHandler suppression;

    /**
     * A ProxyConnection whose p2s channel can be swapped for an embedded one
     * (the channelFuture field is protected, so a subclass may assign it).
     */
    static final class TestProxyConnection extends ProxyConnection {
        private ProtocolVersion serverVersion;

        TestProxyConnection(final EmbeddedChannel c2p) {
            super(new MinecraftChannelInitializer(Proxy2ServerHandler::new), c2p);
        }

        void attachP2s(final EmbeddedChannel p2s) {
            this.channelFuture = p2s.newSucceededFuture();
        }

        void setServerVersion(final ProtocolVersion version) {
            this.serverVersion = version;
        }

        @Override
        public ProtocolVersion getServerVersion() {
            return this.serverVersion;
        }
    }

    private static final class RecordingOwner implements SwitchSuppressionHandler.Owner {
        final List<SwitchJob> completed = new ArrayList<>();
        final List<FailedEvent> failed = new ArrayList<>();
        final List<LobbyCommands.Command> commands = new ArrayList<>();
        final List<KickEvent> forwardKicks = new ArrayList<>();
        final List<ProxyConnection> forwardDeaths = new ArrayList<>();
        final List<TransferEvent> transfers = new ArrayList<>();

        @Override
        public void onSwitchComplete(final ProxyConnection pc, final SwitchJob job) {
            this.completed.add(job);
        }

        @Override
        public void onSwitchFailed(final ProxyConnection pc, final SwitchJob job, final String reason) {
            this.failed.add(new FailedEvent(job, reason));
        }

        @Override
        public void onTargetCommand(final ProxyConnection pc, final LobbyCommands.Command command) {
            this.commands.add(command);
        }

        @Override
        public void onForwardKick(final ProxyConnection pc, final SwitchJob job, final String reason) {
            this.forwardKicks.add(new KickEvent(job, reason));
        }

        @Override
        public void onForwardDeath(final ProxyConnection pc, final SwitchJob job) {
            this.forwardDeaths.add(pc);
        }

        @Override
        public void onTargetTransfer(final ProxyConnection pc, final SwitchJob job, final String host, final int port) {
            this.transfers.add(new TransferEvent(host, port));
        }
    }

    private record FailedEvent(SwitchJob job, String reason) {
    }

    private record KickEvent(SwitchJob job, String reason) {
    }

    private record TransferEvent(String host, int port) {
    }

    @BeforeAll
    static void bootViaProxyConfig() {
        ViaProxyTestConfig.init();
    }

    @BeforeEach
    void setUp() {
        this.c2p = new EmbeddedChannel();
        //The MCPipeline of a real c2p channel always carries the compression threshold;
        //256 mirrors the hot-switch reality: the client connection compressed at its
        //original login, so the compression handler's c2p branch is a no-op (matrix row 6).
        this.c2p.attr(MCPipeline.COMPRESSION_THRESHOLD_ATTRIBUTE_KEY).set(256);
        this.pc = new TestProxyConnection(this.c2p);
        this.pc.setClientVersion(CLIENT_VERSION);
        //A hot switch always starts from a client already in PLAY
        this.pc.setC2pConnectionState(ConnectionState.PLAY);
        this.pc.setGameProfile(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "TestPlayer"));
        this.p2s = new EmbeddedChannel();
        this.p2s.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, CLIENT_VERSION.getVersion()));
        this.pc.attachP2s(this.p2s);
        this.owner = new RecordingOwner();
        this.suppression = new SwitchSuppressionHandler(this.pc, this.owner);
        this.pc.getPacketHandlers().add(this.suppression);
        //The real ViaProxy handlers behind the suppression: they must keep running
        //during a switch to advance the backend login state machine (matrix rows).
        this.pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.CompressionPacketHandler(this.pc));
        this.pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.LoginPacketHandler(this.pc));
        this.pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.DisconnectPacketHandler(this.pc));
        this.pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.ConfigurationPacketHandler(this.pc));
    }

    private SwitchJob beginJob(final boolean lobby) {
        final SwitchJob job = SwitchJob.starting(new SwitchJob.Target("127.0.0.1:25565", CLIENT_VERSION, UUID.randomUUID(), "TestPlayer", lobby, 1));
        assertNotNull(this.suppression.begin(job), "begin must succeed on an idle handler");
        return job;
    }

    private static UnknownPacket unknown(final MCPackets packet, final String firstField) {
        final ByteBuf buf = Unpooled.buffer();
        PacketTypes.writeString(buf, firstField);
        return new UnknownPacket(packet.getId(CLIENT_VERSION.getVersion()), ByteBufUtil.getBytes(buf));
    }

    private static Packet readOutbound(final EmbeddedChannel channel, final Class<? extends Packet> type) {
        for (;;) {
            final Object message = channel.readOutbound();
            if (message == null) {
                throw new NoSuchElementException("No outbound " + type.getSimpleName() + " on the channel");
            }
            if (type.isInstance(message)) {
                return (Packet) message;
            }
        }
    }

    private static void assertNoOutbound(final EmbeddedChannel channel) {
        assertTrue(channel.outboundMessages().isEmpty(), "Unexpected outbound traffic: " + channel.outboundMessages());
    }

    @Test
    void idlePassesBothDirectionsThrough() {
        final Packet packet = new S2CPlayDisconnectPacket(new StringComponent("x"));
        assertTrue(this.suppression.handleP2S(packet, new ArrayList<>()));
        assertTrue(this.suppression.handleC2P(packet, new ArrayList<>()));
        assertNoOutbound(this.c2p);
        assertNoOutbound(this.p2s);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {47, 765, 769, 774})
    void cachedAccountCannotAuthenticateAfterProxyOnlineModeIsDisabled(final int clientVersion) {
        final boolean online = net.raphimc.viaproxy.ViaProxy.getConfig().isProxyOnlineMode();
        final boolean allowed = dev.connectplus.config.CPConfig.allowAccountLogin;
        try {
            net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(false);
            dev.connectplus.config.CPConfig.allowAccountLogin = true;
            this.pc.setClientVersion(ProtocolVersion.getProtocol(clientVersion));
            this.pc.setServerVersion(ProtocolVersion.getProtocol(clientVersion));
            final SwitchJob job = beginJob(false);
            job.markAuthenticated(true); // account was attached before the policy changed
            this.pc.getPacketHandlers().clear();
            this.pc.getPacketHandlers().add(this.suppression);
            final java.util.concurrent.atomic.AtomicInteger forwarded = new java.util.concurrent.atomic.AtomicInteger();
            this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
                @Override public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                    forwarded.incrementAndGet();
                    return true;
                }
            });
            final var encoded = new net.raphimc.netminecraft.packet.impl.login.S2CLoginHelloPacket("", new byte[]{1}, new byte[]{2}, true);
            final ByteBuf wire = Unpooled.buffer();
            final var request = new net.raphimc.netminecraft.packet.impl.login.S2CLoginHelloPacket();
            try {
                encoded.write(wire, clientVersion);
                request.read(wire, clientVersion);
                assertEquals(clientVersion >= 766, request.authenticate, "Older protocols have no authentication flag on the wire");
            } finally {
                wire.release();
            }
            assertFalse(this.suppression.handleP2S(request, new ArrayList<>()));
            assertEquals(0, forwarded.get(), "Account authentication must be blocked before ViaProxy sees the request");
            assertEquals(1, this.owner.failed.size());
            assertNoOutbound(this.c2p);
        } finally {
            net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(online);
            dev.connectplus.config.CPConfig.allowAccountLogin = allowed;
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "false,true", "true,false"})
    void bedrockBackendAuthenticationUsesItsTrustedIdentityAndAdminSetting(final boolean allowed, final boolean currentLease) {
        final boolean oldOnline = net.raphimc.viaproxy.ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = dev.connectplus.config.CPConfig.allowAccountLogin;
        final boolean oldGeyser = dev.connectplus.config.CPConfig.GeyserSupport.enabled;
        try {
            net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(false);
            dev.connectplus.config.CPConfig.allowAccountLogin = allowed;
            dev.connectplus.config.CPConfig.GeyserSupport.enabled = true;
            dev.connectplus.testutil.TestClientIdentity.bedrock(this.c2p,
                    dev.connectplus.identity.ClientIdentity.verifiedBedrock(UUID.randomUUID(), "BedrockPlayer",
                            "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString()));
            final UUID connectionId = this.c2p.attr(dev.connectplus.compat.CPAttributeKeys.CONNECTION_ID).get();
            SwitchEngine.setAccountLeaseValidator(id -> currentLease && connectionId.equals(id));
            final SwitchJob job = this.suppression.begin(SwitchJob.starting(new SwitchJob.Target(
                    "backend.example.net:25565", CLIENT_VERSION, UUID.randomUUID(), "BedrockPlayer",
                    false, 1, false, connectionId)));
            job.markAuthenticated(true);
            this.pc.getPacketHandlers().clear();
            this.pc.getPacketHandlers().add(this.suppression);
            final var forwarded = new java.util.concurrent.atomic.AtomicInteger();
            this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
                @Override public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                    forwarded.incrementAndGet();
                    return true;
                }
            });
            this.suppression.handleP2S(new net.raphimc.netminecraft.packet.impl.login.S2CLoginHelloPacket(
                    "", new byte[]{1}, new byte[]{2}, true), new ArrayList<>());
            assertEquals(allowed && currentLease ? 1 : 0, forwarded.get());
            assertEquals(allowed && currentLease ? 0 : 1, this.owner.failed.size());
        } finally {
            SwitchEngine.setAccountLeaseValidator(null);
            net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(oldOnline);
            dev.connectplus.config.CPConfig.allowAccountLogin = oldLogin;
            dev.connectplus.config.CPConfig.GeyserSupport.enabled = oldGeyser;
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {769, 774})
    void configPongUsesTranslatedClientVersionWithOlderBackend(final int clientVersion) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(clientVersion));
        this.p2s.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, clientVersion));
        this.pc.setServerVersion(ProtocolVersion.v1_20_2);
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        beginJob(false);
        final byte[] ping = {0x12, 0x34, 0x56, 0x78};
        assertFalse(this.suppression.handleP2S(new UnknownPacket(5, ping), new ArrayList<>()));
        final UnknownPacket pong = this.p2s.readOutbound();
        assertNotNull(pong);
        assertEquals(5, pong.packetId, "The Via codec expects the client's pong ID, not the backend's 764 ID");
        org.junit.jupiter.api.Assertions.assertArrayEquals(ping, pong.data);
        assertNoOutbound(this.c2p);
        assertTrue(this.owner.failed.isEmpty());
    }

    @Test
    void configPongUsesTranslatedClientVersionWithNewerBackend() {
        this.pc.setClientVersion(ProtocolVersion.v1_20_2);
        this.p2s.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 764));
        this.pc.setServerVersion(CLIENT_VERSION);
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        beginJob(false);
        final byte[] ping = {0x12, 0x34, 0x56, 0x78};
        assertFalse(this.suppression.handleP2S(new UnknownPacket(4, ping), new ArrayList<>()));
        final UnknownPacket pong = this.p2s.readOutbound();
        assertNotNull(pong);
        assertEquals(4, pong.packetId);
        org.junit.jupiter.api.Assertions.assertArrayEquals(ping, pong.data);
        assertNoOutbound(this.c2p);
    }

    @Test
    void unrelatedShortConfigPacketsDoNotProducePongsOrKeepalives() {
        this.pc.setServerVersion(CLIENT_VERSION);
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        beginJob(false);
        //custom payload (ID 1), not ping or keepalive, despite the same body sizes
        assertFalse(this.suppression.handleP2S(new UnknownPacket(1, new byte[4]), new ArrayList<>()));
        assertFalse(this.suppression.handleP2S(new UnknownPacket(1, new byte[8]), new ArrayList<>()));
        assertNoOutbound(this.p2s);
        assertNoOutbound(this.c2p);
        assertTrue(this.owner.failed.isEmpty());
    }

    @Test
    void malformedConfigPingFailsWithoutSendingAnInvalidPong() {
        this.pc.setServerVersion(CLIENT_VERSION);
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        beginJob(false);
        assertFalse(this.suppression.handleP2S(new UnknownPacket(5, new byte[3]), new ArrayList<>()));
        assertNoOutbound(this.p2s);
        assertEquals(1, this.owner.failed.size());
        assertNoOutbound(this.c2p);
    }

    @Test
    void malformedConfigKeepaliveFailsWithoutSendingAnInvalidReply() {
        this.pc.setServerVersion(CLIENT_VERSION);
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        beginJob(false);
        assertFalse(this.suppression.handleP2S(new UnknownPacket(4, new byte[7]), new ArrayList<>()));
        assertNoOutbound(this.p2s);
        assertEquals(1, this.owner.failed.size());
        assertNoOutbound(this.c2p);
    }

    @Test
    void beginRejectsConcurrentJobsAndAcceptsAfterTerminal() {
        final SwitchJob first = beginJob(false);
        final SwitchJob second = SwitchJob.starting(first.target());
        assertNull(this.suppression.begin(second), "a running switch must reject a second job");
        assertTrue(first.markForward());
        assertNotNull(this.suppression.begin(second), "a finished switch frees the slot");
    }

    @Test
    void switchingDropsP2sButRunsRemainingHandlers() {
        final List<Packet> seen = new ArrayList<>();
        this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
            @Override
            public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                seen.add(packet);
                return true;
            }
        });
        beginJob(false);
        final Packet packet = new S2CPlayDisconnectPacket(new StringComponent("x"));
        assertFalse(this.suppression.handleP2S(packet, new ArrayList<>()), "SWITCHING suppresses the final write");
        assertEquals(List.of(packet), seen, "Remaining handlers must still run (matrix)");
        assertNoOutbound(this.c2p);
    }

    @Test
    void switchingDropsClientPackets() {
        beginJob(false);
        final UnknownPacket chat = unknown(MCPackets.C2S_CHAT, "hello");
        assertFalse(this.suppression.handleC2P(chat, new ArrayList<>()));
        assertNoOutbound(this.p2s);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {764, 765, 766, 767, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777})
    void targetRegistriesReachTheClientBeforeTheBackendCanSendChunks(final int version) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(version));
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        beginJob(false);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        assertInstanceOf(S2CPlayStartConfigurationPacket.class, this.c2p.readOutbound());
        readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        final UnknownPacket registry = new UnknownPacket(MCPackets.S2C_CONFIG_REGISTRY_DATA.getId(version), new byte[]{1,2,3});
        assertFalse(this.suppression.handleP2S(registry, new ArrayList<>()));
        assertNoOutbound(this.c2p);
        assertFalse(this.suppression.handleC2P(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>()));
        assertEquals(ConnectionState.CONFIGURATION, this.pc.getC2pConnectionState());
        final UnknownPacket delivered = assertInstanceOf(UnknownPacket.class, this.c2p.readOutbound());
        org.junit.jupiter.api.Assertions.assertArrayEquals(registry.data, delivered.data);
        assertFalse(this.suppression.handleP2S(new S2CConfigFinishConfigurationPacket(), new ArrayList<>()));
        assertInstanceOf(S2CConfigFinishConfigurationPacket.class, this.c2p.readOutbound());
        assertEquals(ConnectionState.CONFIGURATION, this.pc.getP2sConnectionState());
        assertNoOutbound(this.p2s);
        final var finish = new C2SConfigFinishConfigurationPacket();
        final List<ChannelFutureListener> listeners = new ArrayList<>();
        assertTrue(this.suppression.handleC2P(finish, listeners));
        new net.raphimc.viaproxy.proxy.packethandler.ConfigurationPacketHandler(this.pc).handleC2P(finish, listeners);
        this.p2s.writeAndFlush(finish).addListeners(listeners.toArray(new ChannelFutureListener[0]));
        assertEquals(ConnectionState.PLAY, this.pc.getP2sConnectionState());
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
    }

    @Test
    void loginGameProfileStartsFrontendConfigurationWithoutRepeatingLogin() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        beginJob(false);
        final boolean forward = this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        assertFalse(forward, "The login success packet must not reach the client");
        assertInstanceOf(S2CPlayStartConfigurationPacket.class, this.c2p.readOutbound());
        assertNoOutbound(this.c2p);
        assertEquals(ConnectionState.CONFIGURATION, this.pc.getP2sConnectionState(), "The p2s must advance to configuration for 1.20.2+ clients");
        final C2SLoginAcknowledgedPacket ack = (C2SLoginAcknowledgedPacket) readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        assertNotNull(ack, "The proxy must synthesize the login acknowledgement for the new backend");
        assertTrue(this.p2s.config().isAutoRead(), "Auto read must be restored after the login packet handler disabled it");
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState(), "The client state must never change during a switch");
    }

    @Test
    void configFinishWaitsForTheFrontendAckBeforeEnteringPlay() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        beginJob(false);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.c2p, S2CPlayStartConfigurationPacket.class);
        readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        assertFalse(this.suppression.handleC2P(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>()));
        assertFalse(this.suppression.handleP2S(new S2CConfigFinishConfigurationPacket(), new ArrayList<>()));
        readOutbound(this.c2p, S2CConfigFinishConfigurationPacket.class);
        assertEquals(ConnectionState.CONFIGURATION, this.pc.getP2sConnectionState());
        assertNoOutbound(this.p2s);
        assertFalse(this.p2s.config().isAutoRead());
    }

    @Test
    void configKeepaliveIsAnsweredToTheBackend() {
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        beginJob(false);
        assertFalse(this.suppression.handleP2S(new S2CConfigKeepAlivePacket(7), new ArrayList<>()));
        final C2SConfigKeepAlivePacket answer = (C2SConfigKeepAlivePacket) readOutbound(this.p2s, C2SConfigKeepAlivePacket.class);
        assertEquals(7L, answer.id, "The keepalive must be echoed with the same id");
        assertNoOutbound(this.c2p);
    }

    @Test
    void knownPacksQueryIsAnsweredWithAnEmptyList() {
        //Vanilla 1.20.5+ servers stall the configuration phase until the client
        //reports its known packs; without this answer every hot switch to a real
        //vanilla server timed out (manual verification finding)
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        beginJob(false);
        assertFalse(this.suppression.handleP2S(new S2CConfigSelectKnownPacksPacket(List.of()), new ArrayList<>()));
        final C2SConfigSelectKnownPacksPacket answer = (C2SConfigSelectKnownPacksPacket) readOutbound(this.p2s, C2SConfigSelectKnownPacksPacket.class);
        assertTrue(answer.knownPacks.isEmpty(), "The answer must be the empty pack list so the backend sends full registry data");
        assertNoOutbound(this.c2p);
    }

    @Test
    void backendDisconnectFailsTheJobWithoutForwarding() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        final SwitchJob job = beginJob(false);
        assertFalse(this.suppression.handleP2S(new S2CPlayDisconnectPacket(new StringComponent("Server closed")), new ArrayList<>()));
        assertFalse(job.isSwitching(), "The job must be terminal after a backend disconnect");
        assertEquals("Server closed", this.owner.failed.get(0).reason());
        assertNoOutbound(this.c2p);
    }

    @Test
    void loginDisconnectFailsTheJob() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        final SwitchJob job = beginJob(false);
        assertFalse(this.suppression.handleP2S(new S2CLoginDisconnectPacket(new StringComponent("Banned")), new ArrayList<>()));
        assertEquals("Banned", this.owner.failed.get(0).reason());
        assertFalse(job.isSwitching());
    }

    @Test
    void joinGameFlipsToForwardAndForwardsNormally() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        final UnknownPacket joinGame = dev.connectplus.testutil.ModernWorldPackets.join(769);
        assertFalse(this.suppression.handleP2S(joinGame, new ArrayList<>()), "The modern join/reset path writes both packets itself");
        assertTrue(!job.isSwitching() && this.owner.completed.size() == 1, "The job must be completed exactly once");
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
        org.junit.jupiter.api.Assertions.assertSame(joinGame, this.c2p.readOutbound());
        assertEquals(MCPackets.S2C_RESPAWN.getId(769), ((UnknownPacket) this.c2p.readOutbound()).packetId);
        assertNoOutbound(this.c2p);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {764, 765, 766, 767, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777})
    void modernSwitchWritesJoinThenRespawnWithNoOldPlayerData(final int version) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(version));
        this.p2s.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, version));
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        final UnknownPacket join = dev.connectplus.testutil.ModernWorldPackets.join(version, true);
        final java.util.concurrent.atomic.AtomicInteger completedWrites = new java.util.concurrent.atomic.AtomicInteger();
        this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
            @Override
            public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                if (packet == join) listeners.add(future -> completedWrites.incrementAndGet());
                return true;
            }
        });
        assertFalse(this.suppression.handleP2S(join, new ArrayList<>()), "The ordered join/reset pair is written exactly once");
        org.junit.jupiter.api.Assertions.assertSame(join, this.c2p.readOutbound());
        final UnknownPacket reset = this.c2p.readOutbound();
        assertNotNull(reset, "A mid-play JoinGame alone retains the lobby player's movement state");
        assertEquals(MCPackets.S2C_RESPAWN.getId(version), reset.packetId);
        org.junit.jupiter.api.Assertions.assertArrayEquals(dev.connectplus.testutil.ModernWorldPackets.respawn(version, true), reset.data);
        assertEquals(1, completedWrites.get(), "Original JoinGame listeners must still run");
        assertEquals(List.of(job), this.owner.completed);
        assertNoOutbound(this.c2p);
        assertNoOutbound(this.p2s);
    }

    @Test
    void malformedModernJoinFailsBeforeAnnouncingSwitchCompletion() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        beginJob(false);
        final UnknownPacket truncated = new UnknownPacket(MCPackets.S2C_LOGIN.getId(769), new byte[4]);
        assertFalse(this.suppression.handleP2S(truncated, new ArrayList<>()));
        assertTrue(this.owner.completed.isEmpty());
        assertEquals(1, this.owner.failed.size());
        assertNoOutbound(this.c2p);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {775, 776, 777})
    void modernLobbyReturnWritesJoinThenResetAndReleasesSwitch(final int version) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(version));
        this.p2s.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, version));
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(true);
        final UnknownPacket join = dev.connectplus.testutil.ModernWorldPackets.join(version, false, true, false);
        assertFalse(this.suppression.handleP2S(join, new ArrayList<>()));
        org.junit.jupiter.api.Assertions.assertSame(join, this.c2p.readOutbound());
        final UnknownPacket reset = this.c2p.readOutbound();
        assertNotNull(reset);
        assertEquals(MCPackets.S2C_RESPAWN.getId(version), reset.packetId);
        org.junit.jupiter.api.Assertions.assertArrayEquals(dev.connectplus.testutil.ModernWorldPackets.respawn(version), reset.data);
        assertEquals(List.of(job), this.owner.completed);
        assertTrue(this.owner.failed.isEmpty());
        assertNull(this.suppression.currentJob(), "Returning to the lobby must allow the next switch");
        assertNoOutbound(this.c2p);
        assertNoOutbound(this.p2s);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void truncated26_2JoinFlagsFailBeforeAnyClientWrite(final int missingFlags) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(776));
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        final UnknownPacket join = dev.connectplus.testutil.ModernWorldPackets.join(776);
        join.data = java.util.Arrays.copyOf(join.data, join.data.length - missingFlags);
        assertFalse(this.suppression.handleP2S(join, new ArrayList<>()));
        assertTrue(this.owner.completed.isEmpty());
        assertEquals(1, this.owner.failed.size());
        assertNotNull(job.failureReason());
        assertNoOutbound(this.c2p);
    }

    static java.util.stream.Stream<Integer> olderClientProtocols() {
        // Compare ordered versions BEFORE extracting IDs: ViaLegacy's alpha/beta
        // wire IDs overlap modern release IDs but are not supported lobby clients.
        return ProtocolVersion.getProtocols().stream()
                .filter(version -> version.newerThanOrEqualTo(ProtocolVersion.getProtocol(4))
                        && version.olderThan(ProtocolVersion.v1_20_2))
                .map(ProtocolVersion::getVersion).distinct();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("olderClientProtocols")
    void everyOlderSupportedClientReceivesItsWorldReset(final int version) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(version));
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        final UnknownPacket join = dev.connectplus.testutil.OlderWorldPackets.join(version);
        assertFalse(this.suppression.handleP2S(join, new ArrayList<>()));
        if (version >= 107) org.junit.jupiter.api.Assertions.assertSame(join, this.c2p.readOutbound());
        if (version < 735) {
            final UnknownPacket dummy = this.c2p.readOutbound();
            assertNotNull(dummy, "Pre-1.16 needs a dimension change even for same-world switches");
            assertEquals(MCPackets.S2C_RESPAWN.getId(version), dummy.packetId);
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    dev.connectplus.testutil.OlderWorldPackets.respawn(version, true), dummy.data);
        }
        final UnknownPacket reset = this.c2p.readOutbound();
        assertNotNull(reset, "Every supported client must reset its player state");
        assertEquals(MCPackets.S2C_RESPAWN.getId(version), reset.packetId);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                dev.connectplus.testutil.OlderWorldPackets.respawn(version, false), reset.data);
        assertEquals(List.of(job), this.owner.completed);
        assertNoOutbound(this.c2p);
        assertNoOutbound(this.p2s);
    }

    @Test
    void remainingHandlerCanVetoTheJoinWithoutWritesOrCompletion() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
            @Override
            public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                calls.incrementAndGet();
                return false;
            }
        });
        assertFalse(this.suppression.handleP2S(dev.connectplus.testutil.ModernWorldPackets.join(769), new ArrayList<>()));
        assertEquals(1, calls.get());
        assertTrue(job.isSwitching());
        assertTrue(this.owner.completed.isEmpty());
        assertNoOutbound(this.c2p);
    }

    @Test
    void failureDuringRemainingHandlersCannotLeakAJoinWithoutItsReset() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
            @Override
            public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                job.fail("timeout won the completion race");
                return true;
            }
        });
        assertFalse(this.suppression.handleP2S(dev.connectplus.testutil.ModernWorldPackets.join(769), new ArrayList<>()),
                "Returning true would forward a bare JoinGame and invoke the remaining handlers again");
        assertTrue(this.owner.completed.isEmpty());
        assertNoOutbound(this.c2p);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("olderClientProtocols")
    void truncatedOlderJoinsFailWithoutPartialClientWrites(final int version) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(version));
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        beginJob(false);
        final UnknownPacket valid = dev.connectplus.testutil.OlderWorldPackets.join(version);
        final UnknownPacket truncated = new UnknownPacket(valid.packetId,
                java.util.Arrays.copyOf(valid.data, valid.data.length - 1));
        assertFalse(this.suppression.handleP2S(truncated, new ArrayList<>()));
        assertEquals(1, this.owner.failed.size());
        assertTrue(this.owner.completed.isEmpty());
        assertNoOutbound(this.c2p);
    }

    @Test
    void legacyJoinGameIsConvertedIntoARespawnPair() {
        //BungeeCord's legacy switch behavior: pre-1.8 clients never receive a
        //mid-play JoinGame (it degrades their player state — V7 finding); the
        //JoinGame is withheld and converted into a dummy→real respawn pair
        this.pc.setClientVersion(ProtocolVersion.v1_7_6);
        this.p2s.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, ProtocolVersion.v1_7_6.getVersion()));
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        //1.7 JoinGame payload: int entityId, ubyte gamemode, byte dimension, ubyte difficulty, ubyte maxPlayers, string levelType
        final ByteBuf payload = Unpooled.buffer();
        payload.writeInt(665);
        payload.writeByte(0);
        payload.writeByte(0);
        payload.writeByte(0);
        payload.writeByte(5);
        PacketTypes.writeString(payload, "default");
        final UnknownPacket joinGame = new UnknownPacket(MCPackets.S2C_LOGIN.getId(ProtocolVersion.v1_7_6.getVersion()), ByteBufUtil.getBytes(payload));
        assertFalse(this.suppression.handleP2S(joinGame, new ArrayList<>()),
                "The mid-play JoinGame must never reach a legacy client");
        assertTrue(!job.isSwitching() && this.owner.completed.size() == 1, "The job still completes exactly once");

        //Exactly two respawns: dummy dimension (-1, since the target is the overworld 0)
        //then the real one (0), both carrying the JoinGame's gamemode and difficulty
        final Object dummy = this.c2p.outboundMessages().poll();
        final Object real = this.c2p.outboundMessages().poll();
        assertTrue(dummy instanceof UnknownPacket && real instanceof UnknownPacket, "The respawn pair must be written to the client");
        assertEquals(0x07, ((UnknownPacket) dummy).packetId, "Both injected packets must be 1.7 respawns");
        assertEquals(0x07, ((UnknownPacket) real).packetId, "Both injected packets must be 1.7 respawns");
        assertEquals(-1, java.nio.ByteBuffer.wrap(((UnknownPacket) dummy).data).getInt(), "The dummy respawn flips the dimension");
        assertEquals(0, java.nio.ByteBuffer.wrap(((UnknownPacket) real).data).getInt(), "The real respawn uses the JoinGame's dimension");
        assertTrue(this.c2p.outboundMessages().isEmpty(), "Nothing else may be written");
    }
    @Test
    void eightPointNineClientsGetTheRespawnPairToo() {
        //1.8 shares the 1.7 JoinGame head and respawn layout, so the conversion
        //gate extends to it (V9 finding: a 1.8.9 client stuck the same way)
        this.pc.setClientVersion(ProtocolVersion.v1_8);
        this.p2s.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, ProtocolVersion.v1_8.getVersion()));
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        final SwitchJob job = beginJob(false);
        final UnknownPacket joinGame = dev.connectplus.testutil.OlderWorldPackets.join(47);
        assertFalse(this.suppression.handleP2S(joinGame, new ArrayList<>()));
        assertTrue(!job.isSwitching() && this.owner.completed.size() == 1);
        final Object dummy = this.c2p.outboundMessages().poll();
        final Object real = this.c2p.outboundMessages().poll();
        assertTrue(dummy instanceof UnknownPacket && ((UnknownPacket) dummy).packetId == 0x07);
        assertTrue(real instanceof UnknownPacket && ((UnknownPacket) real).packetId == 0x07);
        assertTrue(this.c2p.outboundMessages().isEmpty());
    }

    @Test
    void joinGameOnLobbyTargetSettlesTheSuppressionToIdle() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        beginJob(true);
        final UnknownPacket joinGame = dev.connectplus.testutil.ModernWorldPackets.join(769);
        assertFalse(this.suppression.handleP2S(joinGame, new ArrayList<>()));
        assertTrue(this.owner.completed.size() == 1);
        //Back in the lobby the lobby command handling takes over again
        assertTrue(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT, "hello"), new ArrayList<>()));
    }

    @Test
    void forwardInterceptsExitCommandsAndPassesEverythingElse() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        beginJob(false);
        assertFalse(this.suppression.handleP2S(dev.connectplus.testutil.ModernWorldPackets.join(769), new ArrayList<>()));

        assertTrue(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT, "just chatting"), new ArrayList<>()),
                "Plain chat passes through to the target server");
        final LobbyCommands.Command disconnect = this.owner.commands.isEmpty() ? null : this.owner.commands.get(0);
        assertNull(disconnect);
        assertFalse(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT, "/disconnect"), new ArrayList<>()), "The /disconnect command must be consumed");
        assertEquals(1, this.owner.commands.size());
        assertEquals(LobbyCommands.Type.DISCONNECT, this.owner.commands.get(0).type());
        assertFalse(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT, "/dc"), new ArrayList<>()), "The /dc alias must be consumed too");
        assertEquals(2, this.owner.commands.size());
        assertEquals(LobbyCommands.Type.DISCONNECT, this.owner.commands.get(1).type());

        assertTrue(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT_COMMAND, "connect play.example.net"), new ArrayList<>()),
                "The removed /connect command passes through to the target server");
        assertTrue(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT, "/disconnect now"), new ArrayList<>()),
                "A usage-error exit input is not an exit command and passes through on a target");
        assertTrue(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT, "/bookmarks"), new ArrayList<>()),
                "Removed commands pass through on a target");
        assertEquals(2, this.owner.commands.size(), "Nothing else may reach the engine callback");
    }

    @Test
    void transferPacketsAreDroppedDuringSwitchingWithoutRunningHandlers() {
        final List<Packet> seen = new ArrayList<>();
        this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
            @Override
            public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                seen.add(packet);
                return true;
            }
        });
        beginJob(false);
        assertFalse(this.suppression.handleP2S(new S2CPlayTransferPacket("evil.example.net", 25565), new ArrayList<>()));
        assertFalse(this.suppression.handleP2S(new S2CPlayTransferPacket("anywhere.example.net", 25565), new ArrayList<>()));
        assertFalse(this.suppression.handleP2S(new S2CPlayStartConfigurationPacket(), new ArrayList<>()));
        assertEquals(List.of(), seen, "Transfer/start-configuration packets must not reach the remaining handlers");
        assertNoOutbound(this.c2p);
    }

    @Test
    void cookieRequestsAreAnsweredWithEmptyPayloads() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        beginJob(false);
        assertFalse(this.suppression.handleP2S(new net.raphimc.netminecraft.packet.impl.login.S2CLoginCookieRequestPacket("minecraft:cookie"), new ArrayList<>()));
        final net.raphimc.netminecraft.packet.impl.login.C2SLoginCookieResponsePacket answer =
                (net.raphimc.netminecraft.packet.impl.login.C2SLoginCookieResponsePacket) readOutbound(this.p2s, net.raphimc.netminecraft.packet.impl.login.C2SLoginCookieResponsePacket.class);
        assertEquals("minecraft:cookie", answer.key);
        assertNull(answer.payload);
    }

    @Test
    void clientKeepaliveEchoesAreDroppedDuringSwitching() {
        beginJob(false);
        final C2SPlayKeepAlivePacket echo = new C2SPlayKeepAlivePacket(42);
        assertFalse(this.suppression.handleC2P(echo, new ArrayList<>()));
        assertNoOutbound(this.p2s);
        assertNoOutbound(this.c2p);
    }

    @Test
    void guardChannelDeathFailsTheRunningSwitch() {
        final SwitchJob job = beginJob(false);
        assertTrue(this.suppression.guardChannelDeath(this.p2s), "A p2s death during switching must be swallowed");
        assertFalse(job.isSwitching());
        assertEquals(1, this.owner.failed.size(), "The engine must be told to fall back to the lobby");
        assertNoOutbound(this.c2p);
    }

    @Test
    void guardChannelDeathPassesThroughWhenIdle() {
        assertTrue(!this.suppression.guardChannelDeath(this.p2s), "Idle passthrough connections keep ViaProxy behavior");
        //A FORWARD death on a server target is now recovered by the engine (M5 F3.1);
        //that semantic is pinned by forwardDeathCallbackFiresForServerTarget and the
        //lobby-target no-recursion case by lobbyTargetDeathNeverRecurses.
    }

    private void driveToForward() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        this.suppression.handleP2S(dev.connectplus.testutil.ModernWorldPackets.join(769), new ArrayList<>());
        this.c2p.outboundMessages().clear(); //join/reset are setup traffic, not the FORWARD action under test
    }

    @Test
    void playKickInterceptedToLobbyUnderDefaultPolicy() {
        final List<Packet> seen = new ArrayList<>();
        this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
            @Override
            public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                seen.add(packet);
                return true;
            }
        });
        final SwitchJob job = beginJob(false);
        this.driveToForward();

        seen.clear(); //modern JoinGame intentionally runs the remaining handlers during setup

        assertTrue(this.suppression.handleP2S(new S2CPlayDisconnectPacket(new StringComponent("You died!")), new ArrayList<>()) == false,
                "The play disconnect must be intercepted (M5 kickPolicy=lobby)");
        assertEquals(1, this.owner.forwardKicks.size(), "The engine must be told about the kick");
        assertEquals("You died!", this.owner.forwardKicks.get(0).reason());
        assertEquals(List.of(), seen, "No remaining handler may run for an intercepted kick");
        assertNoOutbound(this.c2p);
        assertTrue(!job.isSwitching());
    }

    @Test
    void playKickForwardedUnderDisconnectPolicy() {
        final String original = dev.connectplus.config.CPConfig.kickPolicy;
        dev.connectplus.config.CPConfig.kickPolicy = "disconnect";
        try {
            beginJob(false);
            this.driveToForward();
            assertTrue(this.suppression.handleP2S(new S2CPlayDisconnectPacket(new StringComponent("Bye")), new ArrayList<>()),
                    "kickPolicy=disconnect forwards the kick (vanilla behavior)");
            assertTrue(this.owner.forwardKicks.isEmpty(), "No interception callback under the disconnect policy");
        } finally {
            dev.connectplus.config.CPConfig.kickPolicy = original;
        }
    }

    @Test
    void transferPoliciesDoNotRunTheStockTransferHandler() {
        final List<Packet> seen = new ArrayList<>();
        this.pc.getPacketHandlers().add(new PacketHandler(this.pc) {
            @Override
            public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
                seen.add(packet);
                return true;
            }
        });
        beginJob(false);
        this.driveToForward();
        final String original = dev.connectplus.config.CPConfig.transferPolicy;
        try {
            for (final String policy : List.of("confirm", "follow", "ignore")) {
                dev.connectplus.config.CPConfig.transferPolicy = policy;
                seen.clear();
                this.owner.transfers.clear();
                this.c2p.outboundMessages().clear();
                assertFalse(this.suppression.handleP2S(new S2CPlayTransferPacket("evil.example.net", 25565), new ArrayList<>()),
                        "The transfer must be intercepted under policy " + policy);
                assertEquals(List.of(), seen, "The stock transfer handler must not run (no temp redirect) under " + policy);
                assertNoOutbound(this.c2p);
                assertEquals(1, this.owner.transfers.size(), "The engine must see the transfer under " + policy);
                assertEquals("evil.example.net", this.owner.transfers.get(0).host());
                assertEquals(25565, this.owner.transfers.get(0).port());
            }
        } finally {
            dev.connectplus.config.CPConfig.transferPolicy = original;
        }
    }

    @Test
    void forwardDeathCallbackFiresForServerTarget() {
        beginJob(false);
        this.driveToForward();
        assertTrue(this.suppression.guardChannelDeath(this.p2s),
                "A p2s death on a server target in FORWARD must be consumed for recovery");
        assertEquals(1, this.owner.forwardDeaths.size(), "The engine must be told about the death");
        assertNoOutbound(this.c2p);
    }

    @Test
    void kickRecoveryOwnsTheSubsequentP2sDeath() {
        //The vanilla server closes the p2s right after sending a kick. The kick
        //intercept starts the lobby recovery; the death must be swallowed instead of
        //racing it with the backend-down policy / failing the fresh lobby fallback
        //(V4 verification finding: the race kicked the player off the proxy).
        beginJob(false);
        this.driveToForward();
        assertFalse(this.suppression.handleP2S(
                new S2CPlayDisconnectPacket(new StringComponent("kicked for test")), new ArrayList<>()));
        assertEquals(1, this.owner.forwardKicks.size(), "The engine must run the kick recovery");
        assertTrue(this.suppression.guardChannelDeath(this.p2s),
                "The death callback after a kick must still be swallowed (no propagation)");
        assertTrue(this.owner.forwardDeaths.isEmpty(), "The death must not trigger the backend-down policy next to the kick recovery");
        assertTrue(this.owner.failed.isEmpty(), "The death must not fail any job");
        assertNoOutbound(this.c2p);
    }

    @Test
    void lobbyTargetDeathNeverRecurses() {
        beginJob(true);
        this.driveToForward(); //a lobby target settles the suppression back to idle
        assertTrue(this.owner.completed.size() == 1);
        assertFalse(this.suppression.guardChannelDeath(this.p2s),
                "After the lobby switch completed, a dying lobby connection keeps ViaProxy behavior (no recovery recursion)");
        assertTrue(this.owner.forwardDeaths.isEmpty());
        assertTrue(this.owner.failed.isEmpty());
    }

    /**
     * Builds a C2S_COMMAND_SUGGESTION-shaped packet: varint transactionId + string text.
     */
    private static UnknownPacket suggestionRequest(final int transactionId, final String text) {
        final ByteBuf buf = Unpooled.buffer();
        PacketTypes.writeVarInt(buf, transactionId);
        PacketTypes.writeString(buf, text);
        return new UnknownPacket(MCPackets.C2S_COMMAND_SUGGESTION.getId(CLIENT_VERSION.getVersion()), ByteBufUtil.getBytes(buf));
    }

    /**
     * One decoded suggestion entry: the match text and its tooltip marker.
     */
    private record SuggestionEntry(String match, boolean hasTooltip) {
    }

    private static final class SuggestionsResponse {
        final int transactionId;
        final int start;
        final int length;
        final List<SuggestionEntry> entries;

        SuggestionsResponse(final int transactionId, final int start, final int length, final List<SuggestionEntry> entries) {
            this.transactionId = transactionId;
            this.start = start;
            this.length = length;
            this.entries = entries;
        }
    }

    private static SuggestionsResponse readSuggestionsResponse(final Packet packet) {
        assertTrue(packet instanceof UnknownPacket, "The synthesized response must be an UnknownPacket (no typed packet in NetMinecraft)");
        final UnknownPacket unknownPacket = (UnknownPacket) packet;
        final ByteBuf buf = Unpooled.wrappedBuffer(unknownPacket.data);
        final int transactionId = PacketTypes.readVarInt(buf);
        final int start = PacketTypes.readVarInt(buf);
        final int length = PacketTypes.readVarInt(buf);
        final int count = PacketTypes.readVarInt(buf);
        final List<SuggestionEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new SuggestionEntry(PacketTypes.readString(buf, 256), buf.readBoolean()));
        }
        assertEquals(0, buf.readableBytes(), "Every response byte must be consumed by the documented layout");
        return new SuggestionsResponse(transactionId, start, length, entries);
    }

    @Test
    void targetCommandTreeIncludesExitCommandsAndPreservesServerCommands() {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        beginJob(false).markForward();
        //Two nodes: root -> executable "help". Root index zero, as in vanilla trees.
        final UnknownPacket commands = new UnknownPacket(MCPackets.S2C_COMMANDS.getId(769),
                new byte[] {2, 0, 1, 1, 5, 0, 4, 'h', 'e', 'l', 'p', 0});
        assertTrue(this.suppression.handleP2S(commands, new ArrayList<>()),
                "The normal public host handler chain still forwards the command packet");
        assertEquals(List.of("help", "disconnect", "dc"),
                dev.connectplus.testutil.CommandTreeAssertions.executableRoots(commands.data));
        assertNoOutbound(this.c2p);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {4, 5, 47, 107, 340, 404, 578, 754, 758, 759, 762, 763})
    void legacyClientsKeepTheirInlineJoinConfiguration(final int version) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(version));
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        beginJob(false);
        assertFalse(this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>()));
        assertNoOutbound(this.c2p);
        assertNoOutbound(this.p2s);
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
        assertEquals(ConnectionState.PLAY, this.pc.getP2sConnectionState());
    }

    @Test
    void earlyFinishAndOldPlayTrafficCannotAcknowledgeConfiguration() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        beginJob(false);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        assertFalse(this.suppression.handleC2P(new C2SConfigFinishConfigurationPacket(), new ArrayList<>()));
        assertFalse(this.suppression.handleC2P(unknown(MCPackets.C2S_CHAT, "old chat"), new ArrayList<>()));
        assertNoOutbound(this.p2s);
        assertEquals(ConnectionState.CONFIGURATION, this.pc.getP2sConnectionState());
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void recoveryWaitsForOutstandingFrontendAcknowledgements(final boolean finishWasSent) {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        final SwitchJob failed = beginJob(false);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.c2p, S2CPlayStartConfigurationPacket.class);
        readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        if (finishWasSent) {
            this.suppression.handleC2P(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>());
            this.suppression.handleP2S(new S2CConfigFinishConfigurationPacket(), new ArrayList<>());
            readOutbound(this.c2p, S2CConfigFinishConfigurationPacket.class);
        }
        failed.fail("backend lost");
        beginJob(true);
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        final UnknownPacket registry = new UnknownPacket(MCPackets.S2C_CONFIG_REGISTRY_DATA.getId(769), new byte[]{1, 2, 3});
        this.suppression.handleP2S(registry, new ArrayList<>());
        registry.data[0] = 9;
        assertNoOutbound(this.c2p);
        if (finishWasSent) {
            assertFalse(this.suppression.handleC2P(new C2SConfigFinishConfigurationPacket(), new ArrayList<>()), "Old finish must never reach the recovery backend");
            readOutbound(this.c2p, S2CPlayStartConfigurationPacket.class);
            assertEquals(ConnectionState.CONFIGURATION, this.pc.getP2sConnectionState());
        }
        this.suppression.handleC2P(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>());
        final UnknownPacket delivered = assertInstanceOf(UnknownPacket.class, this.c2p.readOutbound());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 2, 3}, delivered.data, "Recovery data remains owned by its job");
        assertNoOutbound(this.p2s);
    }

    @Test
    void excessiveQueuedConfigurationFailsThroughTheExistingRecoveryCallback() {
        this.pc.setP2sConnectionState(ConnectionState.CONFIGURATION);
        final SwitchJob job = beginJob(false);
        final UnknownPacket registry = new UnknownPacket(MCPackets.S2C_CONFIG_REGISTRY_DATA.getId(769), new byte[]{1});
        for (int i = 0; i <= 4096; i++) this.suppression.handleP2S(registry, new ArrayList<>());
        assertFalse(job.isSwitching());
        assertEquals(1, this.owner.failed.size());
        assertTrue(job.failureReason().contains("buffer limit"));
        assertTrue(this.c2p.isActive());
    }

    @Test
    void rawFinishPausesBackendReadsAndWaitsForTheFrontend() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        beginJob(false);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.c2p, S2CPlayStartConfigurationPacket.class);
        readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        this.suppression.handleC2P(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>());
        this.suppression.handleP2S(new UnknownPacket(MCPackets.S2C_CONFIG_FINISH_CONFIGURATION.getId(769), new byte[0]), new ArrayList<>());
        readOutbound(this.c2p, S2CConfigFinishConfigurationPacket.class);
        assertFalse(this.p2s.config().isAutoRead());
        assertNoOutbound(this.p2s);
        assertEquals(ConnectionState.CONFIGURATION, this.pc.getP2sConnectionState());
    }

    @Test
    void frontendKeepAliveUsesTheCurrentConfigurationNamespace() {
        beginJob(false);
        this.pc.setC2pConnectionState(ConnectionState.CONFIGURATION);
        SwitchEngine.writeClientKeepAlive(this.pc);
        final var keep = assertInstanceOf(S2CConfigKeepAlivePacket.class, this.c2p.readOutbound());
        assertFalse(this.suppression.handleC2P(new C2SConfigKeepAlivePacket(keep.id), new ArrayList<>()));
        assertNoOutbound(this.p2s);
    }

    @Test
    void failedAttemptConsumesItsFinishAckBeforeRecoveryBegins() {
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        final SwitchJob job = beginJob(false);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.c2p, S2CPlayStartConfigurationPacket.class);
        readOutbound(this.p2s, C2SLoginAcknowledgedPacket.class);
        this.suppression.handleC2P(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>());
        this.suppression.handleP2S(new S2CConfigFinishConfigurationPacket(), new ArrayList<>());
        readOutbound(this.c2p, S2CConfigFinishConfigurationPacket.class);
        job.fail("failed reconnect waiting for next attempt");
        assertFalse(this.suppression.handleC2P(new C2SConfigFinishConfigurationPacket(), new ArrayList<>()));
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
        assertNoOutbound(this.p2s);
        beginJob(true);
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.c2p, S2CPlayStartConfigurationPacket.class);
    }

    @Test
    void normalLiveBackendReconfigurationKeepsItsNativeAcknowledgements() throws Exception {
        this.pc.setP2sConnectionState(ConnectionState.PLAY);
        beginJob(false).markForward();
        final var nativeHandler = new net.raphimc.viaproxy.proxy.packethandler.ConfigurationPacketHandler(this.pc);
        final var start = new S2CPlayStartConfigurationPacket();
        assertTrue(this.suppression.handleP2S(start, new ArrayList<>()));
        nativeHandler.handleP2S(start, new ArrayList<>());
        final var startAck = new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket();
        var listeners = new ArrayList<ChannelFutureListener>();
        assertTrue(this.suppression.handleC2P(startAck, listeners));
        nativeHandler.handleC2P(startAck, listeners);
        this.p2s.writeAndFlush(startAck).addListeners(listeners.toArray(new ChannelFutureListener[0]));
        assertEquals(ConnectionState.CONFIGURATION, this.pc.getP2sConnectionState());
        final var finish = new S2CConfigFinishConfigurationPacket();
        assertTrue(this.suppression.handleP2S(finish, new ArrayList<>()));
        nativeHandler.handleP2S(finish, new ArrayList<>());
        final var finishAck = new C2SConfigFinishConfigurationPacket();
        listeners = new ArrayList<>();
        assertTrue(this.suppression.handleC2P(finishAck, listeners));
        nativeHandler.handleC2P(finishAck, listeners);
        this.p2s.writeAndFlush(finishAck).addListeners(listeners.toArray(new ChannelFutureListener[0]));
        assertEquals(ConnectionState.PLAY, this.pc.getP2sConnectionState());
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
        assertTrue(this.p2s.config().isAutoRead());
    }

    @Test
    void liveBackendDeathKeepsAckOwnershipWhileNoJobIsInstalled() {
        final SwitchJob job = beginJob(false);
        job.markForward();
        this.suppression.handleP2S(new S2CPlayStartConfigurationPacket(), new ArrayList<>());
        this.suppression.handleC2P(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket(), new ArrayList<>());
        this.pc.setC2pConnectionState(ConnectionState.CONFIGURATION);
        this.suppression.handleP2S(new S2CConfigFinishConfigurationPacket(), new ArrayList<>());
        assertTrue(this.suppression.guardChannelDeath(this.p2s));
        assertNull(this.suppression.currentJob());
        assertFalse(this.suppression.handleC2P(new C2SConfigFinishConfigurationPacket(), new ArrayList<>()));
        assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
        assertFalse(this.c2p.config().isAutoRead(), "Recovery must retain frontend input until a replacement backend is open");
        beginJob(true);
        this.pc.setP2sConnectionState(ConnectionState.LOGIN);
        this.suppression.handleP2S(new S2CLoginGameProfilePacket(UUID.randomUUID(), "TestPlayer", new ArrayList<>()), new ArrayList<>());
        readOutbound(this.c2p, S2CPlayStartConfigurationPacket.class);
        assertTrue(this.c2p.config().isAutoRead());
    }

    @Test
    void oldLanguageSuggestionsCannotReplaceTheRegisteredExitCommands() throws Exception {
        final java.nio.file.Path data = java.nio.file.Files.createTempDirectory("cp-command-language");
        java.nio.file.Files.createDirectories(data.resolve("languages"));
        java.nio.file.Files.writeString(data.resolve("languages/en.yml"),
                "Commands:\n  Suggestions: '/connect\\n/cphelp'\n");
        final String language = dev.connectplus.config.CPConfig.language;
        try {
            dev.connectplus.config.CPConfig.language = "en";
            dev.connectplus.lobby.screen.Languages.init(data.toFile());
            assertFalse(this.suppression.handleC2P(suggestionRequest(6, "/d"), new ArrayList<>()));
            assertEquals(List.of("/disconnect", "/dc"),
                    readSuggestionsResponse(this.c2p.readOutbound()).entries.stream().map(SuggestionEntry::match).toList());
        } finally {
            dev.connectplus.config.CPConfig.language = language;
            dev.connectplus.lobby.screen.Languages.init(null);
            try (final var files = java.nio.file.Files.walk(data)) {
                for (final var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(file);
            }
        }
    }

    @Test
    void lobbyStateAnswersCommandSuggestions() {
        //idle (sitting in the lobby): the proxy answers tab completion itself
        assertFalse(this.suppression.handleC2P(suggestionRequest(9, "/d"), new ArrayList<>()),
                "The suggestion request must be consumed in the lobby");
        final SuggestionsResponse response = readSuggestionsResponse(this.c2p.readOutbound());
        assertEquals(9, response.transactionId);
        assertEquals(0, response.start, "The replaced range starts at the input start (including the slash)");
        assertEquals(2, response.length, "The replaced range covers the typed input '/d'");
        assertEquals(List.of("/disconnect", "/dc"), response.entries.stream().map(SuggestionEntry::match).toList(),
                "Only the two exit commands may be suggested");
        assertTrue(response.entries.stream().noneMatch(e -> e.hasTooltip()), "Each entry carries the no-tooltip marker");
        assertNoOutbound(this.p2s);
    }

    @Test
    void suggestionsFilterByTheTypedPrefix() {
        assertFalse(this.suppression.handleC2P(suggestionRequest(2, "/dis"), new ArrayList<>()),
                "The suggestion request must be consumed in the lobby");
        final SuggestionsResponse response = readSuggestionsResponse(this.c2p.readOutbound());
        assertEquals(List.of("/disconnect"), response.entries.stream().map(SuggestionEntry::match).toList(),
                "The prefix filter must keep only matching commands");
        assertEquals(4, response.length, "The replaced range covers '/dis'");
    }

    @Test
    void unmatchedPrefixAnswersWithAnEmptyResponse() {
        assertFalse(this.suppression.handleC2P(suggestionRequest(5, "/zz"), new ArrayList<>()),
                "Even without matches the request is consumed and answered");
        final SuggestionsResponse response = readSuggestionsResponse(this.c2p.readOutbound());
        assertTrue(response.entries.isEmpty(), "An unmatched input must produce an explicit empty suggestions response");
        assertEquals(3, response.length, "The replaced range still covers the typed input");
        assertNoOutbound(this.p2s);
    }

    @Test
    void targetStateForwardsSuggestions() {
        beginJob(false);
        this.driveToForward();
        assertTrue(this.suppression.handleC2P(suggestionRequest(1, "he"), new ArrayList<>()),
                "On a server target the backend's own tab completion passes through");
        assertNoOutbound(this.c2p);
    }

    @Test
    void lobbyTargetForwardAnswersSuggestions() {
        beginJob(true);
        this.driveToForward(); //a lobby target settles back to idle: lobby semantics again
        assertFalse(this.suppression.handleC2P(suggestionRequest(4, ""), new ArrayList<>()),
                "After returning to the lobby the proxy answers tab completion again");
        final SuggestionsResponse response = readSuggestionsResponse(this.c2p.readOutbound());
        assertEquals(List.of("/disconnect", "/dc"), response.entries.stream().map(SuggestionEntry::match).toList(),
                "An empty input lists exactly the two exit commands");
        assertEquals(0, response.length, "An empty input replaces nothing");
    }

}
