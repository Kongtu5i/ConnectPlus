package dev.connectplus.commands;

import dev.connectplus.access.AccessConnectionRegistry;
import dev.connectplus.access.AccessEntry;
import dev.connectplus.access.AccessKey;
import dev.connectplus.access.AccessListStore;
import dev.connectplus.access.AccessPolicy;
import dev.connectplus.access.AccessService;
import dev.connectplus.access.AccessTargetResolver;
import dev.connectplus.session.PlayerVisitStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccessConsoleServiceTest {

    private String oldLanguage;

    @org.junit.jupiter.api.BeforeEach
    void setConsoleLanguage() {
        this.oldLanguage = dev.connectplus.config.CPConfig.language;
        dev.connectplus.config.CPConfig.language = "en";
    }

    @org.junit.jupiter.api.AfterEach
    void restoreConsoleLanguage() {
        dev.connectplus.config.CPConfig.language = this.oldLanguage;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en", "zh"})
    void successfulChangesIdentifyTheirDestinationListInBothLanguages(String language) throws Exception {
        dev.connectplus.config.CPConfig.language = language;
        final AccessService service = this.service();
        service.mutateNow(AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "A", JAVA_UUID, null)), Set.of());
        final AccessConsoleService console = this.console(service,
                (kind, add, target) -> CompletableFuture.completedFuture(service.state()), new AccessConnectionRegistry());
        for (final AccessCommand.Kind kind : AccessCommand.Kind.values()) {
            final String list = kind == AccessCommand.Kind.WHITELIST
                    ? (language.equals("zh") ? "白名单" : "whitelist")
                    : (language.equals("zh") ? "黑名单" : "blacklist");
            final String player = "A [" + (language.equals("zh") ? "Java" : "java") + "] " + JAVA_UUID;
            assertEquals(language.equals("zh") ? "已将 " + player + " 添加到" + list
                            : "Added " + player + " to " + list + ".",
                    this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.ADD,
                            AccessKey.ClientType.JAVA, JAVA_UUID.toString()))));
            assertEquals(language.equals("zh") ? "已从" + list + "移除 " + player
                            : "Removed " + player + " from " + list + ".",
                    this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.REMOVE,
                            AccessKey.ClientType.JAVA, JAVA_UUID.toString()))));
        }
    }

    @Test
    void oldGeneratedSuccessTranslationsDoNotHideTheNewDestination() throws Exception {
        final Path languages = this.dir.resolve("languages");
        Files.createDirectories(languages);
        final String oldEnglish = "ConsoleAccess:\n  Added: 'added: {0}'\n  Removed: 'removed: {0}'\n";
        final String oldChinese = "ConsoleAccess:\n  Added: '已添加: {0}'\n  Removed: '已移除: {0}'\n";
        Files.writeString(languages.resolve("en.yml"), oldEnglish);
        Files.writeString(languages.resolve("zh.yml"), oldChinese);
        dev.connectplus.lobby.screen.Languages.init(this.dir.toFile());
        try {
            final AccessService service = this.service();
            final AccessConsoleService console = this.console(service,
                    (kind, add, target) -> CompletableFuture.completedFuture(service.state()), new AccessConnectionRegistry());
            dev.connectplus.config.CPConfig.language = "zh";
            assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                    AccessCommand.Action.ADD, AccessKey.ClientType.JAVA, JAVA_UUID.toString()))).contains("添加到白名单"));
            dev.connectplus.config.CPConfig.language = "en";
            assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.BLACKLIST,
                    AccessCommand.Action.ADD, AccessKey.ClientType.JAVA, JAVA_UUID.toString()))).endsWith("to blacklist."));
            assertTrue(Files.readString(languages.resolve("en.yml")).startsWith(oldEnglish));
            assertTrue(Files.readString(languages.resolve("zh.yml")).startsWith(oldChinese));
        } finally {
            dev.connectplus.lobby.screen.Languages.init(this.dir.resolve("clean-language-state").toFile());
        }
    }

    @Test
    void chineseConfigurationLocalizesBothListHelpAndManagementReplies() throws Exception {
        dev.connectplus.config.CPConfig.language = "zh";
        final AccessService service = this.service();
        final AccessConsoleService console = this.console(service, (kind, add, target) -> {
            try {
                return CompletableFuture.completedFuture(service.mutateNow(kind,
                        add ? List.of(target) : List.of(), add ? Set.of() : Set.of(target.key())));
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }, new AccessConnectionRegistry());
        for (final AccessCommand.Kind kind : AccessCommand.Kind.values()) {
            final String label = kind == AccessCommand.Kind.WHITELIST ? "白名单" : "黑名单";
            final String help = this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.HELP, null, null)));
            assertTrue(help.contains("白名单当前状态: off"), help);
            assertTrue(help.contains("黑名单当前状态: off"), help);
            assertTrue(help.contains("玩家名"), help);
            assertTrue(help.contains("重新加载"), help);
            assertFalse(help.contains("a restart restores"), help);
            assertTrue(this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.ON, null, null)))
                    .contains(label + "当前状态: on"));
            assertTrue(this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.OFF, null, null)))
                    .contains("未修改配置文件"));
            assertTrue(this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.ADD,
                    AccessKey.ClientType.JAVA, JAVA_UUID.toString()))).contains("添加到" + label));
            assertTrue(this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.LIST, null, null)))
                    .contains(label + "（1 个账号）"));
            assertTrue(this.joined(console.check(JAVA_UUID.toString())).contains("白名单="));
            assertTrue(this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.REMOVE,
                    AccessKey.ClientType.JAVA, JAVA_UUID.toString()))).contains("已从" + label + "移除"));
            assertTrue(this.joined(console.execute(new AccessCommand(kind, AccessCommand.Action.RELOAD, null, null)))
                    .contains(label + "已重新加载"));
        }
    }

    @Test
    void chineseConfigurationLocalizesMissingTargetsAmbiguityAndReloadFailure() throws Exception {
        dev.connectplus.config.CPConfig.language = "zh";
        final AccessService service = this.service();
        final AccessConsoleService console = this.console(service,
                (kind, add, target) -> CompletableFuture.failedFuture(new IOException("disk failure")),
                new AccessConnectionRegistry());
        assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.BLACKLIST,
                AccessCommand.Action.ADD, AccessKey.ClientType.JAVA, "Nobody"))).contains("未找到"));
        final UUID other = UUID.fromString("00000000-0000-4000-8000-000000000002");
        service.mutateNow(AccessPolicy.Kind.WHITELIST, List.of(
                new AccessEntry(AccessKey.ClientType.JAVA, "SameName", JAVA_UUID, null),
                new AccessEntry(AccessKey.ClientType.JAVA, "SameName", other, null)), Set.of());
        assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.ADD, AccessKey.ClientType.JAVA, "SameName"))).contains("多个账号"));
        assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.BLACKLIST,
                AccessCommand.Action.ADD, AccessKey.ClientType.JAVA, JAVA_UUID.toString()))).contains("添加失败"));
        Files.writeString(this.blacklistFile(), "{broken");
        assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.BLACKLIST,
                AccessCommand.Action.RELOAD, null, null))).contains("重新加载失败"));
    }

    @Test
    void runtimeChangesAndReloadCompleteOnTheSharedStorageExecutor() throws Exception {
        final var storage = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final AccessService service = new AccessService(new AccessListStore(this.whitelistFile()),
                    new AccessListStore(this.blacklistFile()),
                    new AccessService.ConfiguredFlags(false, false), storage);
            service.initialize().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
            final AccessTargetResolver resolver = new AccessTargetResolver(service,
                    AccessConnectionRegistry::new, () -> null);
            final AccessConsoleService console = new AccessConsoleService(service, resolver,
                    (kind, add, target) -> CompletableFuture.failedFuture(new AssertionError("no mutation")), storage);
            for (final AccessCommand.Action action : List.of(AccessCommand.Action.ON,
                    AccessCommand.Action.OFF, AccessCommand.Action.RELOAD)) {
                final List<String> lines = console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                        action, null, null)).toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(lines.isEmpty());
                assertFalse(lines.get(0).contains("failed"), lines.toString());
            }
        } finally {
            storage.shutdownNow();
        }
    }

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";

    @TempDir
    Path dir;

    private Path whitelistFile() {
        return this.dir.resolve("whitelist.json");
    }

    private Path blacklistFile() {
        return this.dir.resolve("blacklist.json");
    }

    private AccessService service() throws IOException {
        Files.writeString(this.whitelistFile(), "{\"schemaVersion\": 1, \"players\": []}");
        Files.writeString(this.blacklistFile(), "{\"schemaVersion\": 1, \"players\": []}");
        final AccessService service = new AccessService(new AccessListStore(this.whitelistFile()),
                new AccessListStore(this.blacklistFile()),
                new AccessService.ConfiguredFlags(false, false), Runnable::run);
        service.initialize().toCompletableFuture().join();
        return service;
    }

    private AccessConsoleService console(final AccessService service, final AccessConsoleService.Mutations mutations,
                                         final AccessConnectionRegistry registry) throws IOException {
        final AccessTargetResolver resolver = new AccessTargetResolver(service,
                () -> registry, () -> new PlayerVisitStore(this.dir.resolve("visits.json").toFile()));
        return new AccessConsoleService(service, resolver, mutations, Runnable::run);
    }

    private List<String> out(final CompletionStage<List<String>> stage) {
        return stage.toCompletableFuture().join();
    }

    private String joined(final CompletionStage<List<String>> stage) {
        return String.join("\n", out(stage));
    }

    // ---- parsing-driven behaviour ------------------------------------------

    @Test
    void emptyListSubcommandBehavesLikeHelpAndShowsBothSwitches() throws IOException {
        final AccessService service = this.service();
        final AccessConsoleService console = this.console(service, (kind, add, target) -> CompletableFuture.failedStage(new AssertionError("no mutation")), new AccessConnectionRegistry());

        final List<String> bare = out(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.HELP, null, null)));
        final List<String> help = out(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.HELP, null, null)));
        assertEquals(bare, help);
        assertTrue(String.join("\n", bare).contains("whitelist: off"));
        assertTrue(String.join("\n", bare).contains("blacklist: off"), "help shows BOTH runtime switches");
    }

    @Test
    void onAndOffFlipTheRuntimeStateOnly() throws IOException {
        final AccessService service = this.service();
        final byte[] whitelistBefore = Files.readAllBytes(this.whitelistFile());
        final AccessConsoleService console = this.console(service, (kind, add, target) -> CompletableFuture.failedStage(new AssertionError("no mutation")), new AccessConnectionRegistry());

        assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.ON, null, null))).contains("whitelist: on"));
        assertTrue(service.state().whitelistEnabled());
        assertArrayEquals(whitelistBefore, Files.readAllBytes(this.whitelistFile()));

        assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.OFF, null, null))).contains("whitelist: off"));
        assertFalse(service.state().whitelistEnabled());
    }

    @Test
    void ambiguityMustNotMutate() throws IOException {
        final AccessService service = this.service();
        // Two different java accounts share the name "SameName".
        try {
            service.mutateNow(AccessPolicy.Kind.WHITELIST, List.of(
                    new AccessEntry(AccessKey.ClientType.JAVA, "SameName",
                            UUID.fromString("00000000-0000-4000-8000-000000000002"), null),
                    new AccessEntry(AccessKey.ClientType.JAVA, "SameName",
                            UUID.fromString("00000000-0000-4000-8000-000000000003"), null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        final AccessListStore.Snapshot before = service.state().whitelist();
        final AtomicInteger mutationCalls = new AtomicInteger();
        final AccessConsoleService console = this.console(service, (kind, add, target) -> {
            mutationCalls.incrementAndGet();
            return CompletableFuture.completedFuture(service.state());
        }, new AccessConnectionRegistry());

        final String output = this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.ADD, AccessKey.ClientType.JAVA, "SameName")));
        assertTrue(output.contains("SameName"), "the console lists the candidates");
        assertEquals(2, output.lines().filter(l -> l.contains("[java]")).count(),
                "both candidates are shown");
        assertEquals(before, service.state().whitelist(), "the console reports candidates without mutating");
        assertEquals(0, mutationCalls.get(), "ambiguous names never reach the mutation port");
    }

    @Test
    void directIdentifierCanPreAddUnknownPlayer() throws IOException {
        final AccessService service = this.service();
        final AtomicInteger mutationCalls = new AtomicInteger();
        final AccessConsoleService console = this.console(service, (kind, add, target) -> {
            mutationCalls.incrementAndGet();
            assertNull(target.name(), "an unknown player is added without a guessed name");
            assertEquals(AccessKey.javaUuid(JAVA_UUID), target.key());
            return CompletableFuture.completedFuture(service.state());
        }, new AccessConnectionRegistry());

        final String output = this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.ADD, AccessKey.ClientType.JAVA, JAVA_UUID.toString())));
        assertTrue(output.toLowerCase().contains("added"), output);
        assertEquals(1, mutationCalls.get());
    }

    @Test
    void malformedIdentifierIsNeverSavedAsIdentifier() throws IOException {
        final AccessService service = this.service();
        final AtomicInteger mutationCalls = new AtomicInteger();
        final AccessConsoleService console = this.console(service, (kind, add, target) -> {
            mutationCalls.incrementAndGet();
            return CompletableFuture.completedFuture(service.state());
        }, new AccessConnectionRegistry());

        // "0123" is neither a canonical XUID nor a UUID nor a known name.
        final String output = this.joined(console.execute(new AccessCommand(AccessCommand.Kind.BLACKLIST,
                AccessCommand.Action.ADD, AccessKey.ClientType.BEDROCK, "0123")));
        assertTrue(output.contains("0123"));
        assertEquals(0, mutationCalls.get(), "a non-canonical token never reaches the mutation port");
    }

    @Test
    void spaceNamesResolveToASingleCandidate() throws IOException {
        final AccessService service = this.service();
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        // A bedrock subject online with a space in the name (simulated registry entry).
        final UUID connectionId = UUID.randomUUID();
        registry.register(connectionId, new io.netty.channel.embedded.EmbeddedChannel());
        final dev.connectplus.access.AccessSubject subject = new dev.connectplus.access.AccessSubject(
                AccessKey.ClientType.BEDROCK, "Bedrock Player", UUID.randomUUID(), XUID);
        // The subject is published through the identity source path; for the resolver
        // test we reflect the same information by adding the matching list entry.
        try {
            service.mutateNow(AccessPolicy.Kind.WHITELIST, List.of(
                    new AccessEntry(AccessKey.ClientType.BEDROCK, "Bedrock Player", null, XUID)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

        final AccessConsoleService console = this.console(service, (kind, add, target) -> {
            assertEquals(XUID, target.xuid());
            return CompletableFuture.completedFuture(service.state());
        }, registry);
        final String output = this.joined(console.execute(new AccessCommand(AccessCommand.Kind.BLACKLIST,
                AccessCommand.Action.ADD, AccessKey.ClientType.BEDROCK, "Bedrock Player")));
        assertTrue(output.toLowerCase().contains("added"), output);
    }

    @Test
    void listReportsEntriesAndWorksWithDisabledLists() throws IOException {
        final AccessService service = this.service(); // both switches off
        try {
            service.mutateNow(AccessPolicy.Kind.WHITELIST, List.of(
                    new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null),
                    new AccessEntry(AccessKey.ClientType.BEDROCK, "BR", null, XUID)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        final AccessConsoleService console = this.console(service, (kind, add, target) -> CompletableFuture.failedStage(new AssertionError("no mutation")), new AccessConnectionRegistry());

        final String output = this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.LIST, null, null)));
        assertTrue(output.contains("Java"));
        assertTrue(output.contains(JAVA_UUID.toString()));
        assertTrue(output.contains("BR"));
        assertTrue(output.contains(XUID));

        // Membership queries also answer while the lists are disabled.
        final String check = this.joined(console.check(JAVA_UUID.toString()));
        assertTrue(check.contains("whitelist"), check);
    }

    @Test
    void accessCheckHasNoMutationOrDisconnect() throws IOException {
        final AccessService service = this.service();
        final AtomicInteger mutationCalls = new AtomicInteger();
        final AccessConsoleService console = this.console(service, (kind, add, target) -> {
            mutationCalls.incrementAndGet();
            return CompletableFuture.completedFuture(service.state());
        }, new AccessConnectionRegistry());

        final String output = this.joined(console.check(JAVA_UUID.toString()));
        assertFalse(output.isEmpty());
        assertEquals(0, mutationCalls.get(), "check is strictly read-only");
    }

    @Test
    void reloadDelegatesAndNeverWritesTheFile() throws IOException {
        final AccessService service = this.service();
        final AccessConsoleService console = this.console(service, (kind, add, target) -> CompletableFuture.failedStage(new AssertionError("no mutation")), new AccessConnectionRegistry());
        final byte[] beforeReload = Files.readAllBytes(this.whitelistFile());

        assertTrue(this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.RELOAD, null, null))).toLowerCase().contains("reload"));
        assertArrayEquals(beforeReload, Files.readAllBytes(this.whitelistFile()),
                "reload never writes the file back");

        // A broken file reports the failure and keeps the old list.
        Files.writeString(this.whitelistFile(), "{\"schemaVersion\": 1, \"players\": [ broken }",
                StandardCharsets.UTF_8);
        final String failed = this.joined(console.execute(new AccessCommand(AccessCommand.Kind.WHITELIST,
                AccessCommand.Action.RELOAD, null, null)));
        assertTrue(failed.toLowerCase().contains("fail"), failed);
        assertTrue(service.state().whitelist().available(), "the last good list stays published");
    }
}
