package dev.connectplus.lobby.screen.impl;

import dev.connectplus.compat.AccountLoginPolicy;

import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Items;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.Msg;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Screen;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.switching.SwitchEngine;
import dev.connectplus.switching.SwitchInitiator;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * The confirmation for a backend transfer (M7, F7.1): the old server asked the
 * proxy to move the player elsewhere; the default confirm policy brings the
 * player back into the lobby with this screen so the move is a deliberate
 * click (a hostile server must not be able to redirect players silently).
 * Follow starts a hot switch to the announced target; staying returns to the
 * main screen. Closing the container counts as staying.
 */
public class TransferConfirmScreen extends Screen {

    private final Lang lang;
    private final String host;
    private final int port;

    public TransferConfirmScreen(final Lang lang, final String host, final int port) {
        super(new StringComponent(Languages.text(lang, Messages.TransferScreen.Title)), 3);
        this.lang = lang;
        this.host = host;
        this.port = port;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        itemList.set(13, item(Items.BOOK).named(new StringComponent(this.t(Messages.TransferScreen.Info)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.TransferScreen.Info), this.host, this.port))).get());

        itemList.set(11, item(Items.ENDER_PEARL).named(new StringComponent(this.t(Messages.TransferScreen.Follow)))
                .calculate(builder -> builder.lore(Messages.format(this.t(Messages.TransferScreen.FollowLore), this.host, this.port))).get(),
                () -> this.follow(screenHandler));
        itemList.set(15, item(Items.ARROW).named(new StringComponent(this.t(Messages.TransferScreen.Stay))).get(),
                () -> screenHandler.openScreen(new MainScreen(this.lang)));
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        //Closing the container counts as staying
        screenHandler.openScreen(new MainScreen(this.lang));
    }

    /**
     * Starts the hot switch to the announced target. The address rule matches the
     * one a player entering an address by hand is subject to (a local address — e.g.
     * the proxy host itself — is rejected), so the GUI grants no new reachability.
     * The engine's outcome is chatted; a started switch reopens the main screen
     * for the case the switch falls back into the lobby.
     */
    private void follow(final ScreenHandler screenHandler) {
        final String address = SwitchEngine.transferAddress(this.host, this.port);
        final PlayerSession session = screenHandler.getStateHandler().getHandler().getSession();
        if (session == null) {
            return;
        }
        if (!BookmarkDetailScreen.validAddress(address)) {
            this.chat(screenHandler, this.t(Messages.Commands.ConnectInvalidAddress));
            screenHandler.openScreen(new MainScreen(this.lang));
            return;
        }
        final SwitchInitiator.StartResult result =
                screenHandler.getStateHandler().getHandler().getSwitchInitiator().startSwitchFromLobby(
                        screenHandler.getStateHandler().getChannel(),
                        new dev.connectplus.session.ConnectionInfo(address, null, session.uuid, session.offlineMode(), session.connectionId),
                        AccountLoginPolicy.isAllowedForSession(session) && !session.offlineMode()
                                && AccountFlow.isLeaseCurrent(session, screenHandler.getStateHandler().getHandler().getLeaseGranter())
                                ? session.account : null,
                        session.name);
        final String message = Messages.Commands.connectResultMessage(this.lang, result);
        if (message != null) {
            this.chat(screenHandler, message);
        }
        if (result == SwitchInitiator.StartResult.STARTED) {
            screenHandler.openScreen(new MainScreen(this.lang));
        }
    }

    private String t(final Msg msg) {
        return Languages.text(this.lang, msg);
    }

    private void chat(final ScreenHandler screenHandler, final String message) {
        for (final TextComponent component : Messages.format(message)) {
            screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
        }
    }
}
