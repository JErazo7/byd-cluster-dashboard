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
 * AdbShell — conexão ADB única e reaproveitada com a central (127.0.0.1:5555).
 *
 * Se o APK trouxer a chave da estação (adbkey/adbkey.pub em assets, embutida só no build
 * local), o adbd já a conhece e a conexão entra sem diálogo. Sem a chave, gera uma própria e
 * pede autorização uma vez, mantendo a conexão aberta.
 *
 * Assinatura do token igual ao P0 do EvSound: bloco PKCS#1 v1.5 de 256 bytes
 * (0x00 0x01 FF..FF 0x00 || DigestInfo SHA-1 || token) com RSA/ECB/NoPadding.
 */
public final class AdbShell {

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
        if (started) {
            android.util.Log.i(TAG, "adb: laço já iniciado");
            return;
        }
        started = true;
        android.util.Log.i(TAG, "adb: laço iniciando");
        new Thread(new Runnable() {
            public void run() {
                Sess sess = null;
                int id = 1;
                while (true) {
                    try {
                        if (sess == null) {
                            sess = connect(ctx);
                            android.util.Log.i(TAG, "adb: conectado " + (sess != null));
                        }
                        /* respeita o botão do app: desligado pelo usuário, nada de cmd 16 */
                        boolean on = ctx.getSharedPreferences("lab", 0).getBoolean("enabled", true);
                        if (!paused && on) {
                            /* medido no EvSound: 46 prepara/cria o display do painel e só
                               depois o 16 projeta. Sem o 46 o display não existe e a skin fica
                               sem onde desenhar. */
                            String out = exec(sess,
                                    "service call AutoContainer 2 i32 1000 i32 46 s16 null; sleep 1.2; "
                                            + "service call AutoContainer 2 i32 1000 i32 16 s16 null", id++);
                            Log.i(TAG, "adb: 16 -> " + (out == null ? "null" : out.trim()));
                        }
                        android.util.Log.i(TAG, "adb: tick paused=" + paused + " enabled=" + on);
                        for (int i = 0; i < 60 && !wake; i++) Thread.sleep(1000);
                        wake = false;
                    } catch (Throwable t) {
                        Log.w(TAG, "adb: reconectando: " + t);
                        try { if (sess != null) sess.s.close(); } catch (Throwable ignored) { }
                        sess = null;
                        try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
                    }
                }
            }
        }, "adb-panel").start();
    }

    /** volta a assumir o painel quando o usuário abre a SharkLab */
    public static void resume() {
        paused = false;
        wake = true;
    }

    /** roda uma linha de shell numa sessão própria (usado ao soltar o painel) */
    public static void execOnce(final String cmd) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Sess s = connect(null);
                    if (s == null) return;
                    exec(s, cmd, 900);
                    try { s.s.close(); } catch (Throwable ignored) { }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "adb: execOnce falhou: " + t);
                }
            }
        }, "adb-once").start();
    }

    /** solta o painel: o laço para de mandar cmd 16 e a central volta ao original da BYD */
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
                Log.i(TAG, "adb: conectado");
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
            Log.i(TAG, "adb: usando chave da estação");
            return new KeyPair(pb, pk);
        } catch (Throwable t) {
            Log.w(TAG, "adb: chave embutida falhou: " + t);
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
        Log.i(TAG, "adb: chave criada");
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
