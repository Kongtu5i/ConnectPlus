package dev.connectplus.lobby.screen;

import net.lenni0451.mcstructs.text.Style;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.stringformat.StringFormat;
import net.lenni0451.mcstructs.text.stringformat.handling.ColorHandling;
import net.lenni0451.mcstructs.text.stringformat.handling.DeserializerUnknownHandling;
import net.lenni0451.mcstructs.text.utils.TextUtils;

/**
 * User-facing texts for the lobby GUI. Derived from MiniConnect's Messages
 * (MIT, Copyright (c) 2024 Lenni0451). Modified in this repo: Login and
 * ProxyOnlineMode sections are dropped, Welcome/Commands sections are added.
 *
 * <p>The English texts are the built-in defaults on the {@link Msg} fields;
 * the key of each Msg mirrors its section path and doubles as the language
 * file key (see {@link Languages}). {@link Zh} is the built-in Chinese mirror
 * pack. Consumption sites resolve through {@link Languages#text} against the
 * player's {@link Lang}; the reflection tests in MessagesTest guarantee that
 * every key matches its field path and that both packs stay field-for-field
 * in sync.</p>
 */
public class Messages {

    private static final StringFormat LEGACY_FORMAT = StringFormat.vanilla();

    public static TextComponent[] format(final String message, final Object... args) {
        final String[] lines = message.split("\n");
        final TextComponent[] components = new TextComponent[lines.length];
        for (int i = 0; i < lines.length; i++) {
            components[i] = LEGACY_FORMAT.fromString(lines[i], ColorHandling.RESET, DeserializerUnknownHandling.IGNORE);
            components[i] = TextUtils.replace(components[i], "\\{\\d+\\}", original -> {
                final String argIndexStr = original.asUnformattedString();
                final int argIndex = Integer.parseInt(argIndexStr.substring(1, argIndexStr.length() - 1));
                if (argIndex >= 0 && argIndex < args.length) {
                    final Object arg = args[argIndex];
                    final TextComponent argComponent;
                    if (arg instanceof TextComponent) {
                        argComponent = (TextComponent) arg;
                    } else {
                        argComponent = TextComponent.of(String.valueOf(arg));
                    }
                    //The placeholder's style (color etc.) is the base; the argument's
                    //own style (click events like the verification link) wins on top
                    final Style merged = original.getStyle().copy();
                    mergeStyle(merged, argComponent.getStyle());
                    return argComponent.setStyle(merged);
                } else {
                    return original;
                }
            });
        }
        return components;
    }

    /**
     * Copies every set property of {@code from} onto {@code into}, overriding it.
     */
    private static void mergeStyle(final Style into, final Style from) {
        if (from.getColor() != null) into.setFormatting(from.getColor());
        if (from.getShadowColor() != null) into.setShadowColor(from.getShadowColor());
        if (from.getObfuscated() != null) into.setObfuscated(from.getObfuscated());
        if (from.getBold() != null) into.setBold(from.getBold());
        if (from.getStrikethrough() != null) into.setStrikethrough(from.getStrikethrough());
        if (from.getUnderlined() != null) into.setUnderlined(from.getUnderlined());
        if (from.getItalic() != null) into.setItalic(from.getItalic());
        if (from.getClickEvent() != null) into.setClickEvent(from.getClickEvent());
        if (from.getHoverEvent() != null) into.setHoverEvent(from.getHoverEvent());
        if (from.getInsertion() != null) into.setInsertion(from.getInsertion());
        if (from.getFont() != null) into.setFont(from.getFont());
    }

    public static class Hotbar {
        public static final Msg MenuName = new Msg("Hotbar.MenuName", "§aOpen main menu");
        public static final Msg MenuLore = new Msg("Hotbar.MenuLore", "§7Use or swing this item to open the menu");
        public static final Msg DisconnectLore = new Msg("Hotbar.DisconnectLore", "§7Use or swing this item to disconnect");
    }

    public static class MainScreen {
        public static final Msg Title = new Msg("MainScreen.Title", "§aConnectPlus");

        public static class SetServerAddress {
            public static final Msg ItemName = new Msg("MainScreen.SetServerAddress.ItemName", "§aSet server address");
            public static final Msg ItemLore = new Msg("MainScreen.SetServerAddress.ItemLore", "§bClick to set the server address to connect to");
            public static final Msg ItemLoreAddressSet = new Msg("MainScreen.SetServerAddress.ItemLoreAddressSet", "§aAddress: §6{0}");
            public static final Msg ItemLoreNoAddressSet = new Msg("MainScreen.SetServerAddress.ItemLoreNoAddressSet", "§cNo address set (required)");
            public static final Msg ChatInfo = new Msg("MainScreen.SetServerAddress.ChatInfo", "§aPlease enter the server ip into the chat (with optional port) (e.g. example.com, example.com:25565)");
            public static final Msg ChatCancelled = new Msg("MainScreen.SetServerAddress.ChatCancelled", "§cCancelled input");
            public static final Msg ChatInvalidAddress = new Msg("MainScreen.SetServerAddress.ChatInvalidAddress", "§cInvalid server address");
        }

        public static class SetProtocolVersion {
            public static final Msg ItemName = new Msg("MainScreen.SetProtocolVersion.ItemName", "§aSet protocol version");
            public static final Msg ItemLore = new Msg("MainScreen.SetProtocolVersion.ItemLore", "§bClick to set the protocol version to connect with");
            public static final Msg ItemLoreVersionSet = new Msg("MainScreen.SetProtocolVersion.ItemLoreVersionSet", "§aVersion: §6{0}");
            public static final Msg ItemLoreAutoDetect = new Msg("MainScreen.SetProtocolVersion.ItemLoreAutoDetect", "§7Protocol version is auto detected while no version is set");
        }

        public static class Bookmarks {
            public static final Msg ItemName = new Msg("MainScreen.Bookmarks.ItemName", "§aBookmarks");
            public static final Msg ItemLore = new Msg("MainScreen.Bookmarks.ItemLore", "§bClick to open your saved servers ({0})");
        }

