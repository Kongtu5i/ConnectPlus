package dev.connectplus.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProfileKeyTest {

    private static final UUID OWNER = UUID.fromString("12345678-1234-4234-8234-123456789abc");

    @Test
    void javaProfileValueIsTheLowercaseHyphenatedUuid() {
        final ProfileKey key = ProfileKey.javaProfile(UUID.fromString("12345678-1234-4234-8234-123456789ABC"));

        assertEquals(ProfileKey.Kind.JAVA, key.kind());
        assertEquals("12345678-1234-4234-8234-123456789abc", key.value(), "UUID file names are lowercase hyphenated");
        assertEquals(OWNER, key.javaUuid());
        assertEquals("12345678-1234-4234-8234-123456789abc.json", key.fileName());
    }

    @Test
    void javaAndBedrockNamespacesAreNeverInterchangeable() {
        final ProfileKey java = ProfileKey.javaProfile(OWNER);
        final ProfileKey bedrock = ProfileKey.bedrockProfile("2533274790000001");

        assertEquals(ProfileKey.Kind.BEDROCK, bedrock.kind());
        assertEquals("2533274790000001", bedrock.value(), "a canonical XUID is echoed unchanged");
        assertNotEquals(java, bedrock, "the same player is a different key in each namespace");
        assertNull(bedrock.javaUuid(), "a bedrock profile has no Java UUID");
        //Equal keys require equal kind and value
        assertEquals(java, ProfileKey.javaProfile(OWNER));
        assertEquals(java.hashCode(), ProfileKey.javaProfile(OWNER).hashCode());
        assertNotEquals(java, ProfileKey.javaProfile(UUID.fromString("87654321-4321-4321-8321-cba987654321")));
    }

    @Test
    void xuidRangeBoundariesAreEnforcedWithoutSignedLongParsing() {
        assertEquals("1", ProfileKey.bedrockProfile("1").value());
        assertEquals("9", ProfileKey.bedrockProfile("9").value());
        assertEquals("18446744073709551615", ProfileKey.bedrockProfile("18446744073709551615").value(),
                "the maximum unsigned 64-bit value is valid");

        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("18446744073709551616"),
                "one above the maximum is out of range");
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("99999999999999999999"),
                "21 digits are always out of range");
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("0"),
                "zero is not a valid XUID");
    }

    @Test
    void xuidLeadingZerosAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("01"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("007"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("00000000000000000001"));
    }

    @Test
    void xuidPathCharactersAndNonDigitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("../secret"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("1/2"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("-1"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("1 2"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("1.5"));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile(""));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile(null));
        assertThrows(IllegalArgumentException.class, () -> ProfileKey.bedrockProfile("\u0661\u0662\u0663"),
                "non-ASCII digits are not decimal digits");
    }

    @Test
    void bedrockFileNamesStayInsideTheirDirectory() {
        //XUIDs are validated digits only, so a key can never contain path separators
        assertEquals("2533274790000001.json", ProfileKey.bedrockProfile("2533274790000001").fileName());
        assertEquals("1.json", ProfileKey.bedrockProfile("1").fileName());
    }

}
