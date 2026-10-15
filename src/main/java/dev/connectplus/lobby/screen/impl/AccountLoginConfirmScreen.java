package dev.connectplus.lobby.screen.impl;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.compat.AccountLoginPolicy;
import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Items;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.Msg;
import dev.connectplus.lobby.screen.Screen;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.SessionLease;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;

import java.util.function.Consumer;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/** Replacing a signed-in account requires a separate, explicit confirmation. */
public class AccountLoginConfirmScreen extends Screen {
    private final Lang lang;
    private final PlayerSession session;
    private final CPAccount account;
    private final PlayerData data;
    private final SessionLease lease;
    private final Consumer<ScreenHandler> startLogin;
    private boolean confirmed;

    public AccountLoginConfirmScreen(final Lang lang, final PlayerSession session) {
        this(lang, session, screens -> AccountFlow.startLogin(session, screens, screens.getStateHandler().getHandler()));
    }

    AccountLoginConfirmScreen(final Lang lang, final PlayerSession session, final Consumer<ScreenHandler> startLogin) {
        super(new StringComponent(Languages.text(lang, Messages.AccountLoginConfirmScreen.Title)), 3);
        this.lang = lang;
        this.session = session;
        this.account = session.account;
        this.data = session.playerData;
        this.lease = session.lease;
        this.startLogin = startLogin;
    }

    @Override
    public void init(final ScreenHandler screens, final ItemList items) {
        if (!this.matchesCurrentAccount(screens)) {
            screens.openScreen(new MainScreen(this.lang));
            return;
        }
        items.set(11, item(Items.TRIAL_KEY)
                .named(new StringComponent(Languages.text(this.lang, Messages.AccountLoginConfirmScreen.Confirm)))
                .lore(Messages.format(Languages.text(this.lang, Messages.AccountLoginConfirmScreen.Warning))).get(),
                () -> this.confirm(screens));
        items.set(15, item(Items.ARROW)
                .named(new StringComponent(Languages.text(this.lang, Messages.AccountLoginConfirmScreen.Back))).get(),
                () -> screens.openScreen(new MainScreen(this.lang)));
    }

    @Override
    public void close(final ScreenHandler screens) {
        screens.openScreen(new MainScreen(this.lang));
    }

    private boolean matchesCurrentAccount(final ScreenHandler screens) {
        return this.account != null && screens.getStateHandler().getHandler().getSession() == this.session
                && this.session.account == this.account && this.session.playerData == this.data
                && this.session.lease == this.lease;
    }

    private void confirm(final ScreenHandler screens) {
        synchronized (this.session) {
            if (this.confirmed || screens.getCurrentScreen() != this) return;
            final var handler = screens.getStateHandler().getHandler();
            if (!screens.getStateHandler().getChannel().isActive()
                    || this.session.lobbyChannel != screens.getStateHandler().getChannel()) return;
            if (!this.matchesCurrentAccount(screens)
                    || !AccountFlow.isLeaseCurrent(this.session, handler.getLeaseGranter())) {
                this.send(screens, Messages.AccountLoginConfirmScreen.Stale);
                screens.openScreen(new MainScreen(this.lang));
                return;
            }
            if (!AccountLoginPolicy.isAllowedForSession(this.session)) {
                this.send(screens, Messages.Commands.AccountLoginDisabled);
                return;
            }
            if (this.session.loginInProgress || handler.isAccountRefreshing()) {
                this.send(screens, Messages.MainScreen.Unlink.ChatBusy);
                return;
            }
            if (!AccountFlow.allowlisted(this.session)) {
                this.send(screens, Messages.Commands.AccountLoginNotAllowed);
                return;
            }
            if (AccountFlow.logoutSafely(this.session, screens, handler)) {
                this.confirmed = true;
                this.startLogin.accept(screens);
            }
        }
    }

    private void send(final ScreenHandler screens, final Msg message) {
        for (final TextComponent line : Messages.format(Languages.text(this.lang, message))) {
            screens.getStateHandler().send(new dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket(line, false));
        }
    }
}
