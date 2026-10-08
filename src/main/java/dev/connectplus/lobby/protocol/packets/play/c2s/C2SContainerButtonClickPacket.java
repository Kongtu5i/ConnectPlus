package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's C2SContainerButtonClickPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: write() is implemented so the scripted test client can encode it.
 */
public class C2SContainerButtonClickPacket implements Packet {

    public int syncId;
    public int buttonId;

    public C2SContainerButtonClickPacket(final int syncId, final int buttonId) {
        this.syncId = syncId;
        this.buttonId = buttonId;
    }

    public C2SContainerButtonClickPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.syncId = PacketTypes.readVarInt(byteBuf);
        this.buttonId = PacketTypes.readVarInt(byteBuf);
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.syncId);
        PacketTypes.writeVarInt(byteBuf, this.buttonId);
    }
}
