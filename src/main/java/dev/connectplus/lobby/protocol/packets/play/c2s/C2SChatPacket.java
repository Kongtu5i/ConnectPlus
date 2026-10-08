package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Derived from MiniConnect's C2SChatPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: write() is implemented so the scripted test client can encode it.
 *
 * <p>The lobby's 1.21.4 wire format uses a fixed 256-byte optional signature
 * and a fixed three-byte acknowledgment window. Neither carries a length
 * prefix. Optional tails from newer clients are drained so PacketCodec cannot
 * interpret leftover bytes as another packet ID.</p>
 */
public class C2SChatPacket implements Packet {

    private static final int ACKNOWLEDGMENT_BYTES = 3;
    private static final int SIGNATURE_BYTES = 256;

    public String message;
    public Instant timestamp;
    public long salt;
    public byte[] signature;
    public int offset;
    public BitSet acknowledged;

    public C2SChatPacket(final String message, final Instant timestamp, final long salt, final byte[] signature, final int offset, final BitSet acknowledged) {
        this.message = message;
        this.timestamp = timestamp;
        this.salt = salt;
        this.signature = signature;
        this.offset = offset;
        this.acknowledged = acknowledged;
    }

    public C2SChatPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.message = PacketTypes.readString(byteBuf, 256);
        this.timestamp = Instant.ofEpochMilli(byteBuf.readLong());
        this.salt = byteBuf.readLong();
        if (byteBuf.readBoolean()) {
            this.signature = new byte[SIGNATURE_BYTES];
            byteBuf.readBytes(this.signature);
        } else {
            this.signature = null;
        }
        this.offset = PacketTypes.readVarInt(byteBuf);
        final byte[] acknowledgedBytes = new byte[ACKNOWLEDGMENT_BYTES];
        byteBuf.readBytes(acknowledgedBytes);
        this.acknowledged = BitSet.valueOf(acknowledgedBytes);
        byteBuf.skipBytes(byteBuf.readableBytes());
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeString(byteBuf, this.message);
        byteBuf.writeLong(this.timestamp.toEpochMilli());
        byteBuf.writeLong(this.salt);
        if (this.signature != null) {
            if (this.signature.length != SIGNATURE_BYTES) {
                throw new IllegalArgumentException("Chat signature must be 256 bytes");
            }
            byteBuf.writeBoolean(true);
            byteBuf.writeBytes(this.signature);
        } else {
            byteBuf.writeBoolean(false);
        }
        PacketTypes.writeVarInt(byteBuf, this.offset);
        //Fixed 20-bit window, in BitSet's little-endian byte order.
        byteBuf.writeBytes(Arrays.copyOf(this.acknowledged.toByteArray(), ACKNOWLEDGMENT_BYTES));
    }
}
