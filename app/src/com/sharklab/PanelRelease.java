package com.sharklab;

import android.util.Log;

/** PanelRelease — gives the panel back to BYD's original layout (cmd 18) and pauses the ADB loop. */
public final class PanelRelease {

    private static final String TAG = "SharkLab";

    private PanelRelease() {
    }

    /**
     * Valid only inside the process: the skin is on the panel because the user (or the startup)
     * asked for it NOW. If the process dies — the car shuts off and PanelService is revived — the
     * skin does not come back: that was what kept reappearing on top of the open-door alert.
     */
    public static volatile boolean session;

    /** gives the panel back and marks the skin as unapplied (the app does not resume by itself) */
    public static void release(android.content.Context ctx) {
        session = false;
        if (ctx != null) {
            /* applied AND enabled: the 60 s loop recreated the window with `enabled` still on */
            ctx.getSharedPreferences("lab", android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean("applied", false).putBoolean("enabled", false).apply();
        }
        release();
    }

    public static void release() {
        session = false;
        /* the skin window has to go out BEFORE giving the panel back: with the car off BYD
           lights the display for the open-door alert and our window stayed on top of it */
        ClusterOverlay.hide();
        AdbShell.stop();
        /* the reference app's sequence to give the panel back: 46, 18, 0 */
        AdbShell.execOnce("service call AutoContainer 2 i32 1000 i32 46 s16 null; sleep 1.2; "
                + "service call AutoContainer 2 i32 1000 i32 18 s16 null; sleep 1; "
                + "service call AutoContainer 2 i32 1000 i32 0 s16 null");
        if (PanelCast.send(18)) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
            if (binder == null) return;
            Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
            Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            svc.getClass().getMethod("sendInfo", int.class, int.class, String.class)
                    .invoke(svc, 1000, 18, "");
            Log.i(TAG, "panel: given back to BYD's original");
        } catch (Throwable t) {
            Log.w(TAG, "panel: release failed", t);
        }
    }
}
