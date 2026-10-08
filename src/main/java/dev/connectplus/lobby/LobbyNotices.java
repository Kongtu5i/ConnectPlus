package dev.connectplus.lobby;

import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Msg;
import io.netty.channel.Channel;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot notices for players about to (re-)enter the lobby: the switch engine
 * posts the failure reason of a hot switch before connecting the player back, and
 * the lobby's session load consumes it and shows it in the player's language.
 * Keyed by player UUID, never by address.
 *
 * <p>M6: the engine has no access to the player's language (it runs proxy-side),
 * so it posts {@link Msg} templates plus format arguments and the lobby resolves
 * them against the receiving session's language.</p>
 */
public final class LobbyNotices {

    /**
     * One notice: the message template plus the format arguments. A null
     * template carries a raw/pre-formatted line, which is sent as-is.
     */
    public record Notice(@Nullable Msg template, @Nullable String raw, Object[] args) {

        public static Notice of(final Msg template, final Object... args) {
            return new Notice(template, null, args);
        }

        public static Notice raw(final String line) {
            return new Notice(null, line, new Object[0]);
        }

        /**
         * The text for the player's language.
         */
        public String text(final String lang) {
            return this.template == null ? this.raw : Languages.text(lang, this.template);
        }
    }

    /**
     * A notice argument whose text depends on the player's language (resolved
     * by the lobby when the notice is formatted).
     */
    public interface LocalizedArg {
        String text(String lang);
    }

    private record Pending(List<Notice> notices, @Nullable Channel connection) {
    }

    private static final ConcurrentHashMap<UUID, Pending> NOTICES = new ConcurrentHashMap<>();

    private LobbyNotices() {
    }

    /**
     * Posts the notice for the player; a second post for the same player
     * replaces the pending one.
     */
    public static void post(final UUID player, final List<Notice> notices) {
        post(player, notices, null);
    }

    /**
     * A recovery notice belongs to its original client connection. Closing that
     * connection discards only this post, never a newer login's pending notice.
     * The returned action also discards only this post if recovery cannot start.
     */
    public static Runnable post(final UUID player, final List<Notice> notices, @Nullable final Channel connection) {
        if (notices == null || notices.isEmpty()) {
            return () -> {};
        }
        final Pending pending = new Pending(List.copyOf(notices), connection);
        NOTICES.put(player, pending);
        final Runnable discard = () -> NOTICES.computeIfPresent(player, (key, current) -> current == pending ? null : current);
        if (connection != null) {
            connection.closeFuture().addListener(future -> discard.run());
        }
        return discard;
    }

    /**
     * Posts pre-formatted lines (legacy § text) as raw notices; convenience for
     * tests and callers without a template.
     */
    public static void postRaw(final UUID player, final List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        post(player, lines.stream().map(Notice::raw).toList());
    }

    /**
     * Takes and removes the pending notice of the player, or null.
     */
    @Nullable
    public static List<Notice> consume(final UUID player) {
        final Pending pending = NOTICES.remove(player);
        // A new login must not race a close listener on the previous event loop.
        if (pending == null || pending.connection() != null && !pending.connection().isActive()) return null;
        return pending.notices();
    }

    /**
     * Current number of pending notices; for diagnostics.
     */
    public static int size() {
        return NOTICES.size();
    }

    /**
     * Clears every pending notice; for test isolation (a leaked notice from a prior
     * test would otherwise be consumed by an unrelated session).
     */
    public static void consumeAll() {
        NOTICES.clear();
    }

}
