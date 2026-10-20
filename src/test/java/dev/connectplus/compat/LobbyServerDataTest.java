package dev.connectplus.compat;

import com.google.common.net.HostAndPort;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.config.CPConfig;
import dev.connectplus.switching.SwitchEngine;
import dev.connectplus.switching.SwitchJob;
import dev.connectplus.switching.SwitchSuppressionHandler;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.netty.connection.NetClient;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.ProxySessionCreationEvent;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class LobbyServerDataTest {
    private static final String MOTD = "{\"text\":\"Backend\"}";
    private static final byte[] ICON = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1cAAAAASUVORK5CYII=");
    private EmbeddedChannel client, backend;
    private ProxyConnection connection;
    private String previousMode, previousIconPath;
    private int version;

    @BeforeAll static void boot() { ViaProxyTestConfig.init(); }

    @BeforeEach void saveConfig() {
        previousMode = CPConfig.mode;
        previousIconPath = ViaProxy.getConfig().getCustomFaviconPath();
        CPConfig.mode = "lobby";
        ViaProxy.getConfig().setCustomFaviconPath("");
    }

    @AfterEach void close() {
        CPConfig.mode = previousMode;
        ViaProxy.getConfig().setCustomFaviconPath(previousIconPath);
        if (client != null) client.finishAndReleaseAll();
        if (backend != null) backend.finishAndReleaseAll();
    }

    private void setup(int version) throws Exception {
        this.version = version;
        client = new EmbeddedChannel();
        backend = new EmbeddedChannel();
        client.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(false, version));
        backend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, version));
        var original = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
        var event = new ProxySessionCreationEvent<>(original, false);
        new SwitchableSessionInstaller(new SwitchEngine(() -> null)).onProxySessionCreation(event);
        connection = event.getProxySession();
        var channelFuture = NetClient.class.getDeclaredField("channelFuture");
        channelFuture.setAccessible(true);
        channelFuture.set(connection, backend.newSucceededFuture());
        connection.setClientVersion(ProtocolVersion.getProtocol(version));
        connection.setClientHandshakeAddress(HostAndPort.fromParts("lobby.example.com", 25565));
        connection.setC2pConnectionState(ConnectionState.PLAY);
        connection.setP2sConnectionState(ConnectionState.PLAY);
    }

    private UnknownPacket serverData(boolean withIcon) {
        ByteBuf bytes = Unpooled.buffer();
        try {
            if (version < 762) bytes.writeBoolean(true); // Optional MOTD until 1.19.3.
            if (version >= 765) {
                bytes.writeByte(8).writeShort(7).writeBytes(new byte[]{'B', 'a', 'c', 'k', 'e', 'n', 'd'});
            } else PacketTypes.writeString(bytes, MOTD);
            bytes.writeBoolean(withIcon);
            if (withIcon) {
                if (version >= 762) {
                    PacketTypes.writeVarInt(bytes, ICON.length);
                    bytes.writeBytes(ICON);
                } else PacketTypes.writeString(bytes, "data:image/png;base64," + Base64.getEncoder().encodeToString(ICON));
            }
            if (version <= 760) bytes.writeBoolean(true); // Chat preview.
            if (version >= 760 && version < 766) bytes.writeBoolean(true); // Secure chat.
            return new UnknownPacket(MCPackets.S2C_SERVER_DATA.getId(version), ByteBufUtil.getBytes(bytes));
        } finally { bytes.release(); }
    }

    private UnknownPacket forward(UnknownPacket packet) throws Exception {
        var listeners = new ArrayList<ChannelFutureListener>();
        for (var handler : connection.getPacketHandlers()) {
            if (!handler.handleP2S(packet, listeners)) return client.readOutbound();
        }
        client.writeAndFlush(packet).addListeners(listeners.toArray(new ChannelFutureListener[0]));
        return client.readOutbound();
    }

    private void assertServerData(UnknownPacket packet, byte[] expectedIcon) {
        assertNotNull(packet);
        assertEquals(MCPackets.S2C_SERVER_DATA.getId(version), packet.packetId);
        ByteBuf bytes = Unpooled.wrappedBuffer(packet.data);
        try {
            if (version < 762) assertTrue(bytes.readBoolean());
            if (version >= 765) {
                assertEquals(8, bytes.readUnsignedByte());
                assertEquals(7, bytes.readUnsignedShort());
                byte[] text = new byte[7];
                bytes.readBytes(text);
                assertArrayEquals(new byte[]{'B', 'a', 'c', 'k', 'e', 'n', 'd'}, text);
            } else assertEquals(MOTD, PacketTypes.readString(bytes, 32767));
            assertEquals(expectedIcon != null, bytes.readBoolean(), "The server list entry must use the proxy's icon");
            if (expectedIcon != null) {
                if (version >= 762) {
                    byte[] icon = new byte[PacketTypes.readVarInt(bytes)];
                    bytes.readBytes(icon);
                    assertArrayEquals(expectedIcon, icon);
                } else assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(expectedIcon),
                        PacketTypes.readString(bytes, 32767));
            }
            if (version <= 760) assertTrue(bytes.readBoolean(), "Chat preview must be preserved");
            if (version >= 760 && version < 766) assertTrue(bytes.readBoolean(), "Secure chat must be preserved");
            assertFalse(bytes.isReadable(), "The rest of the packet must remain intact");
        } finally { bytes.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {759, 760, 761, 762, 764, 765, 766, 769, 774, 777})
    void backendIconsCannotOverwriteTheLobbyServerListEntry(int version) throws Exception {
        setup(version);
        assertServerData(forward(serverData(true)), null);
        assertServerData(forward(serverData(false)), null);
        assertServerData(forward(serverData(true)), null); // A later backend cannot bring it back.
    }

    @ParameterizedTest @ValueSource(ints = {759, 769})
    void configuredProxyIconReplacesTheBackendIcon(int version, @TempDir Path temp) throws Exception {
        Path icon = temp.resolve("server-icon.png");
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        assertTrue(ImageIO.write(image, "PNG", icon.toFile()));
        byte[] proxyIcon = Files.readAllBytes(icon);
        ViaProxy.getConfig().setCustomFaviconPath(icon.toString());
        setup(version);
        assertServerData(forward(serverData(true)), proxyIcon);
        assertServerData(forward(serverData(false)), proxyIcon);
    }

    @ParameterizedTest @ValueSource(ints = {759, 769})
    void switchedBackendIconsAreClearedThroughTheRealHandlerChain(int version) throws Exception {
        setup(version);
        var suppression = connection.getPacketHandler(SwitchSuppressionHandler.class);
        assertNotNull(suppression);
        for (int target = 0; target < 2; target++) {
            var job = SwitchJob.starting(new SwitchJob.Target("backend.example.com:25565",
                    connection.getClientVersion(), UUID.randomUUID(), "Player", false, 1));
            assertSame(job, suppression.begin(job));
            assertTrue(job.markForward());
            assertServerData(forward(serverData(true)), null);
        }
    }

    @Test void absentLegacyMotdAndDisabledChatPreviewArePreserved() throws Exception {
        setup(759);
        ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeBoolean(false).writeBoolean(true);
            PacketTypes.writeString(bytes, "data:image/png;base64," + Base64.getEncoder().encodeToString(ICON));
            bytes.writeBoolean(false);
            var packet = new UnknownPacket(MCPackets.S2C_SERVER_DATA.getId(version), ByteBufUtil.getBytes(bytes));
            assertArrayEquals(new byte[]{0, 0, 0}, forward(packet).data);
        } finally { bytes.release(); }
    }

    @Test void truncatedServerDataIsNeverPartiallyRewritten() throws Exception {
        setup(769);
        var packet = new UnknownPacket(MCPackets.S2C_SERVER_DATA.getId(version), new byte[]{8, 0});
        assertArrayEquals(new byte[]{8, 0}, forward(packet).data);
    }

    @Test void missingCustomIconCannotFallBackToTheBackendIcon(@TempDir Path temp) throws Exception {
        ViaProxy.getConfig().setCustomFaviconPath(temp.resolve("missing.png").toString());
        setup(769);
        assertServerData(forward(serverData(true)), null);
    }

    @Test void proxyModeRetainsBackendIcons() throws Exception {
        CPConfig.mode = "proxy";
        setup(769);
        assertServerData(forward(serverData(true)), ICON);
    }

    @Test void wildcardDirectConnectionsRetainBackendIcons() throws Exception {
        setup(769);
        connection.setClientHandshakeAddress(HostAndPort.fromParts("backend.example.com.viaproxy.localhost", 25565));
        assertServerData(forward(serverData(true)), ICON);
    }

    @Test void configurationPacketWithTheSameIdIsUntouched() throws Exception {
        setup(769);
        connection.setP2sConnectionState(ConnectionState.CONFIGURATION);
        var packet = new UnknownPacket(MCPackets.S2C_SERVER_DATA.getId(version), new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, forward(packet).data);
    }

    @Test void unrelatedPlayPacketsAreUntouched() throws Exception {
        setup(769);
        var packet = new UnknownPacket(MCPackets.S2C_SERVER_DATA.getId(version) + 1, new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, forward(packet).data);
    }
}
