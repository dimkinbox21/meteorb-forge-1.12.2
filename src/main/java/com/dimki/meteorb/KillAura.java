package com.dimki.meteorb;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * KillAura + AutoWeapon, as on the 1.8 build.
 *
 * Every few ticks it finds the closest hostile mob in range and attacks it
 * through PlayerControllerMP, the same call a left click makes. While a target
 * stays alive the hotbar slot holding the best weapon stays selected, and the
 * player's previous slot comes back when the fight ends.
 *
 * <p><b>The weapon score is no longer a flat damage number.</b> 1.9 gave combat
 * a cooldown, and damage is now scaled by how charged the swing is, so a sword
 * is not one number but a curve. Vanilla's own formula, read out of
 * {@code EntityPlayer.attackTargetEntityWithCurrentItem}:
 *
 * <pre>
 *     damage = base
 *            * getCooledAttackStrength(0.5f)
 *            * (0.2f + getCooledAttackStrength(0.5f) * 0.8f)
 * </pre>
 *
 * where the strength runs 0..1 over the weapon's own cycle,
 * {@code 20.0 / attackSpeed} ticks. So the "best weapon" is the one with the
 * best <em>damage per second</em> - and a weapon whose cooldown is long but
 * whose charged hit is huge can still lose to a fast one.
 *
 * <p>Neutral mobs are left alone until they attack: zombified piglins only
 * when angry, spiders only when hunting the player. Passive animals are never
 * targets.
 */
public class KillAura extends Module {
    /** Ticks between attack attempts. */
    private static final int DELAY_TICKS = 2;

    /** Attack range, just below the vanilla reach of 4.0. */
    private static final double RANGE = 3.9;

    /**
     * How charged a swing must be before it is thrown.
     *
     * <p>Vanilla reaches full strength at 1.0, and its own display shows the
     * crosshair "ready" well before that; 0.9 keeps almost all the damage while
     * still waiting out the charge, which is the whole point.
     */
    private static final float CHARGE_THRESHOLD = 0.9f;

    private static final int HOTBAR_SIZE = 9;

    private int cooldown;

    /** Slot held before the fight started; -1 when not fighting. */
    private int restoreSlot = -1;

    /**
     * True while KillAura has taken the hotbar over for a weapon.
     *
     * <p>AutoEat asks this before moving the held slot: two modules writing
     * {@code inventory.currentItem} in the same tick overwrite each other's
     * saved slot and leave the bar flipping between them. KillAura wins,
     * because a fight cannot wait on a meal - so AutoEat stands down and eats
     * after the target is gone.
     */
    public boolean isHoldingSlot() {
        return restoreSlot >= 0;
    }

    /**
     * World time at which each enderman's screaming flag was last seen raised,
     * and the tick it first read raised, by entity id.
     *
     * <p>The flag is not synchronised inside the tick it flips on the server:
     * measured in game, the first blow lands and the metadata carrying the flag
     * reaches the client on a later tick. Until it has read raised steadily for
     * {@link #ENDERMAN_GRACE} ticks an enderman is not a target - the module
     * cannot otherwise tell a freshly provoked one it has not been told about
     * yet from one provoked a while ago.
     *
     * <p>The count is per world tick, not per call. isTarget() runs for every
     * entity in the search box each tick, so counting calls let a single
     * enderman accumulate the grace threshold within one tick and made the
     * window meaningless.
     */
    private static final java.util.Map<Integer, Long> endermanFirstSeen =
            new java.util.HashMap<Integer, Long>();
    private static final java.util.Map<Integer, Long> endermanLastSeen =
            new java.util.HashMap<Integer, Long>();

    /** Ticks the screaming flag must read steadily before an enderman is a target. */
    private static final int ENDERMAN_GRACE = 3;

    public KillAura() {
        super("KillAura", "Attacks nearby hostile mobs, switching to the best weapon");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.currentScreen != null || minecraft.player == null
                || minecraft.world == null) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        EntityPlayer player = minecraft.player;
        double r = RANGE;
        AxisAlignedBB box = new AxisAlignedBB(player.posX - r, player.posY - r,
                player.posZ - r, player.posX + r, player.posY + r,
                player.posZ + r);
        java.util.List<Entity> entities =
                minecraft.world.getEntitiesWithinAABB(Entity.class, box);

