package dev.connectplus.access;

import dev.connectplus.session.PlayerVisitStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Resolves console targets to list entries. A canonical identifier (UUID or
 * XUID) resolves by format — that is how an unknown player is pre-added by
 * identifier. A name resolves case-insensitively over the registry subjects,
 * both list stores and the visit-store name metadata; same-name accounts stay
 * separate candidates and a temporary wire-only visit record never becomes a
 * Java identity. Nothing here writes anything.
 */
public final class AccessTargetResolver {

    private final AccessService service;
    private final Supplier<AccessConnectionRegistry> registry;
    private final Supplier<PlayerVisitStore> visitStore;

    public AccessTargetResolver(final AccessService service, final Supplier<AccessConnectionRegistry> registry,
                                final Supplier<PlayerVisitStore> visitStore) {
        this.service = Objects.requireNonNull(service, "service");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.visitStore = Objects.requireNonNull(visitStore, "visitStore");
    }

    /** Targets for one token on one platform; empty when nothing is known. */
    public List<AccessEntry> resolve(final AccessKey.ClientType type, final String token) {
        Objects.requireNonNull(type, "type");
        if (token == null || token.isBlank()) {
            return List.of();
        }
        final String trimmed = token.trim();
        final AccessEntry byIdentifier = this.byIdentifier(type, trimmed);
        if (byIdentifier != null) {
            return List.of(byIdentifier);
        }
        return this.byName(type, trimmed);
    }

    /** Targets for one token on any platform; the format decides for canonical identifiers. */
    public List<AccessEntry> resolveAny(final String token) {
        if (token == null || token.isBlank()) {
            return List.of();
        }
        final String trimmed = token.trim();
        final AccessEntry asJava = this.byIdentifier(AccessKey.ClientType.JAVA, trimmed);
        if (asJava != null) {
            return List.of(asJava);
        }
        final AccessEntry asBedrock = this.byIdentifier(AccessKey.ClientType.BEDROCK, trimmed);
        if (asBedrock != null) {
            return List.of(asBedrock);
        }
        return this.byName(null, trimmed);
    }

    // ---- identifier resolution ---------------------------------------------

    /** The entry for a canonical identifier, or null when the token is not one. */
    private AccessEntry byIdentifier(final AccessKey.ClientType type, final String token) {
        final AccessKey key = canonicalKey(type, token);
        if (key == null) {
            return null;
        }
        return new AccessEntry(type, this.knownNameFor(key), type == AccessKey.ClientType.JAVA
                ? UUID.fromString(token.toLowerCase(Locale.ROOT)) : null,
                type == AccessKey.ClientType.BEDROCK ? token : null);
    }

    private static AccessKey canonicalKey(final AccessKey.ClientType type, final String token) {
        try {
            return switch (type) {
                case JAVA -> {
                    final UUID uuid = UUID.fromString(token);
                    yield AccessKey.javaUuid(uuid);
                }
                case BEDROCK -> AccessKey.bedrockXuid(token); // rejects non-canonical input
            };
        } catch (final RuntimeException e) {
            return null; // format check failed: the token may still be a name
        }
    }

