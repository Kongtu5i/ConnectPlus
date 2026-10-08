package dev.connectplus.lobby.screen;

import com.viaversion.viaversion.api.minecraft.item.Item;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemListTest {

    @Test
    void addFillsSlotsInOrder() {
        final ItemList list = new ItemList(9);
        final Item first = ItemBuilder.item("stone").get();
        final Item second = ItemBuilder.item("dirt").get();
        list.add(first);
        list.add(second);

        assertEquals(9, list.getItems().length);
        assertSame(first, list.getItems()[0]);
        assertSame(second, list.getItems()[1]);
        for (int i = 2; i < 9; i++) {
            assertTrue(list.getItems()[i].isEmpty(), "slot " + i + " must stay empty");
        }
    }

    @Test
    void setOverwritesPreviousItem() {
        final ItemList list = new ItemList(3);
        final Item original = ItemBuilder.item("stone").get();
        final Item replacement = ItemBuilder.item("dirt").get();
        list.set(1, original);
        list.set(1, replacement);

        assertSame(replacement, list.getItems()[1]);
        assertFalse(list.getItems()[1].isEmpty());
        assertNull(list.getListeners()[1]);
    }

    @Test
    void listenersRoundTrip() {
        final ItemList list = new ItemList(3);
        final AtomicBoolean clicked = new AtomicBoolean(false);
        final ItemList.ClickListener listener = () -> clicked.set(true);

        list.set(0, ItemBuilder.item("stone").get(), listener);
        list.add(ItemBuilder.item("dirt").get());

        assertSame(listener, list.getListeners()[0]);
        assertNull(list.getListeners()[1]);
        list.getListeners()[0].onClick();
        assertTrue(clicked.get());
    }

    @Test
    void addWithoutFreeSlotThrows() {
        final ItemList list = new ItemList(1);
        list.add(ItemBuilder.item("stone").get());
        assertThrows(IllegalStateException.class, () -> list.add(ItemBuilder.item("dirt").get()));
    }
}
