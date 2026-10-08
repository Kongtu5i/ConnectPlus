package dev.connectplus.lobby;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

/**
 * Single pinned protocol the lobby speaks. Clients of other versions are translated
 * to this version by ViaProxy before reaching the lobby.
 */
public final class LobbyProtocol {

    public static final ProtocolVersion VERSION = ProtocolVersion.v1_21_4;

    private LobbyProtocol() {
    }
}
