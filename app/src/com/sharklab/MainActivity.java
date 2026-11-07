package com.sharklab;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    private static final String[] BUILTIN = {"shark"};

    /** injetados nesta ordem depois da skin carregar (o mesmo caminho que o app de referência usa) */
    private static final String[] INJECT = {
            "engine/profiles.js", "engine/synth.js", "engine/model.js",
            "lab/lab-feed.js", "lab/lab-logbook.js", "lab/lab-sound.js", "lab/lab-ui.js"
    };
    private WebView web;
    private BydBridge byd;
    private TextureView camView;
    private android.view.Surface camSurface;
    private boolean camShown = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float t = 0f;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        byd = new BydBridge(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF04070C);

        camView = new TextureView(this);
        camView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                camSurface = new android.view.Surface(st);
                if (camShown) CameraService.openPreview(camSurface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                if (camSurface != null) {
                    CameraService.closePreview(camSurface);
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
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new Lab(), "Lab");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("file:")) inject();
            }
        });
        web.setBackgroundColor(0);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
        web.loadUrl(urlFor(p.getString("skin", "shark"), p.getBoolean("full", false), p.getBoolean("light", false)));
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
        sb.append("&h24=1&lang=pt");
        return sb.toString();
    }

    /** lê cada script do APK e joga na página, na ordem */
    private void inject() {
        evalAsset("engine/profiles.js");
        /* sons editáveis sem recompilar: <armazenamento>/sound/profiles.js sobrepõe a lista */
        File custom = new File(getExternalFilesDir(null), "sound/profiles.js");
        if (custom.isFile()) evalFile(custom);
        for (String rel : INJECT) {
            if ("engine/profiles.js".equals(rel)) continue;
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

    private void evalAsset(String rel) {
        try {
            evalStream(getAssets().open(rel), rel);
        } catch (IOException e) {
            Log.w(TAG, "não injetou " + rel, e);
        }
    }

    private void evalFile(File f) {
        try {
            evalStream(new java.io.FileInputStream(f), f.getAbsolutePath());
        } catch (IOException e) {
            Log.w(TAG, "não injetou " + f, e);
        }
    }

    private void evalStream(InputStream in, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        web.evaluateJavascript(out.toString("UTF-8"), null);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == Mirror.REQUEST_CODE && result == RESULT_OK) {
            Mirror.start(this, result, data);
            startActivity(new Intent(this, MirrorActivity.class));
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
            double speedKmh = car ? b[0] : 0;

            /* simulação: sempre roda, e é o que sobra quando o carro não responde */
            speed += up ? 1.6f : -1.6f;
            if (speed > 120) { speed = 120; up = false; }
            if (speed < 0) { speed = 0; up = true; }
            t += 0.05f;
            double simSpd = speed;
            int spd = car ? (int) Math.round(speedKmh) : (int) Math.round(simSpd);
            int gear = b[1] > -998 ? (int) b[1] : Math.max(1, Math.min(6, (int) (spd / 22) + 1));
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
            boolean reverse = gear == -1 || gear == 0;
            if (reverse != lastReverse) {
                lastReverse = reverse;
                if (reverse && CameraService.available()) camShape("{\"x\":640,\"y\":96,\"w\":600,\"h\":340}");
                else camHide();
            }
            int pu = b[11] > -998 ? (int) b[11] : 1;
            int spdU = b[12] > -998 ? (int) b[12] : 1;
            return "{\"pu\":" + pu + ",\"du\":1,\"su\":" + spdU + ",\"car\":\"v8\",\"phase\":\"on\",\"reverse\":" + (gear == -1)
                    + ",\"speed\":" + spd
                    + ",\"gear\":" + Math.max(1, Math.min(6, gear))
                    + ",\"gsel\":2,\"boost\":0.3,\"load\":" + (ped / 100.0)
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
                        if (camSurface != null) CameraService.openPreview(camSurface);
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
                    if (camSurface != null) CameraService.closePreview(camSurface);
                }
            });
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
