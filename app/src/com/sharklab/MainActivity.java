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
 * SharkLab: a skin lab that runs outside the reference cluster app.
 *
 * Loads dash/<id>/index.html and feeds the skin with simulated data (assets/lab/lab-feed.js).
 * It exists to draw and test the cluster panel on the head unit itself, without reading the car.
 */
public class MainActivity extends Activity {

    private static final String TAG = "SharkLab";
    static final String[] BUILTIN = {"byd"};

    /** injected after the skin loads: this is what feeds the drawing with the car data */
    static final String[] INJECT = {
            "lab/lab-feed.js"
    };

    /** the cluster panel only draws: it does not inject the head unit control UI */
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
        registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        /* the head unit screen is NOT fullscreen; only the cluster overlay is */

        HiddenApi.allowAll();
        Log.i(TAG, "hiddenApi=" + HiddenApi.result());
        ClusterOverlay.enableDevtools();
        byd = new BydBridge(this);

        /* logcat diagnostics: what this unit answers (adb logcat -s SharkLab) */
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
                    Log.i(TAG, "probe.json written");
                } catch (Throwable t) {
                    Log.w(TAG, "probe failed", t);
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
                    Log.i(TAG, "camera: attach=" + ok + " error=" + BydCamera.error());
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
        /* with cover the skin fills the whole display (the panel gets a border-to-border image) */
        /* WebView over the whole display: the skin computes the scale by itself (fitSkin) */
        int h = FrameLayout.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, h);
        wlp.gravity = android.view.Gravity.CENTER;
        root.addView(web, wlp);
        setContentView(root);

        SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
        /* the cluster panel reads the editable copy in files/dash; if the APK is newer, refresh it first */
        ThemeSync.run(this);
        /* the only screen: theme grid + apply/release + start automatically */
        web.loadUrl("file:///android_asset/lab/home.html");

        startGps();

        /* Power on/off comes from the car itself: power.getPowerCtlStatus(3) — 1 on, 0
           off (measured). It applies as soon as power comes on (even in P) and releases as soon as it goes off. */
        final int[] tick = {0};
        ui.post(new Runnable() {
            public void run() {
                try {
                    SharedPreferences pa = getSharedPreferences("lab", MODE_PRIVATE);
                    double[] b = byd.snapshot();
                    int pc = b[36] > -998 ? (int) b[36] : -1;
                    if (pc == 1) {
                        if (pa.getBoolean("bootApply", false) && !ClusterOverlay.running()) {
                            AdbShell.trace(MainActivity.this, "auto: car on -> apply");
                            bridge.overlayShow();
                        }
                    } else if (pc == 0) {
                        if (ClusterOverlay.running()) {
                            AdbShell.trace(MainActivity.this, "auto: car off -> release");
                            bridge.panelRelease();
                        }
                    }
                } catch (Throwable ignored) {
                }
                /* re-arms the watchdog every ~60 s: it is what brings the app back after power off */
                if (++tick[0] % 60 == 0) AdbShell.watchdog(MainActivity.this);
                ui.postDelayed(this, 1000);
            }
        });

        testHooks(getIntent());

        if (getIntent() != null && getIntent().hasExtra("festate")) {
            final int v = getIntent().getIntExtra("festate", -1);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Class<?> stub = Class.forName("android.view.IMagicWindowManager$Stub");
                        Object binder = Class.forName("android.os.ServiceManager")
                                .getMethod("getService", String.class).invoke(null, "magicwindow");
                        Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                        Object before = svc.getClass().getMethod("getFeatureState").invoke(svc);
                        Object r = svc.getClass().getMethod("setFeatureState", int.class).invoke(svc, v);
                        Object after = svc.getClass().getMethod("getFeatureState").invoke(svc);
                        Log.i(TAG, "festate: before=" + before + " set=" + v + " r=" + r + " after=" + after);
                    } catch (Throwable t) {
                        Log.w(TAG, "festate failed", t);
                    }
                }
            }).start();
        }

        if (getIntent() != null && getIntent().getBooleanExtra("magic", false)) {
            new Thread(new Runnable() {
                public void run() {
                    StringBuilder sb = new StringBuilder("{\"iface\":[");
                    try {
                        Class<?> c = Class.forName("android.view.IMagicWindowManager");
                        java.lang.reflect.Method[] ms = c.getMethods();
                        for (int i = 0; i < ms.length; i++) {
                            if (i > 0) sb.append(',');
                            sb.append("\"").append(ms[i].getName()).append('(');
                            Class<?>[] ps = ms[i].getParameterTypes();
                            for (int j = 0; j < ps.length; j++) {
                                if (j > 0) sb.append(',');
                                sb.append(ps[j].getSimpleName());
                            }
                            sb.append("):").append(ms[i].getReturnType().getSimpleName()).append("\"");
                        }
                    } catch (Throwable t) {
                        sb.append("\"error:").append(t).append('"');
                    }
                    sb.append("],\"stub\":[");
                    try {
                        Class<?> stub = Class.forName("android.view.IMagicWindowManager$Stub");
                        java.lang.reflect.Method[] ms = stub.getDeclaredMethods();
                        boolean first = true;
                        for (java.lang.reflect.Method m : ms) {
                            if (!java.util.Arrays.toString(m.getParameterTypes()).contains("IBinder")) continue;
                            if (!first) sb.append(',');
                            first = false;
                            sb.append('"').append(m.getName()).append('(');
                            Class<?>[] ps = m.getParameterTypes();
                            for (int j = 0; j < ps.length; j++) {
                                if (j > 0) sb.append(',');
                                sb.append(ps[j].getSimpleName());
                            }
                            sb.append(')').append('"');
                        }
                    } catch (Throwable t) {
                        sb.append("\"error:").append(t).append('"');
                    }
                    sb.append("],\"state\":[");
                    try {
                        Class<?> stub = Class.forName("android.view.IMagicWindowManager$Stub");
                        Object binder = Class.forName("android.os.ServiceManager")
                                .getMethod("getService", String.class).invoke(null, "magicwindow");
                        Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                        String[] pkgs = {"com.xdja.clusterdemo"};
                        boolean first = true;
                        for (String pk : pkgs) {
                            if (!first) sb.append(',');
                            first = false;
                            sb.append("{\"pkg\":\"").append(pk).append("\"");
                            try {
                                Object magic = svc.getClass().getMethod("isMagicApp", String.class).invoke(svc, pk);
                                sb.append(",\"isMagic\":").append(String.valueOf(magic));
                            } catch (Throwable t) { sb.append(",\"isMagic\":\"error\""); }
                            try {
                                Object acts = svc.getClass().getMethod("getMagicAppActivities", String.class).invoke(svc, pk);
                                sb.append(",\"activities\":").append(String.valueOf(acts));
                            } catch (Throwable t) { sb.append(",\"activities\":\"error\""); }
                            sb.append('}');
                        }
                    } catch (Throwable t) {
                        sb.append("\"error:").append(t).append('"');
                    }
                    sb.append("]}");
                    try {
                        java.io.File f = new java.io.File(getExternalFilesDir(null), "magic.json");
                        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                        o.write(sb.toString().getBytes("UTF-8"));
                        o.close();
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
        }

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

        /* test: launch the skin on display 1 from the app itself (ActivityOptions) */
        if (getIntent() != null && getIntent().getBooleanExtra("disp", false)) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    try {
                        /* cluster panel display: look it up by the name the container service uses */
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
                                urlFor(getSharedPreferences("lab", MODE_PRIVATE).getString("skin", "byd"), true, false));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(i, o.toBundle());
                        Log.i(TAG, "disp: launched on display " + did);
                    } catch (Throwable t) {
                        Log.w(TAG, "disp: refused", t);
                    }
                }
            }, 1200);
        }

        /* file trigger (adb test): cmd.txt with "pub" / "token" / "proj" */
        final String cmd = readCmdFile();
        if (cmd != null && !cmd.isEmpty()) {
            Log.i(TAG, "cmd.txt=" + cmd);
            if (cmd.contains("pub")) {
                int d = ClusterProjection.startPublic(this, urlFor(p.getString("skin", "byd"), true, false));
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
                boolean ok = ClusterOverlay.show(this, urlFor(p.getString("skin", "byd"), true, false));
                writeResult("overlay.json", "{\"ok\":" + ok + ",\"erro\":\""
                        + ClusterOverlay.error().replace("\"", "'") + "\"}");
            } else if (cmd.contains("token")) {
                wantProjection = true;
                Mirror.request(this);
            }
        }

        /* hook: --ez pub true creates the "evsound_mapa" display (flags 3) and puts the skin on it */
        if (getIntent() != null && getIntent().getBooleanExtra("pub", false)) {
            int did = ClusterProjection.startPublic(this, urlFor(p.getString("skin", "byd"), true, false));
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
                    Log.i(TAG, "skin launched on display " + did);
                } catch (Throwable t) {
                    Log.w(TAG, "launch on display " + did + " refused", t);
                }
            }
        }

        /* test hook: --ez token true asks for consent and projects with the token */
        if (getIntent() != null && getIntent().getBooleanExtra("token", false)) {
            wantProjection = true;
            Mirror.request(this);
        }

        /* adb test hook: --ez proj true|false forces the projection */
        if (getIntent() != null && getIntent().hasExtra("proj")) {
            boolean want = getIntent().getBooleanExtra("proj", true);
            getSharedPreferences("lab", MODE_PRIVATE).edit().putBoolean("projection", want).apply();
        }

        /* adb test hook: am start ... --ez cam true */
        if (getIntent() != null && getIntent().getBooleanExtra("cam", false)) {
            bridge.camShape("{\"x\":0,\"y\":90,\"w\":1920,\"h\":900}");
        }

        /* adb test hook: am broadcast -a com.sharklab.CAM -p com.xdja.clusterdemo */
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
            boolean ok = ClusterOverlay.show(this, urlFor(p.getString("skin", "byd"), true, false));
            Log.i(TAG, "panel by overlay: " + ok + " " + ClusterOverlay.error());
            if (ok) ui.postDelayed(new Runnable() {
            public void run() { bridge.clusterCast(45); }
        }, 2000);      /* takes the panel without depending on the reference app */
            retryOverlay();
        } else if (p.getBoolean("cluster_cast", true)) {
            bridge.clusterCast(45);
        }

        if (getIntent() != null && getIntent().getBooleanExtra("release", false)) {
            released = true;
            PanelRelease.release();
        }

        /* panel via ADB shell (station key already authorized, no dialog) */
        if (!p.getBoolean("manual_v1", false)) {
            p.edit().putBoolean("autostart", false).putBoolean("manual_v1", true).apply();
        }
        startPanelKeep();
        AdbShell.start(this);

        /* driver cluster panel: a Presentation on the car's virtual display */
        if (p.getBoolean("cluster", false)) showCluster(p.getString("skin", "byd"));
    }

    /**
     * At boot the cluster panel display may not exist yet when onCreate runs; the overlay
     * fails and with no window no cmd 16 shows up. Retries for ~30 s while there is none.
     */
    private void retryOverlay() {
        final int[] tries = {0};
        final Runnable retry = new Runnable() {
            public void run() {
                if (ClusterOverlay.running()) return;
                ensureOverlay();
                if (!ClusterOverlay.running() && ++tries[0] < 15) ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(retry, 1500);
    }

    /** guarantees the cluster panel window when it does not exist (boot, display change, etc.) */
    private void ensureOverlay() {
        SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
        /* only recreates if the user (or boot) asked to apply: otherwise the loop resurrected
           the skin after the car was turned off */
        if (!PanelRelease.session || !p.getBoolean("applied", false) || !p.getBoolean("overlay", true)
                || ClusterOverlay.running()) return;
        boolean ok = ClusterOverlay.show(this, urlFor(p.getString("skin", "byd"), true, false));
        Log.i(TAG, "panel: overlay recreated=" + ok + " " + ClusterOverlay.error());
        if (ok) ui.postDelayed(new Runnable() {
            public void run() { bridge.clusterCast(45); }
        }, 2000);
    }

    /**
     * With the (token) projection active, reasserts cmd 16 every 5 s: it is what takes the panel
     * back when another app sends 18 or when the cluster returns to the native layout. With no
     * projection registered the 16 is refused, so the loop only runs with the projection alive.
     */
    private void startPanelKeep() {
        Log.i(TAG, "panel keep: started");
        ui.post(new Runnable() {
            public void run() {
                try {
                    /* 45 only with the window alive: fullscreen without the skin shows the native layout */
                    SharedPreferences p2 = getSharedPreferences("lab", MODE_PRIVATE);
                    if (PanelRelease.session && p2.getBoolean("applied", false)) {
                        if (ClusterOverlay.running()) bridge.clusterCast(45);
                        else ensureOverlay();
                        /* re-arms the power-off watchdog: if it already fired or never came up, it comes back */
                        AdbShell.watchdog(MainActivity.this);
                    }
                } catch (Throwable ignored) {
                }
                ui.postDelayed(this, 60000);
            }
        });
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** dash/<id>/index.html from the card (if present) or from the APK, with the head unit parameters. */
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

    /** reads each script from the APK and injects it into the page, in order */
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
                Log.w(TAG, "did not inject " + rel, e);
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

    /** GPS speed (public API): real source that does not depend on a BYD permission */
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
            Log.i(TAG, "GPS enabled for speed");
        } catch (SecurityException se) {
            Log.w(TAG, "no location permission");
        } catch (Throwable t) {
            Log.w(TAG, "GPS failed", t);
        }
    }

    /** opens (or reopens) the skin on the cluster panel display, if the system allows it */
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
                clusterError = "no secondary display";
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
                        Log.i(TAG, "panel: presentation on display " + d.getDisplayId()
                                + " (" + d.getWidth() + "x" + d.getHeight() + ")");
                    } catch (Throwable t) {
                        clusterError = t.getClass().getSimpleName() + ": " + t.getMessage();
                        Log.w(TAG, "panel refused on display " + d.getDisplayId(), t);
                    }
                }
            });
        } catch (Throwable t) {
            clusterError = t.toString();
            Log.w(TAG, "panel failed", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (released) return;
        takePanel();
    }

    private boolean released;

    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            /* car turned off/slept: releases the panel and closes the skin; on power on, the BYD original comes back */
            PanelRelease.release(c);
            MainActivity.this.finishAndRemoveTask();
        }
    };

    /**
     * Did the watchdog release the panel a short while ago? Then this start is the open-door
     * alert, not an ignition: do not re-apply. A real ignition takes much longer than that.
     */
    private boolean justReleasedByWatchdog() {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "off.ts");
            if (!f.isFile()) return false;
            long ts = Long.parseLong(new String(java.nio.file.Files.readAllBytes(f.toPath())).trim());
            return System.currentTimeMillis() / 1000 - ts < 45;
        } catch (Throwable t) {
            return false;
        }
    }

    private void takePanel() {
        SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
        /* 'applied' is the user's intent: only APPLY (or boot with the toggle on)
           turns the panel on. The default used to be on and the skin came up by itself when the app opened. */
        AdbShell.trace(this, "takePanel: applied=" + p.getBoolean("applied", false)
                + " bootApply=" + p.getBoolean("bootApply", false));
        if (!PanelRelease.session || !p.getBoolean("applied", false)) {
            Log.i(TAG, "panel: released by the user, not resumed");
            return;
        }
        /* ORDER MATTERS: the container only creates the panel display
           (shared_fission_bg_XDJAScreenProjection_0) after cmd 16. Creating the window before
           leaves the panel black, because the skin goes to a display that does not exist yet. */
        AdbShell.resume();
        /* 16 projects onto the panel, 45 only afterwards makes the skin fullscreen: sending 45 alone
           shows the BYD native layout */
        AdbShell.trace(this, "takePanel: enabled=" + p.getBoolean("enabled", true)
                + " cast=" + p.getBoolean("cluster_cast", true));
        if (p.getBoolean("cluster_cast", true)) {
            bridge.clusterCast(16);
            ui.postDelayed(new Runnable() {
                public void run() { bridge.clusterCast(45); }
            }, 1600);
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                ensureOverlay();
            }
        }, 1200);
        ClusterOverlay.bringToFront();
        Log.i(TAG, "panel: 16 sent; overlay in 1.2 s");
    }

    /** test hooks (they do not appear in the head unit UI) */
    private void testHooks(Intent it) {
        if (it == null) return;
        final boolean hWake = it.getBooleanExtra("wake", false);
        final boolean hBoot = it.getBooleanExtra("bootapply", false);
        final boolean hApply = it.getBooleanExtra("apply", false);
        final boolean hRelease = it.getBooleanExtra("release", false);
        if (it.getBooleanExtra("dumplog", false)) AdbShell.dumpLog(getApplicationContext());
        if (hWake) {
            /* wakes up like the reference app (activity with flag, never a service): starts the
               service and exits without UI, so it does not steal the head unit's "load now" dialog */
            try {
                Intent svc = new Intent(this, PanelService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
                AdbShell.trace(this, "wake: activity woke up");
            } catch (Throwable ignored) { }
            finish();
            return;
        }
    /* test hooks (they do not appear in the UI):
       --ez bootapply true  runs the boot decision
       --ez apply true      is the APPLY button
       --ez release true    is the RELEASE button */
    if (hBoot) {
        BootReceiver.applyChoice(getApplicationContext());
    }
    if (it != null && it.hasExtra("bydset")) {
        String spec0 = it.getStringExtra("bydset");
        final String spec = it.hasExtra("bydarg") ? spec0 + "|" + it.getIntExtra("bydarg", 0) : spec0;
        new Thread(new Runnable() {
            public void run() { bridge.bydSet(spec); }
        }).start();
    }
    if (it != null && it.getBooleanExtra("dumpstate", false)) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String js = new ClusterSkinActivity.PanelBridge(getApplicationContext()).state();
                    java.io.File f = new java.io.File(getExternalFilesDir(null), "state.json");
                    java.io.FileWriter w = new java.io.FileWriter(f, false);
                    w.write(js);
                    w.close();
                    AdbShell.trace(MainActivity.this, "state dump ok");
                } catch (Throwable t) {
                    AdbShell.trace(MainActivity.this, "state dump error " + t);
                }
            }
        }).start();
    }
    if (it != null && it.hasExtra("bydtrace")) {
        final String spec = it.getStringExtra("bydtrace");
        bridge.bydTrace(spec, it.getIntExtra("bydsec", 45));
    }
    if (it != null && it.hasExtra("bydpoll")) {
        final String spec = it.getStringExtra("bydpoll");
        bridge.bydPoll(spec, 75);
    }
    if (it != null && it.hasExtra("dumpapi")) {
        final String cl = it.getStringExtra("dumpapi");
        new Thread(new Runnable() {
            public void run() { bridge.apiDump(cl); }
        }).start();
    }
    if (hApply) {
        bridge.overlayShow();
    }
    if (it != null && it.getBooleanExtra("release", false)) {
        bridge.panelRelease();
    }
        /* Android re-delivers the original intent when it recreates the activity: without consuming the
           extras, a test `--ez apply true` re-applied the skin on every process restart
           (that is what made the theme show up with the car turned off) */
        if (it.hasExtra("apply") || it.hasExtra("release") || it.hasExtra("bootapply")
                || it.hasExtra("dumpstate") || it.hasExtra("dumplog")
                || it.hasExtra("bydtrace") || it.hasExtra("bydpoll") || it.hasExtra("bydset")
                || it.hasExtra("dumpapi")) {
            it.removeExtra("apply");
            it.removeExtra("release");
            it.removeExtra("bootapply");
            it.removeExtra("dumpstate");
            it.removeExtra("dumplog");
            it.removeExtra("bydtrace");
            it.removeExtra("bydpoll");
            it.removeExtra("bydset");
            it.removeExtra("dumpapi");
            setIntent(it);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        testHooks(intent);
        if (intent != null && intent.getBooleanExtra("release", false)) {
            released = true;
            PanelRelease.release();
            return;
        }
        released = false;
        takePanel();
    }

    @Override
    protected void onPause() {
        /* do NOT release the panel here: when the panel skin takes over, the MainActivity pauses and
           cmd 18 handed the panel back to BYD, wiping the projection itself in seconds */
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
                            urlFor(getSharedPreferences("lab", MODE_PRIVATE).getString("skin", "byd"), true, false));
                    bridge.clusterCast(45);
                    startService(new Intent(this, PanelService.class));
                } catch (Throwable t) {
                    Log.w(TAG, "token projection failed", t);
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
        try { unregisterReceiver(screenOff); } catch (Throwable ignored) {}

        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Minimal bridge: what the skin asks for in order to draw. */
    public class Lab {
        private float speed = 0f;
        private boolean up = true;
        private boolean lastReverse = false;

        /**
         * State for the skin. If the BYD APIs answer, it sends the real car and
         * fills in only what is missing with the simulation; otherwise it simulates everything (~20 Hz).
         */
        @JavascriptInterface
        public String state() {
            double[] b = byd.snapshot();
            boolean car = b[0] > -998;                      /* speed responded */
            if (!car && gpsKmh >= 0) { b[0] = gpsKmh; car = true; }
            double speedKmh = car ? b[0] : 0;

            /* simulation: always runs, and it is what is left when the car does not answer */
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
            int pw0 = b[2] > -998 ? (int) Math.round(b[2]) : Integer.MIN_VALUE;
            /* at boot BYD returns an out-of-range raw value (3095 kW): it is not a reading */
            int pw = (pw0 != Integer.MIN_VALUE && Math.abs(pw0) <= 400) ? pw0
                    : Math.round(spd * 0.9f - (up ? 0 : 25));
            int soc = b[3] > -998 ? (int) Math.round(b[3]) : Math.max(0, 82 - (int) (spd / 8));
            int range = b[4] > -998 ? (int) Math.round(b[4]) : Math.max(0, 412 - (int) (spd / 6));
            int ped = b[5] > -998 ? (int) Math.round(b[5]) : Math.round(Math.min(100, spd / 1.2f));
            int brk = b[6] > -998 ? (int) Math.round(b[6]) : (up ? 0 : 40);
            int temp = b[7] > -998 ? (int) Math.round(b[7]) : 24;
            int odo = b[8] > -998 ? (int) Math.round(b[8]) : 12843;
            double v12 = b[9] > -998 ? b[9] : 13.8;
            boolean turn = b[10] > -998 ? b[10] > 0 : spd > 60;
            /* reverse: shows the camera behind the skin, on the right */
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
                    + ",\"opmode\":" + i(b[25]) + ",\"surfmode\":" + i(b[23]) + ",\"ecoind\":" + i(b[31])
                    + ",\"fuel\":" + i(b[13]) + ",\"fuelKm\":" + i(b[14])
                    + ",\"ev\":" + (b[15] > -998 ? ((b[15] >= 200 && b[15] <= 8000) ? "0" : "1") : "null")
                    + ",\"tyre\":" + tyreJson(b)
                    + ",\"v12\":" + v12
                    + ",\"phone\":-1,\"tt\":{},\"rhd\":false,\"warn\":[],\"tyre\":null"
                    + ",\"adas\":" + byd.adasJson()
                    + ",\"cv\":null,\"nmv\":null,\"ext\":null,\"trip\":{\"km\":12.4}}";
        }

        private String i(double v) {
            return String.valueOf(v > -998 ? (int) Math.round(v) : -999);
        }

        private String tyreJson(double[] b) {
            double fl = byd.tyre(1), fr = byd.tyre(2), rl = byd.tyre(3), rr = byd.tyre(4);
            if (fl <= -998 && fr <= -998 && rl <= -998 && rr <= -998) return "null";
            return "{\"fl\":" + fl + ",\"fr\":" + fr + ",\"rl\":" + rl + ",\"rr\":" + rr + "}";
        }

        /** rpm estimated from speed and gear (the BYD APIs do not provide rpm) */
        private int rpmFor(int kmh, int gearIdx) {
            double[] ratios = {3.9, 2.4, 1.6, 1.2, 0.95, 0.78};
            int g = Math.max(1, Math.min(6, gearIdx));
            double mps = kmh / 3.6;
            double rpm = mps / 2.35 * ratios[g - 1] * 3.55 * 60;
            return (int) Math.max(700, Math.min(6500, rpm));
        }

        /** "car" when the BYD APIs answer, "sim" when it is all simulation */
        @JavascriptInterface
        public String source() {
            return byd.available() ? "car" : "sim";
        }

        /** latest Waze/Maps maneuver read from the notification */
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
                Log.w(TAG, "no notification access screen", t);
            }
        }

        /** apply the skin by itself when the car powers on (default: no) */
        @JavascriptInterface
        public boolean bootApplyGet() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            return p.getBoolean("bootApply", p.getBoolean("autostart", false));
        }

        @JavascriptInterface
        public void bootApplySet(boolean on) {
            getSharedPreferences("lab", MODE_PRIVATE).edit()
                    .putBoolean("bootApply", on).putBoolean("autostart", on).apply();
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), "autostart");
                if (on) f.createNewFile(); else f.delete();
            } catch (Throwable ignored) { }
            AdbShell.trace(MainActivity.this, "bootApply=" + on);
        }

        public boolean autostartGet() {
            return getSharedPreferences("lab", MODE_PRIVATE).getBoolean("autostart", false);
        }

        @JavascriptInterface
        public void autostartSet(boolean on) {
            getSharedPreferences("lab", MODE_PRIVATE).edit().putBoolean("autostart", on).apply();
        }

        /** raw command to AutoContainer (diagnostic): sendJson(type, json) */
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
                return "error: " + c;
            }
        }

        /** skin on the panel as an overlay window (the title the container looks for) */
        @JavascriptInterface
        public String overlayShow() {
            /* order measured in the car: it drops the old window, 46 creates the display, the window goes
               on the NEW display and only then does 45 put the panel fullscreen. A window created before
               46 stays on the old display and 45 shows the BYD native layout. */
            final SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            /* enabled=true re-arms the ADB loop (it is what starts the power-off watchdog) */
            p.edit().putBoolean("applied", true).putBoolean("enabled", true).apply();
            PanelRelease.session = true;
            /* the service is what listens for SCREEN_OFF: without it the theme stays on the display when
               the car turns off and BYD lights the open-door alert */
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(new Intent(MainActivity.this, PanelService.class));
                } else {
                    startService(new Intent(MainActivity.this, PanelService.class));
                }
            } catch (Throwable ignored) {
            }
            final String sk = p.getString("skin", "byd");
            try {
                java.io.File af = new java.io.File(getExternalFilesDir(null), "autostart");
                if (p.getBoolean("bootApply", false)) {
                    if (!af.isFile()) af.createNewFile();
                } else {
                    af.delete();
                }
            } catch (Throwable ignored) { }
            AdbShell.watchdog(MainActivity.this);
            ClusterOverlay.hide();
            new Thread(new Runnable() {
                public void run() {
                            AdbShell.start(MainActivity.this);
                    /* the panel display already exists in most cases; 46 recreates the display
                       and leaves the window orphaned, so it only runs if the window cannot be created */
                    AdbShell.trace(MainActivity.this, "overlayShow: entering");
                    final boolean[] res = {false};
                    final java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                    ui.post(new Runnable() {
                        public void run() {
                            res[0] = ClusterOverlay.show(MainActivity.this, urlFor(sk, true, false));
                            Log.i(TAG, "panel: overlay " + res[0] + " " + ClusterOverlay.error());
                            latch.countDown();
                        }
                    });
                    try { latch.await(6, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    try { Thread.sleep(1600); } catch (InterruptedException ignored) { }
                    if (!res[0]) {
                        AdbShell.execOnce("service call AutoContainer 2 i32 1000 i32 46 s16 null");
                        AdbShell.trace(MainActivity.this, "no window: 46 sent");
                        try { Thread.sleep(1800); } catch (InterruptedException ignored) { }
                        ui.post(new Runnable() {
                            public void run() {
                                res[0] = ClusterOverlay.show(MainActivity.this, urlFor(sk, true, false));
                                AdbShell.trace(MainActivity.this, "2nd window " + res[0]);
                            }
                        });
                        try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
                    }
                    if (res[0]) {
                        /* 16 projects onto the panel and only after the 16 does 45 become the skin fullscreen */
                        AdbShell.trace(MainActivity.this, "overlay ok, sending 16");
                        AdbShell.execOnce("service call AutoContainer 2 i32 1000 i32 16 s16 null");
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                        AdbShell.execOnce("service call AutoContainer 2 i32 1000 i32 45 s16 null");
                        AdbShell.trace(MainActivity.this, "16/45 sent");
                    }
                }
            }).start();
            return "{\"step\":\"taking the panel\"}";
        }

        @JavascriptInterface
        public void overlayHide() {
            ClusterOverlay.hide();
        }

        /** the release button: closes the skin and hands the panel back to the BYD original layout */
        @JavascriptInterface
        public void panelRelease() {
            getSharedPreferences("lab", MODE_PRIVATE).edit()
                    .putBoolean("enabled", false).putBoolean("applied", false).apply();
            ClusterOverlay.hide();
            PanelRelease.release();
        }

        @JavascriptInterface
        public boolean overlayOn() {
            return ClusterOverlay.running();
        }

        /** the panel is taken by the skin (user intent + window alive) */
        @JavascriptInterface
        public boolean panelApplied() {
            return getSharedPreferences("lab", MODE_PRIVATE).getBoolean("applied", false)
                    && ClusterOverlay.running();
        }

        /** turns the theme on the panel on/off (persistent) */
        @JavascriptInterface
        public String clusterToggle() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            boolean on = !p.getBoolean("cluster_cast", true);
            p.edit().putBoolean("cluster_cast", on).apply();
            clusterCast(on ? 16 : 18);
            Log.i(TAG, "theme on panel: " + (on ? "on" : "off"));
            return "{\"on\":" + on + "}";
        }

        @JavascriptInterface
        public boolean clusterOn() {
            return getSharedPreferences("lab", MODE_PRIVATE).getBoolean("cluster_cast", true);
        }

        /**
         * Driver cluster panel: the cluster is another instance of the system (fission), not an
         * Android display. The channel is the AutoContainer binder, type 1000:
         *   16 = mirror the head unit fullscreen, 17 = half, 18 = turn off.
         */
        @JavascriptInterface
        public String clusterCast(int cmd) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
                if (binder == null) return "{\"ok\":false,\"why\":\"no AutoContainer\"}";
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
                Log.w(TAG, "cluster cast failed cmd=" + cmd + " cause=" + c);
                return "{\"ok\":false,\"why\":\"" + String.valueOf(c).replace("\"", "'") + "\"}";
            }
        }

        /**
         * Vehicle camera via the BYD jar: loads bmmcamera.jar in a DexClassLoader and
         * lists the AVMCamera methods + the callback interface, so that code can be written against
         * what really exists on this unit.
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

        /** turns the skin projection on the driver cluster panel on/off */
        @JavascriptInterface
        public String projStart() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            p.edit().putBoolean("projection", true).putBoolean("enabled", true).apply();
            /* re-arms the ADB loop: the release button leaves it paused, and without the 60 s cmd 16
               the panel is not taken again */
            AdbShell.start(MainActivity.this);
            AdbShell.resume();
            /* needs the capture token: PUBLIC without it is refused by the system */
            wantProjection = true;
            Mirror.request(MainActivity.this);
            return "{\"step\":\"waiting for consent\"}";
        }

        @JavascriptInterface
        public String projStop() {
            /* off means off: it records the preference, stops the ADB loop (it was the one re-sending
               cmd 16 every 60 s) and hands the panel back to the BYD layout */
            getSharedPreferences("lab", MODE_PRIVATE).edit()
                    .putBoolean("projection", false)
                    .putBoolean("enabled", false)
                    .apply();
            ClusterProjection.stop();
            stopService(new Intent(MainActivity.this, PanelService.class));
            ClusterOverlay.hide();
            PanelRelease.release();
            return projState();
        }

        @JavascriptInterface
        public String projState() {
            return "{\"running\":" + ClusterProjection.running()
                    + ",\"error\":\"" + ClusterProjection.error().replace("\"", "'") + "\"}";
        }

        /** music controls: prev | play | next */
        @JavascriptInterface
        public void media(String cmd) {
            MediaNow.control(getApplicationContext(), cmd);
        }

        /** positions the camera surface behind the skin */
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
         * Takes the skin to the driver cluster panel display.
         *
         * The car exposes that panel as a system virtual display (1920×720, owner
         * com.xdja.containerservice). setLaunchDisplayId is a public API; if the system does not
         * authorize it, the reason is returned instead of failing silently.
         */
        @JavascriptInterface
        public String clusterShow() {
            SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            p.edit().putBoolean("cluster", true).apply();
            showCluster(p.getString("skin", "byd"));
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
            if (id < 0) return "{\"ok\":false,\"why\":\"no second display\"}";
            ui.post(new Runnable() {
                public void run() {
                    try {
                        ActivityOptions o = ActivityOptions.makeBasic();
                        o.setLaunchDisplayId(id);
                        Intent i = new Intent(MainActivity.this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(i, o.toBundle());
                        Log.i(TAG, "skin sent to display " + id);
                    } catch (Throwable t) {
                        Log.w(TAG, "system refused display " + id, t);
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

        /** cluster panel display id, or -1 */
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

        /** opens the car camera test (reverse/panoramic) */
        @JavascriptInterface
        public void camOpen() {
            ui.post(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(MainActivity.this, CamView.class));
                    } catch (Throwable t) {
                        Log.w(TAG, "did not open the camera", t);
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean camAvailable() {
            return CameraService.available();
        }

        /** real methods of this unit's camera service */
        @JavascriptInterface
        public String camDump() {
            return CameraService.dump();
        }

        /** latest steering wheel key read by the accessibility service */
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
                Log.w(TAG, "no accessibility screen", t);
            }
        }

        /** real-time screen capture (Waze, Maps, AA, CarPlay: whatever is on screen) */
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

        /** opens an app installed on the head unit (waze, android auto, BYD carplay) */
        @JavascriptInterface
        public boolean openApp(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "did not open " + pkg, t);
                return false;
            }
        }

        /** version and origin of an installed app (to tell whether Waze is the official one) */
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

        /** skins installed on the card (getExternalFilesDir/dash/<id>/index.html) */
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

        /** base of a skin: the card (if present) or inside the APK */
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
            return getSharedPreferences("lab", MODE_PRIVATE).getString("skin", "byd");
        }

        /** ignition effect on the panel */
        @JavascriptInterface
        public void ignite() {
            ClusterOverlay.javaScript("window.EvsIgnition && window.EvsIgnition();");
        }


        @JavascriptInterface
        public boolean overlayReload(final String id) {
            final SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
            p.edit().putString("skin", id).apply();
            /* the window can only be created on the UI thread */
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
        public void setSkin(final String id) {
            getSharedPreferences("lab", MODE_PRIVATE).edit().putString("skin", id).apply();
            ui.post(new Runnable() {
                public void run() {
                    if (web != null) web.loadUrl(urlFor(id, false, false));
                }
            });
        }

        /** what this car lets us read (runs on the vehicle, not on the PC) */
        /** calls a BYD API setter: "setting.BYDAutoSettingDevice|setX|2" (test, outside the UI) */
        @JavascriptInterface
        public String bydSet(String spec) {
            String[] p = spec.split("\\|");
            if (p.length < 2) return "error: spec";
            String cls = "android.hardware.bydauto." + p[0];
            try {
                Class<?> c = Class.forName(cls);
                Object dev;
                try {
                    java.lang.reflect.Method g = c.getDeclaredMethod("getInstance", android.content.Context.class);
                    g.setAccessible(true);
                    dev = g.invoke(null, new BydPermissionContext(getApplicationContext()));
                } catch (NoSuchMethodException e) {
                    java.lang.reflect.Method g = c.getDeclaredMethod("getInstance");
                    g.setAccessible(true);
                    dev = g.invoke(null);
                }
                java.lang.reflect.Method m;
                Object r;
                if (p.length >= 3) {
                    m = c.getMethod(p[1], int.class);
                    m.setAccessible(true);
                    r = m.invoke(dev, Integer.parseInt(p[2].trim()));
                } else {
                    m = c.getMethod(p[1]);
                    m.setAccessible(true);
                    r = m.invoke(dev);
                }
                AdbShell.trace(MainActivity.this, "byd " + spec + " -> " + r);
                return String.valueOf(r);
            } catch (Throwable t) {
                Throwable c2 = t;
                while (c2.getCause() != null && c2 != c2.getCause()) c2 = c2.getCause();
                AdbShell.trace(MainActivity.this, "byd " + spec + " error " + c2);
                return "error: " + c2;
            }
        }

        /** samples everything, unfiltered, with an ms timestamp (raw read of one mode cycle) */
        @JavascriptInterface
        public void bydTrace(final String spec, final int seconds) {
            new Thread(new Runnable() {
                public void run() {
                    String[] list = spec.split(",");
                    long end = System.currentTimeMillis() + seconds * 1000L;
                    while (System.currentTimeMillis() < end) {
                        StringBuilder sb = new StringBuilder("t=" + (System.currentTimeMillis() % 100000));
                        for (String item : list) {
                            String[] p = item.split("\\|");
                            sb.append(' ').append(p[1]).append('=').append(bydSet(p[0] + "|" + p[1] + "|").trim());
                        }
                        AdbShell.trace(MainActivity.this, sb.toString());
                        try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                    }
                    AdbShell.trace(MainActivity.this, "trace end");
                }
            }).start();
        }

        /** samples getters and logs only what changes (discovery of which reading follows the wheel) */
        @JavascriptInterface
        public void bydPoll(final String spec, final int seconds) {
            new Thread(new Runnable() {
                public void run() {
                    String[] list = spec.split(",");
                    int[] last = new int[list.length];
                    for (int i = 0; i < last.length; i++) last[i] = Integer.MIN_VALUE;
                    long end = System.currentTimeMillis() + seconds * 1000L;
                    while (System.currentTimeMillis() < end) {
                        for (int i = 0; i < list.length; i++) {
                            String[] p = list[i].split("\\|");
                            String r = bydSet(p[0] + "|" + p[1] + "|");
                            try {
                                int v = Integer.parseInt(r.trim());
                                if (v != last[i]) {
                                    last[i] = v;
                                    AdbShell.trace(MainActivity.this, "poll " + p[1] + "=" + v);
                                }
                            } catch (Throwable ignored) { }
                        }
                        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                    }
                    AdbShell.trace(MainActivity.this, "poll end");
                }
            }).start();
        }

        /** lists the public methods of the BYD API classes (discovery, does not appear in the UI) */
        @JavascriptInterface
        public String apiDump(String classes) {
            StringBuilder sb = new StringBuilder();
            String[] list = classes.split(",");
            for (String name : list) {
                String cls = name.trim();
                try {
                    Class<?> c = Class.forName(cls);
                    java.lang.reflect.Method[] ms = c.getMethods();
                    java.util.Arrays.sort(ms, new java.util.Comparator<java.lang.reflect.Method>() {
                        public int compare(java.lang.reflect.Method a, java.lang.reflect.Method b) {
                            return a.getName().compareTo(b.getName());
                        }
                    });
                    sb.append("== ").append(cls).append('\n');
                    for (java.lang.reflect.Method m : ms) {
                        if (m.getDeclaringClass() == Object.class) continue;
                        sb.append(m.getName()).append('(');
                        Class<?>[] ps = m.getParameterTypes();
                        for (int i = 0; i < ps.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(ps[i].getSimpleName());
                        }
                        sb.append("): ").append(m.getReturnType().getSimpleName()).append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("== ").append(cls).append(" error: ").append(t).append('\n');
                }
            }
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), "api.txt");
                java.io.FileWriter w = new java.io.FileWriter(f, false);
                w.write(sb.toString());
                w.close();
            } catch (Throwable ignored) { }
            return sb.toString();
        }

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
