package dev.connectplus.session;

import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.session.PlayerVisitStore.CorruptIndexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the persistent visit and name index: fresh-file semantics,
 * dedup by the current link snapshot, temporary-identity merging, corruption
 * handling and write-failure visibility.
 */
class PlayerVisitStoreTest {

    private static final UUID JAVA_A = UUID.nameUUIDFromBytes("java-a".getBytes(StandardCharsets.UTF_8));
    private static final String XUID_X = "12345678901234567";
    private static final String XUID_Y = "98765432109876543";

    @TempDir
    File dir;

    private File indexFile() {
        return new File(this.dir, "visits-index.json");
    }

    private static ClientIdentity verifiedBedrock(final UUID wire, final String xuid) {
        return ClientIdentity.verifiedBedrock(wire, "BedrockPlayer", xuid, UUID.randomUUID(), "bridge-session");
    }

    // ---- fresh index -----------------------------------------------------

    @Test
    void freshIndexStartsEmptyAndDoesNotImportOldProfiles() throws IOException {
        //Leftovers from earlier versions in the same directory must never be
        //imported as visit history
        final File players = new File(this.dir, "players");
        players.mkdirs();
        Files.writeString(new File(players, "some-old-player.json").toPath(),
                "{\"name\":\"OldPlayer\",\"bookmarks\":[]}");
        Files.writeString(this.indexFile().toPath().resolveSibling("identity-links.json").toAbsolutePath(),
                "{\"schemaVersion\":1,\"revision\":4,\"links\":[{\"xuid\":\"1\",\"javaUuid\":\"" + JAVA_A + "\"}]}");

        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        assertEquals(0, store.snapshot().uniqueVisitorCount(Map.of()),
                "A missing index starts from an empty history");
        assertTrue(store.snapshot().visits().isEmpty());
    }

    // ---- dedup by the current links --------------------------------------

    @Test
    void repeatedVisitsCountOnceAndSurviveRestart() throws IOException {
        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        store.recordVisit(JAVA_A, "JavaA", ClientIdentity.verifiedJava(JAVA_A, "JavaA", JAVA_A));
        store.recordVisit(JAVA_A, "JavaA", ClientIdentity.verifiedJava(JAVA_A, "JavaA", JAVA_A));
        assertEquals(1, store.snapshot().uniqueVisitorCount(Map.of()), "A repeated visit must not add a second visitor");

        //Restart: the index is read back from disk
        final PlayerVisitStore reloaded = new PlayerVisitStore(this.indexFile());
        assertEquals(1, reloaded.snapshot().uniqueVisitorCount(Map.of()), "The visit count survives a restart");
    }

    @Test
    void linkedIdentitiesMergeAndUnlinkSplitsAgain() throws IOException {
        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        store.recordVisit(JAVA_A, "JavaA", ClientIdentity.verifiedJava(JAVA_A, "JavaA", JAVA_A));
        store.recordVisit(UUID.nameUUIDFromBytes("bedrock-wire".getBytes(StandardCharsets.UTF_8)), "BedrockX",
                verifiedBedrock(UUID.nameUUIDFromBytes("bedrock-wire".getBytes(StandardCharsets.UTF_8)), XUID_X));

        assertEquals(2, store.snapshot().uniqueVisitorCount(Map.of()), "Unlinked: two visitors");
        assertEquals(1, store.snapshot().uniqueVisitorCount(Map.of(XUID_X, JAVA_A)),
                "X linked to A: the shared person counts once");
        assertEquals(2, store.snapshot().uniqueVisitorCount(Map.of()),
                "Unlinking restores the two separate visitors");
        assertEquals(1, new PlayerVisitStore(this.indexFile()).snapshot().uniqueVisitorCount(Map.of(XUID_X, JAVA_A)),
                "The link-time dedup works on the reloaded index too");
    }

