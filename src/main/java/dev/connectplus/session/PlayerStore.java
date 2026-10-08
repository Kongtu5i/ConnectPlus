package dev.connectplus.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.connectplus.CoreMain;
import dev.connectplus.identity.ProfileKey;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Loads and stores per-player data files by {@link ProfileKey}:
 * {@code players/java/<JavaUUID>.json} and {@code players/bedrock/<XUID>.json}.
 * The persistence methods are explicit success/failure interfaces (plan §4):
 * blocking IO for a storage executor, atomic unique-temp-file + move writes,
 * and IOException on every failure — a save failure is always observable by
 * the caller.
 *
 * <p>A missing file loads as default (empty) data. A corrupt file throws a
 * recognizable {@link CorruptDataException}: the protected file stays on disk
 * untouched and must never be replaced by empty data later on.</p>
 */
public class PlayerStore {

    private final File playersDir;

    public PlayerStore(final File playersDir) {
        this.playersDir = playersDir;
    }

    /**
     * The players directory this store is rooted at; the identity-link index
     * ({@code identity-links.json}) lives beside the profile namespaces, so the
     * link store is rooted at the same directory (plan layout, binding).
     */
    public File playersDir() {
        return this.playersDir;
    }

    /**
     * Thrown when a profile file exists but cannot be trusted (unparseable JSON
     * or a wrong data shape). Callers must treat this as a failure state and
     * never fall back to empty data: saving empty data over the original file
     * would destroy the profile.
     */
    public static final class CorruptDataException extends IOException {
        private static final long serialVersionUID = 1L;

        public CorruptDataException(final String message) {
            super(message);
        }

        public CorruptDataException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Loads the profile of {@code key}; a missing file yields default (empty)
     * data, a corrupt file fails recognizably. Blocking IO: call on a storage
     * executor.
     */
    public PlayerData load(final ProfileKey key) throws IOException {
        final Path file = this.file(key);
        if (!Files.exists(file)) {
            return new PlayerData(key);
        }
        return this.parse(key, Files.readString(file, StandardCharsets.UTF_8), file);
    }

    /**
     * Writes the profile of {@code key} atomically (unique temp file + move)
     * and throws on failure; readers only ever see a complete file. Blocking
     * IO: call on a storage executor.
     */
    public void save(final ProfileKey key, final PlayerData data) throws IOException {
        if (!key.equals(data.key)) {
            throw new IllegalArgumentException("PlayerData belongs to " + data.key + ", not to " + key);
        }
        final Path target = this.file(key);
        //The temp file is unique per save so concurrent savers of one player can
        //never interleave on the same temp path
        final Path temp = target.resolveSibling(key.value() + "-" + UUID.randomUUID() + ".json.tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, this.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            //The previous complete file stays in place; only this update is lost
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException ignored) {
            }
            throw new IOException("Failed to save player data for " + key, e);
        }
    }

    /**
     * Whether a profile file exists for {@code key}. Blocking IO: call on a
     * storage executor. Recovery paths use this to never overwrite a profile
     * that was created in the meantime.
     */
    public boolean exists(final ProfileKey key) {
        return Files.exists(this.file(key));
    }

    /**
     * Removes the whole profile file of {@code key} (bookmarks, account blob,
     * settings). A missing file is already the desired state. Blocking IO: call
     * on a storage executor.
     */
    public void delete(final ProfileKey key) throws IOException {
        Files.deleteIfExists(this.file(key));
    }

    // ---- Legacy convenience bridges (rewired in tasks 3/5/6) --------------

    /**
     * Legacy wrapper keeping today's swallow-and-log behavior for the existing
     * GUI save flows; binding-commit paths must use
     * {@link #save(ProfileKey, PlayerData)} and observe failures explicitly.
     */
    public void save(final PlayerData data) {
        try {
            this.save(data.key, data);
        } catch (final IOException e) {
            CoreMain.logger().error("Failed to save player data for {}", data.key, e);
        }
    }

    /**
     * Legacy wrapper keeping today's swallow-and-log delete behavior for the
     * existing GUI flows; use {@link #delete(ProfileKey)} where failures must
     * be observable.
     */
    public void delete(final UUID javaUuid) {
        try {
            this.delete(ProfileKey.javaProfile(javaUuid));
        } catch (final IOException e) {
            CoreMain.logger().error("Failed to delete player data for {}", javaUuid, e);
        }
    }

