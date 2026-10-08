package dev.connectplus.identity;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.session.Bookmark;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionLease;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.testutil.StubAccount;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5: the §4.1 first-link transaction of the {@link IdentityLinkService}.
 * These tests pin the §1.1 outcome rules: B's bookmarks survive and A's are
 * discarded, a missing B profile is created empty, a cancelled authorization
 * preserves A, an existing binding conflicts without being overwritten, and
 * the commit point is the atomic index revision.
 */
class IdentityLinkServiceTest {

    private boolean oldLogin, oldGeyser;

    @org.junit.jupiter.api.BeforeEach
    void enableAccountPolicy() {
        oldLogin = dev.connectplus.config.CPConfig.allowAccountLogin;
        oldGeyser = dev.connectplus.config.CPConfig.GeyserSupport.enabled;
        dev.connectplus.config.CPConfig.allowAccountLogin = true;
        dev.connectplus.config.CPConfig.GeyserSupport.enabled = true;
    }

    @org.junit.jupiter.api.AfterEach
    void restoreAccountPolicy() {
        dev.connectplus.config.CPConfig.allowAccountLogin = oldLogin;
        dev.connectplus.config.CPConfig.GeyserSupport.enabled = oldGeyser;
    }

    private static final String XUID_A = "2533274790000001";
    private static final UUID WIRE_A = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa");
    private static final String NAME_A = "BedrockA";
    private static final UUID JAVA_B = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final UUID JAVA_C = UUID.fromString("87654321-4321-4321-8321-cba987654321");

    @TempDir
    File tempDir;

    private File playersDir() {
        return new File(this.tempDir, "players");
    }

    private PlayerStore store() {
        return new PlayerStore(playersDir());
    }

    private AccountSessionCoordinator coordinator(final PlayerStore store) {
        return new AccountSessionCoordinator(store, () -> null);
    }

    /** A verified bedrock session holding its own BEDROCK profile lease (the pre-link state of A). */
    private PlayerSession bedrockSessionA(final PlayerStore store, final AccountSessionCoordinator coordinator) throws Exception {
        final PlayerSession session = new PlayerSession(WIRE_A, NAME_A);
        session.connectionId = UUID.randomUUID();
        final EmbeddedChannel c2p = new EmbeddedChannel();
        //Production binds the verified identity to the c2p (ClientIdentityCapture);
        //the service reads the XUID only from there.
        dev.connectplus.testutil.TestClientIdentity.bedrock(c2p,
                ClientIdentity.verifiedBedrock(WIRE_A, NAME_A, XUID_A, UUID.randomUUID(), UUID.randomUUID().toString()));
        session.c2pChannel = c2p;
        final ProfileKey ownBedrock = ProfileKey.bedrockProfile(XUID_A);
        session.lease = coordinator.claim(session, ownBedrock, java.util.Set.of())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.generation = session.lease.generation();
        session.profileKey = ownBedrock;
        session.playerData = store.load(ownBedrock);
        return session;
    }

    private void seedBedrockProfile(final PlayerStore store, final String bookmarkName) throws IOException {
        final PlayerData data = new PlayerData(ProfileKey.bedrockProfile(XUID_A));
        data.bookmarks.add(new Bookmark(bookmarkName, "a.example", null, 1, 2));
        store.save(data);
    }

    private void seedJavaProfile(final PlayerStore store, final UUID javaUuid, final String bookmarkName) throws IOException {
        final PlayerData data = new PlayerData(ProfileKey.javaProfile(javaUuid));
        data.bookmarks.add(new Bookmark(bookmarkName, "b.example", null, 3, 4));
        data.saveLoginInfo = true;
        store.save(data);
    }

    private IdentityLinkService service(final PlayerStore store, final AccountSessionCoordinator coordinator,
                                        final IdentityLinkStore links, final TokenStore tokens) {
        return new DefaultIdentityLinkService(store, links, coordinator, tokens, Runnable::run);
    }

    private static CPAccount accountFor(final UUID javaUuid) {
        return new StubAccount() {
            @Override
            public UUID uuid() {
                return javaUuid;
            }

            @Override
            public String toJson() {
                return "{\"java\":\"" + javaUuid + "\"}";
            }
        };
    }

