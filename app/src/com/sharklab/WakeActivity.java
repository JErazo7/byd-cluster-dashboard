package com.sharklab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * WakeActivity — wakes SharkLab without showing anything.
 *
 * The watchdog runs as shell and, with the package stopped after the shutdown,
 * `am start-foreground-service` is refused ("Not found; no service started"). An activity's
 * `am start` is always accepted. It is the same trick the reference app uses (`ArmActivity
 * --ez wake_only true`): this activity has no layout, starts PanelService and exits before the
 * first frame.
 */
public class WakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent svc = new Intent(this, PanelService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            AdbShell.trace(this, "wake: activity");
        } catch (Throwable t) {
            AdbShell.trace(this, "wake: failed " + t);
        }
        finish();
    }
}
