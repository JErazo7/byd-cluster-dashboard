package com.sharklab;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * BydBridge — leitura do carro pelas APIs do próprio DiLink (android.hardware.bydauto.*).
 *
 * Implementação própria, por reflexão: as classes existem no sistema da central, não no APK,
 * então nada é linkado em tempo de compilação. Cada valor tenta uma lista de candidatos
 * (classe + método) e devolve o primeiro que responder — modelos diferentes (Seal, Shark, Song)
 * expõem o mesmo dado em classes diferentes, e sem a central certa todo acesso é tentativa.
 *
 * Nada aqui copia código de terceiros: são chamadas às APIs do veículo, como qualquer app da
 * central faria; o padrão de acesso (getInstance(Context) → getters numéricos) é o mesmo da
 * plataforma e foi verificado no comportamento observável do sistema.
 */
public final class BydBridge {

    private static final String P = "android.hardware.bydauto.";

    /* valores numéricos: candidatos na ordem em que devem ser tentados */
    private static final String[][] SPEED = {
            {P + "speed.BYDAutoSpeedDevice", "getCurrentSpeed"},
            {P + "bodywork.BYDAutoBodyworkDevice", "getCurrentSpeed"},
            {P + "ac.BYDAutoAcDevice", "getCurrentSpeed"}
    };
    private static final String[][] SPEED_GW = {
            {P + "speed.BYDAutoSpeedDevice", "getSpeedFromGateway"}
    };
    private static final String[][] GEAR = {
            {P + "gearbox.BYDAutoGearboxDevice", "getCurrentGear"},
            {P + "speed.BYDAutoSpeedDevice", "getCurrentGear"}
    };
    private static final String[][] POWER = {
            {P + "engine.BYDAutoEngineDevice", "getEnginePower"}
    };
    private static final String[][] SOC = {
            {P + "statistic.BYDAutoStatisticDevice", "getElecPercentageValue"},
            {P + "gearbox.BYDAutoGearboxDevice", "getElecPercentageValue"}
    };
    private static final String[][] RANGE = {
            {P + "setting.BYDAutoSettingDevice", "getElecDrivingRangeValue"},
            {P + "statistic.BYDAutoStatisticDevice", "getElecDrivingRangeValue"}
    };
    private static final String[][] PEDAL = {
            {P + "speed.BYDAutoSpeedDevice", "getAccelerateDeepness"}
    };
    private static final String[][] PEDAL_FUEL = {
            {P + "speed.BYDAutoSpeedDevice", "getFuelAccelerateDeepness"}
    };
    private static final String[][] BRAKE = {
            {P + "speed.BYDAutoSpeedDevice", "getBrakeDeepness"}
    };
    private static final String[][] OUT_TEMP = {
            {P + "setting.BYDAutoSettingDevice", "getOutCarTemperature"},
            {P + "statistic.BYDAutoStatisticDevice", "getOutCarTemperature"},
            {P + "speed.BYDAutoSpeedDevice", "getOutCarTemperature"}
    };
    private static final String[][] ODO_TOTAL = {
            {P + "setting.BYDAutoSettingDevice", "getTotalMileageValue"},
            {P + "instrument.BYDAutoInstrumentDevice", "getTotalMileageValue"}
    };
    private static final String[][] ODO_EV = {
            {P + "statistic.BYDAutoStatisticDevice", "getEVMileageValue"}
    };
    private static final String[][] ODO_HEV = {
            {P + "statistic.BYDAutoStatisticDevice", "getHEVMileageValue"}
    };
    private static final String[][] V12 = {
            {P + "ota.BYDAutoOtaDevice", "getBatteryPowerVoltage"}
    };
    private static final String[][] TURN = {
            {P + "light.BYDAutoLightDevice", "getTurnLightFlashState"}
    };
    private static final String[][] PWR_UNIT = {
            {P + "instrument.BYDAutoInstrumentDevice", "getPowerUnit"}
    };
    private static final String[][] SPD_UNIT = {
            {P + "instrument.BYDAutoInstrumentDevice", "getSpeedUnit"}
    };

