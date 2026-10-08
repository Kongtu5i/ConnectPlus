package dev.connectplus.verify;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.netminecraft.packet.impl.play.C2SPlayKeepAlivePacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayKeepAlivePacket;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manual verification client (M7): joins the real ViaProxy like a vanilla 1.21.4
 * client, auto-answers keepalives/configuration finish and prints every chat line.
 * Script "join": sit in the lobby for 20s. Script "connect <host> <port>": then
 * connect through the GUI (address item → chat prompt) and /disconnect back
 * (hot switch round trip).
 */
public class VerifyClient {

    static {
        //Same ViaVersion static-init workaround as the integration tests: the first
        //GUI item decode triggers VersionedTypes.<clinit>, which deadlock-cycles with
        //StructuredDataKey.<clinit> and poisons the packet stream; touching
        //StructuredDataKey first resolves it.
        if (com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME == null) {
            throw new IllegalStateException("ViaVersion structured data keys not initialized");
        }
    }

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(1);

    private final BlockingQueue<Packet> received = new LinkedBlockingQueue<>();
    private volatile int windowId = 1;
    private Channel channel;
    private String lastScreenTitle = "";
    private boolean transferNoticeSeen = false;

    public static void main(final String[] args) throws Exception {
        final String host = args[0];
        final int port = Integer.parseInt(args[1]);
        final String name = args[2];
        final String script = args.length > 3 ? args[3] : "join";

        final VerifyClient client = new VerifyClient();
        if ("wildcard".equals(script) && args.length > 4) {
            client.handshakeHostOverride = args[4];
        }
        if ("newver".equals(script)) {
            //"1.21.11" client: the handshake claims a newer protocol than the lobby's
            //1.21.4, so ViaProxy's translation pipeline engages exactly like for the
            //public deployment; the config packets are sent raw with delays so the
            //proxy log pinpoints the packet that breaks the lobby decode
            client.protocolOverride = args.length > 4 ? Integer.parseInt(args[4]) : 774;
        }
        if ("watch17".equals(script) || "fresh17".equals(script)) {
            client.legacy17 = true;
        }
        client.connect(new InetSocketAddress(host, port));
        client.login(name, UUID.nameUUIDFromBytes(name.getBytes("UTF-8")));
        client.awaitLogin();
        if (client.legacy17) {
            //1.7 has no login-acknowledgement and no configuration phase: straight to play
            client.setState(ConnectionState.PLAY);
            if ("fresh17".equals(script)) {
                final long seconds = Long.parseLong(args[4]);
                System.out.println("[VERIFY] fresh 1.7 join via " + host + ":" + port + ", watching " + seconds + "s");
                final long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
                while (System.currentTimeMillis() < end) {
                    client.drainAndPrint(250);
                }
                System.out.println("[VERIFY] fresh17 done, active=" + client.channel.isActive());
                client.disconnect();
                System.exit(0);
            }
            Thread.sleep(2500);
            client.drainAndPrint(500);
            final String target = args[4] + ":" + args[5];
            System.out.println("[VERIFY] opening the address prompt (slot 10) and entering " + target + " through the GUI");
            client.clickContainer(10, 0);
            Thread.sleep(800);
            client.drainAndPrint(400);
            client.sendLegacyChat(target);
            final long seconds = Long.parseLong(args[6]);
            final long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
            while (System.currentTimeMillis() < end) {
                client.drainAndPrint(250);
            }
            System.out.println("[VERIFY] watch17 done, active=" + client.channel.isActive());
            client.disconnect();
            System.exit(0);
        }
        client.write(new C2SLoginAcknowledgedPacket());
        if ("newver".equals(script)) {
            client.runNewVerConfigSequence();
        }
        if ("zh".equals(script)) {
            //V6: the client settings (locale) right after the login acknowledgement,
            //like a vanilla client — the lobby picks the text language from them (F1.4)
            client.write(new dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket(
                    "zh_cn", 12, 0, true, 127, 1, false, true));
        }

        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(25);
        boolean playReached = false;
        while (System.currentTimeMillis() < deadline && !playReached) {
            final Packet packet = client.received.poll(100, TimeUnit.MILLISECONDS);
            if (packet instanceof S2CConfigFinishConfigurationPacket) {
                client.write(new C2SConfigFinishConfigurationPacket());
                client.setState(ConnectionState.PLAY);
            } else if (packet instanceof S2CPlayKeepAlivePacket keepAlive) {
                client.write(new C2SPlayKeepAlivePacket(keepAlive.id));
            } else if (packet instanceof net.raphimc.netminecraft.packet.impl.configuration.S2CConfigKeepAlivePacket configKeep) {
                client.write(new net.raphimc.netminecraft.packet.impl.configuration.C2SConfigKeepAlivePacket(configKeep.id));
            } else if (packet instanceof net.raphimc.netminecraft.packet.impl.configuration.S2CConfigSelectKnownPacksPacket) {
                client.write(new net.raphimc.netminecraft.packet.impl.configuration.C2SConfigSelectKnownPacksPacket(java.util.List.of()));
            } else if (packet instanceof net.raphimc.netminecraft.packet.impl.configuration.S2CConfigCustomPayloadPacket custom) {
                //echo the payload back only for minecraft:brand-like requests? No: just log it
                System.out.println("[VERIFY] config custom payload received, ignoring");
            } else if (packet instanceof net.raphimc.netminecraft.packet.UnknownPacket up && client.attraState() == ConnectionState.CONFIGURATION) {
                System.out.println("[VERIFY] config-state raw packet 0x" + Integer.toHexString(up.packetId) + " " + (up.data == null ? 0 : up.data.length) + "B");
            } else if (packet instanceof S2CSystemChatPacket) {
                playReached = true; //the welcome chat signals the play state
            }
        }
        System.out.println("[VERIFY] play state reached, active=" + client.channel.isActive());

        if ("connect".equals(script) && playReached) {
            Thread.sleep(1200);
            client.drainAndPrint(800);
            final String target = args[4].contains(":") ? "[" + args[4] + "]:" + args[5] : args[4] + ":" + args[5];
            client.guiConnect(target);
            Thread.sleep(6000);
            client.drainAndPrint(1500);
            System.out.println("[VERIFY] after the GUI connect: active=" + client.channel.isActive());

            client.write(new C2SChatCommandPacket("disconnect"));
            Thread.sleep(5000);
            client.drainAndPrint(4000);
            System.out.println("[VERIFY] after /disconnect: active=" + client.channel.isActive());
        } else if ("reconnect".equals(script) && playReached) {
            //join → GUI connect → then stay connected while the operator kills/restarts the
            //backend; every chat line is printed as evidence (auto reconnect chain, V3)
            Thread.sleep(1200);
            client.drainAndPrint(800);
            final String target = args[4].contains(":") ? "[" + args[4] + "]:" + args[5] : args[4] + ":" + args[5];
            client.guiConnect(target);
            final long totalSeconds = Long.parseLong(args[6]);
            final long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(totalSeconds);
            while (System.currentTimeMillis() < end) {
                client.drainAndPrint(250);
            }
            System.out.println("[VERIFY] after " + totalSeconds + "s: active=" + client.channel.isActive());
        } else if ("wildcard".equals(script)) {
            //V8: a wildcard handshake address must bypass the lobby (ViaProxy's native
            //direct connect); evidence: no lobby welcome, the proxy dials the target
            client.drainAndPrint(3000);
            System.out.println("[VERIFY] wildcard join done, active=" + client.channel.isActive());
        } else if ("transfer".equals(script) && playReached) {
            //V5: GUI-connect the vanilla backend, wait for the operator's RCON /transfer,
            //then exercise one confirmation-GUI path (follow slot 11 / stay slot 15 / close)
            Thread.sleep(1200);
            client.drainAndPrint(800);
            final String target = args[4].contains(":") ? "[" + args[4] + "]:" + args[5] : args[4] + ":" + args[5];
            client.guiConnect(target);
            Thread.sleep(6000);
            client.drainAndPrint(800);
            final String action = args[6];
            System.out.println("[VERIFY] waiting for the server-side /transfer (up to 120s), action=" + action);
            boolean confirmSeen = false;
            final long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(120);
            while (System.currentTimeMillis() < end && !confirmSeen) {
                client.drainAndPrint(250);
                confirmSeen = client.lastScreenTitle.toLowerCase().contains("transfer") || client.transferNoticeSeen;
            }
            if (!confirmSeen) {
                System.out.println("[VERIFY] no transfer confirmation screen appeared");
            } else {
                Thread.sleep(1500);
                switch (action) {
                    case "follow" -> client.clickContainer(11, 0);
                    case "stay" -> client.clickContainer(15, 0);
                    case "close" -> client.closeContainer();
                    default -> System.out.println("[VERIFY] unknown action " + action);
                }
                System.out.println("[VERIFY] action sent: " + action);
                Thread.sleep(6000);
                client.drainAndPrint(3000);
            }
            System.out.println("[VERIFY] after " + action + ": active=" + client.channel.isActive());
        } else if ("signedchat".equals(script) && playReached) {
            //Public-deployment regression: a real online-mode client's chat rides the
            //full signed layout with the 1.21.5 tail; the lobby must answer it (address
            //prompt reopened) instead of dying in the decode loop
            Thread.sleep(800);
            client.drainAndPrint(500);
            System.out.println("[VERIFY] clicking the address item (slot 11) to arm the chat listener");
            client.clickContainer(11, 0);
            Thread.sleep(500);
            client.drainAndPrint(400);
            System.out.println("[VERIFY] sending signed-shaped chat '192.168.1.99:25565'");
            client.writeRaw(0x07, body -> {
                PacketTypes.writeString(body, "192.168.1.99:25565");
                body.writeLong(System.currentTimeMillis());
                body.writeLong(12345L);
                body.writeBoolean(true);
                final byte[] signature = new byte[256];
                new java.util.Random(42).nextBytes(signature);
                body.writeBytes(signature); //fixed size; no length prefix
                PacketTypes.writeVarInt(body, 0);
                body.writeBytes(new byte[]{0x2a, 0, 8}); //fixed 20-bit acknowledgment
                body.writeByte(0);
            });
            Thread.sleep(2500);
            client.drainAndPrint(1500);
            System.out.println("[VERIFY] sending plain chat to reopen the GUI");
            client.write(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket("reopen", java.time.Instant.now(), 0L, null, 0, new java.util.BitSet()));
            Thread.sleep(1500);
            client.drainAndPrint(800);
            System.out.println("[VERIFY] signedchat done, active=" + client.channel.isActive());
        } else if ("versel".equals(script) && playReached) {
            //Version selector regression (public-deployment 3.4.14 finding): the anvil
            //item click must not crash the lobby (NoSuchFieldError on bedrockLatest)
            Thread.sleep(800);
            client.drainAndPrint(500);
            System.out.println("[VERIFY] clicking the version item (slot 12)");
            client.clickContainer(12, 0);
            Thread.sleep(1500);
            client.drainAndPrint(800);
            System.out.println("[VERIFY] picking a version (first slot)");
            client.clickContainer(0, 0);
            Thread.sleep(1500);
            client.drainAndPrint(800);
            System.out.println("[VERIFY] versel done, active=" + client.channel.isActive());
        } else if ("zh".equals(script) && playReached) {
            //V6: the join texts must be Chinese (config-state settings); then the M7
            //play-state language switch must apply to the NEXT command feedback
            client.drainAndPrint(2000);
            System.out.println("[VERIFY] switching language to en_us in the play state");
            client.write(new dev.connectplus.lobby.protocol.packets.config.C2SPlayClientInformationPacket(
                    "en_us", 12, 0, true, 127, 1, false, true));
            Thread.sleep(500);
            client.write(new C2SChatCommandPacket("disconnect extra"));
            client.drainAndPrint(2500);
            System.out.println("[VERIFY] after language switch: active=" + client.channel.isActive());
        } else {
            client.drainAndPrint(2000);
        }
        System.out.println("[VERIFY] done, active=" + client.channel.isActive());
        client.disconnect();
        System.exit(0);
    }

