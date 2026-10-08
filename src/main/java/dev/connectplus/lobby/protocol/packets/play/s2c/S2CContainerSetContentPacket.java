package dev.connectplus.lobby.protocol.packets.play.s2c;

import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's S2CContainerSetContentPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: read() is implemented so the scripted test client can decode it.
 */
public class S2CContainerSetContentPacket implements Packet {

    public int windowId;
    public int revision;
    public Item[] items;
    public Item cursor;

    public S2CContainerSetContentPacket(final int windowId, final int revision, final Item[] items, final Item cursor) {
        this.windowId = windowId;
        this.revision = revision;
        this.items = items;
        this.cursor = cursor;
    }

    public S2CContainerSetContentPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.windowId = PacketTypes.readVarInt(byteBuf);
        this.revision = PacketTypes.readVarInt(byteBuf);
        this.items = VersionedTypes.V1_21_4.itemArray.read(byteBuf);
        this.cursor = VersionedTypes.V1_21_4.item.read(byteBuf);
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.windowId);
        PacketTypes.writeVarInt(byteBuf, this.revision);
        VersionedTypes.V1_21_4.itemArray.write(byteBuf, this.items);
        VersionedTypes.V1_21_4.item.write(byteBuf, this.cursor);
    }
}
