package com.sharklab;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EngineAudio — o som do motor em canal próprio.
 *
 * Sai de WebAudio (que sempre cai no stream de música) e usa AudioTrack com
 * USAGE_ASSISTANCE_SONIFICATION / CONTENT_TYPE_SONIFICATION: é o canal de sons do sistema,
 * então o volume do motor não sobe nem desce junto com a música, o GPS ou as chamadas.
 *
 * O timbre continua vindo do perfil (engine/profiles.js): o JS manda os harmônicos, cortes,
 * turbo e estouros em configure(); aqui só se sintetiza.
 */
public final class EngineAudio {

    private static final int SR = 48000;
    private static final int TABLE = 2048;

    private static AudioTrack track;
    private static Thread thread;
    private static volatile boolean running;
    private static volatile float volume = 1.0f;
    private static volatile boolean muted;

    private static volatile double rpm = 700, tps = 0, load = 0;
    private static volatile int cylinders = 8, idle = 700, limiter = 6800;
    private static volatile double cut0 = 110, cut1 = 900, turboAmt = 0, popsAmt = 0;
    private static volatile double[] harmonics = {1, 0.7, 0.5, 0.35, 0.2, 0.15};
    private static volatile float[] table = buildTable(harmonics);
    private static volatile double popAt = -1;

    private EngineAudio() {
    }

    private static float[] buildTable(double[] h) {
        float[] t = new float[TABLE];
        double peak = 0;
        for (int i = 0; i < TABLE; i++) {
            double x = 2 * Math.PI * i / TABLE;
            double s = 0;
            for (int k = 0; k < h.length; k++) s += h[k] * Math.sin((k + 1) * x);
            t[i] = (float) s;
            peak = Math.max(peak, Math.abs(s));
        }
        if (peak > 0) for (int i = 0; i < TABLE; i++) t[i] /= peak;
        return t;
    }

    /** perfil vindo do JS: harmônicos e parâmetros do motor */
    public static void configure(String json) {
        try {
            JSONObject o = new JSONObject(json);
            cylinders = o.optInt("cylinders", 8);
            idle = o.optInt("idle", 700);
            limiter = o.optInt("limiter", 6800);
            cut0 = o.optDouble("cut0", 110);
            cut1 = o.optDouble("cut1", 900);
            turboAmt = o.optDouble("turbo", 0);
            popsAmt = o.optDouble("pops", 0.4);
            if (o.has("harmonics")) {
                JSONArray a = o.getJSONArray("harmonics");
                double[] h = new double[a.length()];
                for (int i = 0; i < a.length(); i++) h[i] = a.getDouble(i);
                if (h.length > 0) {
                    harmonics = h;
                    table = buildTable(h);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void start() {
        if (track != null) return;
        try {
            /* mesmo canal e formato do EvSound (m.e): USAGE_MEDIA/CONTENT_TYPE_MUSIC,
               48 kHz, PCM float, estéreo e buffer de pelo menos 8 KiB. No canal de
               sistema (sonification) a central do Shark simplesmente não reproduz. */
            int buf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT);
            if (buf <= 0) buf = 8192;
            if (buf < 8192) buf = 8192;
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(buf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            running = true;
            android.util.Log.i("SharkLab", "EngineAudio: AudioTrack " + SR + "Hz state=" + track.getState()
                    + " playState=" + track.getPlayState());
            thread = new Thread(new Runnable() {
                public void run() {
                    renderLoop();
                }
            }, "engine-audio");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        } catch (Throwable t) {
            android.util.Log.w("SharkLab", "EngineAudio.start falhou: " + t);
            stop();
        }
    }

    public static void set(double r, double t, double l) {
        if (t < 0.4 && tps > 0.45 && popsAmt > 0) popAt = System.nanoTime();
        rpm = r;
        tps = t;
        load = l;
    }

    public static void setVolume(float v) {
        volume = v;
    }

    public static void setMuted(boolean m) {
        muted = m;
    }

    public static boolean running() {
        return running && track != null;
    }

    public static synchronized void stop() {
        running = false;
        try { if (track != null) track.stop(); } catch (Throwable ignored) {}
        try { if (track != null) track.release(); } catch (Throwable ignored) {}
        track = null;
        thread = null;
    }

    /* ---------- síntese ---------- */

    private static void renderLoop() {
        float[] buf = new float[1920];   /* 960 quadros estéreo */
        double phase = 0, phase2 = 0, turboPhase = 0;
        double lp = 0, noise = 0, popEnv = 0;
        int blocks = 0;
        long seed = 12345;

        while (running) {
            double fire = Math.max(20, rpm / 60.0 * (cylinders / 2.0));
            double t = Math.min(1, Math.max(0, (rpm - idle) / Math.max(1, limiter - idle)));
            /* piso de corte: em ralenti a explosão de um V8 é ~40 Hz e o alto-falante do carro
               não reproduz isso — sem o piso o som existe e não se ouve */
            double cut = Math.max(420, (cut0 + (cut1 - cut0) * Math.pow(t, 0.75)) * (0.55 + 0.45 * load));
            double a = 1 - Math.exp(-2 * Math.PI * Math.min(6000, Math.max(60, cut)) / SR);
            double inc = fire * TABLE / SR;
            double inc2 = inc * 1.006;
            double amp = 0.75 + 0.35 * t;
            double gain = (muted ? 0 : volume) * amp * (0.85 + 0.15 * load);

            for (int i = 0; i < buf.length / 2; i++) {
                phase += inc;
                if (phase >= TABLE) phase -= TABLE;
                phase2 += inc2;
                if (phase2 >= TABLE) phase2 -= TABLE;
                double s = table[(int) phase] * 0.65 + table[(int) phase2] * 0.35;

                /* admissão: ruído filtrado */
                seed = seed * 1664525 + 1013904223;
                noise = (seed >> 11) / (double) (1L << 52);
                s += noise * (0.02 + 0.16 * t) * (0.3 + 0.7 * load);

                /* turbo */
                if (turboAmt > 0) {
                    turboPhase += (1800 + 7200 * load) * 2 * Math.PI / SR;
                    if (turboPhase > 2 * Math.PI) turboPhase -= 2 * Math.PI;
                    s += Math.sin(turboPhase) * turboAmt * load * 0.05;
                }

                /* estouro ao tirar o pé */
                if (popAt > 0) {
                    if (System.nanoTime() - popAt < 90_000_000L) popEnv = 0.8;
                    else { popEnv = 0; popAt = -1; }
                }
                if (popEnv > 0.001) {
                    s += noise * popEnv * popsAmt;
                    popEnv *= 0.996;
                }

                /* passa-baixa de um polo (escape) */
                lp += a * (s - lp);
                double out = lp * gain;
                if (out > 1) out = 1;
                if (out < -1) out = -1;
                buf[i * 2] = (float) out;
                buf[i * 2 + 1] = (float) out;
            }
            /* diagnóstico: pico do buffer uma vez por segundo */
            if ((blocks++ % 43) == 0) {
                double peakF = 0;
                for (int i = 0; i < buf.length; i++) {
                    double v = Math.abs(buf[i]);
                    if (v > peakF) peakF = v;
                }
                long peak = Math.round(peakF * 32000);
                android.util.Log.i("SharkLab", "audio peak=" + peak + " rpm=" + (int) rpm
                        + " tps=" + (int) (tps * 100) + " load=" + (int) (load * 100)
                        + " vol=" + volume + " muted=" + muted);
            }
            try {
                track.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
            } catch (Throwable t2) {
                running = false;
            }
        }
    }
}
