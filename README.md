# ConnectPlus

[English](README.md) | [简体中文](README.zh-CN.md)

This project is inspired by [MiniConnect](https://github.com/ViaVersionAddons/MiniConnect) and incorporates some of its code.

**One entry point. Bookmark your favorite servers and switch between them in game.**

ConnectPlus is a [ViaProxy](https://github.com/ViaVersion/ViaProxy) plugin. Its built-in lobby lets players enter a Minecraft Java server address, choose a version, manage personal bookmarks, sign in with a Microsoft account, and switch between servers and the lobby without repeatedly returning to the multiplayer server list.

Multiple players can share one proxy while choosing their own destination servers. With official Geyser-ViaProxy and the separate ConnectPlus-GeyserBridge extension installed, Bedrock players can also use the lobby and link their Bedrock account to their Java profile.

This guide is for players and proxy administrators and describes **ConnectPlus 1.0.0 (pre-release)**. The ports shown are deployment examples; use the addresses and ports configured by your administrator.

**Players who already have a proxy address** can jump to the [player guide](#player-guide): connect to the address provided by your administrator → set a destination address in the menu → sign in with Microsoft if needed → connect. To change servers, use `/dc` to return to the lobby, then choose another server.

## Contents

- [Features and use cases](#features-and-use-cases)
- [Versions and requirements](#versions-and-requirements)
- [Installation and first start](#installation-and-first-start)
- [Bedrock setup](#bedrock-setup)
- [Player guide](#player-guide)
- [Microsoft accounts and saved sign-in](#microsoft-accounts-and-saved-sign-in)
- [Linking Java and Bedrock accounts](#linking-java-and-bedrock-accounts)
- [Commands](#commands)
- [Administrator configuration](#administrator-configuration)
- [Backups and upgrades](#backups-and-upgrades)
- [FAQ and troubleshooting](#faq-and-troubleshooting)
- [Scope and acceptance testing](#scope-and-acceptance-testing)
- [Building an installation JAR](#building-an-installation-jar)
- [License and acknowledgments](#license-and-acknowledgments)

## Features and use cases

| Feature | What you can do |
| --- | --- |
| Built-in lobby | Open a menu when you join the proxy and configure connections in game, without running a separate lobby server |
| Shared proxy | Each player chooses a destination and saves personal bookmarks and preferences; players on the same network have their data managed by their individual identities |
| Connections across versions | Use ViaProxy's protocol translation to join Java servers running different versions; detect the destination version automatically or select it manually |
| In-game server switching | Keep the client connected to the proxy while joining a destination, returning to the lobby, and choosing another server |
| Personal bookmarks | Save server addresses and versions, view details, rename entries, change addresses or versions, and delete with confirmation |
| Microsoft sign-in | Authorize through a browser device-code flow to authenticate with servers that require a licensed Java account |
| Saved sign-in | Decide whether to store your sign-in on the proxy; with saving disabled, it lasts only for your current proxy connection |
| Personal offline mode | Keep your Microsoft sign-in in the lobby while choosing an offline identity for destination connections |
| Connection recovery | Return to the lobby when a destination unexpectedly disconnects, or reconnect according to administrator settings; return with the reason when kicked |
| Server transfer handling | Confirm, automatically follow, or ignore a destination server's request to transfer you to another address |
| English and Chinese interface | Use either built-in language, select a language from each client's locale, or customize the text |
| Bedrock identity and linking | With the bridge installed, save an independent profile by Xbox identity or link to a Java profile to share bookmarks and preferences |
| Console management | Inspect lobby sessions, account links, player information, visitor totals, versions, and bridge status, and temporarily enable diagnostic logs |

ConnectPlus is suited to a shared connection entry point for yourself, friends, or multiple players using different versions. Its lobby manages server selection and connections; destination servers still manage game worlds, inventories, permissions, whitelists, and game progress.

## Versions and requirements

| Component | Current requirement |
| --- | --- |
| ConnectPlus | Current version: `1.0.0` (pre-release) |
| Java runtime | ConnectPlus with only a Java entry point requires at least Java 17; the Bedrock bridge requires Java 21 or later. Java 21 can be used for a combined deployment, while also meeting the hosts' own requirements |
| ViaProxy | Use an unmodified official build. The plugin requires at least `3.4.13`; the Bedrock bridge supports the `3.4.x` series from `3.4.13` onward |
| Geyser-ViaProxy, for Bedrock access | Use an unmodified official `2.11.x` build, at least `2.11.3` |
| ConnectPlus-GeyserBridge, for Bedrock identity features | A separate extension matching the ConnectPlus bridge protocol; current version: `1.0.1` |
| Java client | The current switching logic covers Java release versions `1.7.2` through `26.3`; the installed ViaProxy must also support the client |
| Bedrock client | A Bedrock version supported by the installed Geyser build, signed in to an Xbox account |
| Network | The proxy must be able to reach destination servers; account sign-in also requires access to Microsoft, Xbox, and Minecraft authentication services |

ConnectPlus, Geyser-ViaProxy, and the bridge extension run in **the same ViaProxy instance**. The bridge currently supports the Geyser-ViaProxy deployment type.

Compatibility with newer hosts or clients depends on both host support and plugin adaptation. If a client protocol has not yet been supported, ConnectPlus refuses to start the switch and displays an update notice.

## Installation and first start

### 1. Prepare the runtime directory

Download an official JAR from [ViaProxy releases](https://github.com/ViaVersion/ViaProxy/releases) and put it in a dedicated folder. The commands below assume you have renamed the host JAR to `viaproxy.jar`.

Rename the ConnectPlus installation JAR to `ConnectPlus.jar` and put it in `plugins/`. The examples below use filenames without version suffixes:

```text
ViaProxy/
├── viaproxy.jar
└── plugins/
    └── ConnectPlus.jar
```

Run all commands from this `ViaProxy/` folder. Configuration paths in this guide are relative to it. Do not install ConnectPlus in a destination Bukkit/Paper server's plugin directory or in a client's `mods/` folder.

### 2. Generate configuration

First check the Java version used by your terminal:

```shell
java -version
```

Generate the host configuration on the first start:

```shell
java -jar viaproxy.jar config viaproxy.yml
```

This is ViaProxy's official configuration startup mode. When the configuration file is first created, the host asks you to edit it and exits. Loading ConnectPlus creates `plugins/ConnectPlus/config.yml` and the default language files. See the [official ViaProxy guide](https://github.com/ViaVersion/ViaProxy#usage-for-server-owners-config) for host startup instructions.

If the process is still running, enter `stop` in the proxy console and wait for it to exit before editing configuration.

### 3. Configure the Java entry point

Edit the existing matching fields in **`viaproxy.yml`** in the runtime directory:

```yaml
bind-address: 0.0.0.0:25565

# Ordinary connections are routed to the lobby; these are required host defaults.
# Replace the target with a real server if you later use ordinary proxy mode.
target-address: 127.0.0.1:1
target-version: 1.21.4

# Java players must authenticate at the entry point to use Microsoft account features.
proxy-online-mode: true

# Players sign in to their own accounts through the ConnectPlus lobby.
auth-method: NONE

# Ordinary addresses enter the lobby; configure wildcard connections separately if needed.
wildcard-domain-handling: NONE
```

Edit the matching fields in **`plugins/ConnectPlus/config.yml`**:

```yaml
mode: lobby
language: en
motd: "§bConnectPlus Lobby\n§7Choose a server and start playing"
allowAccountLogin: true
blockLocalTargets: true

geyser-support:
  enabled: false
```

These snippets show only the fields that need attention. **Update their existing values in the generated configuration, preserve other settings, and avoid adding duplicate keys.**

To let Java clients enter without licensed-account authentication, you can set the host's `proxy-online-mode` to `false`. Those Java connections cannot use ConnectPlus's Microsoft account features. See [Microsoft accounts and saved sign-in](#microsoft-accounts-and-saved-sign-in) for the distinction between entry-point and destination authentication.

### 4. Start and verify

```shell
java -jar viaproxy.jar cli
```

Always start from the same runtime directory. You can also run `java -jar viaproxy.jar` to open ViaProxy's graphical interface and start the proxy there; make sure its online-mode and listening settings match the configuration above.

After startup, run these commands in the proxy console:

```text
cp version
cp status
```

Check that ConnectPlus is loaded, the mode is `lobby`, and the **host's actual listen address** is correct. The `Lobby server listening on 127.0.0.1:...` log line shows an internal lobby address allocated by the plugin. Players should connect to ViaProxy's public entry point.

### 5. Connect players

With this example configuration, Java players add the following to their multiplayer server list:

```text
proxy-host-address:25565
```

For a test on the same machine, use `127.0.0.1:25565`. LAN players use the proxy machine's LAN address; Internet players use its domain or public address.

For public access, allow or forward **TCP 25565** through firewalls, cloud security groups, and your router or tunnel. `0.0.0.0` is a listen setting, not an address players should enter. Adjust the port everywhere if you choose a different one.

The lobby's main menu should open after joining. This completes installation for Java-only access. Continue below if you also need Bedrock access.

## Bedrock setup

Bridge extension repository: [ConnectPlus-GeyserBridge](https://github.com/Kongtu5i/ConnectPlus-GeryserBridge). See that project for information about obtaining the extension, installation, and supported versions.

### 1. Install Geyser and the bridge extension

Download a supported **Geyser-ViaProxy** build from the [official Geyser download page](https://geysermc.org/download/) and put it in ViaProxy's `plugins/` directory. Start once to generate configuration, then stop the proxy.

Rename the bridge extension JAR to `connectplus-geyserbridge.jar` and put it in **`plugins/Geyser/extensions/`**, creating the directory if needed. The Geyser plugin and Geyser extensions use different directories; see the [official Geyser extension instructions](https://geysermc.org/wiki/geyser/extensions/#installing-extensions).

```text
ViaProxy/
├── viaproxy.jar
└── plugins/
    ├── ConnectPlus.jar
    ├── Geyser-ViaProxy.jar
    ├── ConnectPlus/
    │   └── config.yml
    └── Geyser/
        ├── config.yml
        └── extensions/
            └── connectplus-geyserbridge.jar
```

### 2. Enable ConnectPlus Bedrock support

Update the matching fields in `plugins/ConnectPlus/config.yml`:

```yaml
mode: lobby
allowAccountLogin: true

geyser-support:
  enabled: true
```

Enabling `geyser-support.enabled` allows a trusted bridge to register; it does not automatically set `allowAccountLogin` to `true`.

### 3. Configure Geyser

Update the matching fields in `plugins/Geyser/config.yml`:

```yaml
bedrock:
  address: 0.0.0.0
  port: 19132

java:
  auth-type: offline

advanced:
  bedrock:
    validate-bedrock-login: true
    use-waterdogpe-forwarding: false
```

Here, `offline` is a downstream Java authentication setting. It does not disable Xbox identity verification for Bedrock players; keep `validate-bedrock-login: true`. The current bridge does not support Floodgate authentication mode or WaterdogPE identity forwarding.

ViaProxy manages Geyser-ViaProxy's actual Java authentication mode. Players sign in to their individual Java accounts through ConnectPlus after entering the lobby. This section describes the ConnectPlus bridge deployment; see the [official Geyser-ViaProxy setup guide](https://geysermc.org/wiki/geyser/setup/self/viaproxy/) for general installation, networking, and port settings.

If your configuration contains `clone-remote-port`, check whether it overrides the Bedrock port you set manually. Disable port following if you want a fixed port of `19132`.

### 4. Restart and check the bridge

Restart ViaProxy with Java 21 or later. Look for this text in the log:

```text
registered with ConnectPlus (bridge capabilities:
```

Run `cp status` and `cp version` to check the bridge registration and actual runtime versions. On the first Geyser start, also wait for Minecraft assets to finish downloading, extracting, and loading.

Bedrock identity verification happens in the background. If the menu opens before the profile or account status updates, wait for verification before making changes. Without a working, compatible bridge, ordinary connections remain available, but protected Bedrock profiles, saved-account restoration, and account linking are unavailable.

### 5. Connect Bedrock players

Sign in to an Xbox account in the Bedrock client, then add a server:

| Field | Value for this example |
| --- | --- |
| Name | Any name, such as `ConnectPlus` |
| Address | The proxy machine's domain, LAN address, or public address |
| Port | `19132`, or the port Geyser is actually listening on |

The Bedrock entry point uses **UDP**. For public access, allow or forward **UDP 19132**. Opening only the Java TCP port is insufficient for Bedrock connections. Java players continue using the Java TCP entry point.

## Player guide

### Lobby menus and hotbar shortcuts

Joining through an ordinary entry point opens the main menu automatically. When you close the chest menu, these shortcuts appear in your hotbar:

| Hotbar slot | Item | Action |
| --- | --- | --- |
| Slot 5 | Compass | Open the lobby's main menu |
| Slot 9 | Barrier | Disconnect from the proxy |

Select an item and use it: Java players can right-click to use it or left-click to swing it; Bedrock players can use it or tap it on a touchscreen. Slot 5 is selected when you enter or return to the lobby. Bedrock players should close chat before using the compass.

Both shortcuts are hidden while a chest menu is open and restored when it closes. Their positions are fixed, and moving, dropping, or swapping them restores the items. Lobby items are cleared before joining a destination and issued again when you return.

Ordinary chat does not open a menu. Chat is used for actions such as entering an address or bookmark name only when a menu has prompted you for input.

The main menu's **How to use** book provides an in-game tutorial. Older clients may display it as a book screen; use the compass to reopen the menu after reading.

### Connect to your first server

1. Click **Set server address** (name tag) in the main menu.
2. Enter a destination in chat, such as `play.example.com` or `play.example.com:25565`. If you omit the port, the usual Java port `25565` is used. Do not add `http://` or `https://`.
3. Usually, leave the version unset to detect it automatically. If you need to select one, click **Set protocol version** (anvil) and choose the **destination server's version**.
4. If the destination requires licensed-account authentication, sign in with Microsoft in the menu and make sure your personal offline mode is disabled.
5. Click **Connect to server** (wooden door) and wait to join.

Automatic detection works with `1.7+` destinations that support the modern server-list status query. If the query is blocked, detection is inaccurate, or you need an earlier server version, select the version manually. The destination version can differ from your client version.

Address entry and bookmark address editing convert full-width ASCII letters, digits, and punctuation to half-width equivalents. For example, `１２３．４５．６７．８９：２５５６５` becomes `123.45.67.89:25565`. International domain names are preserved, and the normalized address must still satisfy administrator restrictions.

While entering an address from the main menu, sending text beginning with `/` cancels that address entry and returns to the menu. Follow the current chat prompt for other editing actions.

When a target status response repeats the connecting client's protocol, automatic version detection performs an additional status query with an unknown protocol. This handles Velocity's and BungeeCord's local ping responses, which echoes supported client versions: an older client can then select the proxy's advertised maximum instead. If the extra query fails or supplies an unknown version, the original successful response is retained. An explicit target version bypasses detection. A proxy's status response cannot guarantee the version of every server behind it; pin the target version when its backend differs from its advertised version.

### Return to the lobby, change servers, or leave

On a destination server joined through ConnectPlus, enter:

```text
/dc
```

Or use `/disconnect` to return to the lobby. Then choose another address or bookmark in the menu and connect.

The same commands **in the lobby** disconnect you from the proxy. The main menu and hotbar's disconnect actions also leave the proxy. Minecraft's own **Disconnect** button ends the entire client connection; use `/dc` first when you want to change servers.

### Save and manage bookmarks

1. Set a server address and destination version in the main menu.
2. Open **Bookmarks** (ender chest).
3. Click **Save current server** to save the current address and version as a personal bookmark. Its initial name is the server address.
4. Left-click or tap a bookmark in the list to open its details.
5. Rename it, change its address, select another version, or use the detail page's **Connect to server** button.

Selecting a bookmark in the list opens its details. To delete it, choose Delete on the detail page and confirm again on the confirmation page; canceling keeps the bookmark. Choosing a version saves it to that bookmark and returns to its details. The Back button or closing the version picker cancels that selection.

Each player can save **50** bookmarks by default, with page navigation when needed. Saving a bookmark with an existing name updates that entry; renaming cannot reuse another bookmark's name. Bookmarks are stored on the proxy and remain available after reconnecting.

### Java and Bedrock menu differences

Java clients use each page's own title and row count. Verified Bedrock connections use a six-row chest menu titled **ConnectPlus** throughout; a paper in the third slot of the bottom row shows the current page name.

Bedrock players should use the page's **Back button** to go up one level. Closing the chest exits the whole menu and restores the compass. Java subpages usually return to their parent when closed. A Bedrock player linked to a Java profile still receives the Bedrock menu presentation.

### Destination disconnections, kicks, and transfers

By default, ConnectPlus tries to return you to the lobby if a destination unexpectedly disconnects or kicks you, and shows the connection failure or kick reason. Administrators can instead enable automatic reconnection or configure kicks to disconnect you directly.

When a destination sends a server transfer request, the default is to return to the lobby and show a confirmation menu. Confirm to connect to the new address, or cancel to stay in the lobby. With `follow` configured, the proxy follows automatically; `ignore` ignores the request.

Switching and recovery depend on the destination responding. Timeouts, unreachable servers, authentication failures, or unsupported protocols produce relevant notices. A stopped proxy process or a lost client network connection still ends the connection.

## Microsoft accounts and saved sign-in

### Why there are two authentication steps

| Authentication | Purpose |
| --- | --- |
| Identity authentication when joining the proxy | Establish who owns the lobby profile. Java uses ViaProxy online mode and completed account authentication; Bedrock uses Xbox verification and the trusted bridge |
| Microsoft sign-in inside the lobby | Supply that player's account credentials for a Java destination requiring licensed-account authentication |

**Even after joining the lobby with an authenticated Java client, you still need to sign in with Microsoft in the lobby before joining a destination that requires account authentication.** A Bedrock Xbox sign-in does not replace the Java account authorization required by such a destination; the Microsoft account must have the required Java game entitlement.

The administrator must enable account features, and your current connection must have a valid authenticated identity. With Java entry-point online mode disabled, unauthenticated Java players cannot use those menu features. Bedrock players with a valid bridge and enabled account features can sign in and link independently of the Java entry point's online-mode setting.

### Sign-in steps

1. Click **Login with Microsoft** in the main menu.
2. The menu closes, and chat displays an authorization URL and a one-time device code.
3. Open that URL in a browser, enter the device code, and sign in to your Microsoft account.
4. Complete the browser authorization, keep your game connected, and wait for the lobby's success message.
5. The menu reopens and displays the signed-in account name. You can then join your destination.

On newer Java clients (`1.21.5+`), clicking the URL copies it; older clients try to open it in a browser. If clicking is unavailable, copy the URL and device code manually. Follow the actual chat instructions.

You enter your password on Microsoft's web page; ConnectPlus does not store Microsoft passwords. After authorization, the plugin handles sign-in tokens according to your save preference. If the device code expires, authorization is canceled, or a network request fails, start sign-in again. A Bedrock player's first Java account sign-in also performs linking; read the [linking guide](#linking-java-and-bedrock-accounts) first.

### Save login information

The main menu's **Save login information** lever is enabled by default:

| Setting | Effect |
| --- | --- |
| Enabled | Store encrypted sign-in information on the proxy for restoration on later visits; expired or invalid credentials can still require authorization again |
| Disabled | Immediately remove saved account credentials from the current profile while keeping the current connection's sign-in usable; sign in again after leaving the proxy |

Returning to the lobby and switching destinations remain part of the same proxy connection. Disabling saving does not immediately log you out or delete bookmarks. Enabling it again lets the current usable sign-in be saved.

Bookmarks, preferences, and saved account data reside **on the proxy server**. Tokens are encrypted with AES-GCM, using the proxy's `secret.key` file. Administrators should protect the data directory, and players should authorize accounts only on a proxy they trust.

### Offline mode, logout, and deletion

| Action | Effect |
| --- | --- |
| Enable personal **Offline mode** (redstone torch) | Use your original player name and its offline UUID for destination connections, without sending Microsoft credentials; keep lobby sign-in and bookmarks. The choice also applies to bookmarks, reconnection, and server transfers |
| Disable personal offline mode | Use an available, permitted Microsoft sign-in for destination connections; without an account, you do not automatically obtain an authenticated identity |
| Click the signed-in account item to **log out** | Remove the current sign-in and credentials saved in that profile; keep bookmarks |
| Disable **Save login information** | Keep using the sign-in for the current connection, without restoring it after leaving the proxy |
| Choose **Delete all data** (TNT) and confirm | Clear the current player profile's account, bookmarks, and settings, and reset the current session; this cannot be undone |

Offline mode cannot bypass destination account authentication. Servers that identify saves by UUID may give you a different inventory or game progress when you change between online and offline identities or change destination accounts. This follows the destination's identity rules.

**Delete all data** clears player profile data; **use the dedicated unlink action to remove a Java/Bedrock link**. Before changing a shared Java profile, remember that the linked identity also uses its data.

## Linking Java and Bedrock accounts

This section applies to deployments with the bridge installed, verified Bedrock identities, and account operations enabled by the administrator.

### Independent profiles before linking

An unlinked Bedrock player uses an independent profile keyed by their Xbox XUID, with their own bookmarks and preferences. The profile is created when identity verification succeeds and the player first enters the lobby.

Java players use profiles keyed by their original authenticated Java UUID. The same Java player reads the same profile across different client versions; changing the account used to authenticate with destinations does not change ownership of the original Java lobby profile.

### Link to a Java account

When an unlinked Bedrock player chooses Microsoft sign-in in the main menu, a data-change warning appears before device-code authorization begins. After successful authorization and linking:

- The current Bedrock identity is linked to the Java UUID verified by authorization. Links are one-to-one.
- The corresponding Java profile becomes active. Its bookmarks and preferences are loaded if it already exists; otherwise, a new profile is created.
- The former independent Bedrock profile is cleaned up. **Bedrock bookmarks are not automatically merged into the Java profile.** Record any addresses you need beforehand or ask an administrator to make a backup.
- Future visits through either the Java entry point or that Bedrock identity use this Java profile.

An XUID or Java UUID already linked to another account causes a conflict; remove that existing link first. Canceled authorization or a failed linking operation that was not committed does not replace the original Bedrock profile. Follow the result shown in the lobby.

### Unlink

A linked Bedrock player sees an **Unlink from Java account…** chain item in the main menu. After successful unlinking:

- The current Bedrock connection stops using the Java credentials and switches to a new, empty independent Bedrock profile.
- The original Java profile's bookmarks and saved sign-in remain available to the Java player.
- Old Bedrock bookmarks cleaned up during linking are not automatically restored by unlinking.

Logging out of Microsoft and unlinking are separate actions; logout does not unlink accounts.

### Shared profiles and duplicate logins

Java and linked Bedrock identities cannot use the same protected profile simultaneously. When a new verified connection takes ownership, ConnectPlus coordinates the old session's exit to avoid concurrent bookmark and account writes. A takeover can fail if the old state cannot be saved safely or another required step fails.

**Two Bedrock clients using the same Xbox account** are subject to official Geyser's duplicate-XUID policy: the original client stays connected, and the new connection is rejected. The ConnectPlus bridge preserves this native rule.

## Commands

### Player commands

ConnectPlus currently provides these two forms, both without arguments:

| Command | In the lobby | On a destination joined through ConnectPlus |
| --- | --- | --- |
| `/disconnect` | Disconnect from the proxy | Return to the lobby |
| `/dc` | Same behavior, short alias | Same behavior, short alias |

To change servers: enter `/dc` on the destination → return to the lobby → connect to another server through the menu.

### Administrator console commands

Enter these in the **ViaProxy console**, using `cp` or `connectplus` as the root command. They work in both plugin modes. The roots also accept `/cp` and `/connectplus`; entering `cp` by itself displays help.

| Command | Description |
| --- | --- |
| `cp help` | List available commands, aliases, and arguments |
| `cp status` | Show the configured mode, actual host listen address, internal lobby address, lobby session count, in-flight destination connections, unexpected lobby exception count for this run, and bridge registration; access lists show configured/runtime switches and account counts in four-line indented blocks |
| `cp list` | List current lobby sessions, each player's online/offline choice for destinations, and Microsoft sign-in state; this is not a list of everyone playing on destinations |
| `cp info <player>` | Query original Java UUID/XUID, lobby online status, bookmark count, and links from current lobby sessions or historical records; supports case-insensitive names, offline queries, and Bedrock names containing spaces; queried and linked identities each show whitelist/blacklist membership even when switches are off |
| `cp links` | List committed Bedrock → Java links with both names and identity IDs, without account tokens |
| `cp accounts` | Show the recorded total visitor count, deduplicated using current links, plus Java/Bedrock identity counts and merged counts |
| `cp version` | Show actual ConnectPlus, ViaProxy, and bridge protocol versions; include bridge and Geyser versions when registered |
| `cp debug on` | Temporarily enable ConnectPlus diagnostics for this run without editing configuration |
| `cp debug off` | Temporarily disable ConnectPlus diagnostics for this run without editing configuration |
| `cp wl` / `cp bl` | Whitelist/blacklist help and runtime switches; full aliases `whitelist` / `blacklist` apply to every action |
| `cp wl on\|off` / `cp bl on\|off` | Change the current run only; restarting restores configured switches |
| `cp wl add\|remove java\|bedrock <name or UUID/XUID>` | Change the unique target and its currently linked counterpart; blacklist uses the same actions |
| `cp wl list` / `cp wl reload` | List entries or reload the file without rewriting it; blacklist uses the same actions |
| `cp access check <name or UUID/XUID>` | Read-only membership and rule check; does not grant authentication |

Examples:

```text
cp info Steve
cp info Bedrock Player
connectplus status
cp debug on
```

`cp info` groups results by identity; distinct identities with the same name can appear separately. Queries do not create player profiles. `cp accounts` counts people who actually entered the lobby, not signed-in Microsoft accounts or current online players. Recording starts when the visitor index was introduced and cannot reconstruct unrecorded visits from before that upgrade. Linking merges visitor counts, and unlinking separates them again.

In `cp info`, `[在线]` means currently in the lobby, and `[离线]` means there is no current lobby session. The latter can also describe someone playing on a destination, so it does not establish that the player has left the proxy.

If a visitor or link index is corrupt, the relevant queries report it as unavailable, not as zero people, and do not overwrite the original file. The `cp status` exception count includes only unexpected exceptions recorded by lobby handlers, not every JVM, ViaProxy, or Geyser error.

Use the host's `stop` command to stop the proxy. ConnectPlus currently has no `cp reload`, console server-switch, kick, or link-editing command. Fully restart the proxy after changing persistent configuration.

### Whitelist and blacklist

These console-only commands work for lobby joins, lobby-to-server switches, native wildcard direct connections and `mode: proxy`. Roots `cp` / `connectplus` and their leading-slash forms are equivalent. Names locate accounts; rules match Java **entry UUID** or Bedrock **XUID**, independently of authentication. A Java whitelist entry does not grant account/profile permissions. Ambiguous names only list candidates; use the exact identifier to make a change. Canonical identifiers can pre-add an unknown player.

The root `cp help` lists `cp wl` and `cp bl`, their full aliases, required platform arguments and `access check`. Access-list replies follow `language` through the existing language packs (`ConsoleAccess.*`), including help, list entries, changes, queries and failure prompts. For `auto`, the console has no client locale and uses the existing English fallback. `on/off` values and command tokens retain their spelling.

Explicit `add` saves known names for both the target and its linked counterpart, including offline names from the typed visit/name index. Repeat the same `add` to fill missing names in older entries; existing names and identifiers stay intact. Unknown names remain absent. `reload` and journal replay do not enrich existing entries. Success replies identify the list, for example `Added JavaPlayer (...) to whitelist.`; removal also names its source list. The new `ConsoleAccess.AddedToList` / `RemovedFromList` keys are appended to older language packs without replacing their previous translations.

Both files are under `plugins/ConnectPlus/` and use this case-sensitive schema:

```json
{
  "schemaVersion": 1,
  "players": [
    {"name": "JavaPlayer", "clientType": "java", "UUID": "00000000-0000-4000-8000-000000000001"},
    {"name": "Bedrock Player", "clientType": "bedrock", "XUID": "2530000000000001"}
  ]
}
```

`name` may be null for pre-added identifiers. UUIDs use full hyphenated form; XUIDs are canonical positive decimal strings without leading zeros, within unsigned 64-bit range. Auxiliary identifiers are permitted but never change the platform's primary key. Duplicate primary keys, invalid schema or malformed identifiers fail loading. Disabled lists initialize missing files as empty; an enabled missing/corrupt list rejects until repaired and reloaded. Failed reload keeps the last valid rules and leaves disk bytes untouched. External edits must be reloaded before console writes.

`add/remove` updates both currently linked accounts in one list write, including removal when only the counterpart has a record. A **new binding inherits only blacklist membership**, even when blacklist is off; unlink keeps existing records and ends subsequent synchronization. Manual file edits followed by `reload` use exactly those entries: they do not inherit, repair or synchronize links. If inheritance fails after a binding commits, the binding stays committed, reports access pending and keeps short-lived transaction intent; affected keys are blocked while blacklist is enabled and protected CP operations pause. Repair the file and restart for transaction recovery. Ordinary reload never replays transactions. Blacklist data must be available before a new binding, including when its switch is off.

Known Bedrock without XUID is allowed with whitelist off, including blacklist-only mode (the skipped blacklist check is logged); whitelist on rejects it. **Unknown platform differs from confirmed Bedrock**: if either list is enabled, unavailable/timeout bridge classification rejects rather than using a Java UUID. Offline Java is classified as Java when public host metadata proves Geyser is absent. On a mixed Geyser host an unverified Java connection without a conclusive platform signal may therefore be refused; use completed Java authentication and a healthy supported bridge.

Resolved connections allowed by the lists can use ordinary connections/switches without an extra identifier gate. With both lists off, unknown platform does not add a new restriction to those operations; account and protected-profile permissions still use their existing authentication and lease checks.

Java synchronous refusal closes the exact frontend and backend before the host queues backend login. Official APIs do not pause native forwarding during asynchronous Bedrock identity resolution: it may briefly enter the backend before a negative result disconnects it. CP account/profile claims and switches wait for admission. Official Geyser still refuses duplicate XUIDs while retaining the original client; neither host is modified. Automated verification and outstanding real-client acceptance are recorded separately in verification and real-client checklist. Chinese operator guidance: [配置说明](docs/config-zh.md#黑白名单).

## Administrator configuration

ConnectPlus configuration is stored in **`plugins/ConnectPlus/config.yml`**. The host's `viaproxy.yml` and Geyser's configuration control their respective behavior; do not mix fields from different files.

Apart from the runtime `cp debug on/off`, `cp wl on/off`, and `cp bl on/off` switches, apply configuration and language-file changes by **restarting the proxy**. The plugin may regenerate configuration comments on load. Use this guide or the [Chinese configuration reference](docs/config-zh.md) when reviewing settings.

### Lobby, language, and connection limits

| Setting | Default | Meaning |
| --- | --- | --- |
| `mode` | `lobby` | `lobby`: ordinary addresses enter the built-in lobby; `proxy`: disable lobby routing and use ViaProxy's ordinary proxy behavior |
| `motd` | `§bConnectPlus Lobby` | Lobby description in the Java multiplayer list; supports `§` color codes and `\n` inside double quotes. Direct connections and `proxy` mode use the destination's status |
| `language` | `en` | `en` for English, `zh` for Chinese, `auto` for the client's language, or a custom language filename without `.yml` |
| `switchTimeoutSeconds` | `15` | Maximum duration of one switch in seconds; on timeout, attempt to return to the lobby |
| `maxConnectAttemptsPerMinute` | `30` | Connection attempt limit per player per minute; `0` disables it. Lobby recovery is exempt |
| `blockLocalTargets` | `true` | Block destination connections to the proxy machine itself, loopback, private networks, and other restricted addresses |
| `whitelist` | `false` | Startup whitelist switch; console on/off applies to this run only |
| `blacklist` | `false` | Startup blacklist switch; blacklist rejection has priority |
| `maxBookmarksPerPlayer` | `50` | Maximum saved bookmarks per player profile |

For a public shared proxy, normally leave `blockLocalTargets: true`. Destinations on the same machine or private network are blocked by default. Consider setting it to `false` only when you need trusted internal destinations and can control who uses the proxy. Disabling it lets player-selected targets point to the proxy machine and internal services; the deployment administrator must control access.

### Accounts and Bedrock bridge

| Setting | Default | Meaning |
| --- | --- | --- |
| `allowAccountLogin` | `true` | Administrator account-feature switch. When enabled, connections must still have a trusted authenticated identity. When disabled, do not sign in, restore, or use saved accounts; retain existing encrypted data |
| `accountLoginAllowlist` | `[]` | Player names allowed to initiate Microsoft sign-in, matched case-insensitively; an empty list permits all players meeting authentication requirements. Leave it at the default empty list: this is a legacy MiniConnect setting, and ConnectPlus provides redesigned whitelist functionality that is easier to use |
| `geyser-support.enabled` | `false` | Allow a matching bridge extension to register and supply trusted Bedrock identities; enabling this alone does not install the extension or replace Xbox verification |

**Automatic account-feature disable rule:** if both ViaProxy's `proxy-online-mode` and ConnectPlus's `geyser-support.enabled` are disabled, ConnectPlus sets `allowAccountLogin` to `false` on proxy load/start and writes it to configuration. Re-enabling an authentication path still requires the administrator to manually set `allowAccountLogin` back to `true`.

Disabling the account-feature switch affects both Java and Bedrock connections. Asynchronous sign-in completion, switches, reconnections, and transfers must also respect the restriction. Existing bookmarks and encrypted saved tokens remain, but those credentials are not used while restricted.

### Recovery and server transfers

| Setting | Default | Meaning |
| --- | --- | --- |
| `backendDownPolicy` | `lobby` | On an unexpected destination disconnection, `lobby` returns to the lobby and `reconnect` attempts automatic reconnection |
| `reconnectAttempts` | `3` | Maximum automatic reconnection attempts before returning to the lobby; `0` means no retry. Used only with `backendDownPolicy: reconnect` |
| `reconnectDelaySeconds` | `5` | Base reconnection wait in seconds, increasing linearly after later failures; the first recovery attempt may start immediately |
| `kickPolicy` | `lobby` | When kicked, `lobby` returns with the reason and `disconnect` forwards the kick and ends the connection |
| `transferPolicy` | `confirm` | On a requested address transfer, `confirm` returns to the lobby for confirmation, `follow` follows automatically, and `ignore` ignores the request |

For example, to reconnect after a temporary destination disconnection, update the existing fields:

```yaml
backendDownPolicy: reconnect
reconnectAttempts: 3
reconnectDelaySeconds: 5
kickPolicy: lobby
transferPolicy: confirm
```

### Diagnostic logs

| Setting | Default | Meaning |
| --- | --- | --- |
| `debug` | `false` | Enable ConnectPlus DEBUG diagnostics. Restart after a persistent change, or use `cp debug on/off` temporarily |

Routine startup, connection outcomes, and recovery information remain available; warnings and errors are unaffected by this switch. Official ViaProxy's default logging writes detailed diagnostics to **`logs/debug.log`**, while the console and `logs/latest.log` primarily show routine output. It is normal for DEBUG messages not to appear in the console after enabling diagnostics.

`cp debug on/off` affects only the current process; restarting restores the configured `debug` value. This switch controls ConnectPlus diagnostics. ViaProxy and Geyser retain their own logging settings.

### Customize interface languages

The first load generates:

```text
plugins/ConnectPlus/languages/en.yml
plugins/ConnectPlus/languages/zh.yml
```

Edit these texts directly and restart to apply them. Deleting a text key or leaving it blank falls back to built-in text. To add another language, copy `en.yml` to a file such as `fr.yml`, translate it, and set `language: fr`. Missing keys in a custom pack fall back to English.

With `language: auto`, each player's client locale selects a pack: for example, `zh_cn` selects `zh`, and `de_de` selects `de` when `de.yml` exists. If no pack matches, English is used. After changing the in-game language, reconnect to the lobby to apply language selection.

A legacy `messages.yml` is migrated to the English language file when `languages/en.yml` does not exist. New installations should use `languages/`.

### Ordinary proxy mode and wildcard connections

`mode: proxy` disables ConnectPlus lobby routing. Connections use the fixed destination address and version from ViaProxy configuration. Replace the example placeholder `127.0.0.1:1` with a real destination.

In `mode: lobby`, ViaProxy wildcard addresses retain direct-connection behavior, for example `target-address_port_version.viaproxy.proxy-domain`. This requires separate ViaProxy wildcard handling and DNS configuration; follow the [official host instructions](https://github.com/ViaVersion/ViaProxy). Lobby bookmarks and connection menus are intended for ordinary lobby entry points.

## Backups and upgrades

### Important files

Paths below are relative to ViaProxy's runtime directory:

| Path | Purpose |
| --- | --- |
| `viaproxy.yml` | Host listen settings, default destination, and Java entry-point authentication |
| `plugins/ConnectPlus/config.yml` | ConnectPlus settings |
| `plugins/ConnectPlus/languages/` | Built-in language files and administrator text overrides |
| `plugins/ConnectPlus/secret.key` | Account-token encryption key; back it up with player data |
| `plugins/ConnectPlus/players/java/<JavaUUID>.json` | Java player profiles and data shared by linked Bedrock identities |
| `plugins/ConnectPlus/players/bedrock/<XUID>.json` | Independent profiles for unlinked Bedrock players |
| `plugins/ConnectPlus/players/identity-links.json` | Committed Bedrock XUID ↔ Java UUID links |
| `plugins/ConnectPlus/players/.transactions/` | Recovery records for link/unlink operations, created as needed; include them in backups |
| `plugins/ConnectPlus/visits-index.json` | Lobby visit records and name index used by console queries |
| `plugins/ConnectPlus/whitelist.json` | Whitelist entries, stored independently of runtime/config switches |
| `plugins/ConnectPlus/blacklist.json` | Blacklist entries, stored independently of runtime/config switches |
| `plugins/Geyser/config.yml` | Bedrock entry-point and authentication settings when Geyser is installed |
| `logs/latest.log`, `logs/debug.log` | Runtime logs and detailed diagnostics |

### Back up or move a deployment

Stop the proxy normally before backing up **the entire `plugins/ConnectPlus/` directory**. Save the host and Geyser configuration as well. When moving to another machine, move the data and configuration together and recheck listen addresses and port forwarding.

Do not back up only player JSON files and lose `secret.key`. Without the original key, old account tokens cannot be decrypted and players must authorize again. Bookmarks do not depend on this key.

Do not publicly upload the data directory, encryption key, or backups containing account information. The plugin maintains link indexes and transaction records; avoid editing them manually while running.

### Update the plugin

1. Stop ViaProxy and back up data and configuration.
2. Move the old ConnectPlus JAR out of `plugins/` and install the new JAR, keeping only one version.
3. If updating the bridge too, replace its extension in `plugins/Geyser/extensions/` while preserving configuration and player data.
4. Check the supported versions of the new plugin, official ViaProxy, Geyser, and Java.
5. After startup, run `cp version` and `cp status`, then use an actual client to test lobby entry, bookmark loading, destination connections, and returning.

If an old configuration cannot be merged during an upgrade, the plugin may save it as `config.yml.backup-timestamp` and regenerate defaults. If the log reports this, compare the backup, reapply your settings, and restart. If legacy player ownership is unclear or migration conflicts occur, preserve original files and backups instead of overwriting new profiles manually.

Continue using official host JARs when upgrading. ConnectPlus and bridge updates do not require maintaining host patches.

## FAQ and troubleshooting

| Symptom | What to check |
| --- | --- |
| Cannot connect to the Java entry point | Confirm ViaProxy is running, check `bind-address`, TCP firewall/security-group rules, and forwarding; use `cp status` to confirm the host listen address |
| No lobby menu after joining | Check plugin loading, `mode: lobby`, and whether you used a wildcard direct address; use the slot 5 compass if the menu was closed |
| Bedrock client cannot connect | Check Geyser startup, supported client version, actual Bedrock port, and UDP rules/forwarding; do not use the Java TCP port as the Bedrock port |
| Bedrock client on the same Windows machine cannot connect | Alongside listen and UDP settings, check Windows app loopback restrictions; see the loopback fix in the [official Geyser guide](https://geysermc.org/wiki/geyser/setup/self/viaproxy/) |
| `ConnectPlus not found` in the log | Confirm the ConnectPlus JAR is installed in the same instance's `plugins/` directory and loaded without errors |
| Bridge not registered or `bridge disabled` appears | Check the extension directory, Java 21, host version series, `geyser-support.enabled`, Xbox verification, and Floodgate/WaterdogPE settings; follow the reason in the same log message |
| Bedrock bookmarks do not save or account features are unavailable | Check the bridge with `cp status`; protected profiles and accounts are unavailable before identity verification or after the bridge becomes invalid |
| Microsoft sign-in is disabled | Check `allowAccountLogin`. Java also requires entry-point online mode and completed authentication; Bedrock requires a valid bridge. If both authentication paths were previously disabled, manually re-enable the account switch |
| Microsoft sign-in is not allowed for this player | Check that `accountLoginAllowlist` includes the current original player name; matching is case-insensitive |
| Destination requires a licensed account | Sign in with Microsoft in the lobby and disable personal offline mode; ensure the account has Java game entitlement and valid credentials |
| Device-code authorization still fails | Keep the game connected, read the chat reason, and check authentication-service connectivity; retry after an expired code, canceled authorization, or entitlement issue |
| Destination address is rejected | Check address/port syntax, accidental URL prefixes, and whether `blockLocalTargets` blocks the same machine or private network |
| Automatic version detection fails | Check destination status-query access and select the destination version manually; make sure you did not select your own client version instead |
| Switching reports an unsupported version | Update to ConnectPlus support for that client protocol and check host support, or connect with a supported release client |
| Switching times out or repeatedly fails | Check reachability, version, account requirements, and destination logs; enable diagnostics to inspect the failure at the same time |
| `/connect` or `/bookmarks` does not work | These commands were removed from ConnectPlus. Use lobby menus; return with `/dc` without arguments |
| `/dc` on a destination leaves the proxy | Check that you joined through ConnectPlus lobby switching; ordinary proxy mode and wildcard direct connections are outside this return-to-lobby flow |
| Closing a Bedrock chest does not return to its parent page | Closing exits the whole GUI. Use its Back buttons or reopen with the compass |
| Ordinary chat does not reopen the menu | Use the slot 5 compass |
| GUI item names show numbers such as `#4101` | Minecraft advanced tooltips are enabled; press `F3 + H` in Java to disable them |
| A language change has no effect | Check `language` and the language file, then restart the proxy; in `auto` mode, reconnect after changing the client language |
| DEBUG messages do not appear in the console | Official defaults write details to `logs/debug.log`; check that file and the enable command's feedback |
| Linking reports a conflict | An XUID or Java UUID is already linked elsewhere. Unlink through the corresponding identity first; administrators can inspect `cp links` |
| A second Bedrock client using the same account is rejected | This is official Geyser behavior. The old client stays connected; leave the old connection before signing in again |

### What to include in an issue report

Before reproducing a problem, run `cp debug on` in the proxy console, then `cp debug off` afterward. Record the following for the maintainer:

- Output from `cp version` and `cp status`, plus the Java runtime version.
- Java/Bedrock client version and destination server type and version.
- Exact steps, time of occurrence, chat errors, expected behavior, and actual behavior.
- The corresponding sections of `logs/latest.log` and `logs/debug.log`, plus destination logs when relevant.

Remove account tokens, device codes, keys, passwords, and player identities or network addresses you do not want to publish. You do not need to send `secret.key` or the entire player data directory.

## Scope and acceptance testing

ConnectPlus uses unmodified official ViaProxy and Geyser-ViaProxy, with features provided by the plugin and separate extension. Protocol translation comes from the hosts. Servers requiring client mods, custom authentication or protocols, special resource packs, and future protocol formats do not automatically become compatible by installing this plugin.

In-game switching keeps the client connected to the proxy, but each new destination still needs a connection and login. Keeping that connection does not move worlds, inventories, or game state from one destination to another.

The project's automated checks cover switching and recovery, account policy, profiles and links, GUI behavior, and commands. **Passing automated tests, successful host startup, bridge registration, or a UDP ping does not establish real-client acceptance.** Test Java/Bedrock clients separately for joining, movement, repeated switching, lobby returns, and linking with your actual deployment combination.

Some real-client scenarios remain unexecuted. Automated checks and real-client acceptance are recorded separately; verify the deployment combination you plan to use.

## Building an installation JAR

Skip this section if you already have the plugin JAR. If you have source code, install JDK 17 or later and use the project's Gradle Wrapper from the source root.

Windows PowerShell:

```powershell
.\gradlew.bat build
```

Linux/macOS:

```shell
./gradlew build
```

The first build needs network access to download Gradle and dependencies. After a successful build, find the generated plugin JAR in:

```text
build/libs/
```

Rename the generated JAR to `ConnectPlus.jar`, place it in ViaProxy's `plugins/` directory, and follow the installation steps above. The Bedrock bridge is built in a separate project and must be obtained separately; no changes to or rebuilding of the official hosts are required.

## License and acknowledgments

ConnectPlus is licensed under [GPL-3.0](LICENSE). Some lobby features derive from [MiniConnect](https://github.com/ViaVersionAddons/MiniConnect)'s design and code; the corresponding MIT attribution notices remain in the source. The full upstream notice is included in [Third-party notices](THIRD_PARTY_NOTICES.md).

Thanks to [ViaProxy / ViaVersion](https://github.com/ViaVersion/ViaProxy) and [GeyserMC](https://geysermc.org/) for the protocol translation and cross-edition connection foundations.
