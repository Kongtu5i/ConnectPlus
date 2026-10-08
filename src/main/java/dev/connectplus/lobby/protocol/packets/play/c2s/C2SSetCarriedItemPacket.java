package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/** Client interaction translated by ViaVersion to the lobby's 1.21.4 protocol. */
public class C2SSetCarriedItemPacket implements Packet {
    public int slot;

    @Override public void read(final ByteBuf buf, final int protocolVersion) {
        slot = buf.readShort();
    }

    @Override public void write(final ByteBuf buf, final int protocolVersion) {
        buf.writeShort(slot);
    }
}

