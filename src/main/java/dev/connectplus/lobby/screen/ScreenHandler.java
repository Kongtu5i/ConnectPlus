package dev.connectplus.lobby.screen;

import dev.connectplus.logging.DebugLog;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.identity.ClientIdentity;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.lenni0451.mcstructs.text.components.StringComponent;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.lobby.states.StateHandler;
import net.lenni0451.lambdaevents.EventHandler;

/**
 * Derived from MiniConnect's ScreenHandler (MIT, Copyright (c) 2024 Lenni0451).
 * Each opened screen has its own window ID so old clicks and closes cannot
 * act on a replacement screen, including Geyser's delayed close confirmations.
 */
public class ScreenHandler {

    private final StateHandler stateHandler;
    private final Runnable screenChanged;
    private Screen currentScreen;
    private ItemList currentItemList;
    private int currentWindowId;
    private long screenRevision;
    private long openedAtNanos;
    private boolean stableBedrockChest;

    public ScreenHandler(final StateHandler stateHandler) {
        this(stateHandler, () -> {});
    }

    public ScreenHandler(final StateHandler stateHandler, final Runnable screenChanged) {
        this.stateHandler = stateHandler;
        this.screenChanged = screenChanged;
    }

    public StateHandler getStateHandler() {
        return this.stateHandler;
    }

    /**
     * The currently open screen, or null when no screen is open.
     */
    public Screen getCurrentScreen() {
        return this.currentScreen;
    }

    public int getCurrentWindowId() {
        return this.currentWindowId;
    }

    public void openScreen(final Screen screen) {
        final long revision = ++this.screenRevision;
        if (this.currentScreen != null) this.stateHandler.getHandlerManager().unregister(this.currentScreen);
        // The presentation is decided before init: a screen that owns the full
        // six-row Bedrock container places its row-six actions during init.
        final int bedrockSlotCount = screen.getBedrockSlotCount();
        final boolean bedrockChest = this.isBedrockConnection() && screen.getInventoryType() >= 0
                && screen.getInventoryType() <= 5 && bedrockSlotCount <= 54;
        final ItemList items = new ItemList(bedrockChest ? bedrockSlotCount : screen.getSlotCount());
        screen.init(this, items);
        // init may redirect to another screen (e.g. a deleted bookmark). That
        // inner open owns the window; never overwrite it with the abandoned page.
        if (revision != this.screenRevision) return;
        // OpenScreen replaces the current Java window. Keep the old holder
        // available to Geyser so its native translator owns close confirmation,
        // Bedrock ID allocation and pending-open handling as one transition.
        final int previousWindowId = this.currentWindowId;
        this.currentScreen = screen;
        this.currentItemList = items;
        this.stableBedrockChest = bedrockChest;
        this.setWindowId(this.stateHandler.getHandler().getSession().nextLobbyWindowId());
        // Clear the player shortcuts before the client opens the chest GUI.
        this.screenChanged.run();
        this.openedAtNanos = System.nanoTime();
        DebugLog.log("[Lobby GUI] channel={} open={} window={} previous={} bedrockChest={}",
                this.stateHandler.getChannel().id().asShortText(), screen.getClass().getSimpleName(),
                this.currentWindowId, previousWindowId, this.stableBedrockChest);
        // Official Geyser can reuse the physical holder only when type, title and
        // size match. Java window IDs remain distinct to isolate stale packets.
        this.stateHandler.send(new S2COpenScreenPacket(this.currentWindowId,
                this.stableBedrockChest ? 5 : screen.getInventoryType(),
                this.stableBedrockChest ? new StringComponent("§aConnectPlus") : screen.getTitle()));
        this.sendContents();
        this.stateHandler.getHandlerManager().register(this.currentScreen);
    }

    public void closeScreen() {
        if (this.currentScreen == null) return;
        ++this.screenRevision;
        this.stateHandler.getHandlerManager().unregister(this.currentScreen);
        final int windowId = this.currentWindowId;
        DebugLog.log("[Lobby GUI] channel={} server-close={} window={} ageMs={}",
                this.stateHandler.getChannel().id().asShortText(), this.currentScreen.getClass().getSimpleName(),
                windowId, (System.nanoTime() - this.openedAtNanos) / 1_000_000);
        this.currentScreen = null;
        this.currentItemList = null;
        this.stableBedrockChest = false;
        this.setWindowId(0);
        this.stateHandler.send(new S2CContainerClosePacket(windowId));
        this.screenChanged.run();
    }

