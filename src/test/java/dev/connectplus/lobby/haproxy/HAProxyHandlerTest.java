package dev.connectplus.lobby.haproxy;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.lobby.model.HandshakeData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyTLV;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HAProxyHandlerTest {

    private EmbeddedChannel channel;

    @AfterEach
    void finishChannel() {
        if (this.channel != null) {
            this.channel.finishAndReleaseAll();
            this.channel = null;
        }
    }

    @Test
    void rewritesRemoteAddressAndStoresHandshakeData() throws Exception {
        final ByteBuf handshakeBuf = Unpooled.buffer();
        new HandshakeData("lobbytest.example.org", 25565, ProtocolVersion.v1_21_4).write(handshakeBuf);
        final HAProxyMessage message = new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "203.0.113.7", "203.0.113.7", 40000, 25565,
                List.of(new HAProxyTLV((byte) 0xE0, handshakeBuf)));

        this.channel = new EmbeddedChannel(new HAProxyMessageDecoder(), new HAProxyHandler());
        this.channel.writeInbound(HaProxyTestSupport.encode(message));

        final InetSocketAddress remote = (InetSocketAddress) this.channel.remoteAddress();
        assertEquals(InetAddress.getByName("203.0.113.7"), remote.getAddress());
        assertEquals(40000, remote.getPort());
        final HandshakeData stored = this.channel.attr(CPAttributeKeys.HANDSHAKE_DATA).get();
        assertEquals("lobbytest.example.org", stored.host());
        assertEquals(25565, stored.port());
        assertSame(ProtocolVersion.v1_21_4, stored.clientVersion());
        assertNull(this.channel.pipeline().get(HAProxyHandler.class), "Handler must remove itself after processing");
    }

    @Test
    void toleratesMissingHandshakeDataTlv() throws Exception {
        final HAProxyMessage message = new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "198.51.100.9", "198.51.100.9", 50000, 25565);

        this.channel = new EmbeddedChannel(new HAProxyMessageDecoder(), new HAProxyHandler());
        this.channel.writeInbound(HaProxyTestSupport.encode(message));

        assertNull(this.channel.attr(CPAttributeKeys.HANDSHAKE_DATA).get(), "No TLV must leave the attribute unset");
        final InetSocketAddress remote = (InetSocketAddress) this.channel.remoteAddress();
        assertEquals(InetAddress.getByName("198.51.100.9"), remote.getAddress());
        assertEquals(50000, remote.getPort());
        assertNull(this.channel.pipeline().get(HAProxyHandler.class));
    }

    @Test
    void storesLinkIdFromE1Tlv() throws Exception {
        final UUID linkId = UUID.randomUUID();
        final ByteBuf linkBuf = Unpooled.buffer(16, 16);
        linkBuf.writeLong(linkId.getMostSignificantBits());
        linkBuf.writeLong(linkId.getLeastSignificantBits());
        final ByteBuf handshakeBuf = Unpooled.buffer();
        new HandshakeData("lobbytest.example.org", 25565, ProtocolVersion.v1_21_4).write(handshakeBuf);
        final HAProxyMessage message = new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "203.0.113.7", "203.0.113.7", 40000, 25565,
                List.of(new HAProxyTLV((byte) 0xE0, handshakeBuf), new HAProxyTLV((byte) 0xE1, linkBuf)));

        this.channel = new EmbeddedChannel(new HAProxyMessageDecoder(), new HAProxyHandler());
        this.channel.writeInbound(HaProxyTestSupport.encode(message));

        assertEquals(linkId, this.channel.attr(CPAttributeKeys.LOBBY_LINK_ID).get(),
                "The switch link id must be stored on the accepted channel");
        assertNotNull(this.channel.attr(CPAttributeKeys.HANDSHAKE_DATA).get(), "The handshake TLV must survive the extra TLV");
    }

    @Test
    void ignoresMalformedLinkIdTlv() throws Exception {
        final ByteBuf garbage = Unpooled.buffer(3, 3);
        garbage.writeZero(3);
        final HAProxyMessage message = new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "203.0.113.7", "203.0.113.7", 40000, 25565,
                List.of(new HAProxyTLV((byte) 0xE1, garbage)));

        this.channel = new EmbeddedChannel(new HAProxyMessageDecoder(), new HAProxyHandler());
        this.channel.writeInbound(HaProxyTestSupport.encode(message));

        assertNull(this.channel.attr(CPAttributeKeys.LOBBY_LINK_ID).get(), "A malformed link id must be ignored, not throw");
    }
}
