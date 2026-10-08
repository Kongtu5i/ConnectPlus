package dev.connectplus.commands;

import dev.connectplus.access.AccessConnectionRegistry;
import dev.connectplus.access.AccessListStore;
import dev.connectplus.access.AccessEntry;
import dev.connectplus.access.AccessKey;
import dev.connectplus.access.AccessPolicy;
import dev.connectplus.access.AccessService;
import dev.connectplus.access.AccessSubject;
import dev.connectplus.access.AccessTargetResolver;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages.ConsoleAccess;
import dev.connectplus.lobby.screen.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * The console behaviour for the access-list subtree: help with both runtime
 * switches, on/off, add/remove (through the {@link Mutations} port so the
 * binding coordinator owns the linked-accounts sync), list, reload and the
 * read-only {@code access check} query.
 *
 * <p>Only a resolved, unique target reaches a mutation; an ambiguous name is
 * answered with the candidates and nothing else. A failed reload reports the
 * error and leaves the last good list published. The check query never mutates,
 * never disconnects and never probes the real bridge availability.</p>
 */
public final class AccessConsoleService {

    /**
     * The write port for add/remove. Task 6 binds the binding coordinator's
     * change method here (both linked accounts in one commit); tests inject a
     * recording double.
     */
    public interface Mutations {
        CompletionStage<AccessPolicy.State> change(AccessPolicy.Kind kind, boolean add, AccessEntry target);
    }

    /** Shared by the root help and both access subtrees, so aliases cannot drift. */
    public static List<String> helpSummary() {
        return List.of(text(ConsoleAccess.HelpWhitelist), text(ConsoleAccess.HelpBlacklist),
                text(ConsoleAccess.HelpMutation), text(ConsoleAccess.HelpCheck));
    }

    private final AccessService service;
    private final AccessTargetResolver resolver;
    private final Mutations mutations;
    private final Executor executor;

