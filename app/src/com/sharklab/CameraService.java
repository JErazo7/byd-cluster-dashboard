package com.sharklab;

import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.Method;

/**
 * CameraService — as câmeras do carro (ré, pisca, panorâmica) pelo serviço que a BYD expõe.
 *
 * O caminho, verificado no comportamento do sistema: ServiceManager → binder
 * "bydcameramanager" (ou "bmmcameraserver") → Stub.asInterface → open/addPreviewSurface/
 * startPreview, com os quadros chegando num Surface.
 *
 * As assinaturas desses métodos variam entre unidades, então nada é linkado: procuramos um
 * método pelo nome e montamos os argumentos (int, Surface, boolean, String) na ordem em que
 * ele pede. Quando não existe, devolve erro legível em vez de quebrar o painel.
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
                error = "serviço não encontrado (" + SERVICES[0] + ")";
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
            error = "sem asInterface para " + used;
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

    /** chama por nome casando a quantidade de argumentos que temos para oferecer */
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
            error = "método " + name + "/" + args.length + " não existe";
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

    /* ---------- sequência de preview ---------- */

    public static String openPreview(Surface surface) {
        if (service() == null) return "sem serviço: " + error;
        /* tenta as combinações conhecidas; para no primeiro caminho que aceitar a surface */
        Object r;
        r = call("addPreviewSurface", surface);
        if (r == null) r = call("addPreviewSurface", 0, surface);
        if (r == null) r = call("addPreviewSurface", surface, 0);
        if (r == null) return "addPreviewSurface falhou: " + error;

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

    /** lista de métodos do serviço em execução (diagnóstico no carro) */
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
