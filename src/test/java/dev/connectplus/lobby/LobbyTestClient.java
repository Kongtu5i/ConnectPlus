package dev.connectplus.lobby;

import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.lobby.model.HandshakeData;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandSignedPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.S2CKeepAlivePacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyTLV;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.play.C2SPlayKeepAlivePacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.BitSet;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scripted NetMinecraft client for lobby integration tests. Auto-answers
 * keepalives and configuration finish so tests can assert on the packet flow.
 * M2: adds GUI interactions (container clicks, chat, HAProxy preamble).
 */
class LobbyTestClient {

    static {
        //Same ViaVersion static-init workaround as CustomPacketRoundTripTest: the first
        //SetContent encode/decode in a test JVM triggers VersionedTypes.<clinit>, which
        //deadlock-cycles with StructuredDataKey.<clinit>. Touching StructuredDataKey
        //first resolves the cycle. (Inside ViaProxy, Via initializes long before.)
        if (com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME == null) {
            throw new IllegalStateException("ViaVersion structured data keys not initialized");
        }
    }

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(1);

    private final BlockingQueue<Packet> received = new LinkedBlockingQueue<>();
    private final AtomicReference<Packet> firstPlayPacket = new AtomicReference<>();
    private volatile boolean playStarted = false;
    private volatile int windowId;
    private Channel channel;

    void connect(final InetSocketAddress address) {
        final Bootstrap bootstrap = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .handler(new ClientInitializer());
        this.channel = bootstrap.connect(address).syncUninterruptibly().channel();
    }

    void login(final String name) {
        this.login(name, UUID.randomUUID());
    }

    void login(final String name, final UUID uuid) {
        final InetSocketAddress remote = (InetSocketAddress) this.channel.remoteAddress();
        this.write(new C2SHandshakingClientIntentionPacket(LobbyProtocol.VERSION.getVersion(), remote.getHostString(), remote.getPort(), IntendedState.LOGIN));
        this.write(new C2SLoginHelloPacket(name, null, null, null, uuid));
    }

    /**
     * Clicks the currently displayed container in pickup mode.
     */
    void clickContainer(final int slot, final int button) {
        this.write(new C2SContainerClickPacket(this.windowId, 0, slot, button, 0, new HashMap<>(), StructuredItem.empty()));
    }

    /**
     * Sends a chat message as if typed into the chat window.
     */
    void sendChat(final String message) {
        this.write(new C2SChatPacket(message, Instant.now(), 0L, null, 0, new BitSet(3)));
    }

    /**
     * Sends the client settings carrying the locale for the bilingual texts
     * (M6 F1.4); written right after the login acknowledgement like a vanilla
     * client does.
     */
    void sendClientInformation(final String locale) {
        this.write(new dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket(locale, 12, 0, true, 127, 1, false, true));
    }

    /**
     * Sends the client settings again inside the play state (a client that
     * changed its language in the options screen); the play-state packet class
     * resolves its own packet id (M7).
     */
    void sendClientInformationInPlay(final String locale) {
        this.write(new dev.connectplus.lobby.protocol.packets.config.C2SPlayClientInformationPacket(locale, 12, 0, true, 127, 1, false, true));
    }

    /**
     * Sends a chat command as if typed with a leading slash (the packet carries
     * the command without the slash).
     */
    void sendChatCommand(final String command) {
        this.write(new C2SChatCommandPacket(command));
    }

    /**
     * Sends a chat command in the 1.19.1+ signed variant — what ViaVersion
     * translates pre-1.19.1 clients' slash commands into (V7 finding).
     */
    void sendChatCommandSigned(final String command) {
        this.write(new C2SChatCommandSignedPacket(command));
    }

    /**
     * Closes the currently open container GUI.
     */
    void closeContainer() {
        this.write(new C2SContainerClosePacket(this.windowId));
    }

    /**
     * Writes raw bytes without any packet framing (garbage-input fixtures).
     */
    void sendRaw(final byte[] bytes) {
        this.channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
    }

    /**
     * Closes the client connection.
     */
    void close() {
        this.channel.close();
    }

    /**
     * Sends an HAProxy v2 preamble carrying a 0xE0 TLV with the given handshake
     * information; must be written before the MC handshake, like a real proxy sender.
     */
    void sendHaProxyPreamble(final String host, final int port) {
        this.sendHaProxyPreamble(host, port, LobbyProtocol.VERSION);
    }

    /**
     * Same as {@link #sendHaProxyPreamble(String, int)} but with an explicit client
     * protocol version in the TLV (the MC handshake itself keeps the lobby version).
     */
    void sendHaProxyPreamble(final String host, final int port, final ProtocolVersion clientVersion) {
        final ByteBuf handshakeBuf = Unpooled.buffer();
        new HandshakeData(host, port, clientVersion).write(handshakeBuf);
        final InetSocketAddress remote = (InetSocketAddress) this.channel.remoteAddress();
        final HAProxyMessage preamble = new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "203.0.113.7", "203.0.113.7", 40000, remote.getPort(),
                java.util.List.of(new HAProxyTLV((byte) 0xE0, handshakeBuf)));
        this.writeRaw(encodeHaProxyPreamble(preamble));
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