    public AccessConsoleService(final AccessService service, final AccessTargetResolver resolver,
                                final Mutations mutations, final Executor executor) {
        this.service = Objects.requireNonNull(service, "service");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Executes one parsed access command; the lines are printed by the console listener. */
    public CompletionStage<List<String>> execute(final AccessCommand command) {
        Objects.requireNonNull(command, "command");
        final CompletableFuture<List<String>> result = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    this.run(command).whenComplete((lines, error) -> result.complete(error == null
                            ? lines : List.of(text(ConsoleAccess.Failed, rootMessage(error)))));
                } catch (final Exception e) {
                    result.complete(List.of(text(ConsoleAccess.Failed, rootMessage(e))));
                }
            });
        } catch (final RuntimeException e) {
            result.complete(List.of(text(ConsoleAccess.Failed, rootMessage(e))));
        }
        return result;
    }

    private CompletionStage<List<String>> run(final AccessCommand command) {
        return switch (command.action()) {
            case HELP -> CompletableFuture.completedFuture(this.help());
            case ON -> this.flip(command.list(), true);
            case OFF -> this.flip(command.list(), false);
            case LIST -> CompletableFuture.completedFuture(this.list(command.list()));
            case RELOAD -> this.reload(command.list());
            case ADD -> CompletableFuture.completedFuture(this.mutate(command, true));
            case REMOVE -> CompletableFuture.completedFuture(this.mutate(command, false));
        };
    }

    // ---- help / switches ------------------------------------------------------

    private List<String> help() {
        final AccessPolicy.State state = this.service.state();
        final AccessService.ConfiguredFlags config = this.service.configuredFlags();
        final List<String> lines = new ArrayList<>();
        lines.add(text(ConsoleAccess.RuntimeState, text(ConsoleAccess.WhitelistLabel),
                onOff(state.whitelistEnabled()), onOff(config.whitelist())));
        lines.add(text(ConsoleAccess.RuntimeState, text(ConsoleAccess.BlacklistLabel),
                onOff(state.blacklistEnabled()), onOff(config.blacklist())));
        lines.addAll(helpSummary());
        lines.add(text(ConsoleAccess.ReloadHint));
        return List.copyOf(lines);
    }

    private static String onOff(final boolean value) {
        return value ? "on" : "off";
    }

    private CompletionStage<List<String>> flip(final AccessCommand.Kind kind, final boolean on) {
        return this.service.setEnabled(
                kind == AccessCommand.Kind.WHITELIST ? AccessPolicy.Kind.WHITELIST : AccessPolicy.Kind.BLACKLIST,
                on).thenApply(state -> {
            final boolean runtime = kind == AccessCommand.Kind.WHITELIST
                    ? state.whitelistEnabled() : state.blacklistEnabled();
            return List.of(text(ConsoleAccess.RuntimeChanged, label(kind), onOff(runtime)));
        });
    }

    // ---- list ------------------------------------------------------------------

    private List<String> list(final AccessCommand.Kind kind) {
        final AccessListStore.Snapshot snapshot = kind == AccessCommand.Kind.WHITELIST
                ? this.service.state().whitelist() : this.service.state().blacklist();
        if (!snapshot.available()) {
            return List.of(text(ConsoleAccess.ListUnavailable, label(kind)));
        }
        final List<String> lines = new ArrayList<>();
        lines.add(text(ConsoleAccess.ListHeader, label(kind), snapshot.entries().size()));
        for (final AccessEntry entry : snapshot.entries().values()) {
            lines.add(describe(entry));
        }
        return List.copyOf(lines);
    }

    // ---- reload ------------------------------------------------------------------

    private CompletionStage<List<String>> reload(final AccessCommand.Kind kind) {
        final AccessPolicy.Kind policyKind = kind == AccessCommand.Kind.WHITELIST
                ? AccessPolicy.Kind.WHITELIST : AccessPolicy.Kind.BLACKLIST;
        return this.service.reload(policyKind).handle((state, error) -> {
            if (error != null) {
                return List.of(text(ConsoleAccess.ReloadFailed, label(kind), rootMessage(error)));
            }
            final AccessListStore.Snapshot snapshot = policyKind == AccessPolicy.Kind.WHITELIST
                    ? state.whitelist() : state.blacklist();
            return List.of(text(ConsoleAccess.Reloaded, label(kind), snapshot.entries().size()));
        });
    }

    // ---- mutations -------------------------------------------------------------

    private List<String> mutate(final AccessCommand command, final boolean add) {
        final List<AccessEntry> targets = this.resolver.resolve(command.clientType(), command.target());
        if (targets.isEmpty()) {
            return List.of(text(ConsoleAccess.UnknownTarget, command.target()));
        }
        if (targets.size() > 1) {
            final List<String> lines = new ArrayList<>();
            lines.add(text(ConsoleAccess.MultipleTargets, command.target()));
            for (final AccessEntry candidate : targets) {
                lines.add("  " + describe(candidate));
            }
            lines.add(text(ConsoleAccess.UseExactIdentifier));
            return List.copyOf(lines);
        }
        final AccessEntry target = targets.get(0);
        try {
            this.mutations.change(kindOf(command.list()), add, target).toCompletableFuture().join();
        } catch (final Exception e) {
            return List.of(text(add ? ConsoleAccess.AddFailed : ConsoleAccess.RemoveFailed,
                    describe(target), rootMessage(e)));
        }
        return List.of(text(add ? ConsoleAccess.AddedToList : ConsoleAccess.RemovedFromList,
                describe(target), label(command.list())));
    }

    private static AccessPolicy.Kind kindOf(final AccessCommand.Kind kind) {
        return kind == AccessCommand.Kind.WHITELIST ? AccessPolicy.Kind.WHITELIST : AccessPolicy.Kind.BLACKLIST;
    }

    // ---- read-only check ---------------------------------------------------------

    /** Read-only membership query; never mutates, never disconnects, never probes the bridge. */
    public CompletionStage<List<String>> check(final String target) {
        Objects.requireNonNull(target, "target");
        final CompletableFuture<List<String>> result = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    result.complete(this.runCheck(target));
                } catch (final Exception e) {
                    result.complete(List.of(text(ConsoleAccess.Failed, rootMessage(e))));
                }
            });
        } catch (final RuntimeException e) {
            result.complete(List.of(text(ConsoleAccess.Failed, rootMessage(e))));
        }
        return result;
    }

    private List<String> runCheck(final String target) {
        final List<AccessEntry> targets = this.resolver.resolveAny(target);
        if (targets.isEmpty()) {
            return List.of(text(ConsoleAccess.UnknownCheckTarget, target));
        }
        final AccessPolicy.State state = this.service.state();
        final List<String> lines = new ArrayList<>();
        for (final AccessEntry entry : targets) {
            final AccessSubject subject = new AccessSubject(entry.clientType(), entry.displayName(),
                    entry.uuid(), entry.xuid());
            final AccessPolicy.Reason reason = AccessPolicy.evaluate(state, subject).reason();
            lines.add(text(ConsoleAccess.CheckLine, describe(entry), membership(state.whitelist(), entry.key()),
                    membership(state.blacklist(), entry.key()), reasonText(reason)));
        }
        lines.add(text(ConsoleAccess.CheckHint));
        return List.copyOf(lines);
    }

    private static String membership(final AccessListStore.Snapshot snapshot, final AccessKey key) {
        if (!snapshot.available()) {
            return text(ConsoleAccess.Unavailable);
        }
        return text(key != null && snapshot.entries().containsKey(key) ? ConsoleAccess.Yes : ConsoleAccess.No);
    }

    private static String reasonText(final AccessPolicy.Reason reason) {
        return switch (reason) {
            case ALLOWED -> text(ConsoleAccess.Allowed);
            case BLACKLISTED -> text(ConsoleAccess.Blacklisted);
            case NOT_WHITELISTED -> text(ConsoleAccess.NotWhitelisted);
            case IDENTIFIER_UNAVAILABLE -> text(ConsoleAccess.IdentifierUnavailable);
            case LIST_UNAVAILABLE -> text(ConsoleAccess.ListDataUnavailable);
        };
    }

    // ---- helpers -----------------------------------------------------------------

    private static String describe(final AccessEntry entry) {
        final String name = entry.displayName() != null ? entry.displayName() : text(ConsoleAccess.UnknownName);
        return name + " [" + text(entry.clientType() == AccessKey.ClientType.JAVA
                ? ConsoleAccess.JavaLabel : ConsoleAccess.BedrockLabel) + "] "
                + (entry.clientType() == AccessKey.ClientType.JAVA
                ? entry.uuid() : entry.xuid());
    }

    private static String label(final AccessCommand.Kind kind) {
        return text(kind == AccessCommand.Kind.WHITELIST ? ConsoleAccess.WhitelistLabel : ConsoleAccess.BlacklistLabel);
    }

    private static final java.util.regex.Pattern PLACEHOLDERS = java.util.regex.Pattern.compile("\\{(\\d+)\\}");

    private static String text(final Msg message, final Object... arguments) {
        // A console has no client locale: auto follows the existing null-locale
        // fallback; explicit languages and custom packs use the normal resolver.
        final String template = Languages.text(Languages.forLocale(null), message);
        return PLACEHOLDERS.matcher(template).replaceAll(match -> {
            final int index = Integer.parseInt(match.group(1));
            return java.util.regex.Matcher.quoteReplacement(index < arguments.length
                    ? String.valueOf(arguments[index]) : match.group());
        });
    }

    private static String rootMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
