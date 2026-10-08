package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.compat.SwitchableProxyConnection;
import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.LobbyNotices;
import dev.connectplus.session.ConnectionInfo;
import dev.connectplus.session.PlayerSession;
import net.raphimc.viaproxy.ViaProxy;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.netminecraft.packet.impl.play.C2SPlayKeepAlivePacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end engine test without the ViaProxy runtime: two real lobby servers act as
 * origin and target, the proxy connection is a real (switchable) ProxyConnection over
 * real p2s channels, and the client side is an embedded channel driven by a harness
 * that mirrors Client2ProxyHandler's forwarding tail. Validates the whole switch flow:
 * teardown, reconnect, suppression, session migration and the failure fallback.
 */
class SwitchEngineIT {

    static {
        //Same ViaVersion static-init workaround as LobbyTestClient: the first GUI item
        //encode/decode triggers VersionedTypes.<clinit>, which deadlock-cycles with
        //StructuredDataKey.<clinit>; touching StructuredDataKey first resolves it.
        if (com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME == null) {
            throw new IllegalStateException("ViaVersion structured data keys not initialized");
        }
    }

    private static final ProtocolVersion CLIENT_VERSION = ProtocolVersion.v1_21_4;
    private static final String PLAYER_NAME = "SwitchPlayer";

    private LobbyServer origin;
    private LobbyServer target;
    private UUID playerUuid;

    private SwitchEngine engine;
    private SwitchableProxyConnection pc;
    private EmbeddedChannel c2p;
    private UUID originLink;
    private final java.util.concurrent.atomic.AtomicReference<SwitchSuppressionHandler> suppressionRef =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final List<Packet> clientReceived = new ArrayList<>();
    private int switchBaseline;

    /**
     * The test p2s initializer: the plain NetMinecraft pipeline (no Via translation,
     * the lobby speaks the client's 1.21.4 dialect directly here) with ViaProxy's
     * registry override — s2c packets decoded in the client's version, exactly what
     * Proxy2ServerChannelInitializer does in production. The Proxy2ServerHandler is
     * the guarded variant (session installer): a p2s death consults the suppression
     * handler so the engine's FORWARD-phase recovery can fire instead of the stock
     * cascade c2p close.
     */
    static final class TestP2sInitializer extends MinecraftChannelInitializer {
        private final java.util.function.Supplier<SwitchSuppressionHandler> suppression;

        TestP2sInitializer(final java.util.function.Supplier<SwitchSuppressionHandler> suppression) {
            super(() -> new net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler() {
                @Override
                public void channelInactive(final io.netty.channel.ChannelHandlerContext ctx) throws Exception {
                    final SwitchSuppressionHandler handler = suppression.get();
                    if (handler != null && handler.guardChannelDeath(ctx.channel())) {
                        return;
                    }
                    super.channelInactive(ctx);
                }

                @Override
                public void exceptionCaught(final io.netty.channel.ChannelHandlerContext ctx, final Throwable cause) {
                    final SwitchSuppressionHandler handler = suppression.get();
                    if (handler != null && handler.guardChannelDeath(ctx.channel())) {
                        return;
                    }
                    super.exceptionCaught(ctx, cause);
                }
            });
            this.suppression = suppression;
        }

