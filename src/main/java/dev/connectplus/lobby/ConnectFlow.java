package dev.connectplus.lobby;

import dev.connectplus.session.ConnectionInfo;
import dev.connectplus.compat.AccountLoginPolicy;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.switching.SwitchInitiator;
import dev.connectplus.lobby.states.StateHandler;

import javax.annotation.Nullable;

/**
 * The connect flow shared by the GUI connect item and the transfer-confirmation screen: hands
 * the session's target to the hot switch engine, which seamlessly moves the player's
 * connection to the target server (M4). Since the switch is driven proxy-side, every
 * client version can use it (the former transfer packet required 1.20.5+).
 */
public final class ConnectFlow {

    private ConnectFlow() {
    }

    /**
     * Starts the switch for the given session. Returns {@code NOT_AVAILABLE} (and
     * sends nothing) when the session has no server address set yet; returns the
     * engine's outcome otherwise so the caller can tell the player why nothing
     * happened (M5: busy vs rate limited vs unavailable).
     *
     * <p>Task 6 §6 (connect target selection): the account rides the switch only
     * while the session's lease is still current — a result captured after a
     * displacement must never reconnect a backend with the old session's
     * credentials, so a stale-lease handoff carries {@code account = null}.</p>
     */
    public static SwitchInitiator.StartResult start(final PlayerSession session, final StateHandler out, @Nullable final SwitchInitiator switchInitiator) {
        if (session.serverAddress == null || switchInitiator == null) {
            return SwitchInitiator.StartResult.NOT_AVAILABLE;
        }
        final boolean credentialsPermitted = AccountLoginPolicy.isAllowedForSession(session) && !session.offlineMode()
                && session.account != null
                && dev.connectplus.lobby.screen.impl.AccountFlow.isLeaseCurrent(session, out.getHandler().getLeaseGranter());
        //The connection id travels with the target: it is the lease key the engine's
        //§6 switch-cache validation (and the reconnect chain, transfers and on-target
        //commands) re-checks — never the profile uuid, which is not a session key.
        return switchInitiator.startSwitchFromLobby(
                out.getChannel(),
                new ConnectionInfo(session.serverAddress, session.targetVersion, session.uuid, session.offlineMode(), session.connectionId),
                credentialsPermitted ? session.account : null,
                session.name);
    }

}
