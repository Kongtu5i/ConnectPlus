package dev.connectplus.session;

import com.google.gson.JsonParser;
import dev.connectplus.identity.ProfileKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStoreTest {

    @TempDir
    File tempDir;

    @Test
    void saveLoadRoundTrip() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());

        final PlayerData data = new PlayerData(key);
        final Bookmark bm1 = new Bookmark("survival", "mc.example.com:25565", "1.21.4", 1000L, 2000L);
        final Bookmark bm2 = new Bookmark("creative", "creative.example.com", null, 3000L, 0L);
        data.bookmarks.add(bm1);
        data.bookmarks.add(bm2);
        data.accountBlob = "enc-blob-123";
        store.save(key, data);

        final PlayerData loaded = store.load(key);
        assertEquals(key, loaded.key, "the loaded data carries its owner");
        assertEquals(2, loaded.bookmarks.size());
        assertEquals("survival", loaded.bookmarks.get(0).name);
        assertEquals("mc.example.com:25565", loaded.bookmarks.get(0).address);
        assertEquals("1.21.4", loaded.bookmarks.get(0).versionName);
        assertEquals(1000L, loaded.bookmarks.get(0).createdAt);
        assertEquals(2000L, loaded.bookmarks.get(0).lastConnectedAt);
        assertNull(loaded.bookmarks.get(1).versionName);
        assertEquals("enc-blob-123", loaded.accountBlob);
        assertTrue(new File(new File(tempDir, "java"), key.value() + ".json").exists(),
                "java profiles live in players/java/");
    }

    @Test
    void bedrockProfilesRoundTripInTheirOwnNamespace() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.bedrockProfile("2533274790000001");

        final PlayerData data = new PlayerData(key);
        data.bookmarks.add(new Bookmark("bm", "a.example.net", null, 1L, 2L));
        data.accountBlob = "enc-blob";
        store.save(key, data);

        final PlayerData loaded = store.load(key);
        assertEquals(key, loaded.key);
        assertEquals(1, loaded.bookmarks.size());
        assertEquals("enc-blob", loaded.accountBlob);
        assertTrue(new File(new File(tempDir, "bedrock"), "2533274790000001.json").exists(),
                "bedrock profiles live in players/bedrock/");
        assertFalse(new File(new File(tempDir, "java"), "2533274790000001.json").exists(),
                "an XUID must never be used as a Java file name");
    }

    @Test
    void loadMissingFileReturnsDefaultData() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final PlayerData data = store.load(ProfileKey.javaProfile(UUID.randomUUID()));
        assertTrue(data.bookmarks.isEmpty());
        assertNull(data.accountBlob);
        assertFalse(data.offlineMode, "New players default to normal account use");
    }

    @Test
    void legacyRootLayoutIsNoLongerRead() throws IOException {
        //Pre-migration files at the old players/<uuid>.json location belong to
        //LegacyPlayerMigration: the store must never read (or overwrite) them,
        //otherwise unconfirmed ownership would silently become a live profile.
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());
        Files.writeString(new File(tempDir, key.value() + ".json").toPath(),
                "{\"bookmarks\":[],\"account\":\"legacy\"}", StandardCharsets.UTF_8);

        final PlayerData data = store.load(key);
        assertTrue(data.bookmarks.isEmpty());
        assertNull(data.accountBlob, "the legacy root file must not be loaded");
    }

    @Test
    void offlineModePersistsWithoutChangingLoginOrBookmarksAndLegacyFilesDefaultOff() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());
        final PlayerData data = new PlayerData(key);
        data.offlineMode = true;
        data.saveLoginInfo = false;
        data.accountBlob = "kept-opaque-blob";
        data.bookmarks.add(new Bookmark("Kept", "example.net", null, 1L, 2L));
        store.save(key, data);
        final PlayerData loaded = store.load(key);
        assertTrue(loaded.offlineMode);
        assertFalse(loaded.saveLoginInfo);
        assertEquals(data.accountBlob, loaded.accountBlob);
        assertEquals("Kept", loaded.bookmarks.get(0).name);
        //A legacy file without the flag keeps the historic default
        final File file = new File(new File(tempDir, "java"), key.value() + ".json");
        Files.writeString(file.toPath(), "{\"bookmarks\":[],\"account\":\"legacy\"}", StandardCharsets.UTF_8);
        final PlayerData legacy = store.load(key);
        assertFalse(legacy.offlineMode);
        assertTrue(legacy.saveLoginInfo);
        assertEquals("legacy", legacy.accountBlob);
    }

    @Test
    void saveLoginInfoRoundTripsAndLegacyFilesDefaultToSaving() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());
        final PlayerData data = new PlayerData(key);
        data.saveLoginInfo = false;
        data.accountBlob = "enc-blob";
        store.save(key, data);
        final PlayerData loaded = store.load(key);
        assertFalse(loaded.saveLoginInfo, "the toggled-off state must round trip");
        assertEquals("enc-blob", loaded.accountBlob);

        //A legacy file without the flag keeps the historic save behavior
        final ProfileKey legacyKey = ProfileKey.javaProfile(UUID.randomUUID());
        Files.writeString(new File(new File(tempDir, "java"), legacyKey.value() + ".json").toPath(),
                "{\"bookmarks\": [], \"account\": \"legacy-blob\"}", StandardCharsets.UTF_8);
        final PlayerData legacyData = store.load(legacyKey);
        assertTrue(legacyData.saveLoginInfo, "legacy files default to saving the login");
        assertEquals("legacy-blob", legacyData.accountBlob);
    }

    @Test
    void deleteRemovesTheWholeFileAndToleratesAMissingOne() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());
        final PlayerData data = new PlayerData(key);
        data.bookmarks.add(new Bookmark("bm", "a.example.net", null, 1L, 2L));
        data.accountBlob = "enc-blob";
        store.save(key, data);

        store.delete(key);
        assertFalse(new File(new File(tempDir, "java"), key.value() + ".json").exists(),
                "the data file must be gone");
        assertTrue(store.load(key).bookmarks.isEmpty());

        store.delete(key); //deleting a missing file is already the desired state
    }

    @Test
    void corruptFileIsRejectedRecognizablyIsolatedAndKeptOnDisk() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey bad = ProfileKey.javaProfile(UUID.randomUUID());
        final ProfileKey good = ProfileKey.javaProfile(UUID.randomUUID());

        final PlayerData goodData = new PlayerData(good);
        goodData.bookmarks.add(new Bookmark("bm", "a.example.net", null, 1L, 2L));
        store.save(good, goodData);

        final File badFile = new File(new File(tempDir, "java"), bad.value() + ".json");
        Files.writeString(badFile.toPath(), "{ this is not json", StandardCharsets.UTF_8);

        assertThrows(PlayerStore.CorruptDataException.class, () -> store.load(bad),
                "a corrupt protected profile must fail recognizably, never load as empty data");
        assertEquals("bm", store.load(good).bookmarks.get(0).name, "other players unaffected");
        assertTrue(badFile.exists(), "corrupt file is kept on disk, not deleted");
    }

    @Test
    void shapeCorruptFilesAreRejectedInsteadOfLoadedEmpty() throws IOException {
        //A file that parses as JSON but has the wrong shape is equally corrupt:
        //loading it as empty data would let a later save overwrite the original.
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey bookmarksNotArray = ProfileKey.javaProfile(UUID.randomUUID());
        final ProfileKey bookmarkMissingFields = ProfileKey.javaProfile(UUID.randomUUID());
        final ProfileKey accountNotString = ProfileKey.javaProfile(UUID.randomUUID());
        final File javaDir = new File(tempDir, "java");
        Files.createDirectories(javaDir.toPath());
        Files.writeString(new File(javaDir, bookmarksNotArray.value() + ".json").toPath(),
                "{\"bookmarks\": 5}", StandardCharsets.UTF_8);
        Files.writeString(new File(javaDir, bookmarkMissingFields.value() + ".json").toPath(),
                "{\"bookmarks\": [{\"name\": \"x\"}]}", StandardCharsets.UTF_8);
        Files.writeString(new File(javaDir, accountNotString.value() + ".json").toPath(),
                "{\"account\": {\"nested\": true}}", StandardCharsets.UTF_8);

        assertThrows(PlayerStore.CorruptDataException.class, () -> store.load(bookmarksNotArray));
        assertThrows(PlayerStore.CorruptDataException.class, () -> store.load(bookmarkMissingFields));
        assertThrows(PlayerStore.CorruptDataException.class, () -> store.load(accountNotString));
    }

    @Test
    void saveFailureIsObservableByTheCaller() throws IOException {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());
        //A directory at the target path makes the atomic replace fail
        Files.createDirectories(new File(new File(tempDir, "java"), key.value() + ".json").toPath());
        final PlayerData data = new PlayerData(key);
        data.accountBlob = "enc-blob";

        assertThrows(IOException.class, () -> store.save(key, data),
                "a failed save must be observable, never silently swallowed");
    }

    @Test
    void saveRejectsDataWhoseOwnerDoesNotMatchTheKey() {
        final PlayerStore store = new PlayerStore(tempDir);
        final PlayerData data = new PlayerData(ProfileKey.javaProfile(UUID.randomUUID()));

        assertThrows(IllegalArgumentException.class,
                () -> store.save(ProfileKey.javaProfile(UUID.randomUUID()), data),
                "saving data under a different key would misfile the profile");
    }

    @Test
    void legacyConvenienceSaveKeepsItsObservableStateAndLogsInsteadOfThrowing() throws IOException {
        //The GUI save flows still use the legacy wrapper until tasks 3/5/6 rewire
        //them: a failed save must not crash the flow, matching today's behavior.
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());
        Files.createDirectories(new File(new File(tempDir, "java"), key.value() + ".json").toPath());

        assertDoesNotThrow(() -> store.save(new PlayerData(key)));
    }

    @Test
    void concurrentSavesProduceValidJson() throws Exception {
        final PlayerStore store = new PlayerStore(tempDir);
        final ProfileKey key = ProfileKey.javaProfile(UUID.randomUUID());
        final File target = new File(new File(tempDir, "java"), key.value() + ".json");
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        final CountDownLatch latch = new CountDownLatch(4);
        final java.util.List<AssertionError> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean(true);
        //A reader continuously loads while the writers save: it must never observe a partial file
        final Thread reader = new Thread(() -> {
            while (reading.get()) {
                try {
                    assertTrue(JsonParser.parseString(Files.readString(target.toPath())).isJsonObject(),
                            "Reader must only ever see a complete JSON object");
                } catch (final NoSuchFileException missing) {
                    //The first save has not landed yet; not a partial read
                } catch (final IOException e) {
                    failures.add(new AssertionError("Reader could not read the target file", e));
                } catch (final AssertionError e) {
                    failures.add(e);
                }
                try {
                    Thread.sleep(1);
                } catch (final InterruptedException interrupted) {
                    return;
                }
            }
        });
        reader.start();
        for (int t = 0; t < 4; t++) {
            final int thread = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        final PlayerData data = new PlayerData(key);
                        data.bookmarks.add(new Bookmark("bm" + thread + "-" + i, "a.example.net", null, i, i));
                        //Concurrent same-key replaces can transiently collide on Windows;
                        //a failed save is observable, so the caller retries explicitly.
                        IOException lastFailure = null;
                        for (int attempt = 0; attempt < 10; attempt++) {
                            try {
                                store.save(key, data);
                                lastFailure = null;
                                break;
                            } catch (final IOException e) {
                                lastFailure = e;
                                Thread.sleep(2);
                            }
                        }
                        if (lastFailure != null) {
                            failures.add(new AssertionError("Save still failing after retries", lastFailure));
                        }
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        reading.set(false);
        reader.join();
        pool.shutdownNow();

        assertTrue(failures.isEmpty(), "Concurrent save/load failures: " + failures);
        assertTrue(JsonParser.parseString(Files.readString(target.toPath())).isJsonObject());
        final File[] tmpFiles = new File(tempDir, "java").listFiles((d, n) -> n.endsWith(".tmp"));
        if (tmpFiles != null) {
            assertEquals(0, tmpFiles.length, "no leftover temp files");
        }
    }

}
