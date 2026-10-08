package dev.connectplus.identity;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The persistent transaction journal of the binding transactions (plan §4):
 * one file per operation at {@code players/.transactions/<operationId>.json},
 * written with the same atomic unique-temp-file + move discipline as every
 * other persistent artifact. The journal records ONLY the operation identity
 * and its phase — never credentials, bookmarks or profile content (plan §4:
 * the journal must not become a hidden archive of discarded data).
 *
 * <p>Phases (§4.1): PREPARED (before the index commit) → COMMITTED (the index
 * revision was atomically committed — THE binding commit point) →
 * CLEANUP_PENDING (the committed binding stays, the old bedrock profile's
 * deletion failed or was interrupted) → (file deleted when the cleanup
 * finished). A phase string alone never decides what happened during
 * recovery: the recovery re-derives the truth from the index revision, the
 * actual mapping and the journal together.</p>
 */
public class LinkTransactionJournal {

    /** The operation recorded in the journal. v1 only ever writes LINK. */
    public enum Operation { LINK, UNLINK }

    /**
     * The journal phase of an operation (§4.1 state machine). ACCESS_PENDING
     * (access lists, task 6) marks a COMMITTED binding whose blacklist
     * inheritance could not be written yet — the startup recovery replays the
     * recorded delta idempotently.
     */
    public enum Phase { PREPARED, COMMITTED, CLEANUP_PENDING, ACCESS_PENDING }

    /** One journal entry as read from disk. Immutable. */
    public static final class Entry {
        private final String operationId;
        private final Operation operation;
        private final String xuid;
        private final UUID javaUuid;
        private final long expectedRevision;
        private final long targetRevision;
        private final Phase phase;
        private final java.util.List<dev.connectplus.access.AccessEntry> blacklistAdds;

        Entry(final String operationId, final Operation operation, final String xuid, final UUID javaUuid,
              final long expectedRevision, final long targetRevision, final Phase phase) {
            this(operationId, operation, xuid, javaUuid, expectedRevision, targetRevision, phase, null);
        }

        /**
         * @param blacklistAdds the blacklist delta this committed binding still owes
         *                      (access lists, task 6); null/empty for operations without one.
         *                      Every phase transition MUST carry this list along
         *                      ({@link #withPhase}).
         */
        public Entry(final String operationId, final Operation operation, final String xuid, final UUID javaUuid,
                     final long expectedRevision, final long targetRevision, final Phase phase,
                     final java.util.List<dev.connectplus.access.AccessEntry> blacklistAdds) {
            this.operationId = operationId;
            this.operation = operation;
            this.xuid = xuid;
            this.javaUuid = javaUuid;
            this.expectedRevision = expectedRevision;
            this.targetRevision = targetRevision;
            this.phase = phase;
            this.blacklistAdds = blacklistAdds == null ? java.util.List.of() : java.util.List.copyOf(blacklistAdds);
        }

        /** A copy of this entry in the given phase, keeping the blacklist delta. */
        public static Entry withPhase(final Entry entry, final Phase phase) {
            return new Entry(entry.operationId, entry.operation, entry.xuid, entry.javaUuid,
                    entry.expectedRevision, entry.targetRevision, phase, entry.blacklistAdds);
        }

        public String operationId() { return this.operationId; }

        public Operation operation() { return this.operation; }

        /** The canonical XUID whose bedrock profile this operation touches. */
        public String xuid() { return this.xuid; }

        /** The Java UUID being linked (LINK) or unlinked (UNLINK). */
        public UUID javaUuid() { return this.javaUuid; }

        /** The index revision the operation observed when it started. */
        public long expectedRevision() { return this.expectedRevision; }

        /** The index revision this operation intends to commit. */
        public long targetRevision() { return this.targetRevision; }

        public Phase phase() { return this.phase; }

        /**
         * The blacklist delta of a committed binding (never null; empty for
         * pre-access records and operations without inheritance). The delta is
         * replayed idempotently by the startup recovery — existing entries with
         * the same primary key are never overwritten.
         */
        public java.util.List<dev.connectplus.access.AccessEntry> blacklistAdds() { return this.blacklistAdds; }
    }

    /** A journal file that cannot be trusted; recovery reports it instead of guessing. */
    public static final class CorruptJournalException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CorruptJournalException(final String message) {
            super(message);
        }

        CorruptJournalException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private final File playersDir;

    public LinkTransactionJournal(final File playersDir) {
        this.playersDir = Objects.requireNonNull(playersDir, "playersDir");
    }

    private File transactionsDir() {
        return new File(this.playersDir, ".transactions");
    }

    private File file(final String operationId) {
        //operationId is always a UUID produced by the service; the guard keeps a
        //hand-planted name from escaping the transactions directory
        if (!operationId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("operationId must be a UUID string: " + operationId);
        }
        return new File(this.transactionsDir(), operationId + ".json");
    }

