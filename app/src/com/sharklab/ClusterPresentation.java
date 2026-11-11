package com.sharklab;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebView;

/**
 * ClusterPresentation — the skin on the driver cluster panel.
 *
 * The car exposes the panel as a system virtual display (1920×720, owner
 * com.xdja.containerservice). It is not an Activity: what shows up there is a Presentation,
 * which is public API and does not enter the activity stack — that is why the "activity
 * activities" dump shows up empty while the panel shows the skin.
 *
 * If the system refuses the presentation on that display, the reason goes to the log and to the
 * bridge (verify), instead of leaving a black screen with no explanation.
 */
public class ClusterPresentation extends Presentation {

    private WebView web;
    private String url;
    private final MainActivity.Lab bridge;

    public ClusterPresentation(Context ctx, Display display, String url, MainActivity.Lab bridge) {
        super(ctx, display);
        this.url = url;
        this.bridge = bridge;
    }

    /** injects into the panel the same script set as the head unit panel (without audio, so as not to duplicate sound) */
    private void inject() {
        if (web == null) return;
        for (String rel : MainActivity.INJECT_PANEL) {
            try {
                java.io.InputStream in = getContext().getAssets().open(rel);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                web.evaluateJavascript(out.toString("UTF-8"), null);
            } catch (Throwable t) {
                android.util.Log.w("SharkLab", "panel: did not inject " + rel, t);
            }
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(getContext());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setBackgroundColor(0);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String u) {
                if (u != null && u.startsWith("file:")) inject();
            }
        });
        if (bridge != null) web.addJavascriptInterface(bridge, "Lab");
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(0xFF04070C));
        } catch (Throwable ignored) {
        }
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (url != null) web.loadUrl(url);
    }

    public void load(String url) {
        this.url = url;
        if (web != null) web.loadUrl(url);
    }

    @Override
    protected void onStop() {
        if (web != null) web.loadUrl("about:blank");
        super.onStop();
    }
}
