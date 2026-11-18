package com.pumpkinpanic.coop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LANAddonSync - addon handshake for LAN multiplayer.
 *
 * HOST    getActiveAddonsManifestPacket() -> byte[] you send to a joining client in your handshake
 *         startHostServer()               -> TCP thread streaming active addons (each folder zipped) on request
 * CLIENT  verifyHostAddons(packet, cb)    -> compare, ask "Download required files from Host?",
 *         download into /sdcard/PumpkinPanic/addons/, AddonDataManager.rescan(), reload, then
 *         cb.onSyncComplete() (finish the join) or cb.onSyncAborted() (back to the main menu).
 *
 * WIRE FORMAT (big-endian, java.io.Data*Stream)
 *   manifest packet : int 'PPAD' | short version | int filePort | UTF hostIp | int count |
 *                     count x { UTF id, UTF version, UTF sha256hex, UTF fileName, long size }
 *   file request    : int 'PPRQ' | short version | int count | count x { UTF id, UTF sha256hex }
 *   file response   : per requested addon, in order: byte status, then (if OK) long size + raw bytes
 *
 * SAFETY  The host only serves files of its currently ACTIVE addons, looked up by id + hash; a
 *         client can never name a path. The client accepts only plain .zip/.png file names, writes
 *         to *.part, verifies SHA-256, then renames. Downloaded files are only as trustworthy as the
 *         host the player chose to join - script safety is AddonScriptSandbox's job.
 *
 * Send the manifest packet over a reliable (TCP) channel. All SyncCallback methods run on the UI
 * thread - hop to your network thread before touching sockets.
 */
public final class LANAddonSync {

    private static final String TAG = "LANAddonSync";

    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_PORT = 47321;          // falls back to any free port

    private static final int PACKET_MAGIC  = 0x50504144;   // "PPAD"
    private static final int REQUEST_MAGIC = 0x50505251;   // "PPRQ"
    private static final int ST_OK = 0, ST_NOT_ACTIVE = 1, ST_HASH_MISMATCH = 2;

    private static final int BUF = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SO_TIMEOUT_MS = 15000;
    private static final int MAX_ADDONS = 256;
    private static final int MAX_PACKET_ADDONS = 150;      // keeps the manifest packet under the game's 64 KB frame limit
    private static final int MAX_HOST_CLIENTS = 4;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final long STORAGE_MARGIN = 8L * 1024 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Result of the join-time addon check. Both methods are delivered on the UI thread. */
    public interface SyncCallback {
        /** The client now has every host addon installed and active: continue the join. */
        void onSyncComplete();

        /**
         * The join must be dropped: the player chose NO, the download failed, the host
         * disconnected, or an addon could not be activated. Disconnect and show the main menu.
         */
        void onSyncAborted(String reason);
    }

    private final AddonEngineController owner;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService clientExec = Executors.newSingleThreadExecutor();
    private final ExecutorService hostPool = Executors.newFixedThreadPool(MAX_HOST_CLIENTS);
    private final Object zipLock = new Object();
    private final Set<Socket> hostSockets = Collections.synchronizedSet(new HashSet<Socket>());
    private ServerSocket serverSocket;                      // guarded by this
    private Session session;                                // guarded by this
    private volatile int hostPort;
    private volatile String hostOverride;

    LANAddonSync(AddonEngineController owner) {
        this.owner = owner;
    }

    // =========================================================================================
    // HOST
    // =========================================================================================

    /** Starts the addon file server thread (idempotent). Returns its TCP port, or -1 on failure. */
    public synchronized int startHostServer() {
        if (serverSocket != null && !serverSocket.isClosed()) return hostPort;
        ServerSocket ss;
        try {
            try {
                ss = new ServerSocket(DEFAULT_PORT);
            } catch (IOException portBusy) {
                ss = new ServerSocket(0);                   // any free port; it is advertised in the packet
            }
        } catch (IOException e) {
            Log.e(TAG, "Cannot open addon file server", e);
            return -1;
        }
        final ServerSocket server = ss;
        serverSocket = server;
        hostPort = server.getLocalPort();

        Thread acceptor = new Thread(new Runnable() {
            public void run() { acceptLoop(server); }
        }, "AddonSync-Host");
        acceptor.setDaemon(true);
        acceptor.start();

        hostPool.execute(new Runnable() {                   // pre-hash so the first joiner isn't delayed
            public void run() { snapshotActiveEntries(); }
        });
        Log.i(TAG, "Addon file server listening on port " + hostPort);
        return hostPort;
    }

