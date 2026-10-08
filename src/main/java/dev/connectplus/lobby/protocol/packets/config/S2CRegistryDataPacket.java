package dev.connectplus.lobby.protocol.packets.config;

import io.netty.buffer.ByteBuf;
import net.lenni0451.mcstructs.nbt.NbtTag;
import net.lenni0451.mcstructs.nbt.tags.CompoundTag;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.Map;

/**
 * Derived from MiniConnect's S2CConfigRegistryDataPacket (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CRegistryDataPacket implements Packet {

    public String registry;
    public CompoundTag data;

    public S2CRegistryDataPacket(final String registry, final CompoundTag data) {
        this.registry = registry;
        this.data = data;
    }

    public S2CRegistryDataPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeString(byteBuf, this.registry);
        PacketTypes.writeVarInt(byteBuf, this.data.size());
        for (final Map.Entry<String, NbtTag> entry : this.data) {
            PacketTypes.writeString(byteBuf, entry.getKey());
            byteBuf.writeBoolean(true);
            PacketTypes.writeUnnamedTag(byteBuf, entry.getValue());
        }
    }
}
