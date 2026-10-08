package dev.connectplus.lobby.haproxy;

import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.lobby.model.HandshakeData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyTLV;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HAProxyDetectHandlerTest {

    @Test
    void passesBareConnectionThroughUntouched() {
        final EmbeddedChannel channel = new EmbeddedChannel(new HAProxyDetectHandler());
        final byte[] mcHandshake = new byte[]{0x0F, 0x00, 0x07, 0x01, 0x05, 0x06, 0x05, 0x7F, 0x00, 0x00, 0x01, 0x63, (byte) 0xDD, 0x02};

        channel.writeInbound(Unpooled.wrappedBuffer(mcHandshake));

        assertEquals(1, channel.inboundMessages().size(), "Bare bytes must be forwarded downstream");
        assertNull(channel.pipeline().get(HAProxyDetectHandler.class), "Detector must remove itself for bare connections");
        channel.finishAndReleaseAll();
    }

    @Test
    void passesBareConnectionThroughWhenSplitAcrossFragments() {
        final EmbeddedChannel channel = new EmbeddedChannel(new HAProxyDetectHandler());

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x0D, 0x00, 0x07})); // starts like a MC packet, not decidable yet
        assertTrue(channel.inboundMessages().isEmpty(), "Ambiguous start must be buffered");
        assertNotNull(channel.pipeline().get(HAProxyDetectHandler.class));

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x01, 0x05, 0x06, 0x05, 0x7F, 0x00, 0x00, 0x01, 0x63, (byte) 0xDD, 0x02}));
        assertEquals(1, channel.inboundMessages().size(), "Buffered bytes must be forwarded once the start is known to be bare");
        assertNull(channel.pipeline().get(HAProxyDetectHandler.class));
        channel.finishAndReleaseAll();
    }

    @Test
    void processesV2PreambleEndToEnd() throws Exception {
        final ByteBuf handshakeBuf = Unpooled.buffer();
        new HandshakeData("lobbytest.example.org", 25565, com.viaversion.viaversion.api.protocol.version.ProtocolVersion.v1_21_4).write(handshakeBuf);
        final HAProxyMessage message = new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "203.0.113.7", "203.0.113.7", 40000, 25565,
                List.of(new HAProxyTLV((byte) 0xE0, handshakeBuf)));
        final EmbeddedChannel channel = new EmbeddedChannel(new HAProxyDetectHandler());

        channel.writeInbound(HaProxyTestSupport.encode(message));

        assertNull(channel.pipeline().get(HAProxyDetectHandler.class), "Detector must remove itself after installing the decoder");
        final InetSocketAddress remote = (InetSocketAddress) channel.remoteAddress();
        assertEquals(InetAddress.getByName("203.0.113.7"), remote.getAddress(), "Handler must have rewritten the remoteAddress");
        assertEquals(40000, remote.getPort());
        assertNotNull(channel.attr(CPAttributeKeys.HANDSHAKE_DATA).get(), "Handler must have stored the 0xE0 TLV");
        channel.finishAndReleaseAll();
    }

    @Test
    void processesV1PreambleEndToEnd() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel(new HAProxyDetectHandler());

        channel.writeInbound(Unpooled.copiedBuffer("PROXY TCP4 198.51.100.9 203.0.113.7 50000 25565\r\n".getBytes()));

        assertNull(channel.pipeline().get(HAProxyDetectHandler.class), "Detector must remove itself after installing the decoder");
        final InetSocketAddress remote = (InetSocketAddress) channel.remoteAddress();
        assertEquals(InetAddress.getByName("198.51.100.9"), remote.getAddress());
        assertEquals(50000, remote.getPort());
        channel.finishAndReleaseAll();
    }
}
