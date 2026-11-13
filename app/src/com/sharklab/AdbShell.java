package com.sharklab;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.zip.CRC32;

/**
 * AdbShell — single, reused ADB connection to the head unit (127.0.0.1:5555).
 *
 * If the APK carries the station key (adbkey/adbkey.pub in assets, embedded only in the local
 * build), adbd already knows it and the connection goes in without a dialog. Without the key,
 * it generates its own and asks for authorization once, keeping the connection open.
 *
 * Token signature identical to the reference app's P0: 256-byte PKCS#1 v1.5 block
 * (0x00 0x01 FF..FF 0x00 || SHA-1 DigestInfo || token) with RSA/ECB/NoPadding.
 */
public final class AdbShell {

    /* ctx for execOnce: without it connect uses keys(null) and dies silently */
    private static Context appCtx;

    /* trace to file: the car's logcat rotates and vanishes in the middle of a test */
    static void trace(android.content.Context c, String line) {
        if (c == null && appCtx != null) c = appCtx;
        if (c == null) return;
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + " " + line + "\n";
        /* internal first: with the car off the external storage may unmount and the trace
           (exactly the one from the shutdown) was lost */
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "panel.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(stamp);
            w.close();
        } catch (Throwable ignored) { }
        try {
            java.io.File f = new java.io.File(c.getExternalFilesDir(null), "panel.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(stamp);
            w.close();
        } catch (Throwable ignored) { }
    }

