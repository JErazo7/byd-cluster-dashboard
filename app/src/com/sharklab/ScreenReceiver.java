package com.sharklab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ScreenReceiver — quando a central dorme (carro desligado), o painel volta ao original da BYD. */
public class ScreenReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            PanelRelease.release();
        }
    }
}
