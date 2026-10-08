package dev.connectplus.routing;

/**
 * Mirrors ViaProxy's wildcard domain detection (Client2ProxyHandler PUBLIC mode):
 * format 1 "address_port_version.viaproxy.hostname" and
 * format 2 "address.<a>.port.<p>.version.<v>.f2.viaproxy.hostname".
 * Only decides whether the handshake targets a wildcard domain; parsing is left to ViaProxy.
 */
public final class WildcardDomainSyntax {

    private WildcardDomainSyntax() {
    }

    public static boolean matches(final String host) {
        if (host == null) {
            return false;
        }
        final String lower = host.toLowerCase();
        return lower.contains("f2.viaproxy.") || lower.contains("viaproxy.");
    }
}