    void write(final Packet packet) {
        this.channel.writeAndFlush(packet);
        if (packet instanceof C2SHandshakingClientIntentionPacket) {
            this.setState(ConnectionState.LOGIN);
        } else if (packet instanceof C2SLoginAcknowledgedPacket) {
            this.setState(ConnectionState.CONFIGURATION);
        } else if (packet instanceof C2SConfigFinishConfigurationPacket) {
            this.setState(ConnectionState.PLAY);
        }
    }

    /**
     * Sends raw bytes (e.g. an HAProxy proxy protocol preamble) before the MC handshake.
     * Written at the pipeline head so no MC outbound framing (the length-prefixed sizer)
     * touches the preamble — the same bytes a real proxy protocol sender would put on the wire.
     */
    void writeRaw(final ByteBuf data) {
        this.channel.pipeline().firstContext().writeAndFlush(data).syncUninterruptibly();
    }

    /**
     * The server address this client connected to (handshake host/port source).
     */
    java.net.InetSocketAddress remoteAddressOf() {
        return (java.net.InetSocketAddress) this.channel.remoteAddress();
    }

    void setState(final ConnectionState state) {
        this.channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().setConnectionState(state);
    }

    void disconnect() {
        if (this.channel != null) {
            this.channel.close().syncUninterruptibly();
        }
    }

    boolean isActive() {
        return this.channel != null && this.channel.isActive();
    }

    java.util.List<Packet> receivedSnapshot() {
        return new java.util.ArrayList<>(this.received);
    }

    <T extends Packet> T await(final Class<T> type) throws InterruptedException {
        return this.await(type, 1);
    }

    /**
     * Waits for the nth packet of the given type (1-based) — screens like the main
     * menu can be opened multiple times, so tests target a specific occurrence.
     */
    <T extends Packet> T await(final Class<T> type, final int occurrence) throws InterruptedException {
        return this.await(type, occurrence, packet -> true);
    }

    dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket awaitContainerContent(
            final int windowId, final int occurrence) throws InterruptedException {
        return this.await(dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket.class,
                occurrence, packet -> packet.windowId == windowId);
    }

    dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket awaitChestContent(
            final int occurrence) throws InterruptedException {
        return this.await(dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket.class,
                occurrence, packet -> packet.windowId > 0);
    }

    private <T extends Packet> T await(final Class<T> type, final int occurrence,
                                      final java.util.function.Predicate<T> match) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int seen = 0;
            for (final Packet packet : this.received) {
                if (type.isInstance(packet) && match.test(type.cast(packet)) && ++seen == occurrence) {
                    return type.cast(packet);
                }
            }
            Thread.sleep(25);
        }
        throw new NoSuchElementException("Timed out waiting for occurrence " + occurrence + " of " + type.getSimpleName() + ", got: " + this.received);
    }

    Packet firstPlayPacket() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final Packet packet = this.firstPlayPacket.get();
            if (packet != null) {
                return packet;
            }
            Thread.sleep(25);
        }
        throw new NoSuchElementException("Timed out waiting for the first play packet");
    }

    private final class ClientInitializer extends MinecraftChannelInitializer {

        private ClientInitializer() {
            super(AutoResponder::new);
        }

        @Override
        protected void initChannel(final Channel channel) {
            super.initChannel(channel);
            channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new dev.connectplus.lobby.protocol.LobbyPacketRegistry(true));
        }
    }

    private final class AutoResponder extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof Packet packet) {
                if (packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket open) {
                    LobbyTestClient.this.windowId = open.id;
                } else if (packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerClosePacket close
                        && close.id == LobbyTestClient.this.windowId) {
                    LobbyTestClient.this.windowId = 0;
                }
                LobbyTestClient.this.received.add(packet);
                if (LobbyTestClient.this.playStarted && LobbyTestClient.this.firstPlayPacket.get() == null) {
                    LobbyTestClient.this.firstPlayPacket.set(packet);
                }
                if (packet instanceof S2CConfigFinishConfigurationPacket) {
                    LobbyTestClient.this.playStarted = true;
                    ctx.writeAndFlush(new C2SConfigFinishConfigurationPacket());
                    LobbyTestClient.this.setState(ConnectionState.PLAY);
                } else if (packet instanceof S2CKeepAlivePacket keepAlive) {
                    ctx.writeAndFlush(new C2SPlayKeepAlivePacket(keepAlive.id));
                }
            }
        }
    }
}
