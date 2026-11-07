package com.sharklab.shell;

import java.lang.reflect.Method;

/**
 * ContainerProbe — descobre a API do AutoContainer (o canal que liga a IVI ao clúster).
 *
 * Roda com app_process (usuário shell), onde a reflexão não é bloqueada. Só LÊ: lista os
 * métodos do binder e chama os getters. Nada de comando que mude o que o motorista vê.
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
                    sb.append("classe ok: ").append(s).append('\n');
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
                    sb.append("sem ").append(s).append(": ").append(t).append('\n');
                }
            }

            /* a estrutura da projeção: nomes dos campos revelam o que o attach espera */
            for (String cn : new String[]{"android.os.ProjectionDisplayInfoParcel",
                                          "android.os.AutoContainerProjectionInfo",
                                          "android.os.IAutoContainer$ProjectionDisplayInfoParcel"}) {
                try {
                    Class<?> c = Class.forName(cn);
                    sb.append("classe ").append(cn).append('\n');
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        sb.append("  campo ").append(f.getType().getSimpleName()).append(' ').append(f.getName()).append('\n');
                    }
                    for (Method m : c.getDeclaredMethods()) {
                        sb.append("  metodo ").append(m.getReturnType().getSimpleName()).append(' ')
                          .append(m.getName()).append("()\n");
                    }
                } catch (Throwable t) {
                    sb.append("sem ").append(cn).append('\n');
                }
            }

            /* getter de diagnóstico, se existir */
            try {
                Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
                Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                sb.append("servico=").append(svc.getClass().getName()).append('\n');
                /* getProjectionDisplayInfo(Parcel) preenche a estrutura e devolve status */
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
                    sb.append("getProjectionDisplayInfo falhou: ").append(t).append('\n');
                }
            } catch (Throwable t) {
                sb.append("asInterface falhou: ").append(t).append('\n');
            }
        } catch (Throwable t) {
            sb.append("falhou: ").append(t).append('\n');
        }
        System.out.println(sb.toString());
    }
}
