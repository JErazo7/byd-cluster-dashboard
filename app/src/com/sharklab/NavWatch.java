package com.sharklab;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * NavWatch — navigation instruction without touching Waze.
 *
 * Waze and Google Maps publish the next maneuver and the distance as a notification. Reading the
 * notification is public API (the user grants "notification access" once), so
 * this works without root, without patching Waze and without capturing the screen.
 *
 * Exposed in Lab.nav(): {"src":"waze","title":"...","text":"...","dist":"..."}
 */
public class NavWatch extends NotificationListenerService {

    private static volatile String src = "";
    private static volatile String title = "";
    private static volatile String text = "";
    private static volatile long at = 0;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null) return;
        boolean waze = pkg.toLowerCase().contains("waze");
        boolean maps = pkg.contains("google.android.apps.maps");
        if (!waze && !maps) return;

        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle b = n.extras;
        String t = str(b, Notification.EXTRA_TITLE);
        String x = str(b, Notification.EXTRA_TEXT);
        String big = str(b, Notification.EXTRA_BIG_TEXT);
        String sub = str(b, Notification.EXTRA_SUB_TEXT);
        if (t == null && x == null && big == null) return;

        src = waze ? "waze" : "maps";
        title = t == null ? "" : t;
        text = big != null ? big : (x == null ? "" : x);
        if (sub != null && sub.length() > 0) text = text.length() > 0 ? text + " · " + sub : sub;
        at = System.currentTimeMillis();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        String pkg = sbn.getPackageName().toLowerCase();
        if (pkg.contains("waze") || pkg.contains("google.android.apps.maps")) {
            /* route ended */
            if (System.currentTimeMillis() - at > 8000) {
                src = ""; title = ""; text = "";
            }
        }
    }

    private static String str(Bundle b, String key) {
        try {
            CharSequence cs = b.getCharSequence(key);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** JSON with the last maneuver; empty text = no route */
    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"src\":\"").append(src).append("\",\"title\":\"").append(esc(title))
          .append("\",\"text\":\"").append(esc(text)).append("\",\"age\":")
          .append(src.isEmpty() ? -1 : (System.currentTimeMillis() - at)).append('}');
        return sb.toString();
    }

    public static boolean isEnabled(android.content.Context ctx) {
        try {
            String flat = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null) return false;
            String me = new ComponentName(ctx, NavWatch.class).flattenToString();
            return flat.contains(me) || flat.contains(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') o.append('\\').append(c);
            else if (c < 0x20) o.append(' ');
            else o.append(c);
        }
        return o.toString();
    }
}
