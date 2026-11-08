package com.sharklab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Mirror — o que está na tela da central, em tempo real, para mostrar dentro do painel.
 *
 * MediaProjection (API pública, um consentimento) → VirtualDisplay → ImageReader → JPEG.
 * Não modifica nem depende de nenhum app de terceiros: serve para Waze, Google Maps,
 * Android Auto, CarPlay — o que estiver na tela.
 *
 * Limite: ~10 quadros/s a 640 px de largura (o suficiente para mapa), porque cada quadro
 * atravessa a ponte como imagem.
 */
public final class Mirror {

    public static final int REQUEST_CODE = 4711;
    private static final int WIDTH = 640;
    private static final long MIN_MS = 90;

    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static volatile byte[] frame;
    private static volatile long frameAt;
    private static volatile boolean running;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Mirror() {
    }

    public static MediaProjection projection() {
        return projection;
    }

    public static boolean isRunning() {
        return running;
    }

    public static byte[] frame() {
        return frame;
    }

    public static long frameAge() {
        return frame == null ? -1 : System.currentTimeMillis() - frameAt;
    }

    /** pede o consentimento de captura */
    public static void request(Activity activity) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return;
            activity.startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    /** chamado no onActivityResult da activity */
    public static void start(final Context ctx, int resultCode, Intent data) {
        if (data == null || resultCode != Activity.RESULT_OK) return;
        stop();
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int h = Math.max(1, Math.round(WIDTH * dm.heightPixels / (float) dm.widthPixels));

            reader = ImageReader.newInstance(WIDTH, h, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("sharklab-mirror", WIDTH, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ui);

            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                private long last;

                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        long now = System.currentTimeMillis();
                        if (now - last < MIN_MS) return;
                        last = now;
                        frame = toJpeg(img);
                        frameAt = now;
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) {
                            try { img.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }, ui);
            running = true;
        } catch (Throwable t) {
            stop();
        }
    }

    /** RGBA_8888 costuma vir com preenchimento de linha: recorta pela rowStride */
    private static byte[] toJpeg(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * img.getWidth();

        Bitmap padded = Bitmap.createBitmap(
                img.getWidth() + rowPadding / pixelStride, img.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        Bitmap exact = Bitmap.createBitmap(padded, 0, 0, img.getWidth(), img.getHeight());
        ByteArrayOutputStream out = new ByteArrayOutputStream(24 * 1024);
        exact.compress(Bitmap.CompressFormat.JPEG, 55, out);
        padded.recycle();
        exact.recycle();
        return out.toByteArray();
    }

    public static void stop() {
        running = false;
        try { if (display != null) display.release(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        display = null;
        reader = null;
        projection = null;
        frame = null;
    }
}
