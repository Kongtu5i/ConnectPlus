package dev.connectplus.verify;

import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.*;
import com.viaversion.nbt.tag.CompoundTag;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.raphimc.netminecraft.constants.*;
import net.raphimc.netminecraft.netty.connection.*;
import net.raphimc.netminecraft.packet.*;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.netminecraft.packet.impl.configuration.*;
import net.raphimc.netminecraft.packet.impl.handshaking.*;
import net.raphimc.netminecraft.packet.impl.login.*;
import net.raphimc.netminecraft.packet.impl.play.*;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Loopback-only black-box probe: unmodified official host, legacy backend, real frontend codec. */
public final class RegistrySwitchProbe {
    private final int version;
    private String scenario = "normal";
    private volatile int window, height, minY, cycles, joins;
    private volatile Throwable error;
    private final CountDownLatch initial = new CountDownLatch(1), targetChunk = new CountDownLatch(1), returned = new CountDownLatch(1);
    private Channel client;
    private final Set<Channel> backends = ConcurrentHashMap.newKeySet();

    private RegistrySwitchProbe(int version) { this.version = version; }

    public static void main(String[] args) throws Exception {
        final var probe = new RegistrySwitchProbe(Integer.parseInt(args[1]));
        if (args.length > 2) probe.scenario = args[2];
        probe.run(Integer.parseInt(args[0]));
    }

