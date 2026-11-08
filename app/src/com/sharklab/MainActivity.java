package com.sharklab;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.ActivityOptions;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SharkLab: laboratorio de skins fora do EvSound.
 *
 * Carrega dash/<id>/index.html e alimenta a skin com dados simulados (assets/lab/lab-feed.js).
 * Serve para desenhar e testar o painel na propria central, sem leitura do carro.
 */
public class MainActivity extends Activity {

    private static final String TAG = "SharkLab";
    private static final String[] BUILTIN = {"industria", "muscle", "shark"};

    /** injetados nesta ordem depois da skin carregar (o mesmo caminho que o app de referência usa) */
    static final String[] INJECT = {
            "lab/lab-feed.js", "lab/lab-logbook.js", "lab/lab-ui.js"
    };

    /** o painel só desenha: não injeta a interface de controle da central */
    static final String[] INJECT_PANEL = {
            "lab/lab-feed.js"
    };
    private WebView web;
    private BydBridge byd;
    private TextureView camView;
    private android.view.Surface camSurface;
    private android.graphics.SurfaceTexture camTexture;
    private boolean camShown = false;
    private boolean wantProjection = false;
    private ClusterPresentation cluster;
    private final Lab bridge = new Lab();
    private volatile double gpsKmh = -1;
    private LocationListener gpsListener;
    private String clusterError = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float t = 0f;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        /* a tela central NÃO é tela cheia; só o overlay do painel é */

        HiddenApi.allowAll();
        Log.i(TAG, "hiddenApi=" + HiddenApi.result());
        byd = new BydBridge(this);

