package com.sharklab;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * PanelCallback — registra o cliente de callback do AutoContainer por transação crua.
 *
 * Com um Proxy de Java o container mata o processo (`Killed`); com um Binder próprio enviado por
 * `transact(TRANSACTION_registerCallback, …)` o registro é aceito (medido: reply=0, o processo
 * sobrevive). É o caminho que o EvSound usa no helper dele.
 *
 * Guardar o Binder numa referência estática é parte do truque: se ele for coletado o container
 * registra "Callback app is died" e perde o cliente.
 */
public final class PanelCallback {

    private static final String TAG = "SharkLab";
    private static Binder client;
    private static boolean registered;

    private PanelCallback() {
    }

    /** mantém o cliente vivo e registra uma vez; devolve true se o container aceitou */
    public static synchronized boolean register() {
        if (registered) return true;
        try {
            IBinder svc = binder();
            if (svc == null) {
                AdbShell.trace(null, "callback: sem serviço AutoContainer");
                return false;
            }
            int code = transaction();
            if (code < 0) {
                AdbShell.trace(null, "callback: sem TRANSACTION_registerCallback");
                return false;
            }
            client = new Binder();
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                d.writeInterfaceToken("android.os.IAutoContainer");
                d.writeStrongBinder(client);
                svc.transact(code, d, r, 0);
                int reply = r.readInt();
                registered = true;
                AdbShell.trace(null, "callback: register reply=" + reply);
                return true;
            } finally {
                d.recycle();
                r.recycle();
            }
        } catch (Throwable t) {
            AdbShell.trace(null, "callback: falhou " + t);
            return false;
        }
    }

    private static IBinder binder() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method g = sm.getDeclaredMethod("getService", String.class);
        g.setAccessible(true);
        return (IBinder) g.invoke(null, "AutoContainer");
    }

    private static int transaction() {
        try {
            Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
            for (Field f : stub.getDeclaredFields()) {
                if (f.getName().startsWith("TRANSACTION_registerCallback")) {
                    f.setAccessible(true);
                    return f.getInt(null);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
