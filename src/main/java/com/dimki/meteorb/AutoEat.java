package com.dimki.meteorb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;

/**
 * AutoEat: keeps the food bar up by eating from anywhere in the hotbar,
 * without permanently stealing the slot you are holding.
 *
 * <p>The trigger is hunger, not health - the one real difference from the
 * 1.7.3 port, where food healed and the bar barely mattered, so the check
 * there was {@code health < 12}. It fires either when health is not full and
 * hunger has started (a hurt player wants to regen, and regen costs food), or
 * when the bar has fallen to 3.5 icons on its own. See {@link #LOW_FOOD}.
 *
 * <p>Three things shaped this class, each read out of vanilla's bytecode rather
 * than guessed - and 1.12.2 keeps all three, which is why the module still works
 * by holding a key rather than by faking the effect:
 *
 * <ul>
 *   <li>Eating is not instant: the right click puts the stack into use, and
 *       the meal runs on a counter until {@code onFoodEaten} raises the bar;</li>
 *   <li>The client still cannot finish a meal. {@code updateActiveHand} reads
 *       its counter down and reaches the finish only under
 *       {@code !world.isRemote}, so the server owns the meal and the client
 *       mirrors what it is told - bytecode offsets 69 and 76 of that method;</li>
 *   <li>{@code ItemFood.onFoodEaten} is gated the same way, so the potion
 *       effect is applied server-side too.</li>
 * </ul>
 *
 * <p>So the module holds the use-item binding down while a meal runs and lets
 * vanilla drive the rest exactly as it does for a held right mouse button.
 * Nothing is faked: every tick still goes through the same
 * {@code PlayerControllerMP} call a real click makes.
 */
public class AutoEat extends Module {
    /**
     * Eats on either of two conditions, in food points:
     *
     * <ul>
     *   <li>health is not full and hunger has started - a damaged player is
     *       also a player who wants to regen, and regen costs food;</li>
     *   <li>hunger has fallen to {@value #LOW_FOOD} or below on its own,
     *       health irrelevant - 3.5 icons is the last point at which the bar
     *       empties slowly enough to eat into.</li>
     * </ul>
     *
     * The food bar is 20 points, 2 to an icon, so 7 is three and a half icons.
     */
    private static final int LOW_FOOD = 7;

    /** Hotbar slots: inventory indexes 0..8, the only ones currentItem names. */
    private static final int HOTBAR = 9;

    /** A food this much better than what is missing is not worth eating yet. */
    private static final int WASTE_MARGIN = 2;

    /** True from the click that starts a meal until the food is put back. */
    private boolean eating;

    /** The slot the player held before a meal took it over. */
    private int previousSlot = -1;

    /** True while this module holds the use-item binding down. */
    private boolean holdingUse;

    public AutoEat() {
        super("AutoEat", "Eats food from anywhere in the hotbar when hungry");
    }

    /** True while a meal is in progress; other modules should leave the hand alone. */
    public boolean isBusy() {
        return eating;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.currentScreen != null || minecraft.player == null
                || minecraft.world == null) {
            restore(minecraft);
            return;
        }

        EntityPlayer player = minecraft.player;
        InventoryPlayer inventory = player.inventory;

        if (eating) {
            // A fight broke out mid-meal: give the hotbar back at once. The
            // meal itself is abandoned - KillAura owns the hand now, and a
            // meal that outlasts the fight is worse than a meal skipped.
            if (killAuraHoldsSlot()) {
                holdUse(minecraft, false);
                if (isEating(player)) {
                    player.resetActiveHand();
                }
                abandon();
                return;
            }
            // Vanilla drives the meal while the use key reads as held; this
            // only keeps that key held and watches for the end. Releasing it
            // early is what cancels a meal one tick in.
            if (isEating(player)) {
                holdUse(minecraft, true);
                return;
            }
            holdUse(minecraft, false);
            inventory.currentItem = previousSlot;
            finish();
            return;
        }

        // KillAura is fighting with the hotbar; eating can wait until the
        // target is gone. See KillAura.isHoldingSlot().
        if (killAuraHoldsSlot()) {
            return;
        }

        if (!shouldEat(player)) {
            return;
        }

