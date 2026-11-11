package com.sharklab;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * ClusterSkinActivity — a skin running ON the cluster panel display.
 *
 * It has to be its own Activity: MainActivity is singleTask, so asking it for the cluster
 * panel display delivers the intent to the instance that is already on display 0. Here the
 * skin is loaded on its own, without audio and without the head unit.
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

        ClusterOverlay.enableDevtools();
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
     * Minimal panel bridge: only what the skin reads to draw (no audio, no head-unit
     * touches). Kept here so the panel Activity does not depend on MainActivity.
     */
    static class PanelBridge {
        private final android.content.Context ctx;
        private final BydBridge byd;
        private float simSpeed = 0f;
        private boolean up = true;

        PanelBridge(android.content.Context c) {
            ctx = c.getApplicationContext();
            app = ctx;
            byd = new BydBridge(ctx);
        }

        /** readings that change when the car powers off (to find the "off" signal) */
        public String powerProbe() {
            double[] b = snap();
            return "estate=" + (int) b[28] + " bcm=" + (int) b[29] + " day=" + (int) b[30]
                    + " spd=" + (int) b[0] + " gear=" + (int) b[1] + " off=" + (int) b[32] + " auto=" + (int) b[33] + " pwr=" + (int) b[34] + " mcu=" + (int) b[35] + " pc=" + (int) b[36];
        }

        /* One `snapshot()` costs ~12 ms (37 reads on the BYD modules) and there are two consumers
           in the panel: the 400 ms pusher (ClusterOverlay) and the page's own lab-feed.
           With a 120 ms cache the two share the same read and the renderer thread stops
           getting stuck in the binder on every frame. */
        private static double[] cached;
        private static long cachedAt;

        private double[] snap() {
            long now = System.currentTimeMillis();
            if (cached != null && now - cachedAt < 120) return cached;
            cached = byd.snapshot();
            cachedAt = now;
            return cached;
        }

        @android.webkit.JavascriptInterface
        public String state() {
            double[] b = snap();
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
                    + ",\"pw\":" + (b[2] > -998 && Math.abs(b[2]) <= 400 ? (int) b[2] : -999)
                    + ",\"odo\":" + (b[8] > -998 ? (int) b[8] : -999)
                    + ",\"range\":" + (b[4] > -998 ? (int) b[4] : -999)
                    + ",\"tout\":" + (b[7] > -998 ? (int) b[7] : -999)
                    + ",\"btemp\":-999,\"dmode\":2,\"drive\":\"" + driveName(b) + "\",\"ready\":1,\"regen\":0"
                    + ",\"fuel\":" + num(b[13]) + ",\"fuelKm\":" + num(b[14])
                    + ",\"ev\":" + evState(b)
                    + ",\"tyre\":" + tyres()
                    + ",\"dcfg\":" + num(b[18]) + ",\"surf\":" + num(b[17]) + ",\"emode\":" + num(b[16])
                    + ",\"mNormal\":" + num(b[19]) + ",\"mMud\":" + num(b[20])
                    + ",\"mSand\":" + num(b[21]) + ",\"mGrass\":" + num(b[22])
                    + ",\"surfmode\":" + surfValid(b[23]) + ",\"dcmode\":" + num(b[24])
                    + ",\"opmode\":" + num(b[25]) + ",\"ecoind\":" + ecoValid(b[31]) + ",\"menu\":" + num(b[26])
                    + ",\"automode\":" + num(b[27])
                    + ",\"estate\":" + num(b[28]) + ",\"bcm\":" + num(b[29]) + ",\"daymode\":" + num(b[30])
                    + ",\"v12\":" + (b[9] > -998 ? b[9] : 13.8)
                    + ",\"phone\":-1,\"tt\":{},\"rhd\":false,\"warn\":[],\"adas\":" + byd.adasJson()
                    + ",\"cv\":null,\"nmv\":null,\"ext\":null,\"trip\":null}";
        }

        /**
         * Active mode, measured in the car: the mode is carried by `energy.getRoadSurfaceMode()`,
         * 1 = mountain, 2 = snow, 3 = mud, 4 = sand and 5 = asphalt (normal). The panel
         * lights are not boolean (65535/8322) and `dcfg` stayed at 2 in ECO and NORMAL, so
         * eco/sport cannot be told apart — they fall back to NORMAL instead of lying.
         */
        private String driveName(double[] b) {
            int surf = b[23] > -998 ? (int) b[23] : -1;
            if (surf == 1) return "MONTAÑA";
            if (surf == 2) return "NIEVE";
            if (surf == 3) return "LODO";
            if (surf == 4) return "ARENA";
            return "NORMAL";
        }

        /** integer, or -999 (the skin contract uses -999 as "no reading") */
        /* last valid value: when the car starts the getters return 8322/-999 and the mode
           showed up swapped until the reading settled */
        private static int lastEco = Integer.MIN_VALUE;
        private static int lastSurf = Integer.MIN_VALUE;
        private static final long STARTED = System.currentTimeMillis();
        /* hold the last value only for the first 15 s of the process; after that whatever the car
           says wins, otherwise the wheel stops changing the mode (the 8322 test lasted forever) */
        private static final long HOLD = 15000;

        private android.content.Context app;

        private void loadMode() {
            if (lastEco != Integer.MIN_VALUE && lastSurf != Integer.MIN_VALUE) return;
            if (app == null) { lastEco = 135298; lastSurf = 1; return; }
            lastEco = app.getSharedPreferences("lab", android.content.Context.MODE_PRIVATE)
                    .getInt("lastEco", 135298);
            lastSurf = app.getSharedPreferences("lab", android.content.Context.MODE_PRIVATE)
                    .getInt("lastSurf", 1);
        }

        private int ecoValid(double v) {
            loadMode();
            int e = (int) Math.round(v);
            if (e == 135298 || e == 8353) { lastEco = e; saveMode(); return e; }
            if (e == 10306) { lastEco = e; saveMode(); return e; }
            return (System.currentTimeMillis() - STARTED < HOLD) ? lastEco : e;
        }

        private int surfValid(double v) {
            loadMode();
            int sm = (int) Math.round(v);
            if (sm >= 1 && sm <= 5) { lastSurf = sm; saveMode(); return sm; }
            return (System.currentTimeMillis() - STARTED < HOLD) ? lastSurf : sm;
        }

        /** keeps the last valid mode: on an app restart it shows up correct right away */
        private void saveMode() {
            try {
                if (app != null) {
                    app.getSharedPreferences("lab", android.content.Context.MODE_PRIVATE).edit()
                            .putInt("lastEco", lastEco).putInt("lastSurf", lastSurf).apply();
                }
            } catch (Throwable ignored) { }
        }

        private String num(double v) {
            return String.valueOf(v > -998 ? (int) Math.round(v) : -999);
        }

        /** EV when the combustion engine is stopped, HEV when it is spinning.
         *  RPMs outside 200..8000 (0, 65535, -10011) are sentinels: engine stopped. */
        private String evState(double[] b) {
            if (b[15] <= -998) return "null";
            boolean running = b[15] >= 200 && b[15] <= 8000;
            return running ? "0" : "1";
        }

        /** raw pressures of the four tyres; null when the car does not answer */
        private String tyres() {
            double fl = byd.tyre(1), fr = byd.tyre(2), rl = byd.tyre(3), rr = byd.tyre(4);
            if (fl <= -998 && fr <= -998 && rl <= -998 && rr <= -998) return "null";
            /* the car returns kPa (265); the card shows bar */
            return "{\"fl\":" + bar(fl) + ",\"fr\":" + bar(fr) + ",\"rl\":" + bar(rl) + ",\"rr\":" + bar(rr) + "}";
        }

        private String bar(double kpa) {
            if (kpa <= -998) return "-999";
            double bar = kpa > 20 ? kpa / 100.0 : kpa;      /* already in bar on some versions */
            return String.valueOf(Math.round(bar * 10) / 10.0);
        }

        /** TPMS diagnostic, for the probe skin */
        @android.webkit.JavascriptInterface
        public String tyreInfo() {
            return byd.tyreReport();
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
            android.util.Log.i("SharkLab", "panel: " + s);
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
