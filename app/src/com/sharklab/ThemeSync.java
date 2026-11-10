package com.sharklab;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * ThemeSync — mantém a cópia editável do tema em sincronia com a que vem no APK.
 *
 * O painel carrega `files/dash/<id>/index.html` (o usuário pode editar sem recompilar).
 * O problema é que essa cópia ficava velha para sempre: o tema novo entrava no APK e o painel
 * continuava desenhando o antigo. Agora, se a cópia for mais velha que a instalação do APK,
 * ela é substituída pela do APK.
 */
public final class ThemeSync {

    private static final String TAG = "SharkLab";

    private ThemeSync() {
    }

    public static void run(Context ctx) {
        long apk = installedAt(ctx);
        for (String id : MainActivity.BUILTIN) {
            File dir = new File(ctx.getExternalFilesDir(null), "dash/" + id);
            File index = new File(dir, "index.html");
            // ainda não existe (ou foi apagada pelo usuário): extrai do APK
            boolean stale = !index.isFile() || index.lastModified() < apk;
            if (!stale) continue;
            try {
                copyTree(ctx, "dash/" + id, dir);
                // o mtime do arquivo copiado é o de agora: a cópia deixa de parecer velha
                Log.i(TAG, "tema " + id + " sincronizado com o APK");
            } catch (Throwable t) {
                Log.w(TAG, "tema " + id + ": sincronização falhou", t);
            }
        }
    }

    private static long installedAt(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** copia a árvore de assets/dash/<id>/ para o diretório de destino */
    private static void copyTree(Context ctx, String assetPath, File dest) throws Exception {
        String[] entries = ctx.getAssets().list(assetPath);
        if (entries == null) return;
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IllegalStateException("sem diretório " + dest);
        }
        for (String name : entries) {
            String child = assetPath + "/" + name;
            File target = new File(dest, name);
            String[] sub = ctx.getAssets().list(child);
            if (sub != null && sub.length > 0) {
                copyTree(ctx, child, target);
                continue;
            }
            InputStream in = null;
            OutputStream out = null;
            try {
                in = ctx.getAssets().open(child);
                out = new FileOutputStream(target);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
                if (out != null) try { out.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** só para o log de arranque: quantos temas existem para mostrar */
    static String describe(Context ctx) {
        File dash = new File(ctx.getExternalFilesDir(null), "dash");
        String[] names = dash.list();
        return String.format(Locale.US, "dash=%d", names == null ? 0 : names.length);
    }
}
