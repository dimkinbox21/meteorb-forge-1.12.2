package com.dimki.meteorb;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

/**
 * The hook the class transformer calls into GuiScreen, and the key handling
 * vanilla will not reach while a screen is open.
 *
 * <p>Vanilla routes a key pressed with a screen open through
 * {@code GuiScreen.handleKeyboardInput()}, which only the open screen receives.
 * A {@code KeyBinding} is asked about a key only when no screen is open, so
 * ChestDump's bind could never fire with a chest on screen - which is exactly
 * where it is used. That is why the transformer hooks the screen instead of
 * relying on the binding.
 *
 * <p>Nothing is faked: the dump still goes through PlayerControllerMP, the same
 * call a real shift-click makes.
 */
public final class GuiKeyHook {
    private GuiKeyHook() {
    }

    /**
     * Called from GuiScreen.handleKeyboardInput(), before the screen sees the
     * key.
     *
     * @return true when a module consumed the key, false to let the screen
     *         handle it as usual
     */
    public static boolean meteorbHandleKey(int key) {
        if (key == Keyboard.KEY_NONE) {
            return false;
        }
        // Escape is never ours to swallow: it is the one key that must always
        // reach the screen, or the menu cannot be closed again.
        if (key == Keyboard.KEY_ESCAPE) {
            return false;
        }
        // While our own menu is open it owns every key, capture included.
        if (BindManager.menuOpen) {
            if (MeteorScreen.isCapturing()) {
                return MeteorScreen.onKeyInMenu(key);
            }
            return true;
        }
        for (Module module : Modules.get().getAll()) {
            if (module instanceof ChestDump && module.isEnabled()
                    && module.getBindKey() == key) {
                ((ChestDump) module).startDump(Minecraft.getMinecraft());
                return true;
            }
        }
        return false;
    }

    /**
     * The key code the screen should see, which is how the verdict reaches it.
     *
     * <p>The transformer replaces the top stack slot - the key argument -
     * with whatever this returns, so the value has to be a real key code.
     * Returning 0 swallows the key: vanilla's keyTyped treats 0 as "no key".
     * Returning the key itself passes it through unchanged, and that is why
     * this is not a plain 1: 1 is KEY_ESCAPE, and handing 1 back for every
     * untouched key made any key close any screen.
     *
     * <p>Substituting rather than branching also keeps the injection
     * branch-free, so no stack-map frames have to be rebuilt at a stage that
     * cannot resolve the class hierarchy.
     *
     * @param key the key vanilla is about to deliver
     * @return 0 when a module consumed the key, otherwise {@code key} unchanged
     */
    public static int meteorbSuppress(int key) {
        return meteorbHandleKey(key) ? 0 : key;
    }
}
