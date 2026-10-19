package com.dimki.meteorb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/**
 * ChestDump: while a chest screen is open, one key press moves the whole
 * inventory into the chest and leaves the hotbar alone.
 *
 * <p>Ported from the 1.7.3 and 1.8 builds. One real change, and it is a 1.12.2
 * change: the click is a {@code ClickType} enum now, not an int. Shift-click is
 * {@code ClickType.values()[1]} - QUICK_MOVE - which the mapping confirms (b is
 * QUICK_MOVE in the TSRG), because the constants did not survive MCP and their
 * names cannot be written down. In 1.8 the same action was mode 1.
 *
 * <p>How it works, unchanged:
 *
 * <ul>
 *   <li>Container slots are chest-first, then the player's 27 inventory slots,
 *       then the 9 hotbar slots. Only those 27 are dumped, so what you hold and
 *       your quick-access row stay put.</li>
 *   <li>Every stack moves through PlayerControllerMP.windowClick with
 *       QUICK_MOVE, the exact path a real shift-click takes - the game performs
 *       its own transfer and sends the proper packet, so this works on a server
 *       too. No fabricated packets, no writes into the inventory arrays.</li>
 *   <li>A few slots per tick, so a full inventory takes a second or two.</li>
 *   <li>The window id comes from the container being dumped, the same value
 *       vanilla passes, so the clicks land in the right window.</li>
 * </ul>
 *
 * <p>Armour is skipped: dumping it is never what you want, since the worn set is
 * the one you are fighting in.
 */
public class ChestDump extends Module {
    /** Default dump key: P. */
    public static final int DEFAULT_KEY = org.lwjgl.input.Keyboard.KEY_P;

    /** The player half of a chest container: 27 inventory + 9 hotbar. */
    private static final int PLAYER_SLOTS = 36;

    /** The last 9 slots of the player half are the hotbar - never dumped. */
    private static final int HOTBAR_SLOTS = 9;

    /** Slots clicked per tick. 2 keeps the clicks human-paced. */
    private static final int CLICKS_PER_TICK = 2;

    /** Shift-click, whose constant is not reachable by name. */
    private static final net.minecraft.inventory.ClickType QUICK_MOVE =
            net.minecraft.inventory.ClickType.values()[1];

    /** Container slots still to click; null = idle. */
    private int[] pending;
    private int pendingIndex;

    /** The container the queue belongs to, so a screen change aborts it. */
    private Container target;

    public ChestDump() {
        super("ChestDump", "Dumps inventory into an open chest, hotbar kept");
        setBindKey(DEFAULT_KEY);
    }

    /** True while a dump is in progress. */
    public boolean isDumping() {
        return pending != null;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.currentScreen == null || minecraft.player == null) {
            stop();
            return;
        }

        Container open = containerOf(minecraft);
        if (open == null || (pending != null && open != target)) {
            // Not a container screen, or a different container than the one we
            // queued for - drop the queue either way.
            stop();
            return;
        }

        if (pending == null || pendingIndex >= pending.length) {
            return; // armed, nothing queued
        }

        EntityPlayer player = minecraft.player;
        int windowId = open.windowId;

        for (int clicks = 0;
             clicks < CLICKS_PER_TICK && pendingIndex < pending.length;
             clicks++) {
            int slot = pending[pendingIndex++];
            // Only click slots that still hold something.
            if (open.getSlot(slot).getStack() != null) {
                minecraft.playerController.windowClick(windowId, slot, 0,
                        QUICK_MOVE, player);
            }
        }

        if (pendingIndex >= pending.length) {
            stop();
        }
    }

    /**
     * Queues every non-empty player inventory slot and starts the dump.
     * Safe to call on any tick; does nothing without an open container.
     */
    public void startDump(Minecraft minecraft) {
        Container open = containerOf(minecraft);
        if (open == null) {
            return;
        }

        int total = open.inventorySlots.size();
        // The player half is the last PLAYER_SLOTS slots; its tail is the
        // hotbar. Dump only [playerStart, playerStart + 27).
        int playerStart = Math.max(0, total - PLAYER_SLOTS);
        int inventoryEnd = playerStart + (PLAYER_SLOTS - HOTBAR_SLOTS);

        java.util.List<Integer> list = new java.util.ArrayList<Integer>();
        for (int i = playerStart; i < inventoryEnd && i < total; i++) {
            Slot slot = open.getSlot(i);
            ItemStack stack = slot.getStack();
            if (stack != null && stack.getCount() >= 1 && !skip(stack)) {
                list.add(Integer.valueOf(i));
            }
        }

        target = open;

        if (list.isEmpty()) {
            pending = null;
            pendingIndex = 0;
            return;
        }

        pending = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            pending[i] = list.get(i).intValue();
        }
        pendingIndex = 0;
    }

    /** Armour is worthless in a chest: leave the worn set alone. */
    private static boolean skip(ItemStack stack) {
        return stack.getItem() instanceof ItemArmor;
    }

    private void stop() {
        pending = null;
        pendingIndex = 0;
        target = null;
    }

    /** The container behind the open screen, or null for anything else. */
    private static Container containerOf(Minecraft minecraft) {
        if (!(minecraft.currentScreen instanceof GuiContainer)) {
            return null;
        }
        return ((GuiContainer) minecraft.currentScreen).inventorySlots;
    }
}
