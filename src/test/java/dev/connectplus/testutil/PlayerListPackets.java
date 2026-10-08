package dev.connectplus.testutil;

import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

import java.util.UUID;

/** Independent player-list fixtures for the three Java wire families. */
public final class PlayerListPackets {
    private PlayerListPackets() { }

    public static UnknownPacket add(int version, UUID... players) {
        var bytes = Unpooled.buffer();
        try {
            if (version < 47) {
                if (players.length != 1) throw new IllegalArgumentException();
                PacketTypes.writeString(bytes, "SameName");
                bytes.writeBoolean(true).writeShort(45);
            } else {
                if (version < 761) PacketTypes.writeVarInt(bytes, 0);
                else bytes.writeByte(version >= 769 ? 255 : version >= 768 ? 127 : 63);
                PacketTypes.writeVarInt(bytes, players.length);
                for (UUID player : players) {
                    bytes.writeLong(player.getMostSignificantBits()).writeLong(player.getLeastSignificantBits());
                    PacketTypes.writeString(bytes, "SameName");
                    PacketTypes.writeVarInt(bytes, 1);
                    PacketTypes.writeString(bytes, "textures");
                    PacketTypes.writeString(bytes, "test-texture");
                    bytes.writeBoolean(true);
                    PacketTypes.writeString(bytes, "test-signature");
                    if (version >= 761) bytes.writeBoolean(false); // initialize chat, no key
                    PacketTypes.writeVarInt(bytes, 0); // game mode
                    if (version >= 761) bytes.writeBoolean(true); // listed
                    PacketTypes.writeVarInt(bytes, 45); // latency
                    bytes.writeBoolean(true);
                    if (version >= 765) Types.TAG.write(bytes, new StringTag("Same display"));
                    else PacketTypes.writeString(bytes, "{\"text\":\"Same display\"}");
                    if (version == 759 || version == 760) bytes.writeBoolean(false); // optional profile key
                    if (version >= 768) PacketTypes.writeVarInt(bytes, 17); // list order
                    if (version >= 769) bytes.writeBoolean(true); // hat
                }
            }
            return new UnknownPacket((version >= 761 ? MCPackets.S2C_PLAYER_INFO_UPDATE : MCPackets.S2C_PLAYER_INFO).getId(version), ByteBufUtil.getBytes(bytes));
        } finally { bytes.release(); }
    }

    public static UnknownPacket remove(int version, UUID... players) {
        var bytes = Unpooled.buffer();
        try {
            if (version < 47) {
                PacketTypes.writeString(bytes, "SameName");
                bytes.writeBoolean(false).writeShort(0);
            } else {
                if (version < 761) PacketTypes.writeVarInt(bytes, 4);
                PacketTypes.writeVarInt(bytes, players.length);
                for (UUID player : players) bytes.writeLong(player.getMostSignificantBits()).writeLong(player.getLeastSignificantBits());
            }
            return new UnknownPacket((version >= 761 ? MCPackets.S2C_PLAYER_INFO_REMOVE : MCPackets.S2C_PLAYER_INFO).getId(version), ByteBufUtil.getBytes(bytes));
        } finally { bytes.release(); }
    }
}
