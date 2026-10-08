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

/** Objective/team definitions belonging to the previous backend, confined to c2p's loop. */
final class ScoreboardState {
    private final Set<String> objectives = new LinkedHashSet<>();
    private final Set<String> teams = new LinkedHashSet<>();

    static boolean supports(int version) { return version <= 777 && WorldReset.supports(version); }

    static boolean isScoreboard(UnknownPacket packet, int version) {
        return supports(version) && (packet.packetId == MCPackets.S2C_SET_OBJECTIVE.getId(version)
                || packet.packetId == MCPackets.S2C_SET_PLAYER_TEAM.getId(version));
    }

    void observe(UnknownPacket packet, int version) {
        var bytes = Unpooled.wrappedBuffer(packet.data);
        try {
            boolean team = packet.packetId == MCPackets.S2C_SET_PLAYER_TEAM.getId(version);
            String name = PacketTypes.readString(bytes, 32767);
            // 1.7 always includes a display string before the objective mode.
            if (!team && version < 47) PacketTypes.readString(bytes, 32);
            int action = bytes.readUnsignedByte();
            if (action > (team ? 4 : 2)) throw new IllegalArgumentException("Unaudited scoreboard action " + action);
            Set<String> definitions = team ? teams : objectives;
            if (action == 0) definitions.add(name);
            else if (action == 1) definitions.remove(name);
            // Other modes only update an existing definition or its members.
            // Metadata after this header remains native, including newer formats.
        } finally { bytes.release(); }
    }

    List<UnknownPacket> clear(int version) {
        if (!supports(version)) throw new IllegalArgumentException("Unaudited scoreboard protocol " + version);
        List<UnknownPacket> removals = new ArrayList<>();
        for (String name : objectives) removals.add(removal(name, false, version));
        for (String name : teams) removals.add(removal(name, true, version));
        objectives.clear();
        teams.clear();
        return removals;
    }

    private static UnknownPacket removal(String name, boolean team, int version) {
        var bytes = Unpooled.buffer();
        try {
            PacketTypes.writeString(bytes, name);
            if (!team && version < 47) PacketTypes.writeString(bytes, "");
            bytes.writeByte(1);
            return new UnknownPacket((team ? MCPackets.S2C_SET_PLAYER_TEAM : MCPackets.S2C_SET_OBJECTIVE).getId(version), ByteBufUtil.getBytes(bytes));
        } finally { bytes.release(); }
    }
}
