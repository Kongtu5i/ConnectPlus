package dev.connectplus.lobby.protocol.packets.play.s2c;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's S2CContainerSetDataPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: read() is implemented so the scripted test client can decode it.
 */
public class S2CContainerSetDataPacket implements Packet {

    public int syncId;
    public int propertyId;
    public int value;

    public S2CContainerSetDataPacket(final int syncId, final int propertyId, final int value) {
        this.syncId = syncId;
        this.propertyId = propertyId;
        this.value = value;
    }

    public S2CContainerSetDataPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.syncId = PacketTypes.readVarInt(byteBuf);
        this.propertyId = byteBuf.readShort();
        this.value = byteBuf.readShort();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.syncId);
        byteBuf.writeShort(this.propertyId);
        byteBuf.writeShort(this.value);
    }
}
