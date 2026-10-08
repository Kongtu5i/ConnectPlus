package dev.connectplus.switching;

import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window rate limit for connect attempts, keyed by player UUID only —
 * never by address (design F8.2: two players behind one NAT must stay
 * independent). {@code maxPerMinute <= 0} disables the limiter entirely.
 */
public final class ConnectRateLimiter {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final int maxPerMinute;
    private final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Long>> attempts = new ConcurrentHashMap<>();

    public ConnectRateLimiter(final int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    /**
     * Records an attempt for the player if the window allows it.
     *
     * @return true when the attempt is allowed (and counted)
     */
    public boolean tryAcquire(final UUID playerId) {
        return this.tryAcquireAt(playerId, System.nanoTime(), List.of());
    }

    /**
     * Package-visible variant for tests: extra timestamps are folded into the
     * window before the decision (simulating past attempts).
     */
    boolean tryAcquireAt(final UUID playerId, final long nowNanos, final List<Long> preexistingNanos) {
        if (this.maxPerMinute <= 0) {
            return true;
        }
        final ConcurrentLinkedDeque<Long> window = this.attempts.computeIfAbsent(playerId, id -> new ConcurrentLinkedDeque<>());
        //Fold the preexisting (test) timestamps in first; prune expired ones afterwards
        for (final long stamp : preexistingNanos) {
            window.addLast(stamp);
        }
        window.removeIf(stamp -> nowNanos - stamp > WINDOW_NANOS);
        for (;;) {
            final int size = window.size();
            if (size >= this.maxPerMinute) {
                return false;
            }
            if (window.offerFirst(nowNanos) && window.size() == size + 1) {
                return true;
            }
            //A concurrent prune raced the offer: retry the size check
            window.pollFirst();
        }
    }

    /**
     * Current attempt count of the player inside the window; for diagnostics and tests.
     */
    public int currentCount(final UUID playerId) {
        final Deque<Long> window = this.attempts.get(playerId);
        if (window == null) {
            return 0;
        }
        final long now = System.nanoTime();
        window.removeIf(stamp -> now - stamp > WINDOW_NANOS);
        return window.size();
    }

    /**
     * Clears all counters; for tests.
     */
    public void reset() {
        this.attempts.clear();
    }

}
