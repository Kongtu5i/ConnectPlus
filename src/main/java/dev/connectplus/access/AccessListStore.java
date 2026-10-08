package dev.connectplus.access;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One list file (whitelist.json / blacklist.json): strict loading, strict
 * validation, atomic saving and an immutable published snapshot.
 *
 * <p>Formats follow the design: {@code schemaVersion} 1 and a {@code players}
 * array whose entries carry {@code name}, lowercase {@code clientType} and the
 * type's primary identifier ({@code UUID} / {@code XUID}) with exactly that
 * casing; auxiliary identifiers are preserved but never used for matching.
 * A file that fails any rule never replaces the last good snapshot.</p>
 *
 * <p>Commits write a same-directory temp file and atomically move it; when the
 * filesystem cannot move atomically the commit fails instead of degrading, so
 * the live file is never truncated. The byte fingerprint of the loaded file is
 * kept: a commit over a file that changed on disk refuses to run until the
 * caller reloads, so an external edit is never clobbered by the stale memory
 * snapshot. {@link #reload()} never writes the file.</p>
 */
public final class AccessListStore {

    /** The only supported list file schema version. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * An immutable published state of the list. The revision is process-local
     * bookkeeping: it advances on every published change so callers can pass
     * the revision they based a decision on back into {@link #replace}.
     */
    public record Snapshot(long revision, boolean available, Map<AccessKey, AccessEntry> entries) {
        public Snapshot {
            entries = Map.copyOf(entries);
        }
    }

    /** The injectable atomic file commit; the default implementation moves a same-directory temp file. */
    public interface FileCommitter {
        void commit(Path target, byte[] content) throws IOException;
    }

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final FileCommitter committer;
    private Snapshot current = new Snapshot(0, false, Map.of());
    private String loadedFingerprint;

    public AccessListStore(final Path file) {
        this(file, AccessListStore::atomicCommit);
    }

    public AccessListStore(final Path file, final FileCommitter committer) {
        this.file = Objects.requireNonNull(file, "file");
        this.committer = Objects.requireNonNull(committer, "committer");
    }

    /** The currently published snapshot; available=false before the first valid load. */
    public synchronized Snapshot snapshot() {
        return this.current;
    }

    /**
     * Loads the file. A missing file is an error unless initializeIfMissing is
     * set, in which case an empty list file is created (first initialization of
     * a disabled list); a missing file for an enabled list is never generated away.
     */
    public synchronized Snapshot load(final boolean initializeIfMissing) throws IOException {
        if (!Files.exists(this.file)) {
            if (!initializeIfMissing) {
                throw new IOException(this.file.getFileName() + " is missing");
            }
            final byte[] empty = serialize(List.of());
            this.committer.commit(this.file, empty);
            return this.publish(empty, List.of());
        }
        final byte[] bytes = this.readBytes();
        return this.publish(bytes, parse(bytes));
    }

    /** Re-reads the file without ever writing it; any failure keeps the last good snapshot. */
    public synchronized Snapshot reload() throws IOException {
        final byte[] bytes = this.readBytes();
        return this.publish(bytes, parse(bytes));
    }

    /**
     * Validates the entries and atomically replaces the file content. The commit
     * refuses to run when the file changed on disk since the last load/reload
     * (reload first) or when the expected revision is stale. Duplicate primary
     * keys and invalid identifiers are rejected before anything is written.
     */
    public synchronized Snapshot replace(final long expectedRevision, final Collection<AccessEntry> entries)
            throws IOException {
        Objects.requireNonNull(entries, "entries");
        if (!this.current.available()) {
            throw new IOException(this.file.getFileName() + " has no loaded snapshot; load or reload first");
        }
        if (expectedRevision != this.current.revision()) {
            throw new IOException(this.file.getFileName() + " changed since revision " + expectedRevision
                    + " (current " + this.current.revision() + "); reload before committing");
        }
        final List<AccessEntry> validated = List.copyOf(entries);
        final Map<AccessKey, AccessEntry> map = new LinkedHashMap<>();
        for (final AccessEntry entry : validated) {
            if (map.putIfAbsent(entry.key(), entry) != null) {
                throw new IOException("duplicate primary key " + entry.key());
            }
        }
        final byte[] bytes = serialize(validated);
        this.assertUnchangedOnDisk();
        this.committer.commit(this.file, bytes);
        return this.publish(bytes, validated);
    }

    // ---- internals ---------------------------------------------------------

    private byte[] readBytes() throws IOException {
        try {
            return Files.readAllBytes(this.file);
        } catch (final IOException e) {
            throw new IOException(this.file.getFileName() + " could not be read", e);
        }
    }

    private void assertUnchangedOnDisk() throws IOException {
        if (!Files.exists(this.file)) {
            throw new IOException(this.file.getFileName() + " vanished; reload before committing");
        }
        final String onDisk = fingerprint(this.readBytes());
        if (!onDisk.equals(this.loadedFingerprint)) {
            throw new IOException(this.file.getFileName()
                    + " was modified outside this process; reload before committing");
        }
    }

    private Snapshot publish(final byte[] bytes, final List<AccessEntry> entries) {
        final Map<AccessKey, AccessEntry> map = new LinkedHashMap<>();
        for (final AccessEntry entry : entries) {
            map.put(entry.key(), entry);
        }
        this.loadedFingerprint = fingerprint(bytes);
        this.current = new Snapshot(this.current.revision() + 1, true, map);
        return this.current;
    }

    private static byte[] serialize(final List<AccessEntry> entries) {
        final JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", SCHEMA_VERSION);
        final JsonArray players = new JsonArray();
        for (final AccessEntry entry : entries) {
            players.add(entryToJson(entry));
        }
        document.add("players", players);
        return PRETTY_GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
    }

    /** One entry as the designed JSON object ({@code name}/{@code clientType}/{@code UUID}/{@code XUID}). */
    public static JsonObject entryToJson(final AccessEntry entry) {
        final JsonObject player = new JsonObject();
        if (entry.displayName() != null) {
            player.addProperty("name", entry.name());
        }
        player.addProperty("clientType", entry.clientType() == AccessKey.ClientType.JAVA ? "java" : "bedrock");
        if (entry.uuid() != null) {
            player.addProperty("UUID", entry.uuid().toString());
        }
        if (entry.xuid() != null) {
            player.addProperty("XUID", entry.xuid());
        }
        return player;
    }

    /** Parses one entry object; invalid identifiers throw {@link IOException}. */
    public static AccessEntry entryFromJson(final JsonObject player) throws IOException {
        return parseEntry(player);
    }

    private static List<AccessEntry> parse(final byte[] bytes) throws IOException {
        final JsonObject document;
        try {
            document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (final RuntimeException e) {
            throw new IOException("list file is not a JSON object", e);
        }
        if (!document.has("schemaVersion") || !document.get("schemaVersion").isJsonPrimitive()
                || document.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
            throw new IOException("unsupported or missing schemaVersion");
        }
        if (!document.has("players") || !document.get("players").isJsonArray()) {
            throw new IOException("players must be an array");
        }
        final Map<AccessKey, AccessEntry> map = new LinkedHashMap<>();
        for (final JsonElement element : document.getAsJsonArray("players")) {
            final AccessEntry entry = parseEntry(element);
            if (map.putIfAbsent(entry.key(), entry) != null) {
                throw new IOException("duplicate primary key " + entry.key());
            }
        }
        return List.copyOf(map.values());
    }

    private static AccessEntry parseEntry(final JsonElement element) throws IOException {
        if (!element.isJsonObject()) {
            throw new IOException("a player entry must be an object");
        }
        final JsonObject player = element.getAsJsonObject();
        final String clientType = optionalString(player, "clientType");
        final String name = optionalString(player, "name");
        final String uuidField = optionalString(player, "UUID");
        final String xuidField = optionalString(player, "XUID");
        final UUID uuid;
        final String xuid;
        switch (clientType == null ? "" : clientType) {
            case "java" -> {
                if (uuidField == null) {
                    throw new IOException("a java entry requires UUID");
                }
                uuid = parseUuid(uuidField);
                xuid = xuidField;
            }
            case "bedrock" -> {
                if (xuidField == null) {
                    throw new IOException("a bedrock entry requires XUID");
                }
                xuid = xuidField;
                uuid = uuidField == null ? null : parseUuid(uuidField);
            }
            default -> throw new IOException("clientType must be lowercase \"java\" or \"bedrock\": " + clientType);
        }
        try {
            // Constructors and key building validate the canonical identifier forms.
            final AccessEntry entry = new AccessEntry(AccessKey.ClientType.valueOf(clientType.toUpperCase(Locale.ROOT)),
                    name, uuid, xuid);
            entry.key();
            return entry;
        } catch (final RuntimeException e) {
            throw new IOException("invalid player entry: " + e.getMessage(), e);
        }
    }

    private static String optionalString(final JsonObject object, final String field) throws IOException {
        if (!object.has(field)) {
            return null;
        }
        final JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static UUID parseUuid(final String raw) throws IOException {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            throw new IOException("UUID must be the standard hyphenated form: " + raw, e);
        }
    }

    private static String fingerprint(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new UncheckedIOException(new IOException("SHA-256 unavailable", e));
        }
    }

    /**
     * The production commit: a unique same-directory temp file plus an atomic
     * move. When the filesystem cannot move atomically the commit fails — the
     * live file is never degraded to a non-atomic in-place write.
     */
    /**
     * Test-visible handle to the production commit (temp file + atomic move):
     * counting/failing {@link FileCommitter} doubles call this for the commits
     * they let through, so the byte fingerprint state stays honest.
     */
    public static void atomicCommitForTests(final Path target, final byte[] content) throws IOException {
        atomicCommit(target, content);
    }

    private static void atomicCommit(final Path target, final byte[] content) throws IOException {
        final Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException ignored) {
            }
            throw new IOException("atomic rename is not supported for " + target + "; refusing a non-atomic commit", e);
        } catch (final IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException ignored) {
            }
            throw new IOException("failed to commit " + target.getFileName(), e);
        }
    }
}
