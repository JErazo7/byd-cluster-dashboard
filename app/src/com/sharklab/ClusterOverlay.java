package com.sharklab;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * ClusterOverlay — the skin on the cluster panel as an overlay window.
 *
 * Read from the car (SurfaceFlinger): the layer the BYD container shows on the cluster is a
 * TYPE_APPLICATION_OVERLAY (2038) window whose title is the WINDOW_TITLE literal below, in
 * the panel's layerStack — it is not a display nor a cast. That is why it requires
 * SYSTEM_ALERT_WINDOW.
 *
 * The window must be created with the panel display's Context (createDisplayContext); in the
 * Activity context it is born on display 0 and appears on top of the head unit screen.
 */
public final class ClusterOverlay {

    private static final String TAG = "SharkLab";
    /* the panel shows any window on its display while projection is active; this literal is
       copied from the layer observed on the reference app so the layer is easy to find in dumpsys */
    public static final String WINDOW_TITLE = "EvSound painel";

    /* the panel is a virtual window: Chromium freezes the skin's requestAnimationFrame, so
       what feeds the drawing is this pusher, from outside the page */
    private static volatile boolean pushing;
    private static volatile Context lastCtx;
    private static int ticks;
    private static DisplayManager jetDm;
    private static DisplayManager.DisplayListener jetListener;
    private static final int W = 1920, H = 720;

    private static WebView web;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lastLp;
    private static String error = "";

    private ClusterOverlay() {
    }

    public static boolean running() {
        return web != null;
    }

    /**
     * Turns on the WebView devtools. Without it there is no way to measure or debug the skin
     * inside the panel: the page's `requestAnimationFrame` stays frozen on that virtual display
     * and Chrome does not see a `webview_devtools_remote` process. With the flag, `adb forward
     * tcp:9222 localabstract:webview_devtools_remote_<pid>` opens the panel's `chrome://inspect`
     * tab. Must run BEFORE the process' first WebView.
     */
    public static void enableDevtools() {
        try {
            WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable ignored) {
        }
    }

    public static String error() {
        return error;
    }

    /** panel display, or null */
    public static Display panelDisplay(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : dm.getDisplays()) {
                if (String.valueOf(d.getName()).contains("fission_bg_xdjaVirtualSurface")) return d;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static synchronized boolean show(Context ctx, String url) {
        AdbShell.trace(ctx, "overlay.show\n" + android.util.Log.getStackTraceString(new Throwable()).split("\n")[1]);
        hide();
        error = "";
        try {
            Display target = panelDisplay(ctx);
            if (target == null) {
                error = "panel display not found";
                return false;
            }
            Context panelCtx = ctx.createDisplayContext(target);
            wm = (WindowManager) panelCtx.getSystemService(Context.WINDOW_SERVICE);

            web = new WebView(panelCtx);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            /* the panel viewport is 960 CSS px (1920 px at 320 dpi); the skin SVG
               (viewBox 1280×480) scales by itself. Measure before touching zoom: a wrong
               setInitialScale crops the skin. */
            /* 1280 CSS × 1.5 = 1920 px: fills the panel without depending on dpr */
            /* no artificial zoom: the viewport stays at device-width (960 CSS with dpr 2) and
               the skin SVG (viewBox 1920×720) scales by itself to the whole display */
            s.setUseWideViewPort(false);
            s.setLoadWithOverviewMode(false);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            web.setBackgroundColor(0xFF0A1017);
            web.addJavascriptInterface(new ClusterSkinActivity.PanelBridge(ctx), "Lab");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String u) {
                    if (u == null || !u.startsWith("file:")) return;
                    for (String rel : MainActivity.INJECT_PANEL) {
                        try {
                            java.io.InputStream in = v.getContext().getAssets().open(rel);
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            in.close();
                            v.evaluateJavascript(out.toString("UTF-8"), null);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });

            /* The same flags as the reference app: only FLAG_LAYOUT_NO_LIMITS. With
               NOT_FOCUSABLE/NOT_TOUCHABLE/FULLSCREEN/LAYOUT_IN_SCREEN the container draws the
               native BYD bar (P/ECO, battery, range, ODO) on top of the skin; with this
               combination, the panel shows only the skin. */
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    W, H,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    /* the SAME flags as the reference app's panel window, read in dumpsys
                       (NOT_FOCUSABLE|NOT_TOUCHABLE|KEEP_SCREEN_ON|LAYOUT_IN_SCREEN|
                       LAYOUT_NO_LIMITS|FULLSCREEN|HARDWARE_ACCELERATED): this combination
                       is what puts the skin on top of the BYD native widgets */
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            lp.setTitle(WINDOW_TITLE);      /* the title is what the container looks for */
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            lastLp = lp;
            wm.addView(web, lp);
            if (url != null) {
                /* real panel size: the skin scales itself to fill */
                String u = url.indexOf('#') >= 0 ? url : url + "#";
                u += (u.endsWith("#") ? "" : "&") + "pw=" + W + "&ph=" + H;
                web.loadUrl(u);
            }
            startPush(ctx);
            watchPanelPower(ctx, target.getDisplayId());
            Log.i(TAG, "panel overlay created on display " + target.getDisplayId()
                    + " expected panel layerStack");
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "panel overlay failed: " + error, t);
            hide();
            return false;
        }
    }

