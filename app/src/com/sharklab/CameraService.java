package com.sharklab;

import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.Method;

/**
 * CameraService — the car cameras (reverse, turn signal, panorama) through the service BYD
 * exposes.
 *
 * The path, verified against system behavior: ServiceManager → binder
 * "bydcameramanager" (or "bmmcameraserver") → Stub.asInterface → open/addPreviewSurface/
 * startPreview, with the frames arriving on a Surface.
 *
 * The signatures of those methods vary between units, so nothing is linked: we look for a
 * method by name and build the arguments (int, Surface, boolean, String) in the order it
 * asks for. When it does not exist, we return a readable error instead of breaking the panel.
 */
public final class CameraService {

    private static final String[] SERVICES = {"bydcameramanager", "bmmcameraserver", "bydcamera"};
    private static final String[] STUBS = {
            "android.hardware.IBYDCameraService$Stub",
            "android.hardware.bydcamera.IBYDCameraService$Stub",
            "android.hardware.IBYDCameraService"
    };

    private static Object svc;
    private static String error = "";
    private static String used = "";

    private CameraService() {
    }

    public static synchronized Object service() {
        if (svc != null) return svc;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            Object binder = null;
            for (String name : SERVICES) {
                try {
                    binder = get.invoke(null, name);
                } catch (Throwable ignored) {
                }
                if (binder != null) {
                    used = name;
                    break;
                }
            }
            if (binder == null) {
                error = "service not found (" + SERVICES[0] + ")";
                return null;
            }
            for (String stub : STUBS) {
                try {
                    Class<?> c = Class.forName(stub);
                    Method as = c.getDeclaredMethod("asInterface", IBinder.class);
                    as.setAccessible(true);
                    svc = as.invoke(null, binder);
                    if (svc != null) return svc;
                } catch (Throwable ignored) {
                }
            }
            error = "no asInterface for " + used;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return null;
    }

    public static String error() {
        return error;
    }

    public static String serviceName() {
        return used;
    }

    public static boolean available() {
        return service() != null;
    }

    /** calls by name, matching the number of arguments we have to offer */
    public static synchronized Object call(String name, Object... args) {
        Object s = service();
        if (s == null) return null;
        Method best = null;
        for (Method m : s.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                if (!fits(p[i], args[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                best = m;
                break;
            }
        }
        if (best == null) {
            error = "method " + name + "/" + args.length + " does not exist";
            return null;
        }
        try {
            best.setAccessible(true);
            return best.invoke(s, args);
        } catch (Throwable t) {
            error = name + ": " + t.getClass().getSimpleName();
            return null;
        }
    }

    private static boolean fits(Class<?> type, Object arg) {
        if (arg == null) return !type.isPrimitive();
        if (type.isInstance(arg)) return true;
        if (type == int.class && arg instanceof Integer) return true;
        if (type == long.class && arg instanceof Long) return true;
        if (type == boolean.class && arg instanceof Boolean) return true;
        return false;
    }

    /* ---------- preview sequence ---------- */

    public static String openPreview(Surface surface) {
        if (service() == null) return "no service: " + error;
        /* tries the known combinations; stops at the first path that accepts the surface */
        Object r;
        r = call("addPreviewSurface", surface);
        if (r == null) r = call("addPreviewSurface", 0, surface);
        if (r == null) r = call("addPreviewSurface", surface, 0);
        if (r == null) return "addPreviewSurface failed: " + error;

        call("open", 0);
        call("open", 0, 0);
        Object started = call("startPreview");
        if (started == null) call("startPreview", 0);
        return "ok";
    }

    public static void closePreview(Surface surface) {
        try {
            call("stopPreview");
            call("rmPreviewSurface", surface);
            call("rmPreviewSurface", 0, surface);
            call("close");
            call("close", 0);
        } catch (Throwable ignored) {
        }
    }

    /** list of methods of the running service (diagnostic in the car) */
    public static String dump() {
        Object s = service();
        if (s == null) return "{\"error\":\"" + error + "\"}";
        StringBuilder sb = new StringBuilder("{\"service\":\"" + used + "\",\"methods\":[");
        boolean first = true;
        for (Method m : s.getClass().getMethods()) {
            String n = m.getName();
            if (n.startsWith("asBinder")) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(n).append('(');
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(p[i].getSimpleName());
            }
            sb.append(")\"");
        }
        return sb.append("]}").toString();
    }
}
