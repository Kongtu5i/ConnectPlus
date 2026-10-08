package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/** Client interaction translated by ViaVersion to the lobby's 1.21.4 protocol. */
public class C2SUseItemOnPacket implements Packet {
    public int hand, face, sequence;
    public long position;
    public float x, y, z;
    public boolean insideBlock, worldBorderHit;

    @Override public void read(final ByteBuf buf, final int protocolVersion) {
        hand = PacketTypes.readVarInt(buf);
        position = buf.readLong();
        face = PacketTypes.readVarInt(buf);
        x = buf.readFloat(); y = buf.readFloat(); z = buf.readFloat();
        insideBlock = buf.readBoolean();
        worldBorderHit = buf.readBoolean();
        sequence = PacketTypes.readVarInt(buf);
    }

    @Override public void write(final ByteBuf buf, final int protocolVersion) {
        PacketTypes.writeVarInt(buf, hand);
        buf.writeLong(position);
        PacketTypes.writeVarInt(buf, face);
        buf.writeFloat(x); buf.writeFloat(y); buf.writeFloat(z);
        buf.writeBoolean(insideBlock);
        buf.writeBoolean(worldBorderHit);
        PacketTypes.writeVarInt(buf, sequence);
    }
}

