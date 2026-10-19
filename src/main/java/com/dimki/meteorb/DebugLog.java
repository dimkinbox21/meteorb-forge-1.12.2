package com.dimki.meteorb;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Simple file logger, same contract as the Babric build: writes next to
 * options.txt so gameplay-time diagnostics are not lost (the vanilla log
 * only captures startup).
 */
public final class DebugLog {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    private DebugLog() {
    }

    public static void log(String format, Object... args) {
        String dir = MeteorB.RUN_DIR;
        if (dir == null) {
            return;
        }
        String msg = TIME.format(new Date()) + " " + String.format(format, args) + "\n";
        synchronized (LOCK) {
            try (FileWriter w = new FileWriter(new File(dir, "meteorb-debug.log"), true)) {
                w.write(msg);
            } catch (IOException e) {
                // ignore, debug only
            }
        }
    }
}
