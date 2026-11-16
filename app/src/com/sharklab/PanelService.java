package com.sharklab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
            /* the projection is not rebuilt in one second when the container has to create the
               display: only reapply after it was missing for a few polls, so a shutdown does not
               summon the panel back on top of the open-door alert */
            int lost = 0;

            public void run() {
                try {
                    if (bridge[0] == null) bridge[0] = new BydBridge(getApplicationContext());
                    /* one binder call instead of the whole snapshot (37 calls) once a second:
                       this loop runs forever while the car is on */
                    double p = bridge[0].powerCtl();
                    int pc = p > -998 ? (int) p : -1;
                    SharedPreferences lab = getSharedPreferences("lab", MODE_PRIVATE);
                    /* want = the user's intent, either "apply on power-on" or the APPLY button.
                       release() clears `applied` when the car turns off, so this is not sticky.
                       An explicit RELEASE is NOT honoured while the car is on: the container keeps
                       the projection owner alive with no window and the panel stays black
                       (measured 16/11, 09:11) — releasing only makes sense at power-off. */
                    boolean want = lab.getBoolean("bootApply", false) || lab.getBoolean("applied", false);
                    boolean lostPanel = !ClusterOverlay.attached(getApplicationContext());
                    /* anything that says the skin was up: without this the release ran on every
                       poll once the window was gone (three container commands per second) */
                    boolean wasUp = lostPanel ? PanelRelease.session : true;
                    if (pc == 1 && want && lostPanel) {
                        lost++;
                        AdbShell.trace(getApplicationContext(), "service: car on, panel lost " + lost);
                        if (lost >= 3) {
                            lost = 0;
                            AdbShell.trace(getApplicationContext(), "service: car on -> applying");
                            PanelBoot.apply(getApplicationContext());
                        }
                    } else if (pc == 0 && wasUp) {
                        lost = 0;
                        AdbShell.trace(getApplicationContext(), "service: car off -> releasing");
                        PanelRelease.release(getApplicationContext());
                    } else {
                        lost = 0;
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
