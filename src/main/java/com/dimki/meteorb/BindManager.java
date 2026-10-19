package com.dimki.meteorb;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Global key handling for Forge 1.12.2, carrying the scheme across from the
 * b1.7.3 and 1.8 builds.
 *
 * <p>The scheme is the same: Right Shift opens the module menu; while the menu
 * is open, the next key press is consumed by the bind capture; Escape cancels,
 * Delete clears; a key already taken by another module warns first and binds on
 * the second press.
 *
 * <p>Delivery is the same as 1.8: routes a key pressed with a screen open into
 * {@code GuiScreen.handleKeyboardInput()}, which drains the LWJGL queue and
 * hands each press to the open screen's {@code keyTyped} - so the menu and the
 * capture live in {@link MeteorScreen}, and this class owns only the menu key
 * and the in-world toggles.
 *
 * <p>ClientTickEvent is used for the in-world toggles only, and on its START
 * phase: the flag-copy that AutoJump depends on happens inside the entity
 * update, so an END-phase write is too late for it by one tick.
 */
public final class BindManager {
    public static final int KEY_NONE = 0;

    /** Category our binds appear under, in the vanilla controls screen. */
    public static final String CATEGORY = "MeteorB";

    /** True while the menu screen is open. */
    public static boolean menuOpen;

    private BindManager() {
    }

    /** The key code the menu is bound to, read back from the binding. */
    public static int getMenuKey() {
        return menuBind.getKeyCode();
    }

    /** Opens the menu. The screen itself is what receives the keys. */
    public static void openMenu() {
        net.minecraft.client.Minecraft mc =
                net.minecraft.client.Minecraft.getMinecraft();
        menuOpen = true;
        mc.displayGuiScreen(new MeteorScreen());
    }

    /** Closes the menu, unless it was already closed. */
    public static void closeMenu() {
        net.minecraft.client.Minecraft mc =
                net.minecraft.client.Minecraft.getMinecraft();
        menuOpen = false;
        if (mc.currentScreen instanceof MeteorScreen) {
            mc.displayGuiScreen(null);
        }
    }

    public static void toggleMenu() {
        if (menuOpen) {
            closeMenu();
        } else {
            openMenu();
        }
    }

    /**
     * In-world module toggles, driven from the client tick.
     *
     * <p>Deliberately does NOT drain the LWJGL event queue. {@code runTick()}
     * has exactly one {@code Keyboard.next()} loop and it feeds vanilla's own key
     * binds - reading the queue first, which is what the earlier version did on
     * the START phase, starves {@code KeyBinding.onTick} and the player can no
     * longer walk or jump. {@code KeyBinding} itself works off key *state*, not
     * the queue, so polling state here is both safe and sufficient.
     *
     * <p>Edge detection: a key counts once per press, not once per tick it is
     * held down, otherwise holding the menu key flickers the menu open and
     * closed every tick.
     */
    /**
     * The menu key as a vanilla KeyBinding.
     *
     * LWJGL's Keyboard.isKeyDown does not work here - measured: while the player
     * was visibly moving, all three probed keys read false, so polling it can
     * never fire. KeyBinding is what vanilla itself uses: it is fed by the one
     * Keyboard.next() loop in runTick, and isPressed() is edge-detected (it is
     * true exactly once per press), which removes the need for the manual
     * held-set bookkeeping that the LWJGL version required.
     */
    public static KeyBinding menuBind;

    static {
        // Registered with the "MeteorB" category so it appears among the
        // controls, exactly like a vanilla bind.
        menuBind = new KeyBinding("Open MeteorB menu",
                org.lwjgl.input.Keyboard.KEY_RSHIFT, "MeteorB");
    }

    /**
     * Registers our key binding with the game's control list.
     *
     * The binding must live in GameSettings.keyBindings, because that array is
     * the only thing vanilla feeds from its single Keyboard.next() loop - a
     * KeyBinding nobody added is never asked about a key.
     */
    public static void registerKeybind() {
        addKeyBinding(menuBind);
    }

    /** Adds one binding to GameSettings.keyBindings, unless already there. */
    public static void addKeyBinding(KeyBinding binding) {
        net.minecraft.client.settings.GameSettings settings =
                net.minecraft.client.Minecraft.getMinecraft().gameSettings;
        if (settings == null) {
            return;
        }
        KeyBinding[] all = settings.keyBindings;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == binding) {
                return;
            }
        }
        KeyBinding[] grown = new KeyBinding[all.length + 1];
        System.arraycopy(all, 0, grown, 0, all.length);
        grown[all.length] = binding;
        settings.keyBindings = grown;
    }

    public static void onClientTick(net.minecraft.client.Minecraft mc) {
        if (mc.player == null || mc.world == null) {
            return;
        }

        if (menuOpen) {
            return; // the menu owns the rest of the keys
        }

        if (menuBind.isPressed()) {
            toggleMenu();
            return;
        }

        // Module binds, the b1.7.3 scheme: one key per module, toggled on press.
        for (Module module : Modules.get().getAll()) {
            KeyBinding binding = module.getBinding();
            if (binding != null && binding.isPressed()) {
                module.toggle();
            }
        }
    }

    /**
     * The tick listener, in its own public class.
     *
     * Forge only picks up @SubscribeEvent on public methods of a registered
     * instance, and an anonymous Object was silently dropped - the first
     * version used one and the handler never ran at all. The working pattern in
     * this codebase (MeteorB.ClientEventBridge) is a public static class.
     */
    public static class TickHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            BindManager.onClientTick(
                    net.minecraft.client.Minecraft.getMinecraft());
        }
    }

    /**
     * Registers the tick handler on the same buses the working bridge uses.
     *
     * `EVENT_BUS` alone was not enough: the tick handler was constructed and
     * registered, but its event never fired, while MeteorB.ClientEventBridge -
     * which registers on BOTH the FML bus and the Forge bus - ticks normally
     * from the same place. Mirroring its registration fixes that.
     */
    public static void register() {
        net.minecraftforge.fml.common.FMLCommonHandler.instance().bus()
                .register(new TickHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
                .register(new TickHandler());
    }

    /** @deprecated use {@link #register()}; kept for the explicit-bus call. */
    @Deprecated
    public static void register(
            net.minecraftforge.fml.common.eventhandler.EventBus bus) {
        bus.register(new TickHandler());
    }
}
