package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/** Client interaction translated by ViaVersion to the lobby's 1.21.4 protocol. */
public class C2SPlayerActionPacket implements Packet {
    public int action, face, sequence;
    public long position;

    @Override public void read(final ByteBuf buf, final int protocolVersion) {
        action = PacketTypes.readVarInt(buf);
        position = buf.readLong();
        face = buf.readUnsignedByte();
        sequence = PacketTypes.readVarInt(buf);
    }

    @Override public void write(final ByteBuf buf, final int protocolVersion) {
        PacketTypes.writeVarInt(buf, action);
        buf.writeLong(position);
        buf.writeByte(face);
        PacketTypes.writeVarInt(buf, sequence);
    }
}