        public static class OfflineMode {
            public static final Msg ItemName = new Msg("MainScreen.OfflineMode.ItemName", "§aOffline mode");
            public static final Msg ItemLore = new Msg("MainScreen.OfflineMode.ItemLore", "§bClick to toggle offline connections\n§7Your Microsoft login is kept");
            public static final Msg ItemLoreEnabled = new Msg("MainScreen.OfflineMode.ItemLoreEnabled", "§eOffline mode: enabled (connect without your Microsoft account)");
            public static final Msg ItemLoreDisabled = new Msg("MainScreen.OfflineMode.ItemLoreDisabled", "§7Offline mode: disabled (use your logged-in account when available)");
            public static final Msg ChatEnabled = new Msg("MainScreen.OfflineMode.ChatEnabled", "§aOffline mode enabled. Connections use an offline identity; your Microsoft login is kept.");
            public static final Msg ChatDisabled = new Msg("MainScreen.OfflineMode.ChatDisabled", "§aOffline mode disabled. Connections use your logged-in account when available.");
        }

        public static class ConnectToServer {
            public static final Msg ItemName = new Msg("MainScreen.ConnectToServer.ItemName", "§aConnect to server");
            public static final Msg ItemLore = new Msg("MainScreen.ConnectToServer.ItemLore", "§bClick to connect to the server");
            public static final Msg ItemLoreNoAddress = new Msg("MainScreen.ConnectToServer.ItemLoreNoAddress", "§cNo address set (required)");
            public static final Msg ItemLoreMissingRequirements = new Msg("MainScreen.ConnectToServer.ItemLoreMissingRequirements", "§cYou need to set all options before connecting");
        }

        public static class HowToUse {
            public static final Msg ItemName = new Msg("MainScreen.HowToUse.ItemName", "§6How to use");
        }

        public static class Login {
            public static final Msg ItemName = new Msg("MainScreen.Login.ItemName", "§aLogin with Microsoft");
            public static final Msg ItemLore = new Msg("MainScreen.Login.ItemLore", "§bClick to login with your Microsoft account (needed for online-mode servers)");
            public static final Msg ItemLoreLoggedIn = new Msg("MainScreen.Login.ItemLoreLoggedIn", "§aLogged in as §6{0}");
            public static final Msg ItemLoreNotLoggedIn = new Msg("MainScreen.Login.ItemLoreNotLoggedIn", "§cNot logged in");
            public static final Msg ItemLoreExpired = new Msg("MainScreen.Login.ItemLoreExpired", "§cLogin expired - click to login again");
            public static final Msg ChatLoading = new Msg("MainScreen.Login.ChatLoading", "§6Starting the Microsoft device code login...");
            public static final Msg ChatCodeLogin = new Msg("MainScreen.Login.ChatCodeLogin", """
                    §6To login, open §9{0}§r and enter the code §2{1}§r.""");
            public static final Msg ChatCodeLoginCopyHint = new Msg("MainScreen.Login.ChatCodeLoginCopyHint", "§7Click the link above to copy it to your clipboard.");
            public static final Msg ChatLoginSuccess = new Msg("MainScreen.Login.ChatLoginSuccess", "§aLogin successful");
            public static final Msg ChatLoginFailed = new Msg("MainScreen.Login.ChatLoginFailed", "§cLogin failed: {0}");
            public static final Msg ChatLogout = new Msg("MainScreen.Login.ChatLogout", "§aLogged out - the stored account was removed from this proxy");
            public static final Msg ChatLinkWarning = new Msg("MainScreen.Login.ChatLinkWarning", """
                    §6After linking a Java account, your current Bedrock profile will be cleared and its bookmarks will NOT be kept. Please back them up in advance. After linking, the Java account's profile will be used; if that account has no existing profile, a new one will be created.""");
            public static final Msg ChatLinkCommitted = new Msg("MainScreen.Login.ChatLinkCommitted", "§aJava account linked. Your profile is now the Java account's profile.");
            public static final Msg ChatLinkCommittedCleanupPending = new Msg("MainScreen.Login.ChatLinkCommittedCleanupPending", "§aJava account linked. Your old Bedrock profile will be removed automatically (a cleanup is pending).");
            public static final Msg ChatLinkCommittedAccessPending = new Msg("MainScreen.Login.ChatLinkCommittedAccessPending", "§eJava account linked. The access list update is still pending, the lists are temporarily unavailable for the new accounts.");
            public static final Msg ChatLinkConflict = new Msg("MainScreen.Login.ChatLinkConflict", "§cThis Bedrock account or the Java account is already linked to another account. Please unlink first, then link again.");
            public static final Msg ChatLinkStale = new Msg("MainScreen.Login.ChatLinkStale", "§cThe link could not be started because your session changed. Please log in again.");
            public static final Msg ChatLinkFailed = new Msg("MainScreen.Login.ChatLinkFailed", "§cLinking failed - your Bedrock profile is unchanged. Please try again.");
        }

        /**
         * The §1.3 bedrock-only unlink option (task 7). The button text formats
         * the Java account's SAFELY PROCESSED display name in (never a raw
         * client-controlled string); the description is the spec text verbatim.
         */
        public static class Unlink {
            public static final Msg ItemName = new Msg("MainScreen.Unlink.ItemName", "§cUnlink from Java account {0}");
            public static final Msg ItemLore = new Msg("MainScreen.Unlink.ItemLore",
                    "§7After unlinking you will use an independent Bedrock profile; the Java account's bookmarks and saved login are kept.");
            public static final Msg FallbackName = new Msg("MainScreen.Unlink.FallbackName", "Unknown");
            public static final Msg ChatLoading = new Msg("MainScreen.Unlink.ChatLoading",
                    "§6Unlinking your Java account...");
            public static final Msg ChatBusy = new Msg("MainScreen.Unlink.ChatBusy",
                    "§6Another account operation is already in progress - please wait a moment.");
            public static final Msg ChatCommitted = new Msg("MainScreen.Unlink.ChatCommitted",
                    "§aUnlinked. You now use your own independent Bedrock profile.");
            public static final Msg ChatCommittedCleanupPending = new Msg("MainScreen.Unlink.ChatCommittedCleanupPending",
                    "§aUnlinked. Your independent Bedrock profile could not be created right now - it is blank and will be completed automatically (a cleanup is pending).");
            public static final Msg ChatStale = new Msg("MainScreen.Unlink.ChatStale",
                    "§cThe unlink could not be started because your session changed.");
            public static final Msg ChatFailed = new Msg("MainScreen.Unlink.ChatFailed",
                    "§cUnlinking failed - the binding is unchanged. Please try again.");
        }

