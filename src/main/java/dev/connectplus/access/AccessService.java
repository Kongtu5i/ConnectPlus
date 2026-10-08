package dev.connectplus.access;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The access rule service: the startup flags come from the config, the runtime
 * switches live only here (a restart restores the configured flags), list
 * entries live in the two {@link AccessListStore}s and every accepted change
 * publishes exactly one new immutable {@link AccessPolicy.State}.
 *
 * <p>Disk work runs on the injected executor (the plugin's storage executor) so
 * connection handling and console events never block on file IO. Subscribers
 * are notified on that executor after a publish — never inline in the caller's
 * commit critical section. {@link #mutateNow} and {@link #setPendingInheritanceNow}
 * must be called inside the shared {@link AccessMutationCoordinator} lock.</p>
 */
public final class AccessService {

    /** The startup configuration snapshot; runtime switches never rewrite it. */
    public record ConfiguredFlags(boolean whitelist, boolean blacklist) {
    }

    private final AccessListStore whitelist;
    private final AccessListStore blacklist;
    private final ConfiguredFlags configuredFlags;
    private final Executor executor;
    private final List<Consumer<AccessPolicy.State>> subscribers = new CopyOnWriteArrayList<>();
    private volatile AccessPolicy.State state;
    private final Object stateLock = new Object();
    private volatile AccessMutationCoordinator commitLock = new AccessMutationCoordinator();

    /** Configured during coordinator setup, before console/binding work is accepted. */
    public void useCommitLock(final AccessMutationCoordinator commitLock) {
        this.commitLock = Objects.requireNonNull(commitLock);
    }

    public AccessService(final AccessListStore whitelist, final AccessListStore blacklist,
                         final ConfiguredFlags configuredFlags, final Executor executor) {
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
        this.blacklist = Objects.requireNonNull(blacklist, "blacklist");
        this.configuredFlags = Objects.requireNonNull(configuredFlags, "configuredFlags");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.state = new AccessPolicy.State(configuredFlags.whitelist(), configuredFlags.blacklist(),
                whitelist.snapshot(), blacklist.snapshot(), Set.of(), 0);
    }

    /** The currently published rule state. */
    public AccessPolicy.State state() {
        return this.state;
    }

    public ConfiguredFlags configuredFlags() {
        return this.configuredFlags;
    }

    /**
     * Loads both lists once at startup. A disabled list is initialized (a
     * missing file may be created as an empty list); an enabled list is loaded
     * strictly — a missing or broken file leaves it unavailable, which the
     * policy turns into rejections until a successful reload repairs it.
     */
    public CompletionStage<AccessPolicy.State> initialize() {
        return this.runAsync(() -> {
            this.loadForStartup(this.whitelist, this.configuredFlags.whitelist());
            this.loadForStartup(this.blacklist, this.configuredFlags.blacklist());
            return this.publishStateFromStores();
        });
    }

    /** Flips one runtime switch; never touches the config or the list files. */
    public CompletionStage<AccessPolicy.State> setEnabled(final AccessPolicy.Kind kind, final boolean enabled) {
        return this.runAsync(() -> {
            return this.updateState(current -> new AccessPolicy.State(
                    kind == AccessPolicy.Kind.WHITELIST ? enabled : current.whitelistEnabled(),
                    kind == AccessPolicy.Kind.BLACKLIST ? enabled : current.blacklistEnabled(),
                    current.whitelist(), current.blacklist(), current.pendingInheritance(),
                    current.revision() + 1));
        });
    }

    /** Hot-reloads one list file; a failure keeps the last good state and reports the error. */
    public CompletionStage<AccessPolicy.State> reload(final AccessPolicy.Kind kind) {
        return this.runAsync(() -> this.commitLock.withCommitLock(() -> {
            final AccessListStore store = this.store(kind);
            store.reload(); // a failure propagates; the last good snapshot stays published
            return this.publishStateFromStores();
        }));
    }

    /**
     * Applies adds/removes to one list and commits the file once. Adding an
     * already present key is a no-op (the stored record stays); removing a
     * missing key is a no-op. Must be called inside the shared commit lock.
     */
    public AccessPolicy.State mutateNow(final AccessPolicy.Kind kind, final Collection<AccessEntry> adds,
                                        final Set<AccessKey> removes) throws IOException {
        return this.mutateNow(kind, adds, removes, false);
    }

    /** Explicit console adds may fill a missing name; recovery keeps existing entries intact. */
    public AccessPolicy.State mutateNow(final AccessPolicy.Kind kind, final Collection<AccessEntry> adds,
                                        final Set<AccessKey> removes, final boolean enrichMissingNames) throws IOException {
        Objects.requireNonNull(adds, "adds");
        Objects.requireNonNull(removes, "removes");
        final AccessListStore store = this.store(kind);
        final AccessListStore.Snapshot current = store.snapshot();
        if (!current.available()) {
            throw new IOException(kind + " has no valid list data; repair the file and reload before mutating");
        }
        final Map<AccessKey, AccessEntry> merged = new LinkedHashMap<>(current.entries());
        for (final AccessEntry add : adds) {
            final AccessEntry existing = merged.get(add.key());
            if (existing == null) {
                merged.put(add.key(), add);
            } else if (enrichMissingNames && (existing.name() == null || existing.name().isBlank())
                    && add.name() != null && !add.name().isBlank()) {
                merged.put(add.key(), new AccessEntry(existing.clientType(), add.name(),
                        existing.uuid(), existing.xuid()));
            }
        }
        for (final AccessKey remove : removes) {
            merged.remove(remove);
        }
        store.replace(current.revision(), merged.values());
        return this.publishStateFromStores();
    }

    /** Replaces the pending-blacklist-inheritance set; must run inside the commit lock. */
    public AccessPolicy.State setPendingInheritanceNow(final Set<AccessKey> pending) {
        Objects.requireNonNull(pending, "pending");
        return this.updateState(current -> new AccessPolicy.State(current.whitelistEnabled(), current.blacklistEnabled(),
                current.whitelist(), current.blacklist(), pending, current.revision() + 1));
    }

    /** Registers a state subscriber; the returned handle closes the subscription. */
    public AutoCloseable subscribe(final Consumer<AccessPolicy.State> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        this.subscribers.add(subscriber);
        return () -> this.subscribers.remove(subscriber);
    }

    // ---- internals ---------------------------------------------------------

    private AccessListStore store(final AccessPolicy.Kind kind) {
        return kind == AccessPolicy.Kind.WHITELIST ? this.whitelist : this.blacklist;
    }

    private void loadForStartup(final AccessListStore store, final boolean enabled) {
        try {
            // An enabled list loads strictly (a missing file is an error state, never
            // generated away); a disabled list may create its empty file on first start.
            store.load(!enabled);
        } catch (final IOException e) {
            // The store keeps its unavailable (or last good) snapshot; the policy
            // turns an enabled-but-unavailable list into rejections.
        }
    }

    private AccessPolicy.State publishStateFromStores() {
        return this.updateState(current -> new AccessPolicy.State(current.whitelistEnabled(), current.blacklistEnabled(),
                this.whitelist.snapshot(), this.blacklist.snapshot(), current.pendingInheritance(),
                current.revision() + 1));
    }

    private AccessPolicy.State updateState(final java.util.function.UnaryOperator<AccessPolicy.State> update) {
        final AccessPolicy.State newState;
        synchronized (this.stateLock) {
            newState = update.apply(this.state);
            this.state = newState;
        }
        // Notify outside the state critical section, including direct test executors.
        try {
            this.executor.execute(() -> {
                for (final Consumer<AccessPolicy.State> subscriber : this.subscribers) {
                    try {
                        subscriber.accept(newState);
                    } catch (final RuntimeException ignored) {
                        // One broken subscriber never breaks the publication.
                    }
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException stopping) {
            // The committed state remains authoritative even during executor shutdown.
            // In particular, pending inheritance must never escape as a precommit abort.
            org.slf4j.LoggerFactory.getLogger("ConnectPlus-Access")
                    .warn("Access state committed while notification executor was stopping");
        }
        return newState;
    }

    private CompletableFuture<AccessPolicy.State> runAsync(final Supplier<AccessPolicy.State> action) {
        final CompletableFuture<AccessPolicy.State> future = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    future.complete(action.get());
                } catch (final Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (final RuntimeException t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /** Minimal supplier shape that may throw. */
    @FunctionalInterface
    private interface Supplier<T> {
        T get() throws Exception;
    }
}