        /* diagnóstico no logcat: o que esta unidade responde (adb logcat -s SharkLab) */
        new Thread(new Runnable() {
            public void run() {
                try {
                    Log.i(TAG, "probe=" + CarProbe.run(getApplicationContext()));
                    Log.i(TAG, "bydAvailable=" + byd.available());
                    Log.i(TAG, "bydGetters=" + byd.report());
                    Log.i(TAG, "adas=" + byd.adasJson());
                    StringBuilder dump = new StringBuilder();
                    dump.append("{\"bydAvailable\":").append(byd.available())
                        .append(",\"bydGetters\":").append(byd.report())
                        .append(",\"adas\":").append(byd.adasJson())
                        .append(",\"cam\":").append(bridge.camProbe()).append('}');
                    java.io.File f = new java.io.File(getExternalFilesDir(null), "probe.json");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                    out.write(dump.toString().getBytes("UTF-8"));
                    out.close();
                    Log.i(TAG, "probe.json escrito");
                } catch (Throwable t) {
                    Log.w(TAG, "probe falhou", t);
                }
            }
        }).start();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF04070C);

        camView = new TextureView(this);
        camView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                camSurface = new android.view.Surface(st);
                camTexture = st;
                if (camShown) {
                    boolean ok = BydCamera.attach(MainActivity.this, st, 0);
                    Log.i(TAG, "câmera: attach=" + ok + " erro=" + BydCamera.error());
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                if (camSurface != null) {
                    BydCamera.detach(MainActivity.this, st, 0);
                    BydCamera.close();
                    try { camSurface.release(); } catch (Throwable ignored) {}
                    camSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {
            }
        });
        FrameLayout.LayoutParams camLp = new FrameLayout.LayoutParams(1, 1);
        camLp.leftMargin = 0;
        camLp.topMargin = 0;
        root.addView(camView, camLp);
        camView.setVisibility(View.GONE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(bridge, "Lab");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("file:")) inject();
            }
        });
        web.setBackgroundColor(0);
        web.clearCache(true);
        /* com cover a skin preenche o quadro todo (o painel recebe imagem de borda a borda) */
        /* WebView no quadro todo: a skin calcula a escala sozinha (fitSkin) */
        int h = FrameLayout.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, h);
        wlp.gravity = android.view.Gravity.CENTER;
        root.addView(web, wlp);
        setContentView(root);

        SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
        /* centro = galeria/ajustes; a skin vive no painel (overlay) */
        web.loadUrl("file:///android_asset/lab/home.html");

        startGps();

        if (getIntent() != null && getIntent().getBooleanExtra("jsontest", false)) {
            new Thread(new Runnable() {
                public void run() {
                    String[] payloads = {
                            "{\"auto_container\":true}",
                            "{\"auto_container\":true,\"name\":\"shared_fission_bg_XDJAScreenProjection_0\",\"width\":1920,\"height\":720}",
                            "{\"name\":\"shared_fission_bg_XDJAScreenProjection_0\",\"width\":1920,\"height\":720,\"auto_container\":1}",
                            "{\"display\":\"shared_fission_bg_XDJAScreenProjection_0\",\"w\":1920,\"h\":720}",
                            "{\"projection\":\"shared_fission_bg_XDJAScreenProjection_0\"}"
                    };
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < payloads.length; i++) {
                        sb.append("{\"i\":").append(i).append(",\"r\":\"").append(bridge.jsonCmd(1000, payloads[i])).append("\"}");
                        if (i + 1 < payloads.length) sb.append(',');
                    }
                    sb.append(']');
                    try {
                        java.io.File f = new java.io.File(getExternalFilesDir(null), "json.json");
                        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                        o.write(sb.toString().getBytes("UTF-8"));
                        o.close();
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
        }

        /* teste: lançar a skin no display 1 pelo próprio app (ActivityOptions) */
        if (getIntent() != null && getIntent().getBooleanExtra("disp", false)) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    try {
                        /* display do painel: procura pelo nome que o container service usa */
                        int did = 1;
                        android.hardware.display.DisplayManager dm =
                                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
                        for (android.view.Display d : dm.getDisplays()) {
                            if (String.valueOf(d.getName()).contains("fission_bg_xdjaVirtualSurface")) {
                                did = d.getDisplayId();
                            }
                        }
                        ActivityOptions o = ActivityOptions.makeBasic();
                        o.setLaunchDisplayId(did);
                        Intent i = new Intent(MainActivity.this, ClusterSkinActivity.class);
                        i.putExtra(ClusterSkinActivity.EXTRA_URL,
                                urlFor(getSharedPreferences("lab", MODE_PRIVATE).getString("skin", "industria"), true, false));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(i, o.toBundle());
                        Log.i(TAG, "disp: lançado no display " + did);
                    } catch (Throwable t) {
                        Log.w(TAG, "disp: recusado", t);
                    }
                }
            }, 1200);
        }

        /* disparador por arquivo (teste por adb): cmd.txt com "pub" / "token" / "proj" */
        final String cmd = readCmdFile();
        if (cmd != null && !cmd.isEmpty()) {
            Log.i(TAG, "cmd.txt=" + cmd);
            if (cmd.contains("pub")) {
                int d = ClusterProjection.startPublic(this, urlFor(p.getString("skin", "industria"), true, false));
                writeResult("pub.json", "{\"display\":" + d + ",\"erro\":\""
                        + ClusterProjection.error().replace("\"", "'") + "\"}");
                if (d > 0) {
                    try {
                        ActivityOptions o = ActivityOptions.makeBasic();
                        o.setLaunchDisplayId(d);
                        Intent i3 = new Intent(this, MainActivity.class);
                        i3.putExtra("skinonly", true);
                        i3.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(i3, o.toBundle());
                    } catch (Throwable t) {
                        writeResult("pub2.json", "{\"launch\":\"" + String.valueOf(t).replace("\"", "'") + "\"}");
                    }
                }
            } else if (cmd.contains("overlay")) {
                boolean ok = ClusterOverlay.show(this, urlFor(p.getString("skin", "industria"), true, false));
                writeResult("overlay.json", "{\"ok\":" + ok + ",\"erro\":\""
                        + ClusterOverlay.error().replace("\"", "'") + "\"}");
            } else if (cmd.contains("token")) {
                wantProjection = true;
                Mirror.request(this);
            }
        }

        /* gancho: --ez pub true cria o display "evsound_mapa" (flags 3) e joga a skin nele */
        if (getIntent() != null && getIntent().getBooleanExtra("pub", false)) {
            int did = ClusterProjection.startPublic(this, urlFor(p.getString("skin", "industria"), true, false));
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), "pub.json");
                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                o.write(("{\"display\":" + did + ",\"erro\":\"" + ClusterProjection.error().replace("\"", "'") + "\"}")
                        .getBytes("UTF-8"));
                o.close();
            } catch (Throwable ignored) {
            }
            if (did > 0) {
                try {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setLaunchDisplayId(did);
                    Intent i2 = new Intent(this, MainActivity.class);
                    i2.putExtra("skinonly", true);
                    i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    startActivity(i2, o.toBundle());
                    Log.i(TAG, "skin lançada no display " + did);
                } catch (Throwable t) {
                    Log.w(TAG, "launch no display " + did + " recusado", t);
                }
            }
        }

        /* gancho de teste: --ez token true pede o consentimento e projeta com o token */
        if (getIntent() != null && getIntent().getBooleanExtra("token", false)) {
            wantProjection = true;
            Mirror.request(this);
        }

        /* gancho de teste por adb: --ez proj true|false força a projeção */
        if (getIntent() != null && getIntent().hasExtra("proj")) {
            boolean want = getIntent().getBooleanExtra("proj", true);
            getSharedPreferences("lab", MODE_PRIVATE).edit().putBoolean("projection", want).apply();
        }

        /* gancho de teste por adb: am start ... --ez cam true */
        if (getIntent() != null && getIntent().getBooleanExtra("cam", false)) {
            bridge.camShape("{\"x\":0,\"y\":90,\"w\":1920,\"h\":900}");
        }

        /* gancho de teste por adb: am broadcast -a com.sharklab.CAM -p com.xdja.clusterdemo */
        android.content.IntentFilter camFilter = new android.content.IntentFilter();
        camFilter.addAction("com.sharklab.CAM");
        camFilter.addAction("com.sharklab.CAMOFF");
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent i) {
                if (i == null) return;
                if ("com.sharklab.CAM".equals(i.getAction())) {
                    bridge.camShape("{\"x\":0,\"y\":90,\"w\":1920,\"h\":900}");
                } else {
                    bridge.camHide();
                }
            }
        }, camFilter);
        if (p.getBoolean("overlay", true)) {
            boolean ok = ClusterOverlay.show(this, urlFor(p.getString("skin", "industria"), true, false));
            Log.i(TAG, "painel por overlay: " + ok + " " + ClusterOverlay.error());
            if (ok) bridge.clusterCast(16);      /* assume o painel sem depender do EvSound */
        } else if (p.getBoolean("cluster_cast", true)) {
            bridge.clusterCast(16);
        }

        /* skin no painel do motorista, pela projeção (quando habilitada) */
        if (p.getBoolean("projection", false)) {
            ClusterProjection.start(this, bridge, urlFor(p.getString("skin", "industria"), true, false));
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (ClusterProjection.running()) return;      /* projeção ok */
                    Log.i(TAG, "sem projeção, usando espelho da IVI no painel");
                    bridge.clusterCast(16);
                }
            }, 1500);
        }

        /* painel do motorista: uma Presentation no display virtual do carro */
        if (p.getBoolean("cluster", false)) showCluster(p.getString("skin", "industria"));
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** dash/<id>/index.html do cartao (se existir) ou do APK, com os parametros da central. */
    private String urlFor(String id, boolean full, boolean light) {
        String rel = "dash/" + id + "/index.html";
        File ext = new File(getExternalFilesDir(null), rel);
        String base = ext.isFile() ? ("file://" + ext.getAbsolutePath()) : ("file:///android_asset/" + rel);
        StringBuilder sb = new StringBuilder(base).append("#lab=1&panel=1");
        if (full) sb.append("&full=1");
        if (light) sb.append("&light=1");
        String lang = java.util.Locale.getDefault().getLanguage();
        if (!("pt".equals(lang) || "es".equals(lang) || "en".equals(lang) || "zh".equals(lang))) lang = "es";
        sb.append("&h24=1&lang=").append(lang);
        return sb.toString();
    }

    /** lê cada script do APK e joga na página, na ordem */
    private void inject() {
        for (String rel : INJECT) {
            try {
                InputStream in = getAssets().open(rel);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                web.evaluateJavascript(out.toString("UTF-8"), null);
            } catch (IOException e) {
                Log.w(TAG, "não injetou " + rel, e);
            }
        }
    }

    private String readCmdFile() {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "cmd.txt");
            if (!f.isFile()) return "";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            in.close();
            return new String(b, 0, Math.max(0, n), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void writeResult(String name, String json) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), name);
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(json.getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) {
        }
    }

    /** velocidade do GPS (API pública): fonte real que não depende de permissão da BYD */
    private void startGps() {
        try {
            final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return;
            gpsListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location loc) {
                    if (loc != null && loc.hasSpeed()) gpsKmh = loc.getSpeed() * 3.6;
                    else if (loc != null) gpsKmh = -1;
                }

                @Override
                public void onStatusChanged(String p, int s, android.os.Bundle b) {
                }

                @Override
                public void onProviderEnabled(String p) {
                }

                @Override
                public void onProviderDisabled(String p) {
                }
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, gpsListener);
            Log.i(TAG, "GPS ligado para velocidade");
        } catch (SecurityException se) {
            Log.w(TAG, "sem permissão de localização");
        } catch (Throwable t) {
            Log.w(TAG, "GPS falhou", t);
        }
    }

    /** abre (ou reabre) a skin no display do painel, se o sistema permitir */
    private void showCluster(final String skin) {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display target = null;
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                d.getRealMetrics(m);
                if (m.widthPixels >= 800) target = d;
            }
            if (target == null) {
                clusterError = "sem display secundário";
                Log.w(TAG, clusterError);
                return;
            }
            final Display d = target;
            ui.post(new Runnable() {
                public void run() {
                    try {
                        if (cluster != null) cluster.dismiss();
                        cluster = new ClusterPresentation(MainActivity.this, d,
                                urlFor(skin, true, false) + "&wide=" + d.getWidth(), bridge);
                        cluster.show();
                        clusterError = "";
                        Log.i(TAG, "painel: presentation no display " + d.getDisplayId()
                                + " (" + d.getWidth() + "x" + d.getHeight() + ")");
                    } catch (Throwable t) {
                        clusterError = t.getClass().getSimpleName() + ": " + t.getMessage();
                        Log.w(TAG, "painel recusado no display " + d.getDisplayId(), t);
                    }
                }
            });
        } catch (Throwable t) {
            clusterError = t.toString();
            Log.w(TAG, "painel falhou", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
        if (p.getBoolean("cluster_cast", true) && !p.getBoolean("projection", false)) {
            bridge.clusterCast(16);
            ClusterOverlay.bringToFront();
            Log.i(TAG, "painel: tomado no onResume");
        }
    }

    @Override
    protected void onPause() {
        /* NÃO liberar o painel aqui: quando a skin do painel assume, a MainActivity pausa e
           o cmd 18 devolvia o painel da BYD, apagando a própria projeção em segundos */
        super.onPause();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == Mirror.REQUEST_CODE && result == RESULT_OK) {
            if (wantProjection) {
                wantProjection = false;
                try {
                    android.media.projection.MediaProjectionManager mpm =
                            (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    android.media.projection.MediaProjection mp = mpm.getMediaProjection(result, data);
                    ClusterProjection.startWithProjection(this, mp, bridge,
                            urlFor(getSharedPreferences("lab", MODE_PRIVATE).getString("skin", "industria"), true, false));
                } catch (Throwable t) {
                    Log.w(TAG, "projeção com token falhou", t);
                }
            } else {
                Mirror.start(this, result, data);
                startActivity(new Intent(this, MirrorActivity.class));
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
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

    /** Ponte minima: o que a skin pede para desenhar. */
    public class Lab {
        private float speed = 0f;
        private boolean up = true;
        private boolean lastReverse = false;

        /**
         * Estado para a skin. Se as APIs do BYD responderem, manda o carro de verdade e
         * completa com a simulação só o que faltar; senão, simula tudo (~20 Hz).
         */
        @JavascriptInterface
        public String state() {
            double[] b = byd.snapshot();
            boolean car = b[0] > -998;                      /* velocidade respondeu */
            if (!car && gpsKmh >= 0) { b[0] = gpsKmh; car = true; }
            double speedKmh = car ? b[0] : 0;

            /* simulação: sempre roda, e é o que sobra quando o carro não responde */
            speed += up ? 1.6f : -1.6f;
            if (speed > 120) { speed = 120; up = false; }
            if (speed < 0) { speed = 0; up = true; }
            t += 0.05f;
            double simSpd = speed;
            int spd = car ? (int) Math.round(speedKmh) : (int) Math.round(simSpd);
            int bydGear = b[1] > -998 ? (int) b[1] : -1;     /* 0 N, 1 R, 2 D, 3 P, 4 N */
            int gsel = bydGear >= 0 ? bydGear : 2;
            int gear = bydGear >= 0 ? Math.max(1, Math.min(6, spd / 22 + 1))
                                    : Math.max(1, Math.min(6, (int) (spd / 22) + 1));
            int rpm = car ? rpmFor(spd, gear) : 900 + (int) (spd * 42);
            int pw = b[2] > -998 ? (int) Math.round(b[2]) : Math.round(spd * 0.9f - (up ? 0 : 25));
            int soc = b[3] > -998 ? (int) Math.round(b[3]) : Math.max(0, 82 - (int) (spd / 8));
            int range = b[4] > -998 ? (int) Math.round(b[4]) : Math.max(0, 412 - (int) (spd / 6));
            int ped = b[5] > -998 ? (int) Math.round(b[5]) : Math.round(Math.min(100, spd / 1.2f));
            int brk = b[6] > -998 ? (int) Math.round(b[6]) : (up ? 0 : 40);
            int temp = b[7] > -998 ? (int) Math.round(b[7]) : 24;
            int odo = b[8] > -998 ? (int) Math.round(b[8]) : 12843;
            double v12 = b[9] > -998 ? b[9] : 13.8;
            boolean turn = b[10] > -998 ? b[10] > 0 : spd > 60;
            /* ré: mostra a câmera atrás da skin, à direita */
            boolean reverse = gsel == 1;
            if (reverse != lastReverse) {
                lastReverse = reverse;
                if (reverse) camShape("{\"x\":660,\"y\":190,\"w\":600,\"h\":340}");
                else camHide();
            }
            int pu = b[11] > -998 ? (int) b[11] : 1;
            int spdU = b[12] > -998 ? (int) b[12] : 1;
            return "{\"pu\":" + pu + ",\"du\":1,\"su\":" + spdU + ",\"car\":\"v8\",\"phase\":\"on\",\"reverse\":" + (gsel == 1)
                    + ",\"speed\":" + spd
                    + ",\"gear\":" + Math.max(1, Math.min(6, gear))
                    + ",\"gsel\":" + gsel + ",\"boost\":0.3,\"load\":" + (ped / 100.0)
                    + ",\"rpm\":" + rpm
                    + ",\"ped\":" + ped
                    + ",\"soc\":" + soc
                    + ",\"brk\":" + brk
                    + ",\"turnL\":false,\"turnR\":" + turn
                    + ",\"pw\":" + pw
                    + ",\"odo\":" + odo + ",\"range\":" + range
                    + ",\"tout\":" + temp + ",\"btemp\":30,\"dmode\":2,\"drive\":\"ECO\",\"ready\":1,\"regen\":0"
                    + ",\"v12\":" + v12
                    + ",\"phone\":-1,\"tt\":{},\"rhd\":false,\"warn\":[],\"tyre\":null"
                    + ",\"adas\":" + byd.adasJson()
                    + ",\"cv\":null,\"nmv\":null,\"ext\":null,\"trip\":{\"km\":12.4}}";
        }

        /** giro estimado a partir da velocidade e da marcha (as APIs do BYD não dão rpm) */
        private int rpmFor(int kmh, int gearIdx) {
            double[] ratios = {3.9, 2.4, 1.6, 1.2, 0.95, 0.78};
            int g = Math.max(1, Math.min(6, gearIdx));
            double mps = kmh / 3.6;
            double rpm = mps / 2.35 * ratios[g - 1] * 3.55 * 60;
            return (int) Math.max(700, Math.min(6500, rpm));
        }

        /** "car" quando as APIs do BYD respondem, "sim" quando é tudo simulação */
        @JavascriptInterface
        public String source() {
            return byd.available() ? "car" : "sim";
        }

        /** última manobra do Waze/Maps lida da notificação */
        @JavascriptInterface
        public String nav() {
            return NavWatch.snapshot();
        }

        @JavascriptInterface
        public boolean navAccess() {
            return NavWatch.isEnabled(getApplicationContext());
        }

        @JavascriptInterface
        public void navSettings() {
            try {
                Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "sem tela de acesso a notificações", t);
            }
        }

        @JavascriptInterface
        public boolean autostartGet() {
            return getSharedPreferences("lab", MODE_PRIVATE).getBoolean("autostart", true);
        }

        @JavascriptInterface
        public void autostartSet(boolean on) {
            getSharedPreferences("lab", MODE_PRIVATE).edit().putBoolean("autostart", on).apply();
        }

        /** comando cru ao AutoContainer (diagnóstico): sendJson(type, json) */
        @JavascriptInterface
        public String jsonCmd(int type, String json) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
                Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
                Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                Object r = svc.getClass().getMethod("sendJson", int.class, String.class)
                        .invoke(svc, type, json);
                return String.valueOf(r);
            } catch (Throwable t) {
                Throwable c = t;
                while (c.getCause() != null && c != c.getCause()) c = c.getCause();
                return "erro: " + c;
            }
        }

        /** skin no painel como janela overlay (título que o container procura) */
        @JavascriptInterface
        public String overlayShow() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            boolean ok = ClusterOverlay.show(MainActivity.this,
                    urlFor(p.getString("skin", "industria"), true, false));
            if (ok) clusterCast(16);
            return "{\"ok\":" + ok + ",\"erro\":\"" + ClusterOverlay.error().replace("\"", "'") + "\"}";
        }

        @JavascriptInterface
        public void overlayHide() {
            ClusterOverlay.hide();
        }

        @JavascriptInterface
        public boolean overlayOn() {
            return ClusterOverlay.running();
        }

        /** liga/desliga o tema no painel (persistente) */
        @JavascriptInterface
        public String clusterToggle() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            boolean on = !p.getBoolean("cluster_cast", true);
            p.edit().putBoolean("cluster_cast", on).apply();
            clusterCast(on ? 16 : 18);
            Log.i(TAG, "tema no painel: " + (on ? "ligado" : "desligado"));
            return "{\"on\":" + on + "}";
        }

        @JavascriptInterface
        public boolean clusterOn() {
            return getSharedPreferences("lab", MODE_PRIVATE).getBoolean("cluster_cast", true);
        }

        /**
         * Painel do motorista: o cluster é outra instância do sistema (fission), não um display
         * Android. O canal é o binder AutoContainer, tipo 1000:
         *   16 = espelhar a IVI em tela cheia, 17 = metade, 18 = desligar.
         */
        @JavascriptInterface
        public String clusterCast(int cmd) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
                if (binder == null) return "{\"ok\":false,\"why\":\"sem AutoContainer\"}";
                Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
                Object svc = stub.getMethod("asInterface", android.os.IBinder.class)
                        .invoke(null, binder);
                svc.getClass().getMethod("sendInfo", int.class, int.class, String.class)
                        .invoke(svc, 1000, cmd, "");
                Log.i(TAG, "cluster cast cmd=" + cmd);
                return "{\"ok\":true,\"cmd\":" + cmd + "}";
            } catch (Throwable t) {
                Throwable c = t;
                while (c.getCause() != null && c != c.getCause()) c = c.getCause();
                Log.w(TAG, "cluster cast falhou cmd=" + cmd + " causa=" + c);
                return "{\"ok\":false,\"why\":\"" + String.valueOf(c).replace("\"", "'") + "\"}";
            }
        }

        /**
         * Câmera do carro pelo jar da BYD: carrega bmmcamera.jar num DexClassLoader e
         * lista os métodos de AVMCamera + a interface de callback, para implementar contra
         * o que existe de verdade nesta unidade.
         */
        @JavascriptInterface
        public String camProbe() {
            StringBuilder sb = new StringBuilder("{");
            try {
                java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                sb.append("\"jarExists\":").append(jar.isFile()).append(',');
                try {
                    BydCamera.loader(getApplicationContext());
                    sb.append("\"jniLoaded\":true,");
                } catch (Throwable t) {
                    sb.append("\"jniLoaded\":false,\"jniError\":\"").append(String.valueOf(t).replace("\"", "'")).append("\",");
                }
                Class<?> avm = BydCamera.avmClass(getApplicationContext());
                sb.append("\"methods\":[");
                boolean first = true;
                for (java.lang.reflect.Method m : avm.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("\"").append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                        Class<?>[] ps = m.getParameterTypes();
                        for (int i = 0; i < ps.length; i++) {
                            if (i > 0) sb.append(',');
                            sb.append(ps[i].getSimpleName());
                        }
                        sb.append(")\"");
                    }
                }
                sb.append("],\"ifaces\":[");
                boolean f2 = true;
                for (Class<?> c : avm.getDeclaredClasses()) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append("\"").append(c.getSimpleName()).append('(');
                    if (c.isInterface()) {
                        java.lang.reflect.Method[] ims = c.getDeclaredMethods();
                        for (int i = 0; i < ims.length; i++) {
                            if (i > 0) sb.append(';');
                            sb.append(ims[i].getName()).append('/').append(ims[i].getParameterTypes().length);
                        }
                    }
                    sb.append(")\"");
                }
                sb.append("]}");
            } catch (Throwable t) {
                return "{\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
            }
            return sb.toString();
        }

        /** liga/desliga a projeção da skin no painel do motorista */
        @JavascriptInterface
        public String projStart() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            p.edit().putBoolean("projection", true).apply();
            /* precisa do token de captura: PUBLIC sem ele é recusado pelo sistema */
            wantProjection = true;
            Mirror.request(MainActivity.this);
            return "{\"step\":\"aguardando consentimento\"}";
        }

        @JavascriptInterface
        public String projStop() {
            getSharedPreferences("lab", MODE_PRIVATE).edit().putBoolean("projection", false).apply();
            ClusterProjection.stop();
            return projState();
        }

        @JavascriptInterface
        public String projState() {
            return "{\"running\":" + ClusterProjection.running()
                    + ",\"error\":\"" + ClusterProjection.error().replace("\"", "'") + "\"}";
        }

        /** controles da música: prev | play | next */
        @JavascriptInterface
        public void media(String cmd) {
            MediaNow.control(getApplicationContext(), cmd);
        }

        /** posiciona a superfície da câmera atrás da skin */
        @JavascriptInterface
        public void camShape(String json) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                final int x = o.optInt("x"), y = o.optInt("y"), w = o.optInt("w"), h = o.optInt("h");
                ui.post(new Runnable() {
                    public void run() {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) camView.getLayoutParams();
                        lp.width = Math.max(1, w);
                        lp.height = Math.max(1, h);
                        lp.leftMargin = x;
                        lp.topMargin = y;
                        camView.setLayoutParams(lp);
                        camView.setVisibility(View.VISIBLE);
                        camShown = true;
                        if (camTexture != null) {
                            boolean ok2 = BydCamera.attach(MainActivity.this, camTexture, 0);
                            try {
                                java.io.File f = new java.io.File(getExternalFilesDir(null), "camlog.json");
                                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                                o.write(("{\"attach\":" + ok2 + ",\"erro\":\"" + BydCamera.error().replace("\"", "'") + "\"}")
                                        .getBytes("UTF-8"));
                                o.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void camHide() {
            ui.post(new Runnable() {
                public void run() {
                    camShown = false;
                    camView.setVisibility(View.GONE);
                    if (camTexture != null) BydCamera.detach(MainActivity.this, camTexture, 0);
                }
            });
        }

        /**
         * Leva a skin para o display do painel do motorista.
         *
         * O carro expõe esse painel como um display virtual do sistema (1920×720, dono
         * com.xdja.containerservice). setLaunchDisplayId é API pública; se o sistema não
         * autorizar, devolve o motivo em vez de falhar calado.
         */
        @JavascriptInterface
        public String clusterShow() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            p.edit().putBoolean("cluster", true).apply();
            showCluster(p.getString("skin", "industria"));
            return "{\"ok\":" + (clusterError.isEmpty()) + ",\"why\":\"" + clusterError.replace("\"", "'") + "\"}";
        }

        @JavascriptInterface
        public void clusterHide() {
            getSharedPreferences("lab", MODE_PRIVATE).edit().putBoolean("cluster", false).apply();
            ui.post(new Runnable() {
                public void run() {
                    if (cluster != null) {
                        cluster.dismiss();
                        cluster = null;
                    }
                }
            });
        }

        @JavascriptInterface
        public String clusterLaunch() {
            final int id = clusterDisplayId();
            if (id < 0) return "{\"ok\":false,\"why\":\"sem segundo display\"}";
            ui.post(new Runnable() {
                public void run() {
                    try {
                        ActivityOptions o = ActivityOptions.makeBasic();
                        o.setLaunchDisplayId(id);
                        Intent i = new Intent(MainActivity.this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(i, o.toBundle());
                        Log.i(TAG, "skin enviada ao display " + id);
                    } catch (Throwable t) {
                        Log.w(TAG, "sistema recusou o display " + id, t);
                    }
                }
            });
            return "{\"ok\":true,\"display\":" + id + "}";
        }

        @JavascriptInterface
        public String clusterInfo() {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Display d : dm.getDisplays()) {
                    if (!first) sb.append(',');
                    first = false;
                    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                    d.getRealMetrics(m);
                    sb.append("{\"id\":").append(d.getDisplayId())
                      .append(",\"w\":").append(m.widthPixels)
                      .append(",\"h\":").append(m.heightPixels)
                      .append(",\"name\":\"").append(String.valueOf(d.getName())).append("\"}");
                }
                return sb.append(']').toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        /** id do display do painel, ou -1 */
        private int clusterDisplayId() {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                Display best = null;
                for (Display d : dm.getDisplays()) {
                    if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                    d.getRealMetrics(m);
                    if (m.widthPixels < 800) continue;
                    if (best == null || m.widthPixels * m.heightPixels > best.getWidth() * best.getHeight()) best = d;
                }
                return best == null ? -1 : best.getDisplayId();
            } catch (Throwable t) {
                return -1;
            }
        }

        /** abre o teste da câmera do carro (ré/panorâmica) */
        @JavascriptInterface
        public void camOpen() {
            ui.post(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(MainActivity.this, CamView.class));
                    } catch (Throwable t) {
                        Log.w(TAG, "não abriu a câmera", t);
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean camAvailable() {
            return CameraService.available();
        }

        /** métodos reais do serviço de câmera desta unidade */
        @JavascriptInterface
        public String camDump() {
            return CameraService.dump();
        }

        /** última tecla do volante lida pelo serviço de acessibilidade */
        @JavascriptInterface
        public String wheel() {
            return WheelKeys.snapshot();
        }

        @JavascriptInterface
        public boolean wheelAccess() {
            return WheelKeys.isEnabled(getApplicationContext());
        }

        @JavascriptInterface
        public void wheelSettings() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "sem tela de acessibilidade", t);
            }
        }

        /** captura de tela em tempo real (Waze, Maps, AA, CarPlay: o que estiver na tela) */
        @JavascriptInterface
        public void mirrorStart() {
            ui.post(new Runnable() {
                public void run() {
                    Mirror.request(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public boolean mirrorLive() {
            return Mirror.isRunning() && Mirror.frameAge() >= 0 && Mirror.frameAge() < 1500;
        }

        @JavascriptInterface
        public void mirrorStop() {
            Mirror.stop();
        }

        /** abre um app instalado na central (waze, android auto, carplay da BYD) */
        @JavascriptInterface
        public boolean openApp(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "não abriu " + pkg, t);
                return false;
            }
        }

        /** versão e origem de um app instalado (para saber se o Waze é o oficial) */
        @JavascriptInterface
        public String appInfo(String pkg) {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
                String installer = "?";
                try {
                    installer = String.valueOf(getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName());
                } catch (Throwable ignored) {
                }
                return "{\"installed\":true,\"version\":\"" + pi.versionName + "\",\"code\":" + pi.getLongVersionCode()
                        + ",\"installer\":\"" + installer + "\"}";
            } catch (Throwable t) {
                return "{\"installed\":false}";
            }
        }

        @JavascriptInterface
        public String apps() {
            String[] cand = {"com.waze", "com.google.android.apps.maps",
                    "com.byd.androidauto", "com.byd.carplay.ui", "com.byd.mediacenter", "com.byd.carsettings"};
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String c : cand) {
                boolean ok;
                try {
                    ok = getPackageManager().getLaunchIntentForPackage(c) != null;
                } catch (Throwable t) {
                    ok = false;
                }
                if (!ok) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(c).append('"');
            }
            return sb.append(']').toString();
        }

        @JavascriptInterface
        public String cars() {
            return "[{\"id\":\"v8\",\"name\":\"V8\",\"redline\":7000,\"turbo\":false,\"limiter\":8000,\"layout\":\"v\",\"cyl\":8,\"subtitle\":\"\",\"pro\":false}]";
        }

        @JavascriptInterface
        public String nowPlaying() {
            String real = MediaNow.snapshot(getApplicationContext());
            return real == null ? "{}" : real;
        }

        @JavascriptInterface
        public String position() {
            return "{}";
        }

        @JavascriptInterface
        public void beat(double ageMs, double frames, boolean hidden, String err) {
        }

        /** skins instaladas no cartao (getExternalFilesDir/dash/<id>/index.html) */
        @JavascriptInterface
        public String skins() {
            List<String> ids = new ArrayList<String>();
            File[] dirs = new File(getExternalFilesDir(null), "dash").listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (new File(d, "index.html").isFile()) ids.add(d.getName());
                }
            }
            for (String id : BUILTIN) {
                if (!ids.contains(id)) ids.add(id);
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(ids.get(i)).append('"');
            }
            return sb.append(']').toString();
        }

        /** base de uma skin: cartão (se existir) ou dentro do APK */
        private String skinBase(String id) {
            java.io.File ext = new java.io.File(getExternalFilesDir(null), "dash/" + id);
            if (new java.io.File(ext, "index.html").isFile()) return "file://" + ext.getAbsolutePath() + "/";
            return "file:///android_asset/dash/" + id + "/";
        }

        @JavascriptInterface
        public String thumbUrl(String id) {
            return skinBase(id) + "thumb.jpg";
        }

        @JavascriptInterface
        public String skinUrl(String id) {
            return skinBase(id) + "index.html";
        }

        @JavascriptInterface
        public String currentSkin() {
            return getSharedPreferences("lab", MODE_PRIVATE).getString("skin", "industria");
        }

        /** efeito de arranque no painel */
        @JavascriptInterface
        public void ignite() {
            ClusterOverlay.javaScript("window.EvsIgnition && window.EvsIgnition();");
        }

        /** menu avançado (ajustes/projeção/volante) */
        @JavascriptInterface
        public void openAdvanced() {
            ui.post(new Runnable() {
                public void run() {
                    if (web != null) web.loadUrl("file:///android_asset/lab/settings.html");
                }
            });
        }

        @JavascriptInterface
        public boolean overlayReload(final String id) {
            final SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            p.edit().putString("skin", id).apply();
            /* a janela só pode ser criada na thread de UI */
            ui.post(new Runnable() {
                public void run() {
                    ClusterOverlay.show(MainActivity.this, urlFor(id, true, false));
                }
            });
            return true;
        }

        public void applySkin(final String id) {
            getSharedPreferences("lab", MODE_PRIVATE).edit().putString("skin", id).apply();
            ui.post(new Runnable() {
                public void run() {
                    if (web != null) web.loadUrl(urlFor(id, false, false));
                }
            });
        }

        @JavascriptInterface
        public void openSkin() {
            ui.post(new Runnable() {
                public void run() {
                    if (web != null) web.loadUrl(urlFor(currentSkin(), false, false));
                }
            });
        }

        @JavascriptInterface
        public void openGallery() {
            ui.post(new Runnable() {
                public void run() {
                    if (web != null) web.loadUrl("file:///android_asset/lab/skins.html");
                }
            });
        }

        @JavascriptInterface
        public void setSkin(final String id) {
            getSharedPreferences("lab", MODE_PRIVATE).edit().putString("skin", id).apply();
            ui.post(new Runnable() {
                public void run() {
                    if (web != null) web.loadUrl(urlFor(id, false, false));
                }
            });
        }

        /** o que este carro deixa ler (roda no veículo, não no PC) */
        @JavascriptInterface
        public String probe() {
            String base = CarProbe.run(getApplicationContext());
            try {
                org.json.JSONObject o = new org.json.JSONObject(base);
                o.put("bydAvailable", byd.available());
                o.put("bydGetters", new org.json.JSONObject(byd.report()));
                return o.toString();
            } catch (Throwable t) {
                return base;
            }
        }

        @JavascriptInterface
        public void log(String s) {
            Log.i(TAG, s);
        }
    }
}
