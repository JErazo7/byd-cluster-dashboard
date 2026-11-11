package com.sharklab;

import android.util.Log;

/** PanelCast — sends a command to the AutoContainer (16 takes over the panel, 18 gives it back to BYD). */
public final class PanelCast {

    private static final String TAG = "SharkLab";

    private PanelCast() {
    }

    public static boolean send(int cmd) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
            if (binder == null) return false;
            /* the head unit uses android.os.IAutoContainer$Stub; the other one is the container package's */
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
            Log.i(TAG, "panel: cmd " + cmd);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "panel: cmd " + cmd + " failed", t);
            return false;
        }
    }
}
