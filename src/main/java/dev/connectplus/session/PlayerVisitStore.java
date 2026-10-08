package dev.connectplus.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.ProfileKey;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The persistent visit and name index for the console queries (accounts/info).
 * One independent JSON file in the CP data folder, kept separate from the
 * bookmark profiles, the account tokens and the link-transaction files; the
 * link protocol is untouched.
 *
 * <p>Recording rules: a visit is recorded only when a connection actually
 * entered the lobby (the caller decides that; handshakes, failures and refused
 * connections never reach this store). The record keeps the original protocol
 * identity (the wire UUID), the most recent known name and the protocol UUID
 * itself. Java identities use their (premium or offline) UUID; verified
 * Bedrock identities use their canonical XUID. An unconfirmed visit is stored
 * under its wire UUID as a temporary identity; when the trusted identity of
 * the same connection arrives later, the temporary record folds into the
 * confirmed one, so one connection never counts twice.</p>
 *
 * <p>Names of linked Java targets can be recorded through
 * {@link #recordKnownJavaName} without marking that Java identity as an actual
 * lobby visitor. The unique-visitor count is computed against the CURRENT
 * committed link snapshot ({@code XUID -> JavaUUID}) supplied by the caller;
 * the raw visit records are never merged permanently, so binding and unbinding
 * need no history rewrite.</p>
 *
 * <p>Every mutation commits atomically (unique temp file + move) before the
 * in-memory snapshot changes, so a visible snapshot always reflects a committed
 * state and a failed commit publishes nothing. A corrupt or unknown-schema
 * index is a blocked state: queries and mutations throw
 * {@link CorruptIndexException} and the original file bytes are never touched.
 * The index contains no account tokens and no authentication proofs.</p>
 */
public class PlayerVisitStore {

    static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final Object lock = new Object();
    private volatile State state;

    /**
     * Thrown when the index on disk cannot be trusted (unreadable, invalid
     * JSON, unknown schema version, malformed identity keys). A repair-needed
     * state: the console statistics and offline name queries report themselves
     * unavailable and the file must never be overwritten.
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

    /** One lobby visit: an actual lobby entrant's identity and known name. */
    public record VisitEntry(String key, Kind kind, UUID wireUuid, String name,
                             String javaUuid, String xuid, boolean temporary,
                             long firstSeenMillis, long lastSeenMillis) {
    }

    /** The identity kind of a stored visit. */
    public enum Kind { JAVA, BEDROCK, TEMPORARY_WIRE }

    /** A name match across visit records and known Java names. */
    public record NameMatch(String name, UUID javaUuid, String xuid, UUID wireUuid, boolean fromVisit) {
    }

    private static final class State {
        final Map<String, VisitEntry> visits = new LinkedHashMap<>();
        final Map<String, String> knownJavaNames = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    /** The immutable query view of the committed index state. */
    public static final class Snapshot {
        private final List<VisitEntry> visits;
        private final Map<String, String> knownJavaNames;

        private Snapshot(final List<VisitEntry> visits, final Map<String, String> knownJavaNames) {
            this.visits = List.copyOf(visits);
            this.knownJavaNames = Map.copyOf(knownJavaNames);
        }

        /** Every recorded visit (confirmed and temporary), unmodifiable. */
        public List<VisitEntry> visits() {
            return this.visits;
        }

        /** Known Java profile names, without implying those accounts visited. */
        public Map<String, String> knownJavaNames() {
            return this.knownJavaNames;
        }

        /**
         * Case-insensitive exact-name matches over visit records and known Java
         * names; same-name identities are all returned, never arbitrarily chosen.
         */
        public List<NameMatch> matchByName(final String name) {
            if (name == null) {
                return List.of();
            }
            final String needle = name.toLowerCase(Locale.ROOT);
            final List<NameMatch> matches = new ArrayList<>();
            for (final VisitEntry visit : this.visits) {
                if (visit.name() != null && visit.name().toLowerCase(Locale.ROOT).equals(needle)) {
                    final UUID wireUuid = visit.wireUuid();
                    final UUID javaUuid = visit.javaUuid() == null ? null : UUID.fromString(visit.javaUuid());
                    matches.add(new NameMatch(visit.name(), javaUuid, visit.xuid(), wireUuid, true));
                }
            }
            for (final Map.Entry<String, String> known : this.knownJavaNames.entrySet()) {
                if (known.getValue().toLowerCase(Locale.ROOT).equals(needle)) {
                    matches.add(new NameMatch(known.getValue(), UUID.fromString(known.getKey()), null, null, false));
                }
            }
            return List.copyOf(matches);
        }

        /**
         * The unique-visitor count against the current committed links: each
         * confirmed Bedrock visitor linked to a Java UUID merges into that Java
         * person; temporary visitors count as their own (protocol) identity.
         * Java and Bedrock identities of the same person without a committed
         * link count separately.
         */
        public int uniqueVisitorCount(final Map<String, UUID> links) {
            final java.util.Set<String> persons = new java.util.HashSet<>();
            for (final VisitEntry visit : this.visits) {
                switch (visit.kind()) {
                    case JAVA -> persons.add("J:" + visit.javaUuid());
                    case BEDROCK -> {
                        final UUID linked = links.get(visit.xuid());
                        persons.add(linked != null ? "J:" + linked : "B:" + visit.xuid());
                    }
                    case TEMPORARY_WIRE -> persons.add("W:" + visit.wireUuid());
                }
            }
            return persons.size();
        }
    }

    /**
     * @param file the independent index file; a missing file starts from an
     *             empty history (old profiles in the same directory are never
     *             imported). The file is read lazily on first use so a corrupt
     *             index blocks queries instead of plugin startup.
     */
    public PlayerVisitStore(final File file) {
        Objects.requireNonNull(file, "file");
        this.file = file.toPath();
    }

    // ---- reads -----------------------------------------------------------

    /** The current committed snapshot. Blocking IO on first use only. */
    public Snapshot snapshot() {
        final State current = this.load();
        return new Snapshot(List.copyOf(current.visits.values()), current.knownJavaNames);
    }

    private State load() {
        State current = this.state;
        if (current != null) {
            return current;
        }
        synchronized (this.lock) {
            if (this.state == null) {
                this.state = this.readFromDisk();
            }
            return this.state;
        }
    }

    private State readFromDisk() {
        if (!Files.exists(this.file)) {
            return new State();
        }
        final String raw;
        try {
            raw = Files.readString(this.file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new CorruptIndexException("The visit index could not be read; statistics and offline name queries stay blocked until it is repaired", e);
        }
        return parseIndex(raw);
    }

    private static State parseIndex(final String raw) {
        final JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (final RuntimeException e) {
            throw new CorruptIndexException("The visit index is not valid JSON; repair it before querying statistics", e);
        }
        if (!root.isJsonObject()) {
            throw new CorruptIndexException("The visit index is not a JSON object");
        }
        final JsonObject object = root.getAsJsonObject();
        if (!object.has("schemaVersion") || !object.get("schemaVersion").isJsonPrimitive()
                || !object.get("schemaVersion").getAsJsonPrimitive().isNumber()
                || object.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
            throw new CorruptIndexException("The visit index has an unknown or missing schemaVersion");
        }
        final State state = new State();
        if (object.has("visits")) {
            if (!object.get("visits").isJsonArray()) {
                throw new CorruptIndexException("The visit index has an invalid visits array");
            }
            for (final JsonElement element : object.getAsJsonArray("visits")) {
                if (!element.isJsonObject()) {
                    throw new CorruptIndexException("The visit index contains a visit entry that is not an object");
                }
                final JsonObject entry = element.getAsJsonObject();
                final String key = requireString(entry, "key");
                final VisitEntry visit = parseVisitEntry(key, entry);
                if (state.visits.put(key, visit) != null) {
                    throw new CorruptIndexException("The visit index contains a duplicate identity key: " + key);
                }
            }
        }
        if (object.has("known")) {
            if (!object.get("known").isJsonArray()) {
                throw new CorruptIndexException("The visit index has an invalid known array");
            }
            for (final JsonElement element : object.getAsJsonArray("known")) {
                if (!element.isJsonObject()) {
                    throw new CorruptIndexException("The visit index contains a known-name entry that is not an object");
                }
                final JsonObject entry = element.getAsJsonObject();
                final UUID javaUuid = requireUuid(entry, "javaUuid");
                final String name = requireString(entry, "name");
                if (state.knownJavaNames.put(javaUuid.toString(), name) != null) {
                    throw new CorruptIndexException("The visit index contains a duplicate known Java UUID: " + javaUuid);
                }
            }
        }
        return state;
    }

    private static VisitEntry parseVisitEntry(final String key, final JsonObject entry) {
        final UUID wireUuid = requireUuid(entry, "wire");
        final long firstSeen = requireIntegral(entry, "first");
        final long lastSeen = requireIntegral(entry, "last");
        final String name = entry.has("name") && entry.get("name").isJsonPrimitive() && entry.get("name").getAsJsonPrimitive().isString()
                ? entry.get("name").getAsString() : null;
        if (firstSeen < 0 || lastSeen < firstSeen) {
            throw new CorruptIndexException("The visit index has invalid timestamps for " + key);
        }
        if (key.startsWith("java:")) {
            final UUID javaUuid;
            try {
                javaUuid = UUID.fromString(key.substring("java:".length()));
            } catch (final IllegalArgumentException e) {
                throw new CorruptIndexException("The visit index contains a malformed Java identity key: " + key, e);
            }
            return new VisitEntry(key, Kind.JAVA, wireUuid, name, javaUuid.toString(), null, false, firstSeen, lastSeen);
        }
        if (key.startsWith("bedrock:")) {
            final String xuid = key.substring("bedrock:".length());
            try {
                ProfileKey.bedrockProfile(xuid);
            } catch (final IllegalArgumentException e) {
                throw new CorruptIndexException("The visit index contains a non-canonical XUID key: " + key, e);
            }
            return new VisitEntry(key, Kind.BEDROCK, wireUuid, name, null, xuid, false, firstSeen, lastSeen);
        }
        if (key.startsWith("wire:")) {
            final UUID tempUuid;
            try {
                tempUuid = UUID.fromString(key.substring("wire:".length()));
            } catch (final IllegalArgumentException e) {
                throw new CorruptIndexException("The visit index contains a malformed temporary identity key: " + key, e);
            }
            if (!tempUuid.equals(wireUuid)) {
                throw new CorruptIndexException("The visit index temporary key does not match its wire UUID: " + key);
            }
            return new VisitEntry(key, Kind.TEMPORARY_WIRE, wireUuid, name, null, null, true, firstSeen, lastSeen);
        }
        throw new CorruptIndexException("The visit index contains an unknown identity key: " + key);
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Records that a connection entered the lobby. The first call with an
     * unverified (or absent) identity stores a temporary record under the wire
     * UUID; a later call with a verified identity for the same wire UUID folds
     * that temporary record into the confirmed identity (Java UUID / Bedrock
     * XUID) instead of adding a second visitor. Blocking IO: call on a storage
     * executor.
     *
     * @throws IOException when the disk commit fails; the visible state then
     *                     stays unchanged
     * @throws CorruptIndexException when the index is corrupt (it is never
     *                               overwritten)
     */
    public void recordVisit(final UUID wireUuid, final String name, final ClientIdentity identity) throws IOException {
        Objects.requireNonNull(wireUuid, "wireUuid");
        synchronized (this.lock) {
            final State current = this.load();
            final State next = copyOf(current);
            final boolean confirmed = identity != null && identity.kind() != ClientIdentity.Kind.UNVERIFIED;
            if (confirmed && identity.kind() == ClientIdentity.Kind.VERIFIED_JAVA) {
                upsertConfirmed(next, "java:" + identity.verifiedJavaUuid(), Kind.JAVA, wireUuid, name,
                        identity.verifiedJavaUuid().toString(), null);
            } else if (confirmed) {
                upsertConfirmed(next, "bedrock:" + identity.xuid(), Kind.BEDROCK, wireUuid, name, null, identity.xuid());
            } else {
                final String key = "wire:" + wireUuid;
                final VisitEntry existing = next.visits.get(key);
                final long now = System.currentTimeMillis();
                if (existing == null) {
                    next.visits.put(key, new VisitEntry(key, Kind.TEMPORARY_WIRE, wireUuid, name,
                            null, null, true, now, now));
                } else {
                    //A repeated temporary visit updates the known name only
                    next.visits.put(key, new VisitEntry(key, Kind.TEMPORARY_WIRE, wireUuid,
                            name != null ? name : existing.name(), null, null, true,
                            existing.firstSeenMillis(), now));
                }
            }
            this.commit(next);
        }
    }

    private static void upsertConfirmed(final State state, final String key, final Kind kind,
                                        final UUID wireUuid, final String name,
                                        final String javaUuid, final String xuid) {
        final long now = System.currentTimeMillis();
        //A temporary record of the same connection folds into the confirmed identity
        final VisitEntry temporary = state.visits.remove("wire:" + wireUuid);
        final VisitEntry existing = state.visits.get(key);
        if (existing == null) {
            final long firstSeen = temporary == null ? now : Math.min(temporary.firstSeenMillis(), now);
            state.visits.put(key, new VisitEntry(key, kind, wireUuid,
                    name != null ? name : (temporary == null ? null : temporary.name()),
                    javaUuid, xuid, false, firstSeen, now));
            return;
        }
        //The identity was seen before: keep the earliest first-seen and the newest name
        final long firstSeen = Math.min(existing.firstSeenMillis(), temporary == null ? now : temporary.firstSeenMillis());
        state.visits.put(key, new VisitEntry(key, kind, existing.wireUuid(),
                name != null ? name : existing.name(), javaUuid, xuid, false, firstSeen, now));
    }

    /**
     * Records the most recent known Java profile name of an account without
     * marking it as a lobby visitor (learning or logging into a linked account
     * is not a visit). Blocking IO: call on a storage executor.
     */
    public void recordKnownJavaName(final UUID javaUuid, final String name) throws IOException {
        Objects.requireNonNull(javaUuid, "javaUuid");
        Objects.requireNonNull(name, "name");
        synchronized (this.lock) {
            final State next = copyOf(this.load());
            next.knownJavaNames.put(javaUuid.toString(), name);
            this.commit(next);
        }
    }

    private static State copyOf(final State state) {
        final State copy = new State();
        copy.visits.putAll(state.visits);
        copy.knownJavaNames.putAll(state.knownJavaNames);
        return copy;
    }

    /**
     * Commits {@code next} to disk and only then publishes it: a visible
     * snapshot always reflects a committed file. A corrupt index is never
     * overwritten.
     */
    private void commit(final State next) throws IOException {
        this.writeAtomically(next);
        this.state = next;
    }

    private void writeAtomically(final State state) throws IOException {
        final Path temp = this.file.resolveSibling(this.file.getFileName() + "-" + UUID.randomUUID() + ".tmp");
        try {
            if (this.file.getParent() != null) {
                Files.createDirectories(this.file.getParent());
            }
            Files.writeString(temp, toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            //The previous committed file stays in place; only this update is lost
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException ignored) {
            }
            throw new IOException("Failed to commit the visit index " + this.file, e);
        }
    }

    private static String requireString(final JsonObject object, final String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isString()) {
            throw new CorruptIndexException("The visit index has an invalid " + field + " field");
        }
        return object.get(field).getAsString();
    }

    private static UUID requireUuid(final JsonObject object, final String field) {
        final UUID uuid;
        try {
            uuid = UUID.fromString(requireString(object, field));
        } catch (final IllegalArgumentException e) {
            throw new CorruptIndexException("The visit index has a malformed " + field + " value", e);
        }
        return uuid;
    }

    private static long requireIntegral(final JsonObject object, final String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isNumber()) {
            throw new CorruptIndexException("The visit index has an invalid " + field + " field");
        }
        try {
            return object.get(field).getAsBigDecimal().longValueExact();
        } catch (final ArithmeticException e) {
            throw new CorruptIndexException("The visit index has a non-integral " + field + " field");
        }
    }

    private static String toJson(final State state) {
        final JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", SCHEMA_VERSION);
        final JsonArray visits = new JsonArray();
        for (final VisitEntry visit : state.visits.values()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("key", visit.key());
            entry.addProperty("wire", visit.wireUuid().toString());
            if (visit.name() != null) {
                entry.addProperty("name", visit.name());
            }
            entry.addProperty("first", visit.firstSeenMillis());
            entry.addProperty("last", visit.lastSeenMillis());
            visits.add(entry);
        }
        object.add("visits", visits);
        final JsonArray known = new JsonArray();
        for (final Map.Entry<String, String> entry : state.knownJavaNames.entrySet()) {
            final JsonObject element = new JsonObject();
            element.addProperty("javaUuid", entry.getKey());
            element.addProperty("name", entry.getValue());
            known.add(element);
        }
        object.add("known", known);
        return object.toString();
    }

}
