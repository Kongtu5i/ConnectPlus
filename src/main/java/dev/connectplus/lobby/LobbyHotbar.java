package dev.connectplus.lobby;

import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import dev.connectplus.lobby.protocol.packets.play.c2s.*;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.screen.*;
import dev.connectplus.lobby.screen.impl.MainScreen;
import dev.connectplus.lobby.states.StateHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.lenni0451.lambdaevents.EventHandler;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket;

/** Owns the two fixed lobby shortcuts for Java and translated Bedrock clients. */
public final class LobbyHotbar {
    public static final int MENU_SLOT = 4;
    public static final int DISCONNECT_SLOT = 8;
    private final StateHandler state;
    private final ScreenHandler screens;
    private int selectedSlot = MENU_SLOT;
    private boolean menuActivated;
    private long lastMenuActivation;

    public LobbyHotbar(final StateHandler state, final ScreenHandler screens) {
        this.state = state;
        this.screens = screens;
    }

    /** Also used to restore the shortcuts after the legacy tutorial book. */
    public static Item[] items(final Lang lang) {
        final Item[] items = StructuredItem.emptyArray(46);
        items[36 + MENU_SLOT] = ItemBuilder.item(Items.COMPASS)
                .named(new StringComponent(Languages.text(lang, Messages.Hotbar.MenuName)))
                .lore(Messages.format(Languages.text(lang, Messages.Hotbar.MenuLore))).get();
        items[36 + DISCONNECT_SLOT] = ItemBuilder.item(Items.BARRIER)
                .named(new StringComponent(Languages.text(lang, Messages.MainScreen.Disconnect.ItemName)))
                .lore(Messages.format(Languages.text(lang, Messages.Hotbar.DisconnectLore))).get();
        return items;
    }

    public void refresh() {
        if (!this.state.getChannel().isActive()) return;
        this.state.send(new S2CContainerSetContentPacket(0, 0,
                this.screens.getCurrentScreen() == null
                        ? items(Lang.of(this.state.getHandler().getSession())) : StructuredItem.emptyArray(46),
                StructuredItem.empty()));
    }

    public void initialize() {
        this.refresh();
        // A return from a backend can keep its previous selected slot. Explicitly
        // select the menu so the client and this new lobby handler agree.
        this.state.send(new UnknownPacket(MCPackets.S2C_SET_HELD_SLOT.getId(LobbyProtocol.VERSION.getVersion()),
                new byte[]{MENU_SLOT}));
    }

    @EventHandler public void handle(final C2SSwingPacket packet) {
        this.activate(packet.hand);
    }

    @EventHandler public void handle(final C2SSetCarriedItemPacket packet) {
        if (packet.slot >= 0 && packet.slot < 9) this.selectedSlot = packet.slot;
    }

    @EventHandler public void handle(final C2SUseItemPacket packet) {
        this.acknowledge(packet.sequence);
        this.activate(packet.hand);
    }

    @EventHandler public void handle(final C2SUseItemOnPacket packet) {
        this.acknowledge(packet.sequence);
        // Correct creative clients' predicted placement of the barrier item.
        this.refresh();
        this.activate(packet.hand);
    }

    private void activate(final int hand) {
        if (hand != 0 || !this.state.getChannel().isActive()) return;
        // A GUI hides the shortcuts. Ignore delayed use/swing packets for those
        // removed items instead of reopening the menu or disconnecting.
        if (this.screens.getCurrentScreen() != null) return;
        final var session = this.state.getHandler().getSession();
        if (session == null) return;
        final Lang lang = Lang.of(session);
        if (this.selectedSlot == MENU_SLOT) {
            final long now = System.nanoTime();
            // One gesture can emit block use, air use and swing. Coalesce those.
            if (this.menuActivated && now - this.lastMenuActivation < 150_000_000L) return;
            this.menuActivated = true;
            this.lastMenuActivation = now;
            session.chatListener = null;
            this.screens.openScreen(new MainScreen(lang));
        } else if (this.selectedSlot == DISCONNECT_SLOT) {
            this.state.sendAndClose(new S2CPlayDisconnectPacket(new StringComponent(
                    Languages.text(lang, Messages.MainScreen.Disconnect.DisconnectMessage))));
        }
    }

    @EventHandler public void handle(final C2SContainerClickPacket packet) {
        if (packet.containerId == 0 || packet.containerId == this.screens.getCurrentWindowId()) this.refresh();
    }

    @EventHandler public void handle(final C2SSetCreativeModeSlotPacket packet) {
        this.refresh();
    }

    @EventHandler public void handle(final C2SPlayerActionPacket packet) {
        this.acknowledge(packet.sequence);
        // Reject dropping and swapping; the lobby owns its entire inventory.
        if (packet.action == 3 || packet.action == 4 || packet.action == 6) this.refresh();
    }

    private void acknowledge(final int sequence) {
        if (sequence < 0) return;
        final ByteBuf bytes = Unpooled.buffer();
        try {
            PacketTypes.writeVarInt(bytes, sequence);
            this.state.send(new UnknownPacket(MCPackets.S2C_BLOCK_CHANGED_ACK.getId(LobbyProtocol.VERSION.getVersion()),
                    ByteBufUtil.getBytes(bytes)));
        } finally { bytes.release(); }
    }
}