    /* combustível, pneus e estado de energia — lidos da API do carro (ver shark_gate/BYD_VEHICLE_API.md) */
    private static final String[][] FUEL = {
            {P + "statistic.BYDAutoStatisticDevice", "getFuelPercentageValue"}
    };
    private static final String[][] FUEL_KM = {
            {P + "statistic.BYDAutoStatisticDevice", "getFuelDrivingRangeValue"}
    };
    private static final String[][] ENGINE_SPEED = {
            {P + "engine.BYDAutoEngineDevice", "getEngineSpeed"}
    };
    private static final String[][] ENERGY_MODE = {
            {P + "energy.BYDAutoEnergyDevice", "getEnergyMode"}
    };
    private static final String[][] ROAD_SURFACE = {
            {P + "energy.BYDAutoEnergyDevice", "getRoadSurfacKind"}
    };
    private static final String[][] DRIVE_CONFIG = {
            {P + "setting.BYDAutoSettingDevice", "getDriveConfig"}
    };
    /* outros candidatos a carregar o modo de condução (medidos um por um no carro) */
    private static final String[][] SURF_MODE = {
            {P + "energy.BYDAutoEnergyDevice", "getRoadSurfaceMode"}
    };
    private static final String[][] DC_MODE = {
            {P + "energy.BYDAutoEnergyDevice", "getDCWorkMode"}
    };
    /* testigo do modo: 135298 = normal, 8353 = eco, 10306 = sport (medido no carro) */
    private static final String[][] ECO_STATE = {
            {P + "instrument.BYDAutoInstrumentDevice", "getECOIndicatorState"}
    };
    private static final String[][] OP_MODE = {
            {P + "energy.BYDAutoEnergyDevice", "getOperationMode"}
    };
    private static final String[][] MENU_STATE = {
            {P + "instrument.BYDAutoInstrumentDevice", "get2in1MenuState"}
    };
    private static final String[][] AUTO_MODE = {
            {P + "gearbox.BYDAutoGearboxDevice", "getGearboxAutoModeType"}
    };
    private static final String[][] ENERGY_STATE = {
            {P + "energy.BYDAutoEnergyDevice", "getEnergyState"}
    };
    private static final String[][] BCM_STATE = {
            {P + "energy.BYDAutoEnergyDevice", "getBCMState"}
    };
    private static final String[][] DAY_MODE = {
            {P + "bodywork.BYDAutoBodyworkDevice", "getPowerDayMode"}
    };

    /* as quatro luzes de modo do painel: a que estiver acesa é o modo ativo */
    private static final String[][] M_NORMAL = {
            {P + "instrument.BYDAutoInstrumentDevice", "get2in1FaultNormalIndicator"}
    };
    private static final String[][] M_MUD = {
            {P + "instrument.BYDAutoInstrumentDevice", "get2in1FaultMuddyIndicator"}
    };
    private static final String[][] M_SAND = {
            {P + "instrument.BYDAutoInstrumentDevice", "get2in1FaultSandIndicator"}
    };
    private static final String[][] M_GRASS = {
            {P + "instrument.BYDAutoInstrumentDevice", "get2in1FaultGrassIndicator"}
    };

    /* ADAS: só estados e distâncias do radar da BYD — nada de câmera nem modelo */
    private static final String[][] BSD = {{P + "adas.BYDAutoADASDevice", "getBSDState"}};
    private static final String[][] LKS = {{P + "adas.BYDAutoADASDevice", "getLKSMode"}};
    private static final String[][] TJA = {{P + "adas.BYDAutoADASDevice", "getTJAState"}};
    private static final String[][] ACC = {{P + "adas.BYDAutoADASDevice", "get2in1AccTimeDistance"}};
    private static final String[][] LINE = {{P + "adas.BYDAutoADASDevice", "getLineValue"}};
    private static final String[][] RADAR = {{P + "radar.BYDAutoRadarDevice", "getAllRadarDistance"}};

    private static final double NONE = -999;

    private final Context ctx;
    private final Map<String, Object> devices = new HashMap<String, Object>();
    private final Map<String, Boolean> probed = new HashMap<String, Boolean>();
    /* os dispositivos da BYD devolvem leitura velha quando o objeto é reaproveitado: em SPORT o
       mesmo instance seguia dizendo ECO. Recriar de vez em quando resolve (medido no carro). */
    private final Map<String, Long> born = new HashMap<String, Long>();
    private static final long TTL = 1500;

    public BydBridge(Context ctx) {
        this.ctx = new BydPermissionContext(ctx.getApplicationContext());
    }

    /* ---------- reflexão ---------- */