    /**
     * Writes {@code entry} atomically (unique temp file + move), replacing any
     * previous phase of the same operation. Blocking IO: call on a storage
     * executor. The move lands the new phase in one step — a crash either
     * leaves the previous complete file or the new one, never a half-written
     * phase.
     */
    public void write(final Entry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        final Path target = this.file(entry.operationId()).toPath();
        final Path temp = target.resolveSibling(entry.operationId() + "-" + UUID.randomUUID() + ".json.tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, toJson(entry), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException ignored) {
            }
            throw new IOException("Failed to write the transaction journal entry " + entry.operationId(), e);
        }
    }

    /**
     * Reads the entry of one operation; null when no journal file exists for it.
     * A present but untrustworthy file throws {@link CorruptJournalException} —
     * recovery must not guess what an unreadable journal meant.
     */
    public Entry read(final String operationId) throws IOException {
        final Path path = this.file(operationId).toPath();
        if (!Files.exists(path)) {
            return null;
        }
        return parse(operationId, Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Every journal entry currently on disk, ordered by operation id. A corrupt
     * entry throws — the caller reports the repair need instead of silently
     * skipping an operation it cannot reason about.
     */
    public Map<String, Entry> readAll() throws IOException {
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final File[] files = this.transactionsDir().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return entries; // no transactions directory yet: nothing in flight
        }
        for (final File file : files) {
            final String name = file.getName();
            final String operationId = name.substring(0, name.length() - ".json".length());
            if (!operationId.matches("[0-9a-fA-F-]{36}")) {
                continue; // a stale temp file (unique per write) is never a journal entry
            }
            entries.put(operationId, parse(operationId, Files.readString(file.toPath(), StandardCharsets.UTF_8)));
        }
        return entries;
    }

    /**
     * Removes the journal file of a finished operation. A missing file is
     * already the desired state.
     */
    public void clear(final String operationId) throws IOException {
        Files.deleteIfExists(this.file(operationId).toPath());
    }

    // ---- (de)serialization --------------------------------------------------

    private static String toJson(final Entry entry) {
        final JsonObject object = new JsonObject();
        object.addProperty("operationId", entry.operationId());
        object.addProperty("operation", entry.operation().name());
        object.addProperty("xuid", entry.xuid());
        object.addProperty("javaUuid", entry.javaUuid().toString());
        object.addProperty("expectedRevision", entry.expectedRevision());
        object.addProperty("targetRevision", entry.targetRevision());
        object.addProperty("phase", entry.phase().name());
        if (!entry.blacklistAdds().isEmpty()) {
            final com.google.gson.JsonArray adds = new com.google.gson.JsonArray();
            for (final dev.connectplus.access.AccessEntry add : entry.blacklistAdds()) {
                adds.add(dev.connectplus.access.AccessListStore.entryToJson(add));
            }
            object.add("blacklistAdds", adds);
        }
        return object.toString();
    }

    private Entry parse(final String operationId, final String raw) {
        final com.google.gson.JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(raw);
        } catch (final RuntimeException e) {
            throw new CorruptJournalException("The transaction journal " + operationId + " is not valid JSON", e);
        }
        if (!root.isJsonObject()) {
            throw new CorruptJournalException("The transaction journal " + operationId + " is not a JSON object");
        }
        final JsonObject object = root.getAsJsonObject();
        try {
            final String id = object.get("operationId").getAsString();
            if (!id.equals(operationId)) {
                throw new CorruptJournalException("The transaction journal file " + operationId
                        + " records a different operationId: " + id);
            }
            final Operation operation = Operation.valueOf(object.get("operation").getAsString());
            final String xuid = ProfileKey.bedrockProfile(object.get("xuid").getAsString()).value();
            final UUID javaUuid = UUID.fromString(object.get("javaUuid").getAsString());
            final long expectedRevision = object.get("expectedRevision").getAsLong();
            final long targetRevision = object.get("targetRevision").getAsLong();
            final Phase phase = Phase.valueOf(object.get("phase").getAsString());
            final java.util.List<dev.connectplus.access.AccessEntry> adds;
            if (object.has("blacklistAdds") && object.get("blacklistAdds").isJsonArray()) {
                adds = new java.util.ArrayList<>();
                try {
                    for (final com.google.gson.JsonElement element : object.getAsJsonArray("blacklistAdds")) {
                        adds.add(dev.connectplus.access.AccessListStore.entryFromJson(element.getAsJsonObject()));
                    }
                } catch (final IOException e) {
                    throw new CorruptJournalException("The transaction journal " + operationId
                            + " carries an invalid blacklistAdds entry", e);
                }
            } else {
                adds = java.util.List.of(); // old records: no field, no delta
            }
            return new Entry(id, operation, xuid, javaUuid, expectedRevision, targetRevision, phase, adds);
        } catch (final CorruptJournalException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw new CorruptJournalException("The transaction journal " + operationId + " has an invalid field", e);
        }
    }

}
