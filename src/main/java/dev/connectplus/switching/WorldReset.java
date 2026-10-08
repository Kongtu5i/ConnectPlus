package dev.connectplus.switching;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.lenni0451.mcstructs.nbt.io.NbtReadTracker;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

import java.util.List;

/** Client-format world resets for audited release protocols (1.7.2–26.3). */
final class WorldReset {
    private WorldReset() { }

    static boolean supports(final int version) {
        if (ModernWorldReset.supports(version)) return true;
        return switch (version) {
            case 4, 5, 47, 107, 108, 109, 110, 210, 315, 316, 335, 338, 340,
                    393, 401, 404, 477, 480, 485, 490, 498, 573, 575, 578,
                    735, 736, 751, 753, 754, 755, 756, 757, 758, 759, 760, 761, 762, 763 -> true;
            default -> false;
        };
    }

    static List<UnknownPacket> fromJoin(final UnknownPacket packet, final int version) {
        if (!supports(version)) throw new IllegalArgumentException("Unaudited world reset protocol: " + version);
        if (ModernWorldReset.supports(version)) return List.of(ModernWorldReset.fromJoin(packet, version));
        final ByteBuf join = Unpooled.wrappedBuffer(packet.data);
        final ByteBuf reset = Unpooled.buffer();
        try {
            join.readInt(); // entity ID: preserved by JoinGame for 1.9+, withheld for 1.7/1.8
            final List<UnknownPacket> result;
            if (version < 735) result = classic(join, reset, version);
            else result = registryEra(join, reset, version);
            if (join.isReadable()) throw new IllegalArgumentException("Unexpected JoinGame trailer");
            return result;
        } finally {
            reset.release();
            join.release();
        }
    }

    private static List<UnknownPacket> classic(final ByteBuf join, final ByteBuf reset, final int version) {
        final int gameMode = join.readUnsignedByte() & ~0x08; // hardcore is JoinGame-only
        // 1.9 still used a byte; the int dimension started in 1.9.1, not 1.9.
        final int dimension = version >= 108 ? join.readInt() : join.readByte();
        final int difficulty = version <= 404 ? join.readUnsignedByte() : 0;
        final long seed = version >= 573 ? join.readLong() : 0;
        join.readUnsignedByte(); // max players
        final String levelType = PacketTypes.readString(join, 16);
        if (version >= 477) PacketTypes.readVarInt(join); // view distance, 1.14+
        if (version >= 47) join.readBoolean(); // reduced debug, 1.8+
        if (version >= 573) join.readBoolean(); // death screen, 1.15+

        reset.writeInt(dimension);
        if (version <= 404) reset.writeByte(difficulty);
        if (version >= 573) reset.writeLong(seed);
        reset.writeByte(gameMode);
        PacketTypes.writeString(reset, levelType);
        final byte[] real = ByteBufUtil.getBytes(reset);
        reset.setInt(0, dimension == 0 ? -1 : 0);
        // Pre-1.16 clients ignore same-dimension respawns. A dummy then real reset
        // guarantees recreation without modifying the target's original JoinGame.
        return List.of(respawn(version, ByteBufUtil.getBytes(reset)), respawn(version, real));
    }

    private static List<UnknownPacket> registryEra(final ByteBuf join, final ByteBuf reset, final int version) {
        if (version >= 751) join.readBoolean(); // standalone hardcore, 1.16.2+
        final int gameMode = join.readUnsignedByte() & ~0x08;
        final int previousGameMode = join.readByte();
        final int worlds = PacketTypes.readVarInt(join);
        if (worlds < 1 || worlds > 1024 || worlds > join.readableBytes()) {
            throw new IllegalArgumentException("Invalid JoinGame world count: " + worlds);
        }
        for (int i = 0; i < worlds; i++) PacketTypes.readString(join, 32767);
        readCompound(join); // registry belongs to JoinGame only
        final int dimensionStart = join.readerIndex();
        if (version >= 751 && version < 759) readCompound(join); // 1.16.2–1.18.2 dimension NBT
        else PacketTypes.readString(join, 32767); // 1.16/1.16.1 and 1.19+ dimension key
        PacketTypes.readString(join, 32767); // world name
        final int dimensionLength = join.readerIndex() - dimensionStart;
        final long seed = join.readLong();
        if (version >= 751) PacketTypes.readVarInt(join); // max players
        else join.readUnsignedByte();
        PacketTypes.readVarInt(join); // view distance
        if (version >= 757) PacketTypes.readVarInt(join); // simulation distance, 1.18+
        join.skipBytes(2); // reduced debug, death screen
        final boolean debug = join.readBoolean();
        final boolean flat = join.readBoolean();
        final int tailStart = join.readerIndex();
        if (version >= 759 && join.readBoolean()) {
            PacketTypes.readString(join, 32767); // death dimension
            join.readLong(); // death position
        }
        if (version >= 763) PacketTypes.readVarInt(join); // portal cooldown, 1.20+

        reset.writeBytes(join, dimensionStart, dimensionLength);
        reset.writeLong(seed);
        reset.writeByte(gameMode);
        reset.writeByte(previousGameMode);
        reset.writeBoolean(debug);
        reset.writeBoolean(flat);
        // false keep-player-data (1.16–1.19.2), zero bitmask (1.19.3–1.20.1).
        // Unlike 1.20.2+, this field PRECEDES the optional death location/cooldown.
        reset.writeByte(0);
        reset.writeBytes(join, tailStart, join.readerIndex() - tailStart);
        return List.of(respawn(version, ByteBufUtil.getBytes(reset)));
    }

    private static void readCompound(final ByteBuf data) {
        // These versions use named NBT roots. Bound both allocation and nesting,
        // and preserve the exact dimension bytes rather than reserializing them.
        PacketTypes.readNamedTag(data, new NbtReadTracker(4 * 1024 * 1024L)).asCompoundTag();
    }

    private static UnknownPacket respawn(final int version, final byte[] payload) {
        return new UnknownPacket(MCPackets.S2C_RESPAWN.getId(version), payload);
    }
}
