package dev.connectplus.session;

import dev.connectplus.compat.CompatSwitchSupport;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AddressNormalizationTest {
    @Test void fullWidthIpAndPortAreCanonicalBeforeConnecting() {
        assertEquals("192.168.1.194:25565", new ConnectionInfo("　１９２．１６８．１．１９４：２５５６５　", null, UUID.randomUUID()).address());
    }
    @Test void fullWidthIpv6BracketsAndColonAreCanonical() {
        assertEquals("[::1]:25565", new ConnectionInfo("［：：１］：２５５６５", null, UUID.randomUUID()).address());
    }
    @Test void unicodeDomainsArePreservedWhileAsciiWidthIsNormalized() {
        assertEquals("例子.测试:25565", new ConnectionInfo("例子．测试：２５５６５", null, UUID.randomUUID()).address());
        assertEquals("mc.example.net:25565", new ConnectionInfo("ｍｃ．ｅｘａｍｐｌｅ．ｎｅｔ：２５５６５", null, UUID.randomUUID()).address());
    }
    @Test void persistedAddressesAreNormalizedAtTheSharedParserToo() {
        final var address = assertInstanceOf(InetSocketAddress.class,
                assertDoesNotThrow(() -> CompatSwitchSupport.parseAddress("１２７．０．０．１：２５５６５", null)));
        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(25565, address.getPort());
    }
}
