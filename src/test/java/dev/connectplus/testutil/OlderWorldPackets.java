package dev.connectplus.testutil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

/** Independent wire fixtures for 1.7 through 1.20.1, with non-default spawn data. */
public final class OlderWorldPackets {
    private OlderWorldPackets() { }

    public static UnknownPacket join(final int version) {
        final ByteBuf out = Unpooled.buffer();
        try {
            out.writeInt(42);
            if (version < 735) {
                out.writeByte(10); // adventure + hardcore: respawn must strip hardcore
                if (version >= 108) out.writeInt(-1);
                else out.writeByte(-1);
                if (version <= 404) out.writeByte(3); // difficulty
                if (version >= 573) out.writeLong(123456789L);
                out.writeByte(20);
                PacketTypes.writeString(out, "flat");
                if (version >= 477) PacketTypes.writeVarInt(out, 12);
                if (version >= 47) out.writeBoolean(true);
                if (version >= 573) out.writeBoolean(false);
            } else {
                if (version >= 751) out.writeBoolean(true);
                out.writeByte(version >= 751 ? 2 : 10);
                out.writeByte(3);
                PacketTypes.writeVarInt(out, 2);
                PacketTypes.writeString(out, "minecraft:the_nether");
                PacketTypes.writeString(out, "custom:destination");
                writeNbt(out); // registry, not part of Respawn
                writeDimension(out, version);
                out.writeLong(123456789L);
                if (version >= 751) PacketTypes.writeVarInt(out, 200);
                else out.writeByte(20);
                PacketTypes.writeVarInt(out, 12);
                if (version >= 757) PacketTypes.writeVarInt(out, 8);
                out.writeBoolean(true); // reduced debug
                out.writeBoolean(false); // death screen
                out.writeBoolean(false); // debug world
                out.writeBoolean(true); // flat
                if (version >= 759) writeDeath(out);
                if (version >= 763) PacketTypes.writeVarInt(out, 17);
            }
            return new UnknownPacket(MCPackets.S2C_LOGIN.getId(version), ByteBufUtil.getBytes(out));
        } finally {
            out.release();
        }
    }

    public static byte[] respawn(final int version, final boolean dummy) {
        final ByteBuf out = Unpooled.buffer();
        try {
            if (version < 735) {
                out.writeInt(dummy ? 0 : -1);
                if (version <= 404) out.writeByte(3);
                if (version >= 573) out.writeLong(123456789L);
                out.writeByte(2);
                PacketTypes.writeString(out, "flat");
            } else {
                writeDimension(out, version);
                out.writeLong(123456789L);
                out.writeByte(2);
                out.writeByte(3);
                out.writeBoolean(false);
                out.writeBoolean(true);
                out.writeByte(0); // keep-player-data bool (<=760), bitmask (761+)
                if (version >= 759) writeDeath(out);
                if (version >= 763) PacketTypes.writeVarInt(out, 17);
            }
            return ByteBufUtil.getBytes(out);
        } finally {
            out.release();
        }
    }

    private static void writeDimension(final ByteBuf out, final int version) {
        if (version >= 751 && version < 759) writeNbt(out);
        else PacketTypes.writeString(out, "minecraft:the_nether");
        PacketTypes.writeString(out, "custom:destination");
    }

    private static void writeDeath(final ByteBuf out) {
        out.writeBoolean(true);
        PacketTypes.writeString(out, "minecraft:the_end");
        out.writeLong(0x123456789ABCDEFL);
    }

    private static void writeNbt(final ByteBuf out) {
        // Named root compound with an int and a nested compound containing a string.
        out.writeByte(10).writeShort(0);
        out.writeByte(3).writeShort(6).writeBytes(new byte[]{'h','e','i','g','h','t'}).writeInt(384);
        out.writeByte(10).writeShort(1).writeByte('x');
        out.writeByte(8).writeShort(1).writeByte('y').writeShort(3).writeBytes(new byte[]{'a','b','c'});
        out.writeByte(0).writeByte(0);
    }
}
