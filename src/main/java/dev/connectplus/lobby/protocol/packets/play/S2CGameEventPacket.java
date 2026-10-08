package dev.connectplus.lobby.protocol.packets.play;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;

/**
 * Derived from MiniConnect's S2CGameEventPacket (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CGameEventPacket implements Packet {

    public int event;
    public float value;

    public S2CGameEventPacket(final int event, final float value) {
        this.event = event;
        this.value = value;
    }

    public S2CGameEventPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.event = byteBuf.readUnsignedByte();
        this.value = byteBuf.readFloat();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        byteBuf.writeByte(this.event);
        byteBuf.writeFloat(this.value);
    }
}
