package com.dimki.meteorb;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/**
 * AutoArmor: equips better armor from the inventory, and puts the old piece back.
 *
 * <p>Ported from the 1.8 build, where the behaviour was already right. Four 1.12.2
 * changes, each read out of the jar rather than remembered:
 *
 * <ul>
 *   <li>{@code ItemArmor.armorType} is no longer an int - it is an
 *       {@code EntityEquipmentSlot}, so the piece is matched by the slot's own
 *       {@code getSlotIndex()} instead of comparing an enum whose constants the
 *       mapping leaves obfuscated ({@code a}..{@code f});</li>
 *   <li>{@code InventoryPlayer.armorInventory} is a {@code NonNullList} whose
 *       generic type is not public, so it is read through the inventory's own
 *       {@code getStackInSlot} and never indexed directly;</li>
 *   <li>{@code windowClick} takes a {@code ClickType} instead of a mode int -
 *       an ordinary click is {@code values()[0]}, since the constants carry
 *       their MCP names this time;</li>
 *   <li>the {@code toughness} field exists and a piece's worth now includes
 *       it, as vanilla's own armour math does.</li>
 * </ul>
 *
 * <p>The swap is the honest one from 1.7.3: three windowClicks on the player
 * container move the new piece into its armour slot and the old one back into
 * the freed slot, so this works on a server too - each click sends the same
 * packet a real click sends. The swapped-out piece is never dropped on the
 * ground; it goes home.
 */
public class AutoArmor extends Module {
    /** Slots clicked per swap, and the pause between two swaps. */
    private static final int SWAP_COOLDOWN = 5;

    /** The player inventory: 27 rows + 9 hotbar. */
    private static final int MAIN_SIZE = 36;

    /** Player container slots 0-4 are the crafting grid and its result. */
    private static final int ARMOR_SLOT_BASE = 5;

    private int cooldown;

    public AutoArmor() {
        super("AutoArmor", "Equips better armor from your inventory automatically");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.currentScreen != null || minecraft.player == null
                || minecraft.world == null) {
            return; // container slots belong to an open screen - don't fight it
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        EntityPlayer player = minecraft.player;
        for (int armorIndex = 0; armorIndex < 4; armorIndex++) {
            int bestSlot = bestPieceFor(player, armorIndex);
            if (bestSlot < 0) {
                continue;
            }

            // Three pickup clicks on the player container: the new piece into
            // its armor slot, the displaced one back where the new piece came
            // from. Not a quick-move - that was tried first and reads its own
            // limits in ContainerPlayer.transferStackInSlot, which handles the
            // crafting grid and the inventory but not the armour slots, so a
            // shift-click puts a helmet in the craft grid instead of on the head.
            int from = mainToContainerSlot(bestSlot);
            int to = containerArmorSlot(armorIndex);
            minecraft.playerController.windowClick(0, from, 0,
                    ClickType.values()[0], player);
            minecraft.playerController.windowClick(0, to, 0,
                    ClickType.values()[0], player);
            minecraft.playerController.windowClick(0, from, 0,
                    ClickType.values()[0], player);
            cooldown = SWAP_COOLDOWN;
            return; // one swap per check
        }
    }

    /**
     * The inventory slot holding the best piece for an armour index, or -1.
     *
     * <p>The index is the container's own: 0 the helmet, 1 the chestplate, 2 the
     * leggings, 3 the boots - the same numbering
     * {@code EntityEquipmentSlot.getSlotIndex()} reports for each of the four
     * armour slots, and the one {@code ContainerPlayer} builds its slots in.
     */
    private static int bestPieceFor(EntityPlayer player, int armorIndex) {
        InventoryPlayer inventory = player.inventory;
        // Read through the accessor: the backing list's element type is not
        // public, so an index into it cannot be compiled.
        //
        // The index is INVERTED. ContainerPlayer builds armour slot 5+i over
        // armorInventory[3 - i] - measured in this jar's own constructor,
        // "iconst_3; iload i; isub; iadd" - so a container index of 0 (the
        // helmet) reads the inventory's element 3. Reading it straight
        // compares a candidate against the wrong worn piece, which for a
        // matching pair silently scores nothing and never equips.
        ItemStack worn = inventory.armorItemInSlot(3 - armorIndex);
        int bestScore = scoreOf(worn, armorIndex);
        int bestSlot = -1;
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null || stack.getCount() != 1) {
                continue; // only a lone piece, never a stack
            }
            if (!(stack.getItem() instanceof ItemArmor)) {
                continue;
            }
            ItemArmor armor = (ItemArmor) stack.getItem();
            if (armor.armorType.getSlotIndex() != armorIndex) {
                continue;
            }
            int score = scoreOf(stack, armorIndex);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * What a piece is worth for its own slot: base defence, enchantment and
     * toughness.
     *
     * <p>Protection is the enchant vanilla's own armour math counts, so a
     * diamond piece out-enchanted by an iron one is not treated as an upgrade,
     * and the reverse is.
     */
    private static int scoreOf(ItemStack stack, int armorIndex) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
            return 0;
        }
        ItemArmor armor = (ItemArmor) stack.getItem();
        // Toughness joined damageReduceAmount in 1.9: vanilla weights it
        // against the attacker's damage, so it is scaled rather than added
        // whole - one point of toughness is worth about that much of a
        // defence point at the strength the numbers actually reach.
        int score = armor.damageReduceAmount;
        score += EnchantmentHelper.getEnchantmentLevel(protectionId(), stack);
        score += (int) (armor.toughness * 2.0f);
        return score;
    }

    /** The enchantment vanilla's protection registers under. */
    private static Enchantment protectionId() {
        return Enchantment.getEnchantmentByLocation("protection");
    }

    /** Inventory index -> the container slot the player half uses. */
    private static int mainToContainerSlot(int mainIndex) {
        return mainIndex < 9 ? 36 + mainIndex : mainIndex;
    }

    /**
     * Inventory armour index -> the container slot it is worn in.
     *
     * <p>One-to-one. The container's own loop builds slot {@code 5 + i} for
     * armour index {@code i}, so slot 5 is the helmet and slot 8 the boots.
     * An earlier version read the GUI x-coordinate as the slot index and
     * reversed this; the pieces then landed in a slot that refused them, so
     * nothing was ever equipped.
     */
    private static int containerArmorSlot(int armorIndex) {
        return ARMOR_SLOT_BASE + armorIndex;
    }

    @Override
    protected void onDisable() {
        cooldown = 0;
    }
}
