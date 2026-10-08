package dev.connectplus.lobby.states;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.model.HandshakeData;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;

/**
 * Derived from MiniConnect's HandshakeStateHandler (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: when no HAProxy 0xE0 TLV was parsed (bare connection), the
 * raw handshake packet host/port/version is stored as fallback handshake data — an
 * existing TLV value is never overwritten.
 */
public class HandshakeStateHandler extends StateHandler {

    public HandshakeStateHandler(final LobbyServerHandler handler, final Channel channel) {
        super(handler, channel);
    }

    @EventHandler
    public void handle(final C2SHandshakingClientIntentionPacket packet) {
        if (packet.protocolVersion != LobbyProtocol.VERSION.getVersion()) {
            this.channel.close();
            return;
        }
        if (this.channel.attr(CPAttributeKeys.HANDSHAKE_DATA).get() == null) {
            this.channel.attr(CPAttributeKeys.HANDSHAKE_DATA).set(new HandshakeData(packet.address, packet.port, ProtocolVersion.getProtocol(packet.protocolVersion)));
        }
        this.setState(packet.intendedState.getConnectionState());
    }
}
