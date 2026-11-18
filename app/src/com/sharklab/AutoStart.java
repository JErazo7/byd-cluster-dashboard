package com.sharklab;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * AutoStart — every process start is a chance to put the skin back after a reboot.
 *
 * The head unit's framework skips `BOOT_COMPLETED` for installed apps unless the package is
 * switched off in `com.byd.appstartmanagement` (`ssc_skip`, measured 2026-11-18), and the adbd
 * watchdog does not survive a real reboot. So the app hooks whatever *does* start a process:
 * the notification listener binding, a screen-on broadcast, the invisible wake activity.
 * It only acts when the user asked for the boot behaviour, and never releases anything.
 */
public final class AutoStart {

    private AutoStart() {
    }

    public static void kick(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences("lab", Context.MODE_PRIVATE);
        if (!p.getBoolean("bootApply", p.getBoolean("autostart", false))) return;
        AdbShell.trace(app, "autostart: process up (" + app.getClass().getSimpleName() + ")");
        AdbShell.start(app);
        try {
            Intent i = new Intent(app, PanelService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable t) {
            AdbShell.trace(app, "autostart: service failed " + t);
        }
        /* PanelBoot decides: head unit awake, window not already attached, not within 12 s */
        PanelBoot.apply(app);
    }
}
