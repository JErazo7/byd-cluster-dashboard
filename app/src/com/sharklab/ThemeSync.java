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
 * ThemeSync — keeps the editable copy of the theme in sync with the one shipped in the APK.
 *
 * The panel loads `files/dash/<id>/index.html` (the user can edit it without recompiling).
 * The problem was that this copy stayed old forever: the new theme went into the APK and the
 * panel kept drawing the old one. Now, if the copy is older than the APK install, it is
 * replaced by the APK's.
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
            // does not exist yet (or was deleted by the user): extract from the APK
            boolean stale = !index.isFile() || index.lastModified() < apk;
            if (!stale) continue;
            try {
                copyTree(ctx, "dash/" + id, dir);
                // the copied file's mtime is now: the copy no longer looks old
                Log.i(TAG, "theme " + id + " synced with the APK");
            } catch (Throwable t) {
                Log.w(TAG, "theme " + id + ": sync failed", t);
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

    /** copies the assets/dash/<id>/ tree to the destination directory */
    private static void copyTree(Context ctx, String assetPath, File dest) throws Exception {
        String[] entries = ctx.getAssets().list(assetPath);
        if (entries == null) return;
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IllegalStateException("no directory " + dest);
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

    /** only for the boot log: how many themes exist to show */
    static String describe(Context ctx) {
        File dash = new File(ctx.getExternalFilesDir(null), "dash");
        String[] names = dash.list();
        return String.format(Locale.US, "dash=%d", names == null ? 0 : names.length);
    }
}