    /** The best known display name for this key: list stores, then registry, then visits. */
    private String knownNameFor(final AccessKey key) {
        final AccessPolicy.State state = this.service.state();
        for (final AccessListStore.Snapshot snapshot : new AccessListStore.Snapshot[]{
                state.whitelist(), state.blacklist()}) {
            if (snapshot.available() && snapshot.entries().containsKey(key)) {
                final String name = snapshot.entries().get(key).displayName();
                if (hasName(name)) return name;
            }
        }
        final AccessConnectionRegistry connections = this.registry.get();
        if (connections != null) {
            for (final AccessConnectionRegistry.Connection connection : connections.snapshot()) {
                if (connection.subject() != null && key.equals(connection.subject().key())
                        && hasName(connection.subject().name())) {
                    return connection.subject().name();
                }
            }
        }
        final PlayerVisitStore visits = this.visitStore.get();
        if (visits != null) {
            try {
                final PlayerVisitStore.Snapshot snapshot = visits.snapshot();
                if (key.type() == AccessKey.ClientType.JAVA) {
                    final String known = snapshot.knownJavaNames().get(key.id());
                    if (hasName(known)) return known;
                }
                for (final PlayerVisitStore.VisitEntry visit : snapshot.visits()) {
                    final boolean matches = key.type() == AccessKey.ClientType.JAVA
                            ? visit.kind() == PlayerVisitStore.Kind.JAVA && key.id().equals(visit.javaUuid())
                            : visit.kind() == PlayerVisitStore.Kind.BEDROCK && key.id().equals(visit.xuid());
                    if (matches && hasName(visit.name())) return visit.name();
                }
            } catch (final PlayerVisitStore.CorruptIndexException unavailable) {
                // Exact identifiers remain usable without optional name metadata.
                // Name-only queries still report this corruption; no file is rewritten.
            }
        }
        return null;
    }

    private static boolean hasName(final String name) {
        return name != null && !name.isBlank();
    }

    // ---- name resolution ------------------------------------------------------

    private List<AccessEntry> byName(final AccessKey.ClientType type, final String needle) {
        final Map<AccessKey, AccessEntry> candidates = new LinkedHashMap<>();
        // Registry subjects (confirmed or classified entry platform).
        if (this.registry.get() != null) {
            for (final AccessConnectionRegistry.Connection connection : this.registry.get().snapshot()) {
                final AccessSubject subject = connection.subject();
                if (subject == null || subject.name() == null) {
                    continue;
                }
                if (type != null && subject.clientType() != type) {
                    continue;
                }
                if (subject.name().equalsIgnoreCase(needle) && subject.key() != null) {
                    this.putCandidate(candidates, subject.clientType(), subject.name(),
                            subject.clientType() == AccessKey.ClientType.JAVA ? subject.entryUuid() : null,
                            subject.xuid());
                }
            }
        }
        // Both list stores (membership names, hot-reload respected via the state).
        final AccessPolicy.State state = this.service.state();
        for (final AccessListStore.Snapshot snapshot : new AccessListStore.Snapshot[]{
                state.whitelist(), state.blacklist()}) {
            if (!snapshot.available()) {
                continue;
            }
            for (final AccessEntry entry : snapshot.entries().values()) {
                if (entry.displayName() == null || (type != null && entry.clientType() != type)) {
                    continue;
                }
                if (entry.displayName().equalsIgnoreCase(needle)) {
                    this.putCandidate(candidates, entry.clientType(), entry.displayName(),
                            entry.uuid(), entry.xuid());
                }
            }
        }
        // Visit-store name metadata; wire-only temporary records are never a typed identity.
        final PlayerVisitStore visits = this.visitStore.get();
        if (visits != null) {
            for (final PlayerVisitStore.NameMatch match : visits.snapshot().matchByName(needle)) {
                if (match.javaUuid() != null && (type == null || type == AccessKey.ClientType.JAVA)) {
                    this.putCandidate(candidates, AccessKey.ClientType.JAVA, match.name(),
                            match.javaUuid(), null);
                }
                if (match.xuid() != null && (type == null || type == AccessKey.ClientType.BEDROCK)) {
                    this.putCandidate(candidates, AccessKey.ClientType.BEDROCK, match.name(),
                            null, match.xuid());
                }
            }
        }
        return List.copyOf(candidates.values());
    }

    private void putCandidate(final Map<AccessKey, AccessEntry> candidates, final AccessKey.ClientType type,
                              final String name, final java.util.UUID uuid, final String xuid) {
        try {
            final AccessEntry entry = new AccessEntry(type, name, uuid, xuid);
            candidates.putIfAbsent(entry.key(), entry);
        } catch (final RuntimeException ignored) {
            // A malformed identifier from a metadata source is not a candidate.
        }
    }
}
