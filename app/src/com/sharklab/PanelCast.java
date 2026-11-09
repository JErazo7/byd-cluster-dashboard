package com.sharklab;

import android.util.Log;

/** PanelCast — manda um comando ao AutoContainer (16 assume o painel, 18 devolve à BYD). */
public final class PanelCast {

    private static final String TAG = "SharkLab";

    private PanelCast() {
    }

    public static boolean send(int cmd) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
            if (binder == null) return false;
            /* a central usa android.os.IAutoContainer$Stub; a outra é a do pacote do container */
            Class<?> stub;
            Object svc;
            try {
                stub = Class.forName("android.os.IAutoContainer$Stub");
                svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            } catch (Throwable e) {
                stub = Class.forName("com.xdja.autocontainer.IAutoContainer$Stub");
                svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            }
            svc.getClass().getMethod("sendInfo", int.class, int.class, String.class)
                    .invoke(svc, 1000, cmd, "");
            Log.i(TAG, "painel: cmd " + cmd);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "painel: cmd " + cmd + " falhou", t);
            return false;
        }
    }
}