    /** copies the internal trace (readable only by the app) to external storage */
    static void dumpLog(android.content.Context c) {
        if (c == null && appCtx != null) c = appCtx;
        if (c == null) return;
        try {
            java.io.File in = new java.io.File(c.getFilesDir(), "panel.log");
            if (!in.isFile()) return;
            java.io.File out = new java.io.File(c.getExternalFilesDir(null), "panel-internal.log");
            java.io.FileInputStream is = new java.io.FileInputStream(in);
            java.io.FileOutputStream os = new java.io.FileOutputStream(out, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            is.close();
            os.close();
        } catch (Throwable ignored) { }
    }

    private static final String TAG = "SharkLab";
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x57525445;

    private static boolean started;
    private static volatile boolean paused;
    private static volatile boolean wake;
    private static byte[] embeddedKeyLine;

    private AdbShell() {
    }

    static final class Sess {
        Socket s;
        DataInputStream in;
        DataOutputStream out;
    }

    public static synchronized void start(final Context ctx) {
        appCtx = ctx.getApplicationContext();
        if (started) {
            android.util.Log.i(TAG, "adb: loop already started");
            return;
        }
        started = true;
        android.util.Log.i(TAG, "adb: loop starting");
        new Thread(new Runnable() {
            public void run() {
                Sess sess = null;
                int id = 1;
                while (true) {
                    try {
                        if (sess == null) {
                            sess = connect(ctx);
                            android.util.Log.i(TAG, "adb: connected " + (sess != null));
                        }
                        /* respects the app's switch: off by the user, no cmd 16 */
                        boolean on = ctx.getSharedPreferences("lab", 0).getBoolean("enabled", true);
                        if (!paused && on) {
                            /* 46 creates the panel display and 45 takes it full screen: with 45
                               the container stops drawing the native widgets (P/NORMAL,
                               battery, tyres, ODO) on top of the skin. 16 alone projects with
                               those widgets. Measured in the car. */
                            /* the shutdown watchdog comes up here, in the loop's long session: via execOnce the
                               session closes and kills the child */
                            String out = exec(sess,
                                    "cp " + WD_SRC + " /data/local/tmp/" + WD_NAME + " 2>/dev/null; "
                                            + "chmod 755 /data/local/tmp/" + WD_NAME + " 2>/dev/null; "
                                            + "[ -f /data/local/tmp/shark_wd.pid ] || "
                                            + "(setsid sh /data/local/tmp/" + WD_NAME + " >/dev/null 2>&1 &); "
                                            + "service call AutoContainer 2 i32 1000 i32 16 s16 null; sleep 1.5; "
                                            + "service call AutoContainer 2 i32 1000 i32 45 s16 null", id++);
                            Log.i(TAG, "adb: 16/45 -> " + (out == null ? "null" : out.trim()));
                        }
                        android.util.Log.i(TAG, "adb: tick paused=" + paused + " enabled=" + on);
                        for (int i = 0; i < 60 && !wake; i++) Thread.sleep(1000);
                        wake = false;
                    } catch (Throwable t) {
                        Log.w(TAG, "adb: reconnecting: " + t);
                        try { if (sess != null) sess.s.close(); } catch (Throwable ignored) { }
                        sess = null;
                        try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
                    }
                }
            }
        }, "adb-panel").start();
    }

    /** takes over the panel again when the user opens SharkLab */
    public static void resume() {
        paused = false;
        wake = true;
    }

    /** runs one shell line in its own session (used when releasing the panel) */
    public static void execOnce(final String cmd) {
        final Context c = appCtx;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Sess s = connect(c);
                    if (s == null) { trace(c, "execOnce without session"); return; }
                    String out = exec(s, cmd, 900);
                    trace(c, "execOnce " + (out == null ? "null" : out.trim()));
                    try { s.s.close(); } catch (Throwable ignored) { }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "adb: execOnce failed: " + t);
                    trace(c, "execOnce error " + t);
                }
            }
        }, "adb-once").start();
    }

    /**
     * Watchdog released into the shell (like the reference app's): it lives in adbd, not in our
     * process. When the app dies — the car shuts off and Android kills everything — it gives the
     * panel back (46/18/0), otherwise the skin's last frame freezes behind BYD's open-door alert.
     */
    private static final String WD_NAME = "shark_wd.sh";
    /* single quote inside the script built in Java */
    private static final String Q = "\\\"";
    private static final String WD_SRC = "/sdcard/Android/data/com.xdja.clusterdemo/files/" + WD_NAME;
    private static final String WD_BEAT = "/sdcard/Android/data/com.xdja.clusterdemo/files/beat";
    /* timestamp of the watchdog's last release: separates "ignition" from "open-door alert" */
    static final String WD_OFF_TS = "/sdcard/Android/data/com.xdja.clusterdemo/files/off.ts";
    /* exists while "start automatically" is on: the watchdog then revives the app */
    static final String WD_AUTO = "/sdcard/Android/data/com.xdja.clusterdemo/files/autostart";

    /**
     * Watchdog (like the reference app's): a script in adbd that lives outside our process.
     * When the app dies — the car shuts off and Android kills everything — it gives the panel
     * back and leaves a record and a capture for diagnosis.
     */
    public static void watchdog(Context ctx) {
        if (ctx == null) return;
        try {
            java.io.File f = new java.io.File(ctx.getExternalFilesDir(null), WD_NAME);
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            /* Same as the reference app: it only makes sure the app is alive. Who decides to
               apply or release is the app, reading getPowerCtlStatus(3) (1 = on, 0 = off). */
            w.write("#!/system/bin/sh\n"
                    + "AUTO=" + WD_AUTO + "\n"
                    + "mkdir /data/local/tmp/shark_wd.lock 2>/dev/null || exit 0\n"
                    + "echo $$ > /data/local/tmp/shark_wd.pid\n"
                    + "echo \"$(date) armed\" >> /data/local/tmp/shark.log\n"
                    + "while [ -f $AUTO ]; do\n"
                    + "  if [ -z \"$(pidof com.xdja.clusterdemo)\" ]; then\n"
                    /* only wakes if the last attempt was more than 15 s ago: without this the
                       watchdog relaunched on every round while the app was starting and the
                       theme flickered 2-3 times */
                    + "    now=$(date +%s); last=$(cat shark_wake.ts 2>/dev/null)\n"
                    + "    case \"$last\" in ''|*[!0-9]*) last=0;; esac\n"
                    + "    if [ $((now - last)) -gt 15 ]; then\n"
                    + "      date +%s > shark_wake.ts\n"
                    + "      echo \"$(date) relaunching\" >> /data/local/tmp/shark.log\n"
                    + "      am start -n com.xdja.clusterdemo/com.sharklab.WakeActivity >>/data/local/tmp/shark.log 2>&1\n"
                    + "      echo \"$(date) am rc=$?\" >> /data/local/tmp/shark.log\n"
                    + "      sleep 8\n"
                    + "    fi\n"
                    + "  fi\n"
                    + "  sleep 2\n"
                    + "done\n"
                    + "rm -f /data/local/tmp/shark_wd.pid\n"
                    + "rmdir /data/local/tmp/shark_wd.lock 2>/dev/null\n");
            w.close();
        } catch (Throwable t) {
            return;
        }
        /* no pkill: it also killed the watchdog that had just been born when the app armed
           twice in a row. Single instance comes from the mkdir lock plus orphan-lock cleanup
           below (an orphan is only removed when the pid it points at is gone). */
        execOnce("cp " + WD_SRC + " /data/local/tmp/" + WD_NAME
                + "; chmod 755 /data/local/tmp/" + WD_NAME
                + "; P=$(cat /data/local/tmp/shark_wd.pid 2>/dev/null); "
                + "case \"$P\" in ''|*[!0-9]*) P=0;; esac; "
                + "if [ \"$P\" != 0 ] && [ ! -d /proc/$P ]; then "
                + "rm -rf /data/local/tmp/shark_wd.lock; rm -f /data/local/tmp/shark_wd.pid; P=0; fi; "
                + "if [ \"$P\" = 0 ]; then setsid sh /data/local/tmp/" + WD_NAME + " >/dev/null 2>&1 & fi; "
                /* the session closes right after the command: keep it alive so adbd does not take
                   the child, and confirm it actually came up */
                + "sleep 3; P2=$(cat /data/local/tmp/shark_wd.pid 2>/dev/null); "
                + "case \"$P2\" in ''|*[!0-9]*) P2=0;; esac; "
                + "if [ \"$P2\" = 0 ] || [ ! -d /proc/$P2 ]; then "
                + "setsid sh /data/local/tmp/" + WD_NAME + " >/dev/null 2>&1 & fi; sleep 2");
    }

    /** heartbeat stamp: the watchdog releases the panel when it stops advancing */
    static void beat(Context ctx) {
        if (ctx == null) return;
        try {
            java.io.File f = new java.io.File(ctx.getExternalFilesDir(null), "beat");
            f.delete();
            f.createNewFile();
        } catch (Throwable ignored) { }
    }

    /** releases the panel: the loop stops sending cmd 16 and the head unit returns to BYD's original */
    public static void stop() {
        paused = true;
    }

    private static Sess connect(Context ctx) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 8000);
        s.setSoTimeout(120000);
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        DataInputStream in = new DataInputStream(s.getInputStream());
        send(out, A_CNXN, 0x01000000, 4096, "host::\u0000".getBytes("UTF-8"));
        KeyPair kp = keys(ctx);
        int tries = 0;
        while (true) {
            byte[] h = hdr(in);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int what = b.getInt(0), a0 = b.getInt(4), len = b.getInt(12);
            byte[] data = new byte[len];
            read(in, data);
            if (what == A_CNXN) {
                Log.i(TAG, "adb: connected");
                s.setSoTimeout(20000);
                Sess sess = new Sess();
                sess.s = s;
                sess.in = in;
                sess.out = out;
                return sess;
            }
            if (what == A_AUTH) {
                Log.i(TAG, "adb: AUTH arg0=" + a0);
                if (a0 == 1) {
                    if (tries % 2 == 0) {
                        send(out, A_AUTH, 2, 0, signToken(kp, data));
                    } else {
                        send(out, A_AUTH, 3, 0, keyString(kp));
                    }
                    tries++;
                }
            }
        }
    }

    private static byte[] signToken(KeyPair kp, byte[] token) throws Exception {
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] block = new byte[256];
        block[0] = 0;
        block[1] = 1;
        for (int i = 2; i < 220; i++) block[i] = (byte) 0xFF;
        block[220] = 0;
        System.arraycopy(prefix, 0, block, 221, prefix.length);
        System.arraycopy(token, 0, block, 236, Math.min(token.length, 20));
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, kp.getPrivate());
        return c.doFinal(block);
    }

    private static byte[] keyString(KeyPair kp) throws Exception {
        if (embeddedKeyLine != null) return embeddedKeyLine;
        String s = Base64.encodeToString(publicKey(kp.getPublic(), "sharklab"), Base64.NO_WRAP)
                + " sharklab\u0000";
        return s.getBytes("UTF-8");
    }

    private static String exec(Sess sess, String cmd, int localId) throws Exception {
        send(sess.out, A_OPEN, localId, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        while (true) {
            byte[] h = hdr(sess.in);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int what = b.getInt(0), a0 = b.getInt(4), len = b.getInt(12);
            byte[] data = new byte[len];
            read(sess.in, data);
            if (what == A_WRTE) {
                acc.write(data);
                send(sess.out, A_OKAY, localId, a0, new byte[0]);
            } else if (what == A_CLSE) {
                break;
            }
        }
        return acc.toString("UTF-8");
    }

    private static synchronized KeyPair keys(Context ctx) throws Exception {
        try {
            java.io.InputStream in = ctx.getAssets().open("adbkey.pem");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String pem = new String(bo.toByteArray(), "UTF-8")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey pk = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pem, Base64.NO_WRAP)));
            RSAPrivateKey rp = (RSAPrivateKey) pk;
            PublicKey pb = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(rp.getModulus(), BigInteger.valueOf(65537)));
            try {
                java.io.InputStream pi = ctx.getAssets().open("adbkey.pub");
                java.io.ByteArrayOutputStream pbo = new java.io.ByteArrayOutputStream();
                byte[] pbuf = new byte[8192];
                int pn;
                while ((pn = pi.read(pbuf)) > 0) pbo.write(pbuf, 0, pn);
                pi.close();
                String txt = new String(pbo.toByteArray(), "UTF-8").trim();
                embeddedKeyLine = (txt + "\u0000").getBytes("UTF-8");
            } catch (Throwable ignored) {
                embeddedKeyLine = null;
            }
            Log.i(TAG, "adb: using station key");
            return new KeyPair(pb, pk);
        } catch (Throwable t) {
            Log.w(TAG, "adb: embedded key failed: " + t);
        }
        SharedPreferences p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE);
        String priv = p.getString("adb_priv", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.length() > 0) {
            try {
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
                RSAPrivateKey rp = (RSAPrivateKey) pk;
                PublicKey pb = kf.generatePublic(
                        new RSAPublicKeySpec(rp.getModulus(), BigInteger.valueOf(65537)));
                return new KeyPair(pb, pk);
            } catch (Throwable ignored) {
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        p.edit()
                .putString("adb_priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .apply();
        Log.i(TAG, "adb: key created");
        return kp;
    }

    private static byte[] publicKey(PublicKey key, String name) throws Exception {
        java.security.interfaces.RSAPublicKey r = (java.security.interfaces.RSAPublicKey) key;
        BigInteger n = r.getModulus();
        BigInteger e = r.getPublicExponent();
        long n0 = n.and(BigInteger.valueOf(0xffffffffL)).longValue();
        long inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32)).longValue();
        int n0inv = (int) (0 - inv);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        byte[] nm = name.getBytes("UTF-8");
        ByteBuffer b = ByteBuffer.allocate(4 + 256 + 256 + 4 + nm.length + 1)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(n0inv);
        words(b, n);
        words(b, rr);
        b.putInt(e.intValue());
        b.put(nm);
        b.put((byte) 0);
        return b.array();
    }

    private static void words(ByteBuffer b, BigInteger v) {
        for (int i = 0; i < 64; i++) {
            b.putInt((int) v.shiftRight(32 * i).and(BigInteger.valueOf(0xffffffffL)).longValue());
        }
    }

    private static void send(DataOutputStream out, int cmd, int a0, int a1, byte[] data)
            throws Exception {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd);
        h.putInt(a0);
        h.putInt(a1);
        h.putInt(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        h.putInt((int) crc.getValue());
        h.putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static byte[] hdr(DataInputStream in) throws Exception {
        byte[] h = new byte[24];
        read(in, h);
        return h;
    }

    private static void read(DataInputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new java.io.EOFException();
            off += n;
        }
    }
}
