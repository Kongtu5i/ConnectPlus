package dev.connectplus.commands;

import dev.connectplus.access.AccessListStore;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.compat.OnlinePlayerQuery;
import dev.connectplus.compat.OnlinePlayerQuery.Player;
import dev.connectplus.compat.OnlinePlayerQuery.Location;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.logging.DebugLog;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.lobby.screen.Messages.Console;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.PlayerVisitStore;
import dev.connectplus.session.PlayerVisitStore.CorruptIndexException;
import dev.connectplus.session.PlayerVisitStore.NameMatch;
import dev.connectplus.session.PlayerVisitStore.Snapshot;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static dev.connectplus.lobby.screen.Languages.consoleText;

/**
 * The console query service: turns a parsed {@link ConsoleCommands.Command}
 * into async output lines. It reads only through CP's own stores and the
 * read-only runtime surfaces — it never writes profiles, never touches tokens,
 * never changes authentication or host state (the debug switch only flips this
 * plugin's own runtime diagnostics gate and is deliberately not persisted).
 * Blocking store IO runs on the supplied executor, never on the console thread.
 */
public final class ConsoleCommandService {

    /**
     * The versions/addresses the runtime reports; all values may be null
     * (unavailable). The listen address is supplied per query: the host's proxy
     * server starts after plugin enable, so a value captured at startup would
     * stay null forever.
     */
    public record Runtime(@Nullable String pluginVersion, @Nullable String hostVersion,
                          java.util.function.Supplier<SocketAddress> hostListenAddress) {
    }

    private final Supplier<LobbyServer> lobbyServer;
    private final Supplier<BedrockBridgeEndpoint> bridgeEndpoint;
    private final Runtime runtime;
    private final IntSupplier inFlightTargetCount;
    private final PlayerStore playerStore;
    private final IdentityLinkStore linkStore;
    private final Supplier<PlayerVisitStore> visitStore;
    private final AccessConsoleService accessConsole;
    private final dev.connectplus.access.AccessService accessService;
    private final ExecutorService executor;
    private final Supplier<OnlinePlayerQuery.Snapshot> onlinePlayers;

    public ConsoleCommandService(final Supplier<LobbyServer> lobbyServer,
                                 final Supplier<BedrockBridgeEndpoint> bridgeEndpoint,
                                 final Runtime runtime, final IntSupplier inFlightTargetCount,
                                 final PlayerStore playerStore, final IdentityLinkStore linkStore,
                                 final Supplier<PlayerVisitStore> visitStore,
                                 final AccessConsoleService accessConsole,
                                 final dev.connectplus.access.AccessService accessService,
                                 final ExecutorService executor,
                                 final Supplier<OnlinePlayerQuery.Snapshot> onlinePlayers) {
        this.lobbyServer = lobbyServer;
        this.bridgeEndpoint = bridgeEndpoint;
        this.runtime = runtime;
        this.inFlightTargetCount = inFlightTargetCount;
        this.playerStore = playerStore;
        this.linkStore = linkStore;
        this.visitStore = visitStore;
        this.accessConsole = accessConsole;
        this.accessService = accessService;
        this.executor = executor;
        this.onlinePlayers = onlinePlayers;
    }

