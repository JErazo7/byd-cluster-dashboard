package com.sharklab.shell;

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Daemon — lê as APIs do carro e escreve o estado num arquivo.
 *
 * Roda com "app_process" como usuário shell, que não sofre o bloqueio de API oculta que
 * derruba a reflexão dentro do app (log: "blacklist, reflection, denied"). É o mesmo
 * mecanismo que o app de referência usa com os helpers em /data/local/tmp.
 *
 * Uso (uma vez, por adb):
 *   adb shell CLASSPATH=/sdcard/SharkLab/helper.dex app_process /system/bin com.sharklab.shell.Daemon
 *
 * Escreve /sdcard/SharkLab/state.json a cada 200 ms:
 *   {"speed":0,"gear":3,"pw":12.5,"soc":69,"range":395,"ped":0,"brk":0,"temp":24,"odo":12843,
 *    "v12":13.8,"turn":0,"bsd":0,"lks":0,"radar":[..],"t":<ms>}
 */
public class Daemon {

    private static final String OUT_DIR = "/sdcard/SharkLab";
    private static final String OUT_FILE = OUT_DIR + "/state.json";
    private static final String P = "android.hardware.bydauto.";

    private static final Map<String, Object> devices = new HashMap<String, Object>();
    private static String lastErr = "";

    private static final String[][] GETTERS = {
            {"speed.BYDAutoSpeedDevice", "getCurrentSpeed"},
            {"gearbox.BYDAutoGearboxDevice", "getCurrentGear"},
            {"engine.BYDAutoEngineDevice", "getEnginePower"},
            {"statistic.BYDAutoStatisticDevice", "getElecPercentageValue"},
            {"gearbox.BYDAutoGearboxDevice", "getElecPercentageValue"},
            {"setting.BYDAutoSettingDevice", "getElecDrivingRangeValue"},
            {"statistic.BYDAutoStatisticDevice", "getElecDrivingRangeValue"},
            {"speed.BYDAutoSpeedDevice", "getAccelerateDeepness"},
            {"speed.BYDAutoSpeedDevice", "getBrakeDeepness"},
            {"setting.BYDAutoSettingDevice", "getOutCarTemperature"},
            {"setting.BYDAutoSettingDevice", "getTotalMileageValue"},
            {"ota.BYDAutoOtaDevice", "getBatteryPowerVoltage"},
            {"light.BYDAutoLightDevice", "getTurnLightFlashState"},
            {"adas.BYDAutoADASDevice", "getBSDState"},
            {"adas.BYDAutoADASDevice", "getLKSMode"}
    };

    private static Object device(String shortName) {
        if (devices.containsKey(shortName)) return devices.get(shortName);
        Object d = null;
        try {
            Class<?> c = Class.forName(P + shortName);
            try {
                Method m = c.getDeclaredMethod("getInstance", android.content.Context.class);
                m.setAccessible(true);
                d = m.invoke(null, appContext());
            } catch (Throwable noCtx) {
                Method m = c.getDeclaredMethod("getInstance");
                m.setAccessible(true);
                d = m.invoke(null);
            }
        } catch (Throwable t) {
            lastErr = shortName + ": " + t;
        }
        devices.put(shortName, d);
        return d;
    }

    /** Contexto via reflexão no ActivityThread (o daemon não é um app) */
    private static Object ctx;

    private static Object appContext() {
        if (ctx != null) return ctx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            Method get = at.getDeclaredMethod("getSystemContext");
            get.setAccessible(true);
            ctx = get.invoke(thread);
        } catch (Throwable t) {
            ctx = null;
        }
        return ctx;
    }

    private static Double num(String dev, String method) {
        Object d = device(dev);
        if (d == null) return null;
        try {
            Method m = d.getClass().getMethod(method);
            m.setAccessible(true);
            Object v = m.invoke(d);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof Boolean) return ((Boolean) v) ? 1.0 : 0.0;
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c != c.getCause()) c = c.getCause();
            lastErr = dev + "." + method + ": " + c;
        }
        return null;
    }

    private static int[] ints(String dev, String method) {
        Object d = device(dev);
        if (d == null) return null;
        try {
            Method m = d.getClass().getMethod(method);
            m.setAccessible(true);
            Object v = m.invoke(d);
            if (v instanceof int[]) return (int[]) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String n(Double v) {
        return v == null ? "null" : String.valueOf(Math.round(v * 100) / 100.0);
    }

    public static void main(String[] args) throws Exception {
        try {
            new java.io.File(OUT_DIR).mkdirs();
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder(512);
        while (true) {
            long t0 = System.currentTimeMillis();
            Double speed = num(GETTERS[0][0], GETTERS[0][1]);
            Double gear = num(GETTERS[1][0], GETTERS[1][1]);
            Double pw = num(GETTERS[2][0], GETTERS[2][1]);
            Double soc = num(GETTERS[3][0], GETTERS[3][1]);
            if (soc == null) soc = num(GETTERS[4][0], GETTERS[4][1]);
            Double range = num(GETTERS[5][0], GETTERS[5][1]);
            if (range == null) range = num(GETTERS[6][0], GETTERS[6][1]);
            Double ped = num(GETTERS[7][0], GETTERS[7][1]);
            Double brk = num(GETTERS[8][0], GETTERS[8][1]);
            Double temp = num(GETTERS[9][0], GETTERS[9][1]);
            Double odo = num(GETTERS[10][0], GETTERS[10][1]);
            Double v12 = num(GETTERS[11][0], GETTERS[11][1]);
            Double turn = num(GETTERS[12][0], GETTERS[12][1]);
            Double bsd = num(GETTERS[13][0], GETTERS[13][1]);
            Double lks = num(GETTERS[14][0], GETTERS[14][1]);
            int[] radar = ints("radar.BYDAutoRadarDevice", "getAllRadarDistance");

            sb.setLength(0);
            sb.append("{\"speed\":").append(n(speed))
              .append(",\"gear\":").append(n(gear))
              .append(",\"pw\":").append(n(pw))
              .append(",\"soc\":").append(n(soc))
              .append(",\"range\":").append(n(range))
              .append(",\"ped\":").append(n(ped))
              .append(",\"brk\":").append(n(brk))
              .append(",\"temp\":").append(n(temp))
              .append(",\"odo\":").append(n(odo))
              .append(",\"v12\":").append(n(v12))
              .append(",\"turn\":").append(n(turn))
              .append(",\"bsd\":").append(n(bsd))
              .append(",\"lks\":").append(n(lks))
              .append(",\"radar\":");
            if (radar == null) {
                sb.append("null");
            } else {
                sb.append('[');
                for (int i = 0; i < radar.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(radar[i]);
                }
                sb.append(']');
            }
            sb.append(",\"err\":\"").append(lastErr.replace("\"", "'").replace("\\", "/")).append('"');
            sb.append(",\"t\":").append(System.currentTimeMillis()).append('}');

            FileOutputStream out = null;
            try {
                out = new FileOutputStream(OUT_FILE);
                out.write(sb.toString().getBytes("UTF-8"));
            } catch (Throwable ignored) {
            } finally {
                if (out != null) try { out.close(); } catch (Throwable ignored) {}
            }
            try {
                java.io.File f = new java.io.File(OUT_FILE);
                f.setReadable(true, false);
            } catch (Throwable ignored) {
            }

            long dt = System.currentTimeMillis() - t0;
            Thread.sleep(Math.max(120, 200 - dt));
        }
    }
}
