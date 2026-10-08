package dev.connectplus.session;

import dev.connectplus.identity.ProfileKey;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The persisted per-player data (bookmarks and the encrypted account token
 * blob). The in-memory owner is the {@link ProfileKey}: a JAVA key for Java
 * accounts, a BEDROCK key for verified bedrock identities — a bedrock profile
 * has no Java UUID. Stored by {@link PlayerStore} at
 * {@code players/java/<JavaUUID>.json} or {@code players/bedrock/<XUID>.json};
 * the account blob is an opaque string encrypted by {@code TokenStore}.
 */
public class PlayerData {

    /** The protected profile this data belongs to; the owner used for storage. */
    public final ProfileKey key;

    /**
     * Thread-safe because the netty event loop mutates it while a save (triggered
     * from the login thread) serializes it.
     */
    public final List<Bookmark> bookmarks = new CopyOnWriteArrayList<>();

    /**
     * The AES-GCM encrypted account JSON, or null when no account is stored.
     */
    @Nullable
    public String accountBlob;

    /**
     * Whether the account login survives reconnects. False keeps the login
     * valid for the current session only: no blob is persisted and the wipe on
     * disconnect (LobbyServerHandler) leaves no account data behind. Default
     * true — the pre-toggle behavior — and also the value assumed for legacy
     * files without the flag.
     */
    public boolean saveLoginInfo = true;

    /** Use an offline identity on backends while keeping the Microsoft login. */
    public boolean offlineMode;

    public PlayerData(final ProfileKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /**
     * Legacy convenience for the existing Java-uuid based flows (GUI resets,
     * tests); identity-aware code uses the ProfileKey constructor directly.
     */
    public PlayerData(final UUID javaUuid) {
        this(ProfileKey.javaProfile(javaUuid));
    }

    /**
     * The Java UUID of a JAVA-keyed profile; null for a BEDROCK profile.
     */
    @Nullable
    public UUID uuid() {
        return this.key.javaUuid();
    }

}
