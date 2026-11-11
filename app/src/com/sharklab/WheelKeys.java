package com.sharklab;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * WheelKeys — steering wheel buttons.
 *
 * AccessibilityService with a key filter: it receives what the head unit hands to accessibility
 * services (media, volume, arrows, enter, back). It does not interfere with other apps and does
 * not need root; the user enables the service once.
 *
 * The skin reads Lab.wheel() and decides the action (switch engine, switch panel, volume).
 */
public class WheelKeys extends AccessibilityService {

    private static volatile int lastKey = -1;
    private static volatile long lastAt = 0;
    private static volatile int count = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        /* only the keys matter */
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            lastKey = event.getKeyCode();
            lastAt = System.currentTimeMillis();
            count++;
        }
        return false;                     /* does not consume: the originating app keeps receiving */
    }

    @Override
    public void onInterrupt() {
    }

    /** last key: {key, age, count} */
    public static String snapshot() {
        return "{\"key\":" + lastKey + ",\"age\":" + (lastKey < 0 ? -1 : System.currentTimeMillis() - lastAt)
                + ",\"count\":" + count + "}";
    }

    public static boolean isEnabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services");
            if (flat == null) return false;
            String me = new ComponentName(ctx, WheelKeys.class).flattenToString();
            return flat.contains(me) || flat.contains(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
