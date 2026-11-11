package com.sharklab;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * PanelBoot — applies the skin to the panel without opening the head unit UI.
 *
 * Used when the user ticks "apply on power-on" in the settings: at startup only the panel
 * window appears, the head unit keeps showing what it was showing.
 */
public final class PanelBoot {

    private static final String TAG = "SharkLab";

    private PanelBoot() {
    }

    /* does not reapply in a burst: the theme flickered (animation) when two calls landed together */
    private static long lastApply;

    public static void apply(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastApply < 12000 || ClusterOverlay.running()) return;
        lastApply = now;
        SharedPreferences p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE);
        String skin = p.getString("skin", "byd");
        boolean overlay = p.getBoolean("overlay", true);

        final Context appCtx = ctx.getApplicationContext();
        android.os.PowerManager pm =
                (android.os.PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            AdbShell.trace(appCtx, "boot: head unit off, nothing applied");
            return;
        }
        PanelRelease.session = true;
        AdbShell.start(ctx);
        AdbShell.resume();
        AdbShell.watchdog(ctx);
        /* order measured in the car: the window first, 16 projects, 45 removes the BYD widgets.
           Without the window, 45 shows the native layout. */
        final Context app = ctx.getApplicationContext();
        final String url = urlFor(ctx, skin);
        final boolean cast = p.getBoolean("cluster_cast", true);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                boolean ok = overlay && ClusterOverlay.show(app, url);
                Log.i(TAG, "boot: overlay=" + ok + " " + ClusterOverlay.error());
                AdbShell.trace(app, "boot: overlay=" + ok);
                if (!ok) return;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        AdbShell.execOnce(
                                "service call AutoContainer 2 i32 1000 i32 16 s16 null; sleep 1.5; "
                                        + "service call AutoContainer 2 i32 1000 i32 45 s16 null");
                        AdbShell.trace(app, "boot: 16/45 sent");
                    }
                }, 2200);
            }
        }, 1500);
        p.edit().putBoolean("enabled", true).apply();
        startService(ctx);
    }

    /** same URL the head unit builds for the skin (whole panel, 24 h, system language) */
    private static String urlFor(Context ctx, String id) {
        String rel = "dash/" + id + "/index.html";
        File ext = new File(ctx.getExternalFilesDir(null), rel);
        String base = ext.isFile() ? ("file://" + ext.getAbsolutePath()) : ("file:///android_asset/" + rel);
        String lang = Locale.getDefault().getLanguage();
        if (!("pt".equals(lang) || "es".equals(lang) || "en".equals(lang) || "zh".equals(lang))) lang = "es";
        return base + "#lab=1&panel=1&full=1&h24=1&lang=" + lang;
    }

    private static void startService(Context ctx) {
        try {
            Intent i = new Intent(ctx, PanelService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "boot: service did not start", t);
        }
    }
}
