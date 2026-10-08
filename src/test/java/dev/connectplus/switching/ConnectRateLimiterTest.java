package dev.connectplus.switching;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectRateLimiterTest {

    @Test
    void allowsUpToLimit() {
        final ConnectRateLimiter limiter = new ConnectRateLimiter(3);
        final UUID player = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player));
        assertTrue(limiter.tryAcquire(player));
        assertTrue(limiter.tryAcquire(player));
        assertFalse(limiter.tryAcquire(player), "The fourth attempt within the window must be rejected");
        assertEquals(3, limiter.currentCount(player));
    }

    @Test
    void rateLimiterKeysByUuidNotChannel() {
        //Review Focus 4 (F8.2): the limiter has no channel/ip input at all — two players
        //behind one NAT are independent because they key by UUID
        final ConnectRateLimiter limiter = new ConnectRateLimiter(1);
        final UUID playerA = UUID.randomUUID();
        final UUID playerB = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(playerA));
        assertTrue(limiter.tryAcquire(playerB), "Another player must not be affected by player A's attempts");
        assertFalse(limiter.tryAcquire(playerA));
    }

    @Test
    void windowExpires() {
        final ConnectRateLimiter limiter = new ConnectRateLimiter(1);
        final UUID player = UUID.randomUUID();
        //The only window entry is an acquire stamped 61s ago: it must not block now
        final long expired = System.nanoTime() - TimeUnit.SECONDS.toNanos(61);
        assertTrue(limiter.tryAcquireAt(player, System.nanoTime(), java.util.List.of(expired)),
                "An expired window entry must not count");
        assertEquals(1, limiter.currentCount(player), "Only the fresh attempt stays in the window");
    }

    @Test
    void zeroOrNegativeLimitDisables() {
        final ConnectRateLimiter limiter = new ConnectRateLimiter(0);
        final UUID player = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(player), "A non-positive limit disables the limiter");
        }
    }

}
