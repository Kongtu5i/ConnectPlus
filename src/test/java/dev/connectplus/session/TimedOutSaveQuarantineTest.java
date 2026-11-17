package dev.connectplus.session;

import dev.connectplus.identity.ProfileKey;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TimedOutSaveQuarantineTest {
    @TempDir File directory;
    @Test void aTimedOutSaveKeepsItsProfileQuarantinedUntilTheWorkerActuallyExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        PlayerStore store = new PlayerStore(directory) {
            @Override public void save(ProfileKey key, PlayerData data) {
                entered.countDown();
                while (true) { try { release.await(); break; } catch (InterruptedException ignored) { } }
                exited.countDown();
            }
        };
        var coordinator = new AccountSessionCoordinator(store, () -> null, (session, message) -> {
            session.c2pChannel.close(); return CompletableFuture.completedFuture(null);
        });
        var old = session(); var second = session(); var third = session();
        var profile = ProfileKey.javaProfile(UUID.randomUUID());
        try {
            old.playerData = new PlayerData(profile);
            old.lease = coordinator.claim(old, profile, Set.of()).toCompletableFuture().get(3,TimeUnit.SECONDS);
            var timedOut = coordinator.claim(second, profile, Set.of()).toCompletableFuture();
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> timedOut.get(8,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> coordinator.claim(third,profile,Set.of())
                    .toCompletableFuture().get(3,TimeUnit.SECONDS));
            release.countDown(); assertTrue(exited.await(3,TimeUnit.SECONDS));
            coordinator.awaitIdle().get(3,TimeUnit.SECONDS);
            var granted = coordinator.claim(third,profile,Set.of()).toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertTrue(coordinator.isCurrent(granted));
        } finally {
            release.countDown(); coordinator.shutdown();
            ((EmbeddedChannel)old.c2pChannel).finishAndReleaseAll();
            ((EmbeddedChannel)second.c2pChannel).finishAndReleaseAll();
            ((EmbeddedChannel)third.c2pChannel).finishAndReleaseAll();
        }
    }
    private static PlayerSession session() {
        var s = new PlayerSession(UUID.randomUUID(),"SaveAudit");
        s.connectionId = UUID.randomUUID(); s.c2pChannel = new EmbeddedChannel(); return s;
    }
}