    private void drainAndPrint(final long ms) throws InterruptedException {
        final long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            final Packet packet = this.received.poll(50, TimeUnit.MILLISECONDS);
            if (packet == null) {
                continue;
            }
            if (packet instanceof S2CSystemChatPacket chat) {
                final String line = chat.message.asUnformattedString().replace('\n', ' ');
                if (line.contains("wants to transfer you") || line.contains("希望你转移")) {
                    this.transferNoticeSeen = true;
                }
                System.out.println("[CHAT] " + line);
            } else if (packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket open) {
                this.lastScreenTitle = open.title.asUnformattedString();
                System.out.println("[SCREEN] " + this.lastScreenTitle);
            } else if (packet instanceof S2CPlayKeepAlivePacket keepAlive) {
                this.write(new C2SPlayKeepAlivePacket(keepAlive.id));
            } else if (packet instanceof net.raphimc.netminecraft.packet.impl.configuration.S2CConfigKeepAlivePacket configKeep) {
                this.write(new net.raphimc.netminecraft.packet.impl.configuration.C2SConfigKeepAlivePacket(configKeep.id));
            } else if (packet instanceof net.raphimc.netminecraft.packet.impl.configuration.S2CConfigSelectKnownPacksPacket) {
                this.write(new net.raphimc.netminecraft.packet.impl.configuration.C2SConfigSelectKnownPacksPacket(java.util.List.of()));
            } else if (packet instanceof net.raphimc.netminecraft.packet.impl.play.S2CPlayStartConfigurationPacket) {
                this.write(new net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket());
                this.setState(ConnectionState.CONFIGURATION);
                System.out.println("[VERIFY] frontend configuration started");
            } else if (packet instanceof S2CConfigFinishConfigurationPacket) {
                this.write(new C2SConfigFinishConfigurationPacket());
                this.setState(ConnectionState.PLAY);
                System.out.println("[VERIFY] frontend configuration finished");
            } else if (packet instanceof net.raphimc.netminecraft.packet.UnknownPacket join17
                    && join17.packetId == 0x01 && join17.data != null && join17.data.length == 16) {
                System.out.println("[PKT] " + describe(packet));
                logJoin17(join17);
            } else {
                System.out.println("[PKT] " + describe(packet));
                if (packet instanceof net.raphimc.netminecraft.packet.UnknownPacket unknownPacket
                        && unknownPacket.data != null) {
                    System.out.println("[PKTDATA] " + new String(unknownPacket.data, java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("[^\\x20-\\x7e]", "."));
                }
            }
        }
    }

