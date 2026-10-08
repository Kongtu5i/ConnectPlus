package dev.connectplus.lobby.protocol.packets.play;

import dev.connectplus.lobby.LobbyConstants;
import dev.connectplus.lobby.protocol.packets.model.CommonPlayerSpawnInfo;
import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * The play-state JoinGame packet. Derived from MiniConnect's S2CLoginPacket
 * (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CLoginPacket implements Packet {

    public int playerId;
    public boolean hardcode;
    public int maxPlayers;
    public int chunkRenderDistance;
    public int simulationDistance;
    public boolean reduceDebugInfo;
    public boolean showDeathScreen;
    public boolean doLimitedCrafting;
    public CommonPlayerSpawnInfo spawnInfo;
    public boolean enforceSecureChat;

    public S2CLoginPacket(final int playerId, final boolean hardcode, final int maxPlayers, final int chunkRenderDistance, final int simulationDistance, final boolean reduceDebugInfo, final boolean showDeathScreen, final boolean doLimitedCrafting, final CommonPlayerSpawnInfo spawnInfo, final boolean enforceSecureChat) {
        this.playerId = playerId;
        this.hardcode = hardcode;
        this.maxPlayers = maxPlayers;
        this.chunkRenderDistance = chunkRenderDistance;
        this.simulationDistance = simulationDistance;
        this.reduceDebugInfo = reduceDebugInfo;
        this.showDeathScreen = showDeathScreen;
        this.doLimitedCrafting = doLimitedCrafting;
        this.spawnInfo = spawnInfo;
        this.enforceSecureChat = enforceSecureChat;
    }

    public S2CLoginPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.playerId = byteBuf.readInt();
        this.hardcode = byteBuf.readBoolean();
        final int dimensionCount = PacketTypes.readVarInt(byteBuf);
        for (int i = 0; i < dimensionCount; i++) {
            PacketTypes.readString(byteBuf, 128);
        }
        this.maxPlayers = PacketTypes.readVarInt(byteBuf);
        this.chunkRenderDistance = PacketTypes.readVarInt(byteBuf);
        this.simulationDistance = PacketTypes.readVarInt(byteBuf);
        this.reduceDebugInfo = byteBuf.readBoolean();
        this.showDeathScreen = byteBuf.readBoolean();
        this.doLimitedCrafting = byteBuf.readBoolean();
        this.spawnInfo = CommonPlayerSpawnInfo.read(byteBuf);
        this.enforceSecureChat = byteBuf.readBoolean();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        byteBuf.writeInt(this.playerId);
        byteBuf.writeBoolean(this.hardcode);
        PacketTypes.writeVarInt(byteBuf, LobbyConstants.DIMENSIONS.length);
        for (final String dimension : LobbyConstants.DIMENSIONS) {
            PacketTypes.writeString(byteBuf, dimension);
        }
        PacketTypes.writeVarInt(byteBuf, this.maxPlayers);
        PacketTypes.writeVarInt(byteBuf, this.chunkRenderDistance);
        PacketTypes.writeVarInt(byteBuf, this.simulationDistance);
        byteBuf.writeBoolean(this.reduceDebugInfo);
        byteBuf.writeBoolean(this.showDeathScreen);
        byteBuf.writeBoolean(this.doLimitedCrafting);
        this.spawnInfo.write(byteBuf);
        byteBuf.writeBoolean(this.enforceSecureChat);
    }
}
