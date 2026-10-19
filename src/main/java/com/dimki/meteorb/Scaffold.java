package com.dimki.meteorb;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Scaffold: places a block under your feet over a gap, and pillars up while
 * jump is held.
 *
 * <p>Ported from the 1.7.3 and 1.8 builds, where the behaviour was already
 * right. Five 1.12.2 changes, each read off the jar:
 *
 * <ul>
 *   <li>{@code Vec3} is {@code Vec3d}, and {@code BlockPos}, {@code MathHelper}
 *       and {@code EnumFacing} moved from {@code util} to {@code util.math};</li>
 *   <li>{@code MathHelper.floor_double} is {@code MathHelper.floor};</li>
 *   <li>placement is {@code processRightClickBlock}, which also takes an
 *       {@code EnumHand} argument that 1.8 did not have;</li>
 *   <li>{@code EnumHand}'s constants are not renamed by the mapping, so the
 *       hand is passed as {@code values()[0]} - MAIN_HAND, the only one the
 *       player places with;</li>
 *   <li>{@code getCollisionBoundingBox} is {@code RayTraceResult}-based here,
 *       but the block-state variant survives, which is what the solid test
 *       uses.</li>
 * </ul>
 *
 * <p>The neighbour list and the aim logic are the 1.8 ones unchanged: glass
 * counts as solid and clickable, lava and water are fillable, and walking into
 * a gap aims at the near cell so the near edge is not left empty.
 */
public class Scaffold extends Module {
    /** Hotbar slots, indexes 0..8 of mainInventory. */
    private static final int HOTBAR_SIZE = 9;

    public Scaffold() {
        super("Scaffold", "Places blocks under you over gaps and while jumping");
    }

    /**
     * An EnumFacing by its readable name.
     *
     * The constants are obfuscated in the compile-time jar, so they cannot be
     * referenced directly; name() is the one stable handle, and in the runtime
     * jar Forge loads the names are readable.
     */
    private static EnumFacing facing(String name) {
        for (EnumFacing f : EnumFacing.values()) {
            if (name.equals(f.name())) {
                return f;
            }
        }
        return EnumFacing.values()[1]; // UP, the safe fallback
    }

    /**
     * The placement call, by its Searge name.
     *
     * MCP's mapping CSVs give this method no readable name in either version,
     * so the derived compile-time jar leaves it obfuscated and reflection is
     * how it is reached. The parameter list is searched for, not assumed: the
     * 1.8 shape (EntityPlayerSP, WorldClient, ItemStack, BlockPos, EnumFacing,
     * Vec3) gains an EnumHand in 1.12.2, and matching on ParameterType rather
     * than arity keeps both working while this class is compiled against one
     * jar.
     */
    private static volatile Method useItemOnBlock;

    private static boolean callUseItemOnBlock(PlayerControllerMP controller,
                                              EntityPlayerSP player,
                                              World world, ItemStack stack,
                                              BlockPos pos, EnumFacing face) {
        try {
            Method m = useItemOnBlock;
            if (m == null) {
                synchronized (Scaffold.class) {
                    m = useItemOnBlock;
                    if (m == null) {
                        m = findPlacement(controller.getClass());
                        if (m == null) {
                            throw new NoSuchMethodException(
                                    "processRightClickBlock");
                        }
                        m.setAccessible(true);
                        useItemOnBlock = m;
                    }
                }
            }
            Vec3d hit = new Vec3d(pos.getX(), pos.getY(), pos.getZ());
            Object[] args;
            Class<?>[] p = m.getParameterTypes();
            int handIndex = -1;
            for (int i = 0; i < p.length; i++) {
                if (p[i].getName().endsWith("EnumHand")) {
                    handIndex = i;
                }
            }
            if (handIndex < 0) {
                // 1.8: player, world, stack, pos, face, hit
                args = new Object[] {player, world, stack, pos, face, hit};
            } else {
                // 1.12.2: player, world, pos, face, hit, hand
                args = new Object[] {player, world, pos, face, hit,
                        p[handIndex].getEnumConstants()[0]};
            }
            Object result = m.invoke(controller, args);
            // 1.8 returned a plain boolean and 1.12.2 an EnumActionResult, so
            // the verdict is read from the result's own shape: SUCCESS (1.12.2)
            // or Boolean.TRUE (1.8). Matching on the return TYPE rather than
            // assuming one is what let the first findPlacement pass reject this
            // very method - it demanded boolean.class.
            if (result instanceof Boolean) {
                return ((Boolean) result).booleanValue();
            }
            return result != null && !"FAIL".equals(String.valueOf(result));
        } catch (Throwable t) {
            // Nothing to undo here: the slot is restored by the caller's
            // finally block, and a failed placement is retried next tick.
            return false;
        }
    }

