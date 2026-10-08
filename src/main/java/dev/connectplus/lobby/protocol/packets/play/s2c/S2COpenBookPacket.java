package dev.connectplus.lobby.protocol.packets.play.s2c;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's S2COpenBookPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: read() is implemented so the scripted test client can decode it.
 */
public class S2COpenBookPacket implements Packet {

    public int hand;

    public S2COpenBookPacket(final int hand) {
        this.hand = hand;
    }

    public S2COpenBookPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.hand = PacketTypes.readVarInt(byteBuf);
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.hand);
    }
}