    /** getInstance(Context) e, se não existir, getInstance() — cacheado por classe */
    private Object device(String cls) {
        Long at = born.get(cls);
        if (devices.containsKey(cls) && at != null
                && System.currentTimeMillis() - at < TTL) {
            return devices.get(cls);
        }
        Object d = null;
        try {
            Class<?> c = Class.forName(cls);
            try {
                Method m = c.getDeclaredMethod("getInstance", Context.class);
                m.setAccessible(true);
                d = m.invoke(null, ctx);
            } catch (NoSuchMethodException e) {
                Method m = c.getDeclaredMethod("getInstance");
                m.setAccessible(true);
                d = m.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        devices.put(cls, d);
        born.put(cls, System.currentTimeMillis());
        return d;
    }

    private Double num(String cls, String method) {
        Object d = device(cls);
        if (d == null) return null;
        try {
            Method m = d.getClass().getMethod(method);
            m.setAccessible(true);
            Object v = m.invoke(d);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof Boolean) return ((Boolean) v) ? 1.0 : 0.0;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** getter com um argumento int (getTyrePressureValue(1..4), por exemplo) */
    private double numInt(String cls, String method, int arg) {
        Object d = device(cls);
        if (d == null) return NONE;
        try {
            Method m = d.getClass().getMethod(method, int.class);
            m.setAccessible(true);
            Object v = m.invoke(d, arg);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {
        }
        return NONE;
    }

    private double first(String[][] candidates) {
        for (String[] c : candidates) {
            Double v = num(c[0], c[1]);
            if (v != null && v > NONE) return v;
        }
        return NONE;
    }

    public boolean available() {
        return device(P + "speed.BYDAutoSpeedDevice") != null
                || device(P + "instrument.BYDAutoInstrumentDevice") != null
                || device(P + "energy.BYDAutoEnergyDevice") != null;
    }

    /** o que respondeu nesta central (para o diagnóstico) */
    public String report() {
        StringBuilder sb = new StringBuilder("{");
        String[][] all = concat(SPEED, GEAR, POWER, SOC, RANGE, PEDAL, BRAKE, OUT_TEMP, ODO_TOTAL, ODO_EV,
                ODO_HEV, V12, TURN, FUEL, FUEL_KM, ENGINE_SPEED, BSD, LKS, TJA, ACC, LINE, RADAR);
        for (String[] c : all) {
            String key = c[0].substring(c[0].lastIndexOf('.') + 1) + "." + c[1];
            if (probed.containsKey(key)) continue;
            Double v = num(c[0], c[1]);
            probed.put(key, v != null);
            sb.append('"').append(key).append("\":").append(v == null ? "null" : String.valueOf(v)).append(',');
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append('}').toString();
    }

    private static String[][] concat(String[][]... parts) {
        int n = 0;
        for (String[][] p : parts) n += p.length;
        String[][] out = new String[n][];
        int i = 0;
        for (String[][] p : parts) for (String[] c : p) out[i++] = c;
        return out;
    }

    /** int[] de um getter (distâncias do radar); null quando não existe */
    private int[] ints(String cls, String method) {
        Object d = device(cls);
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

    /** ADAS disponível nesta central, em JSON (o que não existir sai null) */
    public String adasJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"bsd\":").append(v(BSD));
        sb.append(",\"lks\":").append(v(LKS));
        sb.append(",\"tja\":").append(v(TJA));
        sb.append(",\"acc\":").append(v(ACC));
        sb.append(",\"line\":").append(v(LINE));
        int[] radar = ints(RADAR[0][0], RADAR[0][1]);
        sb.append(",\"radar\":");
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
        return sb.append('}').toString();
    }

    private String v(String[][] c) {
        double d = first(c);
        return d <= NONE ? "null" : String.valueOf(Math.round(d));
    }

    /* ---------- leitura ---------- */

    /**
     * Pedal em %: o Shark reporta o elétrico (getAccelerateDeepness) e o térmico
     * (getFuelAccelerateDeepness) em escalas diferentes; em P um deles fica em 0.
     * Vale o maior dos dois, normalizado quando vem acima de 100.
     */
    public double pedalPercent() {
        double a = first(PEDAL);
        double b = first(PEDAL_FUEL);
        if (a <= NONE && b <= NONE) return 0;
        double v = Math.max(a <= NONE ? 0 : a, b <= NONE ? 0 : b);
        if (v > 100) v = v * 100.0 / 255.0;      /* escala 0..255 em algumas versões */
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    /** odômetro total: o getter do total e, se ele não responder, EV + HEV */
    private double odoTotal() {
        double total = first(ODO_TOTAL);
        if (total > NONE) return total;
        double born = first(ODO_EV), hev = first(ODO_HEV);
        if (born > NONE && hev > NONE) return born + hev;
        return born > NONE ? born : (hev > NONE ? hev : NONE);
    }

    /** diagnóstico do TPMS: assinaturas do device e tentativas nas duas bases de índice */
    public String tyreReport() {
        StringBuilder sb = new StringBuilder("{\"methods\":[");
        Object d = device(P + "tyre.BYDAutoTyreDevice");
        if (d != null) {
            Method[] ms = d.getClass().getMethods();
            boolean firstOne = true;
            for (Method m : ms) {
                if (!m.getName().startsWith("get")) continue;
                if (!firstOne) sb.append(',');
                firstOne = false;
                sb.append('"').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ps[i].getSimpleName());
                }
                sb.append("):").append(m.getReturnType().getSimpleName()).append('"');
            }
        }
        sb.append("],\"pressure\":{");
        for (int i = 0; i <= 4; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(i).append("\":").append(String.valueOf(numInt(P + "tyre.BYDAutoTyreDevice", "getTyrePressureValue", i)));
        }
        sb.append("},\"state\":{");
        for (int i = 0; i <= 4; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(i).append("\":").append(String.valueOf(numInt(P + "tyre.BYDAutoTyreDevice", "getTyrePressureState", i)));
        }
        sb.append("},\"signal\":").append(String.valueOf(num(P + "tyre.BYDAutoTyreDevice", "getTyreSignalState")));
        sb.append(",\"system\":").append(String.valueOf(num(P + "tyre.BYDAutoTyreDevice", "getTyreSystemState")));
        return sb.append('}').toString();
    }

    /** pressão de um pneu; 1 = dianteiro esquerdo, 2 = dianteiro direito, 3/4 = traseiros */
    public double tyre(int n) {
        return numInt(P + "tyre.BYDAutoTyreDevice", "getTyrePressureValue", n);
    }

    /** snapshot cru; -999 = sem leitura */
    public double[] snapshot() {
        double speed = first(SPEED);
        if (speed <= NONE) speed = first(SPEED_GW);
        return new double[]{
                speed,                                  /* 0 km/h */
                first(GEAR),                            /* 1 marcha */
                first(POWER),                           /* 2 kW */
                first(SOC),                             /* 3 % */
                first(RANGE),                           /* 4 km */
                first(PEDAL),                           /* 5 % pedal */
                first(BRAKE),                           /* 6 % freio */
                first(OUT_TEMP),                        /* 7 °C */
                odoTotal(),                             /* 8 km */
                first(V12),                             /* 9 V */
                first(TURN),                            /* 10 pisca */
                first(PWR_UNIT),                        /* 11 1 kW / 2 hp */
                first(SPD_UNIT),                        /* 12 1 km/h / 2 mph */
                first(FUEL),                            /* 13 % tanque */
                first(FUEL_KM),                         /* 14 km a combustível */
                first(ENGINE_SPEED),                    /* 15 rpm do motor */
                first(ENERGY_MODE),                     /* 16 modo de energia (cru) */
                first(ROAD_SURFACE),                    /* 17 tipo de piso (cru) */
                first(DRIVE_CONFIG),                    /* 18 config de condução (cru) */
                first(M_NORMAL),                        /* 19 luz do modo normal */
                first(M_MUD),                           /* 20 luz do modo lama */
                first(M_SAND),                          /* 21 luz do modo areia */
                first(M_GRASS),                         /* 22 luz do modo grama/neve */
                first(SURF_MODE),                       /* 23 energy.getRoadSurfaceMode */
                first(DC_MODE),                         /* 24 energy.getDCWorkMode */
                first(OP_MODE),                         /* 25 energy.getOperationMode */
                first(MENU_STATE),                      /* 26 instrument.get2in1MenuState */
                first(AUTO_MODE),                       /* 27 gearbox.getGearboxAutoModeType */
                first(ENERGY_STATE),                    /* 28 energy.getEnergyState */
                first(BCM_STATE),                       /* 29 energy.getBCMState */
                first(DAY_MODE),                        /* 30 bodywork.getPowerDayMode */
                first(ECO_STATE)                        /* 31 instrument.getECOIndicatorState */
        };
    }
}
