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
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.CRC32;

/**
 * AdbShell — fala ADB com a própria central (127.0.0.1:5555) como o EvSound faz.
 *
 * A autorização de depuração é uma vez só: a chave RSA fica salva em prefs e o adbd guarda a
 * pública em adb_keys. Depois disso dá para rodar comandos de shell (uid 2000), que é o que o
 * EvSound usa para mandar o painel. Sem MediaProjection e sem diálogo por arranque.
 */
public final class AdbShell {

    private static final String TAG = "SharkLab";
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x57525445;
    private static final String PORT = "127.0.0.1";
    private static final int PORT_N = 5555;

    private static boolean started;

    private AdbShell() {
    }

    /** começa o laço de 5 s que reafirma o painel por shell; pede autorização na 1ª vez */
    public static synchronized void start(final Context ctx) {
        if (started) return;
        started = true;
        new Thread(new Runnable() {
            public void run() {
                int id = 1;
                boolean ok = false;
                try {
                    String out = shell(ctx, "service call AutoContainer 2 i32 1000 i32 16 s16 null", id++);
                    Log.i(TAG, "adb: 16 -> " + (out == null ? "null" : out.trim()));
                    ok = true;
                } catch (Throwable t) {
                    Log.w(TAG, "adb: sem autorização nesta execução: " + t);
                }
                while (ok) {
                    try {
                        Thread.sleep(60000);
                        String out = shell(ctx, "service call AutoContainer 2 i32 1000 i32 16 s16 null", id++);
                        Log.i(TAG, "adb: 16 -> " + (out == null ? "null" : out.trim()));
                    } catch (Throwable t) {
                        Log.w(TAG, "adb: parou: " + t);
                        ok = false;
                    }
                }
            }
        }, "adb-panel").start();
    }

    /** roda um comando de shell no adbd local e devolve a saída */
    public static String shell(Context ctx, String cmd, int localId) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(PORT, PORT_N), 8000);
            s.setSoTimeout(20000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            if (!handshake(ctx, in, out)) throw new IllegalStateException("sem CNXN");

            send(out, A_OPEN, localId, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            while (true) {
                byte[] h = hdr(in);
                ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                int what = b.getInt(0), a0 = b.getInt(4);
                int len = b.getInt(12);
                byte[] data = new byte[len];
                read(in, data);
                if (what == A_WRTE) {
                    acc.write(data);
                    send(out, A_OKAY, localId, a0, new byte[0]);
                } else if (what == A_CLSE) {
                    break;
                }
            }
            return acc.toString("UTF-8");
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean handshake(Context ctx, DataInputStream in, DataOutputStream out)
            throws Exception {
        send(out, A_CNXN, 0x01000000, 256 * 1024, "host::".getBytes("UTF-8"));
        KeyPair kp = keys(ctx);
        int authTries = 0;
        while (true) {
            byte[] h = hdr(in);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int what = b.getInt(0), a0 = b.getInt(4);
            int len = b.getInt(12);
            byte[] data = new byte[len];
            read(in, data);
            if (what == A_CNXN) return true;
            if (what == A_AUTH) {
                Log.i(TAG, "adb: AUTH arg0=" + a0);
                if (a0 == 1 && authTries == 0) {
                    authTries++;
                    Signature sig = Signature.getInstance("SHA1withRSA");
                    sig.initSign(kp.getPrivate());
                    sig.update(data);
                    send(out, A_AUTH, 2, 0, sig.sign());     /* AUTH_SIGNATURE */
                } else {
                    send(out, A_AUTH, 3, 0, publicKey(kp.getPublic(), "sharklab")); /* AUTH_RSAPUBLICKEY */
                }
            }
        }
    }

    /** chave RSA persistida: o adbd autoriza uma vez e reconhece nas próximas */
    private static synchronized KeyPair keys(Context ctx) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE);
        String priv = p.getString("adb_priv", "");
        String pub = p.getString("adb_pub", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.length() > 0 && pub.length() > 0) {
            try {
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
                PublicKey pb = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)));
                return new KeyPair(pb, pk);
            } catch (Throwable ignored) {
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        p.edit()
                .putString("adb_priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("adb_pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                .apply();
        Log.i(TAG, "adb: chave criada");
        return kp;
    }

    /** formato RSAPublicKey do adb: n0inv, n[64], rr[64], exponent, nome */
    private static byte[] publicKey(PublicKey key, String name) throws Exception {
        RSAPublicKey r = (RSAPublicKey) key;
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
        for (int i = 0; i < 64; i++) {     /* 2048 bits = 64 palavras de 32 bits */
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
