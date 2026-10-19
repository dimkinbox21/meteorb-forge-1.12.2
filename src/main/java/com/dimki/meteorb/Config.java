package com.dimki.meteorb;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Config persistence: module enabled-state and bind keys, as JSON at
 * {game}/config/meteorb.json. Carried over from the Babric 1.7.3 build, where
 * it solved the same problem.
 *
 * <p>It is needed here more, not less. Vanilla rewrites options.txt from its own
 * control list on exit, and a KeyBinding we added at runtime is not part of that
 * list's saved state - so without this file every bind is gone after a restart.
 *
 * <p>Saves are deferred: {@link #markDirty()} from anywhere, and the tick loop
 * flushes every few seconds plus on menu close, so the file is not rewritten on
 * every keypress.
 */
public final class Config {
    private static final Object LOCK = new Object();

    private static boolean dirty;
    private static boolean loaded;

    private Config() {
    }

    /**
     * Registers a shutdown hook, so quitting the game outright writes the
     * config too.
     *
     * Forge's own server-stopped event is not usable for this: FMLServerStopped
     * extends FMLStateEvent, which is not an EventBus Event at all, and
     * registering a handler for it makes the mod fail to load - it crashed the
     * game during initialization the first time. A JVM hook runs on every exit
     * path, including a crash, and costs nothing when the config is clean.
     */
    public static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                flushIfDirty();
            }
        }, "MeteorB config flush"));
    }

    /**
     * Records that something changed. Nothing else - the write happens on a
     * lifecycle event, never on a timer.
     *
     * A periodic flush is the wrong default here: the file only ever changes
     * when the user toggles a module or binds a key, so a timer would rewrite
     * an identical file every few seconds during a session that changes
     * nothing.
     */
    public static void markDirty() {
        synchronized (LOCK) {
            dirty = true;
        }
    }

    /** Loads once; safe to call every tick. */
    public static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            loaded = true;
        }
        File file = configFile();
        if (file == null || !file.exists()) {
            return;
        }
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject root = new com.google.gson.JsonParser().parse(reader)
                    .getAsJsonObject();
            JsonObject modules = root.getAsJsonObject("modules");
            if (modules != null) {
                for (Module module : Modules.get().getAll()) {
                    String key = module.getName();
                    if (!modules.has(key)) {
                        continue;
                    }
                    JsonObject data = modules.getAsJsonObject(key);
                    if (data.has("enabled")) {
                        module.setEnabled(data.get("enabled").getAsBoolean());
                    }
                    if (data.has("bind")) {
                        // Goes through the bind path that also builds the
                        // KeyBinding, or the loaded bind would be inert.
                        module.setBindKey(data.get("bind").getAsInt());
                    }
                }
            }
            log("MeteorB config loaded from " + file);
        } catch (Exception e) {
            log("MeteorB failed to load config: " + e);
        }
    }

    /**
     * Writes the file only if something actually changed since the last write.
     *
     * Called from lifecycle events - leaving a world, stopping the game - not
     * from a tick, so an unchanged config is never rewritten to disk.
     */
    public static void flushIfDirty() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            dirty = false;
        }
        save();
    }

    /** Writes the file unconditionally. */
    public static void save() {
        File file = configFile();
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            JsonObject root = new JsonObject();
            JsonObject modules = new JsonObject();
            for (Module module : Modules.get().getAll()) {
                JsonObject data = new JsonObject();
                data.addProperty("enabled", module.isEnabled());
                data.addProperty("bind", module.getBindKey());
                modules.add(module.getName(), data);
            }
            root.add("modules", modules);
            try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                        .toJson(root, writer);
            }
            log("MeteorB config saved to " + file);
        } catch (IOException e) {
            log("MeteorB failed to save config: " + e);
        }
    }

    private static File configFile() {
        Minecraft mc = Minecraft.getMinecraft();
        File dir = mc == null ? null : mc.mcDataDir;
        if (dir == null) {
            return null;
        }
        return new File(new File(dir, "config"), "meteorb.json");
    }

    /** Logs to the game log, so a failure is visible without a file hunt. */
    private static void log(String message) {
        System.err.println("[MeteorB] " + message);
    }
}
