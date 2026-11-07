package com.sharklab;

import java.lang.reflect.Method;

/**
 * HiddenApi — libera a reflexão sobre métodos fora do SDK neste build.
 *
 * As classes android.hardware.bydauto.* estão no framework.jar, mas os getters entram na lista
 * de bloqueio do Android 10 ("blacklist, reflection, denied"). O VMRuntime aceita uma lista de
 * isenções; com "L" tudo é liberado. É o mesmo caminho que os APKs de teste do documento usam na
 * prática. Vale só para este processo e não muda nada no sistema.
 */
public final class HiddenApi {

    private static boolean done;
    private static String result = "";

    private HiddenApi() {
    }

    public static void allowAll() {
        if (done) return;
        done = true;
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod",
                    String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", (Object) null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions",
                    new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            result = "ok";
        } catch (Throwable t) {
            result = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static String result() {
        return result;
    }
}
