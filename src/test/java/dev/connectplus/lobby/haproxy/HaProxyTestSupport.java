package dev.connectplus.lobby.haproxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;

/**
 * Test helpers shared by the HAProxy unit tests: encodes an {@link HAProxyMessage}
 * into the raw proxy protocol v2 bytes a real client would send.
 */
final class HaProxyTestSupport {

    private HaProxyTestSupport() {
    }

    static ByteBuf encode(final HAProxyMessage message) {
        final EmbeddedChannel encoder = new EmbeddedChannel(HAProxyMessageEncoder.INSTANCE);
        try {
            encoder.writeOutbound(message);
            return encoder.readOutbound();
        } finally {
            encoder.finishAndReleaseAll();
        }
    }
}
