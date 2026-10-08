package dev.connectplus.access;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessListStoreTest {

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID JAVA_UUID_2 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String XUID = "2530000000000001";
    private static final String XUID_2 = "2530000000000002";

    @TempDir
    Path tempDir;

    private Path file() {
        return this.tempDir.resolve("whitelist.json");
    }

    private AccessListStore store() {
        return new AccessListStore(this.file());
    }

    private void write(final String json) throws IOException {
        Files.writeString(this.file(), json, StandardCharsets.UTF_8);
    }

    private static String javaEntryJson(final String name, final String uuid) {
        return "        {\"name\": " + com.google.gson.JsonParser.parseString(
                "{\"n\": \"" + (name == null ? "" : name) + "\"}").getAsJsonObject().get("n").toString()
                + ", \"clientType\": \"java\", \"UUID\": \"" + uuid + "\"}";
    }

    // ---- loading -----------------------------------------------------------

    @Test
    void missingFileLoadWithoutInitializeFailsAndCreatesNothing() {
        final AccessListStore store = store();
        assertThrows(IOException.class, () -> store.load(false));
        assertFalse(Files.exists(this.file()), "a missing enabled list must not be generated");
        assertFalse(store.snapshot().available());
    }

    @Test
    void initializeMissingCreatesEmptyAvailableListFile() throws IOException {
        final AccessListStore store = store();
        final AccessListStore.Snapshot snapshot = store.load(true);

        assertTrue(snapshot.available());
        assertTrue(snapshot.entries().isEmpty());
        assertTrue(Files.exists(this.file()), "first initialization of a closed list may create the empty file");
        final JsonObject committed = JsonParser.parseString(
                Files.readString(this.file(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, committed.get("schemaVersion").getAsInt());
        assertEquals(0, committed.getAsJsonArray("players").size());
    }

    @Test
    void roundTripPreservesNamesCaseAndCanonicalIdentifiers() throws IOException {
        // Uppercase UUID hex and mixed input must load; the saved output is canonical.
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [
                    {"name": "小明", "clientType": "java", "UUID": "00000000-0000-4000-8000-00000000000A"},
                    {"name": "Bedrock Player", "clientType": "bedrock", "XUID": "%s"}
                  ]
                }
                """.formatted(XUID));
        final AccessListStore store = store();
        final AccessListStore.Snapshot snapshot = store.load(false);

        assertTrue(snapshot.available());
        assertEquals(2, snapshot.entries().size());
        final AccessEntry java = snapshot.entries().get(AccessKey.javaUuid(UUID.fromString("00000000-0000-4000-8000-00000000000a")));
        assertEquals("小明", java.name());
        final AccessEntry bedrock = snapshot.entries().get(AccessKey.bedrockXuid(XUID));
        assertEquals("Bedrock Player", bedrock.name());

        final AccessListStore.Snapshot saved = store.replace(snapshot.revision(), snapshot.entries().values());
        final String raw = Files.readString(this.file(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("00000000-0000-4000-8000-00000000000a"), "UUIDs are saved lowercase hyphenated");
        assertTrue(raw.contains("XUID"), "the XUID field name keeps its designed casing");
        assertTrue(raw.contains("UUID"), "the UUID field name keeps its designed casing");
        assertTrue(raw.contains("小明"), "names are stored verbatim without platform suffixes");
        assertTrue(raw.contains(XUID), "the canonical XUID is stored as-is");
        assertEquals(saved.revision(), store.snapshot().revision());
    }

    @Test
    void renameDoesNotChangeKey() throws IOException {
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [%s]
                }
                """.formatted(javaEntryJson("Old", JAVA_UUID.toString())));
        final AccessListStore store = store();
        final AccessListStore.Snapshot loaded = store.load(false);

        store.replace(loaded.revision(), List.of(new AccessEntry(AccessKey.ClientType.JAVA, "New", JAVA_UUID, null)));
        final AccessListStore.Snapshot reloaded = store.reload();
        final AccessEntry entry = reloaded.entries().get(AccessKey.javaUuid(JAVA_UUID));
        assertEquals("New", entry.name());
        assertEquals(AccessKey.javaUuid(JAVA_UUID), entry.key());
    }

    @Test
    void emptyPlayersListLoadsAsAvailableAndEmpty() throws IOException {
        this.write("{\"schemaVersion\": 1, \"players\": []}");
        final AccessListStore store = store();
        final AccessListStore.Snapshot snapshot = store.load(false);

        assertTrue(snapshot.available());
        assertTrue(snapshot.entries().isEmpty());
    }

    @Test
    void sameNameDifferentKeysBothLoad() throws IOException {
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [
                    {"name": "小明", "clientType": "java", "UUID": "%s"},
                    {"name": "小明", "clientType": "bedrock", "XUID": "%s"}
                  ]
                }
                """.formatted(JAVA_UUID, XUID));
        final AccessListStore store = store();
        final AccessListStore.Snapshot snapshot = store.load(false);

        assertEquals(2, snapshot.entries().size());
        assertEquals("小明", snapshot.entries().get(AccessKey.javaUuid(JAVA_UUID)).name());
        assertEquals("小明", snapshot.entries().get(AccessKey.bedrockXuid(XUID)).name());
    }

    // ---- failure handling --------------------------------------------------

    @Test
    void reloadDoesNotRewriteBytesOrAcceptDuplicateKeys() throws IOException {
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [
                    {"name": "A", "clientType": "java", "UUID": "%s"},
                    {"name": "B", "clientType": "bedrock", "XUID": "%s"}
                  ]
                }
                """.formatted(JAVA_UUID, XUID));
        final AccessListStore store = store();
        store.load(false);
        final byte[] beforeReload = Files.readAllBytes(this.file());
        store.reload();
        final AccessListStore.Snapshot lastGood = store.snapshot();
        assertArrayEquals(beforeReload, Files.readAllBytes(this.file()), "reload never writes the file");

        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [
                    {"name": "A", "clientType": "java", "UUID": "%s"},
                    {"name": "A2", "clientType": "java", "UUID": "%s"}
                  ]
                }
                """.formatted(JAVA_UUID, JAVA_UUID));
        assertThrows(IOException.class, store::reload, "the same platform+primary key must not repeat");
        assertEquals(lastGood, store.snapshot());
    }

    @Test
    void bedrockAuxiliaryUuidNeverBecomesPrimaryKey() throws IOException {
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [
                    {"name": "B", "clientType": "bedrock", "XUID": "%s", "UUID": "%s"}
                  ]
                }
                """.formatted(XUID, JAVA_UUID_2));
        final AccessListStore store = store();
        final AccessListStore.Snapshot snapshot = store.load(false);

        assertEquals(1, snapshot.entries().size());
        final AccessEntry entry = snapshot.entries().get(AccessKey.bedrockXuid(XUID));
        assertNotNull(entry, "the bedrock entry is keyed by its XUID");
        assertEquals(JAVA_UUID_2, entry.uuid(), "a valid auxiliary UUID is preserved for display");
        assertNull(snapshot.entries().get(AccessKey.javaUuid(JAVA_UUID_2)),
                "the auxiliary UUID must not be usable for Java matching");
    }

    @Test
    void rejectsInvalidSchemaAndNonCanonicalXuid() throws IOException {
        final AccessListStore store = store();
        final List<String> badDocuments = List.of(
                "{\"schemaVersion\": 2, \"players\": []}",
                "{\"players\": []}",
                "{\"schemaVersion\": 1}",
                "{\"schemaVersion\": 1, \"players\": {}}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"JAVA\", \"UUID\": \""
                        + JAVA_UUID + "\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"console\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"java\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"java\", \"UUID\": \""
                        + JAVA_UUID.toString().replace("-", "") + "\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"java\", \"UUID\": \"not-a-uuid\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"bedrock\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"bedrock\", \"XUID\": \"0"
                        + XUID + "\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"bedrock\", \"XUID\": \"18446744073709551616\"}]}",
                "{\"schemaVersion\": 1, \"players\": [{\"name\": \"X\", \"clientType\": \"bedrock\", \"XUID\": \"" + XUID
                        + "\", \"UUID\": \"broken\"}]}"
        );
        for (int i = 0; i < badDocuments.size(); i++) {
            this.write(badDocuments.get(i));
            assertThrows(IOException.class, () -> store.load(false),
                    "document " + i + " must be rejected: " + badDocuments.get(i));
        }
        assertFalse(store.snapshot().available(), "no valid snapshot was ever published");
    }

    @Test
    void failedReplacePreservesBothLinkedEntriesAndOldBytes() throws IOException {
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [
                    {"name": "A", "clientType": "java", "UUID": "%s"},
                    {"name": "B", "clientType": "bedrock", "XUID": "%s"}
                  ]
                }
                """.formatted(JAVA_UUID, XUID));
        final AccessListStore store = new AccessListStore(this.file(), (target, content) -> {
            throw new IOException("simulated atomic move failure");
        });
        final AccessListStore.Snapshot loaded = store.load(false);
        final byte[] before = Files.readAllBytes(this.file());

        assertThrows(IOException.class, () -> store.replace(loaded.revision(),
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "OnlyJava", JAVA_UUID, null))));
        assertEquals(loaded, store.snapshot(), "both linked entries stay in the published snapshot");
        assertArrayEquals(before, Files.readAllBytes(this.file()), "the committed file bytes are untouched");
    }

    @Test
    void externalEditsRequireReloadBeforeCommandCommit() throws IOException {
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [%s]
                }
                """.formatted(javaEntryJson("A", JAVA_UUID.toString())));
        final AccessListStore store = store();
        final AccessListStore.Snapshot loaded = store.load(false);

        // An external editor adds a second entry behind our back.
        this.write("""
                {
                  "schemaVersion": 1,
                  "players": [
                    {"name": "A", "clientType": "java", "UUID": "%s"},
                    {"name": "B", "clientType": "bedrock", "XUID": "%s"}
                  ]
                }
                """.formatted(JAVA_UUID, XUID));
        assertThrows(IOException.class, () -> store.replace(loaded.revision(),
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Clobber", JAVA_UUID, null))),
                "a commit must never overwrite an unnoticed external edit with the stale memory snapshot");
        assertEquals(loaded, store.snapshot());

        final AccessListStore.Snapshot reloaded = store.reload();
        assertEquals(2, reloaded.entries().size(), "after reload the external content is live");
        final AccessListStore.Snapshot committed = store.replace(reloaded.revision(),
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null)));
        assertEquals(1, committed.entries().size());
        assertEquals(committed, store.snapshot());
    }

    @Test
    void replaceRejectsDuplicatePrimaryKeys() throws IOException {
        this.write("{\"schemaVersion\": 1, \"players\": []}");
        final AccessListStore store = store();
        final AccessListStore.Snapshot loaded = store.load(false);

        assertThrows(IOException.class, () -> store.replace(loaded.revision(), List.of(
                new AccessEntry(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null),
                new AccessEntry(AccessKey.ClientType.JAVA, "A2", JAVA_UUID, null))));
        assertEquals(loaded, store.snapshot());
    }

    @Test
    void reloadFailsWhenTheFileVanishesAfterALoad() throws IOException {
        this.write("{\"schemaVersion\": 1, \"players\": []}");
        final AccessListStore store = store();
        final AccessListStore.Snapshot loaded = store.load(false);
        Files.delete(this.file());

        assertThrows(IOException.class, store::reload);
        assertEquals(loaded, store.snapshot(), "the last good snapshot survives a vanished file");
    }

    // ---- helpers ------------------------------------------------------------

    @SuppressWarnings("unused")
    private static Map<AccessKey, AccessEntry> single(final AccessEntry entry) {
        return Map.of(entry.key(), entry);
    }

    @SuppressWarnings("unused")
    private static JsonArray players(final JsonObject document) {
        return document.getAsJsonArray("players");
    }
}
