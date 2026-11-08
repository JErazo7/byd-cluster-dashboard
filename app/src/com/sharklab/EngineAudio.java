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
    private static volatile android.content.Context ctx;
    private static volatile double comp = 1.0;

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

    public static void init(android.content.Context c) {
        ctx = c == null ? null : c.getApplicationContext();
    }

    /**
     * Compensação de volume, igual à do EvSound (m.r.b): o motor sai em STREAM_MUSIC porque é
     * o único canal que a central reproduz, então, para a música poder subir e descer sem
     * mudar o motor, a ganho é normalizado pelo volume atual do stream.
     * O valor desta app (0..1.5) é o alvo em % do máximo; o desvio em passos vira dB.
     */
    private static double compensation() {
        double pct = Math.min(1.5, Math.max(0, volume));
        if (pct <= 0) return 0;
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            if (cur < 0 || max <= 0) return Math.min(1, pct);
            return Math.min(1.5, Math.pow(10, -(cur - (pct * max)) / 20.0));
        } catch (Throwable t) {
            return Math.min(1, pct);
        }
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

    /**
     * Modelo físico do escape, no mesmo espírito do EvSound: a onda não é uma soma de
     * harmônicos (soa a sintetizador), é a sequência de explosões de cada cilindro passando
     * por um ressonador acompanhado do giro. Cada bloco tem:
     *   - trem de pulsos por cilindro, com jitter (o ralenti "lope" de um V8);
     *   - pressão de escape assimétrica saturada (é isso que dá corpo e não "buzina");
     *   - filtro ressonante de estado variável preso à frequência de explosão;
     *   - admissão de ruído filtrado, que cresce com o pedal;
     *   - turbo em senoide aguda com a carga;
     *   - estouros no corte de pedal.
     */
    private static void renderLoop() {
        float[] buf = new float[1920];              /* 960 quadros estéreo */
        double crank = 0, pulse = 0, pulse2 = 0;
        double svfLow = 0, svfBand = 0, bufDC = 0, hp = 0;
        double intake = 0, intakeSlow = 0, turboPhase = 0, popEnv = 0;
        int blocks = 0;
        long seed = 97531;
        final double pulseDecay = Math.exp(-1.0 / (0.011 * SR));
        final double pulseDecay2 = Math.exp(-1.0 / (0.026 * SR));

        while (running) {
            double rev = rpm / 60.0;
            double fireCyl = rev * cylinders / 2.0;                 /* V8: 4 explosões/volta */
            double fire = Math.max(8.0, fireCyl);
            double t = Math.min(1, Math.max(0, (rpm - idle) / Math.max(1, limiter - idle)));
            double pedal = Math.min(1, Math.max(0, tps));
            double loadN = Math.min(1, Math.max(0, load));
            if ((blocks % 20) == 0) comp = compensation();
            double gain = (muted ? 0 : 1.0) * comp * (0.55 + 0.45 * loadN);

            /* ressonador preso à explosão: em ralenti fica grave, acelerando abre */
            /* ressonância no 3º harmônico da explosão: é a faixa que o alto-falante do carro
               reproduz (a fundamental de 80 Hz fica no subgrave e não se ouve) */
            double fc = Math.min(5200, Math.max(90, fire * (3.2 - 1.2 * t)));
            double f = 2 * Math.sin(Math.PI * fc / SR);
            if (f > 1.4) f = 1.4;
            double damp = 0.55;
            double cutLP = 1 - Math.exp(-2 * Math.PI * Math.min(9000, Math.max(200, fc * 2.5)) / SR);
            double intakeLP = 1 - Math.exp(-2 * Math.PI * 2600 / SR);
            double intakeHP = 1 - Math.exp(-2 * Math.PI * 260 / SR);
            double aHP = 1 - Math.exp(-2 * Math.PI * 110 / SR);   /* tira o subgrave inaudível */

            for (int i = 0; i < buf.length / 2; i++) {
                /* --- explosões: uma a cada 1/fire segundos, com jitter de combustão --- */
                crank += fire / SR;
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                double rnd = ((seed >>> 11) & 0xFFFFF) / (double) 0x100000;   /* 0..1 */
                if (crank >= 1.0) {
                    crank -= 1.0;
                    double hit = (0.75 + 0.35 * pedal) * (0.85 + 0.3 * rnd);
                    pulse += hit;
                    pulse2 += hit * 0.45;              /* batida secundária, mais grave */
                }
                pulse *= pulseDecay;
                pulse2 *= pulseDecay2;

                /* pressão de escape assimétrica: o pulso empurra para um lado só */
                double press = pulse * 2.4 + pulse2 * 1.1 - 0.18;
                double ex = softClip(press);
                ex = ex - 0.4 * softClip(press * 0.5);  /* cancela o DC e engrossa */

                /* ressonador de estado variável (corpo do escape) */
                svfLow += f * svfBand;
                double hpOut = ex - svfLow - damp * svfBand;
                svfBand += f * hpOut;

                /* admissão: ruído com banda, cresce com o pedal */
                double n = rnd - 0.5;
                intake += intakeLP * (n - intake);
                intakeSlow += intakeHP * (intake - intakeSlow);
                double intakeBand = intake - intakeSlow;

                double s = svfLow * 0.75 + svfBand * 0.55 + intakeBand * (0.25 + 1.6 * pedal);

                /* turbo: assobio fino que sobe com a carga */
                if (turboAmt > 0.01 && loadN > 0.05) {
                    turboPhase += (2200 + 7600 * loadN) / SR;
                    if (turboPhase >= 1.0) turboPhase -= 1.0;
                    s += sinp(turboPhase) * turboAmt * loadN * loadN * 0.09;
                }

                /* estouros ao tirar o pé */
                if (popAt > 0) {
                    if (System.nanoTime() - popAt < 120_000_000L) popEnv = 0.9;
                    else { popEnv = 0; popAt = -1; }
                }
                if (popEnv > 0.001) {
                    s += (rnd - 0.5) * popEnv * popsAmt * 1.3;
                    popEnv *= 0.9955;
                }

                /* passa-baixa suave + DC block */
                bufDC += cutLP * (s - bufDC);
                hp += aHP * (bufDC - hp);
                double out = softClip((bufDC - hp) * 9.0 * gain * 1.6);
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

    /** saturação suave (x/(1+|x|)): o escape real não corta em degrau */
    private static double softClip(double x) {
        if (x > 3) return 1 - 1 / (1 + x);
        if (x < -3) return -1 + 1 / (1 - x);
        return x / (1 + Math.abs(x));
    }

    private static final float[] SINE = new float[4097];

    static {
        for (int i = 0; i <= 4096; i++) SINE[i] = (float) Math.sin(i * 6.283185307179586 / 4096.0);
    }

    /** seno por tabela com interpolação linear, fase em [0,1) */
    private static double sinp(double phase) {
        double x = (phase - Math.floor(phase)) * 4096.0;
        int i = (int) x;
        double fr = x - i;
        return SINE[i] + (SINE[i + 1] - SINE[i]) * fr;
    }
}
