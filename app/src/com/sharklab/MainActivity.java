package com.sharklab;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
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
            "lab/lab-feed.js", "lab/lab-sound.js", "lab/lab-ui.js"
    };
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float t = 0f;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

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
        setContentView(web);

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
        StringBuilder sb = new StringBuilder(base).append("#lab=1");
        if (full) sb.append("&full=1&panel=1");
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

        /** ~20 Hz: sobe e desce a velocidade para a skin mostrar o movimento */
        @JavascriptInterface
        public String state() {
            speed += up ? 1.6f : -1.6f;
            if (speed > 120) { speed = 120; up = false; }
            if (speed < 0) { speed = 0; up = true; }
            t += 0.05f;
            int rpm = 900 + (int) (speed * 42);
            int pw = Math.round(speed * 0.9f - (up ? 0 : 25));
            return "{\"pu\":1,\"du\":1,\"su\":1,\"car\":\"v8\",\"phase\":\"on\",\"reverse\":false"
                    + ",\"speed\":" + Math.round(speed)
                    + ",\"gear\":" + Math.max(1, Math.min(6, (int) (speed / 22) + 1))
                    + ",\"gsel\":2,\"boost\":0.3,\"load\":0.35"
                    + ",\"rpm\":" + rpm
                    + ",\"ped\":" + Math.round(Math.min(100, speed / 1.2f))
                    + ",\"soc\":" + Math.max(0, 82 - (int) (speed / 8))
                    + ",\"brk\":" + (up ? 0 : 40)
                    + ",\"turnL\":false,\"turnR\":" + (speed > 60)
                    + ",\"pw\":" + pw
                    + ",\"odo\":12843,\"range\":" + Math.max(0, 412 - (int) (speed / 6))
                    + ",\"tout\":24,\"btemp\":30,\"dmode\":2,\"drive\":\"ECO\",\"ready\":1,\"regen\":0"
                    + ",\"v12\":13.8,\"phone\":-1,\"tt\":{},\"rhd\":false,\"warn\":[],\"adas\":{},\"tyre\":null"
                    + ",\"cv\":null,\"nmv\":null,\"ext\":null,\"trip\":{\"km\":12.4}}";
        }

        @JavascriptInterface
        public String cars() {
            return "[{\"id\":\"v8\",\"name\":\"V8\",\"redline\":7000,\"turbo\":false,\"limiter\":8000,\"layout\":\"v\",\"cyl\":8,\"subtitle\":\"\",\"pro\":false}]";
        }

        @JavascriptInterface
        public String nowPlaying() {
            return "{\"title\":\"SharkLab\",\"artist\":\"simulação\"}";
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
            return CarProbe.run(getApplicationContext());
        }

        @JavascriptInterface
        public void log(String s) {
            Log.i(TAG, s);
        }
    }
}
