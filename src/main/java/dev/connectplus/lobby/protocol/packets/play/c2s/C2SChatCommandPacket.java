package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's C2SChatCommandPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: write() is implemented so the scripted test client can encode it.
 */
public class C2SChatCommandPacket implements Packet {

    public String message;

    public C2SChatCommandPacket(final String message) {
        this.message = message;
    }

    public C2SChatCommandPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.message = PacketTypes.readString(byteBuf, Short.MAX_VALUE);
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeString(byteBuf, this.message);
    }
}
