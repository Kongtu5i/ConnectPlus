package dev.connectplus.lobby.states;

import dev.connectplus.lobby.LobbyConstants;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.protocol.packets.config.C2SClientInformationPacket;
import dev.connectplus.lobby.protocol.packets.config.S2CRegistryDataPacket;
import dev.connectplus.lobby.protocol.packets.config.S2CUpdateTagsPacket;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.lenni0451.mcstructs.nbt.tags.CompoundTag;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;

import java.util.Map;

/**
 * Derived from MiniConnect's ConfigurationStateHandler (MIT, Copyright (c) 2024 Lenni0451).
 * M6: the client settings (sent here before the play state) are captured for
 * the per-player locale (bilingual texts, F1.4).
 */
public class ConfigurationStateHandler extends StateHandler {

    public ConfigurationStateHandler(final LobbyServerHandler handler, final Channel channel) {
        super(handler, channel);

        for (final Map.Entry<String, CompoundTag> entry : LobbyConstants.REGISTRIES.entrySet()) {
            this.send(new S2CRegistryDataPacket(entry.getKey(), entry.getValue()));
        }
        this.send(new S2CUpdateTagsPacket(LobbyConstants.TAGS));
        this.send(new S2CConfigFinishConfigurationPacket());
    }

    @EventHandler
    public void handle(final C2SClientInformationPacket packet) {
        if (this.handler.getSession() != null) {
            this.handler.getSession().locale = packet.locale;
        }
    }

    @EventHandler
    public void handle(final C2SConfigFinishConfigurationPacket packet) {
        this.setState(ConnectionState.PLAY);
    }
}
