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

    /* o painel é uma janela virtual: o Chromium congela o requestAnimationFrame da skin, então
       quem alimenta o desenho é este empurrador, de fora da página */
    private static volatile boolean pushing;
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
            /* a viewport do painel é 960 CSS px (1920 px a 320 dpi); o SVG da skin
               (viewBox 1280×480) escala sozinho. Medir antes de mexer em zoom: um
               setInitialScale errado corta a skin. */
            /* 1280 CSS × 1.5 = 1920 px: preenche o painel sem depender do dpr */
            /* sem zoom artificial: o viewport fica em device-width (960 CSS com dpr 2) e o
               SVG da skin (viewBox 1920×720) escala sozinho para o quadro inteiro */
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

            /* As mesmas flags do EvSound: só FLAG_LAYOUT_NO_LIMITS. Com
               NOT_FOCUSABLE/NOT_TOUCHABLE/FULLSCREEN/LAYOUT_IN_SCREEN o container desenha a
               barra nativa da BYD (P/ECO, bateria, autonomia, ODO) por cima da skin; com esta
               combinação, o painel fica só com a skin. */
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    W, H,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    /* as MESMAS flags da janela do EvSound no painel, lidas no dumpsys
                       (NOT_FOCUSABLE|NOT_TOUCHABLE|KEEP_SCREEN_ON|LAYOUT_IN_SCREEN|
                       LAYOUT_NO_LIMITS|FULLSCREEN|HARDWARE_ACCELERATED): é essa combinação
                       que deixa a skin por cima dos widgets nativos da BYD */
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
            lp.setTitle(WINDOW_TITLE);      /* o título é o que o container procura */
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            lastLp = lp;
            wm.addView(web, lp);
            if (url != null) {
                /* tamanho real do painel: a skin se escala sozinha para preencher */
                String u = url.indexOf('#') >= 0 ? url : url + "#";
                u += (u.endsWith("#") ? "" : "&") + "pw=" + W + "&ph=" + H;
                web.loadUrl(u);
            }
            startPush(ctx);
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

    /**
     * Sobe a nossa janela para o topo da pilha.
     *
     * As overlays do mesmo tipo são empilhadas por ordem de criação: quando o app do EvSound
     * sobe depois do nosso (no boot, por exemplo) ele fica por cima e o painel mostra a skin
     * dele. Remover e re-adicionar a MESMA view coloca a nossa em cima sem recarregar a página.
     */
    public static synchronized void bringToFront() {
        if (web == null || wm == null || lastLp == null) return;
        try {
            if (web.getParent() != null) wm.removeView(web);
            wm.addView(web, lastLp);
            Log.i(TAG, "painel: reempilhado por cima");
        } catch (Throwable t) {
            Log.w(TAG, "painel: reempilhar falhou (" + t + ")");
            try { wm.addView(web, lastLp); } catch (Throwable ignored) {}
        }
    }

    /** executa JS na skin do painel (arranque, recarregar tema) */
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

    /** empurra o estado para a skin a cada 400 ms (independente do rAF da página) */
    private static void startPush(final Context ctx) {
        pushing = true;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final ClusterSkinActivity.PanelBridge br =
                new ClusterSkinActivity.PanelBridge(ctx.getApplicationContext());
        h.post(new Runnable() {
            public void run() {
                if (!pushing || web == null) return;
                try {
                    String js = "window.BrasaDriveUpdate&&window.BrasaDriveUpdate(" + br.state() + ")";
                    web.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, 400);
            }
        });
    }

    public static synchronized void hide() {
        pushing = false;
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
