package dev.connectplus.lobby.screen.impl;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Items;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Screen;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.session.PlayerSession;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * Paginated protocol version picker. Derived from MiniConnect's
 * VersionSelectorScreen (MIT, Copyright (c) 2024 Lenni0451). Modified in this
 * repo: the bedrock entry is filtered out instead of shown with a bedrock icon
 * (bedrock connect is not part of ConnectPlus). M6: navigation texts and the
 * title follow the player's language.
 */
public class VersionSelectorScreen extends Screen {

    private final Lang lang;
    private final int page;
    private final Supplier<ProtocolVersion> currentVersion;
    private final Consumer<ProtocolVersion> onSelect;
    private final Supplier<Screen> backScreen;

    public VersionSelectorScreen(final Lang lang, final int page) {
        this(lang, page, null, null, null);
    }

    /** A picker scoped to an existing editor instead of the session's current target. */
    VersionSelectorScreen(final Lang lang, final int page, final Supplier<ProtocolVersion> currentVersion,
                          final Consumer<ProtocolVersion> onSelect, final Supplier<Screen> backScreen) {
        super(new StringComponent(Languages.text(lang, Messages.VersionSelectorScreen.Title)), 6);
        this.lang = lang;
        this.page = page;
        this.currentVersion = currentVersion;
        this.onSelect = onSelect;
        this.backScreen = backScreen;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        final PlayerSession session = screenHandler.getStateHandler().getHandler().getSession();
        final ProtocolVersion selected = this.currentVersion == null ? session.targetVersion : this.currentVersion.get();

        final List<ProtocolVersion> allVersions = new ArrayList<>(ProtocolVersion.getProtocols());
        //Filter the bedrock entries through the stable PROTOCOLS list: the constant
        //field holding the latest bedrock version was renamed (bedrockLatest ->
        //BEDROCK_LATEST) between the ViaProxy releases we support, and a hard
        //reference to either name crashes the other one with NoSuchFieldError at
        //screen-open time (public-deployment finding on ViaProxy 3.4.14)
        final Set<ProtocolVersion> bedrockVersions = new HashSet<>(BedrockProtocolVersion.PROTOCOLS);
        allVersions.removeIf(bedrockVersions::contains);
        Collections.reverse(allVersions);
        final List<ProtocolVersion> versions = allVersions.subList(this.page * 45, Math.min(allVersions.size(), (this.page + 1) * 45));
        for (final ProtocolVersion version : versions) {
            final String item;
            if (version.newerThanOrEqualTo(LegacyProtocolVersion.r1_0_0tor1_0_1)) {
                item = Items.CRAFTING_TABLE;
            } else if (version.newerThanOrEqualTo(LegacyProtocolVersion.b1_0tob1_1_1)) {
                item = Items.FURNACE;
            } else {
                item = Items.DIRT;
            }
            itemList.add(item(item).named(new StringComponent("§a" + version.getName())).setGlint(version == selected).get(), () -> {
                if (this.onSelect == null) session.targetVersion = version;
                else this.onSelect.accept(version);
                screenHandler.openScreen(this.parentScreen());
            });
        }
        if (this.page == 0) {
            itemList.set(45, item(Items.GRAY_STAINED_GLASS_PANE).named(new StringComponent(" ")).get());
        } else {
            itemList.set(45, item(Items.ARROW).named(new StringComponent(Languages.text(this.lang, Messages.VersionSelectorScreen.PreviousPage))).get(), () -> {
                screenHandler.openScreen(this.page(this.page - 1));
            });
        }
        for (int i = 46; i <= 52; i++) {
            itemList.set(i, item(Items.GRAY_STAINED_GLASS_PANE).named(new StringComponent(" ")).get());
        }
        itemList.set(49, item(Items.ENDER_PEARL).named(new StringComponent(Languages.text(this.lang, Messages.VersionSelectorScreen.Back))).get(), () -> {
            screenHandler.openScreen(this.parentScreen());
        });
        if (allVersions.size() > (this.page + 1) * 45) {
            itemList.set(53, item(Items.ARROW).named(new StringComponent(Languages.text(this.lang, Messages.VersionSelectorScreen.NextPage))).get(), () -> {
                screenHandler.openScreen(this.page(this.page + 1));
            });
        } else {
            itemList.set(53, item(Items.GRAY_STAINED_GLASS_PANE).named(new StringComponent(" ")).get());
        }
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        screenHandler.openScreen(this.parentScreen());
    }

    private VersionSelectorScreen page(final int page) {
        return new VersionSelectorScreen(this.lang, page, this.currentVersion, this.onSelect, this.backScreen);
    }

    private Screen parentScreen() {
        return this.backScreen == null ? new MainScreen(this.lang) : this.backScreen.get();
    }

}