        public static class SaveLoginInfo {
            public static final Msg ItemName = new Msg("MainScreen.SaveLoginInfo.ItemName", "§aSave login information");
            public static final Msg ItemLore = new Msg("MainScreen.SaveLoginInfo.ItemLore", "§bClick to toggle whether your account login is kept on this proxy");
            public static final Msg ItemLoreEnabled = new Msg("MainScreen.SaveLoginInfo.ItemLoreEnabled", "§aEnabled§7 - your login stays on this proxy after you disconnect");
            public static final Msg ItemLoreDisabled = new Msg("MainScreen.SaveLoginInfo.ItemLoreDisabled", "§cDisabled§7 - your login is wiped when you disconnect");
            public static final Msg ChatEnabled = new Msg("MainScreen.SaveLoginInfo.ChatEnabled", "§aLogin information will be saved on this proxy - your login survives reconnects.");
            public static final Msg ChatDisabled = new Msg("MainScreen.SaveLoginInfo.ChatDisabled", "§6Login information will not be saved - all account login information is wiped when you disconnect from this proxy.");
        }

        public static class DeleteAll {
            public static final Msg ItemName = new Msg("MainScreen.DeleteAll.ItemName", "§cDelete all data");
            public static final Msg ItemLore = new Msg("MainScreen.DeleteAll.ItemLore", "§cClick to delete ALL your stored data on this proxy (account, bookmarks)");
        }

        public static class Disconnect {
            public static final Msg ItemName = new Msg("MainScreen.Disconnect.ItemName", "§cDisconnect");
            public static final Msg DisconnectMessage = new Msg("MainScreen.Disconnect.DisconnectMessage", "Manual Disconnect");
        }
    }

    public static class VersionSelectorScreen {
        public static final Msg Title = new Msg("VersionSelectorScreen.Title", "§aSelect Version");
        public static final Msg PreviousPage = new Msg("VersionSelectorScreen.PreviousPage", "§aPrevious Page");
        public static final Msg Back = new Msg("VersionSelectorScreen.Back", "§cBack");
        public static final Msg NextPage = new Msg("VersionSelectorScreen.NextPage", "§aNext Page");
    }

    public static class TutorialScreen {
        public static final Msg Title = new Msg("TutorialScreen.Title", "ConnectPlus Tutorial");
    }

    public static class BookmarksScreen {
        public static final Msg Title = new Msg("BookmarksScreen.Title", "§aBookmarks");
        public static final Msg Empty = new Msg("BookmarksScreen.Empty", "§cNo bookmarks saved yet");
        public static final Msg ItemLore = new Msg("BookmarksScreen.ItemLore", "§7{0}");
        public static final Msg ItemLoreAutoDetect = new Msg("BookmarksScreen.ItemLoreAutoDetect", "§7Version: §8auto detect");
        public static final Msg ItemLoreLastUsed = new Msg("BookmarksScreen.ItemLoreLastUsed", "§8Last used: {0}");
        public static final Msg SaveCurrent = new Msg("BookmarksScreen.SaveCurrent", "§aSave current server");
        public static final Msg SaveCurrentLore = new Msg("BookmarksScreen.SaveCurrentLore", "§bClick to save the currently set address as a bookmark");
        public static final Msg SaveCurrentNoAddress = new Msg("BookmarksScreen.SaveCurrentNoAddress", "§cNo address set - set a server address first");
        public static final Msg SaveSuccess = new Msg("BookmarksScreen.SaveSuccess", "§aBookmark saved");
        public static final Msg PreviousPage = new Msg("BookmarksScreen.PreviousPage", "§aPrevious Page");
        public static final Msg Back = new Msg("BookmarksScreen.Back", "§cBack");
        public static final Msg NextPage = new Msg("BookmarksScreen.NextPage", "§aNext Page");
        public static final Msg DetailTitle = new Msg("BookmarksScreen.DetailTitle", "§aBookmark details");
        public static final Msg CurrentName = new Msg("BookmarksScreen.CurrentName", "§eCurrent name: §6{0}");
        public static final Msg CurrentVersion = new Msg("BookmarksScreen.CurrentVersion", "§eCurrent version: §6{0}");
        public static final Msg CurrentVersionAutoDetect = new Msg("BookmarksScreen.CurrentVersionAutoDetect", "§eCurrent version: §6auto detect");
        public static final Msg CurrentAddress = new Msg("BookmarksScreen.CurrentAddress", "§eCurrent address: §6{0}");
        public static final Msg Rename = new Msg("BookmarksScreen.Rename", "§aRename bookmark");
        public static final Msg RenameLore = new Msg("BookmarksScreen.RenameLore", "§bClick to rename this bookmark");
        public static final Msg RenameChatInfo = new Msg("BookmarksScreen.RenameChatInfo", "§aPlease type the new bookmark name into the chat");
        public static final Msg ChangeAddress = new Msg("BookmarksScreen.ChangeAddress", "§aChange address");
        public static final Msg ChangeVersion = new Msg("BookmarksScreen.ChangeVersion", "§aReselect version");
        public static final Msg ChangeVersionLore = new Msg("BookmarksScreen.ChangeVersionLore", "§bClick to change this bookmark's server version");
        public static final Msg ChangeAddressLore = new Msg("BookmarksScreen.ChangeAddressLore", "§bClick to change this bookmark's address");
        public static final Msg ChangeAddressChatInfo = new Msg("BookmarksScreen.ChangeAddressChatInfo", "§aPlease type the new server address into the chat (with optional port)");
        public static final Msg Delete = new Msg("BookmarksScreen.Delete", "§cDelete bookmark");
        public static final Msg DeleteLore = new Msg("BookmarksScreen.DeleteLore", "§cClick to delete this bookmark");
        public static final Msg Renamed = new Msg("BookmarksScreen.Renamed", "§aBookmark updated");
        public static final Msg AddressChanged = new Msg("BookmarksScreen.AddressChanged", "§aBookmark address updated");
        public static final Msg DeleteConfirmTitle = new Msg("BookmarksScreen.DeleteConfirmTitle", "§cDelete bookmark");
        public static final Msg DeleteConfirmText = new Msg("BookmarksScreen.DeleteConfirmText", "§cDelete §6{0}§c? This cannot be undone.");
        public static final Msg ConfirmDelete = new Msg("BookmarksScreen.ConfirmDelete", "§cDelete");
        public static final Msg Cancel = new Msg("BookmarksScreen.Cancel", "§7Cancel");
    }

