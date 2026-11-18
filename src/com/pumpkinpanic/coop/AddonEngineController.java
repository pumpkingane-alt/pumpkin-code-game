package com.pumpkinpanic.coop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AddonEngineController - master controller for the Pumpkin Panic addon architecture.
 *
 * BOOT ORDER  scan manifests -> check conflicts -> load textures -> boot scripts -> load scenes
 * THREADING   Boot/reload runs on a worker thread. The game hooks are no-ops until boot has
 *             finished and are fenced off while a reload runs, so they are safe to call every
 *             frame. If boot throws, the game falls back to running with no addons.
 * GAME HOOKS  (all static, all cheap when no addon is active)
 *               onUpdate(dt)  onPlayerMove(x, y)  onEntityMove(id, x, y)  fire(event, data)
 *               sprite(key)  spriteFrames(key)  playOverride(key, vol)  sceneBlocked(x, y, r)
 *               scene()  itemCount() itemId(i) itemName(i) itemPrice(i)
 * LAN         Host: getActiveAddonsManifestPacket(), startLanHostServer(), stopLanHostServer()
 *             Client: setLanHostAddress(), verifyHostAddons(), onHostDisconnected()
 *
 * Java 7 syntax only (no lambdas, no try-with-resources) so it builds in AIDE.
 */
public final class AddonEngineController {

    public static final String TAG = "AddonEngine";

    /** Boot / reload progress. Both callbacks arrive on the main thread. */
    public interface BootListener {
        void onStage(String stage);
        void onFinished(boolean ok, int activeAddons);
    }

    /** One shop item registered by a script through createItem(). */
    public static final class AddonItem {
        public final String id, name;
        public final int price;
        AddonItem(String id, String name, int price) { this.id = id; this.name = name; this.price = price; }
    }

    public static final int MAX_ITEMS = 99;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int ERROR_LOG_LIMIT = 20;
    private static volatile AddonEngineController INSTANCE;
    private static volatile Context APP;
    private static volatile AddonScriptSandbox.GameBridge BRIDGE;
    private static final List<AddonItem> ITEMS = new ArrayList<AddonItem>();

    // ---- subsystems ------------------------------------------------------------------------
    private volatile AddonDataManager data;
    private AddonConflictResolver resolver;
    private volatile AddonAssetPipeline assets;
    private AddonEventBus bus;
    private AddonScriptSandbox scripts;
    private volatile AddonSceneEngine scenes;
    private volatile LANAddonSync lan;

    // ---- state -----------------------------------------------------------------------------
    private final ReentrantLock engineLock = new ReentrantLock();   // hooks vs. (re)boot
    private final AtomicInteger bootsRunning = new AtomicInteger();
    private ExecutorService bootExec;
    private volatile WeakReference<Activity> activityRef;
    private volatile List<AddonManifest> active = Collections.emptyList();
    private volatile boolean initialized;
    private volatile boolean ready;          // game hooks are live
    private int hookErrors;                  // guarded by engineLock

    private AddonEngineController() { }

    public static AddonEngineController get() {
        AddonEngineController c = INSTANCE;
        if (c == null) {
            synchronized (AddonEngineController.class) {
                c = INSTANCE;
                if (c == null) {
                    c = new AddonEngineController();
                    INSTANCE = c;
                }
            }
        }
        return c;
    }

    // =========================================================================================
    // Lifecycle
    // =========================================================================================

