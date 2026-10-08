package dev.connectplus.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyPlayerMigrationTest {

    private static final UUID CONFIRMED = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final UUID UNCONFIRMED = UUID.fromString("87654321-4321-4321-8321-cba987654321");

    @TempDir
    File tempDir;

    private File legacyFile(final UUID uuid) {
        return new File(tempDir, uuid + ".json");
    }

    private File javaFile(final UUID uuid) {
        return new File(new File(tempDir, "java"), uuid + ".json");
    }

    private File backupFile(final UUID uuid) {
        return new File(new File(tempDir, "migration-backup"), uuid + ".json");
    }

    @Test
    void unconfirmedOwnershipIsNotMovedAndIsReported() throws IOException {
        Files.writeString(legacyFile(UNCONFIRMED).toPath(), "{\"bookmarks\":[]}", StandardCharsets.UTF_8);

        final LegacyPlayerMigration.MigrationReport report = new LegacyPlayerMigration(tempDir).migrate(Set.of(CONFIRMED));

        assertTrue(legacyFile(UNCONFIRMED).exists(), "files with unclear ownership stay put");
        assertFalse(javaFile(UNCONFIRMED).exists());
        assertTrue(report.unconfirmed().contains(UNCONFIRMED), "the file is reported for manual confirmation");
        assertTrue(report.moved().isEmpty());
        assertTrue(report.conflicts().isEmpty());
    }

    @Test
    void confirmedOwnershipMovesTheFileKeepsABackupAndRemovesTheOldLocation() throws IOException {
        final String content = "{\"bookmarks\":[],\"account\":\"still-encrypted-blob\"}";
        Files.writeString(legacyFile(CONFIRMED).toPath(), content, StandardCharsets.UTF_8);

        final LegacyPlayerMigration.MigrationReport report = new LegacyPlayerMigration(tempDir).migrate(Set.of(CONFIRMED));

        assertTrue(report.moved().contains(CONFIRMED));
        assertFalse(legacyFile(CONFIRMED).exists(), "the old location is removed only after the target write was confirmed");
        assertEquals(content, Files.readString(javaFile(CONFIRMED).toPath(), StandardCharsets.UTF_8),
                "the migrated profile must be a verbatim copy");
        assertEquals(content, Files.readString(backupFile(CONFIRMED).toPath(), StandardCharsets.UTF_8),
                "a verbatim backup of the original (token still encrypted) must be kept before the move");
        assertTrue(report.unconfirmed().isEmpty());
        assertTrue(report.conflicts().isEmpty());
    }

    @Test
    void anExistingTargetIsNeverOverwrittenOrMerged() throws IOException {
        Files.writeString(legacyFile(CONFIRMED).toPath(), "{\"bookmarks\":[],\"account\":\"legacy\"}", StandardCharsets.UTF_8);
        Files.createDirectories(javaFile(CONFIRMED).getParentFile().toPath());
        Files.writeString(javaFile(CONFIRMED).toPath(), "{\"bookmarks\":[],\"account\":\"newer\"}", StandardCharsets.UTF_8);

        final LegacyPlayerMigration.MigrationReport report = new LegacyPlayerMigration(tempDir).migrate(Set.of(CONFIRMED));

        assertTrue(report.conflicts().contains(CONFIRMED), "the conflict must be reported");
        assertEquals("{\"bookmarks\":[],\"account\":\"newer\"}",
                Files.readString(javaFile(CONFIRMED).toPath(), StandardCharsets.UTF_8),
                "the existing target must not be overwritten or merged");
        assertTrue(legacyFile(CONFIRMED).exists(), "the legacy file is kept next to the untouched target");
    }

    @Test
    void anIdenticalTargetStillCountsAsAConflictAndNeverAutoResolves() throws IOException {
        //A previous run may have copied the file and died before removing the old
        //location: both files are kept and reported, the migrator never deletes by itself.
        final String content = "{\"bookmarks\":[],\"account\":\"enc\"}";
        Files.writeString(legacyFile(CONFIRMED).toPath(), content, StandardCharsets.UTF_8);
        Files.createDirectories(javaFile(CONFIRMED).getParentFile().toPath());
        Files.writeString(javaFile(CONFIRMED).toPath(), content, StandardCharsets.UTF_8);

        final LegacyPlayerMigration.MigrationReport report = new LegacyPlayerMigration(tempDir).migrate(Set.of(CONFIRMED));

        assertTrue(report.conflicts().contains(CONFIRMED));
        assertTrue(legacyFile(CONFIRMED).exists(), "the legacy file stays until resolved manually");
        assertTrue(javaFile(CONFIRMED).exists(), "the target stays untouched");
    }

    @Test
    void rerunningTheMigrationLosesNoData() throws IOException {
        final String content = "{\"bookmarks\":[{\"name\":\"bm\",\"address\":\"a.example.net\",\"createdAt\":1,\"lastConnectedAt\":2}],\"account\":\"enc\"}";
        Files.writeString(legacyFile(CONFIRMED).toPath(), content, StandardCharsets.UTF_8);

        final LegacyPlayerMigration.MigrationReport first =
                new LegacyPlayerMigration(tempDir).migrate(Set.of(CONFIRMED, UNCONFIRMED));
        assertTrue(first.moved().contains(CONFIRMED));
        final String afterFirstRun = Files.readString(javaFile(CONFIRMED).toPath(), StandardCharsets.UTF_8);

        final LegacyPlayerMigration.MigrationReport second =
                new LegacyPlayerMigration(tempDir).migrate(Set.of(CONFIRMED, UNCONFIRMED));

        assertTrue(second.moved().isEmpty(), "a completed migration is a no-op on re-run");
        assertTrue(second.conflicts().isEmpty(), "a completed migration must not turn into a conflict");
        assertEquals(afterFirstRun, Files.readString(javaFile(CONFIRMED).toPath(), StandardCharsets.UTF_8),
                "the migrated profile is untouched by the re-run");
        assertEquals(content, Files.readString(backupFile(CONFIRMED).toPath(), StandardCharsets.UTF_8),
                "the backup is untouched by the re-run");
        assertFalse(legacyFile(CONFIRMED).exists());
    }

    @Test
    void nonUuidFilesAndTheLinkIndexAreIgnored() throws IOException {
        Files.writeString(new File(tempDir, "identity-links.json").toPath(),
                "{\"schemaVersion\":1,\"revision\":1,\"links\":[]}", StandardCharsets.UTF_8);
        Files.writeString(new File(tempDir, "notes.txt").toPath(), "not a profile", StandardCharsets.UTF_8);
        Files.writeString(new File(tempDir, "not-a-uuid.json").toPath(), "{}", StandardCharsets.UTF_8);

        final LegacyPlayerMigration.MigrationReport report = new LegacyPlayerMigration(tempDir).migrate(Set.of());

        assertTrue(report.unconfirmed().isEmpty(), "non-UUID files are not legacy profiles");
        assertTrue(report.moved().isEmpty());
        assertTrue(new File(tempDir, "identity-links.json").exists(), "the link index is never treated as a legacy profile");
    }

    @Test
    void aMissingPlayersDirectoryYieldsAnEmptyReport() {
        final File notInstalled = new File(tempDir, "not-installed");

        assertDoesNotThrow(() -> {
            final LegacyPlayerMigration.MigrationReport report =
                    new LegacyPlayerMigration(notInstalled).migrate(Set.of(CONFIRMED));
            assertTrue(report.moved().isEmpty());
            assertTrue(report.unconfirmed().isEmpty());
        });
    }

}
