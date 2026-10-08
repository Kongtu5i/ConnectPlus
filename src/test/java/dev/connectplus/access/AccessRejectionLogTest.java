package dev.connectplus.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessRejectionLogTest {

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";

    @AfterEach
    void reset() {
        AccessRejectionLog.resetForTests();
    }

    @Test
    void blacklistedLogHasWarningMarkerAndSanitizedName() {
        final List<String> lines = new ArrayList<>();
        AccessRejectionLog.putSinkForTests(record -> lines.add(record.severity() + "|" + record.line()));

        AccessRejectionLog.write(new AccessSubject(AccessKey.ClientType.JAVA, "A\nB\u001B[31mEvil", JAVA_UUID, null),
                new AccessPolicy.Decision(AccessPolicy.Reason.BLACKLISTED), "connect");

        assertEquals(1, lines.size());
        final String line = lines.get(0);
        assertTrue(line.contains("[BLACKLIST]"), "the blacklist hit carries its warning marker: " + line);
        assertFalse(line.contains("\n"), "no raw newline survives the sanitizing");
        assertFalse(line.contains("\u001B"), "no raw escape survives the sanitizing");
        assertTrue(line.contains("A?B?[31mEvil"), "the name is readable but harmless: " + line);
        assertTrue(line.contains(JAVA_UUID.toString()));
        assertTrue(line.contains("connect"), "the source is recorded");
        assertTrue(line.contains("BLACKLISTED"), "the reason is recorded");
    }

    @Test
    void otherReasonsLogWithoutTheBlacklistMarker() {
        final List<String> lines = new ArrayList<>();
        AccessRejectionLog.putSinkForTests(record -> lines.add(record.severity() + "|" + record.line()));

        AccessRejectionLog.write(new AccessSubject(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null),
                new AccessPolicy.Decision(AccessPolicy.Reason.NOT_WHITELISTED), "rule-update");

        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("[BLACKLIST]"));
        assertTrue(lines.get(0).contains("NOT_WHITELISTED"));
    }

    @Test
    void bedrockWithoutXuidLogsTheUnavailableIdentifier() {
        final List<String> lines = new ArrayList<>();
        AccessRejectionLog.putSinkForTests(record -> lines.add(record.severity() + "|" + record.line()));

        AccessRejectionLog.write(new AccessSubject(AccessKey.ClientType.BEDROCK, "Bedrock", null, null),
                new AccessPolicy.Decision(AccessPolicy.Reason.IDENTIFIER_UNAVAILABLE), "connect");

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("unavailable"), "the missing XUID is stated: " + lines.get(0));
        assertTrue(lines.get(0).contains("bedrock"));
    }

    @Test
    void theAvailableXuidIsRecordedForBedrock() {
        final List<String> lines = new ArrayList<>();
        AccessRejectionLog.putSinkForTests(record -> lines.add(record.severity() + "|" + record.line()));

        AccessRejectionLog.write(new AccessSubject(AccessKey.ClientType.BEDROCK, "BR", null, XUID),
                new AccessPolicy.Decision(AccessPolicy.Reason.BLACKLISTED), "connect");

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains(XUID));
        assertTrue(lines.get(0).contains("[BLACKLIST]"));
    }
}
