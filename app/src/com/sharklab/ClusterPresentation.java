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
 * ClusterPresentation — a skin no painel do motorista.
 *
 * O carro expõe o painel como um display virtual do sistema (1920×720, dono
 * com.xdja.containerservice). Não é uma Activity: quem aparece ali é uma Presentation,
 * que é API pública e não entra na pilha de atividades — por isso o dump de "activity
 * activities" mostra vazio e o painel mostra a skin.
 *
 * Se o sistema recusar a apresentação nesse display, o motivo vai para o log e para a
 * ponte (verify), em vez de ficar uma tela preta sem explicação.
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

    /** injeta no painel o mesmo conjunto de scripts do painel central (sem o som, para não duplicar áudio) */
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
                android.util.Log.w("SharkLab", "painel: não injetou " + rel, t);
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
