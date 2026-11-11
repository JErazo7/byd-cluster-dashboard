package com.sharklab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BootReceiver — brings the skin up on the panel when the car turns on.
 *
 * With the package in the AutoContainer whitelist (com.xdja.clusterdemo), the app itself can
 * send the mirror to the panel; no adb and no shell daemon needed.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if ("com.sharklab.RELEASE".equals(a) || "android.intent.action.ACTION_SHUTDOWN".equals(a)) {
            PanelRelease.release(ctx);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"android.intent.action.HTC_QUICKBOOT_BOOT_COMPLETED".equals(a)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_USER_UNLOCKED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        applyChoice(ctx);
    }

    /**
     * Decides what to do at startup according to the "start automatically" toggle.
     * Extracted so it can be exercised without rebooting the car (see MainActivity).
     */
    public static void applyChoice(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE);
        boolean bootApply = p.getBoolean("bootApply", p.getBoolean("autostart", false));
        if (!bootApply) {
            /* toggle off: the panel stays with BYD and the skin does not come up by itself */
            p.edit().putBoolean("enabled", false).putBoolean("applied", false).apply();
            PanelRelease.release(ctx);
            return;
        }
        /* with the car off BYD lights the display only for the open-door alert: the head unit
           screen being off is the "we are not driving" signal, so nothing is applied */
        android.os.PowerManager pm =
                (android.os.PowerManager) ctx.getSystemService(android.content.Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            p.edit().putBoolean("applied", false).putBoolean("enabled", false).apply();
            PanelRelease.release(ctx);
            return;
        }
        p.edit().putBoolean("applied", true).putBoolean("enabled", true).apply();
        /* on: applies the skin to the panel without opening the head unit UI */
        PanelBoot.apply(ctx);
    }
}
