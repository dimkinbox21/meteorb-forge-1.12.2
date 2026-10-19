package com.dimki.meteorb;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of all modules. Singleton, initialized statically.
 */
public class Modules {
    private static final Modules INSTANCE = new Modules();

    private final List<Module> modules = new ArrayList<Module>();

    public final ChestDump chestDump = add(new ChestDump());
    public final AutoTool autoTool = add(new AutoTool());
    public final Scaffold scaffold = add(new Scaffold());
    public final AutoArmor autoArmor = add(new AutoArmor());

    private Modules() {
    }

    public static Modules get() {
        return INSTANCE;
    }

    public List<Module> getAll() {
        return modules;
    }

    private <T extends Module> T add(T module) {
        modules.add(module);
        return module;
    }

    public void tick(Minecraft minecraft) {
        for (Module module : modules) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                module.onTick(minecraft);
            } catch (Throwable t) {
                // A broken module must never kill the game; keep the rest alive.
                module.setEnabled(false);
                DebugLog.log("Module %s crashed, disabled:\n", module.getName());
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                DebugLog.log("%s", sw.toString());
            }
        }
    }
}
