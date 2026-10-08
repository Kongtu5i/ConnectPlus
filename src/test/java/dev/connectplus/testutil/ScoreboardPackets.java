package dev.connectplus.testutil;

import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

public final class ScoreboardPackets {
    private ScoreboardPackets() { }

    public static UnknownPacket objective(int version, String name, int action) {
        var bytes = Unpooled.buffer();
        try {
            PacketTypes.writeString(bytes, name);
            if (version < 47) {
                PacketTypes.writeString(bytes, action == 1 ? "" : "Health");
                bytes.writeByte(action);
            } else {
                bytes.writeByte(action);
                if (action != 1) {
                    component(bytes, version, "Health");
                    if (version < 393) PacketTypes.writeString(bytes, "hearts");
                    else PacketTypes.writeVarInt(bytes, 1);
                    if (version >= 765) bytes.writeBoolean(false); // no number format
                }
            }
            return new UnknownPacket(MCPackets.S2C_SET_OBJECTIVE.getId(version), ByteBufUtil.getBytes(bytes));
        } finally { bytes.release(); }
    }

    public static UnknownPacket team(int version, String name, int action) {
        var bytes = Unpooled.buffer();
        try {
            PacketTypes.writeString(bytes, name);
            bytes.writeByte(action);
            if (action == 0 || action == 2) {
                component(bytes, version, "Team");
                if (version < 393) { PacketTypes.writeString(bytes, "prefix"); PacketTypes.writeString(bytes, "suffix"); }
                bytes.writeByte(0);
                if (version >= 47) {
                    if (version >= 770) PacketTypes.writeVarInt(bytes, 0);
                    else PacketTypes.writeString(bytes, "always");
                    if (version >= 107) {
                        if (version >= 770) PacketTypes.writeVarInt(bytes, 0);
                        else PacketTypes.writeString(bytes, "always");
                    }
                    if (version < 393) bytes.writeByte(0);
                    else {
                        PacketTypes.writeVarInt(bytes, 0);
                        component(bytes, version, "prefix");
                        component(bytes, version, "suffix");
                    }
                }
            }
            if (action == 0 || action == 3 || action == 4) {
                if (version < 47) bytes.writeShort(0);
                else PacketTypes.writeVarInt(bytes, 0);
            }
            return new UnknownPacket(MCPackets.S2C_SET_PLAYER_TEAM.getId(version), ByteBufUtil.getBytes(bytes));
        } finally { bytes.release(); }
    }

    private static void component(ByteBuf bytes, int version, String text) {
        if (version >= 765) Types.TAG.write(bytes, new StringTag(text));
        else PacketTypes.writeString(bytes, version >= 393 ? "{\"text\":\"" + text + "\"}" : text);
    }
}
