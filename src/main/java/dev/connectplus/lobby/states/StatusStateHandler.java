package dev.connectplus.lobby.states;

import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.config.CPConfig;
import com.google.gson.JsonObject;
import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.model.HandshakeData;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.netminecraft.packet.impl.status.C2SStatusPingRequestPacket;
import net.raphimc.netminecraft.packet.impl.status.C2SStatusRequestPacket;
import net.raphimc.netminecraft.packet.impl.status.S2CStatusPongResponsePacket;
import net.raphimc.netminecraft.packet.impl.status.S2CStatusResponsePacket;

/**
 * Derived from MiniConnect's StatusStateHandler (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: the status response echoes the client's own protocol
 * version (V7 manual verification finding) — the lobby is version-agnostic and
 * translated for every client version, so advertising the pinned 1.21.4 protocol
 * id made every older client's server list show an "outdated client" marker.
 */
public class StatusStateHandler extends StateHandler {

    public StatusStateHandler(final LobbyServerHandler handler, final Channel channel) {
        super(handler, channel);
    }

    @EventHandler
    public void handle(final C2SStatusPingRequestPacket packet) {
        this.send(new S2CStatusPongResponsePacket(packet.startTime));
    }

    @EventHandler
    public void handle(final C2SStatusRequestPacket packet) {
        final HandshakeData handshake = this.channel.attr(CPAttributeKeys.HANDSHAKE_DATA).get();
        final int protocol = handshake != null && handshake.clientVersion() != null
                ? handshake.clientVersion().getOriginalVersion()
                : LobbyProtocol.VERSION.getVersion();
        final JsonObject response = new JsonObject();
        final JsonObject players = new JsonObject();
        players.addProperty("max", 20);
        players.addProperty("online", 0);
        response.add("players", players);
        response.addProperty("description", CPConfig.motd == null ? "" : CPConfig.motd);
        final JsonObject version = new JsonObject();
        version.addProperty("protocol", protocol);
        version.addProperty("name", "ConnectPlus");
        response.add("version", version);
        this.send(new S2CStatusResponsePacket(response.toString()));
    }
}
