package dev.connectplus.lobby.haproxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

import java.nio.charset.StandardCharsets;

/**
 * Detects whether a lobby connection starts with an HAProxy proxy protocol preamble
 * and only then installs {@link HAProxyMessageDecoder} + {@link HAProxyHandler}.
 *
 * <p>This detection step is a deviation from MiniConnect's LobbyServerInitializer
 * (MIT, Copyright (c) 2024 Lenni0451), which installs the HAProxy decoder
 * unconditionally: netty's decoder treats every connection as HAProxy v1 unless the
 * v2 binary prefix matches and would stall bare connections while buffering for a
 * line terminator. The lobby must stay joinable without a preamble (tests and
 * diagnostics), so connections whose start matches neither the v2 binary prefix nor
 * the v1 "PROXY " prefix are forwarded downstream untouched.</p>
 */
public class HAProxyDetectHandler extends ChannelInboundHandlerAdapter {

    private static final byte[] V2_BINARY_PREFIX = new byte[]{0x0D, 0x0A, 0x0D, 0x0A, 0x00};
    private static final byte[] V1_TEXT_PREFIX = "PROXY ".getBytes(StandardCharsets.US_ASCII);

    private ByteBuf pending;

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (!(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (this.pending == null) {
            this.pending = Unpooled.buffer();
        }
        this.pending.writeBytes(in);
        in.release();
        this.decide(ctx);
    }

    private void decide(final ChannelHandlerContext ctx) {
        final ByteBuf buffered = this.pending;
        final int readable = buffered.readableBytes();
        if (readable == 0) {
            return;
        }
        final byte first = buffered.getByte(0);
        if (first != V2_BINARY_PREFIX[0] && first != V1_TEXT_PREFIX[0]) {
            this.finish(ctx, false);
            return;
        }
        if (first == V2_BINARY_PREFIX[0] && readable >= V2_BINARY_PREFIX.length) {
            this.finish(ctx, startsWith(buffered, V2_BINARY_PREFIX));
            return;
        }
        if (first == V1_TEXT_PREFIX[0] && readable >= V1_TEXT_PREFIX.length) {
            this.finish(ctx, startsWith(buffered, V1_TEXT_PREFIX));
            return;
        }
        // Not enough bytes to tell whether this is a preamble; keep buffering.
    }

    private void finish(final ChannelHandlerContext ctx, final boolean haProxy) {
        final ByteBuf buffered = this.takeBuffer();
        final ChannelPipeline pipeline = ctx.pipeline();
        if (haProxy) {
            pipeline.addBefore(ctx.name(), "haproxy-decoder", new HAProxyMessageDecoder());
            pipeline.addAfter(ctx.name(), "haproxy-handler", new HAProxyHandler());
        }
        pipeline.remove(this);
        if (haProxy) {
            pipeline.fireChannelRead(buffered);
        } else {
            ctx.fireChannelRead(buffered);
        }
    }

    private ByteBuf takeBuffer() {
        final ByteBuf buffered = this.pending;
        this.pending = null;
        return buffered;
    }

    private static boolean startsWith(final ByteBuf buf, final byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (buf.getByte(i) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        if (this.pending != null) {
            this.pending.release();
            this.pending = null;
        }
    }
}
