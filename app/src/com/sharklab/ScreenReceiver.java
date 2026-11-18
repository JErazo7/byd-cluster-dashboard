package com.sharklab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ScreenReceiver — when the head unit sleeps (car off), the panel goes back to BYD's original. */
public class ScreenReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_SCREEN_OFF.equals(a) || "android.intent.action.ACTION_SHUTDOWN".equals(a)
                || "android.intent.action.QUICKBOOT_POWEROFF".equals(a)) {
            PanelRelease.release(ctx);
            return;
        }
        /* screen back on with the car running: another chance to bring the skin up */
        if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
            AutoStart.kick(ctx);
        }
    }
}
