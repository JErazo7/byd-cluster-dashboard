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
        /* no arranque o painel fica no original da BYD; a skin só entra quando o app abre */
        PanelRelease.release();
        if (!ctx.getSharedPreferences("lab", Context.MODE_PRIVATE).getBoolean("autostart", false)) return;
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