    /**
     * Executes the command asynchronously; the returned stage completes with
     * the output lines (never null). The console listener prints them.
     */
    public CompletionStage<List<String>> execute(final ConsoleCommands.Command command) {
        final CompletableFuture<List<String>> result = new CompletableFuture<>();
        this.executor.execute(() -> {
            try {
                result.complete(this.run(command));
            } catch (final Exception e) {
                result.complete(List.of(consoleText(Console.CommandFailed, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()))));
            }
        });
        return result;
    }

    private List<String> run(final ConsoleCommands.Command command) {
        return switch (command.type()) {
            case STATUS -> this.status();
            case LIST -> this.list();
            case LINKS -> this.links();
            case INFO -> this.info(command.argument());
            case VERSION -> this.version();
            case DEBUG -> this.debug("on".equals(command.argument()));
            case ACCOUNTS -> this.accounts();
            case HELP -> help();
            case USAGE -> usage();
            // The access console runs on the storage executor; joining here keeps
            // the whole console command on the console thread pool (no deadlock:
            // different executors).
            case ACCESS_LIST -> this.accessConsole.execute(command.access()).toCompletableFuture().join();
            case ACCESS_CHECK -> this.accessConsole.check(command.argument()).toCompletableFuture().join();
        };
    }

    // ---- status ----------------------------------------------------------

    private List<String> out() {
        return new ArrayList<>();
    }

    private List<String> status() {
        final List<String> lines = out();
        final LobbyServer lobby = this.lobbyServer.get();
        lines.add(consoleText(Console.StatusHeader));
        lines.add(consoleText(Console.StatusMode, CPConfig.mode));
        final SocketAddress hostAddress = this.runtime.hostListenAddress().get();
        lines.add(consoleText(Console.HostAddress, hostAddress != null ? hostAddress : consoleText(Console.Unavailable)));
        lines.add(consoleText(Console.LobbyAddress, lobby != null ? String.valueOf(lobby.localAddress()) : consoleText(Console.Disabled)));
        this.appendOnlinePlayers(lines, false);
        lines.add("    " + consoleText(Console.InFlightConnections, lobby != null ? this.inFlightTargetCount.getAsInt() : consoleText(Console.Disabled)));
        lines.add(consoleText(Console.LobbyExceptions, lobby != null ? lobby.uncaughtExceptionCount() : consoleText(Console.Disabled)));
        final BedrockBridgeEndpoint.RegistrationSnapshot bridge = this.bridgeSnapshot();
        lines.add(bridge != null ? consoleText(Console.BridgeRegistered, bridge.bridgeVersion(), bridge.geyserVersion())
                : consoleText(Console.BridgeUnregistered));
        this.appendAccessStatus(lines, consoleText(Console.Whitelist), dev.connectplus.access.AccessPolicy.Kind.WHITELIST);
        this.appendAccessStatus(lines, consoleText(Console.Blacklist), dev.connectplus.access.AccessPolicy.Kind.BLACKLIST);
        return lines;
    }

    /** The per-list status block: configured switch, runtime switch and account count. */
    private void appendAccessStatus(final List<String> lines, final String label,
                                    final dev.connectplus.access.AccessPolicy.Kind kind) {
        final AccessListStore.Snapshot snapshot = kind == dev.connectplus.access.AccessPolicy.Kind.WHITELIST
                ? this.accessService.state().whitelist() : this.accessService.state().blacklist();
        final boolean configured = kind == dev.connectplus.access.AccessPolicy.Kind.WHITELIST
                ? this.accessService.configuredFlags().whitelist() : this.accessService.configuredFlags().blacklist();
        final boolean runtime = kind == dev.connectplus.access.AccessPolicy.Kind.WHITELIST
                ? this.accessService.state().whitelistEnabled() : this.accessService.state().blacklistEnabled();
        lines.add(consoleText(Console.AccessStatusHeader, label));
        lines.add("    " + consoleText(Console.AccessConfigured, configured ? "on" : "off"));
        lines.add("    " + consoleText(Console.AccessRuntime, runtime ? "on" : "off"));
        lines.add("    " + consoleText(Console.AccessAccountCount, label, snapshot.available() ? snapshot.entries().size() : consoleText(Console.Unavailable)));
    }

    @Nullable
    private BedrockBridgeEndpoint.RegistrationSnapshot bridgeSnapshot() {
        final BedrockBridgeEndpoint endpoint = this.bridgeEndpoint.get();
        return endpoint == null ? null : endpoint.registrationSnapshot();
    }

    // ---- list ------------------------------------------------------------

    private List<String> list() {
        final List<String> lines = out();
        this.appendOnlinePlayers(lines, true);
        return lines;
    }

    private void appendOnlinePlayers(final List<String> lines, final boolean showPlayers) {
        final OnlinePlayerQuery.Snapshot snapshot = this.onlineSnapshot();
        lines.add(consoleText(Console.OnlineTotal, snapshot.players().size()) + (snapshot.available() ? "" : consoleText(Console.PartialOnlineNotice)));
        this.appendPlayerGroup(lines, snapshot.players(), Location.LOBBY, Console.GroupLobby, showPlayers);
        this.appendPlayerGroup(lines, snapshot.players(), Location.SERVER, Console.GroupServer, showPlayers);
        if (snapshot.players().stream().anyMatch(player -> player.location() == Location.SWITCHING)) {
            this.appendPlayerGroup(lines, snapshot.players(), Location.SWITCHING, Console.GroupSwitching, showPlayers);
        }
    }

    private void appendPlayerGroup(final List<String> lines, final List<Player> players,
                                   final Location location, final dev.connectplus.lobby.screen.Msg title, final boolean showPlayers) {
        final List<Player> group = players.stream().filter(player -> player.location() == location)
                .sorted(Comparator.comparing(player -> player.name().toLowerCase(Locale.ROOT))).toList();
        lines.add((showPlayers ? "" : "    ") + consoleText(title, group.size()));
        if (!showPlayers) return;
        if (group.isEmpty()) lines.add("  " + consoleText(Console.NoPlayers));
        for (final Player player : group) {
            lines.add("  " + consoleText(Console.PlayerLine, player.name(), player.clientVersion(),
                    player.location() == Location.SERVER ? consoleText(Console.ListServerVersion, player.serverVersion()) : ""));
        }
    }

    private OnlinePlayerQuery.Snapshot onlineSnapshot() {
        try {
            return this.onlinePlayers.get();
        } catch (final RuntimeException unavailable) {
            return new OnlinePlayerQuery.Snapshot(List.of(), false);
        }
    }

    // ---- links -----------------------------------------------------------

    private List<String> links() {
        final List<String> lines = out();
        final Map<String, java.util.UUID> links;
        try {
            links = this.linkStore.snapshot().links();
        } catch (final IdentityLinkStore.CorruptIndexException e) {
            lines.add(consoleText(Console.CorruptLinks));
            return lines;
        }
        if (links.isEmpty()) {
            lines.add(consoleText(Console.NoLinks));
            return lines;
        }
        final Map<String, java.util.UUID> sorted = new TreeMap<>();
        links.forEach((xuid, javaUuid) -> sorted.put(xuid, javaUuid));
        final Map<ProfileKey, String> names = this.identityNames(this.visitSnapshot(lines), this.onlineSnapshot().players());
        for (final Map.Entry<String, java.util.UUID> entry : sorted.entrySet()) {
            lines.add(bedrockLabel(entry.getKey(), names) + " → " + javaLabel(entry.getValue(), names));
        }
        return lines;
    }

    // ---- info ------------------------------------------------------------

    private List<String> info(@Nullable final String name) {
        final List<String> lines = out();
        if (name == null || name.isBlank()) {
            return usage();
        }
        final OnlinePlayerQuery.Snapshot online = this.onlineSnapshot();
        final List<Player> sessions = online.players();
        final Snapshot snapshot = this.visitSnapshot(lines);
        final Map<String, UUID> links = this.linkSnapshot();
        final Map<ProfileKey, String> names = this.identityNames(snapshot, sessions);
        final Map<String, InfoEntry> live = new HashMap<>();
        final Map<String, InfoEntry> found = new LinkedHashMap<>();
        for (final Player session : sessions) {
            final InfoEntry entry = onlineEntry(session);
            live.put(entry.key(), entry);
            live.putIfAbsent("wire:" + session.wireUuid(), entry);
            if (name.equalsIgnoreCase(session.name())) found.put(entry.key(), entry);
        }
        if (snapshot != null) {
            for (final NameMatch match : snapshot.matchByName(name)) {
                final InfoEntry history = new InfoEntry(match.name(), match.javaUuid(), match.xuid(), match.wireUuid(), null);
                final InfoEntry entry = live.getOrDefault(history.key(), history);
                found.putIfAbsent(entry.key(), entry);
            }
        }
        for (final InfoEntry entry : found.values()) {
            lines.add(consoleText(Console.PlayerStatus, entry.name(), consoleText(entry.player() != null
                    ? Console.Online : online.available() ? Console.Offline : Console.OnlineUnavailable)));
            if (entry.player() != null) {
                lines.add("  " + consoleText(Console.CurrentLocation, entry.player().location().label(),
                        entry.player().location() == Location.SERVER ? consoleText(Console.InfoServerVersion, entry.player().serverVersion()) : ""));
                if (entry.player().location() == Location.SERVER) {
                    lines.add("  " + consoleText(Console.CurrentServer, entry.player().currentServer()));
                }
                lines.add("  " + consoleText(Console.InfoClientVersion, entry.player().clientVersion()));
            }
            if (entry.javaUuid() != null) lines.add("  " + consoleText(Console.JavaIdentity, entry.javaUuid()));
            else if (entry.xuid() != null) lines.add("  " + consoleText(Console.BedrockIdentity, entry.xuid()));
            else lines.add("  " + consoleText(Console.WireIdentity, entry.wireUuid()));
            if (entry.session() != null) {
                final PlayerData data = entry.session().playerData;
                lines.add("  " + (data != null ? consoleText(Console.BookmarkCount, data.bookmarks.size()) : consoleText(Console.BookmarksNotLoaded)));
            } else if (entry.javaUuid() != null) {
                this.appendBookmarkCount(lines, ProfileKey.javaProfile(entry.javaUuid()));
            } else if (entry.xuid() != null && links != null) {
                final UUID linked = links.get(entry.xuid());
                this.appendBookmarkCount(lines, linked != null ? ProfileKey.javaProfile(linked) : ProfileKey.bedrockProfile(entry.xuid()));
            } else {
                lines.add("  " + consoleText(Console.BookmarksUnavailable));
            }
            this.appendLinkState(lines, entry, links, names);
            // Access membership (task 7): the queried side first, then the bound
            // counterpart with its OWN membership; data gaps say 不可用, never 否.
            this.appendAccessMembership(lines, entry, "  ");
            if (links == null) {
                // A corrupt index cannot answer the binding question at all.
                lines.add("  " + consoleText(Console.BoundAccountUnavailable));
            } else {
                final InfoEntry bound = this.boundEntry(entry, links, names);
                if (bound != null) {
                    lines.add("  " + consoleText(Console.BoundAccount, bound.name() != null ? bound.name() : consoleText(Console.Unknown)));
                    lines.add("    " + consoleText(Console.Platform, consoleText(bound.javaUuid() != null ? Console.Java : Console.Bedrock)));
                    this.appendAccessMembership(lines, bound, "    ");
                } else if (entry.javaUuid() != null || entry.xuid() != null) {
                    lines.add("  " + consoleText(Console.NotBound));
                }
            }
        }
        if (found.isEmpty() && snapshot != null) {
            lines.add(consoleText(Console.IdentityNotFound, name));
        }
        if (!online.available()) lines.add(consoleText(Console.OnlineQueryUnavailable));
        return lines;
    }

    private record InfoEntry(String name, UUID javaUuid, String xuid, UUID wireUuid, Player player) {
        PlayerSession session() { return this.player != null ? this.player.session() : null; }
        String key() {
            return javaUuid != null ? "java:" + javaUuid : xuid != null ? "bedrock:" + xuid : "wire:" + wireUuid;
        }
    }

    private static InfoEntry onlineEntry(final Player session) {
        final ClientIdentity identity = session.identity();
        return new InfoEntry(session.name(),
                identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_JAVA ? identity.verifiedJavaUuid() : null,
                identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_BEDROCK ? identity.xuid() : null,
                session.wireUuid(), session);
    }

    private Snapshot visitSnapshot(final List<String> lines) {
        try {
            final PlayerVisitStore store = this.visitStore.get();
            if (store != null) return store.snapshot();
            lines.add(consoleText(Console.VisitsUnavailable));
        } catch (final CorruptIndexException e) {
            lines.add(consoleText(Console.CorruptVisits));
        }
        return null;
    }

    private Map<String, UUID> linkSnapshot() {
        try {
            return this.linkStore.snapshot().links();
        } catch (final IdentityLinkStore.CorruptIndexException e) {
            return null;
        }
    }

    private Map<ProfileKey, String> identityNames(final Snapshot snapshot, final List<Player> sessions) {
        final Map<ProfileKey, String> names = new HashMap<>();
        if (snapshot != null) {
            for (final PlayerVisitStore.VisitEntry visit : snapshot.visits()) {
                if (visit.kind() == PlayerVisitStore.Kind.JAVA) names.put(ProfileKey.javaProfile(UUID.fromString(visit.javaUuid())), visit.name());
                else if (visit.kind() == PlayerVisitStore.Kind.BEDROCK) names.put(ProfileKey.bedrockProfile(visit.xuid()), visit.name());
            }
            snapshot.knownJavaNames().forEach((uuid, name) -> names.put(ProfileKey.javaProfile(UUID.fromString(uuid)), name));
        }
        for (final Player session : sessions) {
            final InfoEntry entry = onlineEntry(session);
            if (entry.javaUuid() != null) names.put(ProfileKey.javaProfile(entry.javaUuid()), entry.name());
            else if (entry.xuid() != null) names.put(ProfileKey.bedrockProfile(entry.xuid()), entry.name());
        }
        return names;
    }

    private static String javaLabel(final UUID uuid, final Map<ProfileKey, String> names) {
        return displayName(ProfileKey.javaProfile(uuid), names) + "(JavaUUID: " + uuid + ")";
    }

    private static String bedrockLabel(final String xuid, final Map<ProfileKey, String> names) {
        return displayName(ProfileKey.bedrockProfile(xuid), names) + "(XUID: " + xuid + ")";
    }

    private static String displayName(final ProfileKey key, final Map<ProfileKey, String> names) {
        final String name = names.get(key);
        return name != null && !name.isBlank() ? name : consoleText(Console.UnknownPlayerName);
    }

    private void appendBookmarkCount(final List<String> lines, final ProfileKey key) {
        try {
            if (!this.playerStore.exists(key)) {
                lines.add("  " + consoleText(Console.BookmarksNoProfile));
                return;
            }
            final PlayerData data = this.playerStore.load(key);
            lines.add("  " + consoleText(Console.BookmarkCount, data.bookmarks.size()));
        } catch (final Exception e) {
            lines.add("  " + consoleText(Console.BookmarksUnavailable));
        }
    }

    /** The factual membership lines of one identity; unavailable data never reads as 否. */
    private void appendAccessMembership(final List<String> lines, final InfoEntry entry, final String indent) {
        lines.add(indent + consoleText(Console.WhitelistMembership, this.membershipText(dev.connectplus.access.AccessPolicy.Kind.WHITELIST, entry)));
        lines.add(indent + consoleText(Console.BlacklistMembership, this.membershipText(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST, entry)));
    }

    private String membershipText(final dev.connectplus.access.AccessPolicy.Kind kind, final InfoEntry entry) {
        final dev.connectplus.access.AccessKey key;
        if (entry.javaUuid() != null) {
            key = dev.connectplus.access.AccessKey.javaUuid(entry.javaUuid());
        } else if (entry.xuid() != null) {
            key = dev.connectplus.access.AccessKey.bedrockXuid(entry.xuid());
        } else {
            return consoleText(Console.Unavailable); // no confirmed identifier: no membership can be derived
        }
        final AccessListStore.Snapshot snapshot = kind == dev.connectplus.access.AccessPolicy.Kind.WHITELIST
                ? this.accessService.state().whitelist() : this.accessService.state().blacklist();
        if (!snapshot.available()) {
            return consoleText(Console.Unavailable); // list data is broken: never a misleading 否
        }
        return consoleText(snapshot.entries().containsKey(key) ? Console.Yes : Console.No);
    }

    /** The CURRENT bound counterpart as an info entry, or null when unbound. */
    @Nullable
    private InfoEntry boundEntry(final InfoEntry entry, final Map<String, UUID> links,
                                 final Map<ProfileKey, String> names) {
        if (links == null) {
            return null;
        }
        if (entry.javaUuid() != null) {
            final String xuid = links.entrySet().stream()
                    .filter(link -> link.getValue().equals(entry.javaUuid()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            return xuid == null ? null : new InfoEntry(names.get(ProfileKey.bedrockProfile(xuid)),
                    null, xuid, null, null);
        }
        if (entry.xuid() != null) {
            final UUID javaUuid = links.get(entry.xuid());
            return javaUuid == null ? null : new InfoEntry(names.get(ProfileKey.javaProfile(javaUuid)),
                    javaUuid, null, null, null);
        }
        return null;
    }

    private void appendLinkState(final List<String> lines, final InfoEntry entry,
                                 final Map<String, UUID> links, final Map<ProfileKey, String> names) {
        if (entry.javaUuid() == null && entry.xuid() == null) {
            lines.add("  " + consoleText(Console.LinkUnverified));
            return;
        }
        if (links == null) {
            lines.add("  " + consoleText(Console.LinkUnavailable));
            return;
        }
        if (entry.javaUuid() != null) {
            final String linked = links.entrySet().stream()
                    .filter(link -> link.getValue().equals(entry.javaUuid()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            lines.add("  " + (linked != null ? consoleText(Console.LinkedFrom, bedrockLabel(linked, names)) : consoleText(Console.NotLinked)));
        } else {
            final UUID linked = links.get(entry.xuid());
            lines.add("  " + (linked != null ? consoleText(Console.LinkedTo, javaLabel(linked, names)) : consoleText(Console.NotLinked)));
        }
    }

    // ---- version ---------------------------------------------------------

    private List<String> version() {
        final List<String> lines = out();
        lines.add(consoleText(Console.PluginVersion, this.runtime.pluginVersion() != null ? this.runtime.pluginVersion() : consoleText(Console.Unavailable)));
        lines.add(consoleText(Console.HostVersion, this.runtime.hostVersion() != null ? this.runtime.hostVersion() : consoleText(Console.Unavailable)));
        lines.add(consoleText(Console.BridgeProtocolVersion, BedrockBridgeEndpoint.PROTOCOL_VERSION));
        final BedrockBridgeEndpoint.RegistrationSnapshot bridge = this.bridgeSnapshot();
        if (bridge != null) {
            lines.add(consoleText(Console.BridgeVersion, bridge.bridgeVersion(), bridge.geyserVersion()));
        } else {
            lines.add(consoleText(Console.VersionBridgeUnregistered));
        }
        return lines;
    }

    // ---- debug -----------------------------------------------------------

    private List<String> debug(final boolean enabled) {
        DebugLog.setRuntimeEnabled(enabled);
        final List<String> lines = out();
        if (enabled && !DebugLog.enabled()) {
            lines.add(consoleText(Console.DebugBackendDisabled));
        } else if (enabled) {
            DebugLog.log("ConnectPlus diagnostic logging enabled for this run");
            lines.add(consoleText(Console.DebugEnabled));
        } else {
            lines.add(consoleText(Console.DebugDisabled));
        }
        return lines;
    }

    // ---- accounts --------------------------------------------------------

    private List<String> accounts() {
        final List<String> lines = out();
        final Snapshot snapshot;
        try {
            final PlayerVisitStore visits = this.visitStore.get();
            if (visits == null) {
                lines.add(consoleText(Console.VisitorStatsUnavailable));
                return lines;
            }
            snapshot = visits.snapshot();
        } catch (final CorruptIndexException e) {
            lines.add(consoleText(Console.VisitorStatsCorruptVisits));
            return lines;
        }
        final Map<String, java.util.UUID> links;
        try {
            links = this.linkStore.snapshot().links();
        } catch (final IdentityLinkStore.CorruptIndexException e) {
            lines.add(consoleText(Console.VisitorStatsCorruptLinks));
            return lines;
        }
        final int java = (int) snapshot.visits().stream().filter(v -> v.kind() == PlayerVisitStore.Kind.JAVA).count();
        final int bedrock = (int) snapshot.visits().stream().filter(v -> v.kind() == PlayerVisitStore.Kind.BEDROCK).count();
        final int temporary = (int) snapshot.visits().stream().filter(v -> v.kind() == PlayerVisitStore.Kind.TEMPORARY_WIRE).count();
        final int merged = (int) snapshot.visits().stream()
                .filter(v -> v.kind() == PlayerVisitStore.Kind.BEDROCK && links.containsKey(v.xuid())).count();
        final int total = snapshot.uniqueVisitorCount(links);
        lines.add(consoleText(Console.VisitorTotal, total, java, bedrock, merged, temporary));
        lines.add(consoleText(Console.VisitorScope));
        lines.add(consoleText(Console.VisitorRepeatNotice));
        return lines;
    }

    // ---- help / usage ----------------------------------------------------

    private static List<String> help() {
        final List<String> lines = new ArrayList<>();
        lines.add(consoleText(Console.HelpHeader));
        lines.add(consoleText(Console.HelpStatus));
        lines.add(consoleText(Console.HelpList));
        lines.add(consoleText(Console.HelpLinks));
        lines.add(consoleText(Console.HelpInfo));
        lines.add(consoleText(Console.HelpVersion));
        lines.add(consoleText(Console.HelpDebug));
        lines.add(consoleText(Console.HelpAccounts));
        lines.addAll(AccessConsoleService.helpSummary());
        lines.add(consoleText(Console.HelpHelp));
        return lines;
    }

    private static List<String> usage() {
        final List<String> lines = new ArrayList<>();
        lines.add(consoleText(Console.Usage));
        lines.addAll(help());
        return lines;
    }

}
