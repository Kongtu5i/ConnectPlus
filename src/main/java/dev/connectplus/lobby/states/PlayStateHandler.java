package dev.connectplus.lobby.states;

import dev.connectplus.logging.DebugLog;
import dev.connectplus.commands.LobbyCommands;
import dev.connectplus.CoreMain;
import dev.connectplus.lobby.LobbyConstants;
import dev.connectplus.lobby.LobbyNotices;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.protocol.packets.play.S2CKeepAlivePacket;
import dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.lobby.screen.impl.MainScreen;
import dev.connectplus.session.PlayerSession;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket;


/**
 * Derived from MiniConnect's PlayStateHandler (MIT, Copyright (c) 2024 Lenni0451).
 * M2: after the join, the welcome chat is sent and the main screen opens; incoming
 * chat/command packets are first offered to the session's pending chat listener
 * (returning true consumes it), then run through the lobby command chain
 * ({@code /disconnect}, {@code /dc}; every other line is ignored).
 * M6: texts are resolved in the player's language (client settings locale, F1.4);
 * switch notices posted by the engine as bilingual template pairs are formatted here.
 */
public class PlayStateHandler extends StateHandler {

    private ScreenHandler screenHandler;
    private dev.connectplus.lobby.LobbyHotbar hotbar;
    private int nextTeleportId = 1;
    private long nextPositionCorrectionAt;

    public PlayStateHandler(final LobbyServerHandler handler, final Channel channel) {
        super(handler, channel);

        this.init();
    }

    private void init() {
        this.send(new S2CLoginPacket(0, false, 1, 1, 1, false, false, false, LobbyConstants.DEFAULT_SPAWN_INFO, false));
        this.send(new net.raphimc.netminecraft.packet.UnknownPacket(
                net.raphimc.netminecraft.constants.MCPackets.S2C_COMMANDS.getId(dev.connectplus.lobby.LobbyProtocol.VERSION.getVersion()),
                dev.connectplus.commands.ExitCommandTree.lobby()));
        LobbyConstants.sendSpawnInfo(this);
        final Lang lang = this.lang();
        //The storage notice follows the save-login toggle: an unsaved login is only
        //told about the wipe-on-disconnect behavior it actually gets
        final dev.connectplus.session.PlayerSession joinedSession = this.handler.getSession();
        final boolean saving = joinedSession == null || joinedSession.playerData == null || joinedSession.playerData.saveLoginInfo;
        for (final TextComponent component : Messages.format(Languages.text(lang, saving ? Messages.Welcome.Text : Messages.Welcome.SaveOffText))) {
            this.send(new S2CSystemChatPacket(component, false));
        }
        this.sendSwitchNotices();

        this.screenHandler = new ScreenHandler(this, () -> this.hotbar.refresh());
        this.hotbar = new dev.connectplus.lobby.LobbyHotbar(this, this.screenHandler);
        // Repair predicted inventory before a GUI click can disconnect or switch.
        this.handlerManager.register(this.hotbar);
        this.handlerManager.register(this.screenHandler);
        final dev.connectplus.lobby.LobbyTransfers.Pending transfer = this.handler.getSession() == null ? null
                : dev.connectplus.lobby.LobbyTransfers.consume(this.handler.getSession().uuid);
        if (transfer != null) {
            //A backend asked the proxy to move this player (transferPolicy=confirm):
            //the confirmation GUI replaces the main screen for this load (M7 F7.1)
            this.screenHandler.openScreen(new dev.connectplus.lobby.screen.impl.TransferConfirmScreen(lang, transfer.host(), transfer.port()));
            this.hotbar.initialize();
            return;
        }
        this.screenHandler.openScreen(new MainScreen(lang));
        this.hotbar.initialize();
    }

    /**
     * The player's language for this connection; defaults to English until the
     * client sent its settings (ConfigurationStateHandler captures them).
     */
    private Lang lang() {
        return Lang.of(this.handler.getSession());
    }

    /** Called on the event loop after saved credentials finish restoring. */
    public void refreshAccountDisplay() {
        if (this.screenHandler.getCurrentScreen() instanceof MainScreen) {
            this.screenHandler.refreshScreen();
        }
    }

