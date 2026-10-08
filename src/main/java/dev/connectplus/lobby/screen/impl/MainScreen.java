package dev.connectplus.lobby.screen.impl;

import com.google.common.net.HostAndPort;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.FilterableString;
import com.viaversion.viaversion.api.minecraft.item.data.WrittenBook;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.lobby.ConnectFlow;
import dev.connectplus.lobby.Tutorial;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenBookPacket;
import dev.connectplus.compat.AccountLoginPolicy;
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
import dev.connectplus.utils.TargetAddressGuard;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket;


import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * The lobby's main GUI. Derived from MiniConnect's MainScreen (MIT,
 * Copyright (c) 2024 Lenni0451). ConnectPlus adds Microsoft login, per-player
 * connection preferences and bookmarks. The protocol version is optional —
 * when unset the connect runs with auto detection. All texts follow the
 * player's language (F1.4) and the account login honors the allowlist (F8.1).
 * Layout: address item at 10, version item at 11, the save-login toggle at 12
 * (the login lives only for the session while it is off), the offline-mode
 * toggle at 14, the connect door at 16, the tutorial book, the delete-all-data
 * item, the bookmarks ender chest and the disconnect barrier on the bottom
 * action row — row four (27/28/31/35) for Java, row six (45/46/49/53) inside
 * the stable six-row Bedrock container.
 */
public class MainScreen extends Screen {

    private final Lang lang;

    public MainScreen(final Lang lang) {
        super(new StringComponent(Languages.text(lang, Messages.MainScreen.Title)), 4);
        this.lang = lang;
    }