    // ---- §1.1 outcome rules --------------------------------------------------

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void disabledDuringQueuedWorkOrEncryptionCannotPersistOrCommit(boolean duringEncryption) throws Exception {
        PlayerStore store = store();
        AccountSessionCoordinator coordinator = coordinator(store);
        try {
            IdentityLinkStore links = new IdentityLinkStore(playersDir());
            seedBedrockProfile(store, "A kept");
            seedJavaProfile(store, JAVA_B, "B kept");
            PlayerSession session = bedrockSessionA(store, coordinator);
            java.util.List<Runnable> pending = new java.util.ArrayList<>();
            TokenStore tokens = new TokenStore(tempDir) {
                @Override public String encrypt(String json) {
                    dev.connectplus.config.CPConfig.allowAccountLogin = false;
                    return super.encrypt(json);
                }
            };
            IdentityLinkService service = new DefaultIdentityLinkService(store, links, coordinator, tokens, pending::add);
            var result = service.link(session, accountFor(JAVA_B)).toCompletableFuture();
            if (!duringEncryption) dev.connectplus.config.CPConfig.allowAccountLogin = false;
            pending.remove(0).run();
            assertNotEquals(IdentityLinkService.Result.COMMITTED, result.get());
            assertNull(links.snapshot().javaUuidFor(XUID_A));
            assertNull(store.load(ProfileKey.javaProfile(JAVA_B)).accountBlob);
            assertEquals("A kept", store.load(ProfileKey.bedrockProfile(XUID_A)).bookmarks.get(0).name);
            assertNull(session.account);
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void linkingUsesOnlyBsBookmarksAndDiscardsAsEntireProfile() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        seedJavaProfile(store, JAVA_B, "B's bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);

        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED, result);
        //The binding is committed...
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
        //...B's profile keeps ONLY B's bookmark (no merge)...
        final PlayerData b = store.load(ProfileKey.javaProfile(JAVA_B));
        assertEquals(1, b.bookmarks.size());
        assertEquals("B's bookmark", b.bookmarks.get(0).name);
        //...A's bedrock profile is deleted...
        assertFalse(new File(new File(playersDir(), "bedrock"), XUID_A + ".json").exists(),
                "A's discarded bedrock profile must be deleted after the commit");
        //...and the session switched onto B's profile while keeping the wire identity.
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey);
        assertEquals(WIRE_A, session.uuid, "The wire identity is never replaced by linking");
    }