    @Test
    void knownJavaNameDoesNotAddAVisitor() throws IOException {
        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        store.recordVisit(UUID.nameUUIDFromBytes("bedrock-wire".getBytes(StandardCharsets.UTF_8)), "BedrockX",
                verifiedBedrock(UUID.nameUUIDFromBytes("bedrock-wire".getBytes(StandardCharsets.UTF_8)), XUID_X));
        store.recordKnownJavaName(JAVA_A, "JavaA");

        assertEquals(1, store.snapshot().uniqueVisitorCount(Map.of()),
                "Only X actually entered the lobby; the known Java name is not a visit");
        assertEquals(1, store.snapshot().uniqueVisitorCount(Map.of(XUID_X, JAVA_A)),
                "The linked merge still counts exactly one");
        //The known name is queryable (case-insensitive) and points at the Java UUID
        final List<PlayerVisitStore.NameMatch> matches = store.snapshot().matchByName("javaa");
        assertEquals(1, matches.size());
        assertEquals(JAVA_A, matches.get(0).javaUuid());
    }

    // ---- temporary identity merging --------------------------------------

    @Test
    void temporaryWireIdentityMergesIntoTheConfirmedBedrockIdentity() throws IOException {
        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        final UUID wire = UUID.nameUUIDFromBytes("bedrock-wire".getBytes(StandardCharsets.UTF_8));

        store.recordVisit(wire, "BedrockX", null);
        assertEquals(1, store.snapshot().uniqueVisitorCount(Map.of()), "The temporary visit counts once");
        assertTrue(store.snapshot().visits().get(0).temporary(), "The unconfirmed visit is marked temporary");

        //The same connection's identity is confirmed later (async verification):
        //the temporary record must fold into the confirmed one, not add a visitor
        store.recordVisit(wire, "BedrockX", verifiedBedrock(wire, XUID_X));
        final PlayerVisitStore.Snapshot snapshot = store.snapshot();
        assertEquals(1, snapshot.uniqueVisitorCount(Map.of()), "The confirmed visit must not double count");
        assertEquals(1, snapshot.visits().size());
        assertFalse(snapshot.visits().get(0).temporary(), "The merged record is confirmed");
        assertEquals(XUID_X, snapshot.visits().get(0).xuid());
        assertEquals(wire, snapshot.visits().get(0).wireUuid(), "The original protocol UUID is kept");
    }

    @Test
    void temporaryIdentitySurvivesRestartAndCanStillMerge() throws IOException {
        final UUID wire = UUID.nameUUIDFromBytes("bedrock-wire".getBytes(StandardCharsets.UTF_8));
        final PlayerVisitStore first = new PlayerVisitStore(this.indexFile());
        first.recordVisit(wire, "BedrockX", null);

        final PlayerVisitStore reloaded = new PlayerVisitStore(this.indexFile());
        reloaded.recordVisit(wire, "BedrockX", verifiedBedrock(wire, XUID_X));
        assertEquals(1, reloaded.snapshot().uniqueVisitorCount(Map.of()),
                "A temporary record from before the restart merges into the confirmed identity");
    }

    // ---- concurrency -----------------------------------------------------

