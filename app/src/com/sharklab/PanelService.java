package com.sharklab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
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
