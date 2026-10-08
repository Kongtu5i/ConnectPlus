package dev.connectplus.session;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The information a player needs to reach a target server on reconnect: the target
 * address, the protocol version the player should reconnect with (null = let the
 * proxy auto detect) and the player UUID used to verify the reconnecting client.
 *
 * @param address  the target server address in {@code host:port} form
 * @param version  the protocol version to reconnect with, or null for auto detection
 * @param playerId the UUID of the player this information belongs to (the session's
 *                 wire/profile uuid — never a session-registry key)
 * @param offlineMode use an offline backend identity without logging out of Microsoft
 * @param connectionId the ConnectPlus connection id of the session this switch runs
 *                     for (the c2p channel id, the session-registry and lease key);
 *                     the §6 switch-cache lease validation keys on it. Null only for
 *                     callers that carry no account and no session identity.
 */
public record ConnectionInfo(String address, @Nullable ProtocolVersion version, UUID playerId, boolean offlineMode,
                             @Nullable UUID connectionId) {
    public ConnectionInfo {
        address = ServerAddress.normalize(address);
    }
    public ConnectionInfo(final String address, @Nullable final ProtocolVersion version, final UUID playerId) {
        this(address, version, playerId, false, null);
    }

    public ConnectionInfo(final String address, @Nullable final ProtocolVersion version, final UUID playerId, final boolean offlineMode) {
        this(address, version, playerId, offlineMode, null);
    }
}
