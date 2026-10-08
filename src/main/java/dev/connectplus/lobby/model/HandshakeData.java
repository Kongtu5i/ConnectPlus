package dev.connectplus.lobby.model;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * The original client handshake information forwarded to the lobby through the
 * HAProxy v2 0xE0 TLV: the handshake host/port and the client's real protocol version.
 *
 * <p>Derived from MiniConnect's HandshakeData (MIT, Copyright (c) 2024 Lenni0451).</p>
 */
public record HandshakeData(String host, int port, ProtocolVersion clientVersion) {

    public static HandshakeData read(final ByteBuf buf) {
        final String host = PacketTypes.readString(buf, Short.MAX_VALUE);
        final int port = buf.readUnsignedShort();
        final ProtocolVersion clientVersion = ProtocolVersion.getProtocol(buf.readInt());
        return new HandshakeData(host, port, clientVersion);
    }

    public void write(final ByteBuf buf) {
        PacketTypes.writeString(buf, this.host);
        buf.writeShort(this.port);
        buf.writeInt(this.clientVersion.getOriginalVersion());
    }
}
