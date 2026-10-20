package dev.connectplus.compat;

import com.viaversion.viaversion.api.protocol.version.VersionType;
import com.viaversion.viaversion.api.type.Types;
import dev.connectplus.CoreMain;
import dev.connectplus.config.CPConfig;
import dev.connectplus.routing.ConnectionRouter;
import dev.connectplus.routing.RouteDecision;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Keeps backend play packets from changing the saved icon of the lobby's server entry. */
final class LobbyServerDataHandler extends PacketHandler {
    private String loadedIconPath;
    private byte[] proxyIcon;

    LobbyServerDataHandler(final ProxyConnection connection) {
        super(connection);
    }

    @Override
    public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
        final var protocol = this.proxyConnection.getClientVersion();
        if (this.proxyConnection.getP2sConnectionState() != ConnectionState.PLAY || protocol == null
                || protocol.getVersionType() != VersionType.RELEASE || protocol.isSnapshot()
                // Server Data starts in 1.19; future layouts need an explicit audit.
                || protocol.getVersion() < 759 || protocol.getVersion() > 777
                || !(packet instanceof UnknownPacket raw)
                || raw.packetId != MCPackets.S2C_SERVER_DATA.getId(protocol.getVersion())) return true;
        final var handshake = this.proxyConnection.getClientHandshakeAddress();
        if (ConnectionRouter.route(handshake == null ? "" : handshake.getHost(), CPConfig.mode) != RouteDecision.LOBBY) return true;

        final ByteBuf input = Unpooled.wrappedBuffer(raw.data);
        final ByteBuf output = Unpooled.buffer();
        try {
            final int version = protocol.getVersion();
            if (version >= 765) Types.TAG.read(input); // Network NBT component since 1.20.3.
            else if (version >= 762 || input.readBoolean()) skipField(input); // JSON MOTD.
            final int iconStart = input.readerIndex();
            if (input.readBoolean()) skipField(input); // Base64 string before 1.19.4, PNG bytes since then.
            final int tailStart = input.readerIndex();
            final int tailLength = version == 760 ? 2 : version < 766 ? 1 : 0;
            if (input.readableBytes() != tailLength) throw new IllegalArgumentException("Unexpected Server Data tail");

            final byte[] icon = this.proxyIcon();
            output.writeBytes(raw.data, 0, iconStart);
            output.writeBoolean(icon != null);
            if (icon != null) {
                if (version >= 762) {
                    PacketTypes.writeVarInt(output, icon.length);
                    output.writeBytes(icon);
                } else PacketTypes.writeString(output, "data:image/png;base64," + Base64.getEncoder().encodeToString(icon));
            }
            // Preserve the original MOTD, chat preview and secure-chat flags exactly.
            output.writeBytes(raw.data, tailStart, input.readableBytes());
            raw.data = ByteBufUtil.getBytes(output);
        } catch (final RuntimeException error) {
            CoreMain.logger().warn("Could not rewrite lobby Server Data for client v{}: {}", protocol.getVersion(), error.toString());
        } finally {
            input.release();
            output.release();
        }
        return true;
    }

    private byte[] proxyIcon() {
        final String path = ViaProxy.getConfig().getCustomFaviconPath();
        if (path == null || path.isBlank()) return null;
        if (!path.equals(this.loadedIconPath)) {
            this.loadedIconPath = path;
            this.proxyIcon = null;
            try {
                final Path configuredPath = Path.of(path);
                this.proxyIcon = Files.readAllBytes(configuredPath.isAbsolute()
                        ? configuredPath : ViaProxy.getCwd().toPath().resolve(configuredPath));
            } catch (final IOException | RuntimeException error) {
                CoreMain.logger().warn("Could not load the proxy favicon for the lobby: {}", error.toString());
            }
        }
        return this.proxyIcon;
    }

    private static void skipField(final ByteBuf bytes) {
        final int length = PacketTypes.readVarInt(bytes);
        if (length < 0 || length > bytes.readableBytes()) throw new IllegalArgumentException("Invalid Server Data field length");
        bytes.skipBytes(length);
    }
}
