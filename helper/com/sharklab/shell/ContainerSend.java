package com.sharklab.shell;

import java.lang.reflect.Method;

/** Envia um comando ao AutoContainer (uso: args = tipo json). Só imprime o resultado. */
public class ContainerSend {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("uso: <tipo> <json>"); return; }
        int type = Integer.parseInt(args[0]);
        String json = args[1];
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method get = sm.getDeclaredMethod("getService", String.class);
        get.setAccessible(true);
        Object binder = get.invoke(null, "AutoContainer");
        Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
        Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Object r = svc.getClass().getMethod("sendJson", int.class, String.class).invoke(svc, type, json);
        System.out.println("sendJson(" + type + ") = " + r);
    }
}
