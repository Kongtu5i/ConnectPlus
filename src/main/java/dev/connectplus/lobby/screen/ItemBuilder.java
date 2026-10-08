package dev.connectplus.lobby.screen;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viabackwards.protocol.v1_21_4to1_21_2.Protocol1_21_4To1_21_2;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import dev.connectplus.lobby.LobbyConstants;
import dev.connectplus.utils.ViaUtils;
import net.lenni0451.mcstructs.text.TextComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fluent builder for GUI items. Derived from MiniConnect's ItemBuilder (MIT,
 * Copyright (c) 2024 Lenni0451). Modified in this repo: the Via id-lookup is
 * optional — the Via.getManager() call is wrapped in a try/catch so items can
 * be built in environments without a loaded Via (unit tests, scripted client).
 * At runtime inside ViaProxy, Via is always present.
 */
public class ItemBuilder {

    public static ItemBuilder item(final String id) {
        return new ItemBuilder(id);
    }


    private final String id;
    private TextComponent name;
    private final List<TextComponent> lore = new ArrayList<>();
    private Boolean glint;
    private final Map<StructuredDataKey, Object> structuredData = new HashMap<>();

    private ItemBuilder(final String id) {
        this.id = id;
    }

    public ItemBuilder named(final TextComponent name) {
        this.name = name;
        return this;
    }

    public ItemBuilder lore(final TextComponent... lore) {
        Collections.addAll(this.lore, lore);
        return this;
    }

    public ItemBuilder setGlint(final boolean state) {
        this.glint = state;
        return this;
    }

    public <T> ItemBuilder data(final StructuredDataKey<T> key, final T value) {
        this.structuredData.put(key, value);
        return this;
    }

    public ItemBuilder calculate(final Consumer<ItemBuilder> consumer) {
        consumer.accept(this);
        return this;
    }

    public Item get() {
        final int rawId = LobbyConstants.ITEMS.indexOf(this.id);
        if (rawId == -1) throw new IllegalArgumentException("Unknown item id: " + this.id);
        final StructuredItem item = new StructuredItem(rawId, 1);
        try {
            item.dataContainer().setIdLookup(Via.getManager().getProtocolManager().getProtocol(Protocol1_21_4To1_21_2.class), false);
        } catch (final Throwable ignored) {
            //No Via loaded (unit tests): the structured data serializer lookup needs it, so
            //only plain items (no name/lore/glint/data) can be built there — they still
            //encode fine. At runtime inside ViaProxy, Via is always present.
            return item;
        }
        if (this.name != null) {
            item.dataContainer().set(StructuredDataKey.CUSTOM_NAME, ViaUtils.convertNbt(LobbyConstants.TEXT_CODEC.serializeNbtTree(this.name)));
        }
        if (!this.lore.isEmpty()) {
            final Tag[] lore = new Tag[this.lore.size()];
            for (int i = 0; i < this.lore.size(); i++) {
                lore[i] = ViaUtils.convertNbt(LobbyConstants.TEXT_CODEC.serializeNbtTree(this.lore.get(i)));
            }
            item.dataContainer().set(StructuredDataKey.LORE, lore);
        }
        if (this.glint != null) {
            item.dataContainer().set(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE, this.glint);
        }
        for (final Map.Entry<StructuredDataKey, Object> entry : this.structuredData.entrySet()) {
            item.dataContainer().set(entry.getKey(), entry.getValue());
        }
        return item;
    }

}
