package com.dimki.meteorb;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;

/**
 * AutoTool: while mining, the best tool goes into the held slot and the
 * previous slot comes back.
 *
 * <p>Ported from the 1.7.3 and 1.8 builds, where the behaviour was already
 * right. Three 1.12.2 changes, each read off the jar rather than assumed:
 *
 * <ul>
 *   <li>the mining-speed call is {@code getDestroySpeed(ItemStack,
 *       IBlockState)}, taking a block state rather than a block - the 1.9
 *       block-state rework;</li>
 *   <li>{@code RayTraceResult.Type} was not renamed by the mapping either, so
 *       BLOCK is found by {@code name()} through the enum's own constants, as
 *       1.8 had to.</li>
 * </ul>
 *
 * <p>The selected slot is written directly and restored when the block breaks
 * or the target moves, so the hotbar is never left on the tool.
 */
public class AutoTool extends Module {
    /** Default toggle key: G. */
    public static final int DEFAULT_KEY = org.lwjgl.input.Keyboard.KEY_G;

    private static final int HOTBAR_SIZE = 9;

    /** RayTraceResult.Type.BLOCK, resolved once through the enum's constants. */
    private static Enum<?> hitBlockType;

    /** Last block aimed at, so a change of target ends the swap. */
    private int swappedX;
    private int swappedY;
    private int swappedZ;

    /** The slot held before a swap; -1 while none is in progress. */
    private int previousSlot = -1;

    public AutoTool() {
        super("AutoTool", "Swaps to the best tool while mining");
        setBindKey(DEFAULT_KEY);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.currentScreen != null || minecraft.player == null) {
            return;
        }

        boolean mining = minecraft.gameSettings.keyBindAttack.isKeyDown();
        RayTraceResult hit = minecraft.objectMouseOver;
        if (!mining || hit == null || !isBlock(hit)) {
            restore(minecraft);
            return;
        }

        BlockPos pos = hit.getBlockPos();
        // Broken through: air now where the block was.
        if (minecraft.world.isAirBlock(pos)) {
            restore(minecraft);
            return;
        }

        // Target moved on to another block: the swap is finished.
        if (previousSlot >= 0 && (pos.getX() != swappedX || pos.getY() != swappedY
                || pos.getZ() != swappedZ)) {
            restore(minecraft);
            return;
        }

        IBlockState state = minecraft.world.getBlockState(pos);
        InventoryPlayer inventory = minecraft.player.inventory;
        if (previousSlot < 0) {
            int bestSlot = bestSlotFor(inventory, state);
            if (bestSlot >= 0) {
                previousSlot = inventory.currentItem;
                inventory.currentItem = bestSlot;
                swappedX = pos.getX();
                swappedY = pos.getY();
                swappedZ = pos.getZ();
            }
        }
    }

    /**
     * The hotbar slot that mines {@code state} faster than the held item, or -1.
     *
     * <p>The sword exception is the 1.7.3 one and is kept: a sword out-mines a
     * fist on almost anything, which would waste the swap on blocks a tool does
     * properly. Cobweb is the case where it genuinely wins.
     */
    private static int bestSlotFor(InventoryPlayer inventory,
                                   IBlockState state) {
        float bestSpeed = heldSpeed(inventory, state);
        int bestSlot = -1;
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            float speed = heldSpeed(stack, state);
            if (stack.getItem() instanceof ItemSword
                    && state.getBlock() != Blocks.WEB && speed < 2.0f) {
                continue;
            }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /** Speed of the held stack against a state; a fist counts as 1.0. */
    private static float heldSpeed(InventoryPlayer inventory,
                                   IBlockState state) {
        ItemStack held = inventory.getCurrentItem();
        return held == null ? 1.0f : heldSpeed(held, state);
    }

    /** Vanilla's own speed call, which already accounts for Efficiency. */
    private static float heldSpeed(ItemStack stack, IBlockState state) {
        return stack.getItem().getDestroySpeed(stack, state);
    }

    private void restore(Minecraft minecraft) {
        if (previousSlot < 0) {
            return;
        }
        if (minecraft.player != null) {
            minecraft.player.inventory.currentItem = previousSlot;
        }
        previousSlot = -1;
    }

    /**
     * RayTraceResult.Type.BLOCK, without naming the enum's constant.
     *
     * <p>Both halves of the name are unreachable here: the mapping leaves the
     * constants as {@code a..c}, and DeriveMcpJar does not rename the nested
     * entry, so the class is not on the compile classpath under its MCP name at
     * all - naming {@code RayTraceResult.Type} or {@code Type.BLOCK} fails to
     * compile. Reflection over the field's own class keeps both out of the way.
     */
    private static boolean isBlock(RayTraceResult hit) {
        if (hit == null || hit.typeOfHit == null) {
            return false;
        }
        if (hitBlockType == null) {
            for (Object c : hit.typeOfHit.getClass().getEnumConstants()) {
                if ("BLOCK".equals(((Enum<?>) c).name())) {
                    hitBlockType = (Enum<?>) c;
                    break;
                }
            }
        }
        return hitBlockType != null && hitBlockType.equals(hit.typeOfHit);
    }

    @Override
    protected void onDisable() {
        previousSlot = -1;
    }
}
