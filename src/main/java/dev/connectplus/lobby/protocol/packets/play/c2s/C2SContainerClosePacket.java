package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's C2SContainerClosePacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: write() is implemented so the scripted test client can encode it.
 */
public class C2SContainerClosePacket implements Packet {

    public int id;

    public C2SContainerClosePacket(final int id) {
        this.id = id;
    }

    public C2SContainerClosePacket() {
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
