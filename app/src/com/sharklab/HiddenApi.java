package com.sharklab;

import java.lang.reflect.Method;

/**
 * HiddenApi — unblocks reflection on methods outside the SDK in this build.
 *
 * The android.hardware.bydauto.* classes are in framework.jar, but the getters land on
 * Android 10's blocklist ("blacklist, reflection, denied"). VMRuntime accepts a list of
 * exemptions; with "L" everything is released. It is the same path the document's test APKs
 * use in practice. It applies only to this process and changes nothing in the system.
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
