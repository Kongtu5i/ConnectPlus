package dev.connectplus.testutil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

/** Independent wire fixtures for configuration-era JoinGame and Respawn packets. */
public final class ModernWorldPackets {
    private ModernWorldPackets() { }

    public static UnknownPacket join(final int version) {
        return join(version, false);
    }

    public static UnknownPacket join(final int version, final boolean deathLocation) {
        return join(version, deathLocation, false, true);
    }

    public static UnknownPacket join(final int version, final boolean deathLocation,
                                     final boolean onlineMode, final boolean secureChat) {
        return join(version, deathLocation, onlineMode, secureChat, false);
    }

    public static UnknownPacket join(final int version, final boolean deathLocation,
                                     final boolean onlineMode, final boolean secureChat, final boolean paddedGamemodes) {
        final ByteBuf out = Unpooled.buffer();
        try {
            out.writeInt(42);
            out.writeBoolean(false);
            PacketTypes.writeVarInt(out, 1);
            PacketTypes.writeString(out, "minecraft:overworld");
            PacketTypes.writeVarInt(out, 20);
            PacketTypes.writeVarInt(out, 10);
            PacketTypes.writeVarInt(out, 8);
            out.writeBoolean(false);
            out.writeBoolean(true);
            out.writeBoolean(false);
            out.writeBytes(spawn(version, deathLocation, paddedGamemodes));
            if (version >= 776) out.writeBoolean(onlineMode); // JoinGame-only, Java 26.2+
            if (version >= 766) out.writeBoolean(secureChat); // not part of Respawn
            return new UnknownPacket(MCPackets.S2C_LOGIN.getId(version), ByteBufUtil.getBytes(out));
        } finally {
            out.release();
        }
    }

    public static byte[] spawn(final int version) {
        return spawn(version, false);
    }

    private static byte[] spawn(final int version, final boolean deathLocation) {
        return spawn(version, deathLocation, false);
    }

    private static byte[] spawn(final int version, final boolean deathLocation, final boolean paddedGamemodes) {
        final ByteBuf out = Unpooled.buffer();
        try {
            if (version >= 766) PacketTypes.writeVarInt(out, 0);
            else PacketTypes.writeString(out, "minecraft:overworld");
            PacketTypes.writeString(out, "minecraft:overworld");
            out.writeLong(123456789L);
            if (version >= 777) {
                if (paddedGamemodes) {
                    // Valid two-byte encodings of zero: catch a fixed-width-byte decoder.
                    out.writeBytes(new byte[]{(byte) 0x80, 0, (byte) 0x80, 0});
                } else {
                    PacketTypes.writeVarInt(out, 0); // survival
                    PacketTypes.writeVarInt(out, 0); // OptionalVarInt: absent previous game mode
                }
            } else {
                out.writeByte(0); // survival
                out.writeByte(-1); // no previous game mode
            }
            out.writeBoolean(false);
            out.writeBoolean(false);
            out.writeBoolean(deathLocation);
            if (deathLocation) {
                PacketTypes.writeString(out, "minecraft:the_end");
                out.writeLong(0x123456789ABCDEFL);
            }
            PacketTypes.writeVarInt(out, 9);
            if (version >= 768) PacketTypes.writeVarInt(out, 63);
            return ByteBufUtil.getBytes(out);
        } finally {
            out.release();
        }
    }

    public static byte[] respawn(final int version) {
        return respawn(version, false);
    }

    public static byte[] respawn(final int version, final boolean deathLocation) {
        return respawn(version, deathLocation, false);
    }

    public static byte[] respawn(final int version, final boolean deathLocation, final boolean paddedGamemodes) {
        final byte[] spawn = spawn(version, deathLocation, paddedGamemodes);
        // Final dataToKeep=0 clears old entity metadata and attribute modifiers.
        return java.util.Arrays.copyOf(spawn, spawn.length + 1);
    }
}
