package dev.connectplus.lobby.protocol.packets.play.s2c;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's S2CContainerClosePacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: read() is implemented so the scripted test client can decode it.
 */
public class S2CContainerClosePacket implements Packet {

    public int id;

    public S2CContainerClosePacket(final int id) {
        this.id = id;
    }

    public S2CContainerClosePacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.id = PacketTypes.readVarInt(byteBuf);
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.id);
    }
}
