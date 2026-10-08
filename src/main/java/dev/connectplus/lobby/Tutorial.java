package dev.connectplus.lobby;

import com.viaversion.viaversion.api.minecraft.item.data.FilterableComponent;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.lenni0451.mcstructs.text.stringformat.StringFormat;
import net.lenni0451.mcstructs.text.stringformat.handling.ColorHandling;
import net.lenni0451.mcstructs.text.stringformat.handling.DeserializerUnknownHandling;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.utils.ViaUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The tutorial book pages. Derived from MiniConnect's Tutorial (MIT,
 * Copyright (c) 2024 Lenni0451). Modified in this repo: the Login and
 * ProxyOnlineMode pages are dropped (no such features in ConnectPlus), and the
 * pages are built per language (M6 F1.4).
 */
public class Tutorial {

    private static final StringFormat LEGACY_FORMAT = StringFormat.vanilla();

    /**
     * The tutorial book pages in the player's language; built on demand because
     * the language is a per-player property (the old static TEXT was English-only).
     */
    public static FilterableComponent[] text(final Lang lang) {
        return buildTutorial(
                Languages.text(lang, Messages.Tutorial.Introduction),
                Languages.text(lang, Messages.Tutorial.ServerAddress),
                Languages.text(lang, Messages.Tutorial.ServerVersion),
                Languages.text(lang, Messages.Tutorial.Connect),
                Languages.text(lang, Messages.Tutorial.Disconnect),
                Languages.text(lang, Messages.Tutorial.WildcardDomains)
        );
    }

    private static FilterableComponent[] buildTutorial(final String... pages) {
        final List<FilterableComponent> components = new ArrayList<>();
        for (final String page : pages) {
            if (page == null) continue;
            final String[] lines = page.trim().split("\n");
            final StringComponent base = new StringComponent();
            for (int i = 0; i < lines.length; i++) {
                if (i != 0) base.append("\n");
                base.append(LEGACY_FORMAT.fromString(lines[i], ColorHandling.RESET, DeserializerUnknownHandling.IGNORE));
            }
            components.add(new FilterableComponent(ViaUtils.convertNbt(LobbyConstants.TEXT_CODEC.serializeNbtTree(base)), null));
        }
        return components.toArray(new FilterableComponent[0]);
    }

}
