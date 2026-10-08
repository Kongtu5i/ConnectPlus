package dev.connectplus.lobby.protocol.packets.play.s2c;

import dev.connectplus.lobby.LobbyConstants;
import io.netty.buffer.ByteBuf;
import net.lenni0451.mcstructs.text.TextComponent;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's S2COpenScreenPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: read() is implemented so the scripted test client can decode it.
 */
public class S2COpenScreenPacket implements Packet {

    public int id;
    public int type;
    public TextComponent title;

    public S2COpenScreenPacket(final int id, final int type, final TextComponent title) {
        this.id = id;
        this.type = type;
        this.title = title;
    }

    public S2COpenScreenPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.id = PacketTypes.readVarInt(byteBuf);
        this.type = PacketTypes.readVarInt(byteBuf);
        this.title = LobbyConstants.TEXT_CODEC.deserializeNbtTree(PacketTypes.readUnnamedTag(byteBuf));
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.id);
        PacketTypes.writeVarInt(byteBuf, this.type);
        PacketTypes.writeUnnamedTag(byteBuf, LobbyConstants.TEXT_CODEC.serializeNbtTree(this.title));
    }
}