    private void run(int proxyPort) throws Exception {
        final EventLoopGroup group = new NioEventLoopGroup(1);
        final NetServer backend = new NetServer(new MinecraftChannelInitializer(() -> new SimpleChannelInboundHandler<Packet>() {
            @Override protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
                final var registry = ctx.channel().attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get();
                if (packet instanceof C2SHandshakingClientIntentionPacket intention) {
                    registry.setConnectionState(intention.intendedState == IntendedState.STATUS ? ConnectionState.STATUS : ConnectionState.LOGIN);
                } else if (registry.getConnectionState() == ConnectionState.STATUS
                        && (packet instanceof net.raphimc.netminecraft.packet.impl.status.C2SStatusRequestPacket
                        || packet instanceof UnknownPacket raw && raw.packetId == 0)) {
                    ctx.writeAndFlush(raw(0, b -> PacketTypes.writeString(b, "{\"version\":{\"name\":\"1.13.2\",\"protocol\":404},\"players\":{\"max\":1,\"online\":0},\"description\":{\"text\":\"Registry probe\"}}")));
                } else if (packet instanceof C2SLoginHelloPacket hello) {
                    //The official codec handles the historical login-success layout.
                    ctx.writeAndFlush(new S2CLoginGameProfilePacket(UUID.nameUUIDFromBytes(hello.name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), hello.name, List.of()));
                    registry.setConnectionState(ConnectionState.PLAY);
                    ctx.writeAndFlush(raw(MCPackets.S2C_LOGIN.getId(404), b -> {
                        b.writeInt(123); b.writeByte(1); b.writeInt(0); b.writeByte(1); b.writeByte(1);
                        PacketTypes.writeString(b, "default"); b.writeBoolean(false);
                    }));
                    final ChunkSection[] sections = new ChunkSection[16];
                    for (int i = 0; i < sections.length; i++) {
                        final var section = new ChunkSectionImpl(true);
                        section.palette(PaletteType.BLOCKS).addId(0);
                        section.getLight().setSkyLight(new byte[2048]);
                        sections[i] = section;
                    }
                    final int[] biomes = new int[256]; Arrays.fill(biomes, 1);
                    final Chunk chunk = new BaseChunk(0, 0, true, false, 0xffff, sections, biomes, List.of());
                    ctx.writeAndFlush(raw(MCPackets.S2C_LEVEL_CHUNK_WITH_LIGHT.getId(404), b -> new ChunkType1_13(true).write(b, chunk)));
                    System.out.println("BACKEND login/JoinGame/chunk: 1.13.2, sections=16");
                }
            }
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { error = cause; cause.printStackTrace(); ctx.close(); }
        }) {
            @Override protected void initChannel(Channel channel) {
                super.initChannel(channel); backends.add(channel);
                channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(false, 404));
            }
        });
        try {
            backend.bind(new InetSocketAddress("127.0.0.1", 0), false);
            client = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new MinecraftChannelInitializer(() -> new SimpleChannelInboundHandler<Packet>() {
                @Override protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
                    try { receive(ctx, packet); }
                    catch (Throwable cause) { error = cause; cause.printStackTrace(); ctx.close(); }
                }
                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { error = cause; cause.printStackTrace(); ctx.close(); }
            }) {
                @Override protected void initChannel(Channel channel) {
                    super.initChannel(channel);
                    channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, version));
                }
            }).connect("127.0.0.1", proxyPort).syncUninterruptibly().channel();
            client.writeAndFlush(new C2SHandshakingClientIntentionPacket(version, "127.0.0.1", proxyPort, IntendedState.LOGIN));
            state(ConnectionState.LOGIN);
            client.writeAndFlush(new C2SLoginHelloPacket("RegistryProbe", null, null, null, UUID.randomUUID()));
            require(initial.await(20, TimeUnit.SECONDS), "initial lobby did not open");
            click(10); Thread.sleep(150);
            final int port = ((InetSocketAddress) backend.getChannel().localAddress()).getPort();
            String address = "127.0.0.1:" + port;
            final StringBuilder fullWidth = new StringBuilder();
            for (char c : address.toCharArray()) fullWidth.append((char) (c + 0xfee0));
            client.writeAndFlush(raw(MCPackets.C2S_CHAT.getId(version), b -> {
                PacketTypes.writeString(b, fullWidth.toString()); b.writeLong(System.currentTimeMillis()); b.writeLong(0);
                b.writeBoolean(false); PacketTypes.writeVarInt(b, 0); b.writeZero(3);
                if (version >= 770) b.writeByte(0);
            }));
            Thread.sleep(350); click(16);
            if (scenario.equals("normal")) {
                require(targetChunk.await(20, TimeUnit.SECONDS), "target chunk did not arrive");
                require(error == null && client.isActive(), "frontend failed: " + error);
                require(cycles == 1 && height == 256 && minY == 0, "target registry did not replace lobby dimensions");
                client.writeAndFlush(raw((version < 766 ? MCPackets.C2S_CHAT_COMMAND_SIGNED : MCPackets.C2S_CHAT_COMMAND).getId(version), b -> {
                    PacketTypes.writeString(b, "disconnect");
                    if (version < 766) { b.writeLong(System.currentTimeMillis()); b.writeLong(0); PacketTypes.writeVarInt(b, 0); PacketTypes.writeVarInt(b, 0); b.writeZero(3); }
                }));
            }
            require(returned.await(20, TimeUnit.SECONDS), "return to lobby failed");
            require(error == null && client.isActive() && height == 384 && minY == -64
                    && cycles == (scenario.equals("start-timeout") ? 1 : 2), "lobby registry not restored");
            System.out.println("PASS frontend=" + version + " target=404 scenario=" + scenario
                    + ": full-width input; official Auto Detect; lobby restored; active=true");
        } finally {
            if (client != null) client.close().syncUninterruptibly();
            for (Channel channel : backends) channel.close().syncUninterruptibly();
            if (backend.getChannel() != null) backend.getChannel().close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private void receive(ChannelHandlerContext ctx, Packet packet) {
        if (packet instanceof S2CLoginCompressionPacket compression) {
            ctx.channel().attr(MCPipeline.COMPRESSION_THRESHOLD_ATTRIBUTE_KEY).set(compression.compressionThreshold);
            if (ctx.pipeline().get(MCPipeline.COMPRESSION_HANDLER_NAME) == null) ctx.pipeline().addBefore(MCPipeline.PACKET_CODEC_HANDLER_NAME, MCPipeline.COMPRESSION_HANDLER_NAME, new net.raphimc.netminecraft.netty.codec.PacketCompressor());
        } else if (packet instanceof S2CLoginGameProfilePacket) {
            client.writeAndFlush(new C2SLoginAcknowledgedPacket()); state(ConnectionState.CONFIGURATION);
        } else if (packet instanceof S2CPlayStartConfigurationPacket) {
            cycles++;
            final Runnable acknowledge = () -> { client.writeAndFlush(new C2SPlayConfigurationAcknowledgedPacket()); state(ConnectionState.CONFIGURATION); };
            if (scenario.equals("start-timeout") && cycles == 1) ctx.executor().schedule(acknowledge, 1800, TimeUnit.MILLISECONDS);
            else acknowledge.run();
        } else if (packet instanceof S2CConfigFinishConfigurationPacket) {
            final Runnable acknowledge = () -> { client.writeAndFlush(new C2SConfigFinishConfigurationPacket()); state(ConnectionState.PLAY); };
            if (scenario.equals("finish-timeout") && cycles == 1) ctx.executor().schedule(acknowledge, 1800, TimeUnit.MILLISECONDS);
            else acknowledge.run();
        } else if (packet instanceof S2CConfigSelectKnownPacksPacket) {
            client.writeAndFlush(new C2SConfigSelectKnownPacksPacket(List.of()));
        } else if (packet instanceof S2CConfigKeepAlivePacket keep) {
            client.writeAndFlush(new C2SConfigKeepAlivePacket(keep.id));
        } else if (packet instanceof S2CPlayKeepAlivePacket keep) {
            client.writeAndFlush(new C2SPlayKeepAlivePacket(keep.id));
        } else if (packet instanceof UnknownPacket raw) {
            final ByteBuf buf = Unpooled.wrappedBuffer(raw.data);
            try {
                if (state() == ConnectionState.CONFIGURATION && raw.packetId == MCPackets.S2C_CONFIG_REGISTRY_DATA.getId(version)) {
                    if (version < 766) {
                        final CompoundTag registries = Types.COMPOUND_TAG.read(buf);
                        final CompoundTag dimensions = registries.getCompoundTag("minecraft:dimension_type") != null
                                ? registries.getCompoundTag("minecraft:dimension_type") : registries.getCompoundTag("dimension_type");
                        if (dimensions == null) return;
                        for (var element : dimensions.getListTag("value", CompoundTag.class)) {
                            if (element.getString("name").equals("minecraft:overworld") || element.getString("name").equals("overworld")) dimension(element.getCompoundTag("element"));
                        }
                        return;
                    }
                    final String registry = PacketTypes.readString(buf, 32767);
                    final int entries = PacketTypes.readVarInt(buf);
                    for (int i = 0; i < entries; i++) {
                        final String key = PacketTypes.readString(buf, 32767);
                        final var tag = buf.readBoolean() ? Types.TAG.read(buf) : null;
                        if (registry.equals("minecraft:dimension_type") && key.equals("minecraft:overworld") && tag instanceof CompoundTag compound) {
                            dimension(compound);
                        }
                    }
                } else if (state() == ConnectionState.PLAY && raw.packetId == MCPackets.S2C_OPEN_SCREEN.getId(version)) {
                    window = PacketTypes.readVarInt(buf);
                    if (cycles == 0) initial.countDown();
                    else if (cycles == 2 || cycles == 1 && scenario.equals("start-timeout")) returned.countDown();
                } else if (state() == ConnectionState.PLAY && raw.packetId == MCPackets.S2C_LOGIN.getId(version)) {
                    joins++;
                } else if (scenario.equals("normal") && state() == ConnectionState.PLAY
                        && raw.packetId == MCPackets.S2C_LEVEL_CHUNK_WITH_LIGHT.getId(version) && cycles == 1) {
                    //The same chunk must fail with the stale lobby's 24-section definition.
                    final ByteBuf stale = buf.duplicate();
                    boolean wrongHeightFailed = false;
                    try { chunkType(24).read(stale); } catch (IndexOutOfBoundsException expected) { wrongHeightFailed = true; }
                    final Chunk chunk = chunkType(height / 16).read(buf);
                    require(wrongHeightFailed && chunk.getSections().length == 16 && joins >= 2, "height mismatch not reproduced");
                    System.out.println("CHUNK stale 24 sections: IndexOutOfBoundsException; target 16 sections: PASS");
                    targetChunk.countDown();
                }
            } finally { buf.release(); }
        }
    }

    private com.viaversion.viaversion.api.type.Type<Chunk> chunkType(int sections) {
        return version >= 770 ? new ChunkType1_21_5(sections, 15, 7) : new ChunkType1_20_2(sections, 15, 7);
    }
    private void dimension(CompoundTag tag) {
        height = tag.getInt("height"); minY = tag.getInt("min_y");
        System.out.println("REGISTRY cycle=" + cycles + " height=" + height + " min_y=" + minY);
    }
    private void click(int slot) {
        client.writeAndFlush(raw(MCPackets.C2S_CONTAINER_CLICK.getId(version), b -> {
            PacketTypes.writeVarInt(b, window); PacketTypes.writeVarInt(b, 0); b.writeShort(slot); b.writeByte(0);
            PacketTypes.writeVarInt(b, 0); PacketTypes.writeVarInt(b, 0); b.writeByte(0);
        }));
    }
    private void state(ConnectionState state) { client.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().setConnectionState(state); }
    private ConnectionState state() { return client.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().getConnectionState(); }
    private static UnknownPacket raw(int id, Consumer<ByteBuf> writer) {
        final ByteBuf buf = Unpooled.buffer();
        try { writer.accept(buf); return new UnknownPacket(id, ByteBufUtil.getBytes(buf)); }
        finally { buf.release(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
