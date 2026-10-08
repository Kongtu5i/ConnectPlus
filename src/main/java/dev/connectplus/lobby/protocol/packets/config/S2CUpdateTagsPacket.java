package dev.connectplus.lobby.protocol.packets.config;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.Map;

/**
 * Derived from MiniConnect's S2CConfigUpdateTagsPacket (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CUpdateTagsPacket implements Packet {

    public Map<String, Map<String, int[]>> tags;

    public S2CUpdateTagsPacket(final Map<String, Map<String, int[]>> tags) {
        this.tags = tags;
    }

    public S2CUpdateTagsPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.tags.size());
        for (final Map.Entry<String, Map<String, int[]>> entry : this.tags.entrySet()) {
            PacketTypes.writeString(byteBuf, entry.getKey());
            PacketTypes.writeVarInt(byteBuf, entry.getValue().size());
            for (final Map.Entry<String, int[]> entry2 : entry.getValue().entrySet()) {
                PacketTypes.writeString(byteBuf, entry2.getKey());
                PacketTypes.writeVarInt(byteBuf, entry2.getValue().length);
                for (final int i : entry2.getValue()) {
                    PacketTypes.writeVarInt(byteBuf, i);
                }
            }
        }
    }
}
