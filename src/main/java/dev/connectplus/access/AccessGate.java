package dev.connectplus.access;

import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.plugins.events.Client2ProxyChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.ClientLoggedInEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * The admission gate for every player-to-proxy connection. Registration happens
 * on the official channel-init event (PRE), admission on the official
 * ClientLoggedInEvent (capability report 2026-10-07 §2/§4): a synchronous deny
 * closes the client before the host forwards the backend login; a deny that
 * lands after an asynchronous identity result disconnects the client wherever
 * it is (backend included) and never via the kick-and-return-to-lobby path.
 *
 * <p>Denial is terminal in the registry: late bridge answers, rule reloads or
 * switch retries can never re-admit a denied connection. A pending identity
 * blocks ConnectPlus' own protected operations (accounts, binding transfers,
 * switches) via {@link #protectedOperationsAllowed}; the vanilla forwarding of
 * the host is not and cannot be held — covered by the online recheck instead.</p>
 */
public final class AccessGate {

    private static volatile AccessGate installed;

    /** The live gate for cross-cutting checks (account policy, switch engine); null in tests. */
    public static AccessGate installed() {
        return installed;
    }

    /** Installs the gate for the cross-cutting checks; CoreMain lifecycle. */
    public void install() {
        installed = this;
    }

    /** Clears the installed gate; CoreMain lifecycle. */
    public static void uninstall() {
        installed = null;
    }

    private final AccessService service;
    private final AccessConnectionRegistry registry;
    private final AccessIdentitySource identitySource;
    /** Schedules a recheck task on the channel's owning event loop (tests run inline). */
    private final BiConsumer<Channel, Runnable> loopScheduler;
    private java.util.function.Consumer<AccessSubject> subjectObserver = subject -> {};

    public void setSubjectObserver(final java.util.function.Consumer<AccessSubject> observer) {
        this.subjectObserver = Objects.requireNonNull(observer);
    }

    public AccessGate(final AccessService service, final AccessConnectionRegistry registry,
                      final AccessIdentitySource identitySource, final BiConsumer<Channel, Runnable> loopScheduler) {
        this.service = Objects.requireNonNull(service, "service");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
        this.loopScheduler = Objects.requireNonNull(loopScheduler, "loopScheduler");
    }

    // ---- official event hooks ----------------------------------------------

    @EventHandler
    public void onClient2ProxyChannelInitialize(final Client2ProxyChannelInitializeEvent event) {
        if (!ITyped.Type.PRE.equals(event.getType())) {
            return;
        }
        final Channel c2p = event.getChannel();
        // The identity capture's PRE handler ran first (registration order) and
        // assigned the id; defensively assign one when it did not.
        c2p.attr(CPAttributeKeys.CONNECTION_ID).setIfAbsent(UUID.randomUUID());
        final UUID connectionId = c2p.attr(CPAttributeKeys.CONNECTION_ID).get();
        this.whenAdmitted(c2p);
        this.registry.register(connectionId, c2p);
        c2p.closeFuture().addListener(future -> this.registry.remove(connectionId, c2p));
    }

    @EventHandler
    public void onClientLoggedIn(final ClientLoggedInEvent event) {
        this.admit(event.getProxyConnection());
    }

    // ---- admission -----------------------------------------------------------

    /**
     * Admits one connection: resolves the subject (java identities are
     * immediate, bedrock rides the capture's bridge budget), evaluates the
     * LATEST rule state and disconnects on a negative decision.
     */
    public CompletionStage<AccessPolicy.Decision> admit(final ProxyConnection connection) {
        Objects.requireNonNull(connection, "connection");
        final Channel c2p = connection.getC2P();
        if (c2p == null) {
            return CompletableFuture.completedFuture(new AccessPolicy.Decision(AccessPolicy.Reason.LIST_UNAVAILABLE));
        }
        this.ensureRegistered(c2p);
        final UUID connectionId = c2p.attr(CPAttributeKeys.CONNECTION_ID).get();
        return this.identitySource.resolve(connection).thenApply(subject -> {
            this.registry.update(connectionId, subject);
            final AccessPolicy.Decision decision = AccessPolicy.evaluate(this.service.state(), subject);
            if (decision.allowed()) {
                // A denied connection can never be marked allowed again (terminal).
                this.registry.mark(connectionId, AccessConnectionRegistry.Status.ALLOWED);
                if (subject.clientType() == AccessKey.ClientType.BEDROCK && subject.xuid() == null
                        && this.service.state().blacklistEnabled()) {
                    AccessRejectionLog.writeIdentifierLimitation(subject);
                }
            } else {
                this.inAdmission.set(Boolean.TRUE);
                try {
                    this.reject(connection, decision);
                } finally {
                    this.inAdmission.set(Boolean.FALSE);
                }
            }
            this.whenAdmitted(c2p).toCompletableFuture().complete(decision.allowed() && !this.isDenied(c2p));
            try {
                this.subjectObserver.accept(subject);
            } catch (final RuntimeException metadataFailure) {
                org.slf4j.LoggerFactory.getLogger("ConnectPlus-Access")
                        .warn("Entry name metadata could not be recorded; access decision unchanged", metadataFailure);
            }
            return decision;
        });
    }

    /** A profile load may await admission without mistaking verified identity for permission. */
    public CompletionStage<Boolean> whenAdmitted(final Channel c2p) {
        final var attribute = c2p.attr(CPAttributeKeys.ACCESS_ADMISSION);
        attribute.setIfAbsent(new CompletableFuture<>());
        return attribute.get();
    }

    public boolean isDenied(final Channel c2p) {
        return c2p != null && Boolean.TRUE.equals(c2p.attr(CPAttributeKeys.ACCESS_DENIED).get());
    }

    /** Whether this exact channel passed the current rules (live evaluation). */
    public boolean isAllowed(final Channel c2p) {
        if (this.isDenied(c2p)) return false;
        final AccessConnectionRegistry.Connection entry = this.registry.byChannel(c2p);
        if (entry == null || entry.status() != AccessConnectionRegistry.Status.ALLOWED || entry.subject() == null) {
            return false;
        }
        return AccessPolicy.evaluate(this.service.state(), entry.subject()).allowed();
    }

    /**
     * Whether ConnectPlus' own protected operations (accounts, binding flows,
     * switches) may run for this connection: the lists must pass AND the
     * primary key must have no unfinished blacklist inheritance. Never a
     * replacement for the original authentication and lease checks.
     */
    public boolean protectedOperationsAllowed(final Channel c2p) {
        if (this.isDenied(c2p)) return false;
        final AccessConnectionRegistry.Connection entry = this.registry.byChannel(c2p);
        if (entry == null || entry.status() != AccessConnectionRegistry.Status.ALLOWED || entry.subject() == null) {
            return false;
        }
        final AccessKey key = entry.subject().key();
        if (key != null && this.service.state().pendingInheritance().contains(key)) {
            return false;
        }
        return AccessPolicy.evaluate(this.service.state(), entry.subject()).allowed();
    }

    /**
     * Re-evaluates every live connection after a rule change; the evaluation
     * always uses the service's LATEST state at execution time, so a recheck
     * triggered by a stale state can never re-allow anything. Denied
     * connections and connections without a subject are skipped.
     */
    public CompletionStage<Void> recheck(final AccessPolicy.State triggeredState) {
        final java.util.List<CompletableFuture<Void>> pending = new java.util.ArrayList<>();
        for (final AccessConnectionRegistry.Connection connection : this.registry.snapshot()) {
            if (connection.status() == AccessConnectionRegistry.Status.DENIED || connection.subject() == null) {
                continue;
            }
            final Channel c2p = connection.c2p();
            if (!c2p.isActive()) {
                continue;
            }
            final CompletableFuture<Void> done = new CompletableFuture<>();
            pending.add(done);
            this.loopScheduler.accept(c2p, () -> {
                try {
                    final AccessConnectionRegistry.Connection current = this.registry.byChannel(c2p);
                    if (current == null || current.status() == AccessConnectionRegistry.Status.DENIED
                            || current.subject() == null) {
                        return;
                    }
                    final AccessPolicy.Decision decision = AccessPolicy.evaluate(this.service.state(), current.subject());
                    if (!decision.allowed()) {
                        final ProxyConnection pc = ProxyConnection.fromChannel(c2p);
                        if (pc != null) {
                            this.reject(pc, decision);
                        }
                    }
                } finally {
                    done.complete(null);
                }
            });
        }
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
    }

    /**
     * Rejects one connection: marks it denied (terminal), logs the reason and
     * closes the client with the reason message through the official kick —
     * never via a backend kick-and-return-to-lobby flow.
     */
    public void reject(final ProxyConnection connection, final AccessPolicy.Decision decision) {
        Objects.requireNonNull(decision, "decision");
        final Channel c2p = connection.getC2P();
        final Channel backend = connection.getChannel(); // capture exact current p2s before disconnect callbacks
        if (c2p != null) {
            c2p.attr(CPAttributeKeys.ACCESS_DENIED).set(true);
            this.whenAdmitted(c2p).toCompletableFuture().complete(false);
        }
        final AccessConnectionRegistry.Connection entry = c2p == null ? null : this.registry.byChannel(c2p);
        final boolean alreadyDenied = entry == null || entry.status() == AccessConnectionRegistry.Status.DENIED;
        if (entry != null) {
            this.registry.mark(entry.connectionId(), AccessConnectionRegistry.Status.DENIED);
        }
        if (!alreadyDenied) {
            // Repeated reject callbacks for the SAME connection refusal log once.
            AccessRejectionLog.write(entry != null ? entry.subject() : null, decision, this.sourceOf(decision));
        }
        if (c2p == null) {
            return;
        }
        try {
            connection.kickClient(this.kickMessage(c2p, decision));
        } catch (final RuntimeException ignored) { // includes the official CloseAndReturn control-flow throw
            try {
                c2p.close();
            } catch (final RuntimeException ignoredAgain) {
            }
        } finally {
            // Official LoggedIn listeners do not cancel the subsequent backend write.
            // Queue close on that exact p2s BEFORE the host queues its LoginHello.
            if (backend != null && backend != c2p) backend.close();
        }
    }

    /** Whether the refusal came from the entry check or from an online rule update. */
    private String sourceOf(final AccessPolicy.Decision decision) {
        return this.inAdmission.get() ? "connect" : "rule-update";
    }

    /** Thread-local admission marker: the entry check runs on the connection's own flow. */
    private final ThreadLocal<Boolean> inAdmission = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The kick message in the session's language (existing language rules). */
    private String kickMessage(final Channel c2p, final AccessPolicy.Decision decision) {
        final var session = c2p.attr(CPAttributeKeys.LOBBY_SESSION).get();
        final var lang = session != null
                ? dev.connectplus.lobby.screen.Languages.forSession(session)
                : dev.connectplus.lobby.screen.Languages.forLocale(null);
        final var message = switch (decision.reason()) {
            case BLACKLISTED -> dev.connectplus.lobby.screen.Messages.AccessControl.Blacklisted;
            case NOT_WHITELISTED -> dev.connectplus.lobby.screen.Messages.AccessControl.NotWhitelisted;
            case IDENTIFIER_UNAVAILABLE -> dev.connectplus.lobby.screen.Messages.AccessControl.IdentifierUnavailable;
            default -> dev.connectplus.lobby.screen.Messages.AccessControl.ListUnavailable;
        };
        return dev.connectplus.lobby.screen.Languages.text(lang, message);
    }

    private void ensureRegistered(final Channel c2p) {
        if (!c2p.isActive()) {
            return; // a closed channel never re-enters the registry
        }
        if (this.registry.byChannel(c2p) == null) {
            c2p.attr(CPAttributeKeys.CONNECTION_ID).setIfAbsent(UUID.randomUUID());
            final UUID connectionId = c2p.attr(CPAttributeKeys.CONNECTION_ID).get();
            this.registry.register(connectionId, c2p);
            c2p.closeFuture().addListener(future -> this.registry.remove(connectionId, c2p));
        }
    }
}