    @Override
    public int getBedrockSlotCount() {
        // The stable Bedrock container is six rows and the main layout owns
        // its bottom row, so all 54 slots belong to the screen itself.
        return 54;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        final PlayerSession session = screenHandler.getStateHandler().getHandler().getSession();
        final boolean hasAddress = session.serverAddress != null;
        final boolean hasVersion = session.targetVersion != null;
        // The stable Bedrock container is six rows: the bottom actions move
        // from row four down to row six (+18). Java keeps the original row.
        final int bottomRow = itemList.getItems().length == 54 ? 18 : 0;
        final int bottomBook = 27 + bottomRow, bottomDelete = 28 + bottomRow;
        final int bottomBookmarks = 31 + bottomRow, bottomDisconnect = 35 + bottomRow;

        itemList.set(10, item(Items.NAMETAG).named(new StringComponent(this.t(Messages.MainScreen.SetServerAddress.ItemName))).setGlint(hasAddress).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.MainScreen.SetServerAddress.ItemLore)));
            if (hasAddress) {
                builder.lore(Messages.format(this.t(Messages.MainScreen.SetServerAddress.ItemLoreAddressSet), session.serverAddress));
            } else {
                builder.lore(Messages.format(this.t(Messages.MainScreen.SetServerAddress.ItemLoreNoAddressSet)));
            }
        }).get(), () -> {
            screenHandler.closeScreen();
            for (final TextComponent component : Messages.format(this.t(Messages.MainScreen.SetServerAddress.ChatInfo))) {
                screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
            }
            session.chatListener = input -> {
                final String s = dev.connectplus.session.ServerAddress.normalize(input);
                if (s.startsWith("/")) {
                    for (final TextComponent component : Messages.format(this.t(Messages.MainScreen.SetServerAddress.ChatCancelled))) {
                        screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                    }
                } else {
                    try {
                        final HostAndPort hostAndPort = HostAndPort.fromString(s);
                        if (hostAndPort.getHost().isBlank()) throw new InvalidAddressException();
                        if (!TargetAddressGuard.isAllowedHost(hostAndPort.getHost())) throw new InvalidAddressException();
                        session.serverAddress = s;
                    } catch (final Throwable t2) {
                        //Blank, local or unparseable address
                        for (final TextComponent component : Messages.format(this.t(Messages.MainScreen.SetServerAddress.ChatInvalidAddress))) {
                            screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                        }
                        return false;
                    }
                }
                screenHandler.openScreen(new MainScreen(this.lang));
                return true;
            };
        });
        itemList.set(11, item(Items.ANVIL).named(new StringComponent(this.t(Messages.MainScreen.SetProtocolVersion.ItemName))).setGlint(hasVersion).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.MainScreen.SetProtocolVersion.ItemLore)));
            if (hasVersion) {
                builder.lore(Messages.format(this.t(Messages.MainScreen.SetProtocolVersion.ItemLoreVersionSet), session.targetVersion.getName()));
            } else {
                builder.lore(Messages.format(this.t(Messages.MainScreen.SetProtocolVersion.ItemLoreAutoDetect)));
            }
        }).get(), () -> {
            screenHandler.openScreen(new VersionSelectorScreen(this.lang, 0));
        });
        itemList.set(12, this.saveLoginItem(session), () -> this.toggleSaveLogin(session, screenHandler));
        itemList.set(13, this.accountItem(screenHandler, session), () -> {
            if (!AccountLoginPolicy.isAllowedForSession(session)) {
                for (final TextComponent component : Messages.format(this.t(Messages.Commands.AccountLoginDisabled))) {
                    screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                }
            } else if (session.account != null && !session.account.isExpired()) {
                //Logged in and valid: the click logs out (the stored account is removed)
                final dev.connectplus.lobby.LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
                //§6 (final fix): logout is the one path that clears stored
                //credentials — a displaced session whose GUI is still alive during
                //the bounded exit window must not wipe the arriving holder's saved
                //login (the same lease gate as the settings save below).
                if (!AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
                    for (final TextComponent component : Messages.format(this.t(Messages.MainScreen.Login.ChatLinkStale))) {
                        screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                    }
                    return;
                }
                AccountFlow.logout(session, screenHandler, handler);
                screenHandler.openScreen(new MainScreen(this.lang));
            } else if (!AccountFlow.allowlisted(session)) {
                for (final TextComponent component : Messages.format(this.t(Messages.Commands.AccountLoginNotAllowed))) {
                    screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                }
            } else {
                AccountFlow.startLogin(session, screenHandler, screenHandler.getStateHandler().getHandler());
            }
        });
        itemList.set(14, item(Items.REDSTONE_TORCH).named(new StringComponent(this.t(Messages.MainScreen.OfflineMode.ItemName)))
                .setGlint(session.offlineMode()).calculate(builder -> {
                    builder.lore(Messages.format(this.t(Messages.MainScreen.OfflineMode.ItemLore)));
                    builder.lore(Messages.format(this.t(session.offlineMode()
                            ? Messages.MainScreen.OfflineMode.ItemLoreEnabled
                            : Messages.MainScreen.OfflineMode.ItemLoreDisabled)));
        }).get(), () -> {
            final dev.connectplus.lobby.LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
            //§6 settings save: a displaced session's toggle must not rewrite the
            //profile the new holder now owns. For a proxied session with neither
            //lease nor data the protected load is still in flight (final fix) —
            //the lazy-init must not create a wire-uuid scratch profile either.
            if (!AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
                return;
            }
            if (session.playerData == null) session.playerData = new PlayerData(session.uuid);
            session.playerData.offlineMode = !session.playerData.offlineMode;
            handler.getPlayerStore().save(session.playerData);
                    for (final TextComponent component : Messages.format(this.t(session.offlineMode()
                            ? Messages.MainScreen.OfflineMode.ChatEnabled : Messages.MainScreen.OfflineMode.ChatDisabled))) {
                        screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                    }
                    screenHandler.refreshScreen();
                });
        //§1.3 (task 7): the unlink option exists ONLY for a linked trusted
        //bedrock player (a Java player and an unlinked bedrock player see none).
        //The button text carries B's SAFELY PROCESSED display name through the
        //shared Messages pipeline — never a raw client-controlled string.
        final dev.connectplus.lobby.LobbyServerHandler unlinkHandler = screenHandler.getStateHandler().getHandler();
        if (unlinkHandler.getLinkService() != null && AccountLoginPolicy.isAllowedForSession(session)
                && AccountFlow.qualifiesForUnlink(session)) {
            itemList.set(15, item(Items.CHAIN).named(AccountFlow.unlinkButtonTitle(this.lang, session.account, session.profileKey))
                    .calculate(builder -> {
                        builder.lore(Messages.format(this.t(Messages.MainScreen.Unlink.ItemLore)));
                    }).get(), () -> {
                final dev.connectplus.lobby.LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
                //A displaced session must not start an unlink: the new holder owns
                //the profile now (Review Focus R5).
                if (!AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
                    return;
                }
                AccountFlow.startUnlink(session, screenHandler, handler);
            });
        }
        itemList.set(16, item(Items.OAK_DOOR).named(new StringComponent(this.t(Messages.MainScreen.ConnectToServer.ItemName))).setGlint(hasAddress).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.MainScreen.ConnectToServer.ItemLore)));
            builder.lore(Messages.format(this.t(session.offlineMode()
                    ? Messages.MainScreen.OfflineMode.ItemLoreEnabled : Messages.MainScreen.OfflineMode.ItemLoreDisabled)));
            if (!hasAddress) builder.lore(Messages.format(this.t(Messages.MainScreen.ConnectToServer.ItemLoreNoAddress)));
            if (hasVersion) {
                builder.lore(Messages.format(this.t(Messages.MainScreen.SetProtocolVersion.ItemLoreVersionSet), session.targetVersion.getName()));
            } else {
                //No version is not a blocker: the version is auto detected while unset
                builder.lore(Messages.format(this.t(Messages.MainScreen.SetProtocolVersion.ItemLoreAutoDetect)));
            }
            if (hasAddress) {
                builder.lore(Messages.format(this.t(Messages.MainScreen.SetServerAddress.ItemLoreAddressSet), session.serverAddress));
            }
        }).get(), () -> {
            //No address set: the requirements notice (the result mapping below would
            //only say "cannot be switched", which is not what the player needs to know)
            //An address set: the engine's outcome is chatted via the shared mapping
            String message;
            if (!hasAddress) {
                message = this.t(Messages.MainScreen.ConnectToServer.ItemLoreMissingRequirements);
            } else {
                final dev.connectplus.switching.SwitchInitiator.StartResult result = ConnectFlow.start(
                        session, screenHandler.getStateHandler(), screenHandler.getStateHandler().getHandler().getSwitchInitiator());
                message = Messages.Commands.connectResultMessage(this.lang, result);
            }
            if (message != null) {
                for (final TextComponent component : Messages.format(message)) {
                    screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                }
            }
        });

        itemList.set(bottomBookmarks, item(Items.ENDER_CHEST).named(new StringComponent(this.t(Messages.MainScreen.Bookmarks.ItemName))).setGlint(hasAddress).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.MainScreen.Bookmarks.ItemLore), session.playerData != null ? session.playerData.bookmarks.size() : 0));
        }).get(), () -> {
            screenHandler.openScreen(new BookmarksScreen(this.lang, 0));
        });
        itemList.set(bottomBook, item(Items.BOOK).named(new StringComponent(this.t(Messages.MainScreen.HowToUse.ItemName))).get(), () -> {
            if (session.clientVersion != null && session.clientVersion.newerThanOrEqualTo(ProtocolVersion.v1_19)) {
                screenHandler.openScreen(new TutorialScreen(this.lang));
            } else {
                screenHandler.closeScreen();
                final Item book = item(Items.WRITTEN_BOOK).data(
                        StructuredDataKey.WRITTEN_BOOK_CONTENT,
                        new WrittenBook(
                                new FilterableString("How to use ConnectPlus", null),
                                "ConnectPlus",
                                0,
                                Tutorial.text(this.lang),
                                true
                        )
                ).get();
                final Item[] items = StructuredItem.emptyArray(45);
                for (int i = 36; i < 45; i++) items[i] = book;
                screenHandler.getStateHandler().send(new S2CContainerSetContentPacket(0, 0, items, StructuredItem.empty()));
                screenHandler.getStateHandler().send(new S2COpenBookPacket(0));
                screenHandler.getStateHandler().send(new S2CContainerSetContentPacket(0, 0,
                        dev.connectplus.lobby.LobbyHotbar.items(Lang.of(session)), StructuredItem.empty()));
            }
        });
        itemList.set(bottomDelete, item(Items.TNT).named(new StringComponent(this.t(Messages.MainScreen.DeleteAll.ItemName))).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.MainScreen.DeleteAll.ItemLore)));
        }).get(), () -> {
            //§6 (final fix): the confirmation entry is gated like the destructive
            //click itself — a displaced session must not even reach the delete-all
            //screen, let alone delete the profile the arriving holder owns.
            final dev.connectplus.lobby.LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
            if (!AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter())) {
                for (final TextComponent component : Messages.format(this.t(Messages.MainScreen.Login.ChatLinkStale))) {
                    screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                }
                return;
            }
            screenHandler.openScreen(new DeleteAllConfirmScreen(this.lang));
        });
        itemList.set(bottomDisconnect, item(Items.BARRIER).named(new StringComponent(this.t(Messages.MainScreen.Disconnect.ItemName))).get(), () -> {
            screenHandler.getStateHandler().sendAndClose(new S2CPlayDisconnectPacket(new StringComponent(this.t(Messages.MainScreen.Disconnect.DisconnectMessage))));
        });
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        // Remain in the lobby; the hotbar compass supplies the menu entry.
    }

    /**
     * Resolves one text against this screen's language.
     */
    private String t(final Msg msg) {
        return Languages.text(this.lang, msg);
    }

    /**
     * The account entry: not logged in / expired = login (or re-login), logged
     * in and valid = one-click logout (design F5.1/F5.4).
     */
    private Item accountItem(final ScreenHandler screenHandler, final PlayerSession session) {
        final boolean enabled = AccountLoginPolicy.isAllowedForSession(session);
        final boolean loggedIn = enabled && session.account != null;
        final boolean expired = loggedIn && session.account.isExpired();
        return item(Items.TRIAL_KEY).named(new StringComponent(this.t(Messages.MainScreen.Login.ItemName))).setGlint(loggedIn).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.MainScreen.Login.ItemLore)));
            if (!enabled) {
                builder.lore(Messages.format(this.t(Messages.Commands.AccountLoginDisabled)));
            } else if (loggedIn && !expired) {
                builder.lore(Messages.format(this.t(Messages.MainScreen.Login.ItemLoreLoggedIn), session.account.displayName()));
            } else if (expired) {
                builder.lore(Messages.format(this.t(Messages.MainScreen.Login.ItemLoreExpired)));
            } else {
                builder.lore(Messages.format(this.t(Messages.MainScreen.Login.ItemLoreNotLoggedIn)));
            }
        }).get();
    }

    /**
     * The save-login toggle (lever): glint = saving on. Off keeps the login
     * valid for the current session only.
     */
    private Item saveLoginItem(final PlayerSession session) {
        final boolean save = session.playerData == null || session.playerData.saveLoginInfo;
        return item(Items.LEVER).named(new StringComponent(this.t(Messages.MainScreen.SaveLoginInfo.ItemName))).setGlint(save).calculate(builder -> {
            builder.lore(Messages.format(this.t(Messages.MainScreen.SaveLoginInfo.ItemLore)));
            builder.lore(Messages.format(this.t(save
                    ? Messages.MainScreen.SaveLoginInfo.ItemLoreEnabled
                    : Messages.MainScreen.SaveLoginInfo.ItemLoreDisabled)));
        }).get();
    }

    /**
     * Flips the persisted save-login flag. Turning it off drops the stored
     * account immediately (the live login keeps working for this session);
     * turning it on stores the current login again. Either way the player is
     * chatted about the new state and the item re-renders. Task 6 §6 (settings
     * save): a displaced session's settings may not rewrite the profile the
     * new holder now owns — the save path validates the lease currency first.
     * Package-private for the lease-gate pinning tests.
     */
    void toggleSaveLogin(final PlayerSession session, final ScreenHandler screenHandler) {
        final dev.connectplus.lobby.LobbyServerHandler handler = screenHandler.getStateHandler().getHandler();
        //§6 settings save (final fix): a displaced session's toggle must not
        //rewrite the profile, and a session whose protected load is still in
        //flight must not lazily create a wire-uuid scratch profile — the guard
        //now covers the in-flight window too, so the null check is folded in.
        if (!AccountFlow.leaseCurrent(session, handler.getLeaseGranter())) {
            return; // the profile was taken over (or is still loading): no write may race the real landing
        }
        if (session.playerData == null) {
            session.playerData = new PlayerData(session.uuid);
        }
        if ((session.account != null || session.playerData.accountBlob != null)
                && !AccountLoginPolicy.isAllowedForSession(session)) return;
        final boolean enabling = !session.playerData.saveLoginInfo;
        String accountBlob = session.playerData.accountBlob;
        if (enabling) {
            if (session.account != null) {
                if (!AccountLoginPolicy.isAllowedForSession(session)) return;
                accountBlob = handler.getTokenStore().encrypt(session.account.toJson());
                if (!AccountLoginPolicy.isAllowedForSession(session)) return;
            }
        } else {
            accountBlob = null;
        }
        if (!AccountFlow.leaseCurrent(session, handler.getLeaseGranter())) return;
        session.playerData.saveLoginInfo = enabling;
        session.playerData.accountBlob = accountBlob;
        handler.getPlayerStore().save(session.playerData);
        for (final TextComponent component : Messages.format(this.t(enabling
                ? Messages.MainScreen.SaveLoginInfo.ChatEnabled
                : Messages.MainScreen.SaveLoginInfo.ChatDisabled))) {
            screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
        }
        screenHandler.refreshScreen();
    }


    private static class InvalidAddressException extends RuntimeException {
    }

}