    public synchronized void stopHostServer() {
        ServerSocket ss = serverSocket;
        serverSocket = null;
        hostPort = 0;
        closeQuietly(ss);
        synchronized (hostSockets) {
            for (Socket s : hostSockets) closeQuietly(s);
            hostSockets.clear();
        }
    }

    /**
     * HOST: serialises id / version / SHA-256 / file name / size of every active addon (see the
     * wire format above). Starts the file server so the advertised port is live. Call from a
     * background thread: files are hashed on first use (then cached until they change).
     */
    public byte[] getActiveAddonsManifestPacket() {
        int port = startHostServer();
        List<Entry> entries = snapshotActiveEntries();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + entries.size() * 160);
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(PACKET_MAGIC);
            out.writeShort(PROTOCOL_VERSION);
            out.writeInt(Math.max(port, 0));
            out.writeUTF(detectLocalIpv4());
            out.writeInt(entries.size());
            for (Entry e : entries) {
                out.writeUTF(e.id);
                out.writeUTF(e.version);
                out.writeUTF(e.sha256);
                out.writeUTF(e.fileName);
                out.writeLong(e.size);
            }
            out.flush();
        } catch (IOException e) {                           // cannot happen with a byte array
            Log.e(TAG, "Manifest packet failed", e);
            return new byte[0];
        }
        return bytes.toByteArray();
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                final Socket client = ss.accept();
                hostSockets.add(client);
                try {
                    hostPool.execute(new Runnable() {
                        public void run() { serveClient(client); }
                    });
                } catch (RejectedExecutionException shuttingDown) {
                    hostSockets.remove(client);
                    closeQuietly(client);
                    return;
                }
            } catch (IOException e) {
                if (ss.isClosed()) return;
                Log.w(TAG, "accept failed: " + e.getMessage());
                try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** One joining client: read its request, stream the requested ACTIVE addon files back. */
    private void serveClient(Socket s) {
        DataInputStream in = null;
        DataOutputStream out = null;
        InputStream file = null;
        try {
            s.setSoTimeout(SO_TIMEOUT_MS);
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), BUF));

            if (in.readInt() != REQUEST_MAGIC || in.readShort() != PROTOCOL_VERSION) return;
            int count = in.readInt();
            if (count < 0 || count > MAX_ADDONS) return;
            String[] ids = new String[count];
            String[] hashes = new String[count];
            for (int i = 0; i < count; i++) {
                ids[i] = in.readUTF();
                hashes[i] = in.readUTF();
            }

            Map<String, Entry> active = new HashMap<String, Entry>();   // only ACTIVE addons are served
            for (Entry e : snapshotActiveEntries()) active.put(e.id, e);

            byte[] buf = new byte[BUF];
            for (int i = 0; i < count; i++) {
                Entry e = active.get(ids[i]);
                if (e == null) { out.writeByte(ST_NOT_ACTIVE); continue; }
                if (!e.sha256.equalsIgnoreCase(hashes[i])) { out.writeByte(ST_HASH_MISMATCH); continue; }

                out.writeByte(ST_OK);
                out.writeLong(e.size);
                file = new FileInputStream(e.file);
                long left = e.size;
                while (left > 0) {
                    int n = file.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new EOFException("Addon file changed while sending: " + e.fileName);
                    out.write(buf, 0, n);
                    left -= n;
                }
                closeQuietly(file);
                file = null;
            }
            out.flush();
            Log.i(TAG, "Served " + count + " addon file(s) to " + s.getInetAddress());
        } catch (IOException e) {
            Log.w(TAG, "Addon transfer aborted: " + e.getMessage());
        } finally {
            closeQuietly(file);
            closeQuietly(out);
            closeQuietly(in);
            hostSockets.remove(s);
            closeQuietly(s);
        }
    }

    /**
     * Active addons, each described by its folder content hash and a zip of that folder in the
     * cache directory (rebuilt only when the content changes).
     */
    private List<Entry> snapshotActiveEntries() {
        List<Entry> list = new ArrayList<Entry>();
        File cache = cacheDir();
        for (AddonManifest m : owner.getActiveAddons()) {
            if (list.size() >= MAX_PACKET_ADDONS) break;
            String sha = m.contentHash();
            if (sha == null) {
                Log.w(TAG, "Addon '" + m.getId() + "' cannot be read - not synced");
                continue;
            }
            String fileName = AddonPackager.safeName(m.getId()) + ".zip";
            File zip = new File(cache, AddonPackager.safeName(m.getId()) + "-" + sha.substring(0, 16) + ".zip");
            try {
                synchronized (zipLock) {
                    if (!zip.isFile()) {
                        File[] old = cache.listFiles();
                        if (old != null) for (File f : old) if (f.getName().startsWith(AddonPackager.safeName(m.getId()) + "-")) f.delete();
                        AddonPackager.zipFolder(m.getDirectory(), zip);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Cannot package addon '" + m.getId() + "': " + e.getMessage());
                continue;
            }
            list.add(new Entry(m.getId(), m.getVersion(), sha, fileName, zip.length(), zip));
        }
        return list;
    }

    private File cacheDir() {
        Context c = owner.getActivity();
        File base = c != null ? c.getCacheDir() : AddonEngineController.getAddonsDir();
        File d = new File(base, "addon-sync");
        d.mkdirs();
        return d;
    }

    // =========================================================================================
    // CLIENT
    // =========================================================================================

    /** Optional: the IP your client socket connected to. Overrides the IP inside the packet. */
    public void setHostAddress(String hostIp) {
        hostOverride = hostIp;
    }

    /**
     * CLIENT: nothing missing -> activate the host's addons and call onSyncComplete().
     * Something missing -> AlertDialog. YES: download, rescan, activate, onSyncComplete().
     * NO / failure / disconnect: onSyncAborted().
     */
    public void verifyHostAddons(byte[] hostManifestData, SyncCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final Session s = new Session(callback);
        Session previous;
        synchronized (this) {
            previous = session;
            session = s;
        }
        if (previous != null) previous.fail("Superseded by a new join request");

        final HostInfo info;
        try {
            info = HostInfo.parse(hostManifestData);
        } catch (IOException e) {
            Log.w(TAG, "Bad manifest packet: " + e.getMessage());
            s.fail("Invalid addon manifest from host");
            return;
        }

        clientExec.execute(new Runnable() {
            public void run() {
                try {
                    final List<Entry> missing = findMissing(info.entries);
                    if (s.finished.get()) return;
                    if (missing.isEmpty()) {
                        activateAndFinish(s, info, false);
                    } else {
                        ui.post(new Runnable() {
                            public void run() { showConfirmDialog(s, info, missing); }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Addon check failed", e);
                    s.fail("Addon check failed: " + e);
                }
            }
        });
    }

    /** CLIENT: the host connection dropped (or the player backed out) - cancel and abort the join. */
    public void cancelClientSync(String reason) {
        Session s;
        synchronized (this) { s = session; }
        if (s != null) s.fail(reason);
    }

    /** Host addons that are not installed locally with the same id AND hash. */
    private List<Entry> findMissing(List<Entry> hostEntries) {
        Set<String> local = new HashSet<String>();
        for (AddonManifest m : owner.getData().getManifests()) {
            String sha = m.contentHash();
            if (sha != null) local.add(m.getId() + "|" + sha);
        }
        List<Entry> missing = new ArrayList<Entry>();
        for (Entry e : hostEntries) {
            if (!local.contains(e.id + "|" + e.sha256)) missing.add(e);
        }
        return missing;
    }

    /** UI thread. */
    private void showConfirmDialog(final Session s, final HostInfo info, final List<Entry> missing) {
        if (s.finished.get()) return;
        Activity a = owner.getActivity();
        if (a == null || a.isFinishing()) { s.fail("No active screen to ask for permission"); return; }

        StringBuilder msg = new StringBuilder(
                "Host requires active addons to join. Download required files from Host?\n");
        long total = 0;
        for (int i = 0; i < missing.size(); i++) {
            Entry e = missing.get(i);
            total += e.size;
            if (i < 6) {
                msg.append("\n\u2022 ").append(e.id).append(" v").append(e.version)
                   .append(" (").append(fmtSize(e.size)).append(')');
            }
        }
        if (missing.size() > 6) msg.append("\n\u2026 and ").append(missing.size() - 6).append(" more");
        msg.append("\n\nTotal: ").append(fmtSize(total));

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Addons required")
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton("YES", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        startDownload(s, info, missing);
                    }
                })
                .setNegativeButton("NO", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        s.fail("Join cancelled: required addons were declined");
                    }
                })
                .create();
        s.confirm = d;
        d.show();
    }

    /** UI thread (YES clicked): progress dialog, then download on the worker thread. */
    private void startDownload(final Session s, final HostInfo info, final List<Entry> missing) {
        if (s.finished.get()) return;
        Activity a = owner.getActivity();
        if (a == null || a.isFinishing()) { s.fail("No active screen"); return; }

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20);
        box.setPadding(pad, pad, pad, pad);
        TextView label = new TextView(a);
        label.setText("Connecting to host...");
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(label);
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        s.label = label;
        s.bar = bar;

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Downloading addons")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        s.fail("Download cancelled");
                    }
                })
                .create();
        s.progress = d;
        d.show();

        clientExec.execute(new Runnable() {
            public void run() {
                try {
                    downloadFiles(s, info, missing);
                    if (!s.finished.get()) activateAndFinish(s, info, true);
                } catch (IOException e) {
                    Log.w(TAG, "Download failed", e);
                    s.fail(describe(e));
                } catch (RuntimeException e) {
                    Log.e(TAG, "Addon sync error", e);
                    s.fail("Addon sync error: " + e);
                }
            }
        });
    }

    /** Worker thread: fetch every missing file from the host into the addons folder. */
    private void downloadFiles(Session s, HostInfo info, List<Entry> missing) throws IOException {
        String host = (hostOverride != null && hostOverride.length() > 0) ? hostOverride : info.ip;
        if (host == null || host.length() == 0 || info.port <= 0) throw new IOException("Host address unknown");

        File dir = AddonEngineController.getAddonsDir();
        if (!dir.isDirectory()) throw new IOException("Cannot create " + dir.getPath() + " (storage permission?)");
        long total = 0;
        for (Entry e : missing) total += e.size;
        if (dir.getUsableSpace() < total + STORAGE_MARGIN) {
            throw new IOException("Not enough free storage (" + fmtSize(total) + " needed)");
        }
        File[] leftovers = dir.listFiles();                 // *.part files of an interrupted download
        if (leftovers != null) {
            for (File f : leftovers) if (f.getName().endsWith(".part")) f.delete();
        }

        Socket sock = new Socket();
        s.socket = sock;
        DataInputStream in = null;
        DataOutputStream out = null;
        try {
            if (s.finished.get()) throw new IOException("cancelled");
            try {
                sock.connect(new InetSocketAddress(host, info.port), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                throw new IOException("Cannot reach host " + host + ":" + info.port);
            }
            sock.setSoTimeout(SO_TIMEOUT_MS);
            out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
            in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), BUF));

            out.writeInt(REQUEST_MAGIC);
            out.writeShort(PROTOCOL_VERSION);
            out.writeInt(missing.size());
            for (Entry e : missing) {
                out.writeUTF(e.id);
                out.writeUTF(e.sha256);
            }
            out.flush();

            byte[] buf = new byte[BUF];
            long done = 0;
            for (Entry e : missing) {
                int status = in.readUnsignedByte();
                if (status != ST_OK) throw new IOException("Host no longer offers '" + e.id + "' (code " + status + ")");
                long size = in.readLong();
                if (size != e.size) throw new IOException("'" + e.id + "' changed on the host");

                File part = new File(dir, e.fileName + ".part");
                s.partials.add(part);
                FileOutputStream fos = new FileOutputStream(part);
                try {
                    long left = size;
                    while (left > 0) {
                        if (s.finished.get()) throw new IOException("cancelled");
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new EOFException("Host closed the connection");
                        fos.write(buf, 0, n);
                        left -= n;
                        done += n;
                        postProgress(s, e.fileName, done, total);
                    }
                } finally {
                    closeQuietly(fos);
                }

                installDownloaded(e, part);
                s.partials.remove(part);
                Log.i(TAG, "Installed addon " + e.id + " (" + fmtSize(size) + ")");
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(sock);
        }
    }

    /**
     * Unzips a downloaded addon next to the others, checks its content hash against what the host
     * announced, then swaps it in. An older local copy of the same id is replaced.
     */
    private void installDownloaded(Entry e, File zip) throws IOException {
        File root = AddonEngineController.getAddonsDir();
        File tmp = new File(root, ".sync-" + AddonPackager.safeName(e.id));
        AddonPackager.deleteRecursive(tmp);
        try {
            AddonPackager.extractZip(zip, tmp);
            String got = AddonPackager.hashFolder(tmp);
            if (!got.equals(e.sha256)) throw new IOException("Checksum mismatch for '" + e.id + "'");
            File target = null;
            AddonManifest local = owner.getData().getAddon(e.id);
            if (local != null) target = local.getDirectory();
            if (target == null) target = new File(root, AddonPackager.safeName(e.id));
            AddonPackager.deleteRecursive(target);
            if (!tmp.renameTo(target)) throw new IOException("Cannot save addon '" + e.id + "'");
        } finally {
            AddonPackager.deleteRecursive(tmp);
            zip.delete();
        }
    }

    /**
     * Worker thread: rescan (after a download), enable exactly the host's version of every
     * required addon, reload the pipeline, verify they are really active, then finish.
     */
    private void activateAndFinish(Session s, HostInfo info, boolean downloaded) {
        AddonDataManager data = owner.getData();
        if (downloaded) data.rescan();                      // pick up the files we just wrote

        Map<String, String> wanted = new HashMap<String, String>();
        for (Entry e : info.entries) wanted.put(e.id, e.sha256);

        boolean changed = downloaded;
        for (AddonManifest m : data.getManifests()) {
            String sha = wanted.get(m.getId());
            boolean shouldRun = sha != null && sha.equals(m.contentHash());
            // The client runs exactly the host's set (in memory only; its own choices come back on leave).
            if (m.isEnabled() != shouldRun) {
                m.setEnabled(shouldRun);
                changed = true;
            }
        }
        if (changed) owner.reloadNow(false);
        if (s.finished.get()) return;

        for (Entry e : info.entries) {
            if (!isActive(e)) {
                s.fail("Addon '" + e.id + "' could not be activated (conflict with another addon?)");
                return;
            }
        }
        s.succeed();
    }

    private boolean isActive(Entry e) {
        for (AddonManifest m : owner.getActiveAddons()) {
            if (e.id.equals(m.getId()) && e.sha256.equals(m.contentHash())) return true;
        }
        return false;
    }

    private void postProgress(final Session s, final String name, final long done, final long total) {
        long now = System.currentTimeMillis();
        if (done < total && now - s.lastUiUpdate < 100) return;      // throttle UI updates
        s.lastUiUpdate = now;
        final int percent = total > 0 ? (int) (done * 100 / total) : 0;
        ui.post(new Runnable() {
            public void run() {
                ProgressBar b = s.bar;
                TextView t = s.label;
                if (b != null) b.setProgress(percent);
                if (t != null) t.setText(name + "\n" + fmtSize(done) + " / " + fmtSize(total));
            }
        });
    }

    /** Stops everything (host server, client sync, thread pools). The controller creates a new one on init(). */
    public void shutdown() {
        cancelClientSync("Addon sync shut down");
        stopHostServer();
        hostPool.shutdownNow();
        clientExec.shutdownNow();
    }

    // =========================================================================================
    // One client-side sync attempt
    // =========================================================================================

    private final class Session {
        final SyncCallback cb;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final List<File> partials = Collections.synchronizedList(new ArrayList<File>());
        volatile Socket socket;
        volatile AlertDialog confirm, progress;
        volatile TextView label;
        volatile ProgressBar bar;
        long lastUiUpdate;                                  // worker thread only

        Session(SyncCallback cb) { this.cb = cb; }

        void succeed()             { finish(true, null); }
        void fail(String reason)   { finish(false, reason); }

        /** Exactly one outcome per session; cleans up and reports on the UI thread. */
        private void finish(final boolean ok, final String reason) {
            if (!finished.compareAndSet(false, true)) return;
            closeQuietly(socket);                           // unblocks a download in progress
            synchronized (partials) {
                for (File f : partials) f.delete();
                partials.clear();
            }
            synchronized (LANAddonSync.this) {
                if (session == this) session = null;
            }
            ui.post(new Runnable() {
                public void run() {
                    dismiss(confirm);
                    dismiss(progress);
                    if (ok) cb.onSyncComplete();
                    else cb.onSyncAborted(reason);
                }
            });
        }
    }

    private static final class Entry {
        final String id, version, sha256, fileName;
        final long size;
        final File file;                                    // host side only

        Entry(String id, String version, String sha256, String fileName, long size, File file) {
            this.id = nz(id);
            this.version = nz(version);
            this.sha256 = nz(sha256);
            this.fileName = nz(fileName);
            this.size = size;
            this.file = file;
        }
    }

    private static final class HostInfo {
        int port;
        String ip = "";
        final List<Entry> entries = new ArrayList<Entry>();

        static HostInfo parse(byte[] data) throws IOException {
            if (data == null || data.length < 8) throw new IOException("empty manifest");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != PACKET_MAGIC) throw new IOException("bad magic");
            int version = in.readShort();
            if (version != PROTOCOL_VERSION) throw new IOException("unsupported protocol " + version);

            HostInfo h = new HostInfo();
            h.port = in.readInt();
            h.ip = in.readUTF();
            int count = in.readInt();
            if (count < 0 || count > MAX_ADDONS) throw new IOException("bad addon count");
            for (int i = 0; i < count; i++) {
                String id = in.readUTF();
                String ver = in.readUTF();
                String sha = in.readUTF().toLowerCase(Locale.US);
                String fileName = safeFileName(in.readUTF());
                long size = in.readLong();
                if (id.length() == 0 || sha.length() != 64 || size < 0 || size > MAX_FILE_BYTES) {
                    throw new IOException("bad entry");
                }
                h.entries.add(new Entry(id, ver, sha, fileName, size, null));
            }
            return h;
        }
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    private static boolean isSyncableName(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".zip");
    }

    /** Plain *.zip file name only: no directories, no hidden files. */
    private static String safeFileName(String raw) throws IOException {
        String n = new File(raw == null ? "" : raw).getName();
        if (n.length() == 0 || n.startsWith(".") || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0
                || !isSyncableName(n)) {
            throw new IOException("unsafe file name: " + raw);
        }
        return n;
    }

    private static String describe(IOException e) {
        if (e instanceof EOFException || e instanceof SocketException || e instanceof SocketTimeoutException) {
            return "Lost connection to host during download";
        }
        return "Download failed: " + e.getMessage();
    }

    /** Best-effort LAN address of this device (Wi-Fi / hotspot / Wi-Fi Direct preferred). */
    private static String detectLocalIpv4() {
        String fallback = "";
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                boolean lanLike = name.contains("wlan") || name.startsWith("ap")
                        || name.startsWith("eth") || name.startsWith("p2p");
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (lanLike) return a.getHostAddress();
                        if (fallback.length() == 0) fallback = a.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Cannot enumerate network interfaces: " + e.getMessage());
        }
        return fallback;
    }

    private static void dismiss(AlertDialog d) {
        if (d == null) return;
        try {
            if (d.isShowing()) d.dismiss();
        } catch (RuntimeException ignored) {
            // activity already gone
        }
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) { }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) { }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) { }
    }
}
