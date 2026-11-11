package com.sharklab;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * MediaNow — what is playing, with album art and controls.
 *
 * Uses MediaSessionManager (same requirement as Waze: notification access). Returns title,
 * artist, art as a data URI (48 px) and state, and sends play/pause/next/previous to the active
 * session. No third-party app is modified.
 */
public final class MediaNow {

    private static String cacheKey = "";
    private static String cacheArt = "";
    private static long cacheAt = 0;

    private MediaNow() {
    }

    private static MediaController controller(Context ctx) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            ComponentName cn = new ComponentName(ctx, NavWatch.class);
            List<MediaController> list = msm.getActiveSessions(cn);
            for (MediaController c : list) {
                if (c.getPlaybackState() != null) return c;
            }
            return list.isEmpty() ? null : list.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** JSON for the skin: {title, artist, playing, art} */
    public static String snapshot(Context ctx) {
        MediaController c = controller(ctx);
        if (c == null) return null;
        MediaMetadata md = c.getMetadata();
        if (md == null) return null;
        String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
        if (title == null) return null;
        PlaybackState ps = c.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        String art = art(md);
        return "{\"title\":\"" + esc(title) + "\",\"artist\":\"" + esc(artist == null ? "" : artist)
                + "\",\"playing\":" + playing + (art == null ? "" : ",\"art\":\"" + art + "\"") + "}";
    }

    /** prev | play | next */
    public static void control(Context ctx, String cmd) {
        MediaController c = controller(ctx);
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        if (t == null) return;
        try {
            if ("next".equals(cmd)) t.skipToNext();
            else if ("prev".equals(cmd)) t.skipToPrevious();
            else {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
            }
        } catch (Throwable ignored) {
        }
    }

    /** small art as a data URI; only re-encodes when the track changes */
    private static String art(MediaMetadata md) {
        try {
            String key = str(md, MediaMetadata.METADATA_KEY_TITLE) + '|' + str(md, MediaMetadata.METADATA_KEY_ARTIST) + '|'
                    + md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long now = System.currentTimeMillis();
            if (key.equals(cacheKey) && now - cacheAt < 60000) return cacheArt;
            Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp == null) {
                cacheKey = key;
                cacheArt = "";
                cacheAt = now;
                return "";
            }
            int side = 48;
            Bitmap small = Bitmap.createScaledBitmap(bmp, side, side, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            small.compress(Bitmap.CompressFormat.JPEG, 70, out);
            cacheArt = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            cacheKey = key;
            cacheAt = now;
            return cacheArt;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String str(MediaMetadata md, String key) {
        try {
            CharSequence cs = md.getText(key);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
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