    public static class DeleteAllScreen {
        public static final Msg Title = new Msg("DeleteAllScreen.Title", "§cDelete all data?");
        public static final Msg Confirm = new Msg("DeleteAllScreen.Confirm", "§cDelete everything");
        public static final Msg ConfirmLore = new Msg("DeleteAllScreen.ConfirmLore", "§cDeletes your account login, all bookmarks and every other stored file of yours.\n§cThis cannot be undone.");
        public static final Msg Cancel = new Msg("DeleteAllScreen.Cancel", "§7Cancel");
    }

    public static class Tutorial {
        public static final Msg Introduction = new Msg("Tutorial.Introduction", """
                §6§l§oConnectPlus

                Using ConnectPlus you can connect to any Minecraft server with any version.
                Check out the following pages to learn how to use ConnectPlus.""");
        public static final Msg ServerAddress = new Msg("Tutorial.ServerAddress", """
                §6§l§oServer address

                Click on §2Set server address§r (name tag) and enter the server address in the chat.
                Example: §2example.com§r or §2example.com:25565§r
                If no port is specified, the default port is used.""");
        public static final Msg ServerVersion = new Msg("Tutorial.ServerVersion", """
                §6§l§oServer version

                Click on §2Set protocol version§r (anvil) and select the desired version.
                §1Crafting table -> release version
                §2Furnace -> beta version
                §3Dirt -> alpha version""");
        public static final Msg Connect = new Msg("Tutorial.Connect", """
                §6§l§oConnect

                After setting the server address you can connect by clicking on §2Connect to server§r.
                The version is optional: while no version is set it is auto detected, but you can pick one via the §2Set protocol version§r (anvil) item.""");
        public static final Msg Disconnect = new Msg("Tutorial.Disconnect", """
                §6§l§oDisconnect

                Typing §2/disconnect§r in the lobby chat closes your connection to the lobby.
                After a ConnectPlus switch, §2/disconnect§r brings you back to the lobby.
                You can also open the main menu through the compass (hotbar slot 5) and disconnect there.
                All your settings will be retained until you disconnect.""");
        public static final Msg WildcardDomains = new Msg("Tutorial.WildcardDomains", """
                §6§l§oWildcard domains

                ConnectPlus supports the same wildcard domain format as ViaProxy itself.
                Example: §2example.com_25565_1.8.viaproxy.example.com
                This autofills the server address and version automatically.""");
    }

    public static class Welcome {
        public static final Msg Text = new Msg("Welcome.Text", "§aWelcome to the ConnectPlus lobby\n§7Your settings and account tokens are stored on this proxy server.");
        public static final Msg SaveOffText = new Msg("Welcome.SaveOffText", "§aWelcome to the ConnectPlus lobby\n§7Saving is off: your settings and account login only last for this session - everything is wiped when you disconnect.");
    }

    public static class TransferScreen {
        public static final Msg Title = new Msg("TransferScreen.Title", "§6Server transfer");
        public static final Msg Info = new Msg("TransferScreen.Info", "§7The server wants to move you to:§r §6{0}:{1}");
        public static final Msg Follow = new Msg("TransferScreen.Follow", "§aFollow the transfer");
        public static final Msg FollowLore = new Msg("TransferScreen.FollowLore", "§bClick to connect to {0}:{1}");
        public static final Msg Stay = new Msg("TransferScreen.Stay", "§7Stay in the lobby");
    }

