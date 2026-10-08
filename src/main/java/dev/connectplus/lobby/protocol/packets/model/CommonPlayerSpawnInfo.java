package dev.connectplus.lobby.protocol.packets.model;

import com.viaversion.viaversion.api.minecraft.GlobalBlockPosition;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's CommonPlayerSpawnInfo (MIT, Copyright (c) 2024 Lenni0451).
 */
public class CommonPlayerSpawnInfo {

    public int dimension;
    public String world;
    public long levelSeed;
    public int gamemode;
    public int previousGamemode;
    public boolean debugWorld;
    public boolean flatWorld;
    public GlobalBlockPosition lastDeath;
    public int portalCooldown;
    public int seaLevel;

    public CommonPlayerSpawnInfo(final int dimension, final String world, final long levelSeed, final int gamemode, final int previousGamemode, final boolean debugWorld, final boolean flatWorld, final GlobalBlockPosition lastDeath, final int portalCooldown, final int seaLevel) {
        this.dimension = dimension;
        this.world = world;
        this.levelSeed = levelSeed;
        this.gamemode = gamemode;
        this.previousGamemode = previousGamemode;
        this.debugWorld = debugWorld;
        this.flatWorld = flatWorld;
        this.lastDeath = lastDeath;
        this.portalCooldown = portalCooldown;
        this.seaLevel = seaLevel;
    }

    public static CommonPlayerSpawnInfo read(final ByteBuf byteBuf) {
        final int dimension = PacketTypes.readVarInt(byteBuf);
        final String world = PacketTypes.readString(byteBuf, 128);
        final long levelSeed = byteBuf.readLong();
        final int gamemode = byteBuf.readUnsignedByte();
        final int previousGamemode = byteBuf.readUnsignedByte();
        final boolean debugWorld = byteBuf.readBoolean();
        final boolean flatWorld = byteBuf.readBoolean();
        final GlobalBlockPosition lastDeath = Types.OPTIONAL_GLOBAL_POSITION.read(byteBuf);
        final int portalCooldown = PacketTypes.readVarInt(byteBuf);
        final int seaLevel = PacketTypes.readVarInt(byteBuf);
        return new CommonPlayerSpawnInfo(dimension, world, levelSeed, gamemode, previousGamemode, debugWorld, flatWorld, lastDeath, portalCooldown, seaLevel);
    }

    public void write(final ByteBuf byteBuf) {
        PacketTypes.writeVarInt(byteBuf, this.dimension);
        PacketTypes.writeString(byteBuf, this.world);
        byteBuf.writeLong(this.levelSeed);
        byteBuf.writeByte(this.gamemode);
        byteBuf.writeByte(this.previousGamemode);
        byteBuf.writeBoolean(this.debugWorld);
        byteBuf.writeBoolean(this.flatWorld);
        Types.OPTIONAL_GLOBAL_POSITION.write(byteBuf, this.lastDeath);
        PacketTypes.writeVarInt(byteBuf, this.portalCooldown);
        PacketTypes.writeVarInt(byteBuf, this.seaLevel);
    }
}
