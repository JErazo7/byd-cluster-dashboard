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
            app = ctx;
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
         * Modo ativo, medido no carro: quem carrega o modo é `energy.getRoadSurfaceMode()`,
         * 1 = montanha, 2 = neve, 3 = lama, 4 = areia e 5 = asfalto (normal). As luzes do
         * painel não são booleanas (65535/8322) e `dcfg` ficou em 2 em ECO e NORMAL, então
         * eco/sport não se distinguem — caem em NORMAL em vez de mentir.
         */
        private String driveName(double[] b) {
            int surf = b[23] > -998 ? (int) b[23] : -1;
            if (surf == 1) return "MONTAÑA";
            if (surf == 2) return "NIEVE";
            if (surf == 3) return "LODO";
            if (surf == 4) return "ARENA";
            return "NORMAL";
        }

        /** número inteiro ou -999 (o contrato das skins usa -999 como "sem leitura") */
        /* último valor válido: no arranque do carro os getters devolvem 8322/-999 e o modo
           aparecia trocado até a leitura assentar */
        private static int lastEco = Integer.MIN_VALUE;
        private static int lastSurf = Integer.MIN_VALUE;
        private static final long STARTED = System.currentTimeMillis();
        /* segurar o último valor só nos primeiros 15 s do processo; depois vale o que o carro diz,
           senão a rueda deixa de mudar o modo (o teste com 8322 ficava eterno) */
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

        /** guarda o último modo válido: no re-arranque do app ele aparece certo de imediato */
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

        /** EV quando o motor a combustão está parado, HEV quando ele gira.
         *  Rotações fora de 200..8000 (0, 65535, -10011) são sentinela: motor parado. */
        private String evState(double[] b) {
            if (b[15] <= -998) return "null";
            boolean running = b[15] >= 200 && b[15] <= 8000;
            return running ? "0" : "1";
        }

        /** pressões cruas dos quatro pneus; null quando o carro não responde */
        private String tyres() {
            double fl = byd.tyre(1), fr = byd.tyre(2), rl = byd.tyre(3), rr = byd.tyre(4);
            if (fl <= -998 && fr <= -998 && rl <= -998 && rr <= -998) return "null";
            /* o carro devolve kPa (265); o cartão mostra bar */
            return "{\"fl\":" + bar(fl) + ",\"fr\":" + bar(fr) + ",\"rl\":" + bar(rl) + ",\"rr\":" + bar(rr) + "}";
        }

        private String bar(double kpa) {
            if (kpa <= -998) return "-999";
            double bar = kpa > 20 ? kpa / 100.0 : kpa;      /* já em bar em algumas versões */
            return String.valueOf(Math.round(bar * 10) / 10.0);
        }

        /** diagnóstico do TPMS, para a skin de sonda */
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
