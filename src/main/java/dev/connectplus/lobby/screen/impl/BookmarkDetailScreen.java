package dev.connectplus.lobby.screen.impl;

import com.google.common.net.HostAndPort;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.ConnectFlow;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Items;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.Msg;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Screen;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.routing.VersionNames;
import dev.connectplus.session.Bookmark;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.utils.TargetAddressGuard;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;

import java.time.Instant;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * The per-bookmark management screen (M6 F1.2): shows the bookmark details and
 * offers rename (chat input), version selection, address change (chat input)
 * deletion via confirmation, and a separate connection button. Opened by
 * left-clicking a bookmark in the list on both Java and Bedrock clients.
 */
public class BookmarkDetailScreen extends Screen {

    private final Lang lang;
    private final String bookmarkName;
    private final int page;

    public BookmarkDetailScreen(final Lang lang, final String bookmarkName, final int page) {
        super(new StringComponent(Languages.text(lang, Messages.BookmarksScreen.DetailTitle)), 6);
        this.lang = lang;
        this.bookmarkName = bookmarkName;
        this.page = page;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        final PlayerSession session = screenHandler.getStateHandler().getHandler().getSession();
        final Bookmark bookmark = this.find(session);
        if (bookmark == null) {
            //Deleted meanwhile (e.g. from another connection's detail screen): fall back to the list
            screenHandler.openScreen(new BookmarksScreen(this.lang, this.page));
            return;
        }

        itemList.set(19, this.infoItem(bookmark));
        itemList.set(21, item(Items.NAMETAG)
                .named(Messages.format(this.t(Messages.BookmarksScreen.CurrentName), bookmark.name)[0])
                .lore(Messages.format(this.t(Messages.BookmarksScreen.Rename)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.BookmarksScreen.RenameLore)))).get(),
                () -> this.startRename(session, screenHandler));
        final TextComponent currentVersion = bookmark.versionName == null
                ? Messages.format(this.t(Messages.BookmarksScreen.CurrentVersionAutoDetect))[0]
                : Messages.format(this.t(Messages.BookmarksScreen.CurrentVersion), bookmark.versionName)[0];
        itemList.set(22, item(Items.ANVIL).named(currentVersion)
                .lore(Messages.format(this.t(Messages.BookmarksScreen.ChangeVersion)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ChangeVersionLore)))).get(),
                () -> this.startVersionChange(session, screenHandler));
        itemList.set(23, item(Items.WRITABLE_BOOK)
                .named(Messages.format(this.t(Messages.BookmarksScreen.CurrentAddress), bookmark.address)[0])
                .lore(Messages.format(this.t(Messages.BookmarksScreen.ChangeAddress)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ChangeAddressLore)))).get(),
                () -> this.startAddressChange(session, screenHandler));
        itemList.set(53, item(Items.BARRIER).named(new StringComponent(this.t(Messages.BookmarksScreen.Delete)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.BookmarksScreen.DeleteLore)))).get(),
                () -> screenHandler.openScreen(new BookmarkDeleteConfirmScreen(this.lang, this.bookmarkName, this.page)));
        // ScreenHandler isolates each window, so a queued list click at this
        // same slot cannot activate the new detail window's connection button.
        itemList.set(25, item(Items.OAK_DOOR).named(new StringComponent(this.t(Messages.MainScreen.ConnectToServer.ItemName)))
                .lore(Messages.format(this.t(Messages.MainScreen.ConnectToServer.ItemLore))).get(),
                () -> this.connectTo(session, screenHandler));
        itemList.set(49, item(Items.ENDER_PEARL).named(new StringComponent(this.t(Messages.BookmarksScreen.Back))).get(),
                () -> screenHandler.openScreen(new BookmarksScreen(this.lang, this.page)));
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        screenHandler.openScreen(new BookmarksScreen(this.lang, this.page));
    }

    /**
     * The bookmark of this screen, looked up live from the session data so a
     * rename is visible on the next open.
     */
    private Bookmark find(final PlayerSession session) {
        if (session.playerData == null) {
            return null;
        }
        return session.playerData.bookmarks.stream().filter(bm -> bm.name.equals(this.bookmarkName)).findFirst().orElse(null);
    }

    private void connectTo(final PlayerSession session, final ScreenHandler screenHandler) {
        // Read live data: editing or deleting the bookmark must not leave a
        // connection button that targets an outdated captured address/version.
        final Bookmark bookmark = this.find(session);
        if (bookmark == null) {
            screenHandler.openScreen(new BookmarksScreen(this.lang, this.page));
            return;
        }
        final LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
        session.serverAddress = bookmark.address;
        session.targetVersion = bookmark.versionName == null ? null : VersionNames.find(bookmark.versionName);
        // A displaced session must not persist another owner's usage timestamp.
        if (AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
            bookmark.lastConnectedAt = System.currentTimeMillis();
            handler.getPlayerStore().save(session.playerData);
        }
        final var result = ConnectFlow.start(session, screenHandler.getStateHandler(), handler.getSwitchInitiator());
        if (dev.connectplus.switching.SwitchInitiator.StartResult.STARTED != result) {
            final String message = Messages.Commands.connectResultMessage(this.lang, result);
            if (message != null) {
                for (final TextComponent component : Messages.format(message)) {
                    screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                }
            }
            if (result != dev.connectplus.switching.SwitchInitiator.StartResult.REJECTED_UNSUPPORTED_PROTOCOL) {
                screenHandler.openScreen(new MainScreen(this.lang));
            }
        }
    }

    private Item infoItem(final Bookmark bookmark) {
        final ProtocolVersion version = bookmark.versionName == null ? null : VersionNames.find(bookmark.versionName);
        return item(Items.WRITTEN_BOOK).named(new StringComponent(bookmark.name)).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLore), bookmark.address));
            if (version != null) {
                builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLore), version.getName()));
            } else {
                builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLoreAutoDetect)));
            }
            if (bookmark.lastConnectedAt > 0) {
                builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLoreLastUsed),
                        Instant.ofEpochMilli(bookmark.lastConnectedAt).toString().substring(0, 10)));
            }
        }).get();
    }

    private void startRename(final PlayerSession session, final ScreenHandler screenHandler) {
        screenHandler.closeScreen();
        sendChatLines(screenHandler, this.t(Messages.BookmarksScreen.RenameChatInfo));
        final LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
        session.chatListener = input -> {
            final String newName = input.trim();
            if (newName.isEmpty() || newName.equals(this.bookmarkName)) {
                screenHandler.openScreen(this.replacement());
                return true;
            }
            final boolean duplicate = session.playerData != null && session.playerData.bookmarks.stream()
                    .anyMatch(bm -> bm.name.equals(newName));
            if (duplicate) {
                sendChatLines(screenHandler, this.t(Messages.Commands.BmNameExists));
                screenHandler.openScreen(this.replacement());
                return true;
            }
            final Bookmark bookmark = this.find(session);
            //§6 bookmark write: a displaced session's chat input must not rewrite
            //the profile the new holder now owns.
            if (bookmark != null && AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
                bookmark.name = newName;
                handler.getPlayerStore().save(session.playerData);
                sendChatLines(screenHandler, this.t(Messages.BookmarksScreen.Renamed));
            }
            screenHandler.openScreen(new BookmarkDetailScreen(this.lang, newName, this.page));
            return true;
        };
    }

    private void startAddressChange(final PlayerSession session, final ScreenHandler screenHandler) {
        screenHandler.closeScreen();
        sendChatLines(screenHandler, this.t(Messages.BookmarksScreen.ChangeAddressChatInfo));
        final LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
        session.chatListener = input -> {
            final String address = dev.connectplus.session.ServerAddress.normalize(input);
            final Bookmark bookmark = this.find(session);
            //§6 bookmark write: the lease-currency guard (see startRename).
            final boolean leaseCurrent = AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter());
            if (bookmark != null && leaseCurrent && BookmarkDetailScreen.validAddress(address)) {
                bookmark.address = address;
                handler.getPlayerStore().save(session.playerData);
                sendChatLines(screenHandler, this.t(Messages.BookmarksScreen.AddressChanged));
                screenHandler.openScreen(this.replacement());
            } else {
                if (bookmark != null) {
                    sendChatLines(screenHandler, this.t(Messages.MainScreen.SetServerAddress.ChatInvalidAddress));
                }
                screenHandler.openScreen(this.replacement());
            }
            return true;
        };
    }

    private void startVersionChange(final PlayerSession session, final ScreenHandler screenHandler) {
        screenHandler.openScreen(new VersionSelectorScreen(this.lang, 0, () -> {
            final Bookmark bookmark = this.find(session);
            return bookmark == null || bookmark.versionName == null ? null : VersionNames.find(bookmark.versionName);
        }, version -> {
            final LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
            final Bookmark bookmark = this.find(session);
            //§6 bookmark write: the lease-currency guard (see startRename).
            if (bookmark != null && AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
                bookmark.versionName = version.getName();
                handler.getPlayerStore().save(session.playerData);
            }
        }, this::replacement));
    }

    /**
     * A detail screen reopening on the (possibly renamed) bookmark; used after
     * chat inputs where {@code this} was unregistered with the closed container.
     */
    private BookmarkDetailScreen replacement() {
        return new BookmarkDetailScreen(this.lang, this.bookmarkName, this.page);
    }

    /**
     * The address rule shared with the main screen: a parseable host[:port]
     * that is not this machine.
     */
    static boolean validAddress(final String input) {
        try {
            final HostAndPort hostAndPort = HostAndPort.fromString(input);
            if (hostAndPort.getHost().isBlank()) return false;
            return TargetAddressGuard.isAllowedHost(hostAndPort.getHost());
        } catch (final RuntimeException t) {
            return false;
        }
    }

    private String t(final Msg msg) {
        return Languages.text(this.lang, msg);
    }

    private static void sendChatLines(final ScreenHandler screenHandler, final String message) {
        for (final TextComponent component : Messages.format(message)) {
            screenHandler.getStateHandler().send(new dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket(component, false));
        }
    }

}