    /** Console access-list messages use the configured language and ordinary pack overrides. */
    public static class ConsoleAccess {
        public static final Msg WhitelistLabel = new Msg("ConsoleAccess.WhitelistLabel", "whitelist");
        public static final Msg BlacklistLabel = new Msg("ConsoleAccess.BlacklistLabel", "blacklist");
        public static final Msg JavaLabel = new Msg("ConsoleAccess.JavaLabel", "java");
        public static final Msg BedrockLabel = new Msg("ConsoleAccess.BedrockLabel", "bedrock");
        public static final Msg UnknownName = new Msg("ConsoleAccess.UnknownName", "(unknown name)");
        public static final Msg HelpWhitelist = new Msg("ConsoleAccess.HelpWhitelist", "cp wl / cp whitelist - Manage whitelist (help|on|off|add|remove|list|reload)");
        public static final Msg HelpBlacklist = new Msg("ConsoleAccess.HelpBlacklist", "cp bl / cp blacklist - Manage blacklist (help|on|off|add|remove|list|reload)");
        public static final Msg HelpMutation = new Msg("ConsoleAccess.HelpMutation", "cp whitelist|wl|blacklist|bl add|remove java|bedrock <player name or UUID/XUID>");
        public static final Msg HelpCheck = new Msg("ConsoleAccess.HelpCheck", "cp access check <player name or UUID/XUID> - Read-only access check");
        public static final Msg RuntimeState = new Msg("ConsoleAccess.RuntimeState", "{0}: {1} (config: {2}; a restart restores the config value)");
        public static final Msg RuntimeChanged = new Msg("ConsoleAccess.RuntimeChanged", "{0}: {1} (config value unchanged)");
        public static final Msg ReloadHint = new Msg("ConsoleAccess.ReloadHint", "reload reloads the list file only (no config, no file rewrite, no binding sync)");
        public static final Msg ListUnavailable = new Msg("ConsoleAccess.ListUnavailable", "{0}: no valid list data (repair the file and run reload)");
        public static final Msg ListHeader = new Msg("ConsoleAccess.ListHeader", "{0} ({1} accounts):");
        public static final Msg Reloaded = new Msg("ConsoleAccess.Reloaded", "{0} reloaded: {1} accounts");
        public static final Msg ReloadFailed = new Msg("ConsoleAccess.ReloadFailed", "{0} reload failed, the previous list stays active: {1}");
        public static final Msg UnknownTarget = new Msg("ConsoleAccess.UnknownTarget", "no known account for \"{0}\"; add by the exact UUID/XUID to pre-add an unknown player");
        public static final Msg MultipleTargets = new Msg("ConsoleAccess.MultipleTargets", "multiple accounts match \"{0}\"; nothing was changed:");
        public static final Msg UseExactIdentifier = new Msg("ConsoleAccess.UseExactIdentifier", "repeat the command with the exact identifier");
        public static final Msg AddedToList = new Msg("ConsoleAccess.AddedToList", "Added {0} to {1}.");
        public static final Msg RemovedFromList = new Msg("ConsoleAccess.RemovedFromList", "Removed {0} from {1}.");
        public static final Msg AddFailed = new Msg("ConsoleAccess.AddFailed", "add failed for {0}: {1}");
        public static final Msg RemoveFailed = new Msg("ConsoleAccess.RemoveFailed", "remove failed for {0}: {1}");
        public static final Msg Failed = new Msg("ConsoleAccess.Failed", "failed: {0}");
        public static final Msg UnknownCheckTarget = new Msg("ConsoleAccess.UnknownCheckTarget", "no known account for \"{0}\"");
        public static final Msg CheckLine = new Msg("ConsoleAccess.CheckLine", "{0}: whitelist={1}, blacklist={2} -> {3}");
        public static final Msg CheckHint = new Msg("ConsoleAccess.CheckHint", "(identifier check against the current lists only; the real bridge availability is not tested)");
        public static final Msg Yes = new Msg("ConsoleAccess.Yes", "yes");
        public static final Msg No = new Msg("ConsoleAccess.No", "no");
        public static final Msg Unavailable = new Msg("ConsoleAccess.Unavailable", "unavailable");
        public static final Msg Allowed = new Msg("ConsoleAccess.Allowed", "allowed");
        public static final Msg Blacklisted = new Msg("ConsoleAccess.Blacklisted", "blacklisted");
        public static final Msg NotWhitelisted = new Msg("ConsoleAccess.NotWhitelisted", "not whitelisted");
        public static final Msg IdentifierUnavailable = new Msg("ConsoleAccess.IdentifierUnavailable", "identifier unavailable");
        public static final Msg ListDataUnavailable = new Msg("ConsoleAccess.ListDataUnavailable", "list data unavailable");
    }

    /** The exact access-list kick reasons (design §6), resolved with the session locale. */
    public static class AccessControl {
        public static final Msg NotWhitelisted = new Msg("AccessControl.NotWhitelisted", "You are not on the whitelist.");
        public static final Msg Blacklisted = new Msg("AccessControl.Blacklisted", "You are on the blacklist.");
        public static final Msg IdentifierUnavailable = new Msg("AccessControl.IdentifierUnavailable", "Your player identifier is temporarily unavailable. Please try again later.");
        public static final Msg ListUnavailable = new Msg("AccessControl.ListUnavailable", "The access list is temporarily unavailable. Please try again later.");
    }

    public static class Commands {

        /**
         * The chat line for a connect handoff outcome; null for STARTED (nothing to say).
         */
        public static String connectResultMessage(final Lang lang, final dev.connectplus.switching.SwitchInitiator.StartResult result) {
            return switch (result) {
                case STARTED -> null;
                case NOT_AVAILABLE -> Languages.text(lang, SwitchUnavailable);
                case REJECTED_BUSY -> Languages.text(lang, SwitchBusy);
                case REJECTED_RATE_LIMITED -> Languages.text(lang, ConnectRateLimited);
                case REJECTED_UNSUPPORTED_PROTOCOL -> Languages.text(lang, SwitchUnsupportedProtocol);
                case REJECTED_ACCESS_DENIED -> Languages.text(lang, SwitchAccessPending);
            };
        }
        public static final Msg ConnectInvalidAddress = new Msg("Commands.ConnectInvalidAddress", "§cInvalid server address");
        public static final Msg AccountLoginDisabled = new Msg("Commands.AccountLoginDisabled", "§cAccount login is disabled on this proxy");
        public static final Msg AccountLoginNotAllowed = new Msg("Commands.AccountLoginNotAllowed", "§cYour name is not on this proxy's account login allowlist");
        public static final Msg SwitchFailed = new Msg("Commands.SwitchFailed", "§cSwitching to {0} failed: {1} - sending you back to the lobby.");
        public static final Msg SwitchFailedGeneric = new Msg("Commands.SwitchFailedGeneric", "unknown reason");
        public static final Msg SwitchNeedsAccount = new Msg("Commands.SwitchNeedsAccount", "This server requires a premium (Microsoft) account. Login via the lobby GUI and try again.");
        public static final Msg SwitchUnavailable = new Msg("Commands.SwitchUnavailable", "§cThis connection cannot be switched right now. Please reconnect through the proxy.");
        public static final Msg SwitchAccessPending = new Msg("Commands.SwitchAccessPending", "§cConnectPlus cannot connect you right now (access check pending or denied).");
        public static final Msg SwitchUnsupportedProtocol = new Msg("Commands.SwitchUnsupportedProtocol", "§cYour client version is not supported for server switching yet. Please ask the administrator to update ConnectPlus.");
        public static final Msg SwitchBusy = new Msg("Commands.SwitchBusy", "§cA server switch is already in progress, please wait a moment.");
        public static final Msg ConnectRateLimited = new Msg("Commands.ConnectRateLimited", "§cToo many connect attempts, please wait a moment.");
        public static final Msg KickedByServer = new Msg("Commands.KickedByServer", "§cYou were kicked from {0}: {1} - sending you back to the lobby.");
        public static final Msg BackendDied = new Msg("Commands.BackendDied", "§cThe server {0} closed the connection - sending you back to the lobby.");
        public static final Msg ReconnectExhausted = new Msg("Commands.ReconnectExhausted", "§cThe server {0} is unreachable ({1} reconnect attempts failed) - sending you back to the lobby.");
        public static final Msg TransferConfirm = new Msg("Commands.TransferConfirm", "§6The server wants to transfer you to {0}:{1}. Return to the lobby, set the server address to §2{0}:{1}§r in the main menu and connect to follow.");
        public static final Msg DisconnectUsage = new Msg("Commands.DisconnectUsage", "§cUsage: /disconnect or /dc (no arguments)");
        public static final Msg Suggestions = new Msg("Commands.Suggestions", "/disconnect\n/dc");
        public static final Msg BmLimit = new Msg("Commands.BmLimit", "§cYou already reached the bookmark limit of {0}");
        public static final Msg BmDeleted = new Msg("Commands.BmDeleted", "§aBookmark deleted");
        public static final Msg AllDataDeleted = new Msg("Commands.AllDataDeleted", "§aAll your stored data was deleted from this proxy");
        public static final Msg BmNameExists = new Msg("Commands.BmNameExists", "§cA bookmark with this name already exists");
        public static final Msg BmNotFound = new Msg("Commands.BmNotFound", "§cNo bookmark with this name");
    }

