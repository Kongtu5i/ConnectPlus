package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/** Main/offhand swing in the lobby's 1.21.4 protocol. */
public class C2SSwingPacket implements Packet {
    public int hand;

    @Override public void read(final ByteBuf buf, final int protocolVersion) {
        this.hand = PacketTypes.readVarInt(buf);
    }

    @Override public void write(final ByteBuf buf, final int protocolVersion) {
        PacketTypes.writeVarInt(buf, this.hand);
    }
}
