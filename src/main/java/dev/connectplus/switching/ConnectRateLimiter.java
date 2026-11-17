package dev.connectplus.switching;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Sliding-window limit by player UUID, retained across reconnects for the full window. */
public final class ConnectRateLimiter {
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_PLAYERS = 65536, MAX_ATTEMPTS = 1_000_000;
    private final int maxPerMinute;
    private final Map<UUID, ArrayDeque<Long>> attempts = new HashMap<>();
    private int stamps;
    private long nextSweep;

    public ConnectRateLimiter(int maxPerMinute) { this.maxPerMinute = maxPerMinute; }
    public boolean tryAcquire(UUID playerId) { return tryAcquireAt(playerId, System.nanoTime(), List.of()); }

    synchronized boolean tryAcquireAt(UUID playerId, long now, List<Long> preexisting) {
        if (maxPerMinute <= 0) return true;
        if (nextSweep == 0 || now - nextSweep >= 0) {
            pruneAt(now); nextSweep = now + TimeUnit.SECONDS.toNanos(1);
        }
        ArrayDeque<Long> window = attempts.get(playerId);
        if (window == null) {
            if (attempts.size() >= MAX_PLAYERS || stamps >= MAX_ATTEMPTS) return false;
            window = new ArrayDeque<>(); attempts.put(playerId, window);
        }
        for (long stamp : preexisting) {
            if (now - stamp <= WINDOW_NANOS && window.size() < maxPerMinute && stamps < MAX_ATTEMPTS) {
                window.addLast(stamp); stamps++;
            }
        }
        pruneWindow(window, now);
        if (window.size() >= maxPerMinute || stamps >= MAX_ATTEMPTS) return false;
        window.addLast(now); stamps++; return true;
    }

    public synchronized int currentCount(UUID playerId) {
        ArrayDeque<Long> window = attempts.get(playerId);
        if (window == null) return 0;
        pruneWindow(window, System.nanoTime());
        if (window.isEmpty()) attempts.remove(playerId);
        return window.size();
    }
    public synchronized void pruneExpired() { pruneAt(System.nanoTime()); }
    synchronized void pruneAt(long now) {
        attempts.values().removeIf(window -> { pruneWindow(window, now); return window.isEmpty(); });
    }
    private void pruneWindow(ArrayDeque<Long> window, long now) {
        int before = window.size();
        window.removeIf(stamp -> now - stamp > WINDOW_NANOS);
        stamps -= before - window.size();
    }
    public synchronized void reset() { attempts.clear(); stamps = 0; nextSweep = 0; }
}