    /**
     * The placement method, found by shape.
     *
     * Named processRightClickBlock in 1.12.2 and func_178890_a in 1.8, and the
     * parameter ORDER differs between them: 1.8 was (EntityPlayerSP, World,
     * ItemStack, BlockPos, EnumFacing, Vec3), while 1.12.2 is (EntityPlayerSP,
     * WorldClient, BlockPos, EnumFacing, Vec3d, EnumHand) - the hand argument
     * arrived with 1.9 and everything after the world shifted by one. So the
     * match is by WHICH types appear, not by which index they sit at: a first
     * pass demanded ItemStack at index 2 and never found the 1.12.2 method.
     */
    private static Method findPlacement(Class<?> controller) {
        for (Method m : controller.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 6) {
                continue;
            }
            if (!EntityPlayerSP.class.isAssignableFrom(p[0])) {
                continue;
            }
            boolean hasPos = false;
            boolean hasFace = false;
            boolean hasHand = false;
            for (int i = 0; i < p.length; i++) {
                if (p[i].getName().endsWith("BlockPos")) {
                    hasPos = true;
                }
                if (p[i].getName().endsWith("EnumFacing")) {
                    hasFace = true;
                }
                if (p[i].getName().endsWith("EnumHand")) {
                    hasHand = true;
                }
            }
            // A block placement names a position, a face and a hand, and hands
            // only exist from 1.9 on.
            if (!(hasPos && hasFace && hasHand)) {
                continue;
            }
            if (m.getReturnType().equals(void.class)) {
                continue; // must report success or failure somehow
            }
            return m;
        }
        return null;
    }

    @Override
    protected void onDisable() {
        // Nothing to undo: a placement is one-shot and the hotbar slot is
        // restored in a finally block, so switching off mid-bridge leaves no
        // state behind.
    }

    /** A neighbour cell to click, with the face it exposes towards the target. */
    private static final class Neighbour {
        private final int dx;
        private final int dy;
        private final int dz;
        private final EnumFacing face;

        private Neighbour(int dx, int dy, int dz, String face) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.face = facing(face);
        }
    }

    /** In 1.8 these are the six neighbours of a cell. */
    private static final Neighbour[] NEIGHBOURS = {
            new Neighbour(0, 1, 0, "DOWN"),   // above, clicked on its bottom
            new Neighbour(0, -1, 0, "UP"),    // below, clicked on its top
            new Neighbour(1, 0, 0, "WEST"),   // +x neighbour, its -x face
            new Neighbour(-1, 0, 0, "EAST"),  // -x neighbour, its +x face
            new Neighbour(0, 0, 1, "NORTH"),  // +z neighbour, its -z face
            new Neighbour(0, 0, -1, "SOUTH"), // -z neighbour, its +z face
    };

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.currentScreen != null || minecraft.player == null
                || minecraft.world == null) {
            return;
        }
        EntityPlayer player = minecraft.player;
        // posY is the feet level, as in 1.8.
        int feetY = MathHelper.floor(player.posY - 0.001);
        int feetX = MathHelper.floor(player.posX);
        int feetZ = MathHelper.floor(player.posZ);

        boolean rising = player.motionY > 0.0;
        int targetY = rising ? feetY - 1 : feetY;
        int targetX = feetX;
        int targetZ = feetZ;

        if (!rising) {
            // Walking into a gap: aim a little ahead, or the block only
            // appears after the player already stepped into the hole. Never
            // past the first gap cell, though: a wide pit would otherwise
            // have its near edge left unfilled.
            // movementInput is declared on EntityPlayerSP, not on EntityPlayer.
            EntityPlayerSP sp = (EntityPlayerSP) player;
            float strafe = sp.movementInput.moveStrafe;
            float forward = sp.movementInput.moveForward;
            if (forward != 0.0f || strafe != 0.0f) {
                double sin = MathHelper.sin(player.rotationYaw * 0.017453292f);
                double cos = MathHelper.cos(player.rotationYaw * 0.017453292f);
                double dx = strafe * cos - forward * sin;
                double dz = forward * cos + strafe * sin;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.0) {
                    int nearX = MathHelper.floor(
                            player.posX + dx / len * 0.6);
                    int nearZ = MathHelper.floor(
                            player.posZ + dz / len * 0.6);
                    int standX = MathHelper.floor(
                            player.posX + dx / len * 0.4);
                    int standZ = MathHelper.floor(
                            player.posZ + dz / len * 0.4);
                    // Still over the starting cell means the gap begins
                    // immediately, so use the near cell - otherwise the far
                    // aim leaves the near edge empty and the player falls.
                    boolean steppedOff = standX != feetX || standZ != feetZ;
                    targetX = steppedOff ? nearX : standX;
                    targetZ = steppedOff ? nearZ : standZ;
                }
            }
        }

        if (!isFillable(minecraft.world, targetX, targetY, targetZ)) {
            return;
        }

        InventoryPlayer inventory = player.inventory;

        int placeSlot = -1;
        if (isBlockStack(inventory.getStackInSlot(inventory.currentItem))) {
            placeSlot = inventory.currentItem;
        } else {
            int bestCount = 0;
            for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (isBlockStack(stack) && stack.getCount() > bestCount) {
                    bestCount = stack.getCount();
                    placeSlot = slot;
                }
            }
        }
        if (placeSlot < 0) {
            return; // no blocks in the hotbar
        }

        place(minecraft, player, inventory, targetX, targetY, targetZ,
                placeSlot);
    }

    /**
     * True when a block can be placed INTO this cell.
     *
     * Air, but lava and water too: standing over a lava pool, a block placed
     * into the lava is exactly what keeps you alive - requiring air alone made
     * Scaffold refuse to bridge over any liquid, which is how you die to lava
     * you could have outrun.
     *
     * Anything with a collision box (any solid block, glass included) is not
     * fillable: there is already something there.
     */
    private static boolean isFillable(World world, int x, int y, int z) {
        net.minecraft.block.state.IBlockState state =
                world.getBlockState(new BlockPos(x, y, z));
        Material material = state.getBlock().getMaterial(state);
        return material == Material.AIR || material == Material.WATER
                || material == Material.LAVA;
    }

    /**
     * True when clicking this block opens something instead of placing.
     *
     * <p>Found by reading the 1.12.2 jar rather than by listing blocks: 34
     * vanilla classes declare their own {@code onBlockActivated} - chest, ender
     * chest, shulker box, furnace, hopper, dispenser, workbench, anvil, TNT,
     * door, lever, button - and each of those answers "used" to the client the
     * moment it is clicked, so the placement call reports SUCCESS even though no
     * block went in. That is exactly how Scaffold came to open a chest and
     * believe it had built.
     *
     * <p>A list of names is not the test, though. {@code BlockTrappedChest} is
     * absent from the jar entirely - a trapped chest is a {@code BlockChest}
     * carrying a type property - so matching class names would miss it, and
     * "extends BlockContainer" is both too narrow and too wide: 22 classes
     * extend it, but the workbench is not one of them while the mob spawner,
     * end portal and banner are, and those three are not clickable at all.
     *
     * <p>The reliable sign is what the block DOES: override activation, AND
     * either carry an inventory or be able to show the player a screen. Both
     * halves are needed. Overriding alone rejects ordinary building blocks
     * correctly, but it would also reject a door or a lever - and a door is
     * still something to click, since it opens but places nothing in the way
     * of a block going in.
     */
    private static boolean opensGui(net.minecraft.block.state.IBlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        boolean hasOwn;
        try {
            hasOwn = block.getClass().getDeclaredMethod(
                    "onBlockActivated", net.minecraft.world.World.class,
                    net.minecraft.util.math.BlockPos.class,
                    net.minecraft.block.state.IBlockState.class,
                    net.minecraft.entity.player.EntityPlayer.class,
                    net.minecraft.util.EnumHand.class,
                    net.minecraft.util.EnumFacing.class,
                    float.class, float.class, float.class) != null;
        } catch (NoSuchMethodException e) {
            return false; // no own handler: an ordinary building block
        }
        if (!hasOwn) {
            return false;
        }
        // Stores something: every chest, furnace, hopper, dispenser, brewing
        // stand. Covers the trapped chest, which declares no handler of its own.
        if (block instanceof net.minecraft.inventory.IInventory) {
            return true;
        }
        // Or shows the player a screen: the workbench, which stores nothing but
        // still opens a GUI (it is a Block, not a BlockContainer).
        return block instanceof net.minecraft.block.BlockWorkbench;
    }

    /**
     * Places into the cell by clicking a solid neighbour with the face that
     * points at the cell. First solid neighbour wins; the held slot is restored
     * whatever happens, so the hotbar is never left on the scaffold block.
     */
    private void place(Minecraft minecraft, EntityPlayer player,
                       InventoryPlayer inventory, int x, int y, int z,
                       int placeSlot) {
        int prevSlot = inventory.currentItem;
        inventory.currentItem = placeSlot;
        try {
            for (int i = 0; i < NEIGHBOURS.length; i++) {
                Neighbour n = NEIGHBOURS[i];
                BlockPos pos = new BlockPos(x + n.dx, y + n.dy, z + n.dz);
                if (!isSolid(minecraft.world, pos)) {
                    continue; // need something to click against
                }
                if (opensGui(minecraft.world.getBlockState(pos))) {
                    continue; // it would open instead of accept a block
                }
                if (callUseItemOnBlock(minecraft.playerController,
                        (EntityPlayerSP) player, minecraft.world,
                        inventory.getStackInSlot(placeSlot), pos, n.face)) {
                    return;
                }
            }
        } finally {
            inventory.currentItem = prevSlot;
        }
    }

    /** A block with a collision box, glass included - something to click on. */
    private static boolean isSolid(World world, BlockPos pos) {
        net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
        return state.getBlock().getCollisionBoundingBox(state, world, pos) != null;
    }

    /**
     * True when the stack is a placeable FULL block.
     *
     * Torches, saplings, slabs and stairs are ItemBlocks but do not fill a
     * whole cell, so they cannot be stood on and are useless here.
     */
    private static boolean isBlockStack(ItemStack stack) {
        if (stack == null || stack.getCount() < 1) {
            return false;
        }
        if (!(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        net.minecraft.block.state.IBlockState state =
                ((ItemBlock) stack.getItem()).getBlock().getDefaultState();
        return state.getBlock().isFullCube(state);
    }
}
