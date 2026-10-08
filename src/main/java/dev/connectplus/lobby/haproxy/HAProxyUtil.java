package dev.connectplus.lobby.haproxy;

import com.google.common.net.HostAndPort;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.lobby.model.HandshakeData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.haproxy.HAProxyTLV;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the HAProxy v2 message forwarded to the lobby for a proxied player,
 * carrying the original handshake information in the 0xE0 TLV and the switch
 * link id in the 0xE1 TLV (LobbyLink).
 *
 * <p>Derived from MiniConnect's HAProxyUtil (MIT, Copyright (c) 2024 Lenni0451).</p>
 */
public final class HAProxyUtil {

    private static final byte HANDSHAKE_DATA_TLV_TYPE = (byte) 0xE0;
    private static final byte LINK_ID_TLV_TYPE = (byte) 0xE1;

    private HAProxyUtil() {
    }

    /**
     * Builds the HAProxy v2 message. {@code linkId}, when present, travels in the
     * application-specific 0xE1 TLV so the lobby can bind its accepted channel to the
     * p2s side's proxy connection (LobbyLink) without any address-pairing assumption.
     */
    public static HAProxyMessage createMessage(final Channel sourceChannel, final Channel targetChannel, final HostAndPort handshakeAddress, final ProtocolVersion clientVersion, @javax.annotation.Nullable final UUID linkId) {
        final List<HAProxyTLV> tlvs = new ArrayList<>();
        final ByteBuf handshakeBuf = Unpooled.buffer();
        new HandshakeData(handshakeAddress.getHost(), handshakeAddress.getPort(), clientVersion).write(handshakeBuf);
        tlvs.add(new HAProxyTLV(HANDSHAKE_DATA_TLV_TYPE, handshakeBuf));
        if (linkId != null) {
            final ByteBuf linkBuf = Unpooled.buffer(16, 16);
            linkBuf.writeLong(linkId.getMostSignificantBits());
            linkBuf.writeLong(linkId.getLeastSignificantBits());
            tlvs.add(new HAProxyTLV(LINK_ID_TLV_TYPE, linkBuf));
        }

        if (sourceChannel.remoteAddress() instanceof InetSocketAddress sourceAddress && targetChannel.remoteAddress() instanceof InetSocketAddress targetAddress) {
            final HAProxyProxiedProtocol protocol = sourceAddress.getAddress() instanceof Inet4Address ? HAProxyProxiedProtocol.TCP4 : HAProxyProxiedProtocol.TCP6;
            final String sourceAddressString = sourceAddress.getAddress().getHostAddress();

            // The target address is set to the source address to prevent issues with IPv4 and IPv6 mismatches.
            // HAProxy requires both addresses to be of the same type, which we can't guarantee here.
            // The target address is never used, so it is safe to set it to the source address and prevent issues.
            return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, protocol, sourceAddressString, sourceAddressString, sourceAddress.getPort(), targetAddress.getPort(), tlvs);
        } else if (targetChannel.remoteAddress() instanceof DomainSocketAddress targetAddress) {
            return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, HAProxyProxiedProtocol.UNIX_STREAM, "", targetAddress.path(), 0, 0, tlvs);
        } else {
            throw new IllegalArgumentException("Unsupported address type: " + targetChannel.remoteAddress().getClass().getName());
        }
    }
}
