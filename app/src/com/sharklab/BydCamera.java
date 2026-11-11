package com.sharklab;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import java.lang.reflect.Method;

/**
 * BydCamera — the car camera through BYD's own jar.
 *
 * /system/framework/bmmcamera.jar is not on the boot classpath: it has to be loaded in a
 * DexClassLoader, and loadLibrary has to happen INSIDE that loader (JNI_OnLoad looks for
 * the classes in the same jar). Once that is done, AVMCamera delivers the frames straight into a
 * SurfaceTexture — no YUV conversion, no camera permission.
 *
 * Index 0 = pano_h (stitched panorama), the only camera exposed on this unit.
 */
public final class BydCamera {

    private static final String JAR = "/system/framework/bmmcamera.jar";
    private static Object camera;
    private static String error = "";
    private static boolean textureAttached;

    private BydCamera() {
    }

    public static String error() {
        return error;
    }

    public static boolean ready() {
        return camera != null;
    }

    private static ClassLoader loader;

    /**
     * A SINGLE DexClassLoader for the process: libbmmcamera_jni.so can only be opened once
     * per loader, and the second attempt fails with UnsatisfiedLinkError.
     */
    public static synchronized ClassLoader loader(Context ctx) throws Throwable {
        if (loader != null) return loader;
        loader = new dalvik.system.DexClassLoader(JAR,
                ctx.getCodeCacheDir().getAbsolutePath(), null, ctx.getClassLoader());
        /* JNIBMMCamera's static initializer calls System.loadLibrary with this loader */
        Class.forName("android.hardware.JNIBMMCamera", true, loader);
        return loader;
    }

    public static synchronized Class<?> avmClass(Context ctx) throws Throwable {
        return Class.forName("android.hardware.AVMCamera", true, loader(ctx));
    }

    public static synchronized boolean open(Context ctx, int index) {
        if (camera != null) return true;
        try {
            Class<?> avm = avmClass(ctx);
            camera = avm.getMethod("open", int.class).invoke(null, index);
            if (camera == null) {
                error = "open returned null";
                return false;
            }
            try { avm.getMethod("setCameraFps", int.class).invoke(camera, 30); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            error = String.valueOf(t);
            camera = null;
            return false;
        }
    }

    /** attaches the preview to a SurfaceTexture (the fast path: no YUV in between) */
    public static synchronized boolean attach(Context ctx, SurfaceTexture st, int slot) {
        if (!open(ctx, 0)) return false;
        try {
            Method m = camera.getClass().getMethod("addTexture", SurfaceTexture.class, int.class);
            Object ok = m.invoke(camera, st, slot);
            textureAttached = Boolean.TRUE.equals(ok) || "true".equals(String.valueOf(ok));
            start(ctx);
            return textureAttached;
        } catch (Throwable t) {
            error = "addTexture: " + t;
            return false;
        }
    }

    public static synchronized void detach(Context ctx, SurfaceTexture st, int slot) {
        if (camera == null) return;
        try { camera.getClass().getMethod("rmTexture", SurfaceTexture.class, int.class).invoke(camera, st, slot); } catch (Throwable ignored) {}
        textureAttached = false;
    }

    /** preview on a plain Surface (used by the full-screen test) */
    public static synchronized boolean attachSurface(Context ctx, Surface s, int slot) {
        if (!open(ctx, 0)) return false;
        try {
            Object ok = camera.getClass().getMethod("addPreviewSurface", Surface.class, int.class)
                    .invoke(camera, s, slot);
            start(ctx);
            return Boolean.TRUE.equals(ok) || "true".equals(String.valueOf(ok));
        } catch (Throwable t) {
            error = "addPreviewSurface: " + t;
            return false;
        }
    }

    public static synchronized void start(Context ctx) {
        if (camera == null) return;
        try {
            camera.getClass().getMethod("startPreview").invoke(camera);
        } catch (Throwable t) {
            error = "startPreview: " + t;
        }
    }

    public static synchronized void close() {
        if (camera == null) return;
        try {
            camera.getClass().getMethod("stopPreview").invoke(camera);
        } catch (Throwable ignored) {
        }
        try {
            camera.getClass().getMethod("close").invoke(camera);
        } catch (Throwable ignored) {
        }
        camera = null;
        textureAttached = false;
    }
}
