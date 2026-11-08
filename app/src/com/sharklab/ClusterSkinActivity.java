package com.sharklab;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * ClusterSkinActivity — a skin rodando NO display do painel.
 *
 * Precisa ser uma Activity própria: a MainActivity é singleTask, então pedir o display do
 * painel para ela entrega o intent na instância que já está no display 0. Aqui a skin é
 * carregada sozinha, sem som e sem a central.
 */
public class ClusterSkinActivity extends Activity {

    public static final String EXTRA_URL = "skinUrl";
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0);
        web.addJavascriptInterface(new PanelBridge(this), "Lab");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (url == null || !url.startsWith("file:")) return;
                for (String rel : MainActivity.INJECT_PANEL) {
                    try {
                        java.io.InputStream in = getAssets().open(rel);
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
        setContentView(web);

        String url = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        if (url != null) web.loadUrl(url);
    }

    /**
     * Ponte mínima do painel: só o que a skin lê para desenhar (sem áudio, sem toques da
     * central). Mantida aqui para a Activity do painel não depender da MainActivity.
     */
    static class PanelBridge {
        private final android.content.Context ctx;
        private final BydBridge byd;
        private float simSpeed = 0f;
        private boolean up = true;

        PanelBridge(android.content.Context c) {
            ctx = c.getApplicationContext();
            byd = new BydBridge(ctx);
        }

        @android.webkit.JavascriptInterface
        public String state() {
            double[] b = byd.snapshot();
            boolean car = b[0] > -998;
            simSpeed += up ? 1.6f : -1.6f;
            if (simSpeed > 120) { simSpeed = 120; up = false; }
            if (simSpeed < 0) { simSpeed = 0; up = true; }
            int spd = car ? (int) Math.round(b[0]) : Math.round(simSpeed);
            int gsel = b[1] > -998 ? (int) b[1] : 3;
            double load = b[5] > -998 ? b[5] / 100.0 : 0.3;
            return "{\"pu\":1,\"du\":1,\"su\":1,\"car\":\"v8\",\"phase\":\"on\",\"reverse\":" + (gsel == 1)
                    + ",\"speed\":" + spd + ",\"gear\":3,\"gsel\":" + gsel + ",\"boost\":0.3,\"load\":" + load
                    + ",\"rpm\":" + (car ? 900 + spd * 30 : 900 + spd * 42) + ",\"ped\":" + Math.round(load * 100)
                    + ",\"soc\":" + (b[3] > -998 ? (int) b[3] : -1)
                    + ",\"brk\":0,\"turnL\":false,\"turnR\":false"
                    + ",\"pw\":" + (b[2] > -998 ? (int) b[2] : 0)
                    + ",\"odo\":" + (b[8] > -998 ? (int) b[8] : -999)
                    + ",\"range\":" + (b[4] > -998 ? (int) b[4] : -999)
                    + ",\"tout\":" + (b[7] > -998 ? (int) b[7] : -999)
                    + ",\"btemp\":-999,\"dmode\":2,\"drive\":\"NORMAL\",\"ready\":1,\"regen\":0"
                    + ",\"v12\":" + (b[9] > -998 ? b[9] : 13.8)
                    + ",\"phone\":-1,\"tt\":{},\"rhd\":false,\"warn\":[],\"adas\":" + byd.adasJson()
                    + ",\"tyre\":null,\"cv\":null,\"nmv\":null,\"ext\":null,\"trip\":null}";
        }

        @android.webkit.JavascriptInterface
        public String cars() {
            return "[{\"id\":\"v8\",\"name\":\"V8\",\"redline\":7000,\"turbo\":false,\"limiter\":8000,\"layout\":\"v\",\"cyl\":8,\"subtitle\":\"\",\"pro\":false}]";
        }

        @android.webkit.JavascriptInterface
        public String nowPlaying() {
            String real = MediaNow.snapshot(ctx);
            return real == null ? "{}" : real;
        }

        @android.webkit.JavascriptInterface
        public String position() {
            return "{}";
        }

        @android.webkit.JavascriptInterface
        public String nav() {
            return NavWatch.snapshot();
        }

        @android.webkit.JavascriptInterface
        public void beat(double a, double b, boolean c, String d) {
        }

        @android.webkit.JavascriptInterface
        public void log(String s) {
            android.util.Log.i("SharkLab", "painel: " + s);
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
