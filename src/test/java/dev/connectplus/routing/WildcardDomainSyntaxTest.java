package dev.connectplus.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WildcardDomainSyntaxTest {

    @Test
    void recognizesViaProxyWildcardFormats() {
        assertTrue(WildcardDomainSyntax.matches("address_port_version.viaproxy.hostname"));
        assertTrue(WildcardDomainSyntax.matches("a.b.c.port.25565.version.1.21.4.f2.viaproxy.hostname"));
        assertTrue(WildcardDomainSyntax.matches("viaproxy.foo.f2.viaproxy.host"));
    }

    @Test
    void rejectsPlainHosts() {
        assertFalse(WildcardDomainSyntax.matches("localhost"));
        assertFalse(WildcardDomainSyntax.matches("mc.hypixel.net"));
        assertFalse(WildcardDomainSyntax.matches(""));
        assertFalse(WildcardDomainSyntax.matches("127.0.0.1"));
        assertFalse(WildcardDomainSyntax.matches("my.viaproxyish.server"));
    }

    @Test
    void routerDecidesByModeAndSyntax() {
        assertEquals(RouteDecision.LOBBY, ConnectionRouter.route("mc.example.com", "lobby"));
        assertEquals(RouteDecision.DIRECT, ConnectionRouter.route("x_25565_v1.21.4.viaproxy.host", "lobby"));
        assertEquals(RouteDecision.DIRECT, ConnectionRouter.route("mc.example.com", "proxy"));
        assertEquals(RouteDecision.DIRECT, ConnectionRouter.route("", "proxy"));
    }

    @Test
    void routerTreatsUnknownModeAsLobby() {
        assertEquals(RouteDecision.LOBBY, ConnectionRouter.route("mc.example.com", "LOBBY"));
    }
}
