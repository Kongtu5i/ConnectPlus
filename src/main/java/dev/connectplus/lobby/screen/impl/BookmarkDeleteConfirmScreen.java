package dev.connectplus.lobby.screen.impl;

import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Items;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.Msg;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Screen;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.session.Bookmark;
import dev.connectplus.session.PlayerSession;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * The deletion confirmation for a bookmark (M6 F1.2): the destructive action
 * requires a second, explicit click. Confirm removes the bookmark from the
 * persisted player data; cancel returns to the detail screen.
 */
public class BookmarkDeleteConfirmScreen extends Screen {

    private final Lang lang;
    private final String bookmarkName;
    private final int page;

    public BookmarkDeleteConfirmScreen(final Lang lang, final String bookmarkName, final int page) {
        super(new StringComponent(Languages.text(lang, Messages.BookmarksScreen.DeleteConfirmTitle)), 3);
        this.lang = lang;
        this.bookmarkName = bookmarkName;
        this.page = page;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        final PlayerSession session = screenHandler.getStateHandler().getHandler().getSession();
        final Bookmark bookmark = session.playerData == null ? null
                : session.playerData.bookmarks.stream().filter(bm -> bm.name.equals(this.bookmarkName)).findFirst().orElse(null);
        if (bookmark == null) {
            screenHandler.openScreen(new BookmarksScreen(this.lang, this.page));
            return;
        }

        itemList.set(11, item(Items.BARRIER).named(new StringComponent(this.t(Messages.BookmarksScreen.ConfirmDelete)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.BookmarksScreen.DeleteConfirmText), this.bookmarkName))).get(),
                () -> this.delete(session, screenHandler));
        itemList.set(15, item(Items.ARROW).named(new StringComponent(this.t(Messages.BookmarksScreen.Cancel))).get(),
                () -> screenHandler.openScreen(new BookmarkDetailScreen(this.lang, this.bookmarkName, this.page)));
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        //Closing the container counts as cancel
        screenHandler.openScreen(new BookmarksScreen(this.lang, this.page));
    }

    void delete(final PlayerSession session, final ScreenHandler screenHandler) {
        final dev.connectplus.lobby.LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
        //§6 bookmark write: a displaced session's confirmation click must not rewrite
        //the profile the new holder now owns (the click can land during the bounded
        //exit window).
        if (session.playerData != null && AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
            session.playerData.bookmarks.removeIf(bm -> bm.name.equals(this.bookmarkName));
            handler.getPlayerStore().save(session.playerData);
        }
        sendChatLines(screenHandler, Languages.text(this.lang, Messages.Commands.BmDeleted));
        screenHandler.openScreen(new BookmarksScreen(this.lang, this.page));
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
