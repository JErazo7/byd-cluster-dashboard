package com.sharklab;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.hardware.display.DisplayManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * CarProbe — descobre, no próprio carro, o que dá para ler.
 *
 * Sem o veículo na mão isso não pode ser testado: então em vez de adivinhar, a app pergunta.
 * Tudo roda dentro de try/catch e vira JSON; nada aqui quebra a app se faltar.
 *
 * O que ele tenta:
 *  - versão do Android, modelo e o que a BYD declarou no build;
 *  - classes do SDK da BYD e do framework presentes (reflexão, sem linkar);
 *  - telas/displays (o painel do motorista costuma ser um display secundário);
 *  - chaves de Settings.System/Secure/Global que existem de fato.
 */
public class CarProbe {

    private static final String[] BYD_CLASSES = {
            "com.byd.automotive.car.BydCar",
            "com.byd.automotive.car.BydVehicle",
            "com.byd.automotive.car.BydManager",
            "com.byd.automotive.BydAutoManager",
            "android.hardware.bydauto.BYDAutoManager",
            "android.hardware.bydauto.BYDAutoDeviceManager",
            "com.byd.car.BydCar",
            "byd.android.hardware.BYDAutoManager"
    };

    private static final String[] SETTINGS_KEYS = {
            "byd_vehicle_speed", "byd_speed", "vehicle_speed", "car_speed",
            "byd_gear", "byd_soc", "byd_battery", "byd_range",
            "byd_power", "byd_rpm", "byd_drive_mode", "byd_cluster"
    };

    private CarProbe() {
    }

    public static String run(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            JSONObject b = new JSONObject();
            b.put("manufacturer", Build.MANUFACTURER);
            b.put("model", Build.MODEL);
            b.put("device", Build.DEVICE);
            b.put("brand", Build.BRAND);
            b.put("sdk", Build.VERSION.SDK_INT);
            b.put("release", Build.VERSION.RELEASE);
            b.put("hardware", Build.HARDWARE);
            b.put("product", Build.PRODUCT);
            out.put("build", b);
        } catch (Throwable t) {
            put(out, "buildError", t);
        }

        try {
            JSONArray found = new JSONArray();
            for (String name : BYD_CLASSES) {
                try {
                    Class.forName(name);
                    found.put(name);
                } catch (Throwable ignored) {
                }
            }
            out.put("bydClasses", found);
        } catch (Throwable t) {
            put(out, "bydClassesError", t);
        }

        try {
            JSONArray displays = new JSONArray();
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null && Build.VERSION.SDK_INT >= 17) {
                Display[] all = dm.getDisplays();
                for (Display d : all) {
                    DisplayMetrics m = new DisplayMetrics();
                    d.getMetrics(m);
                    JSONObject o = new JSONObject();
                    o.put("id", d.getDisplayId());
                    o.put("name", String.valueOf(d.getName()));
                    o.put("w", m.widthPixels);
                    o.put("h", m.heightPixels);
                    o.put("density", m.densityDpi);
                    o.put("flags", d.getFlags());
                    displays.put(o);
                }
            }
            out.put("displays", displays);
        } catch (Throwable t) {
            put(out, "displaysError", t);
        }

        try {
            JSONObject keys = new JSONObject();
            for (String k : SETTINGS_KEYS) {
                String sys = null, sec = null, glob = null;
                try { sys = Settings.System.getString(ctx.getContentResolver(), k); } catch (Throwable ignored) {}
                try { sec = Settings.Secure.getString(ctx.getContentResolver(), k); } catch (Throwable ignored) {}
                try { glob = Settings.Global.getString(ctx.getContentResolver(), k); } catch (Throwable ignored) {}
                if (sys != null || sec != null || glob != null) {
                    JSONObject v = new JSONObject();
                    if (sys != null) v.put("system", sys);
                    if (sec != null) v.put("secure", sec);
                    if (glob != null) v.put("global", glob);
                    keys.put(k, v);
                }
            }
            out.put("settings", keys);
        } catch (Throwable t) {
            put(out, "settingsError", t);
        }
        return out.toString();
    }

    private static void put(JSONObject o, String key, Throwable t) {
        try {
            o.put(key, t.getClass().getSimpleName() + ": " + t.getMessage());
        } catch (Throwable ignored) {
        }
    }
}