    /**
     * The built-in Chinese mirror pack (F1.4). Every field mirrors the English
     * default one-to-one (same section/field names); MessagesTest enforces the
     * sync via reflection. The runtime pack is registered by {@link Languages}
     * and can be overridden per line through {@code languages/zh.yml}.
     */
    public static class Zh {

        public static class Hotbar {
            public static String MenuName = "§a打开主菜单";
            public static String MenuLore = "§7使用或挥动此物品打开主菜单";
            public static String DisconnectLore = "§7使用或挥动此物品断开连接";
        }

        public static class MainScreen {
            public static String Title = "§aConnectPlus";

            public static class SetServerAddress {
                public static String ItemName = "§a设置服务器地址";
                public static String ItemLore = "§b点击设置要连接的服务器地址";
                public static String ItemLoreAddressSet = "§a地址: §6{0}";
                public static String ItemLoreNoAddressSet = "§c未设置地址（必填）";
                public static String ChatInfo = "§a请在聊天栏输入服务器地址（可带端口），例如 example.com 或 example.com:25565";
                public static String ChatCancelled = "§c已取消输入";
                public static String ChatInvalidAddress = "§c无效的服务器地址";
            }
            public static class SetProtocolVersion {
                public static String ItemName = "§a设置协议版本";
                public static String ItemLore = "§b点击设置连接使用的协议版本";
                public static String ItemLoreVersionSet = "§a版本: §6{0}";
                public static String ItemLoreAutoDetect = "§7未设置版本时将自动检测协议版本";
            }
            public static class Bookmarks {
                public static String ItemName = "§a书签";
                public static String ItemLore = "§b点击打开你收藏的服务器（{0}）";
            }
            public static class OfflineMode {
                public static String ItemName = "§a离线模式";
                public static String ItemLore = "§b点击切换离线模式\n§7不会退出或删除你的微软账号登录";
                public static String ItemLoreEnabled = "§e离线模式：已开启（连接时不使用正版账号）";
                public static String ItemLoreDisabled = "§7离线模式：已关闭（已登录时使用正版账号）";
                public static String ChatEnabled = "§a已开启离线模式：连接服务器时使用离线身份，保留你的微软账号登录。";
                public static String ChatDisabled = "§a已关闭离线模式：连接服务器时使用已登录的正版账号。";
            }
            public static class ConnectToServer {
                public static String ItemName = "§a连接到服务器";
                public static String ItemLore = "§b点击连接到服务器";
                public static String ItemLoreNoAddress = "§c未设置地址（必填）";
                public static String ItemLoreMissingRequirements = "§c连接前请先完成所有设置";
            }
            public static class HowToUse {
                public static String ItemName = "§6使用说明";
            }
            public static class Login {
                public static String ItemName = "§a使用微软账号登录";
                public static String ItemLore = "§b点击登录微软账号（正版验证服务器需要）";
                public static String ItemLoreLoggedIn = "§a已登录: §6{0}";
                public static String ItemLoreNotLoggedIn = "§c未登录";
                public static String ItemLoreExpired = "§c登录已过期 - 点击重新登录";
                public static String ChatLoading = "§6正在启动微软设备码登录...";
                public static String ChatCodeLogin = """
                        §6请打开 §9{0}§r 并输入代码 §2{1}§r 完成登录。""";
                public static String ChatCodeLoginCopyHint = "§7点击上方链接即可复制到剪贴板。";
                public static String ChatLoginSuccess = "§a登录成功";
                public static String ChatLoginFailed = "§c登录失败: {0}";
                public static String ChatLogout = "§a已注销 - 存储在本代理上的账号已移除";
                public static String ChatLinkWarning = "§6关联 Java 账号后，当前基岩版档案将被清除，其中的书签不会保留。请提前做好备份。关联后将使用 Java 账号的档案；若该账号没有已有档案，将创建新档案。";
                public static String ChatLinkCommitted = "§a关联成功，当前已使用 Java 账号的档案。";
                public static String ChatLinkCommittedCleanupPending = "§a关联成功。旧基岩版档案将在后台自动清理。";
                public static String ChatLinkCommittedAccessPending = "§e关联成功。名单同步尚未完成，名单数据暂时不可用。";
                public static String ChatLinkConflict = "§c该基岩账号或 Java 账号已与其他账号关联，请先解除现有绑定后再关联。";
                public static String ChatLinkStale = "§c当前会话已变化，无法发起关联，请重新登录后再试。";
                public static String ChatLinkFailed = "§c关联失败 - 你的基岩版档案保持不变，请重试。";
            }
            public static class Unlink {
                public static String ItemName = "§c解除与 Java 账号 {0} 的绑定";
                public static String ItemLore = "§7解除后将使用基岩版独立档案，Java 账号的书签和登录信息会保留。";
                public static String FallbackName = "未知";
                public static String ChatLoading = "§6正在解除与 Java 账号的绑定...";
                public static String ChatBusy = "§6已有账号操作正在进行中，请稍候。";
                public static String ChatCommitted = "§a已解除绑定，当前已使用基岩版独立档案。";
                public static String ChatCommittedCleanupPending = "§a已解除绑定。基岩版独立档案暂时未能创建 - 当前为受限空白状态，将自动补建（清理待完成）。";
                public static String ChatStale = "§c当前会话已变化，无法发起解绑。";
                public static String ChatFailed = "§c解绑失败 - 绑定关系保持不变，请重试。";
            }