    /**
     * Creates the subsystems and starts the boot pipeline on a worker thread.
     * Call from MainActivity once the storage permission question is settled. Calling it again
     * (e.g. after the activity was re-created) only re-attaches the activity.
     */
    public synchronized void init(Activity activity, BootListener listener) {
        attachActivity(activity);
        if (initialized) {
            if (ready) notifyFinished(listener, true, active.size());
            return;
        }
        Context app = activity.getApplicationContext();
        APP = app;
        try {
            File dir = getAddonsDir();
            data     = new AddonDataManager(app, dir);
            resolver = new AddonConflictResolver();
            assets   = new AddonAssetPipeline(app);
            bus      = new AddonEventBus();
            scenes   = new AddonSceneEngine(app, bus);
            scripts  = new AddonScriptSandbox(app, bus, assets, scenes);
            lan      = new LANAddonSync(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not construct addon subsystems", e);
            notifyFinished(listener, false, 0);
            return;
        }
        bootExec = Executors.newSingleThreadExecutor();
        initialized = true;
        submitBoot(true, listener);
    }

    /** Call from MainActivity.onDestroy when isFinishing(). */
    public synchronized void shutdown() {
        if (!initialized) return;
        initialized = false;
        ready = false;
        lan.shutdown();
        bootExec.execute(new Runnable() {
            public void run() {
                engineLock.lock();
                try {
                    teardown();
                    ready = false;
                    active = Collections.emptyList();
                } finally {
                    engineLock.unlock();
                }
            }
        });
        bootExec.shutdown();     // queued boots + the teardown above still run, then the thread exits
        INSTANCE = null;         // a later get() builds a fresh controller; hooks are no-ops until then
    }

    public void attachActivity(Activity a) {
        activityRef = new WeakReference<Activity>(a);
    }

    public void detachActivity(Activity a) {
        WeakReference<Activity> r = activityRef;
        if (r != null && r.get() == a) activityRef = null;
    }

    /** Activity used for dialogs (may be null while the app is in the background). */
    public Activity getActivity() {
        WeakReference<Activity> r = activityRef;
        return r == null ? null : r.get();
    }

    /**
     * Where addon folders live: /sdcard/PumpkinPanic/addons/ when this phone lets the app use it,
     * otherwise the app's own external folder (Android/data/<package>/files/addons), which needs
     * no permission. Created on demand.
     */
    public static File getAddonsDir() {
        File shared = new File(Environment.getExternalStorageDirectory(), "PumpkinPanic/addons");
        if (shared.isDirectory() || shared.mkdirs()) {
            if (shared.canRead() && shared.listFiles() != null) return shared;
        }
        Context app = APP;
        if (app != null) {
            File own = app.getExternalFilesDir(null);
            if (own != null) {
                File dir = new File(own, "addons");
                if (dir.isDirectory() || dir.mkdirs()) return dir;
            }
            return new File(app.getFilesDir(), "addons");
        }
        Log.w(TAG, "Cannot create " + shared.getPath() + " (storage permission?)");
        return shared;
    }

    public boolean isReady()                     { return ready; }
    public boolean isBusy()                      { return bootsRunning.get() > 0; }
    public List<AddonManifest> getActiveAddons() { return active; }
    public AddonDataManager  getData()           { return data; }
    public AddonAssetPipeline getAssets()        { return assets; }
    public AddonSceneEngine  getScenes()         { return scenes; }
    public AddonScriptSandbox getScripts()       { return scripts; }
    public AddonEventBus     getBus()            { return bus; }

    /** Number of addons currently running (0 until the first boot has finished). */
    public static int activeCount() {
        AddonEngineController c = INSTANCE;
        return c == null || !c.ready ? 0 : c.active.size();
    }

    // =========================================================================================
    // Boot / reload
    // =========================================================================================

    /** Re-runs the pipeline on the worker thread (e.g. after the player changed the selection). */
    public void reloadAsync(BootListener listener) {
        submitBoot(true, listener);
    }

    /**
     * Blocking reload for BACKGROUND threads (used by LANAddonSync). Never call it from the UI
     * thread, the game loop or an addon hook.
     *
     * @param rescan true = re-read mod_info.json files (and the saved on/off choices) first,
     *               false = keep the manifest list exactly as it is in memory
     * @return false if boot failed and the game fell back to running without addons
     */
    public boolean reloadNow(boolean rescan) {
        return runBoot(rescan, null);
    }

    /** After leaving a LAN game: go back to the addons the player chose, not the host's set. */
    public void restoreUserSelection() {
        submitBoot(true, null);
    }

    private void submitBoot(final boolean rescan, final BootListener listener) {
        ExecutorService ex = bootExec;
        if (ex == null) return;
        try {
            ex.execute(new Runnable() {
                public void run() { runBoot(rescan, listener); }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Boot request ignored: controller is shut down");
        }
    }

    private boolean runBoot(boolean rescan, BootListener listener) {
        bootsRunning.incrementAndGet();
        ready = false;                       // fence the hooks off before waiting for the lock
        engineLock.lock();
        try {
            ready = false;
            teardown();
            List<AddonManifest> approved = bootSequence(rescan, listener);
            active = Collections.unmodifiableList(approved);
            hookErrors = 0;
            ready = true;

            EventData d = new EventData();
            d.put("count", approved.size());
            postEvent(AddonEventBus.ON_ADDONS_READY, d);
            notifyFinished(listener, true, approved.size());
            notifyConflicts();
            return true;
        } catch (Throwable t) {              // incl. OutOfMemoryError from huge texture packs
            Log.e(TAG, "Addon boot failed - continuing without addons", t);
            teardown();
            active = Collections.emptyList();
            notifyFinished(listener, false, 0);
            return false;
        } finally {
            engineLock.unlock();
            bootsRunning.decrementAndGet();
        }
    }

    /** The five boot steps, in order. Runs under engineLock on a worker thread. */
    private List<AddonManifest> bootSequence(boolean rescan, BootListener l) {
        stage(l, "Scanning addon manifests");                           // 1. manifests
        if (rescan) data.rescan();
        List<AddonManifest> enabled = new ArrayList<AddonManifest>();
        for (AddonManifest m : data.getManifests()) {
            if (m.isEnabled()) enabled.add(m);
        }

        stage(l, "Checking addon conflicts");                           // 2. conflicts
        List<AddonManifest> approved = new ArrayList<AddonManifest>(resolver.resolve(enabled));
        for (String msg : resolver.getMessages()) Log.w(TAG, "Conflict: " + msg);

        stage(l, "Loading textures");                                   // 3. textures, sprite
        assets.loadAssets(approved);                                    //    sheets, sounds, icons

        stage(l, "Booting scripts");                                    // 4. Luaj sandbox
        scripts.boot(approved);

        stage(l, "Loading scenes");                                     // 5. tilemaps, collision,
        scenes.loadScenes(approved);                                    //    spawns, trigger zones

        return approved;
    }

    /** Reverse boot order; every step is isolated so one failure cannot block the rest. */
    private void teardown() {
        try { if (scenes != null)  scenes.release();    } catch (Throwable t) { Log.w(TAG, "scenes.release failed", t); }
        try { if (scripts != null) scripts.shutdown();  } catch (Throwable t) { Log.w(TAG, "scripts.shutdown failed", t); }
        try { if (assets != null)  assets.release();    } catch (Throwable t) { Log.w(TAG, "assets.release failed", t); }
        synchronized (ITEMS) { ITEMS.clear(); }
    }

    /** Tells the player about conflicts found by the boot that just finished. */
    private void notifyConflicts() {
        final List<ConflictWarning> warnings = resolver.getWarnings();
        if (warnings.isEmpty()) return;
        MAIN.post(new Runnable() {
            public void run() {
                Activity a = getActivity();
                if (a != null && !a.isFinishing()) resolver.resolveAndNotify(a, warnings);
            }
        });
    }

    private static void stage(final BootListener l, final String name) {
        Log.i(TAG, "Boot: " + name);
        if (l == null) return;
        MAIN.post(new Runnable() { public void run() { l.onStage(name); } });
    }

    private static void notifyFinished(final BootListener l, final boolean ok, final int count) {
        if (l == null) return;
        MAIN.post(new Runnable() { public void run() { l.onFinished(ok, count); } });
    }

    // =========================================================================================
    // Game hooks (static - call from anywhere in the game)
    // =========================================================================================

    /** Registered by GameView: how Lua scripts reach into the game. */
    public static void setGameBridge(AddonScriptSandbox.GameBridge bridge) { BRIDGE = bridge; }
    public static AddonScriptSandbox.GameBridge getGameBridge() { return BRIDGE; }

    /** Once per frame from the game loop. */
    public static void onUpdate(float delta) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.enter()) return;
        try {
            c.scenes.update(delta);
            EventData d = new EventData();
            d.put("delta", delta);
            c.postEvent(AddonEventBus.ON_UPDATE, d);
        } catch (RuntimeException e) {
            c.hookError(AddonEventBus.ON_UPDATE, e);
        } finally {
            c.leave();
        }
    }

    /** The local player moved (script event only). */
    public static void onPlayerMove(float x, float y) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.enter()) return;
        try {
            EventData d = new EventData();
            d.put("x", x);
            d.put("y", y);
            c.postEvent(AddonEventBus.ON_PLAYER_MOVE, d);
        } catch (RuntimeException e) {
            c.hookError(AddonEventBus.ON_PLAYER_MOVE, e);
        } finally {
            c.leave();
        }
    }

    /** Host/solo only: position of any player, drives scene trigger zones. */
    public static void onEntityMove(String entityId, float x, float y) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.enter()) return;
        try {
            if (c.scenes.isActive()) c.scenes.updateTriggers(entityId, x, y, 28f, 28f);
        } catch (RuntimeException e) {
            c.hookError("entityMove", e);
        } finally {
            c.leave();
        }
    }

    /** Posts any event to the addon scripts. Returns false if a script canceled it. */
    public static boolean fire(String event, EventData data) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.enter()) return true;
        try {
            return c.bus.post(event, data);
        } catch (RuntimeException e) {
            c.hookError(event, e);
            return true;
        } finally {
            c.leave();
        }
    }

    /** Opens the in-game addon selector. Safe from any thread. Applying changes triggers a reload. */
    public static void openAddonMenu(final Context context) {
        MAIN.post(new Runnable() {
            public void run() {
                final AddonEngineController c = INSTANCE;
                if (c == null || !c.initialized) { toast(context, "Addon system is not initialised"); return; }
                if (c.isBusy()) { toast(context, "Addons are still loading..."); return; }
                if (!(context instanceof Activity)) return;
                try {
                    new AddonManagerDialog((Activity) context).show();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Addon menu failed", e);
                }
            }
        });
    }

    // ---- rendering / audio / collision helpers (never block: they check `ready` first) --------

    /** Addon sprite for a key, or null (then the game draws its own art). */
    public static Bitmap sprite(String key) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.ready) return null;
        AddonAssetPipeline a = c.assets;
        return a == null ? null : a.getSprite(key);
    }

    /** Animation frames for a key (a single frame for a normal PNG), or null. */
    public static Bitmap[] spriteFrames(String key) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.ready) return null;
        AddonAssetPipeline a = c.assets;
        return a == null ? null : a.getFrames(key);
    }

    /** Plays an addon sound for this key. True = an addon supplied it (skip the built-in sound). */
    public static boolean playOverride(String key, float volume) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.ready) return false;
        AddonAssetPipeline a = c.assets;
        return a != null && a.playSound(key, volume);
    }

    /** Scene collision test for the game's movement code. */
    public static boolean sceneBlocked(float x, float y, float r) {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.ready) return false;
        AddonSceneEngine s = c.scenes;
        return s != null && s.isActive() && s.collides(x, y, r);
    }

    /** Current scene (id is "" when none). Null before the first boot. */
    public static AddonSceneEngine.Scene scene() {
        AddonEngineController c = INSTANCE;
        if (c == null || !c.ready) return null;
        AddonSceneEngine s = c.scenes;
        return s == null || !s.isActive() ? null : s.getScene();
    }

    // ---- shop items created by scripts ---------------------------------------------------------

    /** Called by the game's GameBridge.createItem(). Same id twice replaces the earlier item. */
    public static void registerItem(String id, String name, int price) {
        if (id == null || id.length() == 0 || price < 0) return;
        synchronized (ITEMS) {
            for (int i = 0; i < ITEMS.size(); i++) {
                if (ITEMS.get(i).id.equals(id)) { ITEMS.set(i, new AddonItem(id, name, price)); return; }
            }
            if (ITEMS.size() < MAX_ITEMS) ITEMS.add(new AddonItem(id, name, price));
        }
    }

    public static int itemCount() { synchronized (ITEMS) { return ITEMS.size(); } }

    /** Null when the index is out of range. */
    public static AddonItem item(int index) {
        synchronized (ITEMS) { return index >= 0 && index < ITEMS.size() ? ITEMS.get(index) : null; }
    }

    // ---- internals -------------------------------------------------------------------------

    /** Takes the engine lock for one hook call; never stalls the game loop for a reload. */
    private boolean enter() {
        if (!ready) return false;
        try {
            if (!engineLock.tryLock(25, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (ready) return true;
        engineLock.unlock();
        return false;
    }

    private void leave() {
        engineLock.unlock();
    }

    private void postEvent(String event, EventData d) {
        try {
            bus.post(event, d);
        } catch (RuntimeException e) {
            hookError(event, e);
        }
    }

    private void hookError(String hook, Throwable t) {
        if (hookErrors++ < ERROR_LOG_LIMIT) Log.e(TAG, "Addon hook '" + hook + "' failed", t);
    }

    private static void toast(Context c, String msg) {
        if (c != null) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    // =========================================================================================
    // LAN multiplayer sync (implementation lives in LANAddonSync)
    // =========================================================================================

    /**
     * HOST: serialises the id, version, content hash, file name and size of every active addon.
     * Send the bytes to a joining client during your handshake. Starts the file server on first
     * use. Call from a background/network thread (addons are hashed and zipped on the first call).
     */
    public byte[] getActiveAddonsManifestPacket() {
        LANAddonSync l = lan;
        return l == null ? new byte[0] : l.getActiveAddonsManifestPacket();
    }

    /** HOST: start / stop the thread that streams active addon files to joining clients. */
    public void startLanHostServer() { LANAddonSync l = lan; if (l != null) l.startHostServer(); }
    public void stopLanHostServer()  { LANAddonSync l = lan; if (l != null) l.stopHostServer(); }

    /** CLIENT: IP your client socket connected to (the file download connects to the same host). */
    public void setLanHostAddress(String hostIp) {
        LANAddonSync l = lan;
        if (l != null) l.setHostAddress(hostIp);
    }

    /**
     * CLIENT: check the host's manifest packet against local addons. If any are missing it asks
     * "Host requires active addons to join. Download required files from Host?"; YES downloads
     * them into the addons folder, rescans and completes the join, NO aborts. The client then runs
     * exactly the host's addon set until it leaves the game. The callback runs on the UI thread.
     */
    public void verifyHostAddons(byte[] hostManifestData, final LANAddonSync.SyncCallback callback) {
        LANAddonSync l = lan;
        if (l == null) {
            MAIN.post(new Runnable() {
                public void run() { callback.onSyncAborted("Addon system is not initialised"); }
            });
            return;
        }
        l.verifyHostAddons(hostManifestData, callback);
    }

    /** CLIENT: call when the host connection drops mid-join; cancels the sync and aborts the join. */
    public void onHostDisconnected() {
        LANAddonSync l = lan;
        if (l != null) l.cancelClientSync("Disconnected from host");
    }
}