    /**
     * Failure notices of a hot switch that brought the player back into the lobby
     * (LobbyNotices, posted by the switch engine as bilingual template pairs);
     * sent once, right after the welcome, in the player's language.
     */
    private void sendSwitchNotices() {
        final PlayerSession session = this.handler.getSession();
        if (session == null) {
            return;
        }
        final java.util.List<LobbyNotices.Notice> notices = LobbyNotices.consume(session.uuid);
        if (notices == null) {
            return;
        }
        final Lang lang = this.lang();
        for (final LobbyNotices.Notice notice : notices) {
            final Object[] args = new Object[notice.args().length];
            for (int i = 0; i < notice.args().length; i++) {
                final Object arg = notice.args()[i];
                args[i] = arg instanceof LobbyNotices.LocalizedArg localized ? localized.text(lang.code()) : arg;
            }
            for (final TextComponent component : Messages.format(notice.text(lang.code()), args)) {
                this.send(new S2CSystemChatPacket(component, false));
            }
        }
    }

    @Override
    public void tick() {
        this.send(new S2CKeepAlivePacket(0));
    }

    @EventHandler
    public void handle(final dev.connectplus.lobby.protocol.packets.play.c2s.C2SPlayerPositionPacket packet) {
        if (LobbyConstants.isSafePosition(packet.x, packet.y, packet.z)) return;
        final long now = System.nanoTime();
        if (this.nextPositionCorrectionAt != 0 && now - this.nextPositionCorrectionAt < 0) return;
        // Old movement can arrive while a normal teleport is being acknowledged.
        // Bound corrections; never suppress client closes or retry GUI opens.
        this.nextPositionCorrectionAt = now + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500);
        DebugLog.log("[Lobby position] channel={} correcting ({}, {}, {}) to ({}, {}, {})",
                this.channel.id().asShortText(), packet.x, packet.y, packet.z,
                LobbyConstants.SPAWN_X, LobbyConstants.SPAWN_Y, LobbyConstants.SPAWN_Z);
        this.send(LobbyConstants.spawnPosition(this.nextTeleportId++));
    }

    @EventHandler
    public void handle(final dev.connectplus.lobby.protocol.packets.play.c2s.C2SPlayerPositionRotationPacket packet) {
        this.handle((dev.connectplus.lobby.protocol.packets.play.c2s.C2SPlayerPositionPacket) packet);
    }

    @EventHandler
    public void handle(final dev.connectplus.lobby.protocol.packets.config.C2SPlayClientInformationPacket packet) {
        //A client that changed its language in the options screen re-sends its
        //settings in the play state (M7): future texts follow the new language,
        //already sent ones are not re-sent
        if (this.handler.getSession() != null) {
            this.handler.getSession().locale = packet.locale;
            this.hotbar.refresh();
        }
    }

    @EventHandler
    public void handle(final C2SChatPacket packet) {
        final PlayerSession session = this.handler.getSession();
        if (session != null && session.chatListener != null && session.chatListener.apply(packet.message)) {
            session.chatListener = null;
            return;
        }
        this.executeCommand(session, packet.message);
    }

    @EventHandler
    public void handle(final C2SChatCommandPacket packet) {
        final String text = "/" + packet.message;
        final PlayerSession session = this.handler.getSession();
        if (session != null && session.chatListener != null && session.chatListener.apply(text)) {
            session.chatListener = null;
            return;
        }
        this.executeCommand(session, text);
    }

    @EventHandler
    public void handle(final dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandSignedPacket packet) {
        //Pre-1.19.1 clients' slash commands arrive translated into the signed
        //variant (V7 finding); the lobby ignores the signature trailer and runs
        //the same chain as the unsigned command packet
        final String text = "/" + packet.message;
        final PlayerSession session = this.handler.getSession();
        if (session != null && session.chatListener != null && session.chatListener.apply(text)) {
            session.chatListener = null;
            return;
        }
        this.executeCommand(session, text);
    }

    /**
     * Runs one chat line through the command chain. Players keep exactly the two
     * exit commands ({@code /disconnect}, {@code /dc}); every other input is
     * silently ignored, and all connect/bookmark actions live in the GUI only.
     */
    private void executeCommand(final PlayerSession session, final String input) {
        if (session == null) {
            return;
        }
        switch (LobbyCommands.parse(input).type()) {
            case DISCONNECT -> this.sendAndClose(new S2CPlayDisconnectPacket(new StringComponent(
                    Languages.text(this.lang(), Messages.MainScreen.Disconnect.DisconnectMessage))));
            case USAGE -> this.sendChatLines(Languages.text(this.lang(), Messages.Commands.DisconnectUsage));
            case NONE -> {
            }
        }
    }

    private void sendChatLines(final String message, final Object... args) {
        for (final TextComponent component : Messages.format(message, args)) {
            this.send(new S2CSystemChatPacket(component, false));
        }
    }

}
