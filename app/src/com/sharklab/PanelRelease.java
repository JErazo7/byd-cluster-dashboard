package com.sharklab;

import android.util.Log;

/** PanelRelease — devolve o painel ao layout original da BYD (cmd 18) e pausa o laço ADB. */
public final class PanelRelease {

    private static final String TAG = "SharkLab";

    private PanelRelease() {
    }

    public static void release() {
        AdbShell.stop();
        if (PanelCast.send(18)) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
            if (binder == null) return;
            Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
            Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            svc.getClass().getMethod("sendInfo", int.class, int.class, String.class)
                    .invoke(svc, 1000, 18, "");
            Log.i(TAG, "painel: devolvido ao original da BYD");
        } catch (Throwable t) {
            Log.w(TAG, "painel: release falhou", t);
        }
    }
}
