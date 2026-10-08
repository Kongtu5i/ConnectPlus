package dev.connectplus.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccessServiceTest {

    @Test
    void concurrentSwitchAndPendingUpdatesNeverOverwriteEachOther() throws Exception {
        final AccessService service = this.service(false, false);
        service.initialize().toCompletableFuture().join();
        final var workers = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 1000; round++) {
                service.setEnabled(AccessPolicy.Kind.WHITELIST, false).toCompletableFuture().join();
                service.setEnabled(AccessPolicy.Kind.BLACKLIST, false).toCompletableFuture().join();
                service.setPendingInheritanceNow(Set.of());
                final var start = new java.util.concurrent.CyclicBarrier(3);
                final var white = workers.submit(() -> { start.await();
                    return service.setEnabled(AccessPolicy.Kind.WHITELIST, true).toCompletableFuture().join(); });
                final var black = workers.submit(() -> { start.await();
                    return service.setEnabled(AccessPolicy.Kind.BLACKLIST, true).toCompletableFuture().join(); });
                final var pending = workers.submit(() -> { start.await();
                    return service.setPendingInheritanceNow(Set.of(AccessKey.javaUuid(JAVA_UUID))); });
                white.get(3, java.util.concurrent.TimeUnit.SECONDS);
                black.get(3, java.util.concurrent.TimeUnit.SECONDS);
                pending.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(service.state().whitelistEnabled());
                assertTrue(service.state().blacklistEnabled());
                assertEquals(Set.of(AccessKey.javaUuid(JAVA_UUID)), service.state().pendingInheritance());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";

    @TempDir
    Path tempDir;

    private Path whitelistFile() {
        return this.tempDir.resolve("whitelist.json");
    }

    private Path blacklistFile() {
        return this.tempDir.resolve("blacklist.json");
    }

    private Path configFile() {
        return this.tempDir.resolve("config.yml");
    }

    /** A direct executor: service work runs inline, keeping tests deterministic. */
    private static java.util.concurrent.Executor directExecutor() {
        return Runnable::run;
    }

    private AccessService service(final boolean whitelist, final boolean blacklist) {
        return new AccessService(new AccessListStore(this.whitelistFile()), new AccessListStore(this.blacklistFile()),
                new AccessService.ConfiguredFlags(whitelist, blacklist), directExecutor());
    }

    // ---- runtime switches ---------------------------------------------------

    @Test
    void runtimeSwitchNeverChangesConfigBytesOrListFiles() throws IOException {
        Files.writeString(this.configFile(), "mode: proxy\nlanguage: zh\n", StandardCharsets.UTF_8);
        final byte[] configBefore = Files.readAllBytes(this.configFile());
        final AccessService service = this.service(false, false);
        service.initialize().toCompletableFuture().join();

        final AccessPolicy.State state = service.setEnabled(AccessPolicy.Kind.BLACKLIST, true)
                .toCompletableFuture().join();
        assertTrue(state.blacklistEnabled());
        assertFalse(state.whitelistEnabled());
        assertArrayEquals(configBefore, Files.readAllBytes(this.configFile()),
                "the runtime switch must never write the config");
        final byte[] whitelistBefore = Files.exists(this.whitelistFile())
                ? Files.readAllBytes(this.whitelistFile()) : null;
        service.setEnabled(AccessPolicy.Kind.WHITELIST, true).toCompletableFuture().join();
        if (whitelistBefore == null) {
            assertFalse(Files.exists(this.whitelistFile()), "a switch flip must not create list files");
        } else {
            assertArrayEquals(whitelistBefore, Files.readAllBytes(this.whitelistFile()));
        }
    }

    @Test
    void restartUsesConfiguredFlagsButKeepsEntries() throws IOException {
        // The list file exists with one entry before the first service starts.
        Files.writeString(this.whitelistFile(), "{\"schemaVersion\": 1, \"players\": ["
                + "{\"name\": \"A\", \"clientType\": \"java\", \"UUID\": \"" + JAVA_UUID + "\"}]}",
                StandardCharsets.UTF_8);
        final AccessService first = this.service(true, false);
        first.initialize().toCompletableFuture().join();
        // A runtime flip changes only the running state.
        assertFalse(first.setEnabled(AccessPolicy.Kind.WHITELIST, false).toCompletableFuture().join()
                .whitelistEnabled());

        // A fresh service reads the same files but takes its flags from the config snapshot.
        final AccessService second = this.service(true, false);
        final AccessPolicy.State state = second.initialize().toCompletableFuture().join();
        assertTrue(state.whitelistEnabled(), "restart restores the configured switch");
        assertTrue(state.whitelist().available());
        assertNotNull(state.whitelist().entries().get(AccessKey.javaUuid(JAVA_UUID)),
                "list entries survive a restart");
    }

    @Test
    void reloadFailureKeepsLastGoodState() throws IOException {
        final AccessService service = this.service(false, false);
        service.initialize().toCompletableFuture().join();
        service.mutateNow(AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null)), Set.of());
        final AccessPolicy.State good = service.state();
        Files.writeString(this.whitelistFile(), "{\"schemaVersion\": 1, \"players\": [ broken }",
                StandardCharsets.UTF_8);

        final CompletionStage<AccessPolicy.State> reload = service.reload(AccessPolicy.Kind.WHITELIST);
        assertTrue(reload.toCompletableFuture().isCompletedExceptionally(), "a failed reload reports the error");
        assertEquals(good, service.state(), "the last good list stays published");
    }

    @Test
    void enabledListWithMissingFileStartsRejectedAndUnavailable() {
        final AccessService service = this.service(true, false);
        final AccessPolicy.State state = service.initialize().toCompletableFuture().join();

        assertTrue(state.whitelistEnabled());
        assertFalse(state.whitelist().available());
        final AccessPolicy.Decision decision = AccessPolicy.evaluate(
                state, new AccessSubject(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null));
        assertEquals(AccessPolicy.Reason.LIST_UNAVAILABLE, decision.reason());
        assertFalse(Files.exists(this.whitelistFile()), "a missing enabled list file is never generated");
    }

    @Test
    void disabledListInitializationMayCreateTheEmptyFile() {
        final AccessService service = this.service(false, false);
        final AccessPolicy.State state = service.initialize().toCompletableFuture().join();

        assertFalse(state.whitelistEnabled());
        assertTrue(state.whitelist().available(), "the disabled list was initialized");
        assertTrue(Files.exists(this.whitelistFile()));
    }

    // ---- mutations ----------------------------------------------------------

    @Test
    void recoveryStyleMutationKeepsAnExistingNamelessEntryIntact() throws IOException {
        final AccessService service = this.service(false, false);
        service.initialize().toCompletableFuture().join();
        final AccessEntry original = new AccessEntry(AccessKey.ClientType.BEDROCK, null, JAVA_UUID, XUID);
        service.mutateNow(AccessPolicy.Kind.BLACKLIST, List.of(original), Set.of());
        service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, "HistoricalB", null, XUID)), Set.of());
        assertEquals(original, service.state().blacklist().entries().get(original.key()));
        assertEquals(original, new AccessListStore(this.blacklistFile()).load(false).entries().get(original.key()));
    }

    @Test
    void newStateRetainsOtherListAndSwitches() throws IOException {
        final AccessService service = this.service(false, true);
        service.initialize().toCompletableFuture().join();
        service.setPendingInheritanceNow(Set.of(AccessKey.bedrockXuid(XUID)));
        final AccessPolicy.State before = service.state();

        final AccessPolicy.State after = service.mutateNow(AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, "B", null, XUID)), Set.of());
        assertNotNull(after.whitelist().entries().get(AccessKey.bedrockXuid(XUID)));
        assertEquals(before.blacklist(), after.blacklist(), "the other list is untouched");
        assertEquals(before.blacklistEnabled(), after.blacklistEnabled());
        assertEquals(before.whitelistEnabled(), after.whitelistEnabled());
        assertEquals(before.pendingInheritance(), after.pendingInheritance());
        assertTrue(after.revision() > before.revision());
    }

    @Test
    void mutateNowRunsInsideTheSharedCommitLock() throws Exception {
        final AccessService service = this.service(false, false); // disabled: init may create the empty file
        service.initialize().toCompletableFuture().join();
        final AtomicInteger insideLock = new AtomicInteger();

        final AccessMutationCoordinator coordinator = new AccessMutationCoordinator();
        final AccessPolicy.State state = coordinator.withCommitLock((Callable<AccessPolicy.State>) () ->
                service.mutateNow(AccessPolicy.Kind.WHITELIST,
                        List.of(new AccessEntry(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null)), Set.of()));
        insideLock.incrementAndGet();
        assertNotNull(state.whitelist().entries().get(AccessKey.javaUuid(JAVA_UUID)));
        assertEquals(1, insideLock.get());
    }

    @Test
    void mutationOnUnavailableListFailsClosed() {
        final AccessService service = this.service(true, false);
        service.initialize().toCompletableFuture().join(); // missing file → unavailable
        assertThrows(IOException.class, () -> service.mutateNow(AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null)), Set.of()));
    }

    @Test
    void subscribersAreNotifiedAfterPublishAndCanUnsubscribe() {
        final AccessService service = this.service(false, false);
        service.initialize().toCompletableFuture().join();
        final AtomicInteger notifications = new AtomicInteger();
        final AutoCloseable subscription = service.subscribe(state -> notifications.incrementAndGet());

        service.setEnabled(AccessPolicy.Kind.WHITELIST, true).toCompletableFuture().join();
        assertEquals(1, notifications.get(), "one notification per published state change");
        assertDoesNotThrow(subscription::close);
        service.setEnabled(AccessPolicy.Kind.WHITELIST, false).toCompletableFuture().join();
        assertEquals(1, notifications.get(), "a closed subscription receives nothing");
    }

    @Test
    void configuredFlagsReflectTheStartupSnapshot() {
        final AccessService service = this.service(true, false);
        assertEquals(new AccessService.ConfiguredFlags(true, false), service.configuredFlags());
    }
}
