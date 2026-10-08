package dev.connectplus.routing;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the loose version name lookup against ViaVersion's protocol list.
 */
class VersionNamesTest {

    @Test
    void exactNameMatches() {
        assertEquals(ProtocolVersion.v1_21_4, VersionNames.find("1.21.4"));
    }

    @Test
    void olderVersionMatches() {
        assertNotNull(VersionNames.find("1.12.2"));
    }

    @Test
    void nameIsTrimmed() {
        assertEquals(ProtocolVersion.v1_21_4, VersionNames.find(" 1.21.4 "));
    }

    @Test
    void unknownVersionReturnsNull() {
        assertNull(VersionNames.find("9.9.9"));
    }

    @Test
    void blankReturnsNull() {
        assertNull(VersionNames.find("   "));
        assertNull(VersionNames.find(null));
    }

    @Test
    void uniquePrefixMatches() {
        //No protocol is named exactly "1.7.2" and only "1.7.2-1.7.5" starts with it
        final ProtocolVersion byPrefix = VersionNames.find("1.7.2");
        assertNotNull(byPrefix, "The unique prefix '1.7.2' must resolve to the ranged 1.7.2-1.7.5 entry");
        assertEquals(VersionNames.find("1.7.2-1.7.5"), byPrefix);
    }

    @Test
    void ambiguousPrefixReturnsNull() {
        //Several protocol names start with "1." -> not a unique prefix hit
        assertNull(VersionNames.find("1."));
    }

}
