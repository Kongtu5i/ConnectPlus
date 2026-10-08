package dev.connectplus.switching;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.session.ConnectionInfo;
import io.netty.channel.Channel;

import javax.annotation.Nullable;

/**
 * The lobby-facing seam of the hot switch engine: the lobby GUI and the /connect
 * command hand their target over through this interface, so tests can observe the
 * handoff without a ViaProxy runtime. Implemented by {@link SwitchEngine}.
 */
public interface SwitchInitiator {

    /**
     * The outcome of a connect handoff; drives the lobby's chat feedback.
     */
    enum StartResult {
        /** The switch was initiated (the player stays in the lobby until the JoinGame). */
        STARTED,
        /** A switch is already running on this connection (design F2.3). */
        REJECTED_BUSY,
        /** The connect attempt was rejected by the rate limiter (F8.1). */
        REJECTED_RATE_LIMITED,
        /** The client wire format is not audited; its working connection is preserved. */
        REJECTED_UNSUPPORTED_PROTOCOL,
        /** The connection cannot switch at all (no proxy connection linked). */
        NOT_AVAILABLE,
        /** The access gate denied this connection (or its identity is unresolved). */
        REJECTED_ACCESS_DENIED
    }

    /**
     * Starts a seamless switch for the player sitting behind the given lobby
     * connection (the accepted channel of the lobby server).
     *
     * @param lobbyChannel the lobby-side channel of the player's connection
     * @param target       where to switch to (address, version or null = auto detect, player id)
     * @param account      the player's stored account; null for offline targets
     * @param playerName   the player name for logs and failure notices
     * @return why the handoff did not start; the caller is expected to tell the
     *         player in the lobby chat unless the result is {@link StartResult#STARTED}
     */
    StartResult startSwitchFromLobby(Channel lobbyChannel, ConnectionInfo target, @Nullable CPAccount account, String playerName);

}