    /** Rebuild the visible contents and click actions without reopening the window. */
    public void refreshScreen() {
        if (this.currentScreen == null) return;
        final long revision = this.screenRevision;
        // The presentation was latched at open time; a late identity change
        // must not resize an already-open window during a refresh.
        final ItemList items = new ItemList(this.stableBedrockChest
                ? this.currentScreen.getBedrockSlotCount() : this.currentScreen.getSlotCount());
        this.currentScreen.init(this, items);
        if (revision != this.screenRevision) return;
        this.currentItemList = items;
        this.sendContents();
    }

    @EventHandler
    public void handle(final C2SContainerClickPacket packet) {
        DebugLog.log("[Lobby GUI] channel={} clickWindow={} current={} slot={} button={} action={} accepted={}",
                this.stateHandler.getChannel().id().asShortText(), packet.containerId, this.currentWindowId,
                packet.slot, packet.button, packet.action,
                this.currentScreen != null && packet.containerId == this.currentWindowId);
        if (this.currentScreen == null || packet.containerId != this.currentWindowId) return;
        this.sendContents();
        if (packet.slot < 0 || packet.slot >= this.currentItemList.getItems().length) return;
        if (packet.action == 0) {
            //pickup mode: button 0 = left click, button 1 = right click; every other
            //mode (quick move, swap, ...) must not trigger slot actions
            if (packet.button == 0) {
                final ItemList.ClickListener listener = this.currentItemList.getListeners()[packet.slot];
                if (listener != null) listener.onClick();
            } else if (packet.button == 1) {
                final ItemList.RightClickListener listener = this.currentItemList.getRightListeners()[packet.slot];
                if (listener != null) listener.onRightClick();
            }
        }
    }

    @EventHandler
    public void handle(final C2SContainerClosePacket packet) {
        DebugLog.log("[Lobby GUI] channel={} client-close={} current={} screen={} ageMs={} accepted={}",
                this.stateHandler.getChannel().id().asShortText(), packet.id, this.currentWindowId,
                this.currentScreen == null ? "none" : this.currentScreen.getClass().getSimpleName(),
                this.currentScreen == null ? -1 : (System.nanoTime() - this.openedAtNanos) / 1_000_000,
                this.currentScreen != null && packet.id == this.currentWindowId);
        if (this.currentScreen == null || packet.id != this.currentWindowId) return;
        ++this.screenRevision;
        final Screen currentScreen = this.currentScreen;
        final boolean exitBedrockChest = this.stableBedrockChest;
        this.stateHandler.getHandlerManager().unregister(this.currentScreen);
        this.currentScreen = null; //First set the screen to null because the close logic could open a new screen
        this.currentItemList = null;
        this.stableBedrockChest = false;
        this.setWindowId(0);
        // A Bedrock close can also mean that opening the virtual chest failed.
        // Opening a parent here created the observed detail/list/main cascade.
        // Explicit back buttons navigate while the holder is still available.
        if (!exitBedrockChest) currentScreen.close(this);
        // Child screens can immediately reopen their parent. Restore shortcuts
        // only if the close really left the GUI, without flashing them between pages.
        if (this.currentScreen == null) this.screenChanged.run();
    }

    private void setWindowId(final int windowId) {
        this.currentWindowId = windowId;
        final var session = this.stateHandler.getHandler().getSession();
        if (session != null && session.c2pChannel != null && session.lobbyChannel == this.stateHandler.getChannel()) {
            session.c2pChannel.attr(CPAttributeKeys.LOBBY_WINDOW_ID).set(windowId);
        }
    }

    private boolean isBedrockConnection() {
        final var session = this.stateHandler.getHandler().getSession();
        if (session == null || session.c2pChannel == null) return false;
        final ClientIdentity identity = session.c2pChannel.attr(CPAttributeKeys.CLIENT_IDENTITY).get();
        // Linked Bedrock profiles have JAVA profile keys; the original client
        // identity determines presentation, independently of profile ownership.
        return identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_BEDROCK;
    }

    private void sendContents() {
        final Item[] items;
        if (this.stableBedrockChest) {
            items = StructuredItem.emptyArray(54);
            System.arraycopy(this.currentItemList.getItems(), 0, items, 0, this.currentItemList.getItems().length);
        } else {
            items = this.currentItemList.getItems();
        }
        this.stateHandler.send(new S2CContainerSetContentPacket(this.currentWindowId, 0, items, StructuredItem.empty()));
    }

}
