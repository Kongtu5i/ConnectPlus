package dev.connectplus.commands;

import dev.connectplus.access.AccessListStore;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.logging.DebugLog;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.PlayerVisitStore;
import dev.connectplus.session.PlayerVisitStore.CorruptIndexException;
import dev.connectplus.session.PlayerVisitStore.NameMatch;
import dev.connectplus.session.PlayerVisitStore.Snapshot;
import dev.connectplus.session.PlayerVisitStore.VisitEntry;
import dev.connectplus.session.SessionRegistry;

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
import java.util.stream.Collectors;

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

    public ConsoleCommandService(final Supplier<LobbyServer> lobbyServer,
                                 final Supplier<BedrockBridgeEndpoint> bridgeEndpoint,
                                 final Runtime runtime,
                                 final IntSupplier inFlightTargetCount,
                                 final PlayerStore playerStore,
                                 final IdentityLinkStore linkStore,
                                 final Supplier<PlayerVisitStore> visitStore,
                                 final AccessConsoleService accessConsole,
                                 final dev.connectplus.access.AccessService accessService,
                                 final ExecutorService executor) {
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
                result.complete(List.of("命令执行失败: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage())));
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
        lines.add("ConnectPlus 状态:");
        lines.add("配置模式: " + CPConfig.mode);
        lines.add("宿主监听地址: " + (this.runtime.hostListenAddress().get() != null ? this.runtime.hostListenAddress().get() : "不可用"));
        lines.add("大厅监听地址: " + (lobby != null ? String.valueOf(lobby.localAddress()) : "未启用"));
        lines.add("大厅会话数: " + (lobby != null ? lobby.sessionRegistry().snapshot().size() : "未启用"));
        lines.add("目标服务器在途连接: " + (lobby != null ? this.inFlightTargetCount.getAsInt() : "未启用"));
        lines.add("本次启动大厅非预期异常数: " + (lobby != null ? lobby.uncaughtExceptionCount() : "未启用"));
        final BedrockBridgeEndpoint.RegistrationSnapshot bridge = this.bridgeSnapshot();
        lines.add("Geyser 桥: " + (bridge != null
                ? "已注册 (桥版本 " + bridge.bridgeVersion() + ", Geyser 版本 " + bridge.geyserVersion() + ")"
                : "未注册"));
        this.appendAccessStatus(lines, "白名单", dev.connectplus.access.AccessPolicy.Kind.WHITELIST);
        this.appendAccessStatus(lines, "黑名单", dev.connectplus.access.AccessPolicy.Kind.BLACKLIST);
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
        lines.add(label + "状态:");
        lines.add("    配置文件: " + (configured ? "on" : "off") + ",");
        lines.add("    当前状态: " + (runtime ? "on" : "off") + ",");
        lines.add("    " + label + "账号数: " + (snapshot.available() ? snapshot.entries().size() : "不可用"));
    }

    @Nullable
    private BedrockBridgeEndpoint.RegistrationSnapshot bridgeSnapshot() {
        final BedrockBridgeEndpoint endpoint = this.bridgeEndpoint.get();
        return endpoint == null ? null : endpoint.registrationSnapshot();
    }

    // ---- list ------------------------------------------------------------

    private List<String> list() {
        final LobbyServer lobby = this.lobbyServer.get();
        final List<String> lines = out();
        if (lobby == null) {
            lines.add("大厅未启用");
            return lines;
        }
        final List<PlayerSession> sessions = lobby.sessionRegistry().snapshot().stream()
                .sorted(Comparator.comparing(session -> session.name.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        if (sessions.isEmpty()) {
            lines.add("大厅当前没有连接的会话");
            return lines;
        }
        for (final PlayerSession session : sessions) {
            final String backend = session.offlineMode() ? "离线" : "在线";
            final String account = session.loginInProgress ? "登录中" : (session.account != null ? "已登录" : "未登录");
            lines.add(session.name + " - 后端: " + backend + " | 微软账号: " + account);
        }
        return lines;
    }

    // ---- links -----------------------------------------------------------

    private List<String> links() {
        final List<String> lines = out();
        final Map<String, java.util.UUID> links;
        try {
            links = this.linkStore.snapshot().links();
        } catch (final IdentityLinkStore.CorruptIndexException e) {
            lines.add("关联索引损坏,已提交关联数据不可用 (需管理员修复 identity-links.json)");
            return lines;
        }
        if (links.isEmpty()) {
            lines.add("暂无已提交关联");
            return lines;
        }
        final Map<String, java.util.UUID> sorted = new TreeMap<>();
        links.forEach((xuid, javaUuid) -> sorted.put(xuid, javaUuid));
        final Map<ProfileKey, String> names = this.identityNames(this.visitSnapshot(lines), this.sessions());
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
        final List<PlayerSession> sessions = this.sessions();
        final Snapshot snapshot = this.visitSnapshot(lines);
        final Map<String, UUID> links = this.linkSnapshot();
        final Map<ProfileKey, String> names = this.identityNames(snapshot, sessions);
        final Map<String, InfoEntry> live = new HashMap<>();
        final Map<String, InfoEntry> found = new LinkedHashMap<>();
        for (final PlayerSession session : sessions) {
            final InfoEntry entry = onlineEntry(session);
            live.put(entry.key(), entry);
            live.putIfAbsent("wire:" + session.uuid, entry);
            if (name.equalsIgnoreCase(session.name)) found.put(entry.key(), entry);
        }
        if (snapshot != null) {
            for (final NameMatch match : snapshot.matchByName(name)) {
                final InfoEntry history = new InfoEntry(match.name(), match.javaUuid(), match.xuid(), match.wireUuid(), null);
                final InfoEntry entry = live.getOrDefault(history.key(), history);
                found.putIfAbsent(entry.key(), entry);
            }
        }
        for (final InfoEntry entry : found.values()) {
            lines.add(entry.name() + (entry.session() != null ? " [在线]" : " [离线]"));
            if (entry.javaUuid() != null) lines.add("  JavaUUID: " + entry.javaUuid());
            else if (entry.xuid() != null) lines.add("  XUID: " + entry.xuid());
            else lines.add("  协议UUID: " + entry.wireUuid() + " (身份未确认)");
            if (entry.session() != null) {
                final PlayerData data = entry.session().playerData;
                lines.add(data != null ? "  书签数: " + data.bookmarks.size() : "  书签数: 不可用 (档案未加载)");
            } else if (entry.javaUuid() != null) {
                this.appendBookmarkCount(lines, ProfileKey.javaProfile(entry.javaUuid()));
            } else if (entry.xuid() != null && links != null) {
                final UUID linked = links.get(entry.xuid());
                this.appendBookmarkCount(lines, linked != null ? ProfileKey.javaProfile(linked) : ProfileKey.bedrockProfile(entry.xuid()));
            } else {
                lines.add("  书签数: 不可用");
            }
            this.appendLinkState(lines, entry, links, names);
            // Access membership (task 7): the queried side first, then the bound
            // counterpart with its OWN membership; data gaps say 不可用, never 否.
            this.appendAccessMembership(lines, entry, "  ");
            if (links == null) {
                // A corrupt index cannot answer the binding question at all.
                lines.add("  绑定账号：不可用 (关联索引损坏)");
            } else {
                final InfoEntry bound = this.boundEntry(entry, links, names);
                if (bound != null) {
                    lines.add("  已绑定账号：" + (bound.name() != null ? bound.name() : "未知"));
                    lines.add("    平台：" + (bound.javaUuid() != null ? "Java" : "基岩"));
                    this.appendAccessMembership(lines, bound, "    ");
                } else if (entry.javaUuid() != null || entry.xuid() != null) {
                    lines.add("  绑定账号：未绑定");
                }
            }
        }
        if (found.isEmpty() && snapshot != null) {
            lines.add("未找到该名字对应的身份: " + name);
        }
        return lines;
    }

    private record InfoEntry(String name, UUID javaUuid, String xuid, UUID wireUuid, PlayerSession session) {
        String key() {
            return javaUuid != null ? "java:" + javaUuid : xuid != null ? "bedrock:" + xuid : "wire:" + wireUuid;
        }
    }

    private static InfoEntry onlineEntry(final PlayerSession session) {
        final ClientIdentity identity = LobbyLink.sessionIdentity(session);
        return new InfoEntry(session.name,
                identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_JAVA ? identity.verifiedJavaUuid() : null,
                identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_BEDROCK ? identity.xuid() : null,
                session.uuid, session);
    }

    private List<PlayerSession> sessions() {
        final LobbyServer lobby = this.lobbyServer.get();
        return lobby != null ? lobby.sessionRegistry().snapshot() : List.of();
    }

    private Snapshot visitSnapshot(final List<String> lines) {
        try {
            final PlayerVisitStore store = this.visitStore.get();
            if (store != null) return store.snapshot();
            lines.add("访问索引不可用,离线名字查询不可用");
        } catch (final CorruptIndexException e) {
            lines.add("访问索引损坏,离线名字查询不可用");
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

    private Map<ProfileKey, String> identityNames(final Snapshot snapshot, final List<PlayerSession> sessions) {
        final Map<ProfileKey, String> names = new HashMap<>();
        if (snapshot != null) {
            for (final PlayerVisitStore.VisitEntry visit : snapshot.visits()) {
                if (visit.kind() == PlayerVisitStore.Kind.JAVA) names.put(ProfileKey.javaProfile(UUID.fromString(visit.javaUuid())), visit.name());
                else if (visit.kind() == PlayerVisitStore.Kind.BEDROCK) names.put(ProfileKey.bedrockProfile(visit.xuid()), visit.name());
            }
            snapshot.knownJavaNames().forEach((uuid, name) -> names.put(ProfileKey.javaProfile(UUID.fromString(uuid)), name));
        }
        for (final PlayerSession session : sessions) {
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
        return name != null && !name.isBlank() ? name : "未知玩家名";
    }

    private void appendBookmarkCount(final List<String> lines, final ProfileKey key) {
        try {
            if (!this.playerStore.exists(key)) {
                lines.add("  书签数: 不可用 (没有档案)");
                return;
            }
            final PlayerData data = this.playerStore.load(key);
            lines.add("  书签数: " + data.bookmarks.size());
        } catch (final Exception e) {
            lines.add("  书签数: 不可用");
        }
    }

    /** The factual membership lines of one identity; unavailable data never reads as 否. */
    private void appendAccessMembership(final List<String> lines, final InfoEntry entry, final String indent) {
        lines.add(indent + "是否在白名单：" + this.membershipText(dev.connectplus.access.AccessPolicy.Kind.WHITELIST, entry));
        lines.add(indent + "是否在黑名单：" + this.membershipText(dev.connectplus.access.AccessPolicy.Kind.BLACKLIST, entry));
    }

    private String membershipText(final dev.connectplus.access.AccessPolicy.Kind kind, final InfoEntry entry) {
        final dev.connectplus.access.AccessKey key;
        if (entry.javaUuid() != null) {
            key = dev.connectplus.access.AccessKey.javaUuid(entry.javaUuid());
        } else if (entry.xuid() != null) {
            key = dev.connectplus.access.AccessKey.bedrockXuid(entry.xuid());
        } else {
            return "不可用"; // no confirmed identifier: no membership can be derived
        }
        final AccessListStore.Snapshot snapshot = kind == dev.connectplus.access.AccessPolicy.Kind.WHITELIST
                ? this.accessService.state().whitelist() : this.accessService.state().blacklist();
        if (!snapshot.available()) {
            return "不可用"; // list data is broken: never a misleading 否
        }
        return snapshot.entries().containsKey(key) ? "是" : "否";
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
            lines.add("  关联: 不可用 (身份未确认)");
            return;
        }
        if (links == null) {
            lines.add("  关联: 不可用 (关联索引损坏)");
            return;
        }
        if (entry.javaUuid() != null) {
            final String linked = links.entrySet().stream()
                    .filter(link -> link.getValue().equals(entry.javaUuid()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            lines.add(linked != null ? "  关联: 已关联 ← " + bedrockLabel(linked, names) : "  关联: 未关联");
        } else {
            final UUID linked = links.get(entry.xuid());
            lines.add(linked != null ? "  关联: 已关联 → " + javaLabel(linked, names) : "  关联: 未关联");
        }
    }

    // ---- version ---------------------------------------------------------

    private List<String> version() {
        final List<String> lines = out();
        lines.add("ConnectPlus 插件版本: " + (this.runtime.pluginVersion() != null ? this.runtime.pluginVersion() : "不可用"));
        lines.add("ViaProxy 运行版本: " + (this.runtime.hostVersion() != null ? this.runtime.hostVersion() : "不可用")
                + " (支持 3.4.x,最低 3.4.13)");
        lines.add("桥协议版本: " + BedrockBridgeEndpoint.PROTOCOL_VERSION);
        final BedrockBridgeEndpoint.RegistrationSnapshot bridge = this.bridgeSnapshot();
        if (bridge != null) {
            lines.add("Geyser 桥: 桥版本 " + bridge.bridgeVersion() + ", Geyser 版本 " + bridge.geyserVersion()
                    + " (支持 2.11.x,最低 2.11.3)");
        } else {
            lines.add("Geyser 桥: 未注册");
        }
        return lines;
    }

    // ---- debug -----------------------------------------------------------

    private List<String> debug(final boolean enabled) {
        DebugLog.setRuntimeEnabled(enabled);
        final List<String> lines = out();
        if (enabled && !DebugLog.enabled()) {
            lines.add("诊断开关已开启,但当前日志后端未允许 DEBUG 输出;请检查宿主日志设置 (仅本次运行有效,不写入 config.yml)");
        } else if (enabled) {
            DebugLog.log("ConnectPlus diagnostic logging enabled for this run");
            lines.add("诊断日志已开启 (仅本次运行有效,不写入 config.yml;官方默认仅写 logs/debug.log,控制台不显示 DEBUG)");
        } else {
            lines.add("诊断日志已关闭 (仅本次运行有效,不写入 config.yml)");
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
                lines.add("访问统计不可用 (没有访问索引)");
                return lines;
            }
            snapshot = visits.snapshot();
        } catch (final CorruptIndexException e) {
            lines.add("访问索引损坏,访问统计不可用 (需管理员修复 visits-index.json)");
            return lines;
        }
        final Map<String, java.util.UUID> links;
        try {
            links = this.linkStore.snapshot().links();
        } catch (final IdentityLinkStore.CorruptIndexException e) {
            lines.add("关联索引损坏,访问统计不可用 (需管理员修复 identity-links.json)");
            return lines;
        }
        final int java = (int) snapshot.visits().stream().filter(v -> v.kind() == PlayerVisitStore.Kind.JAVA).count();
        final int bedrock = (int) snapshot.visits().stream().filter(v -> v.kind() == PlayerVisitStore.Kind.BEDROCK).count();
        final int temporary = (int) snapshot.visits().stream().filter(v -> v.kind() == PlayerVisitStore.Kind.TEMPORARY_WIRE).count();
        final int merged = (int) snapshot.visits().stream()
                .filter(v -> v.kind() == PlayerVisitStore.Kind.BEDROCK && links.containsKey(v.xuid())).count();
        final int total = snapshot.uniqueVisitorCount(links);
        lines.add("实际进入大厅的访问人数 总数 " + total
                + " (Java 原始 " + java + ",基岩原始 " + bedrock + ",绑定合并 " + merged + ",未确认 " + temporary + ")");
        lines.add("说明: 只统计实际进入过大厅的身份;同一玩家的 Java/基岩身份按当前已提交关联去重,解绑后恢复分别计数;");
        lines.add("登录微软账号本身不增加人数;重复进出与切服返回不重复计数。");
        return lines;
    }

    // ---- help / usage ----------------------------------------------------

    private static List<String> help() {
        final List<String> lines = new ArrayList<>();
        lines.add("ConnectPlus 控制台命令 (cp 或 connectplus,/cp、/connectplus 等价):");
        lines.add("cp status - 查看运行状态(模式、监听地址、会话数、在途数、异常数、桥状态)");
        lines.add("cp list - 当前在大厅的会话(名字、后端在线/离线、微软账号状态)");
        lines.add("cp links - 已提交关联(XUID → Java UUID)");
        lines.add("cp info <玩家名> - 玩家详情(支持离线与已离线玩家、大小写不敏感、基岩名字可含空格)");
        lines.add("cp version - 版本信息(插件、宿主、桥)");
        lines.add("cp debug on|off - 诊断日志开关(仅本次运行有效,不写回 config.yml)");
        lines.add("cp accounts - 访问人数统计(按当前关联去重)");
        lines.addAll(AccessConsoleService.helpSummary());
        lines.add("cp help - 显示本帮助");
        return lines;
    }

    private static List<String> usage() {
        final List<String> lines = new ArrayList<>();
        lines.add("用法错误。可用命令:");
        lines.addAll(help());
        return lines;
    }

}
