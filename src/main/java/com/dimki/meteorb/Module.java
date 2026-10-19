package com.dimki.meteorb;

import net.minecraft.client.Minecraft;

/**
 * Base class for all toggleable modules.
 *
 * Unlike the Babric build there is no bind plumbing yet on Forge: this
 * port carries AutoJump only, so the base class keeps enabled/disabled and
 * the tick hook, and nothing else. Binds, the menu and the config file come
 * with the next stage.
 */
public abstract class Module {
    private final String name;
    private final String description;
    private boolean enabled;

    /** Keyboard key that toggles this module, 0 = unbound. LWJGL2 key code. */
    private int bindKey;

    protected Module(String name, String description) {
        this(name, description, false);
    }

    protected Module(String name, String description, boolean enabledByDefault) {
        this.name = name;
        this.description = description;
        this.enabled = enabledByDefault;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }
        Config.markDirty();
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public int getBindKey() {
        return bindKey;
    }

    /**
     * Binds (or unbinds) the module to a key.
     *
     * This is the only bind path, and it must do both jobs: record the code and
     * create the vanilla key binding. Splitting them left the menu writing a
     * code that nothing ever polled, so binds appeared to take and then did
     * nothing - the plain setter alone is what the first version shipped.
     *
     * A raw LWJGL code cannot be polled from a tick - measured: isKeyDown
     * returned false for W, Space and Right Shift while the player was moving.
     * Vanilla's KeyBinding is the only channel that reaches the keyboard, and
     * its isPressed() fires exactly once per press.
     */
    public void setBindKey(int keyCode) {
        this.bindKey = keyCode;
        if (keyCode == BindManager.KEY_NONE) {
            this.binding = null;
            return;
        }
        this.binding = new net.minecraft.client.settings.KeyBinding(
                "Toggle " + getName(), keyCode, BindManager.CATEGORY);
        BindManager.addKeyBinding(this.binding);
        Config.markDirty();
    }

    /** The module's key binding, null until it has been bound. */
    public net.minecraft.client.settings.KeyBinding getBinding() {
        return binding;
    }

    private net.minecraft.client.settings.KeyBinding binding;

    /** Called every client tick while enabled and the player exists. */
    public void onTick(Minecraft minecraft) {
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }
}
