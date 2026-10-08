package dev.connectplus.lobby.protocol.packets.play;

import dev.connectplus.lobby.LobbyConstants;
import io.netty.buffer.ByteBuf;
import net.lenni0451.mcstructs.text.TextComponent;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's S2CSystemChatPacket (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CSystemChatPacket implements Packet {

    public TextComponent message;
    public boolean actionbar;

    public S2CSystemChatPacket(final TextComponent message, final boolean actionbar) {
        this.message = message;
        this.actionbar = actionbar;
    }

    public S2CSystemChatPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.message = LobbyConstants.TEXT_CODEC.deserializeNbtTree(PacketTypes.readUnnamedTag(byteBuf));
        this.actionbar = byteBuf.readBoolean();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeUnnamedTag(byteBuf, LobbyConstants.TEXT_CODEC.serializeNbtTree(this.message));
        byteBuf.writeBoolean(this.actionbar);
    }
}
