package dev.connectplus.switching;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

import java.util.ArrayList;
import java.util.List;

/** Client-protocol inventory cleanup before leaving a ConnectPlus chest menu. */
final class LobbyInventoryReset {
    private LobbyInventoryReset() { }

    static List<UnknownPacket> forClient(final int version, final int windowId) {
        final ByteBuf cursor = Unpooled.buffer();
        try {
            final MCPackets cursorType;
            if (version >= 768) {
                // 1.21.2 split cursor updates into their own packet. An empty
                // component item is just a zero item count.
                cursorType = MCPackets.S2C_SET_CURSOR_ITEM;
                cursor.writeByte(0);
            } else {
                cursorType = MCPackets.S2C_CONTAINER_SET_SLOT;
                cursor.writeByte(-1); // cursor window, not a real inventory slot
                if (version >= 756) PacketTypes.writeVarInt(cursor, 0); // 1.17.1 state ID
                cursor.writeShort(-1);
                if (version >= 404) cursor.writeByte(0); // absent item / zero component count
                else cursor.writeShort(-1); // pre-1.13.2 empty item ID
            }
            final List<UnknownPacket> packets = new ArrayList<>(5);
            packets.add(new UnknownPacket(cursorType.getId(version), ByteBufUtil.getBytes(cursor)));
            // IDs 1..100 share the same Byte/VarInt encoding on every supported
            // client. If the GUI is already closed, do not close player inventory.
            if (windowId > 0 && windowId <= 100) {
                packets.add(new UnknownPacket(MCPackets.S2C_CONTAINER_CLOSE.getId(version), new byte[]{(byte) windowId}));
            }
            packets.add(emptySlot(version, 40));
            packets.add(emptySlot(version, 44));
            // The new backend's player inventory starts at selected slot zero.
            // Do not carry the lobby's menu/disconnect selection in.
            packets.add(new UnknownPacket((version >= 768 ? MCPackets.S2C_SET_HELD_SLOT : MCPackets.S2C_SET_CARRIED_ITEM).getId(version),
                    new byte[]{0}));
            return packets;
        } finally {
            cursor.release();
        }
    }

    private static UnknownPacket emptySlot(final int version, final int slot) {
        final ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeByte(0); // player inventory, identical as Byte or VarInt
            if (version >= 756) PacketTypes.writeVarInt(bytes, 0);
            bytes.writeShort(slot);
            if (version >= 404) bytes.writeByte(0);
            else bytes.writeShort(-1);
            return new UnknownPacket(MCPackets.S2C_CONTAINER_SET_SLOT.getId(version), ByteBufUtil.getBytes(bytes));
        } finally { bytes.release(); }
    }
}
