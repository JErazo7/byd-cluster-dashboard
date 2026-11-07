package com.sharklab.shell;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * ContainerListen — registra um callback no AutoContainer e imprime tudo o que o serviço
 * manda. É assim que se descobre qual é o transporte da projeção para o clúster: o serviço
 * avisa o app quando quer a superfície.
 */
public class ContainerListen {
    public static void main(String[] args) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method get = sm.getDeclaredMethod("getService", String.class);
        get.setAccessible(true);
        Object binder = get.invoke(null, "AutoContainer");
        Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
        Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);

        Class<?> cbCls = Class.forName("android.os.IContainerCallback");
        Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] a) {
                        StringBuilder sb = new StringBuilder("CB " + m.getName() + "(");
                        if (a != null) {
                            for (int i = 0; i < a.length; i++) {
                                if (i > 0) sb.append(", ");
                                sb.append(a[i]);
                            }
                        }
                        System.out.println(sb.append(") -> ").append(m.getReturnType().getSimpleName()));
                        if (m.getReturnType() == int.class) return 0;
                        if (m.getReturnType() == boolean.class) return false;
                        return null;
                    }
                });

        Object r = svc.getClass().getMethod("registerCallback", cbCls).invoke(svc, cb);
        System.out.println("registerCallback = " + r);
        Thread.sleep(12000);
        System.out.println("fim");
    }
}
