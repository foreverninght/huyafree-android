package com.huyafree;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Hand-rolled WebSocket client for Huya's danmaku push channel.
 *
 * We deliberately avoid OkHttp: the project ships without it, and the channel
 * only needs RFC 6455 basics (text/binary frames, ping/pong, masking, TLS).
 *
 * Lifecycle: connect() on a worker thread, listen until stop().
 * The server pushes MsgPush frames continuously once we register `chat:<pid>`.
 */
final class DanmakuClient {

    interface Listener {
        void onDanmaku(DanmakuProtocol.Msg msg);

        void onState(String text, boolean connected);
    }

    private static final String TAG = "DanmakuClient";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random rnd = new SecureRandom();
    private final List<Listener> listeners = new ArrayList<>();

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile Thread worker;
    private volatile Thread pinger;
    private volatile String guid;
    private volatile String channelId;
    private volatile String deviceModel = "";

    void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) {
                listeners.add(l);
            }
        }
    }

    void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void state(final String text, final boolean ok) {
        main.post(() -> {
            List<Listener> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Listener l : copy) {
                l.onState(text, ok);
            }
        });
    }

    private void deliver(final DanmakuProtocol.Msg m) {
        main.post(() -> {
            List<Listener> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Listener l : copy) {
                l.onDanmaku(m);
            }
        });
    }

    boolean isRunning() {
        return running.get();
    }

    void start(String guid, String channelId, String deviceModel) {
        if (running.getAndSet(true)) {
            return;
        }
        this.guid = guid;
        this.channelId = channelId;
        this.deviceModel = deviceModel == null ? "" : deviceModel;
        worker = new Thread(this::run, "danmaku-ws");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running.set(false);
        closeQuietly();
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
        worker = null;
        Thread p = pinger;
        if (p != null) {
            p.interrupt();
        }
        pinger = null;
        state("弹幕已关闭", false);
    }

    private void closeQuietly() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
        out = null;
    }

    // =================== main loop ===================

    private void run() {
        int attempt = 0;
        while (running.get()) {
            try {
                attempt++;
                state(attempt == 1 ? "连接弹幕…" : "重连弹幕 #" + attempt, false);
                connectAndPump();
            } catch (Exception e) {
                Log.w(TAG, "ws error: " + e);
                state("弹幕连接中断: " + e.getClass().getSimpleName(), false);
            } finally {
                closeQuietly();
            }
            if (!running.get()) {
                break;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            }
        }
        state("弹幕已退出", false);
    }

    private void connectAndPump() throws Exception {
        String host = DanmakuProtocol.WS_HOST;
        String path = "/" + DanmakuProtocol.baseInfo(guid);

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, 443), 8000);
        raw.setSoTimeout(0);
        raw.setTcpNoDelay(true);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c,
                                                   String a) {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] c,
                                                   String a) {
                    }

                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }}, new SecureRandom());
        SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                .createSocket(raw, host, 443, true);
        ssl.startHandshake();
        socket = ssl;

        // ---- HTTP Upgrade ----
        String key = wsKey();
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: https://www.huya.com\r\n"
                + "User-Agent: Mozilla/5.0 (Linux; Android 12) huya/13.3.52\r\n"
                + "\r\n";
        OutputStream so = ssl.getOutputStream();
        so.write(req.getBytes("UTF-8"));
        so.flush();

        InputStream in = ssl.getInputStream();
        String header = readHttpHeader(in);
        if (header == null || !header.contains("101")) {
            throw new IOException("upgrade rejected: "
                    + (header == null ? "no response"
                    : header.split("\r\n")[0]));
        }
        out = so;

        // ---- subscribe ----
        byte[] reg0 = DanmakuProtocol.registerGroup(new String[]{"live:0"});
        writeFrame(reg0);

        byte[] reg = DanmakuProtocol.registerGroup(
                new String[]{"live:" + channelId, "chat:" + channelId});
        writeFrame(reg);

        state("弹幕已连接", true);

        // heartbeat thread
        pinger = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(20000);
                    if (!running.get()) {
                        break;
                    }
                    writeFrame(DanmakuProtocol.heartbeat());
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    break;
                }
            }
        }, "danmaku-hb");
        pinger.setDaemon(true);
        pinger.start();

        // ---- read loop ----
        while (running.get()) {
            byte[] payload = readFrame(in);
            if (payload == null) {
                throw new IOException("connection closed");
            }
            DanmakuProtocol.Frame f = DanmakuProtocol.splitCommand(payload);

            if (f.cmdType == DanmakuProtocol.CMD_WUP_RSP) {
                // Nothing requests WUP here; drain silently.
                continue;
            }
            if (f.cmdType != DanmakuProtocol.CMD_MSG_PUSH
                    && f.cmdType != DanmakuProtocol.CMD_MSG_PUSH_V2) {
                continue;
            }

            for (byte[] blob : DanmakuProtocol.extractMessages(f.cmdType,
                    f.vData)) {
                DanmakuProtocol.Msg m;
                try {
                    m = DanmakuProtocol.parseMessageNotice(blob);
                } catch (Exception e) {
                    continue;
                }
                if (m.usable()) {
                    deliver(m);
                }
            }
        }
    }

    // =================== ws framing ===================

    private String wsKey() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    private static String readHttpHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int prev3 = -1;
        int prev2 = -1;
        int prev1 = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                return buf.size() > 0 ? buf.toString("UTF-8") : null;
            }
            buf.write(c);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && c == '\n') {
                return buf.toString("UTF-8");
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = c;
            if (buf.size() > 16384) {
                return buf.toString("UTF-8");
            }
        }
    }

    private synchronized void writeFrame(byte[] payload) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("not connected");
        }
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x82); // FIN + binary

        int len = payload.length;
        if (len < 126) {
            f.write(0x80 | len);
        } else if (len < 65536) {
            f.write(0x80 | 126);
            f.write((len >> 8) & 0xFF);
            f.write(len & 0xFF);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                f.write((int) (((long) len >> (i * 8)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask, 0, 4);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        f.write(masked, 0, len);
        o.write(f.toByteArray());
        o.flush();
    }

    private byte[] readFrame(InputStream in) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                return null;
            }
            int b1 = in.read();
            if (b1 < 0) {
                return null;
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;

            if (len == 126) {
                len = ((long) readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                for (int i = 0; i < 4; i++) {
                    mask[i] = (byte) readByte(in);
                }
            }
            if (len < 0 || len > 8 * 1024 * 1024) {
                throw new IOException("bad frame length " + len);
            }
            byte[] payload = new byte[(int) len];
            int got = 0;
            while (got < len) {
                int n = in.read(payload, got, (int) len - got);
                if (n < 0) {
                    return null;
                }
                got += n;
            }
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i & 3]);
                }
            }

            if (opcode == 0x8) {          // close
                return null;
            }
            if (opcode == 0x9) {          // ping -> pong
                try {
                    writePong(payload);
                } catch (Exception ignored) {
                }
                continue;
            }
            if (opcode == 0xA) {          // pong
                continue;
            }
            if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                if (!fin) {
                    ByteArrayOutputStream acc = new ByteArrayOutputStream();
                    acc.write(payload, 0, payload.length);
                    while (!fin) {
                        int c0 = in.read();
                        if (c0 < 0) {
                            return null;
                        }
                        int c1 = in.read();
                        if (c1 < 0) {
                            return null;
                        }
                        fin = (c0 & 0x80) != 0;
                        long cl = c1 & 0x7F;
                        if (cl == 126) {
                            cl = ((long) readByte(in) << 8) | readByte(in);
                        } else if (cl == 127) {
                            cl = 0;
                            for (int i = 0; i < 8; i++) {
                                cl = (cl << 8) | readByte(in);
                            }
                        }
                        byte[] part = new byte[(int) cl];
                        int g = 0;
                        while (g < cl) {
                            int n = in.read(part, g, (int) cl - g);
                            if (n < 0) {
                                return null;
                            }
                            g += n;
                        }
                        acc.write(part, 0, part.length);
                    }
                    return acc.toByteArray();
                }
                return payload;
            }
        }
    }

    private int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) {
            throw new IOException("eof");
        }
        return v;
    }

    private synchronized void writePong(byte[] payload) throws IOException {
        OutputStream o = out;
        if (o == null) {
            return;
        }
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x8A);
        f.write(0x80 | (payload.length & 0x7F));
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            f.write(payload[i] ^ mask[i & 3]);
        }
        o.write(f.toByteArray());
        o.flush();
    }
}
