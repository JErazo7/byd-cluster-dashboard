package com.sharklab;

import android.util.Log;

/** PanelRelease — devolve o painel ao layout original da BYD (cmd 18) e pausa o laço ADB. */
public final class PanelRelease {

    private static final String TAG = "SharkLab";

    private PanelRelease() {
    }

    /**
     * Vale só dentro do processo: a skin está no painel porque o usuário (ou o arranque) pediu
     * AGORA. Se o processo morre — o carro apaga e o PanelService é ressuscitado — a skin não
     * volta: era isso que reaparecia em cima da alerta de porta aberta.
     */
    public static volatile boolean session;

    /** devolve o painel e marca a skin como desaplicada (o app não retoma sozinho no resume) */
    public static void release(android.content.Context ctx) {
        session = false;
        if (ctx != null) {
            /* applied E enabled: o laço de 60 s recriava a janela com `enabled` ainda ligado */
            ctx.getSharedPreferences("lab", android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean("applied", false).putBoolean("enabled", false).apply();
        }
        release();
    }

    public static void release() {
        session = false;
        /* a janela da skin tem que sair ANTES de devolver o painel: com o carro desligado a BYD
           acende o quadro para a alerta de porta aberta e a nossa janela ficava por cima dela */
        ClusterOverlay.hide();
        AdbShell.stop();
        /* a sequência do EvSound para devolver o painel: 46, 18, 0 */
        AdbShell.execOnce("service call AutoContainer 2 i32 1000 i32 46 s16 null; sleep 1.2; "
                + "service call AutoContainer 2 i32 1000 i32 18 s16 null; sleep 1; "
                + "service call AutoContainer 2 i32 1000 i32 0 s16 null");
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
