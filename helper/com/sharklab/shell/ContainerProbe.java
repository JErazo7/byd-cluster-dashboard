package com.sharklab.shell;

import java.lang.reflect.Method;

/**
 * ContainerProbe — discovers the AutoContainer API (the channel that links the head unit
 * to the cluster panel).
 *
 * Runs through app_process (shell user), where reflection is not blocked. READ ONLY: it
 * lists the binder methods and calls the getters. No command that changes what the driver
 * sees.
 *
 *   adb shell CLASSPATH=/sdcard/SharkLab/container.dex app_process /system/bin \
 *       com.sharklab.shell.ContainerProbe
 */
public class ContainerProbe {

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            Object binder = get.invoke(null, "AutoContainer");
            sb.append("binder=").append(binder == null ? "null" : binder.getClass().getName()).append('\n');

            String[] stubs = {
                    "android.os.IAutoContainer$Stub",
                    "android.os.IAutoContainer",
                    "android.autocontainer.IAutoContainer$Stub"
            };
            for (String s : stubs) {
                try {
                    Class<?> c = Class.forName(s);
                    sb.append("class ok: ").append(s).append('\n');
                    for (Method m : c.getDeclaredMethods()) {
                        if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
                        sb.append("  ").append(m.getReturnType().getSimpleName()).append(' ')
                          .append(m.getName()).append('(');
                        Class<?>[] p = m.getParameterTypes();
                        for (int i = 0; i < p.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(p[i].getSimpleName());
                        }
                        sb.append(")\n");
                    }
                } catch (Throwable t) {
                    sb.append("no ").append(s).append(": ").append(t).append('\n');
                }
            }

            /* the projection structure: field names reveal what attach expects */
            for (String cn : new String[]{"android.os.ProjectionDisplayInfoParcel",
                                          "android.os.AutoContainerProjectionInfo",
                                          "android.os.IAutoContainer$ProjectionDisplayInfoParcel"}) {
                try {
                    Class<?> c = Class.forName(cn);
                    sb.append("class ").append(cn).append('\n');
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        sb.append("  field ").append(f.getType().getSimpleName()).append(' ').append(f.getName()).append('\n');
                    }
                    for (Method m : c.getDeclaredMethods()) {
                        sb.append("  method ").append(m.getReturnType().getSimpleName()).append(' ')
                          .append(m.getName()).append("()\n");
                    }
                } catch (Throwable t) {
                    sb.append("no ").append(cn).append('\n');
                }
            }

            /* diagnostic getter, if it exists */
            try {
                Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
                Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                sb.append("service=").append(svc.getClass().getName()).append('\n');
                /* getProjectionDisplayInfo(Parcel) fills the structure and returns status */
                try {
                    Class<?> pc = Class.forName("android.os.ProjectionDisplayInfoParcel");
                    Object info = pc.getDeclaredConstructor().newInstance();
                    Object r = svc.getClass().getMethod("getProjectionDisplayInfo", pc).invoke(svc, info);
                    sb.append("getProjectionDisplayInfo=").append(r).append('\n');
                    sb.append("  info=").append(info).append('\n');
                    for (java.lang.reflect.Field f : pc.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        sb.append("  ").append(f.getName()).append('=').append(f.get(info)).append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("getProjectionDisplayInfo failed: ").append(t).append('\n');
                }
            } catch (Throwable t) {
                sb.append("asInterface failed: ").append(t).append('\n');
            }
        } catch (Throwable t) {
            sb.append("failed: ").append(t).append('\n');
        }
        System.out.println(sb.toString());
    }
}
