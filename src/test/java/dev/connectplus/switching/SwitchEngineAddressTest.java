package dev.connectplus.switching;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the transfer packet target rendering (M6: IPv6 literals must
 * be bracketed, review finding from the M5 handover).
 */
class SwitchEngineAddressTest {

    @Test
    void ipv4HostGetsPlainHostPort() {
        assertEquals("example.net:25565", SwitchEngine.transferAddress("example.net", 25565));
    }

    @Test
    void ipv6LiteralGetsBracketed() {
        assertEquals("[::1]:25565", SwitchEngine.transferAddress("::1", 25565));
        assertEquals("[2001:db8::1]:25565", SwitchEngine.transferAddress("2001:db8::1", 25565));
    }
}
