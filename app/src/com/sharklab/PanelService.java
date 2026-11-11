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
 * PanelService — keeps the process alive with the panel projection.
 *
 * The MediaProjection (and the panel's virtual display) only exists while the process lives.
 * This foreground service keeps Android from killing SharkLab when the settings screen closes,
 * so the capture consent is asked once per boot and not on every launch.
 */
public class PanelService extends Service {

    private static final String CHANNEL = "panel";
    private static final int ID = 1;

    /**
     * The head unit screen turning off is the signal that the car shut down. The manifest
     * receiver does not get SCREEN_OFF on Android 8+ (implicit broadcast) and the activity's
     * receiver dies with it, so the one listening is the service, which stays alive while the
     * panel is taken over.
     */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null) return;
            String a = i.getAction();
            AdbShell.trace(getApplicationContext(), "service: " + a);
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
        AdbShell.trace(getApplicationContext(), "service: receiver registered");
        startPowerLoop();
    }

    /**
     * Who decides to apply/release is the service, not the activity: this way the adbd
     * watchdog can wake SharkLab without opening the UI on the head unit (the activity's
     * `am start` stole the "load now" dialog BYD shows on shutdown).
     */
    private void startPowerLoop() {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final BydBridge[] bridge = new BydBridge[1];
        h.post(new Runnable() {
            public void run() {
                try {
                    if (bridge[0] == null) bridge[0] = new BydBridge(getApplicationContext());
                    double[] b = bridge[0].snapshot();
                    int pc = b[36] > -998 ? (int) b[36] : -1;
                    boolean bootApply = getSharedPreferences("lab", MODE_PRIVATE).getBoolean("bootApply", false);
                    if (pc == 1 && bootApply && !ClusterOverlay.running()) {
                        AdbShell.trace(getApplicationContext(), "service: car on -> applying");
                        PanelBoot.apply(getApplicationContext());
                    } else if (pc == 0 && ClusterOverlay.running()) {
                        AdbShell.trace(getApplicationContext(), "service: car off -> releasing");
                        PanelRelease.release(getApplicationContext());
                    }
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, 1000);
            }
        });
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
