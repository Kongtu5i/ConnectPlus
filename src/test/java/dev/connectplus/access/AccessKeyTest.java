package dev.connectplus.access;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessKeyTest {

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";

    @Test
    void sameValueKeysAreEqualAcrossInstances() {
        assertEquals(AccessKey.javaUuid(JAVA_UUID), AccessKey.javaUuid(JAVA_UUID));
        assertEquals(AccessKey.javaUuid(JAVA_UUID).hashCode(), AccessKey.javaUuid(JAVA_UUID).hashCode());
        assertEquals(AccessKey.bedrockXuid(XUID), AccessKey.bedrockXuid(XUID));
    }

    @Test
    void keyEqualityIncludesClientType() {
        // Even with identical identifier strings the namespaces are never interchangeable.
        assertNotEquals(AccessKey.javaUuid(JAVA_UUID), AccessKey.bedrockXuid(XUID));
    }

    @Test
    void javaKeyIsCanonicalLowercaseUuidForm() {
        final UUID mixedCase = UUID.fromString("00000000-0000-4000-8000-00000000000A");
        assertEquals("00000000-0000-4000-8000-00000000000a", AccessKey.javaUuid(mixedCase).id());
    }

    @Test
    void bedrockKeyRejectsNonCanonicalXuid() {
        assertThrows(Exception.class, () -> AccessKey.bedrockXuid(null));
        assertThrows(Exception.class, () -> AccessKey.bedrockXuid(""));
        assertThrows(Exception.class, () -> AccessKey.bedrockXuid("0123"));
        assertThrows(Exception.class, () -> AccessKey.bedrockXuid("2530000000000001a"));
        assertThrows(Exception.class, () -> AccessKey.bedrockXuid("18446744073709551616"));
    }

    @Test
    void javaKeyRejectsNullUuid() {
        assertThrows(NullPointerException.class, () -> AccessKey.javaUuid(null));
    }
}
