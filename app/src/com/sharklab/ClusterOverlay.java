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
 * ClusterOverlay — a skin no painel do motorista como janela overlay.
 *
 * Lido do carro (SurfaceFlinger): a camada que o container da BYD mostra no clúster é uma
 * janela TYPE_APPLICATION_OVERLAY (2038) com título "EvSound painel", no layerStack do painel
 * — não é display nem casteo. Por isso exige SYSTEM_ALERT_WINDOW.
 *
 * A janela tem de ser criada com o Context do display do painel (createDisplayContext); no
 * contexto da Activity ela nasce no display 0 e aparece por cima da tela central.
 */
public final class ClusterOverlay {

    private static final String TAG = "SharkLab";
    public static final String WINDOW_TITLE = "EvSound painel";
    private static final int W = 1920, H = 720;

    private static WebView web;
    private static WindowManager wm;
    private static String error = "";

    private ClusterOverlay() {
    }

    public static boolean running() {
        return web != null;
    }

    public static String error() {
        return error;
    }

    /** display do painel, ou null */
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
        hide();
        error = "";
        try {
            Display target = panelDisplay(ctx);
            if (target == null) {
                error = "display do painel nao encontrado";
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

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    W, H,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.RGBA_8888);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            lp.setTitle(WINDOW_TITLE);      /* o título é o que o container procura */
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            wm.addView(web, lp);
            if (url != null) web.loadUrl(url);
            Log.i(TAG, "overlay do painel criado no display " + target.getDisplayId()
                    + " layerStack esperado do painel");
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "overlay do painel falhou: " + error, t);
            hide();
            return false;
        }
    }

    public static synchronized void hide() {
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