            public static class SaveLoginInfo {
                public static String ItemName = "§a保存登录信息";
                public static String ItemLore = "§b点击切换是否在本代理上保存你的账号登录信息";
                public static String ItemLoreEnabled = "§a已开启§7 - 断开连接后登录信息仍保留在本代理上";
                public static String ItemLoreDisabled = "§c已关闭§7 - 断开连接时将清除你的登录信息";
                public static String ChatEnabled = "§a已开启保存登录信息 - 重新连接后无需再次登录。";
                public static String ChatDisabled = "§6已关闭保存登录信息 - 断开与代理的连接时将清除你的所有账号登录信息。";
            }
            public static class DeleteAll {
                public static String ItemName = "§c删除所有信息";
                public static String ItemLore = "§c点击删除你在本代理上的所有存储数据（账号、书签等）";
            }
            public static class Disconnect {
                public static String ItemName = "§c断开连接";
                public static String DisconnectMessage = "手动断开连接";
            }
        }

        public static class VersionSelectorScreen {
            public static String Title = "§a选择版本";
            public static String PreviousPage = "§a上一页";
            public static String Back = "§c返回";
            public static String NextPage = "§a下一页";
        }

        public static class TutorialScreen {
            public static String Title = "ConnectPlus 教程";
        }

        public static class BookmarksScreen {
            public static String Title = "§a书签";
            public static String Empty = "§c还没有保存的书签";
            public static String ItemLore = "§7{0}";
            public static String ItemLoreAutoDetect = "§7版本: §8自动检测";
            public static String ItemLoreLastUsed = "§8上次使用: {0}";
            public static String SaveCurrent = "§a保存当前服务器";
            public static String SaveCurrentLore = "§b点击将当前设置的地址保存为书签";
            public static String SaveCurrentNoAddress = "§c未设置地址 - 请先设置服务器地址";
            public static String SaveSuccess = "§a书签已保存";
            public static String PreviousPage = "§a上一页";
            public static String Back = "§c返回";
            public static String NextPage = "§a下一页";
            public static String DetailTitle = "§a书签详情";
            public static String CurrentName = "§e当前名称：§6{0}";
            public static String CurrentVersion = "§e当前版本：§6{0}";
            public static String CurrentVersionAutoDetect = "§e当前版本：§6自动检测";
            public static String CurrentAddress = "§e当前地址：§6{0}";
            public static String Rename = "§a重命名书签";
            public static String RenameLore = "§b点击重命名此书签";
            public static String RenameChatInfo = "§a请在聊天栏输入新的书签名称";
            public static String ChangeAddress = "§a修改地址";
            public static String ChangeVersion = "§a重新选择版本";
            public static String ChangeVersionLore = "§b点击重新选择此书签的服务器版本";
            public static String ChangeAddressLore = "§b点击修改此书签的地址";
            public static String ChangeAddressChatInfo = "§a请在聊天栏输入新的服务器地址（可带端口）";
            public static String Delete = "§c删除书签";
            public static String DeleteLore = "§c点击删除此书签";
            public static String Renamed = "§a书签已更新";
            public static String AddressChanged = "§a书签地址已更新";
            public static String DeleteConfirmTitle = "§c删除书签";
            public static String DeleteConfirmText = "§c确定删除 §6{0}§c 吗？此操作不可撤销。";
            public static String ConfirmDelete = "§c删除";
            public static String Cancel = "§7取消";
        }

        public static class DeleteAllScreen {
            public static String Title = "§c删除所有信息？";
            public static String Confirm = "§c全部删除";
            public static String ConfirmLore = "§c将删除你的账号登录信息、所有书签等你存储的全部数据。\n§c此操作不可撤销。";
            public static String Cancel = "§7取消";
        }

        public static class Tutorial {
            public static String Introduction = """
                    §6§l§oConnectPlus

                    通过 ConnectPlus，你可以用任意版本连接任意 Minecraft 服务器。
                    请翻阅后面的页面了解 ConnectPlus 的用法。""";
            public static String ServerAddress = """
                    §6§l§o服务器地址

                    点击 §2设置服务器地址§r（命名牌）后在聊天栏输入服务器地址。
                    例如: §2example.com§r 或 §2example.com:25565§r
                    不指定端口时使用默认端口。""";
            public static String ServerVersion = """
                    §6§l§o服务器版本

                    点击 §2设置协议版本§r（铁砧）选择所需版本。
                    §1工作台 -> 正式版
                    §2熔炉 -> Beta 版
                    §3泥土 -> Alpha 版""";
            public static String Connect = """
                    §6§l§o连接

                    设置好服务器地址后，点击 §2连接到服务器§r 即可连接。
                    版本可以不选：未设置版本时将自动检测，也可以通过 §2设置协议版本§r（铁砧）手动指定。""";
            public static String Disconnect = """
                    §6§l§o断开连接

                    在大厅聊天栏输入 §2/disconnect§r 可断开与大厅的连接。
                    通过 ConnectPlus 切换到目标服务器后，输入 §2/disconnect§r 可返回大厅。
                    也可以使用指南针（快捷栏第 5 格）打开主菜单进行断开。
                    断开前你的所有设置都会保留。""";
            public static String WildcardDomains = """
                    §6§l§o通配符域名

                    ConnectPlus 支持与 ViaProxy 相同的通配符域名格式。
                    例如: §2example.com_25565_1.8.viaproxy.example.com
                    这样会自动填好服务器地址和版本。""";
        }

