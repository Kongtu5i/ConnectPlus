package dev.connectplus.routing;

public final class ConnectionRouter {

    private ConnectionRouter() {
    }

    public static RouteDecision route(final String handshakeHost, final String mode) {
        if ("proxy".equalsIgnoreCase(mode)) {
            return RouteDecision.DIRECT;
        }
        if (WildcardDomainSyntax.matches(handshakeHost)) {
            return RouteDecision.DIRECT;
        }
        return RouteDecision.LOBBY;
    }
}
