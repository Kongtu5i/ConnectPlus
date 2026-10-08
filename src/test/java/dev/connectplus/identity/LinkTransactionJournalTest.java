package dev.connectplus.identity;

import dev.connectplus.access.AccessEntry;
import dev.connectplus.access.AccessKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LinkTransactionJournalTest {

    private static final String XUID = "2530000000000001";
    private static final UUID JAVA = UUID.fromString("12345678-1234-4234-8234-123456789abc");

    @TempDir
    File tempDir;

    private LinkTransactionJournal journal() {
        return new LinkTransactionJournal(this.tempDir);
    }

    private static LinkTransactionJournal.Entry entry(final LinkTransactionJournal.Phase phase) {
        return new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID, JAVA, 0, 1, phase);
    }

    @Test
    void accessPendingPhaseExistsAndRoundTrips() throws IOException {
        final LinkTransactionJournal journal = this.journal();
        final LinkTransactionJournal.Entry prepared = entry(LinkTransactionJournal.Phase.PREPARED);
        journal.write(prepared);
        journal.write(LinkTransactionJournal.Entry.withPhase(prepared, LinkTransactionJournal.Phase.ACCESS_PENDING));

        assertEquals(LinkTransactionJournal.Phase.ACCESS_PENDING, journal.read(prepared.operationId()).phase());
    }

    @Test
    void blacklistAddsRoundTripThroughTheJournal() throws IOException {
        final LinkTransactionJournal journal = this.journal();
        final List<AccessEntry> adds = List.of(
                new AccessEntry(AccessKey.ClientType.BEDROCK, "小明", null, XUID),
                new AccessEntry(AccessKey.ClientType.JAVA, null, JAVA, null));
        final LinkTransactionJournal.Entry prepared = new LinkTransactionJournal.Entry(
                UUID.randomUUID().toString(), LinkTransactionJournal.Operation.LINK, XUID, JAVA, 0, 1,
                LinkTransactionJournal.Phase.PREPARED, adds);
        journal.write(prepared);

        final LinkTransactionJournal.Entry read = journal.read(prepared.operationId());
        assertEquals(2, read.blacklistAdds().size());
        assertEquals(AccessKey.bedrockXuid(XUID), read.blacklistAdds().get(0).key());
        assertEquals("小明", read.blacklistAdds().get(0).name());
        assertEquals(AccessKey.javaUuid(JAVA), read.blacklistAdds().get(1).key());
        assertNull(read.blacklistAdds().get(1).name());
    }

    @Test
    void oldRecordsWithoutTheFieldReadAsEmpty() throws IOException {
        final LinkTransactionJournal journal = this.journal();
        final LinkTransactionJournal.Entry legacy = entry(LinkTransactionJournal.Phase.PREPARED);
        journal.write(legacy);

        final LinkTransactionJournal.Entry read = journal.read(legacy.operationId());
        assertTrue(read.blacklistAdds().isEmpty(), "a pre-access journal record has no adds");
        assertEquals(LinkTransactionJournal.Phase.PREPARED, read.phase());
    }

    @Test
    void phaseTransitionsKeepBlacklistIntent() throws IOException {
        final LinkTransactionJournal journal = this.journal();
        final List<AccessEntry> adds = List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, null, null, XUID));
        final LinkTransactionJournal.Entry prepared = new LinkTransactionJournal.Entry(
                UUID.randomUUID().toString(), LinkTransactionJournal.Operation.LINK, XUID, JAVA, 0, 1,
                LinkTransactionJournal.Phase.PREPARED, adds);

        journal.write(LinkTransactionJournal.Entry.withPhase(prepared, LinkTransactionJournal.Phase.CLEANUP_PENDING));
        assertEquals(1, journal.read(prepared.operationId()).blacklistAdds().size(),
                "the CLEANUP_PENDING transition keeps the delta");

        journal.write(LinkTransactionJournal.Entry.withPhase(prepared, LinkTransactionJournal.Phase.ACCESS_PENDING));
        assertEquals(1, journal.read(prepared.operationId()).blacklistAdds().size(),
                "the ACCESS_PENDING transition keeps the delta");
    }

    @Test
    void theLegacyConstructorStillProducesAnEntryWithoutAdds() {
        final LinkTransactionJournal.Entry legacy = entry(LinkTransactionJournal.Phase.COMMITTED);
        assertTrue(legacy.blacklistAdds().isEmpty());
    }
}