        int slot = chooseFood(player);
        if (slot < 0) {
            return;
        }

        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || !(stack.getItem() instanceof ItemFood)) {
            return;
        }

        previousSlot = inventory.currentItem;
        inventory.currentItem = slot;
        eating = true;
        holdUse(minecraft, true);
        minecraft.playerController.processRightClick(player, minecraft.world,
                net.minecraft.util.EnumHand.values()[0]);
    }

    /**
     * Whether a meal is worth starting now.
     *
     * <p>Two ways in, either alone is enough: health is not full while hunger
     * has begun, or the bar is down to {@link #LOW_FOOD}. A full bar and full
     * health never triggers, and neither does a merely scratched player on a
     * full bar.
     */
    private static boolean shouldEat(EntityPlayer player) {
        int food = player.getFoodStats().getFoodLevel();
        if (food <= LOW_FOOD) {
            return true; // 3.5 icons or less, whatever the health
        }
        return player.getHealth() < player.getMaxHealth() && food < 20;
    }

    /**
     * The best hotbar slot to eat, or -1.
     *
     * <p>The hotbar only: {@code inventory.currentItem} names a hotbar slot and
     * inventory indexes 0..8 are that bar, 9..35 being the pack behind it.
     * Scanning all 36 and writing a number above 8 picked nothing the player
     * could hold, and the bar flipped between slots every tick.
     *
     * <p>A food that would fill much more than is missing is passed over, so a
     * steak is not wasted at nineteen food points when a carrot would do.
     */
    private static int chooseFood(EntityPlayer player) {
        InventoryPlayer inventory = player.inventory;
        int missing = 20 - player.getFoodStats().getFoodLevel();

        int bestSlot = -1;
        int bestValue = 0;
        for (int slot = 0; slot < HOTBAR; slot++) {
            int value = valueOf(inventory.getStackInSlot(slot));
            if (value <= 0) {
                continue;
            }
            int heal = Math.min(value, missing + WASTE_MARGIN);
            if (heal > bestValue) {
                bestValue = heal;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * How much a stack is worth eating now, or 0 to leave it alone.
     *
     * <p>The heal amount is taken plainly. An earlier version refused anything
     * healing 8 or more unless the bar was nearly empty, meant to save golden
     * apples - but cooked porkchop heals 8, so the only food a player had was
     * discarded every tick and the module never ate at all. What makes an apple
     * special is not a number; it is the effect it carries, which lives in NBT
     * a stack does not have until it is eaten.
     */
    private static int valueOf(ItemStack stack) {
        if (stack == null || stack.getCount() < 1
                || !(stack.getItem() instanceof ItemFood)) {
            return 0;
        }
        return ((ItemFood) stack.getItem()).getHealAmount(stack);
    }

    /** True while vanilla still has a use running - a meal being eaten. */
    private static boolean isEating(EntityPlayer player) {
        return player.isHandActive();
    }

    /**
     * Makes the use-item binding read as held, or releases it.
     *
     * <p>{@code KeyBinding.setKeyBindState} sets the private flag its own
     * {@code isKeyDown()} returns, so this is the same signal a held right
     * mouse button produces - and the reason a meal survives to the end
     * instead of being cancelled one tick in.
     *
     * <p>1.9 changed how the hand is tracked - {@code isHandActive()} now reads
     * a networked flag rather than only a local one, and {@code runTickKeyboard}
     * no longer drops the item when the key is released - so this is belt and
     * braces: harmless if vanilla no longer needs it, and required if a server
     * or a later patch still cancels on release. Measured rather than assumed,
     * because the 1.8 build could not eat without it.
     */
    private void holdUse(Minecraft minecraft, boolean down) {
        if (down == holdingUse) {
            return;
        }
        holdingUse = down;
        KeyBinding binding = minecraft.gameSettings == null
                ? null : minecraft.gameSettings.keyBindUseItem;
        if (binding != null) {
            KeyBinding.setKeyBindState(binding.getKeyCode(), down);
        }
    }

    /** Drops the held key and clears the meal state. */
    private void finish() {
        eating = false;
        previousSlot = -1;
    }

    /** True when KillAura has the hotbar, so this module must not touch it. */
    private static boolean killAuraHoldsSlot() {
        return Modules.get().killAura.isEnabled()
                && Modules.get().killAura.isHoldingSlot();
    }

    /**
     * Ends a meal without putting a slot back.
     *
     * <p>KillAura owns the held slot by the time this runs, so restoring this
     * module's own would fight it for the bar.
     */
    private void abandon() {
        eating = false;
        previousSlot = -1;
    }

    private void restore(Minecraft minecraft) {
        if (!eating) {
            return;
        }
        holdUse(minecraft, false);
        if (previousSlot >= 0 && previousSlot < HOTBAR) {
            minecraft.player.inventory.currentItem = previousSlot;
        }
        finish();
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getMinecraft();
        restore(mc);
        if (mc.player != null && isEating(mc.player)) {
            mc.player.resetActiveHand();
        }
    }
}
