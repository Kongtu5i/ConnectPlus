package dev.connectplus.lobby.haproxy;

import com.google.common.net.HostAndPort;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.lobby.model.HandshakeData;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyTLV;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HAProxyUtilTest {

    @Test
    void embedsHandshakeDataInE0Tlv() throws Exception {
        final Channel sourceChannel = channelWithRemoteAddress(new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 40000));
        final Channel targetChannel = channelWithRemoteAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25565));

        final HAProxyMessage message = HAProxyUtil.createMessage(
                sourceChannel,
                targetChannel,
                HostAndPort.fromParts("lobbytest.example.org", 25565),
                ProtocolVersion.v1_21_4,
                null);
        try {
            assertEquals("203.0.113.7", message.sourceAddress());
            assertEquals(40000, message.sourcePort());

            HandshakeData handshakeData = null;
            for (final HAProxyTLV tlv : message.tlvs()) {
                if (tlv.typeByteValue() == (byte) 0xE0) {
                    handshakeData = HandshakeData.read(tlv.content());
                }
            }
            assertNotNull(handshakeData, "HAProxy message must carry the 0xE0 handshake data TLV");
            assertEquals("lobbytest.example.org", handshakeData.host());
            assertEquals(25565, handshakeData.port());
            assertEquals(ProtocolVersion.v1_21_4, handshakeData.clientVersion());
        } finally {
            message.release();
        }
    }

    @Test
    void embedsLinkIdInE1Tlv() throws Exception {
        final Channel sourceChannel = channelWithRemoteAddress(new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 40000));
        final Channel targetChannel = channelWithRemoteAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25565));
        final UUID linkId = UUID.randomUUID();

        final HAProxyMessage message = HAProxyUtil.createMessage(
                sourceChannel,
                targetChannel,
                HostAndPort.fromParts("lobbytest.example.org", 25565),
                ProtocolVersion.v1_21_4,
                linkId);
        try {
            UUID carried = null;
            for (final HAProxyTLV tlv : message.tlvs()) {
                if (tlv.typeByteValue() == (byte) 0xE1) {
                    assertEquals(16, tlv.content().readableBytes(), "The link id TLV must be exactly 16 bytes");
                    carried = new UUID(tlv.content().readLong(), tlv.content().readLong());
                }
            }
            assertEquals(linkId, carried, "HAProxy message must carry the 0xE1 link id TLV");
        } finally {
            message.release();
        }
    }

    @Test
    void omitsLinkIdTlvWithoutId() throws Exception {
        final Channel sourceChannel = channelWithRemoteAddress(new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 40000));
        final Channel targetChannel = channelWithRemoteAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25565));

        final HAProxyMessage message = HAProxyUtil.createMessage(
                sourceChannel,
                targetChannel,
                HostAndPort.fromParts("lobbytest.example.org", 25565),
                ProtocolVersion.v1_21_4,
                null);
        try {
            for (final HAProxyTLV tlv : message.tlvs()) {
                assertNull(tlv.typeByteValue() == (byte) 0xE1 ? tlv : null, "No link id must travel without one");
            }
        } finally {
            message.release();
        }
    }

    private static Channel channelWithRemoteAddress(final InetSocketAddress address) {
        return new EmbeddedChannel() {
            @Override
            public SocketAddress remoteAddress() {
                return address;
            }
        };
    }
}
