package dev.connectplus.identity;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves confirmed-ownership legacy player files ({@code players/<uuid>.json})
 * into the Java namespace ({@code players/java/<uuid>.json}).
 *
 * <p>Ownership is never guessed — a UUID-shaped file name, a v4 UUID or a
 * claimed player name are not proof. Only UUIDs provided by the operator
 * (reliable ownership records or an explicit migration mapping) are migrated;
 * every other legacy file stays put and is reported for manual confirmation.</p>
 *
 * <p>For every migrated file a verbatim byte backup is kept under
 * {@code players/migration-backup/} before anything moves (tokens in the backup
 * stay encrypted). The old location is removed only after the target write was
 * durably confirmed, so the migration is repeatable and loses no data. An
 * existing target is never overwritten, merged or auto-resolved — both files
 * are kept and the conflict is reported.</p>
 */
public final class LegacyPlayerMigration {

    /** A legacy profile file name: a standard hyphenated UUID (any case) + .json. */
    private static final Pattern LEGACY_FILE_NAME = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.json");

    private final File playersDir;

    public LegacyPlayerMigration(final File playersDir) {
        this.playersDir = playersDir;
    }

    /**
     * One idempotent migration run: only files still present in the legacy root
     * are touched, and each one is reported. Blocking IO.
     *
     * @param confirmedOwners the Java UUIDs whose legacy files reliably belong
     *        to a verified Java identity (never derived from the file name alone)
     * @return what this run moved, kept, reported and failed
     * @throws IOException when the players directory exists but cannot be listed
     */
    public MigrationReport migrate(final Set<UUID> confirmedOwners) throws IOException {
        Objects.requireNonNull(confirmedOwners, "confirmedOwners");
        final Set<UUID> moved = new LinkedHashSet<>();
        final Set<UUID> conflicts = new LinkedHashSet<>();
        final Set<UUID> unconfirmed = new LinkedHashSet<>();
        final Map<UUID, IOException> failures = new LinkedHashMap<>();

        final File[] entries = this.playersDir.listFiles();
        if (entries == null) {
            if (this.playersDir.exists()) {
                throw new IOException("players directory " + this.playersDir + " could not be listed");
            }
            return new MigrationReport(moved, conflicts, unconfirmed, failures); //nothing installed yet
        }
        for (final File entry : entries) {
            if (!entry.isFile()) {
                continue;
            }
            final UUID legacyUuid = legacyUuid(entry.getName());
            if (legacyUuid == null) {
                continue; //e.g. identity-links.json; never a legacy profile
            }
            if (!confirmedOwners.contains(legacyUuid)) {
                unconfirmed.add(legacyUuid); //ownership unclear: stays put, reported
                continue;
            }
            this.migrateOne(entry, legacyUuid, moved, conflicts, failures);
        }
        return new MigrationReport(moved, conflicts, unconfirmed, failures);
    }

    private void migrateOne(final File oldFile, final UUID legacyUuid,
                            final Set<UUID> moved, final Set<UUID> conflicts,
                            final Map<UUID, IOException> failures) {
        //ProfileKey is the sole owner of path-key validation
        final File target = new File(new File(this.playersDir, "java"), ProfileKey.javaProfile(legacyUuid).fileName());
        if (target.exists()) {
            conflicts.add(legacyUuid); //never overwrite, never merge, never auto-resolve
            return;
        }
        final File backup = new File(new File(this.playersDir, "migration-backup"), legacyUuid + ".json");
        try {
            Files.createDirectories(backup.getParentFile().toPath());
            if (!backup.exists()) {
                //Keep the first backup of the original; verbatim bytes, the token stays encrypted
                Files.copy(oldFile.toPath(), backup.toPath());
            }
            Files.createDirectories(target.getParentFile().toPath());
            Files.copy(oldFile.toPath(), target.toPath());
            if (Files.mismatch(oldFile.toPath(), target.toPath()) != -1) {
                //The confirmed target write did not land: remove the partial copy and fail
                Files.deleteIfExists(target.toPath());
                throw new IOException("the migrated copy of " + oldFile.getName() + " did not verify");
            }
            //Target durably written: only now remove the old location
            Files.delete(oldFile.toPath());
            moved.add(legacyUuid);
        } catch (final IOException e) {
            //The old file stays in place; the next run retries from a known state
            failures.put(legacyUuid, e);
        }
    }

    private static UUID legacyUuid(final String fileName) {
        if (!LEGACY_FILE_NAME.matcher(fileName).matches()) {
            return null;
        }
        return UUID.fromString(fileName.substring(0, fileName.length() - 5).toLowerCase(Locale.ROOT));
    }

    /** What one migration run did; all views are unmodifiable. */
    public static final class MigrationReport {

        private final Set<UUID> moved;
        private final Set<UUID> conflicts;
        private final Set<UUID> unconfirmed;
        private final Map<UUID, IOException> failures;

        private MigrationReport(final Set<UUID> moved, final Set<UUID> conflicts,
                                final Set<UUID> unconfirmed, final Map<UUID, IOException> failures) {
            this.moved = moved;
            this.conflicts = conflicts;
            this.unconfirmed = unconfirmed;
            this.failures = failures;
        }

        /** Legacy files moved into {@code java/} during this run. */
        public Set<UUID> moved() {
            return Collections.unmodifiableSet(this.moved);
        }

        /** Legacy files whose target already existed; both files were kept. */
        public Set<UUID> conflicts() {
            return Collections.unmodifiableSet(this.conflicts);
        }

        /** Legacy files with unclear ownership; they stay put until confirmed. */
        public Set<UUID> unconfirmed() {
            return Collections.unmodifiableSet(this.unconfirmed);
        }

        /** Legacy files that could not be migrated this run, with the failure. */
        public Map<UUID, IOException> failures() {
            return Collections.unmodifiableMap(this.failures);
        }

    }

}