    // ---- layout and parsing ----------------------------------------------

    /**
     * Files stay inside their namespace directory; ProfileKey owns path-key
     * safety, so the value is guaranteed free of path separators.
     */
    private Path file(final ProfileKey key) {
        final String namespace = key.kind() == ProfileKey.Kind.JAVA ? "java" : "bedrock";
        return new File(new File(this.playersDir, namespace), key.fileName()).toPath();
    }

    private PlayerData parse(final ProfileKey key, final String raw, final Path file) throws CorruptDataException {
        final JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (final RuntimeException e) {
            throw new CorruptDataException("Player data file " + file + " is not valid JSON; repair or remove it manually", e);
        }
        if (!root.isJsonObject()) {
            throw new CorruptDataException("Player data file " + file + " is not a JSON object");
        }
        final JsonObject object = root.getAsJsonObject();
        final PlayerData data = new PlayerData(key);
        if (object.has("bookmarks")) {
            final JsonElement bookmarksElement = object.get("bookmarks");
            if (!bookmarksElement.isJsonArray()) {
                throw new CorruptDataException("Player data file " + file + " has a non-array bookmarks field");
            }
            for (final JsonElement element : bookmarksElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    throw new CorruptDataException("Player data file " + file + " has a bookmark entry that is not an object");
                }
                final JsonObject bookmark = element.getAsJsonObject();
                data.bookmarks.add(new Bookmark(
                        requireString(bookmark, "name", file),
                        requireString(bookmark, "address", file),
                        optionalString(bookmark, "versionName", file),
                        requireLong(bookmark, "createdAt", file),
                        requireLong(bookmark, "lastConnectedAt", file)
                ));
            }
        }
        if (object.has("account")) {
            if (!object.get("account").isJsonPrimitive() || !object.get("account").getAsJsonPrimitive().isString()) {
                throw new CorruptDataException("Player data file " + file + " has a non-string account field");
            }
            data.accountBlob = object.get("account").getAsString();
        }
        if (object.has("saveLoginInfo")) {
            data.saveLoginInfo = requireBoolean(object, "saveLoginInfo", file);
        }
        if (object.has("offlineMode")) {
            data.offlineMode = requireBoolean(object, "offlineMode", file);
        }
        return data;
    }

    private static String requireString(final JsonObject object, final String field, final Path file) throws CorruptDataException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isString()) {
            throw new CorruptDataException("Player data file " + file + " has an invalid " + field + " field");
        }
        return object.get(field).getAsString();
    }

    @Nullable
    private static String optionalString(final JsonObject object, final String field, final Path file) throws CorruptDataException {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        final JsonElement element = object.get(field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new CorruptDataException("Player data file " + file + " has an invalid " + field + " field");
        }
        return element.getAsString();
    }

    private static long requireLong(final JsonObject object, final String field, final Path file) throws CorruptDataException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isNumber()) {
            throw new CorruptDataException("Player data file " + file + " has an invalid " + field + " field");
        }
        return object.get(field).getAsLong();
    }

    private static boolean requireBoolean(final JsonObject object, final String field, final Path file) throws CorruptDataException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isBoolean()) {
            throw new CorruptDataException("Player data file " + file + " has an invalid " + field + " field");
        }
        return object.get(field).getAsBoolean();
    }

    private String toJson(final PlayerData data) {
        final JsonObject object = new JsonObject();
        final JsonArray bookmarks = new JsonArray();
        for (final Bookmark bookmark : List.copyOf(data.bookmarks)) {
            final JsonObject bm = new JsonObject();
            bm.addProperty("name", bookmark.name);
            bm.addProperty("address", bookmark.address);
            if (bookmark.versionName != null) {
                bm.addProperty("versionName", bookmark.versionName);
            }
            bm.addProperty("createdAt", bookmark.createdAt);
            bm.addProperty("lastConnectedAt", bookmark.lastConnectedAt);
            bookmarks.add(bm);
        }
        object.add("bookmarks", bookmarks);
        if (data.accountBlob != null) {
            object.addProperty("account", data.accountBlob);
        }
        object.addProperty("saveLoginInfo", data.saveLoginInfo);
        object.addProperty("offlineMode", data.offlineMode);
        return object.toString();
    }

}
