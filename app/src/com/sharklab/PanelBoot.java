package com.sharklab;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * PanelBoot — aplica a skin no painel sem abrir a interface da central.
 *
 * Usado quando o usuário marca "aplicar ao ligar" nas configurações: no arranque só a janela
 * do painel aparece, a central continua mostrando o que estava mostrando.
 */
public final class PanelBoot {

    private static final String TAG = "SharkLab";

    private PanelBoot() {
    }

    public static void apply(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE);
        String skin = p.getString("skin", "industria");
        boolean overlay = p.getBoolean("overlay", true);

        if (overlay) {
            String url = urlFor(ctx, skin);
            boolean ok = ClusterOverlay.show(ctx, url);
            Log.i(TAG, "boot: overlay=" + ok + " " + ClusterOverlay.error());
        }
        AdbShell.start(ctx);
        AdbShell.resume();
        if (p.getBoolean("cluster_cast", true)) PanelCast.send(16);
        p.edit().putBoolean("enabled", true).apply();
        startService(ctx);
    }

    /** mesma URL que a central monta para a skin (painel inteiro, 24 h, idioma do sistema) */
    private static String urlFor(Context ctx, String id) {
        String rel = "dash/" + id + "/index.html";
        File ext = new File(ctx.getExternalFilesDir(null), rel);
        String base = ext.isFile() ? ("file://" + ext.getAbsolutePath()) : ("file:///android_asset/" + rel);
        String lang = Locale.getDefault().getLanguage();
        if (!("pt".equals(lang) || "es".equals(lang) || "en".equals(lang) || "zh".equals(lang))) lang = "es";
        return base + "#lab=1&panel=1&full=1&h24=1&lang=" + lang;
    }

    private static void startService(Context ctx) {
        try {
            Intent i = new Intent(ctx, PanelService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "boot: serviço não subiu", t);
        }
    }
}
