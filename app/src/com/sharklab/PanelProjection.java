package com.sharklab;

import android.content.Context;

/**
 * PanelProjection — brings the container's panel display back when it disappears.
 *
 * Measured in the car (2026-11-16, on a unit left with `fission_bg_xdjaVirtualSurface` = 0 while
 * the container was healthy): commands 16 and 45 are accepted by the container and dropped,
 * because there is no display to project onto. The reference cluster app survives that state by
 * asking for the projection itself before creating its window:
 *
 *   16 → wait 1 s → 35, each one retried until the head unit answers `Result: Parcel(00000000`
 *   (its own loop: 5 tries, 2 s apart) and then polling for the display for up to 6 s.
 *
 * Two more codes were measured on the car (each one destroys the virtual display and creates a
 * new one: `xdja_AutoDisplayService: updateDisplay: createVirtualDisplay`, ids 1 → 2 → 3):
 * 31 (panel mode/resolution) is the one that always works, 30 is the last resort. Both take down
 * whatever was attached to the old display, so they only run when it is gone.
 */
public final class PanelProjection {

    private static final String TAG = "SharkLab";
    private static final String CALL = "service call AutoContainer 2 i32 1000 i32 ";
    /* the container's success line; anything else means it dropped the command */
    private static final String OK = "Parcel(00000000";
    private static final int TRIES = 5;

    private PanelProjection() {
    }

    /** the panel display exists right now */
    public static boolean present(Context ctx) {
        return ClusterOverlay.panelDisplay(ctx) != null;
    }

    /** one command, true when the container answered success */
    static boolean send(Context ctx, int code) {
        String out = AdbShell.run(ctx, CALL + code + " s16 null");
        boolean ok = out != null && out.contains(OK);
        AdbShell.trace(ctx, "projection: " + code + " -> " + (out == null ? "null" : out.trim()));
        return ok;
    }

    /** repeats until the container answers: it drops commands while it rebuilds */
    static boolean sendRetry(Context ctx, int code) {
        for (int i = 0; i < TRIES; i++) {
            if (send(ctx, code)) return true;
            sleep(2000);
        }
        return false;
    }

    /**
     * Leaves the panel display up: creates it when it is gone, does nothing when it is there.
     * Blocking (up to ~40 s in the worst case): call it off the main thread.
     */
    public static synchronized boolean ensure(Context ctx) {
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext();
        if (present(app)) return true;
        AdbShell.trace(app, "projection: panel display missing, asking the container");
        for (int round = 0; round < TRIES; round++) {
            if (!sendRetry(app, 16)) AdbShell.trace(app, "projection: 16 without answer");
            sleep(1000);
            if (!sendRetry(app, 35)) AdbShell.trace(app, "projection: 35 without answer");
            if (wait(app, 6000)) {
                AdbShell.trace(app, "projection: display back after 16/35");
                return true;
            }
            AdbShell.trace(app, "projection: no display after 16/35 round " + (round + 1));
        }
        /* 31 is the resolution/mode request: the container destroys the current virtual display
           and creates a new one for that mode (measured twice: display 1 → 2, 2 → 3, new modeId).
           It is the command that always brings a display back. */
        sendRetry(app, 31);
        if (wait(app, 8000)) {
            AdbShell.trace(app, "projection: display back after 31");
            return true;
        }
        sendRetry(app, 30);
        boolean ok = wait(app, 8000);
        AdbShell.trace(app, "projection: " + (ok ? "display back after 30" : "no display at all"));
        return ok;
    }

    /** polls for the panel display (the container creates it asynchronously) */
    private static boolean wait(Context ctx, long ms) {
        long limit = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < limit) {
            if (present(ctx)) return true;
            sleep(200);
        }
        return present(ctx);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