    @Test
    void concurrentUpdatesOfDifferentPlayersDoNotLoseData() throws Exception {
        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        final int threads = 4;
        final int perThread = 12;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int base = t * 1000;
            executor.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    store.recordVisit(UUID.nameUUIDFromBytes(("conc-" + base + "-" + i).getBytes(StandardCharsets.UTF_8)),
                            "ConcPlayer" + base + "-" + i, null);
                }
                return null;
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "The concurrent updates must finish");

        final PlayerVisitStore.Snapshot snapshot = store.snapshot();
        assertEquals(threads * perThread, snapshot.uniqueVisitorCount(Map.of()),
                "No concurrent update may be lost");
        assertEquals(threads * perThread, new PlayerVisitStore(this.indexFile()).snapshot().uniqueVisitorCount(Map.of()),
                "Every concurrent update must be committed to disk");
    }

    // ---- corruption ------------------------------------------------------

    @Test
    void corruptJsonIsReportedAndBytesArePreserved() throws IOException {
        final byte[] corrupt = "{ not valid json".getBytes(StandardCharsets.UTF_8);
        Files.write(this.indexFile().toPath(), corrupt);

        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        assertThrows(CorruptIndexException.class, store::snapshot,
                "A corrupt index must block queries, never report zero visitors");

        assertThrows(CorruptIndexException.class, () ->
                        store.recordVisit(JAVA_A, "JavaA", ClientIdentity.verifiedJava(JAVA_A, "JavaA", JAVA_A)),
                "A corrupt index blocks mutations too");
        assertThrows(CorruptIndexException.class, store::snapshot);
        assertTrue(java.util.Arrays.equals(corrupt, Files.readAllBytes(this.indexFile().toPath())),
                "The corrupt file must be preserved, never overwritten with an empty history");
    }

    @Test
    void unknownSchemaAndMalformedEntriesAreReported() throws IOException {
        Files.writeString(this.indexFile().toPath(),
                "{\"schemaVersion\":99,\"visits\":[],\"known\":[]}");
        assertThrows(CorruptIndexException.class, () -> new PlayerVisitStore(this.indexFile()).snapshot(),
                "An unknown schema version must block, not be treated as empty");

        Files.writeString(this.indexFile().toPath(),
                "{\"schemaVersion\":1,\"visits\":[{\"key\":\"java:not-a-uuid\",\"name\":\"x\"}],\"known\":[]}");
        assertThrows(CorruptIndexException.class, () -> new PlayerVisitStore(this.indexFile()).snapshot(),
                "A malformed identity key must block");

        Files.writeString(this.indexFile().toPath(),
                "{\"schemaVersion\":1,\"visits\":[{\"key\":\"bedrock:12x34\",\"name\":\"x\"}],\"known\":[]}");
        assertThrows(CorruptIndexException.class, () -> new PlayerVisitStore(this.indexFile()).snapshot(),
                "A non-canonical XUID must block");

        assertTrue(Files.readString(this.indexFile().toPath()).contains("bedrock:12x34"),
                "The malformed file must stay in place");
    }

    // ---- write failure ---------------------------------------------------

    @Test
    void failedCommitThrowsAndPublishesNothing() throws IOException {
        //A regular file where the index's parent directory would be makes every
        //commit fail portably (read-only attributes are not portable)
        final File blocker = new File(this.dir, "blocker");
        blocker.mkdirs();
        final Path blocked = new File(blocker, "visits-index.json").toPath();
        assertTrue(blocker.delete());
        assertTrue(blocker.createNewFile(), "The blocker file occupies the directory path");

        final PlayerVisitStore store = new PlayerVisitStore(blocked.toFile());
        assertThrows(IOException.class, () ->
                        store.recordVisit(JAVA_A, "JavaA", ClientIdentity.verifiedJava(JAVA_A, "JavaA", JAVA_A)),
                "A failed disk commit must surface as an exception");
        assertEquals(0, store.snapshot().uniqueVisitorCount(Map.of()),
                "A failed commit must not publish a fake success snapshot");
    }

    // ---- snapshot shape --------------------------------------------------

    @Test
    void snapshotExposesNamesAndIdentityDetails() throws IOException {
        final PlayerVisitStore store = new PlayerVisitStore(this.indexFile());
        final UUID wireX = UUID.nameUUIDFromBytes("bedrock-wire".getBytes(StandardCharsets.UTF_8));
        store.recordVisit(JAVA_A, "JavaA", ClientIdentity.verifiedJava(JAVA_A, "JavaA", JAVA_A));
        store.recordVisit(wireX, "Bedrock X", verifiedBedrock(wireX, XUID_X));

        final PlayerVisitStore.Snapshot snapshot = store.snapshot();
        assertEquals(1, snapshot.matchByName("javaa").size(), "Name matching is case-insensitive");
        assertEquals(1, snapshot.matchByName("bedrock x").size(), "Bedrock names with spaces match");
        assertTrue(snapshot.matchByName("nobody").isEmpty());

        final PlayerVisitStore.VisitEntry bedrock = snapshot.visits().stream()
                .filter(v -> v.xuid() != null).findFirst().orElseThrow();
        assertNotNull(ProfileKey.bedrockProfile(bedrock.xuid()), "The stored XUID stays canonical");
        assertEquals("Bedrock X", bedrock.name());
        assertTrue(bedrock.lastSeenMillis() >= bedrock.firstSeenMillis());
    }

}
