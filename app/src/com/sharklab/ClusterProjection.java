package com.sharklab;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

/**
 * ClusterProjection — experimental VirtualDisplay path for the driver cluster panel.
 *
 * The cluster is not a normal Android display: it is another instance (fission). The supported
 * path in this repo is the overlay window in `ClusterOverlay`; this class is kept for reference
 * and probing.
 *
 * Measured limit: a private VirtualDisplay created with the name the compositor looks for —
 * shared_fission_bg_XDJAScreenProjection_0 (1920×720) — renders and can be captured, but the
 * cluster does not show it. The attach step is privileged. Treat this class as unverified.
 */
public final class ClusterProjection {

    private static final String TAG = "SharkLab";
    private static final String[] NAMES = {
            "shared_fission_bg_XDJAScreenProjection_0",
            "shared_fission_bg_XDJAScreenProjection_1"
    };
    private static final int W = 1920, H = 720, DPI = 320;
    /* layer title observed on the reference app in SurfaceFlinger; used only when probing */
    private static final String SURFACE_NAME = "EvSound painel";

    private static VirtualDisplay display;
    private static Surface surface;
    private static SurfaceTexture texture;
    private static ClusterPresentation presentation;
    private static String error = "";

    private ClusterProjection() {
    }

    public static String error() {
        return error;
    }

    public static boolean running() {
        return display != null;
    }

    /**
     * Creates the display like the reference app: with the MediaProjection token, flags
     * PUBLIC|PRESENTATION (3) and the name the cluster compositor expects. Without the token,
     * PUBLIC throws SecurityException; with OWN_CONTENT_ONLY the compositor ignores the display.
     */
    public static void startWithProjection(Context ctx, android.media.projection.MediaProjection mp,
                                           MainActivity.Lab bridge, String url) {
        stop();
        error = "";
        try {
            texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(W, H);
            surface = namedSurface(W, H);
            display = mp.createVirtualDisplay(NAMES[0], W, H, DPI, 3, surface, null,
                    new Handler(Looper.getMainLooper()));
            if (display == null) {
                error = "createVirtualDisplay returned null";
                return;
            }
            final Display d = display.getDisplay();
            final Context app = ctx;
            final MainActivity.Lab b = bridge;
            final String u = url;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        presentation = new ClusterPresentation(app, d, u, b);
                        presentation.show();
                        Log.i(TAG, "projection (token): " + NAMES[0] + " display " + d.getDisplayId());
                    } catch (Throwable t) {
                        error = "presentation refused: " + t;
                        Log.w(TAG, error, t);
                    }
                }
            });
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "projection with token failed", t);
            stop();
        }
    }

    /** same as the reference app: display "evsound_mapa" with flags 3, no token, and an Activity inside */
    public static int startPublic(Context ctx, String url) {
        stop();
        error = "";
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(W, H);
            surface = new Surface(texture);
            display = dm.createVirtualDisplay("evsound_mapa", W, H, DPI, surface, 3);
            int id = display.getDisplay().getDisplayId();
            Log.i(TAG, "public projection: evsound_mapa display " + id);
            return id;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "public projection failed", t);
            return -1;
        }
    }

    /** named Surface (SurfaceControl); without a name the panel ignores the display */
    static Surface namedSurface(int w, int h) {
        try {
            Class<?> builder = Class.forName("android.view.SurfaceControl$Builder");
            Object b = builder.getDeclaredConstructor().newInstance();
            builder.getMethod("setName", String.class).invoke(b, SURFACE_NAME);
            builder.getMethod("setBufferSize", int.class, int.class).invoke(b, w, h);
            Object sc = builder.getMethod("build").invoke(b);
            android.util.Log.i(TAG, "named surface: " + SURFACE_NAME);
            return new Surface((android.view.SurfaceControl) sc);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "SurfaceControl failed (" + t + "), falling back to SurfaceTexture");
            return new Surface(texture);
        }
    }

    /** creates (or recreates) the projection display and the skin presentation */
    public static void start(Context ctx, MainActivity.Lab bridge, String url) {
        stop();
        error = "";
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(W, H);
            surface = new Surface(texture);

            /* the name matters: it is how the cluster compositor finds the projection */
            /* OWN_CONTENT_ONLY: private display of the app, without the capture token that
               "screen sharing" requires. PUBLIC would ask for CAPTURE_VIDEO_OUTPUT. */
            display = dm.createVirtualDisplay(NAMES[0], W, H, DPI, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);

            final Display d = display.getDisplay();
            final Context app = ctx;
            final MainActivity.Lab b = bridge;
            final String u = url;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        presentation = new ClusterPresentation(app, d, u, b);
                        presentation.show();
                        Log.i(TAG, "projection: " + NAMES[0] + " display " + d.getDisplayId());
                    } catch (Throwable t) {
                        error = "presentation refused: " + t;
                        Log.w(TAG, error, t);
                    }
                }
            });
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "projection failed", t);
            stop();
        }
    }

    public static void stop() {
        try { if (presentation != null) presentation.dismiss(); } catch (Throwable ignored) {}
        presentation = null;
        try { if (display != null) display.release(); } catch (Throwable ignored) {}
        display = null;
        try { if (surface != null) surface.release(); } catch (Throwable ignored) {}
        surface = null;
        try { if (texture != null) texture.release(); } catch (Throwable ignored) {}
        texture = null;
    }
}
