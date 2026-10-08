package dev.connectplus.lobby.screen.impl;

import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.LobbyServerHandler;
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
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * The paged bookmark list: left-clicking opens the detail screen (connect /
 * rename / change address / delete with confirmation); slot 51 upserts the currently
 * set address as a bookmark. The texts follow the player's language (F1.4).
 * Deletion is also available from the bookmark detail screen (delete button).
 */
public class BookmarksScreen extends Screen {

    private static final int PAGE_SIZE = 45;

    /**
     * The result of {@link #saveCurrent}.
     */
    public enum SaveResult {SAVED, LIMIT_REACHED, NO_ADDRESS, REFUSED_STALE_SESSION}

    private final Lang lang;
    private final int page;

    public BookmarksScreen(final Lang lang, final int page) {
        super(new StringComponent(Languages.text(lang, Messages.BookmarksScreen.Title)), 6);
        this.lang = lang;
        this.page = page;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        final PlayerSession session = screenHandler.getStateHandler().getHandler().getSession();
        final LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
        final List<Bookmark> bookmarks = new ArrayList<>(session.playerData != null ? session.playerData.bookmarks : List.of());
        bookmarks.sort(Comparator.comparingLong((Bookmark b) -> b.lastConnectedAt).thenComparingLong(b -> b.createdAt).reversed());

        final int pages = Math.max(1, (bookmarks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int page = Math.min(Math.max(this.page, 0), pages - 1);

        if (bookmarks.isEmpty()) {
            itemList.set(22, item(Items.BARRIER).named(new StringComponent(this.t(Messages.BookmarksScreen.Empty))).get());
        } else {
            for (int i = 0; i < PAGE_SIZE; i++) {
                final int index = page * PAGE_SIZE + i;
                if (index >= bookmarks.size()) {
                    break;
                }
                final Bookmark bookmark = bookmarks.get(index);
                itemList.set(i, this.bookmarkItem(bookmark),
                        () -> screenHandler.openScreen(new BookmarkDetailScreen(this.lang, bookmark.name, page)));
            }
        }

        if (page > 0) {
            itemList.set(45, item(Items.ARROW).named(new StringComponent(this.t(Messages.BookmarksScreen.PreviousPage))).get(), () -> screenHandler.openScreen(new BookmarksScreen(this.lang, page - 1)));
        }
        itemList.set(49, item(Items.ENDER_PEARL).named(new StringComponent(this.t(Messages.BookmarksScreen.Back))).get(), () -> screenHandler.openScreen(new MainScreen(this.lang)));
        itemList.set(51, item(Items.WRITABLE_BOOK).named(new StringComponent(this.t(Messages.BookmarksScreen.SaveCurrent))).calculate(builder ->
                builder.lore(Messages.format(this.t(Messages.BookmarksScreen.SaveCurrentLore)))
        ).get(), () -> {
            final SaveResult result = saveCurrent(session, handler);
            switch (result) {
                case SAVED -> sendChatLine(screenHandler, this.t(Messages.BookmarksScreen.SaveSuccess));
                case LIMIT_REACHED -> sendChatLine(screenHandler, this.t(Messages.Commands.BmLimit), CPConfig.maxBookmarksPerPlayer);
                case NO_ADDRESS -> sendChatLine(screenHandler, this.t(Messages.BookmarksScreen.SaveCurrentNoAddress));
            }
            if (result != SaveResult.NO_ADDRESS) {
                screenHandler.openScreen(new BookmarksScreen(this.lang, page));
            }
        });
        if (page < pages - 1) {
            itemList.set(53, item(Items.ARROW).named(new StringComponent(this.t(Messages.BookmarksScreen.NextPage))).get(), () -> screenHandler.openScreen(new BookmarksScreen(this.lang, page + 1)));
        }
    }

    /**
     * Upserts the session's current address as a bookmark named after the
     * address; an existing bookmark keeps its createdAt. Persisted immediately.
     */
    public static SaveResult saveCurrent(final PlayerSession session, final LobbyServerHandler handler) {
        return saveNamed(session, handler, session.serverAddress);
    }

    /**
     * Upserts the session's current address as a bookmark with the given name;
     * an existing bookmark with that name keeps its createdAt. Persisted immediately.
     *
     * <p>Task 6 §6 (bookmark save): a displaced session's GUI can still be alive
     * during the bounded exit window — its clicks must not write the profile the
     * new holder now owns, so the lease currency is validated before any change.
     */
    public static SaveResult saveNamed(final PlayerSession session, final LobbyServerHandler handler, final String name) {
        if (session.serverAddress == null || name == null || name.isEmpty()) {
            return SaveResult.NO_ADDRESS;
        }
        //§6 (final fix): the guard covers the displacement AND the protected-load
        //window — a proxied session with neither lease nor data must not lazily
        //create a wire-uuid scratch profile below (the landing would refuse it).
        if (!AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
            return SaveResult.REFUSED_STALE_SESSION;
        }
        if (session.playerData == null) {
            session.playerData = new PlayerData(session.uuid);
        }
        final String versionName = session.targetVersion != null ? session.targetVersion.getName() : null;
        final long now = System.currentTimeMillis();
        for (final Bookmark bookmark : session.playerData.bookmarks) {
            if (bookmark.name.equals(name)) {
                bookmark.address = session.serverAddress;
                bookmark.versionName = versionName;
                bookmark.lastConnectedAt = now;
                handler.getPlayerStore().save(session.playerData);
                return SaveResult.SAVED;
            }
        }
        if (session.playerData.bookmarks.size() >= CPConfig.maxBookmarksPerPlayer) {
            return SaveResult.LIMIT_REACHED;
        }
        session.playerData.bookmarks.add(new Bookmark(name, session.serverAddress, versionName, now, now));
        handler.getPlayerStore().save(session.playerData);
        return SaveResult.SAVED;
    }

    private Item bookmarkItem(final Bookmark bookmark) {
        final ProtocolVersion version = bookmark.versionName == null ? null : VersionNames.find(bookmark.versionName);
        return item(Items.WRITTEN_BOOK).named(new StringComponent(bookmark.name)).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLore), bookmark.address));
            if (version != null) {
                builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLore), version.getName()));
            } else {
                builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLoreAutoDetect)));
            }
            if (bookmark.lastConnectedAt > 0) {
                builder.lore(Messages.format(this.t(Messages.BookmarksScreen.ItemLoreLastUsed), Instant.ofEpochMilli(bookmark.lastConnectedAt).toString().substring(0, 10)));
            }
        }).get();
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        //Closing the container (E key) returns to the main screen, like the version selector
        screenHandler.openScreen(new MainScreen(this.lang));
    }

    /**
     * Resolves one text against this screen's language.
     */
    String t(final Msg msg) {
        return Languages.text(this.lang, msg);
    }

    private static void sendChatLine(final ScreenHandler screenHandler, final String message, final Object... args) {
        for (final TextComponent component : Messages.format(message, args)) {
            screenHandler.getStateHandler().send(new dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket(component, false));
        }
    }

}