        public static class Welcome {
            public static String Text = "§a欢迎使用 ConnectPlus 大厅\n§7你的设置与账号令牌存储在本代理服务器上。";
            public static String SaveOffText = "§a欢迎使用 ConnectPlus 大厅\n§7当前未开启保存登录信息：你的设置与账号登录仅本次会话有效，断开连接即全部清除。";
        }

        public static class TransferScreen {
            public static String Title = "§6服务器转移";
            public static String Info = "§7服务器要将你移动到:§r §6{0}:{1}";
            public static String Follow = "§a跟随转移";
            public static String FollowLore = "§b点击连接到 {0}:{1}";
            public static String Stay = "§7留在大厅";
        }

        public static class ConsoleAccess {
            public static String WhitelistLabel = "白名单";
            public static String BlacklistLabel = "黑名单";
            public static String JavaLabel = "Java";
            public static String BedrockLabel = "基岩";
            public static String UnknownName = "未知玩家名";
            public static String HelpWhitelist = "cp wl / cp whitelist - 白名单管理（help|on|off|add|remove|list|reload）";
            public static String HelpBlacklist = "cp bl / cp blacklist - 黑名单管理（help|on|off|add|remove|list|reload）";
            public static String HelpMutation = "cp whitelist|wl|blacklist|bl add|remove java|bedrock <玩家名或UUID/XUID> - 增删必须指定平台";
            public static String HelpCheck = "cp access check <玩家名或UUID/XUID> - 只读查询名单判断";
            public static String RuntimeState = "{0}当前状态: {1}（配置文件: {2}；重启后恢复配置文件状态）";
            public static String RuntimeChanged = "{0}当前状态: {1}（未修改配置文件）";
            public static String ReloadHint = "reload 仅重新加载名单文件，不重载配置、不写回文件、不联动绑定账号";
            public static String ListUnavailable = "{0}: 名单数据不可用，请修复文件后执行 reload";
            public static String ListHeader = "{0}（{1} 个账号）:";
            public static String Reloaded = "{0}已重新加载: {1} 个账号";
            public static String ReloadFailed = "{0}重新加载失败，保留上一份有效名单: {1}";
            public static String UnknownTarget = "未找到目标账号：{0}；未知玩家请使用准确的 UUID/XUID 预添加";
            public static String MultipleTargets = "多个账号匹配“{0}”，本次未修改名单:";
            public static String UseExactIdentifier = "请使用准确的 UUID/XUID 重新执行指令";
            public static String AddedToList = "已将 {0} 添加到{1}";
            public static String RemovedFromList = "已从{1}移除 {0}";
            public static String AddFailed = "添加失败，目标 {0}: {1}";
            public static String RemoveFailed = "移除失败，目标 {0}: {1}";
            public static String Failed = "命令执行失败: {0}";
            public static String UnknownCheckTarget = "未找到目标账号：{0}";
            public static String CheckLine = "{0}: 白名单={1}，黑名单={2} -> {3}";
            public static String CheckHint = "（仅按当前名单检查标识，不检测实际桥接可用性）";
            public static String Yes = "是";
            public static String No = "否";
            public static String Unavailable = "不可用";
            public static String Allowed = "允许连接";
            public static String Blacklisted = "命中黑名单";
            public static String NotWhitelisted = "不在白名单中";
            public static String IdentifierUnavailable = "玩家标识暂时不可用";
            public static String ListDataUnavailable = "名单数据暂时不可用";
        }

        public static class AccessControl {
            public static String NotWhitelisted = "你不在白名单中";
            public static String Blacklisted = "你在黑名单中";
            public static String IdentifierUnavailable = "暂时无法获取你的玩家标识，请稍后重试";
            public static String ListUnavailable = "名单数据暂时不可用，请稍后重试";
        }

        public static class Commands {
            public static String ConnectInvalidAddress = "§c无效的服务器地址";
            public static String AccountLoginDisabled = "§c本代理已禁用账号登录";
            public static String AccountLoginNotAllowed = "§c你不在本代理的账号登录允许名单中";
            public static String SwitchFailed = "§c切换到 {0} 失败: {1} - 正在将你送回大厅。";
            public static String SwitchFailedGeneric = "未知原因";
            public static String SwitchNeedsAccount = "该服务器需要正版（微软）账号。请先在大厅界面登录，再重试连接。";
            public static String SwitchUnavailable = "§c当前连接无法热切换，请通过代理重新连接。";
            public static String SwitchAccessPending = "§c当前暂时无法连接：名单检查未完成或被拒绝。";
            public static String SwitchUnsupportedProtocol = "§c当前客户端版本暂不支持切服，请联系管理员更新 ConnectPlus。";
            public static String SwitchBusy = "§c正在进行服务器切换，请稍候。";
            public static String ConnectRateLimited = "§c连接尝试过于频繁，请稍候再试。";
            public static String KickedByServer = "§c你被 {0} 踢出: {1} - 正在将你送回大厅。";
            public static String BackendDied = "§c服务器 {0} 断开了连接 - 正在将你送回大厅。";
            public static String ReconnectExhausted = "§c无法连接到服务器 {0}（重连 {1} 次均失败）- 正在将你送回大厅。";
            public static String TransferConfirm = "§6服务器要将你转移至 {0}:{1}。回到大厅后在主菜单中把服务器地址设为 §2{0}:{1}§r 并连接即可跟随。";
            public static String DisconnectUsage = "§c用法: /disconnect 或 /dc（不带参数）";
            public static String Suggestions = "/disconnect\n/dc";
            public static String BmLimit = "§c你已达到书签数量上限（{0}）";
            public static String BmDeleted = "§a书签已删除";
            public static String AllDataDeleted = "§a你在本代理存储的所有数据已删除";
            public static String BmNameExists = "§c同名书签已存在";
            public static String BmNotFound = "§c没有找到该名称的书签";
        }
    }

}
