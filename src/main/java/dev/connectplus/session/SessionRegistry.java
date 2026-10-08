package dev.connectplus.session;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The players currently in the lobby, keyed by their connection id (plan §5:
 * 按 connectionId 管理 — never by the session uuid, which two connections can
 * share after an offline-name collision and which a switch does not change).
 * The connection id is the c2p channel's id and is stable across switches and
 * lobby returns, so one connection keeps exactly one slot. Sessions are
 * released when the lobby connection goes away (GUI disconnect, transfer,
 * drop) — but a release only takes effect when it names the current holder
 * and the current right-of-use generation; a stale release is a no-op.
 */
public class SessionRegistry {

    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    /**
     * Registers the session under its connection id, replacing any previous
     * session of the same connection (e.g. the re-registration on a lobby return).
     */
    public void register(final PlayerSession session) {
        this.sessions.put(session.connectionId, session);
    }

    /**
     * Releases the slot of {@code session}: the entry is removed only when the
     * registry still holds {@code session} under its connection id AND the
     * given right-of-use {@code generation} is still the session's current one
     * (a newer grant supersedes the caller's). A stale release — older
     * generation or another holder registered meanwhile — is a no-op. The
     * compare and the removal run atomically in one map operation.
     *
     * @return true when this call released the slot
     */
    public boolean release(final PlayerSession session, final long generation) {
        final boolean[] released = {false};
        this.sessions.compute(session.connectionId, (key, holder) -> {
            if (holder != session || holder.generation != generation) {
                return holder; // wrong holder or stale generation: the slot stays
            }
            released[0] = true;
            return null;
        });
        return released[0];
    }

    /**
     * The session registered under the given connection id, or null.
     */
    @Nullable
    public PlayerSession get(final UUID connectionId) {
        return this.sessions.get(connectionId);
    }

    /**
     * Current number of registered sessions.
     */
    public int size() {
        return this.sessions.size();
    }

    /**
     * A read-only snapshot of the registered sessions (console queries): an
     * immutable list copy, safe to iterate off the event loops.
     */
    public java.util.List<PlayerSession> snapshot() {
        return List.copyOf(this.sessions.values());
    }

}
