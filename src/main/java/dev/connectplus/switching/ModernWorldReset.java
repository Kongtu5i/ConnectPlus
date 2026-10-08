package dev.connectplus.switching;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

/** Builds a client-format Respawn from configuration-era JoinGame spawn data. */
final class ModernWorldReset {
    private ModernWorldReset() { }

    /** Explicit wire families: a new protocol must be audited before joining one. */
    private enum Layout {
        KEY_DIMENSION(false, false, false, false),
        REGISTRY_DIMENSION(true, false, false, false),
        SEA_LEVEL(true, true, false, false),
        ONLINE_MODE(true, true, true, false),
        VAR_INT_GAMEMODES(true, true, true, true);

        final boolean registryDimension, seaLevel, onlineMode, varIntGamemodes;

        Layout(boolean registryDimension, boolean seaLevel, boolean onlineMode, boolean varIntGamemodes) {
            this.registryDimension = registryDimension;
            this.seaLevel = seaLevel;
            this.onlineMode = onlineMode;
            this.varIntGamemodes = varIntGamemodes;
        }
    }

    private static Layout layout(final int version) {
        return switch (version) {
            case 764, 765 -> Layout.KEY_DIMENSION;
            case 766, 767 -> Layout.REGISTRY_DIMENSION;
            case 768, 769, 770, 771, 772, 773, 774, 775 -> Layout.SEA_LEVEL;
            case 776 -> Layout.ONLINE_MODE;
            case 777 -> Layout.VAR_INT_GAMEMODES;
            default -> null;
        };
    }

    static boolean supports(final int version) {
        return layout(version) != null;
    }

    static UnknownPacket fromJoin(final UnknownPacket packet, final int version) {
        final Layout layout = layout(version);
        if (layout == null) throw new IllegalArgumentException("Unaudited world reset protocol: " + version);
        final ByteBuf join = Unpooled.wrappedBuffer(packet.data);
        try {
            join.readInt(); // destination entity ID remains in the original JoinGame
            join.readBoolean(); // hardcore
            final int worldCount = PacketTypes.readVarInt(join);
            if (worldCount < 1 || worldCount > 1024 || worldCount > join.readableBytes()) {
                throw new IllegalArgumentException("Invalid JoinGame world count: " + worldCount);
            }
            for (int i = 0; i < worldCount; i++) PacketTypes.readString(join, 32767);
            PacketTypes.readVarInt(join); // max players
            PacketTypes.readVarInt(join); // view distance
            PacketTypes.readVarInt(join); // simulation distance
            join.skipBytes(3); // reduced debug, death screen, limited crafting

            final int spawnStart = join.readerIndex();
            if (layout.registryDimension) PacketTypes.readVarInt(join); // dimension registry ID, 1.20.5+
            else PacketTypes.readString(join, 32767); // dimension key, 1.20.2/1.20.4
            PacketTypes.readString(join, 32767); // world
            join.readLong(); // seed
            if (layout.varIntGamemodes) {
                PacketTypes.readVarInt(join); // game mode, 26.3+
                PacketTypes.readVarInt(join); // OptionalVarInt previous game mode (0 means absent)
            } else {
                join.skipBytes(2); // byte game mode and previous game mode
            }
            join.skipBytes(2); // debug, flat
            if (join.readBoolean()) {
                PacketTypes.readString(join, 32767); // last death dimension
                join.readLong(); // last death block position
            }
            PacketTypes.readVarInt(join); // portal cooldown
            if (layout.seaLevel) PacketTypes.readVarInt(join); // sea level, 1.21.2+
            final int spawnLength = join.readerIndex() - spawnStart;
            if (layout.onlineMode) join.readBoolean(); // online mode: JoinGame only, 26.2+
            if (layout.registryDimension) join.readBoolean(); // enforce secure chat: JoinGame only
            if (join.isReadable()) throw new IllegalArgumentException("Unexpected JoinGame trailer");

            final byte[] payload = new byte[spawnLength + 1];
            join.getBytes(spawnStart, payload, 0, spawnLength);
            // dataToKeep=0: recreate player state, discard lobby attributes/entity metadata.
            // Merely forwarding JoinGame can leave the frozen spectator's movement state alive.
            return new UnknownPacket(MCPackets.S2C_RESPAWN.getId(version), payload);
        } finally {
            join.release();
        }
    }
}