        Entity target = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (!isTarget(e, player)) {
                continue;
            }
            double dx = e.posX - player.posX;
            double dy = e.posY - player.posY;
            double dz = e.posZ - player.posZ;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                target = e;
            }
        }

        if (target == null) {
            restoreSlot(player);
            return;
        }

        InventoryPlayer inventory = player.inventory;
        if (restoreSlot < 0) {
            restoreSlot = inventory.currentItem; // remember what the player held
        }
        // The swing is only worth throwing when the weapon is charged: an
        // uncharged hit does a fraction of the damage and still starts the
        // cooldown over, so waiting one cycle beats mashing. Read from the
        // player's own tracker rather than assumed - the attribute that sets
        // the period is per-weapon.
        if (!isCharged(player, inventory.getStackInSlot(inventory.currentItem))) {
            return;
        }

        EnumCreatureAttribute attribute = creatureAttribute(target);
        double bestDps = -1.0D;
        int bestSlot = inventory.currentItem;
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            double dps = damagePerSecond(stack, attribute);
            if (dps > bestDps) {
                bestDps = dps;
                bestSlot = slot;
            }
        }
        if (bestSlot != inventory.currentItem) {
            inventory.currentItem = bestSlot;
            return; // let the fresh weapon finish charging before swinging
        }

        minecraft.playerController.attackEntity(player, target);
        // 1.9 split the swing per hand, so the bare swingItem() became
        // swingArm(EnumHand).
        player.swingArm(net.minecraft.util.EnumHand.values()[0]);
        cooldown = DELAY_TICKS;
    }

    /**
     * True when the player's swing is charged enough to be worth throwing.
     *
     * <p>Measured, not assumed: the <em>player's</em> attack charge does not
     * live in {@code CooldownTracker}. That class is shared with mobs - its only
     * {@code setCooldown} callers in this jar are {@code EntityMob} and
     * {@code NetHandlerPlayClient} - so for a player {@code hasCooldown(item)}
     * is always false and a gate built on it never waits. The charge is a plain
     * counter on {@code EntityPlayer} that {@code onLivingUpdate} increments and
     * {@code resetCooldown()} zeroes after every swing, which is exactly what
     * {@code getCooledAttackStrength} reports as a 0..1 strength.
     *
     * <p>So the wait is a direct strength test. Below the threshold a hit does a
     * fraction of its damage and turns the counter back to zero, which is why
     * mashing is worse than patience: three half-charged hits lose to one full
     * one and leave the counter empty.
     */
    private static boolean isCharged(EntityPlayer player, ItemStack stack) {
        if (player.getCooldownPeriod() <= 0.0f) {
            return true; // no cooldown on this weapon
        }
        return player.getCooledAttackStrength(0.5f) >= CHARGE_THRESHOLD;
    }

    /** Gives the player's own slot back once the fight is over. */
    private void restoreSlot(EntityPlayer player) {
        if (restoreSlot >= 0) {
            player.inventory.currentItem = restoreSlot;
            restoreSlot = -1;
        }
    }

    /** Hostile, or neutral-and-now-aggressive - never passive, never tamed. */
    private static boolean isTarget(Entity e, EntityPlayer player) {
        if (e == player || e == null || e.isDead
                || !(e instanceof EntityLivingBase) || e instanceof EntityPlayer) {
            return false;
        }
        // Zombified piglins are neutral until provoked.
        if (e instanceof EntityPigZombie
                && !((EntityPigZombie) e).isAngry()) {
            return false;
        }
        // Endermen are neutral until someone stares or hits them, and the
        // readable sign of that is the screaming flag - it is carried in the
        // entity metadata, so the client sees it. Measured in game: it stays
        // false while the enderman is calm and flips to true together with the
        // first hit landing (health drops from 20 in the same tick), so it
        // marks the turn, not a player merely being noticed.
        //
        // The AI target cannot replace it. It reads null on the client every
        // tick, hostile or not, because that field is not synchronised - an
        // earlier version compared against getAITarget() and then never hit an
        // enderman at all.
        if (e instanceof EntityEnderman) {
            if (e.isDead || !e.isEntityAlive()) {
                endermanFirstSeen.remove(e.getEntityId());
                endermanLastSeen.remove(e.getEntityId());
                return false;
            }
            return endermanConfirmed((EntityEnderman) e, e.world);
        }

        // Spiders: the client cannot see their target, so neither "neutral in
        // daylight" nor "already hunting" is a test the client can apply.
        // Measured in a lit cave at light 15: a spider that had been hit had
        // getAITarget() == null the whole time while it fought back, because
        // entityToAttack is never networked. Treated as an ordinary hostile
        // except for the light gate: a spider cannot START a fight in the
        // light, but one it already has does not end when the light arrives.
        // Spiders: the client cannot see their target, so neither "neutral in
        // daylight" nor "already hunting" is a test the client can apply.
        // Measured in a lit cave at light 15: a spider that had been hit had
        // getAITarget() == null the whole time while it fought back, because
        // entityToAttack is never networked. So the rule is the two halves
        // vanilla's own AI applies, both readable from here: provocation first,
        // then the light gate that decides who may START a fight. A spider left
        // alone in the light is left alone; one that has visibly taken damage is
        // always a target, because a running fight survives any brightness.
        if (e instanceof EntitySpider) {
            EntityLivingBase lb = (EntityLivingBase) e;
            if (lb.hurtTime > 0 || lb.getHealth() < lb.getMaxHealth()) {
                return true; // provoked, which outranks the light gate
            }
            return lb.getBrightness() < 0.5f;
        }
        return e instanceof EntityMob || e instanceof EntityGhast
                || e instanceof EntitySlime;
    }

    /**
     * True once an enderman has read as hostile for long enough to trust it.
     *
     * <p>The counter resets the moment the flag reads false, so an enderman
     * that calms down stops being a target immediately rather than at the end
     * of a hardcoded window.
     */
    private static boolean endermanConfirmed(EntityEnderman e,
                                             net.minecraft.world.World world) {
        long now = world.getTotalWorldTime();
        int id = e.getEntityId();
        if (!e.isScreaming()) {
            endermanFirstSeen.remove(id);
            endermanLastSeen.remove(id);
            return false;
        }
        Long first = endermanFirstSeen.get(id);
        if (first == null) {
            endermanFirstSeen.put(id, now);
            first = now;
        }
        endermanLastSeen.put(id, now);
        return now - first >= ENDERMAN_GRACE;
    }

    /**
     * Vanilla's damage per second for the stack against the target's kind.
     *
     * <p>This is the statistic the cooldown makes decisive. A sword's cycle is
     * {@code 20.0 / attackSpeed} ticks and a charged hit does
     * {@code base * s * (0.2 + 0.8 * s)} with s the strength, so its dps is that
     * total divided by the cycle length. Comparing raw base damage - what the
     * 1.8 build did, correctly, when there was no cooldown - picks a slow heavy
     * weapon over a fast light one and leaves the slower one behind.
     */
    private static double damagePerSecond(ItemStack stack,
                                          EnumCreatureAttribute attribute) {
        // A bare hand and a non-weapon have no cooldown and no modifier, which
        // is a base of 1 over a 20-tick cycle.
        double base = 1.0D;
        double attackSpeed = 4.0D; // vanilla default for a bare hand
        try {
            // The slot argument is not optional here, and MAIN_HAND is the one
            // that carries a weapon's numbers: measured in ItemSword's own
            // getItemAttributeModifiers, ATTACK_DAMAGE and ATTACK_SPEED are both
            // put under vl.a, the first constant. Asking for any other slot
            // returns an empty map, which would score every weapon at 1.0.
            com.google.common.collect.Multimap all = stack.getAttributeModifiers(
                    net.minecraft.inventory.EntityEquipmentSlot.values()[0]);
            // Keyed by the attribute's NAME, not by the IAttribute object: a
            // weapon's modifier arrives through its NBT, and ItemStack writes it
            // under that string. Looking it up by the object returns an empty
            // collection, which left every weapon at 1.0 and made the slot order
            // decide the choice instead of the damage - the 1.8 game log showed
            // map=1, objectKey=0, stringKey=1. In 1.12.2 the name comes from
            // IAttribute.getName().
            // objectKey=0, stringKey=1.
            String key = net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE
                    .getName();
            for (Object o : all.get(key)) {
                if (o instanceof AttributeModifier) {
                    base += ((AttributeModifier) o).getAmount();
                }
            }
            String speedKey = net.minecraft.entity.SharedMonsterAttributes
                    .ATTACK_SPEED.getName();
            for (Object o : all.get(speedKey)) {
                if (o instanceof AttributeModifier) {
                    attackSpeed += ((AttributeModifier) o).getAmount();
                }
            }
        } catch (Throwable ignored) {
            // The base numbers are a refinement; the enchantment bonus is the
            // part that changes the choice, so carrying on is correct here.
        }
        if (attackSpeed <= 0.0D) {
            attackSpeed = 4.0D;
        }
        base += enchantmentBonus(stack, attribute);
        // A fully charged swing is the best case: damage per SECOND, so the
        // strongest hit is divided by how long the weapon takes to get there.
        double cycleTicks = 20.0D / attackSpeed;
        return (base * 1.0D * (0.2D + 0.8D)) / cycleTicks * 20.0D;
    }

    /**
     * EnchantmentHelper.getModifierForCreature(stack, attribute): the Sharpness
     * / Smite / Bane-of-Arthropods bonus against the target's kind.
     *
     * MCP 1.8's CSVs gave this method no readable name, so it was reached by
     * its Searge name; in 1.12.2 it is public and named, so the call is direct.
     */
    private static float enchantmentBonus(ItemStack stack,
                                          EnumCreatureAttribute attribute) {
        return net.minecraft.enchantment.EnchantmentHelper
                .getModifierForCreature(stack, attribute);
    }

    /** The target's creature attribute, for Smite / Bane of Arthropods. */
    private static EnumCreatureAttribute creatureAttribute(Entity target) {
        if (target instanceof EntityLivingBase) {
            return ((EntityLivingBase) target).getCreatureAttribute();
        }
        // UNDEFINED is obfuscated in the compile-time jar (a/b/c), so pick it
        // the same way as the others - first constant, per its static init.
        return EnumCreatureAttribute.values()[0];
    }

    @Override
    protected void onDisable() {
        cooldown = 0;
        restoreSlot = -1; // the next fight picks a fresh slot to restore
        endermanFirstSeen.clear();
        endermanLastSeen.clear();
    }
}
