package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/** Client interaction translated by ViaVersion to the lobby's 1.21.4 protocol. */
public class C2SSetCreativeModeSlotPacket implements Packet {
    public int slot;
    public com.viaversion.viaversion.api.minecraft.item.Item item;

    @Override public void read(final ByteBuf buf, final int protocolVersion) {
        slot = buf.readShort();
        item = com.viaversion.viaversion.api.type.types.version.VersionedTypes.V1_21_4.item.read(buf);
    }

    @Override public void write(final ByteBuf buf, final int protocolVersion) {
        buf.writeShort(slot);
        com.viaversion.viaversion.api.type.types.version.VersionedTypes.V1_21_4.item.write(buf, item);
    }
}

