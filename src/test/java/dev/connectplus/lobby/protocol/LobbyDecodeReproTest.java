package dev.connectplus.lobby.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.Packet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the public-deployment 1.21.11 join failure: the client
 * information packet arrives with the 1.21.2+ particleStatus tail (Via has no
 * rewriter on the 1.21.4..1.21.11 path and forwards the body untouched), while
 * Via's own rewrites for older clients omit it. The old 8-field reader left the
 * tail byte in the buffer; the packet codec then misparsed it as the next
 * packet id and the lobby closed the connection
 * (DecoderException: readerIndex(15) + length(1) exceeds writerIndex(15)).
 */
class LobbyDecodeReproTest {

    /**
     * The exact 15-byte packet hex-dumped live on the public deployment:
     * id 0x00 + locale "zh_cn", viewDistance 12, chat 0, colors, skin 127,
     * main hand 1, no filtering, listings allowed, particleStatus 0.
     */
    private static final byte[] NEW_CLIENT_BODY = concat(
            varint(5), "zh_cn".getBytes(StandardCharsets.UTF_8),
            new byte[]{0x0c, 0x00, 0x01, 0x7f, 0x01, 0x00, 0x01, 0x00});

    /**
     * The 8-field body ViaProxy produces for clients it translates (no particleStatus).
     */
    private static final byte[] VIA_REWRITTEN_BODY = concat(
            varint(5), "zh_cn".getBytes(StandardCharsets.UTF_8),
            new byte[]{0x0c, 0x00, 0x01, 0x7f, 0x01, 0x00, 0x01});

    @Test
    void newerClientTailIsConsumed() {
        final LobbyPacketRegistry registry = new LobbyPacketRegistry();
        registry.setConnectionState(ConnectionState.CONFIGURATION);
        final Packet packet = registry.createPacket(0x00, Unpooled.wrappedBuffer(NEW_CLIENT_BODY));
        assertEquals(dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket.class, packet.getClass());
        final dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket settings =
                (dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket) packet;
        assertEquals("zh_cn", settings.locale);
        assertEquals(0, settings.particleStatus);
    }

    @Test
    void viaRewrittenBodyStillDecodes() {
        final LobbyPacketRegistry registry = new LobbyPacketRegistry();
        registry.setConnectionState(ConnectionState.CONFIGURATION);
        final Packet packet = registry.createPacket(0x00, Unpooled.wrappedBuffer(VIA_REWRITTEN_BODY));
        assertEquals(dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket.class, packet.getClass());
        final dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket settings =
                (dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket) packet;
        assertEquals("zh_cn", settings.locale);
        assertEquals(-1, settings.particleStatus, "no tail on the Via-rewritten wire form");
    }

    @Test
    void readConsumesEveryByteOfBothWireForms() {
        //The packet codec frames packets by consuming exactly one packet per read;
        //any leftover byte is misparsed as the next packet id, so the reader must
        //drain the buffer for both wire forms and even for unknown future tails
        final dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket settings =
                new dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket();
        settings.read(Unpooled.wrappedBuffer(NEW_CLIENT_BODY), 769);
        settings.read(Unpooled.wrappedBuffer(VIA_REWRITTEN_BODY), 769);
        final ByteBuf futureBody = Unpooled.wrappedBuffer(concat(VIA_REWRITTEN_BODY, new byte[]{0x01, 0x02}));
        settings.read(futureBody, 9999); //a future client adds more trailing fields
        assertEquals(futureBody.writerIndex(), futureBody.readerIndex(), "no byte may remain for the codec loop");
    }

    @Test
    void writeReadRoundTripKeepsTheTail() {
        final dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket settings =
                new dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket("zh_cn", 12, 0, true, 127, 1, false, true, 0);
        final io.netty.buffer.ByteBuf out = Unpooled.buffer();
        settings.write(out, 769);
        final byte[] written = new byte[out.readableBytes()];
        out.readBytes(written);
        assertEquals(NEW_CLIENT_BODY.length, written.length, "body must match the real 1.21.11 wire form");
        final dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket back =
                new dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket();
        back.read(Unpooled.wrappedBuffer(written), 769);
        assertEquals("zh_cn", back.locale);
        assertEquals(0, back.particleStatus);
    }

    private static byte[] varint(final int value) {
        byte[] bytes = {(byte) value};
        return bytes;
    }

    private static byte[] concat(final byte[]... arrays) {
        int length = 0;
        for (final byte[] a : arrays) length += a.length;
        final byte[] out = new byte[length];
        int offset = 0;
        for (final byte[] a : arrays) {
            System.arraycopy(a, 0, out, offset, a.length);
            offset += a.length;
        }
        return out;
    }
}
