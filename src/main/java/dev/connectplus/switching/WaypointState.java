package dev.connectplus.switching;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Client-visible locator markers, confined to the client channel's event loop. */
final class WaypointState {
    private record Source(UUID uuid, String name) { }
    private final Set<Source> sources = new LinkedHashSet<>();

    private static boolean supports(int version) {
        return switch (version) {
            case 771, 772, 773, 774, 775, 776, 777 -> true; // 1.21.6–26.3
            default -> false;
        };
    }

    static boolean isWaypoint(UnknownPacket packet, int version) {
        return supports(version) && packet.packetId == MCPackets.S2C_WAYPOINT.getId(version);
    }

    void observe(UnknownPacket packet) {
        var bytes = Unpooled.wrappedBuffer(packet.data);
        try {
            int action = PacketTypes.readVarInt(bytes); // TRACK, UNTRACK, UPDATE
            if (action < 0 || action > 2) throw new IllegalArgumentException("Unknown waypoint action " + action);
            Source source = bytes.readBoolean()
                    ? new Source(new UUID(bytes.readLong(), bytes.readLong()), null)
                    : new Source(null, PacketTypes.readString(bytes, 32767));
            PacketTypes.readString(bytes, 32767); // icon style
            if (bytes.readBoolean()) bytes.skipBytes(3); // optional RGB color
            int type = PacketTypes.readVarInt(bytes);
            switch (type) {
                case 0 -> { } // EMPTY, also used by UNTRACK
                case 1 -> { // block coordinates, not a packed BlockPos
                    PacketTypes.readVarInt(bytes);
                    PacketTypes.readVarInt(bytes);
                    PacketTypes.readVarInt(bytes);
                }
                case 2 -> { PacketTypes.readVarInt(bytes); PacketTypes.readVarInt(bytes); } // chunk coordinates
                case 3 -> bytes.readFloat(); // azimuth
                default -> throw new IllegalArgumentException("Unknown waypoint type " + type);
            }
            if (bytes.isReadable()) throw new IllegalArgumentException("Unexpected waypoint trailer");
            // Commit only a fully decoded packet. UPDATE cannot introduce a new
            // client marker; the original TRACK already recorded its source.
            if (action == 0) sources.add(source);
            else if (action == 1) sources.remove(source);
        } finally { bytes.release(); }
    }

    List<UnknownPacket> clear(int version) {
        if (!supports(version)) return List.of();
        List<UnknownPacket> removals = new ArrayList<>(sources.size());
        for (Source source : sources) {
            var bytes = Unpooled.buffer();
            try {
                PacketTypes.writeVarInt(bytes, 1); // UNTRACK
                bytes.writeBoolean(source.uuid() != null);
                if (source.uuid() != null) {
                    bytes.writeLong(source.uuid().getMostSignificantBits()).writeLong(source.uuid().getLeastSignificantBits());
                } else {
                    PacketTypes.writeString(bytes, source.name());
                }
                PacketTypes.writeString(bytes, "minecraft:default");
                bytes.writeBoolean(false); // no color override
                PacketTypes.writeVarInt(bytes, 0); // EMPTY waypoint data
                removals.add(new UnknownPacket(MCPackets.S2C_WAYPOINT.getId(version), ByteBufUtil.getBytes(bytes)));
            } finally { bytes.release(); }
        }
        sources.clear();
        return removals;
    }
}
