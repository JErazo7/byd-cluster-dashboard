package com.sharklab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BootReceiver — sobe a skin no painel quando o carro liga.
 *
 * Com o pacote na lista branca do AutoContainer (com.xdja.clusterdemo), o próprio app
 * consegue mandar o espelho para o painel; não é preciso adb, nem daemon de shell.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if ("com.sharklab.RELEASE".equals(a) || "android.intent.action.ACTION_SHUTDOWN".equals(a)) {
            PanelRelease.release();
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"android.intent.action.HTC_QUICKBOOT_BOOT_COMPLETED".equals(a)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_USER_UNLOCKED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        applyChoice(ctx);
    }

    /**
     * Decide o que fazer no arranque conforme o toggle "iniciar automáticamente".
     * Extraído para poder ser exercitado sem reiniciar o carro (ver MainActivity).
     */
    public static void applyChoice(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE);
        boolean bootApply = p.getBoolean("bootApply", p.getBoolean("autostart", false));
        if (!bootApply) {
            /* toggle desligado: o painel fica com a BYD e a skin não sobe sozinha */
            p.edit().putBoolean("enabled", false).putBoolean("applied", false).apply();
            PanelRelease.release();
            return;
        }
        p.edit().putBoolean("applied", true).putBoolean("enabled", true).apply();
        /* ligado: aplica a skin no painel sem abrir a interface da central */
        PanelBoot.apply(ctx);
    }
}
