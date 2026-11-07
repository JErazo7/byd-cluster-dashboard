package com.sharklab;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * Contexto que anula os checks BYDAUTO_*.
 *
 * O framework da BYD avalia as permissões BYDAUTO_<DEVICE>_GET/SET no CLIENTE, contra o Context
 * entregue a getInstance() — não no serviço. Envolvendo o Context e devolvendo GRANTED só para
 * essas permissões, os getters passam a funcionar sem assinatura de plataforma.
 * (Modelo documentado em BYD_VEHICLE_API.md, verificado no carro.)
 */
public class BydPermissionContext extends ContextWrapper {

    private static final String PREFIX = "android.permission.BYDAUTO_";

    public BydPermissionContext(Context base) {
        super(base);
    }

    @Override
    public void enforceCallingOrSelfPermission(String p, String m) {
        if (p != null && p.startsWith(PREFIX)) return;
        super.enforceCallingOrSelfPermission(p, m);
    }

    @Override
    public int checkCallingOrSelfPermission(String p) {
        if (p != null && p.startsWith(PREFIX)) return PackageManager.PERMISSION_GRANTED;
        return super.checkCallingOrSelfPermission(p);
    }

    @Override
    public void enforcePermission(String p, int pid, int uid, String m) {
        if (p != null && p.startsWith(PREFIX)) return;
        super.enforcePermission(p, pid, uid, m);
    }

    @Override
    public int checkPermission(String p, int pid, int uid) {
        if (p != null && p.startsWith(PREFIX)) return PackageManager.PERMISSION_GRANTED;
        return super.checkPermission(p, pid, uid);
    }
}
