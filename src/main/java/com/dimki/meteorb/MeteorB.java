package com.dimki.meteorb;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * MeteorB for Minecraft 1.8 (Forge).
 *
 * The Forge port of the Babric 1.7.3 build. Babric/Fabric mixins do not exist
 * on Forge, so the AutoJump hook is a coremod class transformer
 * (CoremodPlugin) and everything else is driven from Forge events.
 */
// Deliberately NOT clientSideOnly. That flag makes Forge drop the mod on a
// dedicated server ("Disabling mod {} it is client side only."), and this mod
// has to load on a server: every packet it sends is an ordinary inventory or
// attack packet, so a server that also has the jar must accept it.
//
// What makes it a *client* mod in the sense that matters is the runtime guard in
// init() - it returns unless the side is CLIENT - plus the fact that nothing
// here is ever driven by anything but a client tick. So: stands on a client,
// works across a server, cannot be installed or configured from the server
// side.
@Mod(modid = MeteorB.MODID, version = MeteorB.VERSION,
        acceptableRemoteVersions = "*")
public class MeteorB {
    public static final String MODID = "meteorb";
    public static final String VERSION = "0.1.0";

    /** Set once the game directory is known, used by the debug log. */
    public static volatile String RUN_DIR;

    /**
     * True while a world is loaded, so leaving it can be told apart from
     * starting up. Without this, GuiOpenEvent fires on every transition to a
     * null screen - including the splash screen closing at startup, which
     * wrote the config on every launch for no reason.
     */
    private static boolean worldLoaded;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // Client only. Forge runs this on BOTH sides, and everything below
        // reaches classes that only exist meaningfully on a client:
        // BindManager.registerKeybind() touches GameSettings and creates a
        // KeyBinding, and the tick bridge calls Minecraft.getMinecraft(). On a
        // dedicated server those are dead weight at best.
        //
        // A @SideOnly annotation here was deliberately avoided: on an event
        // handler Forge's behaviour is not the simple "stripped on the wrong
        // side" that it is on an ordinary method, so the reliable form is a
        // plain runtime guard on the event's own side.
        if (!event.getSide().isClient()) {
            return;
        }
        // Only the client side has a player and a GUI. Both buses, the same
        // way for both handlers: registering on one alone was silently dropped.
        net.minecraftforge.fml.common.FMLCommonHandler.instance().bus()
                .register(new ClientEventBridge());
        MinecraftForge.EVENT_BUS.register(new ClientEventBridge());

        // The menu and its binds.
        BindManager.register();

        // The menu key must live in GameSettings.keyBindings, because that
        // array is the only thing vanilla feeds from its single Keyboard.next()
        // loop. A plain LWJGL poll never saw the key at all.
        BindManager.registerKeybind();

        // AutoJump is vanilla in 1.12.2 and is not ported, so there is no
        // module to switch on here yet.
        Config.markDirty();

        // Flush the config on process exit, whatever the reason.
        Config.installShutdownHook();

        org.apache.logging.log4j.LogManager.getLogger(MODID).info(
                "MeteorB loaded! menu on RightShift - ChestDump, AutoTool, Scaffold, AutoArmor and KillAura so far");
    }

    /**
     * Event handlers, in their own class so the @Mod class stays a plain
     * lifecycle holder.
     */
    public static class ClientEventBridge {
        @SubscribeEvent
        @SideOnly(Side.CLIENT)
        public void onClientTick(TickEvent.ClientTickEvent event) {
            // START, not END. Vanilla copies movementInput.jump into the
            // player's isJumping inside updateEntityActionState() and reads it
            // a few instructions later in the same method, so a value written
            // at END - after runTick() - is overwritten by the keyboard before
            // it is ever read. START lands before that copy and survives it.
            if (event.phase != TickEvent.Phase.START) {
                return;
            }
            // Before the player check: the config restores binds and module
            // states, and it must happen even at the title screen so a bind is
            // live the moment the world loads.
            Config.ensureLoaded();

            net.minecraft.client.Minecraft mc =
                    net.minecraft.client.Minecraft.getMinecraft();
            // Track the world itself, not a screen: a screen opening and closing
            // is not a world change, and treating it as one saved the config
            // while the loading screen was still up.
            boolean worldUp = mc.world != null;
            if (!worldUp && worldLoaded) {
                worldLoaded = false;
                Config.flushIfDirty();
            } else if (worldUp) {
                worldLoaded = true;
            }
            if (mc.player == null || !worldUp) {
                return;
            }
            if (RUN_DIR == null && mc.mcDataDir != null) {
                RUN_DIR = mc.mcDataDir.getAbsolutePath();
            }
            Modules.get().tick(mc);
        }

    }
}
