package dev.connectplus.lobby.screen;

import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;

import javax.annotation.Nullable;

/**
 * Derived from MiniConnect's ItemList (MIT, Copyright (c) 2024 Lenni0451).
 */
public class ItemList {

    private final Item[] items;
    private final ClickListener[] listeners;
    private final RightClickListener[] rightListeners;

    public ItemList(final int slotCount) {
        this.items = StructuredItem.emptyArray(slotCount);
        this.listeners = new ClickListener[slotCount];
        this.rightListeners = new RightClickListener[slotCount];
    }

    public Item[] getItems() {
        return this.items;
    }

    public ClickListener[] getListeners() {
        return this.listeners;
    }

    public void add(final Item item) {
        this.add(item, null);
    }

    public void add(final Item item, @Nullable final ClickListener clickListener) {
        for (int i = 0; i < this.items.length; i++) {
            if (this.items[i].isEmpty()) {
                this.items[i] = item;
                this.listeners[i] = clickListener;
                return;
            }
        }
        throw new IllegalStateException("No free slot available");
    }

    public void set(final int slot, final Item item) {
        this.set(slot, item, null);
    }

    public void set(final int slot, final Item item, @Nullable final ClickListener listener) {
        this.items[slot] = item;
        this.listeners[slot] = listener;
    }

    public void set(final int slot, final Item item, @Nullable final ClickListener leftListener, @Nullable final RightClickListener rightListener) {
        this.items[slot] = item;
        this.listeners[slot] = leftListener;
        this.rightListeners[slot] = rightListener;
    }

    public RightClickListener[] getRightListeners() {
        return this.rightListeners;
    }


    @FunctionalInterface
    public interface ClickListener {
        void onClick();
    }
    /**
     * The right-click (container pickup with button 1) handler of a slot (M6:
     * the bookmark list opens the detail screen with it).
     */
    @FunctionalInterface
    public interface RightClickListener {
        void onRightClick();
    }

}