        @Override
        protected void initChannel(final Channel channel) {
            super.initChannel(channel);
            channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, CLIENT_VERSION.getVersion()));
        }
    }

    private static final class TestProxyConnection extends SwitchableProxyConnection {
        private TestProxyConnection(final TestP2sInitializer initializer, final EmbeddedChannel c2p) {
            super(initializer, c2p);
        }
    }

    @TempDir
    static File dataDir;

    @BeforeAll
    static void boot() {
        ViaProxyTestConfig.init();
    }

    @BeforeEach
    void startLobbies() {
        //LobbyNotices/LobbyTransfers are global static maps: a prior test's leaked
        //entry (e.g. a fallback whose lobby join never happened) must not bleed into
        //this test
        LobbyNotices.consumeAll();
        dev.connectplus.lobby.LobbyTransfers.consumeAll();
        final TokenStore tokenStore = new TokenStore(dataDir);
        final PlayerStore playerStore = new PlayerStore(new File(dataDir, "players"));
        this.origin = new LobbyServer(new SessionRegistry(), tokenStore, playerStore, null);
        this.target = new LobbyServer(new SessionRegistry(), tokenStore, playerStore, null);
        this.origin.start();
        this.target.start();
        this.playerUuid = UUID.nameUUIDFromBytes(("SwitchEngineIT-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        if (this.c2p != null) {
            this.c2p.close();
        }
        if (this.originLink != null) {
            dev.connectplus.compat.LobbyLink.unregister(this.originLink);
            this.originLink = null;
        }
        if (this.origin != null) this.origin.stop();
        if (this.target != null) this.target.stop();
    }

    /**
     * Establishes the original connection of the player into the origin lobby: the
     * engine connects, the harness plays the client's login acknowledgements.
     */
    private SwitchableProxyConnection connectToOriginLobby() throws Exception {
        return this.connectToOriginLobby(false);
    }

    /**
     * The origin join, optionally PROTECTED: the c2p carries the verified identity and
     * the connection id, and the LobbyLink is registered before the login hello so the
     * lobby's load flow resolves the identity, claims the lease and keys the session
     * by the connection id (the §6 switch-cache lease validation needs all three).
     */
    private SwitchableProxyConnection connectToOriginLobby(final boolean protectedJoin) throws Exception {
        this.c2p = new EmbeddedChannel();
        this.c2p.attr(MCPipeline.COMPRESSION_THRESHOLD_ATTRIBUTE_KEY).set(256);
        if (protectedJoin) {
            this.c2p.attr(CPAttributeKeys.CONNECTION_ID).set(this.playerUuid);
            this.c2p.attr(CPAttributeKeys.PLAYER_IDENTITY).set(new dev.connectplus.session.PlayerIdentity(this.playerUuid, PLAYER_NAME));
            this.c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).set(dev.connectplus.identity.ClientIdentity.verifiedJava(
                    this.playerUuid, PLAYER_NAME, this.playerUuid));
        }
        final SwitchableProxyConnection pc = new TestProxyConnection(new TestP2sInitializer(() -> this.suppressionRef.get()), this.c2p);
        this.pc = pc; //the helpers below drive the connection
        pc.setClientVersion(CLIENT_VERSION);
        pc.setC2pConnectionState(ConnectionState.PLAY);
        pc.setLoginHelloPacket(new C2SLoginHelloPacket(PLAYER_NAME, null, null, null, this.playerUuid));
        this.engine = new SwitchEngine(() -> this.origin != null ? this.origin.localAddress() : null);
        final UUID linkId = UUID.randomUUID();
        LobbyLink.register(linkId, pc);
        this.originLink = linkId;
        final SwitchSuppressionHandler suppression = new SwitchSuppressionHandler(pc, this.engine);
        this.suppressionRef.set(suppression);
        pc.getPacketHandlers().add(suppression);
        //The real ViaProxy handler list, like the session installer + Client2ProxyHandler
        //assemble it in production (the suppression sits at index 0)
        pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.CompressionPacketHandler(pc));
        pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.LoginPacketHandler(pc));
        pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.DisconnectPacketHandler(pc));
        pc.getPacketHandlers().add(new net.raphimc.viaproxy.proxy.packethandler.ConfigurationPacketHandler(pc));

        final InetSocketAddress originAddress = (InetSocketAddress) origin.localAddress();
        final Channel p2s = pc.connectToServer(originAddress, LobbyProtocol.VERSION).syncUninterruptibly().channel();
        if (protectedJoin) {
            //The link id must be on the accepted channel BEFORE the hello so the
            //lobby's identity resolution (through the link) sees the c2p identity.
            //The server-side accept is asynchronous: wait for the channel first.
            Channel accepted = null;
            final long deadlineAccept = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (accepted == null && System.nanoTime() < deadlineAccept) {
                if (this.origin.acceptedChannels().size() == 1) {
                    accepted = this.origin.acceptedChannels().iterator().next();
                } else {
                    Thread.sleep(25);
                }
            }
            assertNotNull(accepted, "The origin lobby must accept the protected join");
            accepted.attr(CPAttributeKeys.LOBBY_LINK_ID).set(linkId);
        }
        p2s.writeAndFlush(new C2SHandshakingClientIntentionPacket(CLIENT_VERSION.getOriginalVersion(), originAddress.getHostString(), originAddress.getPort(), IntendedState.LOGIN));
        pc.setP2sConnectionState(ConnectionState.LOGIN);
        p2s.writeAndFlush(pc.getLoginHelloPacket());

        this.awaitClientPacket(S2CLoginGameProfilePacket.class, 1);
        this.driveToP2s(new C2SLoginAcknowledgedPacket());
        this.awaitClientPacket(S2CConfigFinishConfigurationPacket.class, 1);
        this.driveToP2s(new C2SConfigFinishConfigurationPacket());
        this.awaitServerSession(origin.sessionRegistry(), "original join");
        //Wait until the origin lobby's own JoinGame reached the client, then freeze the
        //baseline: JoinGame assertions during a switch must only scan packets that
        //arrived after the switch started.
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean sawOriginJoinGame = false;
        while (System.nanoTime() < deadline && !sawOriginJoinGame) {
            this.drainClientOutbound();
            for (final Packet packet : this.clientReceived) {
                if (packet instanceof UnknownPacket unknownPacket
                        && unknownPacket.packetId == MCPackets.S2C_LOGIN.getId(CLIENT_VERSION.getVersion())) {
                    sawOriginJoinGame = true;
                }
            }
            Thread.sleep(25);
        }
        assertTrue(sawOriginJoinGame, "The origin lobby's JoinGame must reach the client during the original join");
        this.drainClientOutbound();
        this.switchBaseline = this.clientReceived.size();
        return pc;
    }

    /**
     * Mirrors Client2ProxyHandler.channelRead0's tail: run the packet handlers and, if
     * none consumed the packet, write it to the current p2s channel.
     */
    private void driveToP2s(final Packet packet) {
        final List<io.netty.channel.ChannelFutureListener> listeners = new ArrayList<>(List.of(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));
        for (final net.raphimc.viaproxy.proxy.packethandler.PacketHandler handler : this.pc.getPacketHandlers()) {
            try {
                if (!handler.handleC2P(packet, listeners)) {
                    return;
                }
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
        //Client2ProxyHandler attaches the handler-added listeners to the final write
        //(the p2s state advance hangs on them)
        this.pc.getChannel().writeAndFlush(packet).addListeners(listeners.toArray(new io.netty.channel.ChannelFutureListener[0]));
    }

    /**
     * Drives a server->proxy packet through the suppression handler exactly like
     * Proxy2ServerHandler.channelRead0 does (inbound direction: the writeAndFlush
     * calls in tests go outbound and would never reach the handler chain).
     */
    private void driveFromServer(final Packet packet) throws Exception {
        final List<io.netty.channel.ChannelFutureListener> listeners = new ArrayList<>(List.of(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));
        for (final net.raphimc.viaproxy.proxy.packethandler.PacketHandler handler : this.pc.getPacketHandlers()) {
            if (!handler.handleP2S(packet, listeners)) {
                return;
            }
        }
    }

    private <T extends Packet> T awaitClientPacket(final Class<T> type, final int occurrence) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int seen = 0;
            for (final Packet packet : this.clientReceived) {
                if (type.isInstance(packet) && ++seen == occurrence) {
                    return (T) packet;
                }
            }
            this.drainClientOutbound();
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + type.getSimpleName() + " on the client, got: " + this.clientReceived);
    }

    /**
     * Drains what the proxy wrote to the (embedded) client so far.
     */
    private void drainClientOutbound() {
        //Run the embedded loop's queued tasks: the engine submits its switch steps
        //onto the c2p event loop from foreign (nio) threads
        this.c2p.runPendingTasks();
        Object message;
        while ((message = this.c2p.readOutbound()) != null) {
            if (message instanceof Packet packet) {
                this.clientReceived.add(packet);
                if (packet instanceof net.raphimc.netminecraft.packet.impl.play.S2CPlayStartConfigurationPacket) {
                    this.driveToP2s(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket());
                } else if (packet instanceof S2CConfigFinishConfigurationPacket
                        && this.suppressionRef.get().currentJob() != null
                        && this.suppressionRef.get().currentJob().isSwitching()) {
                    this.driveToP2s(new C2SConfigFinishConfigurationPacket());
                }
            }
        }
    }

    private void pump() {
        this.drainClientOutbound();
    }

    @Test
    @Timeout(20)
    void configurationBackendWaitsForPongBeforeAllowingTheSwitchToComplete() throws Exception {
        this.pc = this.connectToOriginLobby();
        final byte[] ping = {0x12, 0x34, 0x56, 0x78};
        final java.util.concurrent.atomic.AtomicBoolean pongReceived = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Throwable> backendError = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.Set<Channel> backendChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final net.raphimc.netminecraft.netty.connection.NetServer backend = new net.raphimc.netminecraft.netty.connection.NetServer(
                new MinecraftChannelInitializer(() -> new io.netty.channel.SimpleChannelInboundHandler<Packet>() {
                    @Override
                    protected void channelRead0(final io.netty.channel.ChannelHandlerContext ctx, final Packet packet) {
                        final var registry = ctx.channel().attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get();
                        if (packet instanceof C2SHandshakingClientIntentionPacket) {
                            registry.setConnectionState(ConnectionState.LOGIN);
                        } else if (packet instanceof C2SLoginHelloPacket hello) {
                            ctx.writeAndFlush(new S2CLoginGameProfilePacket(hello.uuid, hello.name, new ArrayList<>()));
                        } else if (packet instanceof C2SLoginAcknowledgedPacket) {
                            registry.setConnectionState(ConnectionState.CONFIGURATION);
                            //Like Velocity: no configuration finish until the int is echoed.
                            ctx.writeAndFlush(new UnknownPacket(5, ping));
                        } else if (packet instanceof UnknownPacket pong && pong.packetId == 5) {
                            org.junit.jupiter.api.Assertions.assertArrayEquals(ping, pong.data);
                            pongReceived.set(true);
                            ctx.writeAndFlush(new S2CConfigFinishConfigurationPacket());
                        } else if (packet instanceof C2SConfigFinishConfigurationPacket) {
                            assertTrue(pongReceived.get());
                            registry.setConnectionState(ConnectionState.PLAY);
                            ctx.writeAndFlush(dev.connectplus.testutil.ModernWorldPackets.join(769));
                            ctx.writeAndFlush(new UnknownPacket(MCPackets.S2C_PLAYER_ABILITIES.getId(769),
                                    java.nio.ByteBuffer.allocate(9).put((byte) 0).putFloat(0.05F).putFloat(0.1F).array()));
                        }
                    }

                    @Override
                    public void exceptionCaught(final io.netty.channel.ChannelHandlerContext ctx, final Throwable cause) {
                        backendError.set(cause);
                        ctx.close();
                    }
                }) {
                    @Override
                    protected void initChannel(final Channel channel) {
                        super.initChannel(channel);
                        backendChannels.add(channel);
                        channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(false, 769));
                    }
                });
        try {
            backend.bind(new InetSocketAddress("127.0.0.1", 0), false);
            final InetSocketAddress address = (InetSocketAddress) backend.getChannel().localAddress();
            final var target = new ConnectionInfo(address.getHostString() + ":" + address.getPort(), CLIENT_VERSION, this.playerUuid);
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc, target, null, PLAYER_NAME));
            this.awaitJoinGameOnClient();
            assertTrue(pongReceived.get(), "The backend must receive its pong over the real socket");
            org.junit.jupiter.api.Assertions.assertNull(backendError.get());
            assertEquals(ConnectionState.PLAY, this.pc.getP2sConnectionState());
            assertEquals(ConnectionState.PLAY, this.pc.getC2pConnectionState());
            assertTrue(this.pc.getC2P().isActive());
            final long abilitiesDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            UnknownPacket abilities = null;
            while (System.nanoTime() < abilitiesDeadline && abilities == null) {
                this.drainClientOutbound();
                for (int i = this.switchBaseline; i < this.clientReceived.size(); i++) {
                    if (this.clientReceived.get(i) instanceof UnknownPacket raw
                            && raw.packetId == MCPackets.S2C_PLAYER_ABILITIES.getId(769)) {
                        assertTrue(i >= this.switchBaseline + 2);
                        assertEquals(MCPackets.S2C_RESPAWN.getId(769), ((UnknownPacket) this.clientReceived.get(i - 1)).packetId,
                                "World reset must precede the backend's normal movement abilities");
                        abilities = raw;
                    }
                }
                if (abilities == null) Thread.sleep(10);
            }
            assertNotNull(abilities);
            assertEquals(0.1F, java.nio.ByteBuffer.wrap(abilities.data).getFloat(5));
            assertTrue(this.suppressionRef.get().currentJob().target().address().endsWith(":" + address.getPort()),
                    "The switch must finish on the target, rather than falling back to the lobby");
            for (int i = this.switchBaseline; i < this.clientReceived.size(); i++) {
                assertFalse(this.clientReceived.get(i) instanceof S2CLoginGameProfilePacket,
                        "A hot switch must not repeat frontend login");
            }
            assertTrue(this.clientReceived.subList(this.switchBaseline, this.clientReceived.size()).stream()
                    .anyMatch(p -> p instanceof S2CConfigFinishConfigurationPacket),
                    "Target configuration must reach the frontend before play");
        } finally {
            for (final Channel channel : backendChannels) channel.close().syncUninterruptibly();
            if (backend.getChannel() != null) backend.getChannel().close().syncUninterruptibly();
        }
    }

    private void awaitServerSession(final SessionRegistry registry, final String what) throws InterruptedException {
        this.awaitServerSession(registry, this.playerUuid, what);
    }

    private void awaitServerSession(final SessionRegistry registry, final UUID expectedUuid, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            this.pump();
            final SwitchSuppressionHandler handler = this.suppressionRef.get();
            final SwitchJob job = handler == null ? null : handler.currentJob();
            if (registry.size() == 1 && registry.get(expectedUuid) != null && (job == null || !job.isSwitching())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for the session to be registered (" + what + ")");
    }

    private void awaitEmptyRegistry(final SessionRegistry registry, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            this.pump();
            if (registry.size() == 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for the registry to clear (" + what + ")");
    }

    private UnknownPacket awaitJoinGameOnClient() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            this.drainClientOutbound();
            for (int i = this.switchBaseline; i < this.clientReceived.size(); i++) {
                final Packet packet = this.clientReceived.get(i);
                if (packet instanceof UnknownPacket unknownPacket
                        && unknownPacket.packetId == MCPackets.S2C_LOGIN.getId(CLIENT_VERSION.getVersion())) {
                    if (i + 1 >= this.clientReceived.size()) continue; //reset write may still be queued on c2p
                    final Packet following = this.clientReceived.get(i + 1);
                    assertTrue(following instanceof UnknownPacket);
                    final UnknownPacket reset = (UnknownPacket) following;
                    assertEquals(MCPackets.S2C_RESPAWN.getId(CLIENT_VERSION.getVersion()), reset.packetId,
                            "Every hot join must reset the player's world before forwarding further traffic");
                    assertEquals(0, reset.data[reset.data.length - 1], "Old entity/attribute data must not survive the switch");
                    return unknownPacket;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for the translated JoinGame on the client");
    }

    @Test
    @Timeout(30)
    void hotSwitchRoundTripBetweenTwoLobbies() throws Exception {
        this.pc = this.connectToOriginLobby();
        assertTrue(this.pc.getC2P().isActive());

        //Lobby -> "server": the switch is driven through the public engine API
        final SwitchSuppressionHandler suppression = this.suppressionOf(this.pc);
        assertNotNull(suppression);
        final InetSocketAddress targetAddress = (InetSocketAddress) target.localAddress();
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc, new ConnectionInfo(
                targetAddress.getHostString() + ":" + targetAddress.getPort(), LobbyProtocol.VERSION, this.playerUuid), null, PLAYER_NAME));

        final UnknownPacket joinGame = this.awaitJoinGameOnClient();
        assertNotNull(joinGame, "The translated JoinGame of the new server must be the first packet the client sees again");
        this.awaitServerSession(target.sessionRegistry(), "switch to target");
        this.awaitEmptyRegistry(origin.sessionRegistry(), "old p2s teardown");
        assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the switch");
        assertTrue(this.pc.getChannel() != null && this.pc.getChannel().isActive(), "The new p2s must be the live connection");
        assertEquals(target.localAddress(), this.pc.getChannel().remoteAddress(), "The p2s must now be connected to the target");
        assertNotNull(suppression.currentJob(), "The FORWARD job stays installed on a server target for command interception");
        assertFalse(suppression.currentJob().isSwitching());

        //Client keepalive echo arrives during FORWARD: it is forwarded to the target
        final int before = this.clientReceived.size();
        this.driveToP2s(new C2SPlayKeepAlivePacket(123));
        this.drainClientOutbound();
        assertEquals(before, this.clientReceived.size(), "FORWARD state must not produce client-side packets by itself");

        //"Server" -> lobby: the on-target /disconnect path
        this.drainClientOutbound();
        this.switchBaseline = this.clientReceived.size();
        assertTrue(this.engine.switchToLobby(this.pc, null, this.playerUuid, PLAYER_NAME));
        this.awaitJoinGameOnClient();
        this.awaitServerSession(origin.sessionRegistry(), "switch back to the lobby");
        this.awaitEmptyRegistry(target.sessionRegistry(), "target teardown");
        assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the round trip");
        assertEquals(origin.localAddress(), this.pc.getChannel().remoteAddress(), "The p2s must be back at the lobby");
        org.junit.jupiter.api.Assertions.assertNull(suppression.currentJob(), "A lobby switch settles the suppression back to idle");
        final long commandsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        UnknownPacket commands = null;
        while (commands == null && System.nanoTime() < commandsDeadline) {
            this.drainClientOutbound();
            commands = this.clientReceived.subList(this.switchBaseline, this.clientReceived.size()).stream()
                    .filter(packet -> packet instanceof UnknownPacket raw && raw.packetId == MCPackets.S2C_COMMANDS.getId(769))
                    .map(packet -> (UnknownPacket) packet).findFirst().orElse(null);
            if (commands == null) Thread.sleep(25);
        }
        assertNotNull(commands, "Returning to the lobby must replace the target command tree");
        assertEquals(List.of("disconnect", "dc"),
                dev.connectplus.testutil.CommandTreeAssertions.executableRoots(commands.data));
    }

    @Test
    @Timeout(45)
    void disconnectAndImmediatelyRejoinTheSameServerRepeatedly() throws Exception {
        this.pc = this.connectToOriginLobby();
        final InetSocketAddress address = (InetSocketAddress) this.target.localAddress();
        Channel previousBackend = this.pc.getChannel();
        for (int attempt = 0; attempt < 3; attempt++) {
            this.drainClientOutbound();
            this.switchBaseline = this.clientReceived.size();
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo(address.getHostString() + ":" + address.getPort(), CLIENT_VERSION, this.playerUuid), null, PLAYER_NAME));
            this.awaitJoinGameOnClient();
            this.awaitServerSession(this.target.sessionRegistry(), "same-server join " + attempt);
            this.awaitEmptyRegistry(this.origin.sessionRegistry(), "lobby exit " + attempt);
            assertNotSame(previousBackend, this.pc.getChannel());
            assertFalse(previousBackend.isActive());
            assertEquals(this.target.localAddress(), this.pc.getChannel().remoteAddress());
            assertTrue(this.pc.getC2P().isActive());
            previousBackend = this.pc.getChannel();
            this.drainClientOutbound();
            this.switchBaseline = this.clientReceived.size();
            this.driveToP2s(new UnknownPacket(MCPackets.C2S_CHAT_COMMAND.getId(769),
                    new byte[]{10, 'd', 'i', 's', 'c', 'o', 'n', 'n', 'e', 'c', 't'}));
            this.awaitJoinGameOnClient();
            this.awaitServerSession(this.origin.sessionRegistry(), "command return " + attempt);
            // Reconnect as soon as the lobby arrives; deliberately do not wait
            // for the remote server's session cleanup before the next attempt.
            assertNull(this.suppressionOf(this.pc).currentJob());
            assertFalse(previousBackend.isActive());
            previousBackend = this.pc.getChannel();
        }
    }

    @Test
    @Timeout(30)
    void disconnectCommandReturnsToAMenuThatCanReopenAfterAbnormalMovement() throws Exception {
        this.pc = this.connectToOriginLobby();
        this.switchToTarget(this.target);
        this.awaitJoinGameOnClient();
        this.awaitRawPacket(MCPackets.S2C_OPEN_SCREEN, this.switchBaseline);
        this.drainClientOutbound();
        this.switchBaseline = this.clientReceived.size();
        this.driveToP2s(new UnknownPacket(MCPackets.C2S_CHAT_COMMAND.getId(769),
                new byte[]{10, 'd', 'i', 's', 'c', 'o', 'n', 'n', 'e', 'c', 't'}));
        this.awaitJoinGameOnClient();
        final var returnedMenu = this.awaitRawPacket(MCPackets.S2C_OPEN_SCREEN, this.switchBaseline);
        final var bytes = io.netty.buffer.Unpooled.wrappedBuffer(returnedMenu.data);
        final int returnedWindow;
        try { returnedWindow = net.raphimc.netminecraft.packet.PacketTypes.readVarInt(bytes); }
        finally { bytes.release(); }
        this.driveToP2s(new UnknownPacket(MCPackets.C2S_CONTAINER_CLOSE.getId(769), new byte[]{(byte) returnedWindow}));
        this.drainClientOutbound();
        final int correctionBaseline = this.clientReceived.size();
        this.driveToP2s(new UnknownPacket(MCPackets.C2S_MOVE_PLAYER_POS.getId(769),
                java.nio.ByteBuffer.allocate(25).putDouble(24).putDouble(-12.5).putDouble(24).put((byte) 0).array()));
        final var correction = this.awaitRawPacket(MCPackets.S2C_PLAYER_POSITION, correctionBaseline);
        final var decoded = new dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket();
        final var positionBytes = io.netty.buffer.Unpooled.wrappedBuffer(correction.data);
        try {
            decoded.read(positionBytes, 769);
            assertEquals(0, positionBytes.readableBytes());
        } finally { positionBytes.release(); }
        assertEquals(dev.connectplus.lobby.LobbyConstants.SPAWN_Y, decoded.posY);
        assertEquals(0, decoded.velocityY);
        this.drainClientOutbound();
        final int reopenBaseline = this.clientReceived.size();
        assertTrue(this.clientReceived.subList(correctionBaseline, reopenBaseline).stream().noneMatch(
                p -> p instanceof UnknownPacket raw && raw.packetId == MCPackets.S2C_OPEN_SCREEN.getId(769)),
                "Movement correction must preserve a deliberate menu close");
        this.driveToP2s(new UnknownPacket(MCPackets.C2S_USE_ITEM.getId(769), new byte[10]));
        final var reopened = this.awaitRawPacket(MCPackets.S2C_OPEN_SCREEN, reopenBaseline);
        assertFalse(java.util.Arrays.equals(returnedMenu.data, reopened.data), "The reopened menu must get a fresh window ID");
        assertTrue(this.pc.getC2P().isActive());
        assertEquals(this.origin.localAddress(), this.pc.getChannel().remoteAddress());
    }

    private UnknownPacket awaitRawPacket(final MCPackets type, final int baseline) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            this.drainClientOutbound();
            for (int i = baseline; i < this.clientReceived.size(); i++) {
                if (this.clientReceived.get(i) instanceof UnknownPacket raw && raw.packetId == type.getId(769)) return raw;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + type + " after the lobby return");
    }

    @Test
    @Timeout(30)
    void socketSwitchRemovesOldTabUuidsAndRetainsTheNextServersSameNameProfile() throws Exception {
        this.pc = this.connectToOriginLobby();
        UUID previous = UUID.randomUUID(), next = UUID.randomUUID();
        this.acceptedOriginChannel().writeAndFlush(dev.connectplus.testutil.PlayerListPackets.add(769, previous)).syncUninterruptibly();
        this.awaitRawPacket(MCPackets.S2C_PLAYER_INFO_UPDATE, 0);
        this.switchBaseline = this.clientReceived.size();
        this.switchToTarget(this.target);
        this.awaitJoinGameOnClient();
        var oldRemove = this.awaitRawPacket(MCPackets.S2C_PLAYER_INFO_REMOVE, this.switchBaseline);
        assertRemovedTabProfile(oldRemove, previous);
        assertTrue(this.clientReceived.indexOf(oldRemove) < this.clientReceived.indexOf(this.awaitJoinGameOnClient()));
        this.target.acceptedChannels().iterator().next().writeAndFlush(dev.connectplus.testutil.PlayerListPackets.add(769, next)).syncUninterruptibly();
        this.awaitRawPacket(MCPackets.S2C_PLAYER_INFO_UPDATE, this.switchBaseline);
        this.drainClientOutbound();
        this.switchBaseline = this.clientReceived.size();
        assertTrue(this.engine.switchToLobby(this.pc, null, this.playerUuid, PLAYER_NAME));
        this.awaitJoinGameOnClient();
        var nextRemove = this.awaitRawPacket(MCPackets.S2C_PLAYER_INFO_REMOVE, this.switchBaseline);
        assertRemovedTabProfile(nextRemove, next);
        assertTrue(this.pc.getC2P().isActive());
    }

    private static void assertRemovedTabProfile(UnknownPacket packet, UUID expected) {
        var bytes = io.netty.buffer.Unpooled.wrappedBuffer(packet.data);
        try {
            assertEquals(1, net.raphimc.netminecraft.packet.PacketTypes.readVarInt(bytes));
            assertEquals(expected, new UUID(bytes.readLong(), bytes.readLong()));
            assertEquals(0, bytes.readableBytes());
        } finally { bytes.release(); }
    }

    @Test
    @Timeout(45)
    void repeatedSameServerVisitsRemoveHpBeforeTheNextVisitCreatesIt() throws Exception {
        this.pc = this.connectToOriginLobby();
        final InetSocketAddress address = (InetSocketAddress) this.target.localAddress();
        for (int visit = 0; visit < 3; visit++) {
            this.drainClientOutbound();
            this.switchBaseline = this.clientReceived.size();
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo(address.getHostString() + ":" + address.getPort(), CLIENT_VERSION, this.playerUuid), null, PLAYER_NAME));
            this.awaitJoinGameOnClient();
            this.awaitServerSession(this.target.sessionRegistry(), "A visit " + visit);
            this.target.acceptedChannels().iterator().next().writeAndFlush(dev.connectplus.testutil.ScoreboardPackets.objective(769, "HP", 0)).syncUninterruptibly();
            this.awaitRawPacket(MCPackets.S2C_SET_OBJECTIVE, this.switchBaseline);
            this.drainClientOutbound();
            this.switchBaseline = this.clientReceived.size();
            this.driveToP2s(new UnknownPacket(MCPackets.C2S_CHAT_COMMAND.getId(769),
                    new byte[]{10, 'd', 'i', 's', 'c', 'o', 'n', 'n', 'e', 'c', 't'}));
            var lobbyJoin = this.awaitJoinGameOnClient();
            var removed = this.awaitRawPacket(MCPackets.S2C_SET_OBJECTIVE, this.switchBaseline);
            var bytes = io.netty.buffer.Unpooled.wrappedBuffer(removed.data);
            try {
                assertEquals("HP", net.raphimc.netminecraft.packet.PacketTypes.readString(bytes, 16));
                assertEquals(1, bytes.readUnsignedByte());
                assertEquals(0, bytes.readableBytes());
            } finally { bytes.release(); }
            assertTrue(this.clientReceived.indexOf(removed) < this.clientReceived.indexOf(lobbyJoin), "Remove HP before the lobby/new backend can create another HP");
            this.awaitServerSession(this.origin.sessionRegistry(), "lobby return " + visit);
            assertTrue(this.pc.getC2P().isActive());
        }
    }

    /**
     * The accepted origin-lobby channel of the current connection; the bridging tests
     * store the link id on it exactly like HAProxyHandler does in production.
     */
    private Channel acceptedOriginChannel() {
        assertEquals(1, this.origin.acceptedChannels().size(), "Exactly one accepted lobby channel after the join");
        return this.origin.acceptedChannels().iterator().next();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Timeout(30)
    void automaticTargetsResolveARealProtocolBeforeConnecting(boolean selectedAutoEntry) throws Exception {
        this.pc = this.connectToOriginLobby();
        final InetSocketAddress targetAddress = (InetSocketAddress) this.target.localAddress();
        final ProtocolVersion configured = selectedAutoEntry
                ? net.raphimc.viaproxy.protocoltranslator.ProtocolTranslator.AUTO_DETECT_PROTOCOL : null;
        this.drainClientOutbound();
        this.switchBaseline = this.clientReceived.size();
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                new ConnectionInfo(targetAddress.getHostString() + ":" + targetAddress.getPort(), configured, this.playerUuid),
                null, PLAYER_NAME));
        this.awaitJoinGameOnClient();
        this.awaitServerSession(this.target.sessionRegistry(), "auto-detected target join");
        assertEquals(CLIENT_VERSION, this.pc.getServerVersion(), "Neither null nor the Auto Detect placeholder may reach the backend as its protocol");
        assertTrue(this.pc.getC2P().isActive());
    }

    @Test
    @Timeout(30)
    void switchStartsThroughLobbyLinkBridging() throws Exception {
        this.pc = this.connectToOriginLobby();

        //The LobbyLink handshake: the p2s side (LobbyHaProxy) registers its link id and
        //the lobby's accepted channel (HAProxyHandler) stores it as a channel attribute.
        //The engine's lobby entry point must resolve the proxy connection through it —
        //the former remote-address pairing broke against the HAProxy real-client-address
        //rewrite of the accepted channel's remoteAddress (manual verification V2 finding).
        final UUID linkId = UUID.randomUUID();
        LobbyLink.register(linkId, this.pc);
        final Channel accepted = this.acceptedOriginChannel();
        accepted.attr(CPAttributeKeys.LOBBY_LINK_ID).set(linkId);
        try {
            final InetSocketAddress targetAddress = (InetSocketAddress) this.target.localAddress();
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitchFromLobby(accepted,
                    new ConnectionInfo(targetAddress.getHostString() + ":" + targetAddress.getPort(), LobbyProtocol.VERSION, this.playerUuid),
                    null, PLAYER_NAME));
        } finally {
            LobbyLink.unregister(linkId);
        }

        this.awaitJoinGameOnClient();
        this.awaitServerSession(this.target.sessionRegistry(), "bridged switch to target");
        assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the bridged switch");
    }

    @Test
    @Timeout(30)
    void bareLobbyConnectionCannotStartSwitch() throws Exception {
        this.pc = this.connectToOriginLobby();
        final Channel accepted = this.acceptedOriginChannel();

        //No link id attribute (bare lobby connection, e.g. from a test client): no switch
        assertEquals(SwitchInitiator.StartResult.NOT_AVAILABLE, this.engine.startSwitchFromLobby(accepted,
                new ConnectionInfo("127.0.0.1:1", LobbyProtocol.VERSION, this.playerUuid), null, PLAYER_NAME));
        assertTrue(this.pc.getC2P().isActive(), "A failed resolution must not touch the connection");

        //An id whose p2s side is already gone resolves to nothing either
        accepted.attr(CPAttributeKeys.LOBBY_LINK_ID).set(UUID.randomUUID());
        assertEquals(SwitchInitiator.StartResult.NOT_AVAILABLE, this.engine.startSwitchFromLobby(accepted,
                new ConnectionInfo("127.0.0.1:1", LobbyProtocol.VERSION, this.playerUuid), null, PLAYER_NAME));
        assertTrue(this.pc.getC2P().isActive(), "A stale link id must not touch the connection");
    }

    @Test
    @Timeout(30)
    void switchFailureKeepsClientConnectedAndFallsBackToLobby() throws Exception {
        this.pc = this.connectToOriginLobby();

        //A guaranteed-closed port: bind, capture, close
        final int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        final SwitchSuppressionHandler suppression = this.suppressionOf(this.pc);
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc, new ConnectionInfo("127.0.0.1:" + deadPort, LobbyProtocol.VERSION, this.playerUuid), null, PLAYER_NAME));

        //The failed switch must fall back into the lobby with the failure notice
        this.awaitServerSession(origin.sessionRegistry(), "fallback to the lobby");
        assertTrue(this.pc.getC2P().isActive(), "A failed switch must never close the client connection (F3.3)");
        assertFalse(suppression.currentJob() != null && suppression.currentJob().isSwitching(), "No switch may be left running");
        assertEquals(0, LobbyNotices.size(), "The failure notice must have been consumed by the lobby session");
    }

    @Test
    @Timeout(30)
    void inFlightTargetCountTracksOnlyActiveServerTargetJobs() throws Exception {
        this.pc = this.connectToOriginLobby();
        this.awaitServerSession(this.origin.sessionRegistry(), "initial join");
        assertEquals(0, this.engine.inFlightTargetCount(), "No job: nothing in flight");

        final InetSocketAddress address = (InetSocketAddress) this.target.localAddress();
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                new ConnectionInfo(address.getHostString() + ":" + address.getPort(), CLIENT_VERSION, this.playerUuid), null, PLAYER_NAME));
        assertEquals(1, this.engine.inFlightTargetCount(), "The launched server-target job is in flight");
        this.awaitServerSession(this.target.sessionRegistry(), "switch to target");
        this.awaitEmptyRegistry(this.origin.sessionRegistry(), "old p2s teardown");
        assertEquals(0, this.engine.inFlightTargetCount(), "A completed switch is no longer in flight");

        //Return to the lobby through the exit command: a lobby-target job never counts
        this.driveToP2s(new UnknownPacket(MCPackets.C2S_CHAT_COMMAND.getId(769),
                new byte[]{10, 'd', 'i', 's', 'c', 'o', 'n', 'n', 'e', 'c', 't'}));
        this.awaitServerSession(this.origin.sessionRegistry(), "command return");
        assertEquals(0, this.engine.inFlightTargetCount(), "A lobby return is not an in-flight target job");
    }

    @Test
    @Timeout(30)
    void inFlightTargetCountRemainsVisibleWhileBackendLoginIsPending() throws Exception {
        this.pc = this.connectToOriginLobby();
        this.awaitServerSession(this.origin.sessionRegistry(), "initial join");

        try (ServerSocket backend = new ServerSocket(0)) {
            backend.setSoTimeout(5000);
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo("127.0.0.1:" + backend.getLocalPort(), CLIENT_VERSION, this.playerUuid), null, PLAYER_NAME));
            this.pump();
            try (java.net.Socket connection = backend.accept()) {
                connection.setSoTimeout(5000);
                assertTrue(connection.getInputStream().read() >= 0, "The backend receives the real login handshake");
                this.pump();
                assertTrue(this.suppressionRef.get().currentJob().isSwitching(), "Login is waiting for the backend's response");
                assertEquals(1, this.engine.inFlightTargetCount(), "Installing watchdogs must keep the active target visible");
                //End the client before closing the stalled backend, so teardown does not start recovery.
                this.c2p.close();
            }
        }
    }

    @Test
    @Timeout(30)
    void inFlightTargetCountDropsToZeroWhenAJobFails() throws Exception {
        this.pc = this.connectToOriginLobby();
        this.awaitServerSession(this.origin.sessionRegistry(), "initial join");

        //A guaranteed-closed port: bind, capture, close
        final int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                new ConnectionInfo("127.0.0.1:" + deadPort, CLIENT_VERSION, this.playerUuid), null, PLAYER_NAME));
        assertEquals(1, this.engine.inFlightTargetCount(), "A connecting job is in flight");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.engine.inFlightTargetCount() != 0 && System.nanoTime() < deadline) {
            this.pump();
            Thread.sleep(25);
        }
        assertEquals(0, this.engine.inFlightTargetCount(), "A failed switch is no longer in flight");
        assertTrue(this.pc.getC2P().isActive(), "The failure fallback keeps the player connected");
    }

    @Test
    @Timeout(30)
    void waitingForARetryReconnectIsNotInFlight() throws Exception {
        final String oldDown = CPConfig.backendDownPolicy;
        final int oldDelay = CPConfig.reconnectDelaySeconds;
        CPConfig.backendDownPolicy = "reconnect";
        CPConfig.reconnectDelaySeconds = 3600;
        try {
            this.pc = this.connectToOriginLobby();
            this.switchToTarget(this.target);
            assertEquals(0, this.engine.inFlightTargetCount(), "The completed switch is not in flight");
            //The backend dies while forwarding: the reconnect policy schedules a retry
            this.pc.getChannel().close();
            this.pump();
            assertEquals(0, this.engine.inFlightTargetCount(),
                    "A connection only waiting for the scheduled reconnect is not in flight");
            assertTrue(this.pc.getC2P().isActive(), "The reconnect wait keeps the player connected");
        } finally {
            CPConfig.backendDownPolicy = oldDown;
            CPConfig.reconnectDelaySeconds = oldDelay;
        }
    }

    @Test
    @Timeout(30)
    void failedTargetSwitchDeliversTheFailureNoticeThroughTheLobby() throws Exception {
        this.pc = this.connectToOriginLobby();

        final int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        LobbyNotices.postRaw(this.playerUuid, List.of("§cSwitching to 127.0.0.1:" + deadPort + " failed: test"));

        //The next lobby join (any session load) consumes and shows the notice
        this.engine.switchToLobby(this.pc, null, this.playerUuid, PLAYER_NAME);
        this.awaitServerSession(origin.sessionRegistry(), "rejoin for notice");
        boolean sawNotice = false;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !sawNotice) {
            this.drainClientOutbound();
            for (final Packet packet : this.clientReceived) {
                if (packet instanceof UnknownPacket unknownPacket && unknownPacket.data != null
                        && new String(unknownPacket.data, StandardCharsets.UTF_8).contains("failed: test")) {
                    sawNotice = true;
                }
            }
            Thread.sleep(25);
        }
        assertTrue(sawNotice, "The notice must reach the client through the lobby's translated chat");
        assertEquals(0, LobbyNotices.size());
    }

    @Test
    @Timeout(30)
    void rateLimiterRejectsBurstAndKeysByUuid() throws Exception {
        //Review Focus 4 (F8.1/F8.2): engine-side limiting keyed by UUID. Server-target
        //launches count; the automatic lobby fallback is exempt (recovery infrastructure).
        final int originalLimit = dev.connectplus.config.CPConfig.maxConnectAttemptsPerMinute;
        dev.connectplus.config.CPConfig.maxConnectAttemptsPerMinute = 2;
        try {
            this.pc = this.connectToOriginLobby();

            //Attempt 1 passes; its failure falls back to the lobby (exempt from the window)
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo("unlinked-a.example.net", null, this.playerUuid), null, PLAYER_NAME));
            this.awaitServerSession(this.origin.sessionRegistry(), "a failed switch falls back to the lobby");

            //Attempt 2 fills the window...
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo("unlinked-b.example.net", null, this.playerUuid), null, PLAYER_NAME));
            this.awaitServerSession(this.origin.sessionRegistry(), "the second attempt also falls back");

            //...attempt 3 within the window is rejected for the same player
            assertEquals(SwitchInitiator.StartResult.REJECTED_RATE_LIMITED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo("unlinked-c.example.net", null, this.playerUuid), null, PLAYER_NAME),
                    "The burst must be rate limited (F8.1)");

            //A different UUID is a different key: not limited (F8.2)
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo("unlinked-d.example.net", null, UUID.randomUUID()), null, "OtherPlayer"),
                    "Another player must not be limited by the first player's window");
        } finally {
            dev.connectplus.config.CPConfig.maxConnectAttemptsPerMinute = originalLimit;
        }
    }

    private SwitchSuppressionHandler suppressionOf(final ProxyConnection pc) {
        for (final net.raphimc.viaproxy.proxy.packethandler.PacketHandler handler : pc.getPacketHandlers()) {
            if (handler instanceof SwitchSuppressionHandler switchSuppressionHandler) {
                return switchSuppressionHandler;
            }
        }
        return null;
    }

    /**
     * Connects to the origin lobby, hot switches to the target, and waits until the
     * FORWARD state settled (session moved, old p2s torn down).
     */
    private void switchToTarget(final LobbyServer targetServer) throws Exception {
        final InetSocketAddress targetAddress = (InetSocketAddress) targetServer.localAddress();
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc, new ConnectionInfo(
                targetAddress.getHostString() + ":" + targetAddress.getPort(), LobbyProtocol.VERSION, this.playerUuid), null, PLAYER_NAME));
        this.awaitServerSession(targetServer.sessionRegistry(), "switch to target");
        this.awaitEmptyRegistry(this.origin.sessionRegistry(), "old p2s teardown");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"reconnect", "transfer"})
    @Timeout(30)
    void offlineIdentitySurvivesBackendReconnectTransferAndConnectCommand(final String path) throws Exception {
        final String oldTransfer = dev.connectplus.config.CPConfig.transferPolicy;
        final String oldDown = dev.connectplus.config.CPConfig.backendDownPolicy;
        final int oldDelay = dev.connectplus.config.CPConfig.reconnectDelaySeconds;
        final boolean oldBlock = dev.connectplus.config.CPConfig.blockLocalTargets;
        final LobbyServer second = new LobbyServer(new SessionRegistry(), new TokenStore(dataDir),
                new PlayerStore(new File(dataDir, "players-offline-" + path)), null);
        try {
            dev.connectplus.config.CPConfig.transferPolicy = "follow";
            dev.connectplus.config.CPConfig.backendDownPolicy = "reconnect";
            dev.connectplus.config.CPConfig.reconnectDelaySeconds = 0;
            dev.connectplus.config.CPConfig.blockLocalTargets = false;
            second.start();
            this.pc = this.connectToOriginLobby();
            final InetSocketAddress address = (InetSocketAddress) this.target.localAddress();
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo(address.getHostString() + ":" + address.getPort(), CLIENT_VERSION, this.playerUuid, true),
                    null, PLAYER_NAME));
            final UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + PLAYER_NAME).getBytes(StandardCharsets.UTF_8));
            this.awaitServerSession(this.target.sessionRegistry(), offlineUuid, "offline initial backend");
            this.awaitEmptyRegistry(this.origin.sessionRegistry(), "offline lobby teardown");
            final var previousJob = this.suppressionOf(this.pc).currentJob();
            final InetSocketAddress next = (InetSocketAddress) second.localAddress();
            switch (path) {
                case "reconnect" -> this.pc.getChannel().close();
                default -> this.driveFromServer(new net.raphimc.netminecraft.packet.impl.play.S2CPlayTransferPacket(
                        next.getHostString(), next.getPort()));
            }
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                this.pump();
                final var nextJob = this.suppressionOf(this.pc).currentJob();
                if (nextJob != null && nextJob != previousJob && !nextJob.isSwitching()) break;
                Thread.sleep(25);
            }
            org.junit.jupiter.api.Assertions.assertNotSame(previousJob, this.suppressionOf(this.pc).currentJob(),
                    "Wait for a new completed switch rather than the old backend's session");
            this.awaitServerSession(path.equals("reconnect") ? this.target.sessionRegistry() : second.sessionRegistry(),
                    offlineUuid, "offline " + path);
            final var current = this.suppressionOf(this.pc).currentJob();
            assertTrue(current.target().offlineMode(), "Every backend continuation must retain the offline selection");
            assertFalse(current.authenticated());
            assertEquals(this.playerUuid, current.target().playerId(), "The original lobby owner must be retained");
            assertEquals(offlineUuid, this.pc.getGameProfile().getId());
            assertEquals(PLAYER_NAME, this.pc.getLoginHelloPacket().name);
            assertTrue(this.c2p.isActive());
            if (path.equals("reconnect")) assertTrue(current.target().attempt() > 1);
        } finally {
            second.stop();
            dev.connectplus.config.CPConfig.transferPolicy = oldTransfer;
            dev.connectplus.config.CPConfig.backendDownPolicy = oldDown;
            dev.connectplus.config.CPConfig.reconnectDelaySeconds = oldDelay;
            dev.connectplus.config.CPConfig.blockLocalTargets = oldBlock;
        }
    }

    @Test
    @Timeout(30)
    void playKickUnderLobbyPolicyReturnsToLobby() throws Exception {
        this.pc = this.connectToOriginLobby();
        this.switchToTarget(this.target);

        //The "server" kicks the player: a play disconnect arriving from the p2s side
        this.driveFromServer(new net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket(
                new net.lenni0451.mcstructs.text.components.StringComponent("Kicked!")));

        this.awaitServerSession(this.origin.sessionRegistry(), "kick policy=lobby returns to the lobby");
        this.awaitEmptyRegistry(this.target.sessionRegistry(), "kicked target teardown");
        assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the kicked-server recovery");
        assertEquals(0, LobbyNotices.size(), "The kick notice must have been consumed by the lobby session");
    }

    @Test
    @Timeout(30)
    void backendDeathAutoReconnectsAndRejoins() throws Exception {
        final String originalPolicy = dev.connectplus.config.CPConfig.backendDownPolicy;
        final int originalAttempts = dev.connectplus.config.CPConfig.reconnectAttempts;
        final int originalDelay = dev.connectplus.config.CPConfig.reconnectDelaySeconds;
        dev.connectplus.config.CPConfig.backendDownPolicy = "reconnect";
        dev.connectplus.config.CPConfig.reconnectAttempts = 3;
        dev.connectplus.config.CPConfig.reconnectDelaySeconds = 0;
        try {
            this.pc = this.connectToOriginLobby();
            this.switchToTarget(this.target);

            //The backend crashes: the p2s channel dies without a disconnect packet
            this.pc.getChannel().close();

            //The reconnect chain rejoins the same target; the client never drops
            this.awaitServerSession(this.target.sessionRegistry(), "reconnect after backend death");
            assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the backend death and reconnect");
            assertTrue(this.origin.sessionRegistry().size() == 0, "The reconnect must not go through the lobby");
            assertTrue(this.pc.getChannel().isActive() && this.target.localAddress().equals(this.pc.getChannel().remoteAddress()),
                    "The p2s must be reconnected to the same target");
        } finally {
            dev.connectplus.config.CPConfig.backendDownPolicy = originalPolicy;
            dev.connectplus.config.CPConfig.reconnectAttempts = originalAttempts;
            dev.connectplus.config.CPConfig.reconnectDelaySeconds = originalDelay;
        }
    }

    /**
     * The real environment fires exceptionCaught AND channelInactive for one p2s death;
     * both reach guardChannelDeath. The second callback must be swallowed as well — the
     * pre-fix code cascaded the c2p close there and killed the client mid-recovery (V3
     * manual verification finding), which the single-callback test above cannot catch.
     */
    @Test
    @Timeout(30)
    void secondDeathCallbackMustNotCascadeCloseTheClient() throws Exception {
        final String originalPolicy = dev.connectplus.config.CPConfig.backendDownPolicy;
        dev.connectplus.config.CPConfig.backendDownPolicy = "reconnect";
        try {
            this.pc = this.connectToOriginLobby();
            this.switchToTarget(this.target);
            final SwitchSuppressionHandler suppression = this.suppressionOf(this.pc);
            assertNotNull(suppression);

            assertTrue(suppression.guardChannelDeath(this.pc.getChannel()), "The first death callback starts the recovery");
            assertTrue(suppression.guardChannelDeath(this.pc.getChannel()), "The second death callback must be swallowed");
            assertTrue(this.c2p.isActive(), "The client connection must survive both death callbacks");

            //The recovery (reconnect chain) must still run: the chain rejoins the target
            this.awaitServerSession(this.target.sessionRegistry(), "reconnect after the double death callback");
            assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the reconnect");
        } finally {
            dev.connectplus.config.CPConfig.backendDownPolicy = originalPolicy;
        }
    }

    @Test
    @Timeout(30)
    void backendDeathAutoReconnectsThenFallsBackToLobbyWhenExhausted() throws Exception {
        final String originalPolicy = dev.connectplus.config.CPConfig.backendDownPolicy;
        final int originalAttempts = dev.connectplus.config.CPConfig.reconnectAttempts;
        final int originalDelay = dev.connectplus.config.CPConfig.reconnectDelaySeconds;
        dev.connectplus.config.CPConfig.backendDownPolicy = "reconnect";
        dev.connectplus.config.CPConfig.reconnectAttempts = 2;
        dev.connectplus.config.CPConfig.reconnectDelaySeconds = 0;
        try {
            this.pc = this.connectToOriginLobby();
            this.switchToTarget(this.target);

            //The backend dies and stays dead: close it and stop the server
            this.pc.getChannel().close();
            this.target.stop();

            this.awaitServerSession(this.origin.sessionRegistry(), "exhausted reconnects fall back to the lobby");
            assertTrue(this.pc.getC2P().isActive(), "Even exhausted reconnects must keep the client connected");
            boolean sawNotice = false;
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && !sawNotice) {
                this.drainClientOutbound();
                for (final Packet packet : this.clientReceived) {
                    if (packet instanceof UnknownPacket unknownPacket && unknownPacket.data != null
                            && new String(unknownPacket.data, StandardCharsets.UTF_8).contains("reconnect attempts failed")) {
                        sawNotice = true;
                    }
                }
                Thread.sleep(25);
            }
            assertTrue(sawNotice, "The exhaustion notice must reach the client through the lobby chat");
            assertTrue(this.pc.getC2P().isActive(), "The exhausted fallback must keep the client connected");
        } finally {
            dev.connectplus.config.CPConfig.backendDownPolicy = originalPolicy;
            dev.connectplus.config.CPConfig.reconnectAttempts = originalAttempts;
            dev.connectplus.config.CPConfig.reconnectDelaySeconds = originalDelay;
        }
    }

    @Test
    @Timeout(30)
    void backendDeathWithZeroReconnectAttemptsFallsBackToLobbyImmediately() throws Exception {
        //M6: reconnectAttempts=0 means no retries (the M5 Math.max(1,..) floor is gone)
        final String originalPolicy = dev.connectplus.config.CPConfig.backendDownPolicy;
        final int originalAttempts = dev.connectplus.config.CPConfig.reconnectAttempts;
        final int originalDelay = dev.connectplus.config.CPConfig.reconnectDelaySeconds;
        dev.connectplus.config.CPConfig.backendDownPolicy = "reconnect";
        dev.connectplus.config.CPConfig.reconnectAttempts = 0;
        dev.connectplus.config.CPConfig.reconnectDelaySeconds = 0;
        try {
            this.pc = this.connectToOriginLobby();
            this.switchToTarget(this.target);

            this.pc.getChannel().close();
            this.target.stop();

            this.awaitServerSession(this.origin.sessionRegistry(), "zero attempts fall straight back to the lobby");
            assertTrue(this.pc.getC2P().isActive(), "The zero-attempt fallback must keep the client connected");
            assertTrue(this.target.sessionRegistry().size() == 0, "The dead target must stay empty (no reconnect tried)");
        } finally {
            dev.connectplus.config.CPConfig.backendDownPolicy = originalPolicy;
            dev.connectplus.config.CPConfig.reconnectAttempts = originalAttempts;
            dev.connectplus.config.CPConfig.reconnectDelaySeconds = originalDelay;
        }
    }

    @Test
    @Timeout(30)
    void lobbyFallbackSurvivesAnExhaustedRateWindow() throws Exception {
        //Review finding 1: the lobby fallback is infrastructure — a player whose
        //connect window is exhausted must still be recovered on a real backend death
        final int originalLimit = dev.connectplus.config.CPConfig.maxConnectAttemptsPerMinute;
        dev.connectplus.config.CPConfig.maxConnectAttemptsPerMinute = 1;
        try {
            this.pc = this.connectToOriginLobby();
            //The window holds exactly one slot: switchToTarget consumes it
            this.switchToTarget(this.target);

            //A real backend death afterwards must still reach the lobby even though
            //the window is exhausted (the fallback is exempt from the limiter)
            this.pc.getChannel().close();
            this.awaitServerSession(this.origin.sessionRegistry(), "backend death recovery despite exhausted window");
            assertTrue(this.pc.getC2P().isActive(), "The recovery must keep the client connected");
            assertEquals(0, LobbyNotices.size(), "The recovery notice must have been consumed by the lobby session");
        } finally {
            dev.connectplus.config.CPConfig.maxConnectAttemptsPerMinute = originalLimit;
        }
    }

    @Test
    @Timeout(30)
    void transferFollowSwitchesToNewTarget() throws Exception {
        final String original = dev.connectplus.config.CPConfig.transferPolicy;
        dev.connectplus.config.CPConfig.transferPolicy = "follow";
        try {
            this.pc = this.connectToOriginLobby();
            final LobbyServer secondTarget = new LobbyServer(
                    new SessionRegistry(),
                    new TokenStore(dataDir),
                    new PlayerStore(new File(dataDir, "players-follow")),
                    null);
            secondTarget.start();
            this.switchToTarget(this.target);

            //The server sends a transfer packet to the second target (inbound, p2s side)
            final InetSocketAddress secondAddress = (InetSocketAddress) secondTarget.localAddress();
            this.driveFromServer(new net.raphimc.netminecraft.packet.impl.play.S2CPlayTransferPacket(
                    secondAddress.getHostString(), secondAddress.getPort()));

            this.awaitServerSession(secondTarget.sessionRegistry(), "transfer follow reaches the new target");
            this.awaitEmptyRegistry(this.target.sessionRegistry(), "old target teardown after transfer");
            assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the transfer follow");
            assertEquals(0, dev.connectplus.lobby.LobbyTransfers.size(), "A followed transfer must not post a confirmation");
            secondTarget.stop();
        } finally {
            dev.connectplus.config.CPConfig.transferPolicy = original;
        }
    }

    @Test
    @Timeout(30)
    void transferConfirmReturnsToLobby() throws Exception {
        //default transferPolicy=confirm
        this.pc = this.connectToOriginLobby();
        this.switchToTarget(this.target);

        this.driveFromServer(new net.raphimc.netminecraft.packet.impl.play.S2CPlayTransferPacket(
                "evil.example.net", 25565));

        this.awaitServerSession(this.origin.sessionRegistry(), "transfer confirm returns to the lobby");
        assertTrue(this.pc.getC2P().isActive(), "The client connection must survive the transfer confirm");
        assertEquals(0, LobbyNotices.size(), "The transfer notice must have been consumed by the lobby session");
        //The pending transfer is consumed by the lobby session load, which opens the
        //confirmation GUI; the screen packet arrives at the (untyped) test client with
        //the title in its payload
        boolean sawConfirmScreen = false;
        final long screenDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < screenDeadline && !sawConfirmScreen) {
            this.drainClientOutbound();
            for (final Packet packet : this.clientReceived) {
                if (packet instanceof UnknownPacket unknownPacket && unknownPacket.data != null
                        && new String(unknownPacket.data, StandardCharsets.UTF_8).contains("Server transfer")) {
                    sawConfirmScreen = true;
                }
            }
            Thread.sleep(25);
        }
        assertTrue(sawConfirmScreen, "The transfer confirmation GUI must open after the fallback join");
        boolean sawNotice = false;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !sawNotice) {
            this.drainClientOutbound();
            for (final Packet packet : this.clientReceived) {
                if (packet instanceof UnknownPacket unknownPacket && unknownPacket.data != null
                        && new String(unknownPacket.data, StandardCharsets.UTF_8).contains("evil.example.net")) {
                    sawNotice = true;
                }
            }
            Thread.sleep(25);
        }
        assertTrue(sawNotice, "The transfer confirm notice (with the target) must reach the client");
    }

    @Test
    @Timeout(30)
    void transferIgnoreStaysConnected() throws Exception {
        final String original = dev.connectplus.config.CPConfig.transferPolicy;
        dev.connectplus.config.CPConfig.transferPolicy = "ignore";
        try {
            this.pc = this.connectToOriginLobby();
            this.switchToTarget(this.target);

            this.driveFromServer(new net.raphimc.netminecraft.packet.impl.play.S2CPlayTransferPacket(
                    "evil.example.net", 25565));

            //Pump for a moment: nothing may change
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
            while (System.nanoTime() < deadline) {
                this.pump();
                Thread.sleep(25);
            }
            assertTrue(this.target.sessionRegistry().size() == 1, "The session must stay on the target under ignore");
            assertTrue(this.pc.getC2P().isActive(), "The client connection must stay alive under ignore");
            assertEquals(this.target.localAddress(), this.pc.getChannel().remoteAddress(), "No reconnect may happen under ignore");
            assertEquals(0, dev.connectplus.lobby.LobbyTransfers.size(), "An ignored transfer must not post a confirmation");
        } finally {
            dev.connectplus.config.CPConfig.transferPolicy = original;
        }
    }


    /**
     * Review round 2, IMPORTANT (transfer-follow drops the connectionId): the
     * follow branch created the follow job WITHOUT the connection id, so the
     * follow's own reconnect chain looked the cache up with a null key and
     * silently lost credential reuse. Pinned through the production wiring (a
     * protected origin join whose lease is keyed by the connection id, the
     * LobbyServer-installed validator, and the real transfer-follow flow): the
     * follow job carries the ORIGINAL connection id, its reconnect attempt
     * reuses the cached account while the lease is current, and the attempt
     * after the displacement drops the account.
     */
    @Test
    @Timeout(60)
    void transferFollowCarriesTheConnectionIdAndItsReconnectReusesTheCachedAccount() throws Exception {
        final String oldTransferPolicy = CPConfig.transferPolicy;
        final String oldBackendPolicy = CPConfig.backendDownPolicy;
        final int oldAttempts = CPConfig.reconnectAttempts;
        final int oldDelay = CPConfig.reconnectDelaySeconds;
        final boolean oldProxyOnline = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldAccountLogin = CPConfig.allowAccountLogin;
        CPConfig.transferPolicy = "follow";
        CPConfig.backendDownPolicy = "reconnect";
        CPConfig.reconnectAttempts = 3;
        CPConfig.reconnectDelaySeconds = 0;
        ViaProxy.getConfig().setProxyOnlineMode(true);
        CPConfig.allowAccountLogin = true;
        try {
            //The production wiring: the origin LobbyServer owns the coordinator, so
            //its validator is the one production CoreMain would install for it.
            SwitchEngine.setAccountLeaseValidator(this.origin.accountLeaseValidator());

            //A PROTECTED origin join: the session holds a lease keyed by the connection id.
            this.pc = this.connectToOriginLobby(true);
            this.awaitServerSession(this.origin.sessionRegistry(), "protected origin join");
            PlayerSession originSession = null;
            final long deadlineLease = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadlineLease) {
                originSession = this.origin.sessionRegistry().get(this.playerUuid);
                if (originSession != null && originSession.lease != null) {
                    break;
                }
                Thread.sleep(25);
            }
            assertNotNull(originSession, "The protected join must register the session");
            assertNotNull(originSession.lease, "The protected join must grant a lease");
            final UUID connectionId = originSession.connectionId;
            assertNotNull(connectionId);
            assertTrue(this.origin.sessionCoordinator().isCurrent(originSession.lease));

            //First switch (to the target lobby): the account rides and is cached. The
            //attach path only wraps the account for the backend login, so a stub is
            //enough here - the lease validation (the thing under test) is independent
            //of the account implementation.
            final dev.connectplus.accounts.CPAccount account = new StubAccount();

            final InetSocketAddress targetAddress = (InetSocketAddress) this.target.localAddress();
            assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                    new ConnectionInfo(targetAddress.getHostString() + ":" + targetAddress.getPort(),
                            LobbyProtocol.VERSION, this.playerUuid, false, connectionId),
                    account, PLAYER_NAME));
            final long deadlineAuth1 = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadlineAuth1
                    && (suppressionOf(this.pc).currentJob() == null || !suppressionOf(this.pc).currentJob().authenticated())) {
                this.drainClientOutbound();
                this.pump();
                Thread.sleep(25);
            }
            assertTrue(suppressionOf(this.pc).currentJob().authenticated(),
                    "A current lease's account must ride the first switch");
            assertSame(account, this.engine.cachedAccountForTest(connectionId),
                    "The account must be cached by the CONNECTION id for the retry paths");
            this.awaitJoinGameOnClient();
            this.drainClientOutbound();
            this.switchBaseline = this.clientReceived.size();
            this.awaitServerSession(this.target.sessionRegistry(), "first switch");

            //The backend sends a transfer packet: transferPolicy=follow creates the
            //follow job - it must carry the ORIGINAL connection id (the round-2 fix).
            final SwitchJob job1 = suppressionOf(this.pc).currentJob();
            this.engine.onTargetTransfer(this.pc, job1, "127.0.0.1", targetAddress.getPort());
            SwitchJob followJob = null;
            final long deadlineFollow = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadlineFollow) {
                this.drainClientOutbound();
                this.pump();
                followJob = suppressionOf(this.pc).currentJob();
                if (followJob != null && followJob.target().connectionId() != null
                        && "transfer.example.net:25565".equals(followJob.target().address())) {
                    break;
                }
                Thread.sleep(25);
            }
            assertNotNull(followJob, "The follow branch must start a new job");
            assertNotSame(job1, followJob);
            assertEquals(connectionId, followJob.target().connectionId(),
                    "The transfer-follow job must carry the ORIGINAL connection id (round-2 fix)");

            //The follow switch completes on the new target (a real local lobby that
            //answers the auto-detect status ping and the login flow).
            this.awaitJoinGameOnClient();
            this.drainClientOutbound();
            this.switchBaseline = this.clientReceived.size();
            assertTrue(followJob.authenticated(),
                    "The follow job must reuse the cached account (fetched by connection id)");

            //The follow job's p2s dies in FORWARD: backendDownPolicy=reconnect runs
            //the reconnect chain - its attempt must reuse the cached account (the
            //origin lease binds the c2p and survives switches).
            this.suppressionOf(this.pc).guardChannelDeath(this.pc.getChannel());
            SwitchJob reconnectJob = null;
            final long deadlineReconnect = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadlineReconnect) {
                this.drainClientOutbound();
                this.pump();
                reconnectJob = suppressionOf(this.pc).currentJob();
                if (reconnectJob != null && reconnectJob.target().attempt() == 2 && reconnectJob.authenticated()) {
                    break;
                }
                Thread.sleep(25);
            }
            assertNotNull(reconnectJob, "The reconnect attempt must start");
            assertTrue(reconnectJob.authenticated(),
                    "The follow job's reconnect attempt must reuse the cached account while the lease is current");
            assertEquals(connectionId, reconnectJob.target().connectionId());
            this.awaitJoinGameOnClient();
            this.drainClientOutbound();
            this.switchBaseline = this.clientReceived.size();

            //The displacement: freeze + invalidate the origin lease (§5 step 2/3).
            originSession.displaced = true;
            this.origin.sessionCoordinator().release(originSession.lease)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            this.pump();

            //The next reconnect attempt must NOT re-attach the displaced session's
            //account: the cache is consulted by connection id and refused.
            this.suppressionOf(this.pc).guardChannelDeath(this.pc.getChannel());
            SwitchJob displacedRetry = null;
            final long deadlineDrop = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadlineDrop) {
                this.drainClientOutbound();
                this.pump();
                displacedRetry = suppressionOf(this.pc).currentJob();
                if (displacedRetry != null && displacedRetry.target().attempt() == 3 && !displacedRetry.authenticated()) {
                    break;
                }
                Thread.sleep(25);
            }
            assertNotNull(displacedRetry, "The post-displacement reconnect attempt must start");
            assertFalse(displacedRetry.authenticated(),
                    "The reconnect after the displacement must drop the cached account (no revival)");
            assertNull(this.engine.cachedAccountForTest(connectionId),
                    "The dropped account must have been evicted from the switch cache");
        } finally {
            CPConfig.transferPolicy = oldTransferPolicy;
            CPConfig.backendDownPolicy = oldBackendPolicy;
            CPConfig.reconnectAttempts = oldAttempts;
            CPConfig.reconnectDelaySeconds = oldDelay;
            ViaProxy.getConfig().setProxyOnlineMode(oldProxyOnline);
            CPConfig.allowAccountLogin = oldAccountLogin;
        }
    }
}
