package com.sharklab;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * Context that bypasses the BYDAUTO_* checks.
 *
 * The BYD framework evaluates the BYDAUTO_<DEVICE>_GET/SET permissions on the CLIENT side,
 * against the Context handed to getInstance() — not in the service. By wrapping the Context and
 * returning GRANTED only for those permissions, the getters start working without a platform
 * signature. (Model documented in BYD_VEHICLE_API.md, verified in the car.)
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
