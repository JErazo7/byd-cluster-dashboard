package com.sharklab;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

/**
 * ClusterProjection — projeta a skin no painel do motorista, do jeito certo.
 *
 * O clúster não é um display Android: é outra instância (fission). Espelhar a IVI deixa tudo
 * fora de esquadro e ainda mostra na tela central o que deveria aparecer só atrás do volante.
 *
 * O caminho usado por apps que já fazem isso: criar um VirtualDisplay privado com o nome que o
 * compositor procura — shared_fission_bg_XDJAScreenProjection_0 (1920×720) — e desenhar a skin
 * nele com uma Presentation. A tela central fica livre para os ajustes.
 */
public final class ClusterProjection {

    private static final String TAG = "SharkLab";
    private static final String[] NAMES = {
            "shared_fission_bg_XDJAScreenProjection_0",
            "shared_fission_bg_XDJAScreenProjection_1"
    };
    private static final int W = 1920, H = 720, DPI = 320;
    /* o compositor da BYD pendura no painel a superfície por NOME (visto no SurfaceFlinger:
       "EvSound painel"), não por display */
    private static final String SURFACE_NAME = "EvSound painel";

    private static VirtualDisplay display;
    private static Surface surface;
    private static SurfaceTexture texture;
    private static ClusterPresentation presentation;
    private static String error = "";

    private ClusterProjection() {
    }

    public static String error() {
        return error;
    }

    public static boolean running() {
        return display != null;
    }

    /**
     * Cria o display como o app de referência: com o token do MediaProjection, flags
     * PUBLIC|PRESENTATION (3) e o nome que o compositor do clúster espera. Sem o token,
     * PUBLIC lança SecurityException; com OWN_CONTENT_ONLY o compositor ignora o display.
     */
    public static void startWithProjection(Context ctx, android.media.projection.MediaProjection mp,
                                           MainActivity.Lab bridge, String url) {
        stop();
        error = "";
        try {
            texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(W, H);
            surface = namedSurface(W, H);
            display = mp.createVirtualDisplay(NAMES[0], W, H, DPI, 3, surface, null,
                    new Handler(Looper.getMainLooper()));
            if (display == null) {
                error = "createVirtualDisplay devolveu null";
                return;
            }
            final Display d = display.getDisplay();
            final Context app = ctx;
            final MainActivity.Lab b = bridge;
            final String u = url;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        presentation = new ClusterPresentation(app, d, u, b);
                        presentation.show();
                        Log.i(TAG, "projeção (token): " + NAMES[0] + " display " + d.getDisplayId());
                    } catch (Throwable t) {
                        error = "apresentação recusada: " + t;
                        Log.w(TAG, error, t);
                    }
                }
            });
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "projeção com token falhou", t);
            stop();
        }
    }

    /** igual ao app de referência: display "evsound_mapa" com flags 3, sem token, e uma Activity dentro */
    public static int startPublic(Context ctx, String url) {
        stop();
        error = "";
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(W, H);
            surface = new Surface(texture);
            display = dm.createVirtualDisplay("evsound_mapa", W, H, DPI, surface, 3);
            int id = display.getDisplay().getDisplayId();
            Log.i(TAG, "projeção pública: evsound_mapa display " + id);
            return id;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "projeção pública falhou", t);
            return -1;
        }
    }

    /** Surface com nome (SurfaceControl); sem nome o painel ignora o display */
    static Surface namedSurface(int w, int h) {
        try {
            Class<?> builder = Class.forName("android.view.SurfaceControl$Builder");
            Object b = builder.getDeclaredConstructor().newInstance();
            builder.getMethod("setName", String.class).invoke(b, SURFACE_NAME);
            builder.getMethod("setBufferSize", int.class, int.class).invoke(b, w, h);
            Object sc = builder.getMethod("build").invoke(b);
            android.util.Log.i(TAG, "superfície nomeada: " + SURFACE_NAME);
            return new Surface((android.view.SurfaceControl) sc);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "SurfaceControl falhou (" + t + "), caindo para SurfaceTexture");
            return new Surface(texture);
        }
    }

    /** cria (ou recria) o display de projeção e a apresentação da skin */
    public static void start(Context ctx, MainActivity.Lab bridge, String url) {
        stop();
        error = "";
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(W, H);
            surface = new Surface(texture);

            /* o nome importa: é por ele que o compositor do clúster encontra a projeção */
            /* OWN_CONTENT_ONLY: display privado do app, sem o token de captura que o
               "screen sharing" exige. PUBLIC pediria CAPTURE_VIDEO_OUTPUT. */
            display = dm.createVirtualDisplay(NAMES[0], W, H, DPI, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);

            final Display d = display.getDisplay();
            final Context app = ctx;
            final MainActivity.Lab b = bridge;
            final String u = url;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        presentation = new ClusterPresentation(app, d, u, b);
                        presentation.show();
                        Log.i(TAG, "projeção: " + NAMES[0] + " display " + d.getDisplayId());
                    } catch (Throwable t) {
                        error = "apresentação recusada: " + t;
                        Log.w(TAG, error, t);
                    }
                }
            });
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "projeção falhou", t);
            stop();
        }
    }

    public static void stop() {
        try { if (presentation != null) presentation.dismiss(); } catch (Throwable ignored) {}
        presentation = null;
        try { if (display != null) display.release(); } catch (Throwable ignored) {}
        display = null;
        try { if (surface != null) surface.release(); } catch (Throwable ignored) {}
        surface = null;
        try { if (texture != null) texture.release(); } catch (Throwable ignored) {}
        texture = null;
    }
}
