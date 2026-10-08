package dev.connectplus.lobby.screen.impl;

import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.data.FilterableString;
import com.viaversion.viaversion.api.minecraft.item.data.WrittenBook;
import dev.connectplus.lobby.Tutorial;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerButtonClickPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetDataPacket;
import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Items;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Screen;
import dev.connectplus.lobby.screen.ScreenHandler;
import net.lenni0451.lambdaevents.EventHandler;
import net.lenni0451.mcstructs.text.components.StringComponent;

import static dev.connectplus.lobby.screen.ItemBuilder.item;

/**
 * The lectern-based tutorial book. Derived from MiniConnect's TutorialScreen
 * (MIT, Copyright (c) 2024 Lenni0451). M6: the book pages and the title follow
 * the player's language.
 */
public class TutorialScreen extends Screen {

    private final Lang lang;
    private final int pageCount;

    private ScreenHandler screenHandler;
    private int currentPage;

    public TutorialScreen(final Lang lang) {
        super(new StringComponent(Languages.text(lang, Messages.TutorialScreen.Title)), 17, 1);
        this.lang = lang;
        this.pageCount = Tutorial.text(lang).length;
    }

    @Override
    public void init(final ScreenHandler screenHandler, final ItemList itemList) {
        this.screenHandler = screenHandler;
        itemList.add(item(Items.WRITTEN_BOOK).data(
                StructuredDataKey.WRITTEN_BOOK_CONTENT,
                new WrittenBook(new FilterableString("Tutorial", null), "ConnectPlus", 0, Tutorial.text(this.lang), true)
        ).get());
    }

    @Override
    public void close(final ScreenHandler screenHandler) {
        screenHandler.openScreen(new MainScreen(this.lang));
    }

    @EventHandler
    public void handle(final C2SContainerButtonClickPacket packet) {
        if (packet.buttonId == 1) this.currentPage--;
        else if (packet.buttonId == 2) this.currentPage++;
        if (this.currentPage < 0) this.currentPage = 0;
        if (this.currentPage >= this.pageCount) this.currentPage = this.pageCount - 1;
        this.screenHandler.getStateHandler().send(new S2CContainerSetDataPacket(packet.syncId, 0, this.currentPage));
    }

}
