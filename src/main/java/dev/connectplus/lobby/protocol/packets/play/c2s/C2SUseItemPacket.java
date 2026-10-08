package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/** Client interaction translated by ViaVersion to the lobby's 1.21.4 protocol. */
public class C2SUseItemPacket implements Packet {
    public int hand, sequence;
    public float yaw, pitch;

    @Override public void read(final ByteBuf buf, final int protocolVersion) {
        hand = PacketTypes.readVarInt(buf);
        sequence = PacketTypes.readVarInt(buf);
        yaw = buf.readFloat();
        pitch = buf.readFloat();
    }

    @Override public void write(final ByteBuf buf, final int protocolVersion) {
        PacketTypes.writeVarInt(buf, hand);
        PacketTypes.writeVarInt(buf, sequence);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
    }
}

