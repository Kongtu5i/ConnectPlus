package dev.connectplus.lobby.haproxy;

import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.lobby.model.HandshakeData;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyTLV;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Processes the HAProxy proxy protocol preamble on lobby connections: rewrites the
 * channel remoteAddress to the real client address and stores the handshake information
 * from the 0xE0 TLV in the {@link CPAttributeKeys#HANDSHAKE_DATA} channel attribute and
 * the switch link id from the 0xE1 TLV in the {@link CPAttributeKeys#LOBBY_LINK_ID}
 * channel attribute.
 *
 * <p>Derived from MiniConnect's HAProxyHandler (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: connections without a 0xE0 TLV are tolerated (bare lobby
 * connections are allowed for tests and diagnostics; the MC handshake packet fields
 * serve as fallback) and the remoteAddress rewrite uses plain reflection on
 * {@link AbstractChannel} instead of RStream.</p>
 */
public class HAProxyHandler extends SimpleChannelInboundHandler<HAProxyMessage> {

    private static final byte HANDSHAKE_DATA_TLV_TYPE = (byte) 0xE0;
    private static final byte LINK_ID_TLV_TYPE = (byte) 0xE1;
    private static final Field REMOTE_ADDRESS_FIELD = remoteAddressField();

    private static Field remoteAddressField() {
        try {
            final Field field = AbstractChannel.class.getDeclaredField("remoteAddress");
            field.setAccessible(true);
            return field;
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final HAProxyMessage message) {
        if (message.command() != HAProxyCommand.PROXY) {
            throw new UnsupportedOperationException("Unsupported HAProxy command: " + message.command());
        }
        if (message.sourceAddress() != null) {
            rewriteRemoteAddress(ctx.channel(), new InetSocketAddress(message.sourceAddress(), message.sourcePort()));
        }
        for (final HAProxyTLV tlv : message.tlvs()) {
            if (tlv.typeByteValue() == HANDSHAKE_DATA_TLV_TYPE) {
                ctx.channel().attr(CPAttributeKeys.HANDSHAKE_DATA).set(HandshakeData.read(tlv.content()));
            } else if (tlv.typeByteValue() == LINK_ID_TLV_TYPE) {
                final UUID linkId = readLinkId(tlv.content());
                if (linkId != null) {
                    ctx.channel().attr(CPAttributeKeys.LOBBY_LINK_ID).set(linkId);
                }
            }
        }
        ctx.pipeline().remove(this);
    }

    /**
     * Reads the 16-byte switch link id from the 0xE1 TLV; a malformed payload is
     * ignored (the connection still works, it just cannot hot switch).
     */
    private static UUID readLinkId(final io.netty.buffer.ByteBuf content) {
        if (content.readableBytes() != 16) {
            return null;
        }
        return new UUID(content.readLong(), content.readLong());
    }

    private static void rewriteRemoteAddress(final Channel channel, final InetSocketAddress address) {
        if (!(channel instanceof AbstractChannel)) {
            return;
        }
        try {
            REMOTE_ADDRESS_FIELD.set(channel, address);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("Failed to rewrite channel remoteAddress to " + address, e);
        }
    }
}
