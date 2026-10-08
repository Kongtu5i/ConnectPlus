package dev.connectplus.lobby.screen.impl;

import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Items;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.Msg;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Screen;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * The deletion confirmation for ALL stored player data: the destructive action
 * requires a second, explicit click. Confirm wipes the player's data file
 * (account login, bookmarks, settings) and logs the session out; cancel —
 * by button or by closing the container — returns to the main screen.
 */
public class DeleteAllConfirmScreen extends Screen {

    private final Lang lang;

    public DeleteAllConfirmScreen(final Lang lang) {
        super(new StringComponent(Languages.text(lang, Messages.DeleteAllScreen.Title)), 3);
        this.lang = lang;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        itemList.set(11, item(Items.TNT).named(new StringComponent(this.t(Messages.DeleteAllScreen.Confirm)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.DeleteAllScreen.ConfirmLore)))).get(),
                () -> this.deleteAll(screenHandler.getStateHandler().getHandler().getSession(), screenHandler));
        itemList.set(15, item(Items.ARROW).named(new StringComponent(this.t(Messages.DeleteAllScreen.Cancel))).get(),
                () -> screenHandler.openScreen(new MainScreen(this.lang)));
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        //Closing the container counts as cancel
        screenHandler.openScreen(new MainScreen(this.lang));
    }

    /**
     * The destructive confirm click. Takes the session explicitly (same seam as
     * {@code BookmarkDeleteConfirmScreen#delete}) so the lease-gate pinning
     * tests can call it against a rigged handler.
     */
    void deleteAll(final PlayerSession session, final ScreenHandler screenHandler) {
        final dev.connectplus.lobby.LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
        //§6 (final fix): the destructive click is lease-gated like its entry item —
        //a displaced session whose GUI is still alive during the bounded exit window
        //must not delete the profile file the arriving holder is about to restore,
        //and a session whose protected load is still in flight must not lazily
        //create a wire-uuid scratch profile below.
        if (!AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
            sendChatLines(screenHandler, Languages.text(this.lang, Messages.MainScreen.Login.ChatLinkStale));
            screenHandler.openScreen(new MainScreen(this.lang));
            return;
        }
        session.account = null;
        session.serverAddress = null;
        session.targetVersion = null;
        session.playerData = new PlayerData(session.uuid);
        handler.getPlayerStore().delete(session.uuid);
        sendChatLines(screenHandler, Languages.text(this.lang, Messages.Commands.AllDataDeleted));
        screenHandler.openScreen(new MainScreen(this.lang));
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
