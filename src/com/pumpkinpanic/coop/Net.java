package com.pumpkinpanic.coop;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** LAN / hotspot multiplayer: TCP host-authoritative sessions + UDP broadcast discovery. */
final class Net {
    static final int PORT = 47777, DPORT = 47778, VERSION = 3, MAGIC = 0x50414E31;
    static final int T_HELLO = 1, T_INPUT = 2, T_CMD = 3, T_WELCOME = 10, T_REJECT = 11, T_SNAP = 12, T_ADDONS = 13, T_LEAVE = 99;
    static final int C_ACT = 1, C_BUY = 2, C_SEL = 3, C_EMOTE = 4;

    static final class Msg { final int slot; final byte[] d; Msg(int s, byte[] d) { slot = s; this.d = d; } }
    static final class Found { String ip = "", name = ""; int port, players, diff, phase; long seen; }
    static final class Conn {
        Socket s; DataInputStream in; DataOutputStream out;
        final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<byte[]>();
        volatile boolean alive = true;
        void close() { alive = false; try { if (s != null) s.close(); } catch (Exception e) { /* ignore */ } }
    }

    final ConcurrentLinkedQueue<Msg> inbox = new ConcurrentLinkedQueue<Msg>();
    final ArrayList<Found> found = new ArrayList<Found>();
    final Conn[] conns = new Conn[4];
    volatile Conn hostConn;
    volatile boolean hosting, joined, running, listening;
    volatile String status = "";
    volatile int hostPlayers = 1, hostDiff, hostPhase;
    volatile String hostName = "Host";
    private ServerSocket server;
    private WifiManager.MulticastLock mlock;

