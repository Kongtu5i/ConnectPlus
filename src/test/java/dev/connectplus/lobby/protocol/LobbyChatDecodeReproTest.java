package dev.connectplus.lobby.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.Packet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the public-deployment address-input disconnect: a real signed
 * client's signature is a fixed 256-byte field, not a length-prefixed array.
 * The acknowledgment is a fixed 20-bit (three-byte) field, not a long array.
 * Fixtures deliberately do not use the production writer to construct inputs.
 */
class LobbyChatDecodeReproTest {

    /**
     * A signed chat packet body as a real online-mode client puts it on the
     * wire: message, timestamp, salt, 256-byte signature, offset, the
     * three-byte acknowledgment window and optional 1.21.5+ checksum tail.
     */
    private static ByteBuf signedChatBody() {
        final ByteBuf buf = Unpooled.buffer();
        PacketTypes_writeString(buf, "192.168.1.14:25565");
        buf.writeLong(Instant.now().toEpochMilli());
        buf.writeLong(42L); //salt
        buf.writeBoolean(true);
        final byte[] signature = new byte[256];
        //These genuine signature bytes were previously interpreted as length 44324130.
        signature[0] = (byte) 0xa2;
        signature[1] = (byte) 0xaa;
        signature[2] = (byte) 0x91;
        signature[3] = 0x15;
        buf.writeBytes(signature);
        PacketTypes_writeVarInt(buf, 7); //offset
        buf.writeBytes(new byte[]{0x2a, 0, 0x08}); //bits 1,3,5,19 (little endian)
        buf.writeByte(0); //1.21.5 network-id tail
        return buf;
    }

    /**
     * The unsigned body the test clients send: no signature, empty acknowledgment.
     */
    private static ByteBuf unsignedChatBody() {
        final ByteBuf buf = Unpooled.buffer();
        PacketTypes_writeString(buf, "hello");
        buf.writeLong(Instant.now().toEpochMilli());
        buf.writeLong(0L);
        buf.writeBoolean(false);
        PacketTypes_writeVarInt(buf, 0);
        buf.writeBytes(new byte[3]);
        return buf;
    }

    @Test
    void signedChatWithTailDecodesAndConsumesEverything() {
        final ByteBuf body = signedChatBody();
        final Packet packet = playRegistry().createPacket(0x07, body);
        assertEquals(dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket.class, packet.getClass());
        final dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket chat =
                (dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket) packet;
        assertEquals("192.168.1.14:25565", chat.message);
        assertEquals(256, chat.signature.length);
        assertEquals((byte) 0xa2, chat.signature[0]);
        assertEquals(7, chat.offset);
        assertEquals(java.util.BitSet.valueOf(new long[]{0x8002a}), chat.acknowledged);
        assertEquals(body.writerIndex(), body.readerIndex(), "no byte may remain for the codec loop");
    }

    @Test
    void unsignedChatDecodesAndConsumesEverything() {
        final ByteBuf body = unsignedChatBody();
        final Packet packet = playRegistry().createPacket(0x07, body);
        assertEquals(dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket.class, packet.getClass());
        assertEquals("hello", ((dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket) packet).message);
        assertEquals(body.writerIndex(), body.readerIndex(), "no byte may remain for the codec loop");
    }

    @Test
    void writeReadRoundTripStaysConsistent() {
        final dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket chat =
                new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket("example.com", Instant.now(), 0L, null, 0, new java.util.BitSet());
        final ByteBuf out = Unpooled.buffer();
        chat.write(out, 769);
        final dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket back =
                new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket();
        back.read(out, 769);
        assertEquals("example.com", back.message);
        assertEquals(out.writerIndex(), out.readerIndex(), "no byte may remain for the codec loop");
    }

    @Test
    void signedCommandTrailerConsumesEverything() {
        final ByteBuf buf = Unpooled.buffer();
        PacketTypes_writeString(buf, "connect example.com");
        buf.writeLong(Instant.now().toEpochMilli());
        buf.writeLong(0L);
        PacketTypes_writeVarInt(buf, 1); //one signed argument
        PacketTypes_writeString(buf, "address");
        final byte[] signature = new byte[256];
        java.util.Arrays.fill(signature, (byte) 0xff);
        buf.writeBytes(signature);
        PacketTypes_writeVarInt(buf, 0); //offset, not a preview boolean
        buf.writeBytes(new byte[]{1, 0, 0});
        buf.writeByte(0); //tail byte a future client adds
        final Packet packet = playRegistry().createPacket(0x06, buf);
        assertEquals(dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandSignedPacket.class, packet.getClass());
        assertEquals("connect example.com", ((dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandSignedPacket) packet).message);
        assertEquals(buf.writerIndex(), buf.readerIndex(), "no byte may remain for the codec loop");
    }

    @Test
    void chatWriterProducesVanillaFixedFields() {
        final byte[] signature = new byte[256];
        java.util.Arrays.fill(signature, (byte) 0x5a);
        final ByteBuf out = Unpooled.buffer();
        new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket(
                "x", Instant.ofEpochMilli(123), 42, signature, 7,
                java.util.BitSet.valueOf(new long[]{0x8002a})).write(out, 769);
        assertEquals("x", net.raphimc.netminecraft.packet.PacketTypes.readString(out, 256));
        assertEquals(123, out.readLong());
        assertEquals(42, out.readLong());
        assertEquals(true, out.readBoolean());
        final byte[] actualSignature = new byte[256];
        out.readBytes(actualSignature);
        org.junit.jupiter.api.Assertions.assertArrayEquals(signature, actualSignature);
        assertEquals(7, net.raphimc.netminecraft.packet.PacketTypes.readVarInt(out));
        final byte[] acknowledgment = new byte[3];
        out.readBytes(acknowledgment);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{0x2a, 0, 8}, acknowledgment);
        assertEquals(0, out.readableBytes());
    }

    @Test
    void signedCommandWriterHasOffsetAndFixedAcknowledgmentWithoutPreviewFlag() {
        final ByteBuf out = Unpooled.buffer();
        new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandSignedPacket("connect example.com").write(out, 769);
        assertEquals("connect example.com", net.raphimc.netminecraft.packet.PacketTypes.readString(out, 256));
        out.skipBytes(16); //timestamp and salt
        assertEquals(0, net.raphimc.netminecraft.packet.PacketTypes.readVarInt(out)); //argument count
        assertEquals(0, net.raphimc.netminecraft.packet.PacketTypes.readVarInt(out)); //offset
        assertEquals(3, out.readableBytes());
        assertEquals(0, out.readUnsignedMedium());
    }

    private static LobbyPacketRegistry playRegistry() {
        final LobbyPacketRegistry registry = new LobbyPacketRegistry();
        registry.setConnectionState(ConnectionState.PLAY);
        return registry;
    }

    private static void PacketTypes_writeString(final ByteBuf buf, final String s) {
        net.raphimc.netminecraft.packet.PacketTypes.writeString(buf, s);
    }

    private static void PacketTypes_writeVarInt(final ByteBuf buf, final int v) {
        net.raphimc.netminecraft.packet.PacketTypes.writeVarInt(buf, v);
    }
}
