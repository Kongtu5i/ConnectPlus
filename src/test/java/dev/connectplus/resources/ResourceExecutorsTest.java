package dev.connectplus.resources;

import org.junit.jupiter.api.Test;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResourceExecutorsTest {
    @Test void lateTimeoutCannotRetireANewerHealthyStorageDomain() {
        var storage = new RecoveringStorageExecutor("test-io", 2);
        try {
            var old = storage.currentDomain();
            storage.rotate(old);
            var healthy = storage.currentDomain();
            storage.rotate(old);
            assertFalse(healthy.isShutdown());
        } finally { storage.shutdownNow(); }
    }
    @Test void slowStorageHasABoundedQueueAndNeverRunsOnCaller() throws Exception {
        ExecutorService executor = ResourceExecutors.single("test-storage", 2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        try {
            executor.execute(() -> { entered.countDown(); await(release); });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            executor.execute(completed::incrementAndGet); executor.execute(completed::incrementAndGet);
            assertThrows(RejectedExecutionException.class, () -> executor.execute(completed::incrementAndGet));
            assertEquals(0, completed.get());
        } finally { release.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); }
        assertEquals(2, completed.get());
    }

    @Test void canceledAuthenticationKeepsItsSlotUntilWorkerActuallyExits() throws Exception {
        var tasks = new AccountTasks(1);
        var first = new EmbeddedChannel(); var next = new EmbeddedChannel();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            assertTrue(tasks.start(first, "test-auth", () -> { entered.countDown(); await(release); }));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            first.close();
            assertFalse(tasks.start(next, "test-auth", () -> {}), "An uninterruptible worker still consumes capacity");
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (tasks.activeCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(0, tasks.activeCount());
            assertTrue(tasks.start(next, "test-auth", () -> {}));
        } finally { release.countDown(); tasks.shutdown(); first.finishAndReleaseAll(); next.finishAndReleaseAll(); }
    }

    @Test void repeatedStorageTimeoutsCannotCreateUnlimitedStuckThreads() throws Exception {
        var storage = new RecoveringStorageExecutor("test-io", 2);
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(2);
        try {
            storage.execute(() -> { entered.countDown(); await(release); });
            storage.rotate();
            storage.execute(() -> { entered.countDown(); await(release); });
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            storage.rotate();
            for (int i=0;i<10;i++) {
                storage.rotate();
                assertThrows(RejectedExecutionException.class, () -> storage.execute(() -> {}));
            }
            assertTrue(storage.workerDomainCount() <= 2);
        } finally { release.countDown(); storage.shutdownNow(); assertTrue(storage.awaitTermination(3,TimeUnit.SECONDS)); }
    }
    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) { try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
