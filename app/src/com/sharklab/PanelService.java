package com.sharklab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

/**
 * PanelService — mantém o processo vivo com a projeção do painel.
 *
 * O MediaProjection (e o display virtual do painel) só existe enquanto o processo vive. Este
 * serviço em primeiro plano evita que o Android mate a SharkLab ao fechar a tela de ajustes,
 * então o consentimento de captura é pedido uma vez por arranque e não a cada abertura.
 */
public class PanelService extends Service {

    private static final String CHANNEL = "panel";
    private static final int ID = 1;

    /**
     * A tela da central apagando é o aviso de que o carro desligou. O receptor do manifest não
     * recebe SCREEN_OFF em Android 8+ (broadcast implícito) e o da activity morre com ela, então
     * quem escuta é o serviço, que fica vivo enquanto o painel está tomado.
     */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null) return;
            String a = i.getAction();
            AdbShell.trace(getApplicationContext(), "servico: " + a);
            if (Intent.ACTION_SCREEN_OFF.equals(a) || Intent.ACTION_SHUTDOWN.equals(a)
                    || "android.intent.action.QUICKBOOT_POWEROFF".equals(a)) {
                PanelRelease.release(getApplicationContext());
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SHUTDOWN);
        f.addAction("android.intent.action.QUICKBOOT_POWEROFF");
        registerReceiver(screenOff, f);
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screenOff); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Panel", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentTitle("SharkLab")
                    .setContentText("Panel activo")
                    .setOngoing(true)
                    .build();
            startForeground(ID, n);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
