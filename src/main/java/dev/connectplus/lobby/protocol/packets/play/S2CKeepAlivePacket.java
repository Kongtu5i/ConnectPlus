package dev.connectplus.lobby.protocol.packets.play;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;

/**
 * Derived from MiniConnect's S2CKeepAlivePacket (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CKeepAlivePacket implements Packet {

    public long id;

    public S2CKeepAlivePacket(final long id) {
        this.id = id;
    }

    public S2CKeepAlivePacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.id = byteBuf.readLong();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        byteBuf.writeLong(this.id);
    }
}