    void init(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { mlock = wm.createMulticastLock("pumpkin-panic"); mlock.setReferenceCounted(false); mlock.acquire(); }
        } catch (Throwable t) { /* permission missing: discovery may still work */ }
    }

    // ------------------------------------------------------------ frames
    static byte[] frame(int type, byte[] body) {
        byte[] r = new byte[body.length + 1];
        r[0] = (byte) type;
        System.arraycopy(body, 0, r, 1, body.length);
        return r;
    }
    static byte[] hello(String name) {
        try { ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(b); o.writeByte(T_HELLO); o.writeInt(VERSION); o.writeUTF(name); return b.toByteArray(); }
        catch (IOException e) { return new byte[] {(byte) T_HELLO}; }
    }
    static byte[] input(World.Player p) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(b);
            o.writeByte(T_INPUT); o.writeFloat(p.x); o.writeFloat(p.y); o.writeFloat(p.fx); o.writeFloat(p.fy);
            o.writeByte((p.moving ? 1 : 0) | (p.sprint ? 2 : 0) | (p.lampRaise ? 4 : 0) | (p.waterHeld ? 8 : 0) | (p.actHeld ? 16 : 0));
            o.writeFloat(p.stamina);
            return b.toByteArray();
        } catch (IOException e) { return new byte[] {(byte) T_INPUT}; }
    }
    static byte[] cmd(int code, int a, int b2) {
        try { ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(b); o.writeByte(T_CMD); o.writeByte(code); o.writeInt(a); o.writeInt(b2); return b.toByteArray(); }
        catch (IOException e) { return new byte[] {(byte) T_CMD}; }
    }
    static byte[] welcome(int slot) { return new byte[] {(byte) T_WELCOME, (byte) slot}; }
    static byte[] reject(String why) {
        try { ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(b); o.writeByte(T_REJECT); o.writeUTF(why); return b.toByteArray(); }
        catch (IOException e) { return new byte[] {(byte) T_REJECT}; }
    }
    static byte[] snap(World w) {
        try { ByteArrayOutputStream b = new ByteArrayOutputStream(2048); DataOutputStream o = new DataOutputStream(b); o.writeByte(T_SNAP); w.write(o); return b.toByteArray(); }
        catch (IOException e) { return new byte[] {(byte) T_SNAP}; }
    }

    // ------------------------------------------------------------ sending
    private static void offer(Conn c, byte[] d) { if (c != null && c.alive) { if (c.q.size() > 40) c.q.poll(); c.q.offer(d); } }
    void sendTo(int slot, byte[] d) { if (slot >= 1 && slot < 4) offer(conns[slot], d); }
    void toAll(byte[] d) { for (int i = 1; i < 4; i++) offer(conns[i], d); }
    void sendHost(byte[] d) { offer(hostConn, d); }
    boolean anyClient() { for (int i = 1; i < 4; i++) if (conns[i] != null && conns[i].alive) return true; return false; }
    boolean hasConn(int slot) { return slot >= 1 && slot < 4 && conns[slot] != null && conns[slot].alive; }

    // ------------------------------------------------------------ IO threads
    private void startIO(final Conn c, final int slot, final int firstLen) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    int pending = firstLen;
                    while (c.alive) {
                        int len = pending >= 0 ? pending : c.in.readInt();
                        pending = -1;
                        if (len <= 0 || len > 65536) break;
                        byte[] d = new byte[len];
                        c.in.readFully(d);
                        inbox.add(new Msg(slot, d));
                    }
                } catch (Exception e) { /* disconnect */ }
                c.alive = false;
                c.close();
                inbox.add(new Msg(slot, new byte[] {(byte) T_LEAVE}));
            }
        }, "pp-read").start();
        new Thread(new Runnable() {
            public void run() {
                try {
                    while (c.alive) {
                        byte[] b = c.q.poll(400, TimeUnit.MILLISECONDS);
                        if (b == null) continue;
                        c.out.writeInt(b.length); c.out.write(b); c.out.flush();
                    }
                } catch (Exception e) { /* disconnect */ }
                c.close();
            }
        }, "pp-write").start();
    }

    // ------------------------------------------------------------ hosting
    boolean host(String name) {
        stop();
        running = true; hostName = name;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
        } catch (IOException e) { status = "Cannot open port " + PORT + ": " + e.getMessage(); running = false; return false; }
        hosting = true; status = "Hosting on port " + PORT;
        final ServerSocket srv = server;
        new Thread(new Runnable() {
            public void run() {
                while (running && !srv.isClosed()) {
                    try {
                        final Socket s = srv.accept();
                        new Thread(new Runnable() { public void run() { handshake(s); } }, "pp-hs").start();
                    } catch (IOException e) { if (!running) break; }
                }
            }
        }, "pp-accept").start();
        new Thread(new Runnable() {
            public void run() {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket();
                    ds.setBroadcast(true);
                    while (running && hosting) {
                        byte[] p = beaconText().getBytes("UTF-8");
                        for (InetAddress a : broadcastAddrs()) { try { ds.send(new DatagramPacket(p, p.length, a, DPORT)); } catch (Exception e) { /* ignore */ } }
                        Thread.sleep(1000);
                    }
                } catch (Exception e) { /* ignore */ }
                if (ds != null) ds.close();
            }
        }, "pp-beacon").start();
        return true;
    }


    String beaconText() { return "PPAN1|" + hostName.replace('|', ' ') + "|" + PORT + "|" + hostPlayers + "|" + hostDiff + "|" + hostPhase; }

    private void handshake(Socket s) {
        try {
            s.setTcpNoDelay(true); s.setSoTimeout(2500);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            int first = in.readInt();
            if (first == MAGIC) { out.writeUTF(beaconText()); out.flush(); s.close(); return; }
            s.setSoTimeout(12000);
            Conn c = new Conn();
            c.s = s; c.in = in; c.out = out;
            int slot = -1;
            synchronized (conns) {
                for (int i = 1; i < 4; i++) if (conns[i] == null || !conns[i].alive) { slot = i; break; }
                if (slot >= 0) conns[slot] = c;
            }
            if (slot < 0) { byte[] r = reject("Game is full"); out.writeInt(r.length); out.write(r); out.flush(); s.close(); return; }
            startIO(c, slot, first);
        } catch (Exception e) { try { s.close(); } catch (Exception x) { /* ignore */ } }
    }

    private void addFound(String ip, String text) {
        String[] t = text.split("\\|");
        if (t.length < 6 || !t[0].equals("PPAN1")) return;
        try {
            synchronized (found) {
                Found f = null;
                for (Found g : found) if (g.ip.equals(ip)) f = g;
                if (f == null) { f = new Found(); f.ip = ip; found.add(f); }
                f.name = t[1]; f.port = Integer.parseInt(t[2]); f.players = Integer.parseInt(t[3]); f.diff = Integer.parseInt(t[4]); f.phase = Integer.parseInt(t[5]);
                f.seen = System.currentTimeMillis();
            }
        } catch (NumberFormatException e) { /* ignore */ }
    }

    /** Fallback discovery for networks that block UDP broadcast: probe every address of our /24 subnet(s). */
    private void scanOnce() {
        ArrayList<String> mine = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) { InetAddress a = as.nextElement(); if (a instanceof Inet4Address && !a.isLoopbackAddress()) mine.add(a.getHostAddress()); }
            }
        } catch (Exception e) { return; }
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(40);
        for (final String me : mine) {
            if (me.startsWith("169.254.")) continue;
            final String pre = me.substring(0, me.lastIndexOf('.') + 1);
            for (int i = 1; i < 255; i++) {
                final String ip = pre + i;
                if (ip.equals(me)) continue;
                ex.submit(new Runnable() { public void run() { probe(ip); } });
            }
        }
        ex.shutdown();
        try { ex.awaitTermination(6, TimeUnit.SECONDS); } catch (InterruptedException e) { /* ignore */ }
        ex.shutdownNow();
    }

    void probe(String ip) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ip, PORT), 350);
            s.setSoTimeout(900);
            DataOutputStream o = new DataOutputStream(s.getOutputStream());
            o.writeInt(MAGIC); o.flush();
            addFound(ip, new DataInputStream(s.getInputStream()).readUTF());
        } catch (Exception e) { /* nobody home */ }
        try { if (s != null) s.close(); } catch (Exception e) { /* ignore */ }
    }

    static ArrayList<InetAddress> broadcastAddrs() {
        ArrayList<InetAddress> r = new ArrayList<InetAddress>();
        try { r.add(InetAddress.getByName("255.255.255.255")); } catch (Exception e) { /* ignore */ }
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) { InetAddress b = ia.getBroadcast(); if (b != null) r.add(b); }
            }
        } catch (Exception e) { /* ignore */ }
        return r;
    }

    static String localIps() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) { if (sb.length() > 0) sb.append("  "); sb.append(a.getHostAddress()); }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return sb.length() == 0 ? "no network" : sb.toString();
    }

    // ------------------------------------------------------------ joining
    void connect(final String ip, final String name) {
        stop();
        running = true; status = "Connecting to " + ip + " ...";
        new Thread(new Runnable() {
            public void run() {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(ip, PORT), 4500);
                    s.setTcpNoDelay(true); s.setSoTimeout(12000);
                    Conn c = new Conn();
                    c.s = s; c.in = new DataInputStream(new BufferedInputStream(s.getInputStream())); c.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                    hostConn = c; joined = true;
                    startIO(c, 0, -1);
                    offer(c, hello(name));
                    status = "Connected, waiting for host ...";
                } catch (Exception e) {
                    status = "Could not connect: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    inbox.add(new Msg(0, new byte[] {(byte) T_LEAVE}));
                }
            }
        }, "pp-connect").start();
    }

    // ------------------------------------------------------------ discovery listener
    void startListen() {
        if (listening) return;
        listening = true;
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(700); } catch (InterruptedException e) { /* ignore */ }
                while (listening) { scanOnce(); for (int i = 0; i < 12 && listening; i++) { try { Thread.sleep(250); } catch (InterruptedException e) { return; } } }
            }
        }, "pp-scan").start();
        new Thread(new Runnable() {
            public void run() {
                DatagramSocket ls = null;
                try {
                    ls = new DatagramSocket(null);
                    ls.setReuseAddress(true);
                    ls.bind(new InetSocketAddress(DPORT));
                    ls.setBroadcast(true);
                    ls.setSoTimeout(500);
                    byte[] buf = new byte[300];
                    while (listening) {
                        try {
                            DatagramPacket pk = new DatagramPacket(buf, buf.length);
                            ls.receive(pk);
                            addFound(pk.getAddress().getHostAddress(), new String(pk.getData(), 0, pk.getLength(), "UTF-8"));
                        } catch (SocketTimeoutException e) { /* loop */ }
                        long now = System.currentTimeMillis();
                        synchronized (found) { for (int i = found.size() - 1; i >= 0; i--) if (now - found.get(i).seen > 4500) found.remove(i); }
                    }
                } catch (Exception e) { status = "Discovery unavailable: use the IP button"; }
                if (ls != null) ls.close();
                listening = false;
            }
        }, "pp-listen").start();
    }
    void stopListen() { listening = false; }

    // ------------------------------------------------------------ shutdown
    void stop() {
        running = false; hosting = false; joined = false; listening = false;
        try { if (server != null) server.close(); } catch (Exception e) { /* ignore */ }
        server = null;
        for (int i = 0; i < 4; i++) { Conn c = conns[i]; if (c != null) c.close(); conns[i] = null; }
        Conn h = hostConn; if (h != null) h.close();
        hostConn = null;
        inbox.clear();
        synchronized (found) { found.clear(); }
    }
    void release() { stop(); try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable t) { /* ignore */ } }
}
