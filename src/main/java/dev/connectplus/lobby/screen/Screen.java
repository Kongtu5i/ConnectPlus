package dev.connectplus.lobby.screen;

import net.lenni0451.mcstructs.text.TextComponent;

/**
 * Derived from MiniConnect's Screen (MIT, Copyright (c) 2024 Lenni0451).
 */
public abstract class Screen {

    private final TextComponent title;
    private final int slotCount;
    private final int inventoryType;

    public Screen(final TextComponent title, final int rows) {
        this.title = title;
        this.slotCount = rows * 9;
        this.inventoryType = rows - 1;
    }

    public Screen(final TextComponent title, final int inventoryType, final int slotCount) {
        this.title = title;
        this.slotCount = slotCount;
        this.inventoryType = inventoryType;
    }

    public TextComponent getTitle() {
        return this.title;
    }

    public int getSlotCount() {
        return this.slotCount;
    }

    public int getInventoryType() {
        return this.inventoryType;
    }

    /**
     * The slot count this screen uses inside the stable Bedrock chest
     * presentation. A screen that spreads its layout across the full six-row
     * container overrides this; the default keeps the logical slot count and
     * the renderer pads the remaining rows with empty slots.
     */
    public int getBedrockSlotCount() {
        return this.slotCount;
    }

    public abstract void init(final ScreenHandler screenHandler, final ItemList itemList);

    public abstract void close(final ScreenHandler screenHandler);

}
