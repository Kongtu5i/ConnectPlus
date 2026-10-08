package dev.connectplus.lobby.states;

import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.compat.LobbyLink;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;

import java.util.ArrayList;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * Derived from MiniConnect's LoginStateHandler (MIT, Copyright (c) 2024 Lenni0451).
 * Proxied logins use the original ViaProxy client identity across all versions and
 * lobby returns. Bare diagnostic connections retain the packet/offline-name fallback.
 *
 * <p>The uuid/name passed to {@code loadSession} (and echoed in
 * {@link S2CLoginGameProfilePacket}) are always the entry connection's ORIGINAL
 * wire identity: linking may only change which profile a session uses (the
 * ProfileKey resolved inside {@code loadSession}), never the protocol identity
 * the client sees (plan §2).</p>
 */
public class LoginStateHandler extends StateHandler {

    public LoginStateHandler(final LobbyServerHandler handler, final Channel channel) {
        super(handler, channel);
    }

    @EventHandler
    public void handle(final C2SLoginHelloPacket packet) {
        final var identity = LobbyLink.identityOf(this.channel);
        final String name = identity != null ? identity.name() : packet.name;
        UUID uuid = identity != null ? identity.uuid() : packet.uuid;
        if (uuid == null) {
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
        this.handler.loadSession(this.channel, uuid, name);
        this.send(new S2CLoginGameProfilePacket(uuid, name, new ArrayList<>()));
    }

    @EventHandler
    public void handle(final C2SLoginAcknowledgedPacket packet) {
        this.setState(ConnectionState.CONFIGURATION);
    }
}
