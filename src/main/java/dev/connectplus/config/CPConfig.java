package dev.connectplus.config;

import net.lenni0451.optconfig.annotations.Description;
import net.lenni0451.optconfig.annotations.OptConfig;
import net.lenni0451.optconfig.annotations.Option;
import net.lenni0451.optconfig.annotations.Section;

import java.util.ArrayList;
import java.util.List;

@OptConfig
public class CPConfig {

    @Option("mode")
    @Description("Plugin mode: lobby (route players into the built-in lobby, default) or proxy (lobby disabled, plain ViaProxy behaviour)")
    public static String mode = "lobby";

    @Option("motd")
    @Description("Message shown in the multiplayer server list for the lobby; supports section-sign colour codes and YAML double-quoted \\n for a second line (restart to apply)")
    public static String motd = "§bConnectPlus Lobby";

    @Option("language")
    @Description("Lobby GUI and chat language: en (default) or zh (built-in), auto (each client's locale picks among the available languages), or any language file in plugins/ConnectPlus/languages/ (e.g. fr for languages/fr.yml - missing lines fall back to English; en.yml/zh.yml are created there on first start and can be edited)")
    public static String language = "en";

    @Option("switchTimeoutSeconds")
    @Description("Maximum seconds a seamless server switch may take before the player is switched back to the lobby")
    public static int switchTimeoutSeconds = 15;

    @Option("maxConnectAttemptsPerMinute")
    @Description("Rate limit: maximum connect attempts per player per minute")
    public static int maxConnectAttemptsPerMinute = 30;

    @Option("blockLocalTargets")
    @Description("Whether players are blocked from connecting (via the GUI address input) to this proxy host itself and to loopback/private networks (default: yes, keep it on). SECURITY WARNING: setting this to false lets ANY player reach every service listening on the proxy machine and its internal networks (other Minecraft servers, admin panels, databases) simply by targeting their address - only disable it if you fully understand and accept that risk.")
    public static boolean blockLocalTargets = true;

    @Option("maxBookmarksPerPlayer")
    @Description("Maximum number of saved bookmarks per player")
    public static int maxBookmarksPerPlayer = 50;

    @Option("allowAccountLogin")
    @Description("Whether authenticated players may login with and use Microsoft accounts. Java requires proxy-online-mode=true and completed authentication; Bedrock requires geyser-support.enabled and a live verified bridge session. Forced to false on proxy load/start when proxy-online-mode=false and Geyser support is disabled. Enabling either option never re-enables this admin switch.")
    public static boolean allowAccountLogin = true;

    @Option("accountLoginAllowlist")
    @Description("Optional allowlist of player names that may use the Microsoft account login (empty = everyone)")
    public static List<String> accountLoginAllowlist = new ArrayList<>();

    @Option("transferPolicy")
    @Description("How to react when a target server sends a transfer packet: confirm (back to lobby for confirmation), follow or ignore")
    public static String transferPolicy = "confirm";

    @Option("backendDownPolicy")
    @Description("What to do when the backend server dies while playing: lobby (back to the lobby, default) or reconnect (auto reconnect chain)")
    public static String backendDownPolicy = "lobby";

    @Option("reconnectAttempts")
    @Description("Number of automatic reconnect attempts before falling back to the lobby (backendDownPolicy=reconnect); 0 disables retrying")
    public static int reconnectAttempts = 3;

    @Option("reconnectDelaySeconds")
    @Description("Base delay in seconds between reconnect attempts (linear backoff: delay x attempt index)")
    public static int reconnectDelaySeconds = 5;

    @Option("kickPolicy")
    @Description("How to react when the target server kicks the player: lobby (back to the lobby with the reason, default) or disconnect (forward the kick)")
    public static String kickPolicy = "lobby";

    @Option("whitelist")
    @Description("Whether the whitelist is enabled at startup (console cp whitelist on/off changes the runtime state only; a restart restores this value; entries live in plugins/ConnectPlus/whitelist.json)")
    public static boolean whitelist = false;

    @Option("blacklist")
    @Description("Whether the blacklist is enabled at startup (console cp blacklist on/off changes the runtime state only; a restart restores this value; entries live in plugins/ConnectPlus/blacklist.json)")
    public static boolean blacklist = false;

    @Option("debug")
    @Description("Enable ConnectPlus DEBUG diagnostics (packet dumps, switch handshake and GUI state). Default: false. With official ViaProxy logging these details go to logs/debug.log; normal INFO/WARN/ERROR messages remain available. Restart to apply.")
    public static volatile boolean debug = false;

    @Option("geyser-support")
    public static GeyserSupport geyserSupport = new GeyserSupport();

    /** Bedrock bridge support (external Geyser extension, protocol v1, opt-in). */
    @Section
    public static class GeyserSupport {

        @Option("enabled")
        @Description("Whether the ConnectPlus-GeyserBridge extension may register as the trusted Bedrock identity provider (protocol v1). Requires the matching Geyser extension to be installed; enabling this does not change allowAccountLogin.")
        public static boolean enabled = false;
    }
}
