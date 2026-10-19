package com.dimki.meteorb;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.CooldownTracker;

/**
 * Compiles the pieces the port depends on, so each 1.12.2 rename is found by
 * the compiler rather than by a guess. It is never loaded by the game.
 */
public final class Probe112 {
    private Probe112() {
    }

    /**
     * The attack multiplier, and the tracker that yields it.
     *
     * <p>getCooldownTracker has no MCP name in the snapshot - it stayed under
     * its Searge name func_184811_cZ - so it is reached by reflection, the way
     * 1.8 reached func_152377_a.
     */
    public static float attackCharge(EntityPlayer p, float partial) throws Exception {
        Object t = p.getClass().getMethod("func_184811_cZ").invoke(p);
        ItemStack held = p.inventory.getCurrentItem();
        if (held == null || t == null) {
            return 1.0f;
        }
        float charge = (Float) t.getClass().getMethod("getCooldown",
                net.minecraft.item.Item.class, float.class)
                .invoke(t, held.getItem(), partial);
        return p.getCooledAttackStrength(partial) * (0.2f + charge * 0.8f);
    }

    /**
     * ClickType is an enum now - but its constants are NOT renamed by the
     * mapping (0 of 7 survived MCP), so the named constants fail to compile and
     * the port has to reach one by ordinal or by name().
     */
    public static ItemStack pickup(Container c, int slot, EntityPlayer p) {
        return c.slotClick(slot, 0, ClickType.values()[0], p);
    }

    public static ItemStack quickMove(Container c, int slot, EntityPlayer p) {
        return c.slotClick(slot, 0, ClickType.values()[1], p);
    }

    /**
     * Armour is typed by the slot itself, and the container's armour slots run
     * 5..8 in that slot's order.
     */
    public static int armorContainerSlot(EntityEquipmentSlot slot) {
        return 5 + slot.getIndex();
    }

    public static int damageOf(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
            return 0;
        }
        ItemArmor a = (ItemArmor) stack.getItem();
        return a.damageReduceAmount;
    }

    /**
     * The inventory is a list, not an array, in 1.12.2 - and its generic type
     * is an obfuscated class that is not public, so mainInventory.get(i) does
     * not compile from here. getStackInSlot is the public door.
     */
    public static ItemStack slotOf(InventoryPlayer inv, int i) {
        return inv.getStackInSlot(i);
    }

    public static int foodOf(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemFood)) {
            return 0;
        }
        return ((ItemFood) stack.getItem()).getHealAmount(stack);
    }



    public static boolean armorFits(Slot s, ItemStack stack) {
        return s.isItemValid(stack);
    }

    public static NBTTagCompound tagOf(ItemStack stack) {
        return stack.getTagCompound();
    }
}
