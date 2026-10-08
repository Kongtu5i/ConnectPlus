package dev.connectplus.lobby;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot pending transfer confirmations: the switch engine posts the target of
 * a backend transfer packet (transferPolicy=confirm) before moving the player
 * back into the lobby, and the lobby's session load consumes it and opens the
 * confirmation GUI instead of the main screen. Keyed by player UUID, never by
 * address (F8.2); same lifecycle as {@link LobbyNotices}.
 */
public final class LobbyTransfers {

    /**
     * The transfer target as announced by the backend.
     */
    public record Pending(String host, int port) {
    }

    private static final ConcurrentHashMap<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private LobbyTransfers() {
    }

    /**
     * Posts the pending transfer for the player; a second post replaces the
     * pending one.
     */
    public static void post(final UUID player, final String host, final int port) {
        PENDING.put(player, new Pending(host, port));
    }

    /**
     * Takes and removes the pending transfer of the player, or null.
     */
    @Nullable
    public static Pending consume(final UUID player) {
        return PENDING.remove(player);
    }

    /**
     * Current number of pending transfers; for diagnostics/tests.
     */
    public static int size() {
        return PENDING.size();
    }

    /**
     * Clears every pending transfer; for test isolation (a leaked entry from a
     * prior test would otherwise open an unrelated confirmation screen).
     */
    public static void consumeAll() {
        PENDING.clear();
    }
}
