package dev.connectplus.identity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IdentityLinkStoreTest {

    private static final String XUID = "2533274790000001";
    private static final String OTHER_XUID = "2533274790000002";
    private static final UUID JAVA = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final UUID OTHER_JAVA = UUID.fromString("87654321-4321-4321-8321-cba987654321");

    @TempDir
    File tempDir;

    private IdentityLinkStore store() {
        return new IdentityLinkStore(tempDir);
    }

    private File indexFile() {
        return new File(tempDir, "identity-links.json");
    }

    @Test
    void missingIndexStartsAtRevisionZeroWithNoLinks() {
        final IdentityLinkStore.Snapshot snapshot = store().snapshot();

        assertEquals(0, snapshot.revision());
        assertTrue(snapshot.links().isEmpty());
        assertNull(snapshot.javaUuidFor(XUID));
    }

    @Test
    void replaceCommitsAtomicallyAndTheNewSnapshotReflectsTheCommittedRevision() throws IOException {
        final IdentityLinkStore store = store();
        store.replace(0, Map.of(XUID, JAVA));

        assertTrue(indexFile().exists(), "the index must be committed to disk");
        final JsonObject committed = JsonParser.parseString(
                Files.readString(indexFile().toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, committed.get("schemaVersion").getAsInt());
        assertEquals(1, committed.get("revision").getAsLong());
        assertEquals(XUID, committed.getAsJsonArray("links").get(0).getAsJsonObject().get("xuid").getAsString());
        assertEquals(JAVA.toString(), committed.getAsJsonArray("links").get(0).getAsJsonObject().get("javaUuid").getAsString(),
                "UUIDs are written lowercase hyphenated");

        final IdentityLinkStore.Snapshot snapshot = store.snapshot();
        assertEquals(1, snapshot.revision());
        assertEquals(JAVA, snapshot.javaUuidFor(XUID));
        assertEquals(Map.of(XUID, JAVA), snapshot.links());
    }

    @Test
    void replaceIncrementsTheRevisionMonotonically() throws IOException {
        final IdentityLinkStore store = store();
        store.replace(0, Map.of(XUID, JAVA));
        store.replace(1, Map.of(XUID, OTHER_JAVA));

        assertEquals(2, store.snapshot().revision());
        assertEquals(OTHER_JAVA, store.snapshot().javaUuidFor(XUID));
    }

    @Test
    void revisionConflictIsAnExplicitErrorThatOverwritesNothing() throws IOException {
        final IdentityLinkStore store = store();
        store.replace(0, Map.of(XUID, JAVA));
        final String committed = Files.readString(indexFile().toPath(), StandardCharsets.UTF_8);

        final IOException conflict = assertThrows(IOException.class,
                () -> store.replace(0, Map.of(XUID, OTHER_JAVA)),
                "a stale expected revision must fail instead of overwriting the later change");
        assertTrue(conflict.getMessage().contains("revision conflict"));
        assertEquals(committed, Files.readString(indexFile().toPath(), StandardCharsets.UTF_8),
                "the committed index must be byte-identical after a rejected replace");
        assertEquals(JAVA, store.snapshot().javaUuidFor(XUID),
                "the published snapshot stays the committed one");
    }

    @Test
    void corruptIndexIsRejectedAndNeverTreatedAsNoLinks() throws IOException {
        Files.writeString(indexFile().toPath(), "{ this is not json", StandardCharsets.UTF_8);
        final IdentityLinkStore store = store();

        assertThrows(IdentityLinkStore.CorruptIndexException.class, store::snapshot,
                "a corrupt index must block link access instead of looking empty");
        assertThrows(IdentityLinkStore.CorruptIndexException.class, () -> store.replace(0, Map.of()),
                "a corrupt index must never be overwritten by a replace");
    }

    @Test
    void duplicateJavaUuidMappingsInTheIndexAreRejected() throws IOException {
        Files.writeString(indexFile().toPath(),
                "{\"schemaVersion\":1,\"revision\":1,\"links\":["
                        + "{\"xuid\":\"" + XUID + "\",\"javaUuid\":\"" + JAVA + "\"},"
                        + "{\"xuid\":\"" + OTHER_XUID + "\",\"javaUuid\":\"" + JAVA + "\"}]}",
                StandardCharsets.UTF_8);

        assertThrows(IdentityLinkStore.CorruptIndexException.class, store()::snapshot,
                "one Java UUID must not be linked to two XUIDs");
    }

    @Test
    void duplicateXuidEntriesInTheIndexAreRejected() throws IOException {
        Files.writeString(indexFile().toPath(),
                "{\"schemaVersion\":1,\"revision\":1,\"links\":["
                        + "{\"xuid\":\"" + XUID + "\",\"javaUuid\":\"" + JAVA + "\"},"
                        + "{\"xuid\":\"" + XUID + "\",\"javaUuid\":\"" + OTHER_JAVA + "\"}]}",
                StandardCharsets.UTF_8);

        assertThrows(IdentityLinkStore.CorruptIndexException.class, store()::snapshot);
    }

    @Test
    void unknownSchemaVersionIsRejected() throws IOException {
        Files.writeString(indexFile().toPath(),
                "{\"schemaVersion\":2,\"revision\":1,\"links\":[]}", StandardCharsets.UTF_8);

        assertThrows(IdentityLinkStore.CorruptIndexException.class, store()::snapshot,
                "an unknown schema version must block access, not be read as v1 or as empty");
    }

    @Test
    void malformedLinkEntriesAreRejected() throws IOException {
        Files.writeString(indexFile().toPath(),
                "{\"schemaVersion\":1,\"revision\":1,\"links\":[{\"xuid\":\"" + XUID + "\"}]}",
                StandardCharsets.UTF_8);

        assertThrows(IdentityLinkStore.CorruptIndexException.class, store()::snapshot,
                "a link entry without a javaUuid must fail recognizably");
    }

    @Test
    void replaceEnforcesTheOneToOneMapping() throws IOException {
        final IdentityLinkStore store = store();

        assertThrows(IllegalArgumentException.class,
                () -> store.replace(0, Map.of(XUID, JAVA, OTHER_XUID, JAVA)),
                "two XUIDs must not share one Java UUID");
        assertFalse(indexFile().exists(), "a rejected mapping must not be written");
    }

    @Test
    void replaceRejectsNonCanonicalXuids() {
        assertThrows(IllegalArgumentException.class, () -> store().replace(0, Map.of("0" + XUID, JAVA)),
                "leading zeros are not canonical");
        assertThrows(IllegalArgumentException.class, () -> store().replace(0, Map.of("18446744073709551616", JAVA)),
                "out-of-range XUIDs are not canonical");
    }

    @Test
    void snapshotIsNotPublishedWhenTheDiskCommitFails() throws IOException {
        //A regular file where the players directory should be makes every write fail
        final File notADirectory = new File(tempDir, "not-a-directory");
        assertTrue(notADirectory.createNewFile());
        final IdentityLinkStore store = new IdentityLinkStore(notADirectory);

        assertThrows(IOException.class, () -> store.replace(0, Map.of(XUID, JAVA)),
                "a failed commit must be observable by the caller");
        final IdentityLinkStore.Snapshot snapshot = store.snapshot();
        assertEquals(0, snapshot.revision(), "no new revision may appear before the disk commit succeeded");
        assertTrue(snapshot.links().isEmpty(), "no links may appear before the disk commit succeeded");
    }

    @Test
    void racingReplacesWithTheSameExpectedRevisionCommitExactlyOne() throws Exception {
        //The read-check-commit sequence of replace must be one serial section:
        //racing replaces must yield exactly one commit and one explicit revision
        //conflict each, never a silently lost update (revision must advance once).
        final IdentityLinkStore store = store();
        final int racers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(racers);
        final CountDownLatch ready = new CountDownLatch(racers);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger conflicts = new AtomicInteger();
        final List<IOException> unexpected = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < racers; t++) {
            final int racer = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    //Distinct canonical XUID per racer so every winner would be a valid commit
                    final String xuid = String.format("25332747900000%02d", racer);
                    try {
                        store.replace(0, Map.of(xuid, JAVA));
                        successes.incrementAndGet();
                    } catch (final IOException e) {
                        if (e.getMessage() != null && e.getMessage().contains("revision conflict")) {
                            conflicts.incrementAndGet();
                        } else {
                            unexpected.add(e);
                        }
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, successes.get(), "exactly one replace may win the revision race");
        assertEquals(racers - 1, conflicts.get(), "every other replace must fail with an explicit revision conflict");
        assertTrue(unexpected.isEmpty(), "no unexpected failures: " + unexpected);
        final IdentityLinkStore.Snapshot committed = store.snapshot();
        assertEquals(1, committed.revision(), "the revision must advance exactly once, not once per racer");
        assertEquals(1, committed.links().size(), "only the winner's link may be committed");
    }

}
