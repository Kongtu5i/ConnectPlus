package dev.connectplus.lobby.protocol.packets.play.c2s;

import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Derived from MiniConnect's C2SContainerClickPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: write() is implemented so the scripted test client can encode it.
 */
public class C2SContainerClickPacket implements Packet {

    public int containerId;
    public int revision;
    public int slot;
    public int button;
    public int action;
    public Map<Integer, Item> modifiedStacks;
    public Item item;

    public C2SContainerClickPacket(final int containerId, final int revision, final int slot, final int button, final int action, final Map<Integer, Item> modifiedStacks, final Item item) {
        this.containerId = containerId;
        this.revision = revision;
        this.slot = slot;
        this.button = button;
        this.action = action;
        this.modifiedStacks = modifiedStacks;
        this.item = item;
    }

    public C2SContainerClickPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.containerId = PacketTypes.readVarInt(byteBuf);
        this.revision = PacketTypes.readVarInt(byteBuf);
        this.slot = byteBuf.readShort();
        this.button = byteBuf.readByte();
        this.action = PacketTypes.readVarInt(byteBuf);
        final int entries = PacketTypes.readVarInt(byteBuf);
        if (entries > 128) throw new DecoderException("Too many entries in C2SContainerClickPacket (" + entries + " > 128)");
        this.modifiedStacks = new HashMap<>();
        for (int i = 0; i < entries; i++) {
            final int key = byteBuf.readShort();
            final Item value = VersionedTypes.V1_21_4.item.read(byteBuf);
            this.modifiedStacks.put(key, value);
        }
        this.item = VersionedTypes.V1_21_4.item.read(byteBuf);
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.containerId);
        PacketTypes.writeVarInt(byteBuf, this.revision);
        byteBuf.writeShort(this.slot);
        byteBuf.writeByte(this.button);
        PacketTypes.writeVarInt(byteBuf, this.action);
        PacketTypes.writeVarInt(byteBuf, this.modifiedStacks.size());
        for (final Map.Entry<Integer, Item> entry : this.modifiedStacks.entrySet()) {
            byteBuf.writeShort(entry.getKey());
            VersionedTypes.V1_21_4.item.write(byteBuf, entry.getValue());
        }
        VersionedTypes.V1_21_4.item.write(byteBuf, this.item);
    }
}
