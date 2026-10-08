package dev.connectplus.lobby.protocol;

import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket;
import dev.connectplus.lobby.protocol.packets.config.S2CRegistryDataPacket;
import dev.connectplus.lobby.protocol.packets.config.S2CUpdateTagsPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CGameEventPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CKeepAlivePacket;
import dev.connectplus.lobby.protocol.packets.play.S2CLevelChunkWithLightPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CPlayerAbilitiesPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandSignedPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerButtonClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetDataPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenBookPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;

/**
 * Derived from MiniConnect's LobbyPacketRegistry (MIT, Copyright (c) 2024 Lenni0451).
 * Registers the custom lobby packets on top of NetMinecraft's defaults for v1_21_4.
 * M2: the container/chat packets are typed on both sides (read+write implemented),
 * so they are registered for the server and the scripted test client alike.
 */
public class LobbyPacketRegistry extends DefaultPacketRegistry {

    public LobbyPacketRegistry() {
        this(false);
    }

    public LobbyPacketRegistry(final boolean clientSide) {
        super(clientSide, LobbyProtocol.VERSION.getVersion());

        if (!clientSide) {
            //server side only: these s2c config packets are written by the lobby but never
            //read by it; their read() would needlessly reject on a scripted client
            this.registerPacket(MCPackets.S2C_CONFIG_REGISTRY_DATA, S2CRegistryDataPacket::new);
            this.registerPacket(MCPackets.S2C_CONFIG_UPDATE_TAGS, S2CUpdateTagsPacket::new);
        }
        //configuration (typed on both sides, read+write implemented): the client
        //settings carry the locale for the bilingual texts (M6 F1.4). The config
        //and play states share the wire layout but need separate packet classes
        //(the registry rejects one class for two ids), see M7.
        this.registerPacket(MCPackets.C2S_CONFIG_CLIENT_INFORMATION, C2SClientInformationPacket::new);
        this.registerPacket(MCPackets.C2S_CLIENT_INFORMATION, dev.connectplus.lobby.protocol.packets.config.C2SPlayClientInformationPacket::new);
        //play (typed on both sides, read implemented)
        this.registerPacket(MCPackets.C2S_CHAT, C2SChatPacket::new);
        this.registerPacket(MCPackets.C2S_CHAT_COMMAND, C2SChatCommandPacket::new);
        //The signed variant is what ViaVersion translates pre-1.19.1 slash commands
        //into (V7 manual verification finding): without this registration every
        //command from such clients arrives as an unknown packet and is silently dropped
        this.registerPacket(MCPackets.C2S_CHAT_COMMAND_SIGNED, C2SChatCommandSignedPacket::new);
        this.registerPacket(MCPackets.C2S_CONTAINER_CLICK, C2SContainerClickPacket::new);
        this.registerPacket(MCPackets.C2S_CONTAINER_CLOSE, C2SContainerClosePacket::new);
        this.registerPacket(MCPackets.C2S_CONTAINER_BUTTON_CLICK, C2SContainerButtonClickPacket::new);
        this.registerPacket(MCPackets.C2S_SET_CARRIED_ITEM, dev.connectplus.lobby.protocol.packets.play.c2s.C2SSetCarriedItemPacket::new);
        this.registerPacket(MCPackets.C2S_USE_ITEM, dev.connectplus.lobby.protocol.packets.play.c2s.C2SUseItemPacket::new);
        this.registerPacket(MCPackets.C2S_SWING, dev.connectplus.lobby.protocol.packets.play.c2s.C2SSwingPacket::new);
        this.registerPacket(MCPackets.C2S_USE_ITEM_ON, dev.connectplus.lobby.protocol.packets.play.c2s.C2SUseItemOnPacket::new);
        this.registerPacket(MCPackets.C2S_PLAYER_ACTION, dev.connectplus.lobby.protocol.packets.play.c2s.C2SPlayerActionPacket::new);
        this.registerPacket(MCPackets.C2S_SET_CREATIVE_MODE_SLOT, dev.connectplus.lobby.protocol.packets.play.c2s.C2SSetCreativeModeSlotPacket::new);
        this.registerPacket(MCPackets.C2S_MOVE_PLAYER_POS, dev.connectplus.lobby.protocol.packets.play.c2s.C2SPlayerPositionPacket::new);
        this.registerPacket(MCPackets.C2S_MOVE_PLAYER_POS_ROT, dev.connectplus.lobby.protocol.packets.play.c2s.C2SPlayerPositionRotationPacket::new);
        this.registerPacket(MCPackets.S2C_LOGIN, S2CLoginPacket::new);
        this.registerPacket(MCPackets.S2C_KEEP_ALIVE, S2CKeepAlivePacket::new);
        this.registerPacket(MCPackets.S2C_GAME_EVENT, S2CGameEventPacket::new);
        this.registerPacket(MCPackets.S2C_LEVEL_CHUNK_WITH_LIGHT, S2CLevelChunkWithLightPacket::new);
        this.registerPacket(MCPackets.S2C_PLAYER_ABILITIES, S2CPlayerAbilitiesPacket::new);
        this.registerPacket(MCPackets.S2C_PLAYER_POSITION, S2CPlayerPositionPacket::new);
        this.registerPacket(MCPackets.S2C_SYSTEM_CHAT, S2CSystemChatPacket::new);
        this.registerPacket(MCPackets.S2C_OPEN_SCREEN, S2COpenScreenPacket::new);
        this.registerPacket(MCPackets.S2C_CONTAINER_SET_CONTENT, S2CContainerSetContentPacket::new);
        this.registerPacket(MCPackets.S2C_CONTAINER_SET_DATA, S2CContainerSetDataPacket::new);
        this.registerPacket(MCPackets.S2C_CONTAINER_CLOSE, S2CContainerClosePacket::new);
        this.registerPacket(MCPackets.S2C_OPEN_BOOK, S2COpenBookPacket::new);
    }
}
