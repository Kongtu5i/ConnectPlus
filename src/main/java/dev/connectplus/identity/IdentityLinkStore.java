package dev.connectplus.identity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The persistent XUID &lt;-&gt; JavaUUID link index at
 * {@code players/identity-links.json} (schemaVersion 1). The mapping is
 * strictly one-to-one: an XUID links to at most one Java UUID and a Java UUID
 * to at most one XUID.
 *
 * <p>Every accepted {@link #replace} commits atomically (unique temp file +
 * move) with a monotonically increasing revision; a replace whose expected
 * revision no longer matches the committed one fails explicitly and overwrites
 * nothing. A new revision only ever becomes visible through the disk commit:
 * readers see the committed state.</p>
 *
 * <p>A corrupt, duplicate-mapped or unknown-schema index is a recognizable
 * failure that blocks all link access ({@link #snapshot} and {@link #replace}
 * throw {@link CorruptIndexException}); it is never treated as "no links" and
 * never overwritten.</p>
 */
public class IdentityLinkStore {

    static final int SCHEMA_VERSION = 1;

    private final File playersDir;

    /**
     * Serializes the read-check-commit sequence of {@link #replace}. v1 is
     * explicitly single-process, so an instance lock is sufficient; it keeps
     * the check and the commit in the same serial execution domain so two
     * racing replaces can never both pass the revision check.
     */
    private final Object commitLock = new Object();

    public IdentityLinkStore(final File playersDir) {
        this.playersDir = playersDir;
    }

    /**
     * Thrown when the index on disk cannot be trusted (unreadable, invalid
     * JSON, unknown schema version, duplicate mappings, malformed entries).
     * This is a repair-needed state: linked-profile access stays blocked and
     * the index must not be treated as empty.
     */
    public static final class CorruptIndexException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CorruptIndexException(final String message) {
            super(message);
        }

        CorruptIndexException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /** An immutable committed revision plus its XUID -&gt; JavaUUID mapping. */
    public static final class Snapshot {
        private final long revision;
        private final Map<String, UUID> links;

        private Snapshot(final long revision, final Map<String, UUID> links) {
            this.revision = revision;
            this.links = Map.copyOf(links);
        }

        /** The committed revision this snapshot reflects; increases with every replace. */
        public long revision() {
            return this.revision;
        }

        /** The unmodifiable XUID -&gt; linked Java UUID mapping. */
        public Map<String, UUID> links() {
            return this.links;
        }

        /** The Java UUID linked to the given XUID, or null when it is unlinked. */
        public UUID javaUuidFor(final String xuid) {
            return this.links.get(xuid);
        }
    }

    /**
     * Reads the current committed state. A missing file is revision 0 with no
     * links; a corrupt file blocks access.
     */
    public Snapshot snapshot() {
        return this.readSnapshot();
    }

    /**
     * Atomically replaces the whole mapping with {@code links}, committing
     * revision {@code expectedRevision + 1}. Fails explicitly (and writes
     * nothing) when the committed revision differs from {@code expectedRevision}
     * — the links changed after they were observed. Blocking IO: call on a
     * storage executor.
     *
     * @throws IOException on the revision conflict or a failed disk commit
     * @throws IllegalArgumentException when {@code links} violates the one-to-one
     *         or canonical-form constraints
     * @throws CorruptIndexException when the current index cannot be trusted
     */
    public void replace(final long expectedRevision, final Map<String, UUID> links) throws IOException {
        Objects.requireNonNull(links, "links");
        final Map<String, UUID> validated = validateLinks(links);
        //The check and the commit must happen in one serial section: without the
        //lock, two racing replaces could both observe expectedRevision as current
        //and both commit revision+1, silently losing one update. Under the lock the
        //later caller re-reads the committed revision and fails with an explicit
        //conflict instead of overwriting. Blocking IO on a storage executor.
        synchronized (this.commitLock) {
            final Snapshot current = this.readSnapshot();
            if (current.revision() != expectedRevision) {
                throw new IOException("identity-links revision conflict: expected " + expectedRevision
                        + " but the committed revision is " + current.revision()
                        + "; refusing to overwrite the later change");
            }
            this.writeAtomically(new Snapshot(expectedRevision + 1, validated));
        }
    }

    // ---- committed state -------------------------------------------------

    private Snapshot readSnapshot() {
        final Path path = this.indexPath();
        if (!Files.exists(path)) {
            return new Snapshot(0, Map.of());
        }
        final String raw;
        try {
            raw = Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new CorruptIndexException(
                    "identity-links.json could not be read; linked-profile access stays blocked until it is repaired", e);
        }
        return parseIndex(raw);
    }

    private static Snapshot parseIndex(final String raw) {
        final JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (final RuntimeException e) {
            throw new CorruptIndexException("identity-links.json is not valid JSON; repair it before linking profiles", e);
        }
        if (!root.isJsonObject()) {
            throw new CorruptIndexException("identity-links.json is not a JSON object");
        }
        final JsonObject object = root.getAsJsonObject();
        if (!object.has("schemaVersion") || !object.get("schemaVersion").isJsonPrimitive()
                || !object.get("schemaVersion").getAsJsonPrimitive().isNumber()
                || object.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
            throw new CorruptIndexException("identity-links.json has an unknown or missing schemaVersion; repair it before linking profiles");
        }
        final long revision = requireIntegral(object, "revision");
        if (revision < 0) {
            throw new CorruptIndexException("identity-links.json has a negative revision");
        }
        if (!object.has("links") || !object.get("links").isJsonArray()) {
            throw new CorruptIndexException("identity-links.json is missing its links array");
        }
        final Map<String, UUID> links = new LinkedHashMap<>();
        for (final JsonElement element : object.getAsJsonArray("links")) {
            if (!element.isJsonObject()) {
                throw new CorruptIndexException("identity-links.json contains a link entry that is not an object");
            }
            final JsonObject link = element.getAsJsonObject();
            final String xuid = requireString(link, "xuid");
            final UUID javaUuid = requireUuid(link, "javaUuid");
            final String canonicalXuid;
            try {
                canonicalXuid = ProfileKey.bedrockProfile(xuid).value();
            } catch (final IllegalArgumentException e) {
                throw new CorruptIndexException("identity-links.json contains a non-canonical XUID: " + xuid, e);
            }
            if (links.put(canonicalXuid, javaUuid) != null) {
                throw new CorruptIndexException("identity-links.json contains a duplicate XUID mapping: " + canonicalXuid);
            }
        }
        final Set<UUID> seenJavaUuids = new LinkedHashSet<>();
        for (final UUID javaUuid : links.values()) {
            if (!seenJavaUuids.add(javaUuid)) {
                throw new CorruptIndexException("identity-links.json links Java UUID " + javaUuid + " to more than one XUID");
            }
        }
        return new Snapshot(revision, links);
    }

    private static long requireIntegral(final JsonObject object, final String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isNumber()) {
            throw new CorruptIndexException("identity-links.json has an invalid " + field + " field");
        }
        try {
            return object.get(field).getAsBigDecimal().longValueExact();
        } catch (final ArithmeticException e) {
            throw new CorruptIndexException("identity-links.json has a non-integral " + field + " field");
        }
    }

    private static String requireString(final JsonObject object, final String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isString()) {
            throw new CorruptIndexException("identity-links.json has an invalid " + field + " field");
        }
        return object.get(field).getAsString();
    }

    private static UUID requireUuid(final JsonObject object, final String field) {
        final UUID uuid;
        try {
            uuid = UUID.fromString(requireString(object, field));
        } catch (final IllegalArgumentException e) {
            throw new CorruptIndexException("identity-links.json has a malformed " + field + " value");
        }
        return uuid;
    }

    private static Map<String, UUID> validateLinks(final Map<String, UUID> links) {
        final Map<String, UUID> validated = new LinkedHashMap<>();
        for (final Map.Entry<String, UUID> entry : links.entrySet()) {
            //ProfileKey is the sole owner of key validation: canonical XUID or reject
            final String canonicalXuid = ProfileKey.bedrockProfile(entry.getKey()).value();
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("links contains a null Java UUID for XUID " + canonicalXuid);
            }
            if (validated.put(canonicalXuid, entry.getValue()) != null) {
                throw new IllegalArgumentException("links contains a duplicate XUID: " + canonicalXuid);
            }
        }
        final Set<UUID> seenJavaUuids = new LinkedHashSet<>();
        for (final UUID javaUuid : validated.values()) {
            if (!seenJavaUuids.add(javaUuid)) {
                throw new IllegalArgumentException("links maps Java UUID " + javaUuid + " to more than one XUID");
            }
        }
        return validated;
    }

    // ---- atomic commit ---------------------------------------------------

    private void writeAtomically(final Snapshot snapshot) throws IOException {
        final Path target = this.indexPath();
        //The temp file is unique per commit so concurrent committers never interleave
        final Path temp = target.resolveSibling("identity-links-" + UUID.randomUUID() + ".json.tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, toJson(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            //The previous committed file stays in place; only this revision is lost
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException ignored) {
            }
            throw new IOException("Failed to commit identity-links.json revision " + snapshot.revision(), e);
        }
    }

    private static String toJson(final Snapshot snapshot) {
        final JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", SCHEMA_VERSION);
        object.addProperty("revision", snapshot.revision());
        final JsonArray links = new JsonArray();
        for (final Map.Entry<String, UUID> entry : snapshot.links().entrySet()) {
            final JsonObject link = new JsonObject();
            link.addProperty("xuid", entry.getKey());
            link.addProperty("javaUuid", entry.getValue().toString());
            links.add(link);
        }
        object.add("links", links);
        return object.toString();
    }

    private Path indexPath() {
        return new File(this.playersDir, "identity-links.json").toPath();
    }

}
