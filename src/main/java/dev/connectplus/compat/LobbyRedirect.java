package dev.connectplus.compat;

import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.routing.ConnectionRouter;
import dev.connectplus.routing.RouteDecision;
import dev.connectplus.session.ConnectionInfo;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.viaproxy.plugins.events.PreConnectEvent;

import java.util.function.Supplier;

/**
 * Routes incoming connections: lobby mode sends non-wildcard logins into the built-in
 * lobby, wildcard domains and proxy mode keep ViaProxy's native direct-connect behaviour.
 * Backend-initiated transfers (TRANSFER intent) fall through to the lobby without
 * the HAProxy forwarding; their follow policy is a later milestone.
 */
public class LobbyRedirect {

    private final Supplier<LobbyServer> lobbyServer;

    public LobbyRedirect(final Supplier<LobbyServer> lobbyServer) {
        this.lobbyServer = lobbyServer;
    }

    @EventHandler
    public void onPreConnect(final PreConnectEvent event) {
        //Status pings land in the lobby too: the lobby answers them itself (its own
        //protocol version), which also keeps the switch engine's version auto detection
        //working when a target is another ConnectPlus lobby. Their bare connections
        //carry no HAProxy preamble, HAProxyDetectHandler passes them through untouched.
        if (event.getIntendedState() != IntendedState.LOGIN && event.getIntendedState() != IntendedState.TRANSFER
                && event.getIntendedState() != IntendedState.STATUS) {
            return;
        }
        final String host = event.getClientHandshakeAddress() != null ? event.getClientHandshakeAddress().getHost() : "";
        if (ConnectionRouter.route(host, CPConfig.mode) != RouteDecision.LOBBY) {
            return;
        }
        final LobbyServer lobby = this.lobbyServer.get();
        if (lobby == null) {
            return;
        }
        if (event.getIntendedState() == IntendedState.LOGIN) {
            // Backend-initiated transfers and expired pending entries fall through to the
            // lobby without the HAProxy forwarding below, plain logins get it.
            event.getClientChannel().attr(CPAttributeKeys.ENABLE_HAPROXY).set(true);
        }
        event.setServerAddress(lobby.localAddress());
        event.setServerVersion(LobbyProtocol.VERSION);
    }
}