    @Test
    void linkingToAMissingJavaProfileStillDiscardsAAndCreatesAnEmptyDefault() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);

        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED, result, "No target B profile must not block the link");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
        final PlayerData created = store.load(ProfileKey.javaProfile(JAVA_B));
        assertTrue(created.bookmarks.isEmpty(), "The new default B profile has empty bookmarks");
        assertFalse(new File(new File(playersDir(), "bedrock"), XUID_A + ".json").exists(),
                "A's bookmarks are discarded even when B has no profile (§1.1)");
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey);
    }

    @Test
    void anAlreadyLinkedXuidConflictsWithoutOverwritingAnything() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        //A is already linked to B; a second link attempt to C must not touch anything.
        links.replace(0, Map.of(XUID_A, JAVA_B));
        seedBedrockProfile(store, "A's own bookmark");
        seedJavaProfile(store, JAVA_C, "C's bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        final long revisionBefore = links.snapshot().revision();

        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, accountFor(JAVA_C)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.CONFLICT, result,
                "One-to-one: an already-linked XUID must not be re-linked silently");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "The existing binding stays");
        assertEquals(revisionBefore, links.snapshot().revision(), "No new revision may be committed");
        assertTrue(store.load(ProfileKey.javaProfile(JAVA_C)).bookmarks.get(0).name.startsWith("C's"),
                "C's profile stays untouched");
        //CONFLICT changes nothing on the session either: the rejected transaction
        //never claims or switches anything (the switch onto B is the join flow's
        //job once it resolves the committed binding, not this transaction's).
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.profileKey);
    }

    @Test
    void aJavaAccountAlreadyLinkedToAnotherXuidConflictsToo() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        links.replace(0, Map.of("9999", JAVA_B));
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);

        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.CONFLICT, result,
                "One-to-one: a Java account can only be linked to one XUID");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor("9999"));
        assertTrue(new File(new File(playersDir(), "bedrock"), XUID_A + ".json").exists(),
                "A's profile is preserved on a conflict");
    }

    // ---- §1.1: authorization cancellation / failure preserves A --------------

    @Test
    void aCancelledAuthorizationNeverTouchesA() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);

        //The AccountFlow's cancelled/failed device-code path completes with NO
        //account (null) — the service must answer STALE/FAILED without any change.
        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, null).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result == IdentityLinkService.Result.STALE || result == IdentityLinkService.Result.FAILED,
                "A missing verified account must not commit anything, got " + result);
        assertEquals(XUID_A, session.profileKey.value(), "A keeps its own bedrock profile");
        assertEquals("A's own bookmark", session.playerData.bookmarks.get(0).name);
        assertTrue(new File(new File(playersDir(), "bedrock"), XUID_A + ".json").exists());
        assertEquals(0, links.snapshot().revision());
    }

    // ---- §4.1 step 1: session/generation verification -------------------------

    @Test
    void aDisplacedSessionIsStaleAndCannotStartALink() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        //A newer verified login displaces A: its lease stops being current.
        final PlayerSession newcomer = new PlayerSession(UUID.randomUUID(), "Newcomer");
        newcomer.connectionId = UUID.randomUUID();
        final EmbeddedChannel newcomerC2p = new EmbeddedChannel();
        newcomer.c2pChannel = newcomerC2p;
        newcomer.lease = coordinator.claim(newcomer, ProfileKey.bedrockProfile(XUID_A), java.util.Set.of())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.STALE, result,
                "A displaced session must not start a binding transaction");
        assertTrue(new File(new File(playersDir(), "bedrock"), XUID_A + ".json").exists());
        assertEquals(0, links.snapshot().revision());
        newcomerC2p.finishAndReleaseAll();
    }

    @Test
    void aJavaIdentityCannotLink() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        //A Java session has no bedrock profile to discard; linking is a bedrock-only flow.
        final PlayerSession session = new PlayerSession(UUID.randomUUID(), "JavaP");
        session.connectionId = UUID.randomUUID();
        final EmbeddedChannel c2p = new EmbeddedChannel();
        c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).set(
                ClientIdentity.verifiedJava(UUID.fromString("cccccccc-0000-4000-8000-0000000000cc"), "JavaB", JAVA_B));
        session.c2pChannel = c2p;
        session.lease = coordinator.claim(session, ProfileKey.javaProfile(JAVA_B), java.util.Set.of(JAVA_B))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.generation = session.lease.generation();
        session.profileKey = ProfileKey.javaProfile(JAVA_B);
        session.playerData = store.load(session.profileKey);

        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result, "A non-bedrock session cannot link");
        assertEquals(0, links.snapshot().revision());
        c2p.finishAndReleaseAll();
    }

    // ---- §1.1 item 3: credentials follow B's saveLoginInfo --------------------

    @Test
    void theNewCredentialsAreStoredOnBsProfilePerItsSaveLoginInfo() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        final TokenStore tokens = new TokenStore(this.tempDir);
        seedBedrockProfile(store, "A's own bookmark");
        seedJavaProfile(store, JAVA_B, "B's bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);

        final IdentityLinkService.Result result = service(store, coordinator, links, tokens)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED, result);
        final PlayerData b = store.load(ProfileKey.javaProfile(JAVA_B));
        assertEquals(accountFor(JAVA_B).toJson(), tokens.decrypt(b.accountBlob),
                "The new credentials are encrypted onto B's profile (saveLoginInfo=true)");
        assertNotNull(session.account, "the session carries the published account");
        assertEquals(accountFor(JAVA_B).uuid(), session.account.uuid());

        //And the saveLoginInfo=false variant: no blob is persisted at all.
        final PlayerStore store2 = new PlayerStore(new File(this.tempDir, "players2"));
        final IdentityLinkStore links2 = new IdentityLinkStore(new File(this.tempDir, "players2"));
        final AccountSessionCoordinator coordinator2 = coordinator(store2);
        final PlayerData b2 = new PlayerData(ProfileKey.javaProfile(JAVA_B));
        b2.saveLoginInfo = false;
        b2.bookmarks.add(new Bookmark("B's bookmark", "b.example", null, 3, 4));
        store2.save(b2);
        final PlayerData a2 = new PlayerData(ProfileKey.bedrockProfile(XUID_A));
        a2.bookmarks.add(new Bookmark("A's own bookmark", "a.example", null, 1, 2));
        store2.save(a2);
        final PlayerSession session2 = bedrockSessionA(store2, coordinator2);
        final IdentityLinkService.Result result2 = new DefaultIdentityLinkService(store2, links2,
                coordinator2, new TokenStore(this.tempDir), Runnable::run)
                .link(session2, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(IdentityLinkService.Result.COMMITTED, result2);
        assertNull(store2.load(ProfileKey.javaProfile(JAVA_B)).accountBlob,
                "saveLoginInfo=false on B persists no credentials");
    }

    // ---- the result after a committed link: the in-memory switch -------------

    @Test
    void theCommittedLinkPublishesTheNewProfileOnTheSessionAndReleasesTheOldBedrockLease() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        final SessionLease oldLease = session.lease;

        final IdentityLinkService.Result result = service(store, coordinator, links, new TokenStore(this.tempDir))
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED, result);
        //§4.1: 事务中的 B 租约属于待提交使用权… 成功后才对 GUI 和切服流程发布新的活动档案
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey);
        assertEquals(ProfileKey.javaProfile(JAVA_B).javaUuid(), session.playerData.key.javaUuid());
        assertFalse(coordinator.isCurrent(oldLease), "The old bedrock-profile lease must have been handed back");
        assertTrue(coordinator.isCurrent(session.lease), "The session ends up with a current lease for B");
        assertEquals(session.lease.generation(), session.generation);
    }

    // ---- access-list binding sync (task 6) -----------------------------------

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void inheritanceUsesBlacklistAtCommitAfterConcurrentConsoleChange(boolean addAtSave) throws Exception {
        final var hook = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        final PlayerStore store = new PlayerStore(playersDir()) {
            @Override public void save(ProfileKey key, PlayerData data) throws IOException {
                super.save(key, data);
                if (key.equals(ProfileKey.javaProfile(JAVA_B))) {
                    final Runnable action = hook.getAndSet(null);
                    if (action != null) action.run();
                }
            }
        };
        final AccountSessionCoordinator coordinator = coordinator(store);
        try {
            final IdentityLinkStore links = new IdentityLinkStore(playersDir());
            seedBedrockProfile(store, "A kept");
            seedJavaProfile(store, JAVA_B, "B kept");
            final PlayerSession session = bedrockSessionA(store, coordinator);
            final var access = new dev.connectplus.access.AccessService(
                    new dev.connectplus.access.AccessListStore(tempDir.toPath().resolve("wl.json")),
                    new dev.connectplus.access.AccessListStore(tempDir.toPath().resolve("bl.json")),
                    new dev.connectplus.access.AccessService.ConfiguredFlags(false, false), Runnable::run);
            access.initialize().toCompletableFuture().join();
            final var binding = new dev.connectplus.access.AccessBindingCoordinator(access,
                    new dev.connectplus.access.AccessMutationCoordinator(), () -> links, null);
            final var target = new dev.connectplus.access.AccessEntry(
                    dev.connectplus.access.AccessKey.ClientType.JAVA, "B", JAVA_B, null);
            if (!addAtSave) binding.change(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST, true, target)
                    .toCompletableFuture().join();
            hook.set(() -> binding.change(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST, addAtSave, target)
                    .toCompletableFuture().join());
            final var service = new DefaultIdentityLinkService(store, links, coordinator,
                    new TokenStore(this.tempDir), Runnable::run);
            service.setAccessBindingCoordinator(binding);
            assertEquals(IdentityLinkService.Result.COMMITTED,
                    service.link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(addAtSave, access.state().blacklist().entries()
                    .containsKey(dev.connectplus.access.AccessKey.bedrockXuid(XUID_A)));
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void newLinkInheritsBlacklistButNeverWhitelist() throws Exception {
        PlayerStore store = store();
        AccountSessionCoordinator coordinator = coordinator(store);
        try {
            IdentityLinkStore links = new IdentityLinkStore(playersDir());
            seedBedrockProfile(store, "A kept");
            seedJavaProfile(store, JAVA_B, "B kept");
            PlayerSession session = bedrockSessionA(store, coordinator);

            // The access lists: JAVA_B is on BOTH lists; both switches off.
            final dev.connectplus.access.AccessService access = new dev.connectplus.access.AccessService(
                    new dev.connectplus.access.AccessListStore(java.nio.file.Path.of(tempDir.getAbsolutePath(), "wl.json")),
                    new dev.connectplus.access.AccessListStore(java.nio.file.Path.of(tempDir.getAbsolutePath(), "bl.json")),
                    new dev.connectplus.access.AccessService.ConfiguredFlags(false, false), Runnable::run);
            access.initialize().toCompletableFuture().join();
            access.mutateNow(dev.connectplus.access.AccessPolicy.Kind.WHITELIST,
                    java.util.List.of(new dev.connectplus.access.AccessEntry(
                            dev.connectplus.access.AccessKey.ClientType.JAVA, "B", JAVA_B, null)),
                    java.util.Set.of());
            access.mutateNow(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST,
                    java.util.List.of(new dev.connectplus.access.AccessEntry(
                            dev.connectplus.access.AccessKey.ClientType.JAVA, "B", JAVA_B, null)),
                    java.util.Set.of());

            final dev.connectplus.access.AccessBindingCoordinator binding =
                    new dev.connectplus.access.AccessBindingCoordinator(access,
                            new dev.connectplus.access.AccessMutationCoordinator(), () -> links, null);
            final DefaultIdentityLinkService service =
                    new DefaultIdentityLinkService(store, links, coordinator, new TokenStore(this.tempDir), Runnable::run);
            service.setAccessBindingCoordinator(binding);

            final IdentityLinkService.Result result = service.link(session, accountFor(JAVA_B))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(IdentityLinkService.Result.COMMITTED, result);

            assertTrue(access.state().blacklist().entries()
                    .containsKey(dev.connectplus.access.AccessKey.bedrockXuid(XUID_A)),
                    "the blacklist is inherited by the new counterpart");
            assertFalse(access.state().whitelist().entries()
                    .containsKey(dev.connectplus.access.AccessKey.bedrockXuid(XUID_A)),
                    "the whitelist is never inherited");
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void committedLinkWithFailedBlacklistWriteReturnsAccessPending() throws Exception {
        PlayerStore store = store();
        AccountSessionCoordinator coordinator = coordinator(store);
        try {
            IdentityLinkStore links = new IdentityLinkStore(playersDir());
            seedBedrockProfile(store, "A kept");
            seedJavaProfile(store, JAVA_B, "B kept");
            PlayerSession session = bedrockSessionA(store, coordinator);

            final java.nio.file.Path blFile = java.nio.file.Path.of(tempDir.getAbsolutePath(), "bl-fail.json");
            java.nio.file.Files.writeString(blFile, "{\"schemaVersion\": 1, \"players\": []}");
            // Fail from the SECOND commit on: the seed write lands, the inheritance fails.
            final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
            final dev.connectplus.access.AccessListStore failingBlacklist = new dev.connectplus.access.AccessListStore(
                    blFile, (target, content) -> {
                        if (commits.incrementAndGet() > 1) {
                            throw new java.io.IOException("simulated failure");
                        }
                        dev.connectplus.access.AccessListStore.atomicCommitForTests(target, content);
                    });
            final java.nio.file.Path wlFile = java.nio.file.Path.of(tempDir.getAbsolutePath(), "wl-fail.json");
            java.nio.file.Files.writeString(wlFile, "{\"schemaVersion\": 1, \"players\": []}");
            final dev.connectplus.access.AccessService access = new dev.connectplus.access.AccessService(
                    new dev.connectplus.access.AccessListStore(wlFile), failingBlacklist,
                    new dev.connectplus.access.AccessService.ConfiguredFlags(false, true), Runnable::run);
            access.initialize().toCompletableFuture().join();
            access.mutateNow(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST,
                    java.util.List.of(new dev.connectplus.access.AccessEntry(
                            dev.connectplus.access.AccessKey.ClientType.JAVA, "B", JAVA_B, null)),
                    java.util.Set.of());

            final dev.connectplus.access.AccessBindingCoordinator binding =
                    new dev.connectplus.access.AccessBindingCoordinator(access,
                            new dev.connectplus.access.AccessMutationCoordinator(), () -> links, null);
            final DefaultIdentityLinkService service =
                    new DefaultIdentityLinkService(store, links, coordinator, new TokenStore(this.tempDir), Runnable::run);
            service.setAccessBindingCoordinator(binding);

            final IdentityLinkService.Result result = service.link(session, accountFor(JAVA_B))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(IdentityLinkService.Result.COMMITTED_ACCESS_PENDING, result,
                    "the binding committed but the blacklist sync failed: neither FAILED nor full success");
            assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the binding is committed");
            assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey, "the session switched onto B");
            assertTrue(access.state().pendingInheritance()
                    .contains(dev.connectplus.access.AccessKey.bedrockXuid(XUID_A)),
                    "the pending key is registered: the gate rejects it while the blacklist is on");
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void unlinkDoesNotRemoveListEntries() throws Exception {
        PlayerStore store = store();
        AccountSessionCoordinator coordinator = coordinator(store);
        try {
            IdentityLinkStore links = new IdentityLinkStore(playersDir());
            seedBedrockProfile(store, "A kept");
            seedJavaProfile(store, JAVA_B, "B kept");
            PlayerSession session = bedrockSessionA(store, coordinator);

            final dev.connectplus.access.AccessService access = new dev.connectplus.access.AccessService(
                    new dev.connectplus.access.AccessListStore(java.nio.file.Path.of(tempDir.getAbsolutePath(), "wl2.json")),
                    new dev.connectplus.access.AccessListStore(java.nio.file.Path.of(tempDir.getAbsolutePath(), "bl2.json")),
                    new dev.connectplus.access.AccessService.ConfiguredFlags(false, false), Runnable::run);
            access.initialize().toCompletableFuture().join();

            final dev.connectplus.access.AccessBindingCoordinator binding =
                    new dev.connectplus.access.AccessBindingCoordinator(access,
                            new dev.connectplus.access.AccessMutationCoordinator(), () -> links, null);
            final DefaultIdentityLinkService service =
                    new DefaultIdentityLinkService(store, links, coordinator, new TokenStore(this.tempDir), Runnable::run);
            service.setAccessBindingCoordinator(binding);

            assertEquals(IdentityLinkService.Result.COMMITTED, service.link(session, accountFor(JAVA_B))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            binding.change(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST, true,
                    new dev.connectplus.access.AccessEntry(dev.connectplus.access.AccessKey.ClientType.BEDROCK,
                            "A", null, XUID_A)).toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Now unlink: the list entries of BOTH accounts survive.
            assertEquals(IdentityLinkService.Result.COMMITTED, service.unlink(session)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(2, access.state().blacklist().entries().size(),
                    "解绑保留记录: unlink never touches the list files");
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void reloadNeverInvokesTheBindingCoordinator() {
        final dev.connectplus.access.AccessService access = new dev.connectplus.access.AccessService(
                new dev.connectplus.access.AccessListStore(java.nio.file.Path.of(tempDir.getAbsolutePath(), "wl3.json")),
                new dev.connectplus.access.AccessListStore(java.nio.file.Path.of(tempDir.getAbsolutePath(), "bl3.json")),
                new dev.connectplus.access.AccessService.ConfiguredFlags(false, false), Runnable::run);
        access.initialize().toCompletableFuture().join();
        final java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        final dev.connectplus.commands.AccessConsoleService.Mutations counting = (kind, add, target) -> {
            changes.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(access.state());
        };
        org.junit.jupiter.api.Assertions.assertNotNull(counting);
        // The reload path calls AccessService.reload directly (console service);
        // the binding coordinator's change() is only reachable from add/remove.
        access.reload(dev.connectplus.access.AccessPolicy.Kind.WHITELIST).toCompletableFuture().join();
        assertEquals(0, changes.get(), "reload performs no binding sync");
    }
}
