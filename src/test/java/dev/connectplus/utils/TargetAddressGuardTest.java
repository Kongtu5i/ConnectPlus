package dev.connectplus.utils;

import dev.connectplus.config.CPConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetAddressGuardTest {

    private boolean original;

    @BeforeEach
    void setUp() {
        this.original = CPConfig.blockLocalTargets;
    }

    @AfterEach
    void tearDown() {
        CPConfig.blockLocalTargets = this.original;
    }

    @Test
    void blocksLocalRangesWhileEnabled() {
        CPConfig.blockLocalTargets = true;
        assertFalse(TargetAddressGuard.isAllowedHost("127.0.0.1"), "loopback");
        assertFalse(TargetAddressGuard.isAllowedHost("::1"), "IPv6 loopback");
        assertFalse(TargetAddressGuard.isAllowedHost("10.1.2.3"), "private 10/8");
        assertFalse(TargetAddressGuard.isAllowedHost("192.168.5.5"), "private 192.168/16");
        assertFalse(TargetAddressGuard.isAllowedHost("172.16.9.9"), "private 172.16/12");
        assertFalse(TargetAddressGuard.isAllowedHost("fe80::1"), "link-local");
        assertFalse(TargetAddressGuard.isAllowedHost("0.0.0.0"), "any-local");
    }

    @Test
    void blocksEveryAddressOfThisHost() throws Exception {
        //The plain range checks cannot recognize the host's own public/global
        //addresses (the manual verification topology dialed exactly such an address);
        //the interface enumeration must catch every one of them
        CPConfig.blockLocalTargets = true;
        final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        int checked = 0;
        while (interfaces.hasMoreElements()) {
            final Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
            while (addresses.hasMoreElements()) {
                final String host = addresses.nextElement().getHostAddress();
                assertFalse(TargetAddressGuard.isAllowedHost(host), "own host address must be blocked: " + host);
                checked++;
            }
        }
        assertTrue(checked > 0, "the test host must have at least one address");
    }

    @Test
    void allowsPublicAddresses() {
        CPConfig.blockLocalTargets = true;
        assertTrue(TargetAddressGuard.isAllowedHost("203.0.113.7"), "TEST-NET-3 documentation address");
        assertTrue(TargetAddressGuard.isAllowedHost("unresolvable.invalid"), "unresolvable hosts resolve when the proxy connects");
    }

    @Test
    void disablingTheGuardAllowsLocalTargets() {
        CPConfig.blockLocalTargets = false;
        assertTrue(TargetAddressGuard.isAllowedHost("127.0.0.1"));
        assertTrue(TargetAddressGuard.isAllowedHost("10.1.2.3"));
        assertTrue(TargetAddressGuard.isAllowedHost("fe80::1"));
    }

    @Test
    void blankHostIsRejectedWhileBlocking() {
        CPConfig.blockLocalTargets = true;
        assertFalse(TargetAddressGuard.isAllowedHost(""));
        assertFalse(TargetAddressGuard.isAllowedHost(null));
    }

}