    /**
     * Raises our window to the top of the stack.
     *
     * Overlays of the same type are stacked in creation order: when the reference app starts
     * after ours (at boot, for instance) it ends up on top and the panel shows its skin.
     * Removing and re-adding the SAME view puts ours on top without reloading the page.
     */
    public static synchronized void bringToFront() {
        if (web == null || wm == null || lastLp == null) return;
        try {
            if (web.getParent() != null) wm.removeView(web);
            wm.addView(web, lastLp);
            Log.i(TAG, "panel: re-stacked on top");
        } catch (Throwable t) {
            Log.w(TAG, "panel: re-stack failed (" + t + ")");
            try { wm.addView(web, lastLp); } catch (Throwable ignored) {}
        }
    }

    /** runs JS on the panel skin (startup, reload theme) */
    public static synchronized void javaScript(final String js) {
        try {
            if (web != null) web.post(new Runnable() {
                public void run() {
                    try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Watches the panel display: when it turns off (car off) the skin steps aside, otherwise
     * BYD lights the display for the open-door alert and it shows up covered by the theme.
     */
    private static void watchPanelPower(final Context ctx, final int displayId) {
        stopWatch();
        try {
            jetDm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            jetListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int id) { }

                @Override
                public void onDisplayRemoved(int id) {
                    if (id == displayId) PanelRelease.release(ctx);
                }

                @Override
                public void onDisplayChanged(int id) {
                    if (id != displayId || jetDm == null) return;
                    Display d = jetDm.getDisplay(id);
                    if (d == null) return;
                    int state = d.getState();
                    if (state == Display.STATE_OFF || state == Display.STATE_DOZE
                            || state == Display.STATE_DOZE_SUSPEND) {
                        PanelRelease.release(ctx.getApplicationContext());
                    }
                }
            };
            jetDm.registerDisplayListener(jetListener, null);
        } catch (Throwable t) {
            Log.w(TAG, "glass: no display watchdog", t);
        }
    }

    private static void stopWatch() {
        try {
            if (jetDm != null && jetListener != null) jetDm.unregisterDisplayListener(jetListener);
        } catch (Throwable ignored) {
        }
        jetListener = null;
    }

    /** pushes the state to the skin every 400 ms (independent of the page rAF) */
    private static void startPush(final Context ctx) {
        pushing = true;
        lastCtx = ctx.getApplicationContext();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final ClusterSkinActivity.PanelBridge br =
                new ClusterSkinActivity.PanelBridge(ctx.getApplicationContext());
        h.post(new Runnable() {
            public void run() {
                if (!pushing || web == null) return;
                /* the listener does not always fire when the car shuts off: check directly */
                try {
                    Context app = lastCtx;
                    Display pd = app == null ? null : panelDisplay(app);
                    if (pd != null && pd.getState() != Display.STATE_ON) {
                        PanelRelease.release(app);
                        return;
                    }
                } catch (Throwable ignored) {
                }
                ticks++;
                /* heartbeat: shows whether the process survives the car shutting off */
                if (ticks % 5 == 0) {
                    AdbShell.trace(lastCtx, "alive " + ticks + " state=" + br.powerProbe());
                }
                try {
                    /* `__pushedByApp` turns the page's lab-feed off: with it on, page and app
                       both read the car (37 binder calls per read) and draw the same frame */
                    String js = "window.__pushedByApp=1;window.BrasaDriveUpdate&&window.BrasaDriveUpdate(" + br.state() + ")";
                    web.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, 400);
            }
        });
    }

    public static synchronized void hide() {
        pushing = false;
        stopWatch();
        lastLp = null;
        try {
            if (web != null && wm != null) wm.removeView(web);
        } catch (Throwable ignored) {
        }
        try {
            if (web != null) {
                web.loadUrl("about:blank");
                web.destroy();
            }
        } catch (Throwable ignored) {
        }
        web = null;
        wm = null;
    }
}
