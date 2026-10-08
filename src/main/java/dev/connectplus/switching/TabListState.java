package dev.connectplus.switching;

import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
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

/** Client-visible player entries. Accessed only on the client channel's event loop. */
final class TabListState {
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<String> legacyNames = new LinkedHashSet<>();
    private static final int MAX_ENTRIES = 65536;

    static boolean supports(int version) { return version <= 777 && WorldReset.supports(version); }

    static boolean isPlayerList(UnknownPacket packet, int version) {
        if (!supports(version)) return false;
        return version >= 761
                ? packet.packetId == MCPackets.S2C_PLAYER_INFO_UPDATE.getId(version)
                    || packet.packetId == MCPackets.S2C_PLAYER_INFO_REMOVE.getId(version)
                : packet.packetId == MCPackets.S2C_PLAYER_INFO.getId(version);
    }

    void observe(UnknownPacket packet, int version) {
        // Parse completely before mutating state: truncated entries must not
        // fabricate UUIDs or partially replace an already tracked player list.
        var bytes = Unpooled.wrappedBuffer(packet.data);
        try {
            if (version < 47) {
                String name = PacketTypes.readString(bytes, 16);
                boolean add = bytes.readBoolean();
                bytes.readShort();
                requireEnd(bytes);
                if (add) legacyNames.add(name); else legacyNames.remove(name);
                return;
            }
            boolean remove = version >= 761 && packet.packetId == MCPackets.S2C_PLAYER_INFO_REMOVE.getId(version);
            int actions = remove ? 0 : version >= 761 ? bytes.readUnsignedByte() : PacketTypes.readVarInt(bytes);
            if (version >= 761 && !remove) {
                int allowed = version >= 769 ? 255 : version >= 768 ? 127 : 63;
                if ((actions & ~allowed) != 0) throw new IllegalArgumentException("Unaudited player-info actions " + actions);
                if ((actions & 1) == 0) return; // Updates cannot create a client profile; keep unlisted profiles until removal.
            } else if (!remove && actions != 0 && actions != 4) {
                return; // Legacy game-mode/latency/display updates also cannot add a profile.
            }
            remove |= version < 761 && actions == 4;
            int count = count(bytes);
            List<UUID> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new UUID(bytes.readLong(), bytes.readLong()));
                if (remove) continue;
                skipProfile(bytes);
                if (version >= 761) {
                    if ((actions & 2) != 0 && bytes.readBoolean()) {
                        bytes.skipBytes(16 + 8); // chat session UUID and expiry
                        skipBytes(bytes); // public key
                        skipBytes(bytes); // signature
                    }
                    if ((actions & 4) != 0) PacketTypes.readVarInt(bytes);
                    if ((actions & 8) != 0) bytes.readBoolean();
                    if ((actions & 16) != 0) PacketTypes.readVarInt(bytes);
                    if ((actions & 32) != 0) skipDisplayName(bytes, version);
                    if ((actions & 64) != 0) PacketTypes.readVarInt(bytes);
                    if ((actions & 128) != 0) bytes.readBoolean();
                } else {
                    PacketTypes.readVarInt(bytes); // game mode
                    PacketTypes.readVarInt(bytes); // latency
                    skipDisplayName(bytes, version);
                    if ((version == 759 || version == 760) && bytes.readBoolean()) {
                        bytes.readLong(); // profile-key expiry
                        skipBytes(bytes);
                        skipBytes(bytes);
                    }
                }
            }
            requireEnd(bytes);
            if (remove) players.removeAll(entries); else players.addAll(entries);
        } finally { bytes.release(); }
    }

    List<UnknownPacket> clear(int version) {
        if (!supports(version)) throw new IllegalArgumentException("Unaudited player-list protocol " + version);
        List<UnknownPacket> result = new ArrayList<>();
        if (version < 47) {
            for (String name : legacyNames) {
                var bytes = Unpooled.buffer();
                try {
                    PacketTypes.writeString(bytes, name);
                    bytes.writeBoolean(false).writeShort(0);
                    result.add(new UnknownPacket(MCPackets.S2C_PLAYER_INFO.getId(version), ByteBufUtil.getBytes(bytes)));
                } finally { bytes.release(); }
            }
        } else if (!players.isEmpty()) {
            var bytes = Unpooled.buffer();
            try {
                if (version < 761) PacketTypes.writeVarInt(bytes, 4);
                PacketTypes.writeVarInt(bytes, players.size());
                for (UUID player : players) bytes.writeLong(player.getMostSignificantBits()).writeLong(player.getLeastSignificantBits());
                result.add(new UnknownPacket((version >= 761 ? MCPackets.S2C_PLAYER_INFO_REMOVE : MCPackets.S2C_PLAYER_INFO).getId(version), ByteBufUtil.getBytes(bytes)));
            } finally { bytes.release(); }
        }
        players.clear();
        legacyNames.clear();
        return result;
    }

    private static void skipProfile(ByteBuf bytes) {
        PacketTypes.readString(bytes, 16);
        for (int properties = count(bytes); properties > 0; properties--) {
            skipBytes(bytes); // property name
            skipBytes(bytes); // property value, commonly a texture
            if (bytes.readBoolean()) skipBytes(bytes); // optional signature
        }
    }

    private static void skipDisplayName(ByteBuf bytes, int version) {
        if (!bytes.readBoolean()) return;
        if (version >= 765) Types.TAG.read(bytes); // anonymous network NBT component
        else skipBytes(bytes); // UTF-8 JSON component
    }

    private static int count(ByteBuf bytes) {
        int count = PacketTypes.readVarInt(bytes);
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid player-info count " + count);
        return count;
    }

    private static void skipBytes(ByteBuf bytes) {
        int length = PacketTypes.readVarInt(bytes);
        if (length < 0 || length > bytes.readableBytes()) throw new IllegalArgumentException("Invalid player-info field length " + length);
        bytes.skipBytes(length);
    }

    private static void requireEnd(ByteBuf bytes) {
        if (bytes.isReadable()) throw new IllegalArgumentException("Unexpected player-info tail: " + bytes.readableBytes());
    }
}