    private void connect(final InetSocketAddress address) {
        this.channel = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .handler(new ClientInitializer(this))
                .connect(address).syncUninterruptibly().channel();
        System.out.println("[VERIFY] tcp connected to " + address);
    }

    private String handshakeHostOverride;
    private boolean legacy17;
    private int protocolOverride = -1;

    private void login(final String name, final UUID uuid) {
        final InetSocketAddress remote = (InetSocketAddress) this.channel.remoteAddress();
        final String host = this.handshakeHostOverride != null ? this.handshakeHostOverride : remote.getHostString();
        final int protocol = this.legacy17 ? ProtocolVersion.v1_7_6.getVersion()
                : this.protocolOverride > 0 ? this.protocolOverride : ProtocolVersion.v1_21_4.getVersion();
        this.write(new C2SHandshakingClientIntentionPacket(protocol, host, remote.getPort(), IntendedState.LOGIN));
        this.write(new C2SLoginHelloPacket(name, null, null, null, uuid));
    }

    /**
     * Sends the configuration-state packets a real vanilla 1.21.11 client puts on
     * the wire (raw bodies, one per 1.5s with markers), then waits and prints what
     * comes back. ViaProxy translates them down to the lobby's 1.21.4 exactly like
     * for the public deployment.
     */
    private void runNewVerConfigSequence() throws Exception {
        //0x00 client information: 1.21.4 layout + the 1.21.2 particleStatus tail
        //(Via forwards the body untouched: no rewriter registered on 769..774)
        System.out.println("[NEWVER] sending client information (0x00)");
        this.writeRaw(0x00, body -> {
            PacketTypes.writeString(body, "zh_cn");
            body.writeByte(12); //view distance
            PacketTypes.writeVarInt(body, 0); //chat visibility
            body.writeBoolean(true); //chat colors
            body.writeByte(127); //displayed skin parts
            PacketTypes.writeVarInt(body, 1); //main hand
            body.writeBoolean(false); //text filtering
            body.writeBoolean(true); //allow server listings
            PacketTypes.writeVarInt(body, 0); //particle status (1.21.2+)
        });
        Thread.sleep(1500);
        //0x07 select known packs: one vanilla entry
        System.out.println("[NEWVER] sending select known packs (0x07)");
        this.writeRaw(0x07, body -> {
            PacketTypes.writeVarInt(body, 1);
            PacketTypes.writeString(body, "minecraft");
            PacketTypes.writeString(body, "core");
            PacketTypes.writeString(body, "1.21.11");
        });
        Thread.sleep(1500);
        //0x02 custom payload: the brand
        System.out.println("[NEWVER] sending brand custom payload (0x02)");
        this.writeRaw(0x02, body -> {
            PacketTypes.writeString(body, "minecraft:brand");
            PacketTypes.writeString(body, "vanilla");
        });
        Thread.sleep(2500);
        System.out.println("[NEWVER] sending finish configuration (0x03)");
        this.write(new C2SConfigFinishConfigurationPacket());
        this.setState(ConnectionState.PLAY);
        final long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            this.drainAndPrint(250);
        }
        System.out.println("[NEWVER] done, active=" + this.channel.isActive());
    }

    private void writeRaw(final int packetId, final java.util.function.Consumer<io.netty.buffer.ByteBuf> body) {
        final io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        body.accept(buf);
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        System.out.println("[NEWVER]   raw packet 0x" + Integer.toHexString(packetId) + " body " + bytes.length + "B");
        this.write(new net.raphimc.netminecraft.packet.UnknownPacket(packetId, bytes));
    }

    private void awaitLogin() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            final Packet packet = this.received.poll(25, TimeUnit.MILLISECONDS);
            if (packet == null) continue;
            if (packet instanceof S2CLoginGameProfilePacket) {
                return;
            }
            if (packet instanceof net.raphimc.netminecraft.packet.UnknownPacket up) {
                System.out.println("[VERIFY] login-state raw packet 0x" + Integer.toHexString(up.packetId)
                        + " " + (up.data == null ? 0 : up.data.length) + "B");
                if (up.data != null && up.data.length <= 128) {
                    try {
                        final io.netty.buffer.ByteBuf b = io.netty.buffer.Unpooled.wrappedBuffer(up.data);
                        System.out.println("[VERIFY]   raw body: " + net.raphimc.netminecraft.packet.PacketTypes.readString(b, 256));
                    } catch (Throwable ignored) {}
                }
            } else if (packet instanceof net.raphimc.netminecraft.packet.impl.login.S2CLoginDisconnectPacket disc) {
                System.out.println("[VERIFY] LOGIN DISCONNECT REASON: " + disc.reason.asUnformattedString());
            } else {
                System.out.println("[VERIFY] login-state packet " + packet.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("no login success within 60s");
    }

    private void write(final Packet packet) {
        this.channel.writeAndFlush(packet);
        if (packet instanceof C2SHandshakingClientIntentionPacket) {
            this.setState(ConnectionState.LOGIN);
        } else if (packet instanceof C2SLoginAcknowledgedPacket) {
            this.setState(ConnectionState.CONFIGURATION);
        } else if (packet instanceof C2SConfigFinishConfigurationPacket) {
            this.setState(ConnectionState.PLAY);
        }
    }

    ConnectionState attraState() {
        return this.channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().getConnectionState();
    }

    void setState(final ConnectionState state) {
        this.channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().setConnectionState(state);
    }

    /**
     * Sends a 1.7 chat packet (raw: 1.7 rides plain text chat for commands too).
     */
    private void sendLegacyChat(final String message) {
        final io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        net.raphimc.netminecraft.packet.PacketTypes.writeString(buf, message);
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        this.write(new net.raphimc.netminecraft.packet.UnknownPacket(0x01, bytes));
    }

    /**
     * Parses and prints a 1.7 JoinGame payload: int entityId, ubyte gamemode,
     * byte dimension, ubyte difficulty, ubyte maxPlayers, string levelType.
     */
    private static void logJoin17(final net.raphimc.netminecraft.packet.UnknownPacket u) {
        try {
            final io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(u.data);
            final int entityId = buf.readInt();
            final int gamemode = buf.readUnsignedByte();
            final int dimension = buf.readByte();
            final int difficulty = buf.readUnsignedByte();
            final int maxPlayers = buf.readUnsignedByte();
            final String levelType = net.raphimc.netminecraft.packet.PacketTypes.readString(buf, 64);
            System.out.println("[JOIN17] entityId=" + entityId + " gamemode=" + gamemode
                    + " dimension=" + dimension + " difficulty=" + difficulty
                    + " maxPlayers=" + maxPlayers + " levelType=" + levelType);
        } catch (final Throwable t) {
            System.out.println("[JOIN17] parse failed: " + t);
        }
    }

    private static String describe(final Packet packet) {
        if (packet instanceof net.raphimc.netminecraft.packet.UnknownPacket u) {
            return "UnknownPacket(0x" + Integer.toHexString(u.packetId) + ", " + (u.data == null ? 0 : u.data.length) + "B)";
        }
        return packet.getClass().getSimpleName();
    }

    private void disconnect() {
        if (this.channel != null) this.channel.close().syncUninterruptibly();
    }

    private void clickContainer(final int slot, final int button) {
        this.write(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket(
                this.windowId, 0, slot, button, 0, new java.util.HashMap<>(), com.viaversion.viaversion.api.minecraft.item.StructuredItem.empty()));
    }

    private void closeContainer() {
        this.write(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket(this.windowId));
    }

    /**
     * Connects to a target through the GUI (the /connect command no longer
     * exists): clicks the address item (main-menu slot 10), waits for the chat
     * prompt and enters the address as chat. Works for modern and 1.7 clients
     * alike (the 1.7 chat rides the raw legacy text packet).
     */
    private void guiConnect(final String address) throws InterruptedException {
        System.out.println("[VERIFY] opening the address prompt (slot 10) and entering " + address + " through the GUI");
        this.clickContainer(10, 0);
        Thread.sleep(800);
        this.drainAndPrint(400);
        if (this.legacy17) {
            this.sendLegacyChat(address);
        } else {
            this.write(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket(address,
                    java.time.Instant.now(), 0L, null, 0, new java.util.BitSet()));
        }
        System.out.println("[VERIFY] address entered, waiting for the switch");
        this.drainAndPrint(800);
        this.clickContainer(16, 0);
    }

    private static final class ClientInitializer extends MinecraftChannelInitializer {
        private final VerifyClient client;

        private ClientInitializer(final VerifyClient client) {
            super(() -> new Responder(client));
            this.client = client;
        }

        @Override
        protected void initChannel(final Channel channel) {
            super.initChannel(channel);
            //The 1.7 mode speaks the raw legacy protocol: NetMinecraft's version-aware
            //default registry decodes the packets the proxy sends down to 1.7.10
            channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(this.client.legacy17
                    ? new net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry(true, com.viaversion.viaversion.api.protocol.version.ProtocolVersion.v1_7_6.getVersion())
                    : new dev.connectplus.lobby.protocol.LobbyPacketRegistry(true));
        }
    }

    private static final class Responder extends ChannelInboundHandlerAdapter {
        private final VerifyClient client;

        private Responder(final VerifyClient client) {
            this.client = client;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof net.raphimc.netminecraft.packet.impl.login.S2CLoginCompressionPacket compression) {
                //Install the compressor the moment the threshold packet is decoded, so every
                //later frame inflates correctly (this runs on the event loop before the packet
                //codec decodes any further bytes). Instances with compression enabled
                //(3.4.14 defaults to threshold 256) send nothing else until this is in place.
                System.out.println("[VERIFY] login compression threshold=" + compression.compressionThreshold + ", installing compressor");
                ctx.channel().attr(MCPipeline.COMPRESSION_THRESHOLD_ATTRIBUTE_KEY).set(compression.compressionThreshold);
                if (ctx.pipeline().get(MCPipeline.COMPRESSION_HANDLER_NAME) == null) {
                    ctx.pipeline().addBefore(MCPipeline.PACKET_CODEC_HANDLER_NAME, MCPipeline.COMPRESSION_HANDLER_NAME,
                            new net.raphimc.netminecraft.netty.codec.PacketCompressor());
                }
            }
            if (msg instanceof Packet packet) {
                if (packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket open) {
                    this.client.windowId = open.id;
                } else if (packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerClosePacket close
                        && close.id == this.client.windowId) {
                    this.client.windowId = 0;
                }
                this.client.received.add(packet);
            }
        }
    }
}
