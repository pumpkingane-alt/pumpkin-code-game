package com.pumpkinpanic.coop;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

/** Game controller: input, screens, LAN protocol, fx and audio mapping. Drawing lives in Hud. */
final class GameView extends View implements Choreographer.FrameCallback {
    interface Cb { void done(String text); }
    interface Host {
        void prompt(String title, String def, Cb cb); void toast(String s); void vibrate(int ms); void saveName(String n); void quit();
        void openEditor();                          // show the code editor dialog
        String loadPref(String key, String def);    // small settings (auto-run, active script ...)
        void savePref(String key, String value);
    }

    static final int S_TITLE = 0, S_JOIN = 1, S_LOBBY = 2, S_PLAY = 3, S_HELP = 4;
    static final int B_SOLO = 1, B_HOST = 2, B_JOIN = 3, B_HELP = 4, B_NAME = 5, B_SOUND = 6, B_BACK = 7, B_IP = 8, B_BOT = 9, B_DIFF = 10, B_START = 11,
        B_LEAVE = 12, B_PAUSE = 13, B_RESUME = 14, B_AGAIN = 16, B_ADDONS = 17, B_MODE = 18, B_CODE = 19, B_RUNBTN = 20, B_CONSOLE = 21, B_AUTORUN = 22, B_FOUND = 400, B_KICK = 500;
    static final float STEP = 1f / 60f;

    final Host host;
    World w = new World();
    final Net net = new Net();
    final Sfx sfx = new Sfx();
    final Art art = new Art();
    final Hud hud;

    int screen = S_TITLE, me, panel, lobbyDiff;
    boolean lobbyEndless = true;
    boolean isHost, isClient, solo, gotSnap, paused, connected;
    String myName = "Pumpkin";
    String msg = ""; float msgT, cueT; String cue = "";
    float time, camX = 1100, camY = 700, shakeT, shakeA, flashT, roofA = 1f, doorA, acc, snapAcc, inAcc, infoAcc, lastDark, zoom = 1f;
    int flashCol;
    float sw = 1920, sh = 1080, u = 2f;
    boolean soundOn = true;
    volatile boolean groundReady;

    // script control (the only way to control the character)
    final ScriptHost scripts = new ScriptHost(new ScriptHost.Link() {
        public World world() { return w; }
        public World.Player me() { return lp(); }
        public boolean authority() { return w.authority; }
        public void cmdAct() { net.sendHost(Net.cmd(Net.C_ACT, 0, 0)); }
        public void cmdBuy(int item) { net.sendHost(Net.cmd(Net.C_BUY, item, 0)); }
        public void cmdSel(int s) { net.sendHost(Net.cmd(Net.C_SEL, s, 0)); }
        public void cmdEmote(int e) { net.sendHost(Net.cmd(Net.C_EMOTE, e, 0)); }
        public void onStatus(int status, ScriptError err) { scriptStatusChanged(status, err); }
    });
    ScriptStore store;
    String activeScript = "main";
    boolean autoRun = true, consoleBig, editorOn, pausedByEditor;
    Runnable scriptListener;                 // the code editor listens for status changes
    float lastErrSfx;
    int lastIsland = 1;
    // ui hit boxes (rebuilt every draw)
    final float[] hx = new float[96], hy = new float[96], hw = new float[96], hh = new float[96]; final int[] hid = new int[96]; int nh;
    final int[] ptrHit = new int[32];
    int pressId = -1;
    // fx
    static final int NPA = 280, NFT = 20;
    final float[] pX = new float[NPA], pY = new float[NPA], pVX = new float[NPA], pVY = new float[NPA], pL = new float[NPA], pM = new float[NPA], pS = new float[NPA];
    final int[] pC = new int[NPA], pK = new int[NPA];
    int pHead;
    final float[] fX = new float[NFT], fY = new float[NFT], fL = new float[NFT]; final String[] fT = new String[NFT]; final int[] fC = new int[NFT]; int fHead;
    float leafT, smokeT, stepT, winFx;
    long lastNs;
    boolean running;

    GameView(Context ctx, Host h) {
        super(ctx);
        host = h; hud = new Hud(this);
        setFocusable(true); setFocusableInTouchMode(true); setKeepScreenOn(true);
        net.init(ctx);
        new Thread(new Runnable() { public void run() { Art ga = new Art(); ga.buildGround(); for (int i = 0; i < 4; i++) art.ground[i] = ga.ground[i]; groundReady = true; } }, "pp-ground").start();
        for (int i = 0; i < NFT; i++) fT[i] = "";
    }

    // ------------------------------------------------------------ lifecycle
    void resume() { running = true; lastNs = 0; if (soundOn) sfx.start(); Choreographer.getInstance().postFrameCallback(this); }
    void pause() { running = false; sfx.stop(); Choreographer.getInstance().removeFrameCallback(this); }
    void destroy() { scripts.stop(false); pause(); net.release(); if (AddonEngineController.getGameBridge() == bridge) AddonEngineController.setGameBridge(null); }
    @Override protected void onSizeChanged(int nw, int nh2, int ow, int oh) { setSize(nw, nh2); }
    void setSize(float nw, float nh2) {
        sw = nw; sh = nh2; u = Math.min(sw, sh) / 540f;
        hud.resize();
    }
    public void doFrame(long ns) {
        if (!running) return;
        float dt = lastNs == 0 ? 0.016f : (ns - lastNs) / 1e9f;
        lastNs = ns;
        if (dt > 0.1f) dt = 0.1f; if (dt < 0f) dt = 0f;
        tick(dt);
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onDraw(Canvas c) { hud.draw(c); }

    World.Player lp() { return w.pl[Math.max(0, me)]; }
    void say(String s) { msg = s; msgT = 3.2f; }

    // ------------------------------------------------------------ sessions
    void newSession(boolean hosting) {
        scripts.stop(false); scripts.clearLog();
        w = new World(); w.authority = hosting; w.mySlot = hosting ? 0 : -1; me = hosting ? 0 : -1; gotSnap = hosting; connected = hosting; paused = false; panel = 0; pausedByEditor = false;
        if (hosting) { World.Player p = w.pl[0]; p.active = true; p.name = myName; p.hat = hatPref; p.hatMask = 1 | (1 << hatPref); }
        w.phase = World.PH_LOBBY; lobbyDiff = 0;
        clearFx();
        AddonEngineController.setGameBridge(bridge);
    }
    int hatPref;
    void startSolo() { net.stop(); newSession(true); isHost = true; isClient = false; solo = true; w.netOn = false; screen = S_LOBBY; sfx.play(Sfx.S_CLICK, 1, 0); }
    void hostGame() {
        if (!net.host(myName)) { say(net.status); return; }
        new Thread(new Runnable() { public void run() { AddonEngineController.get().startLanHostServer(); } }, "pp-addon-srv").start();
        newSession(true); isHost = true; isClient = false; solo = false; w.netOn = true; screen = S_LOBBY; net.hostPlayers = 1; net.hostPhase = 0; net.hostDiff = 0;
    }
    void openJoin() { net.stop(); isHost = false; isClient = false; screen = S_JOIN; net.startListen(); net.status = ""; }
    void joinGame(String ip) {
        ip = ip.trim();
        if (ip.length() == 0) return;
        net.stopListen();
        joinIp = ip; addonWait = false;
        net.connect(ip, myName);
        newSession(false); isHost = false; isClient = true; solo = false; screen = S_LOBBY; joinT = 0;
    }
    // ------------------------------------------------------------ addons
    static final String[] PID = {"player0", "player1", "player2", "player3"};
    volatile boolean addonWait;      // client: downloading / checking the host's addons
    boolean addonsForced;            // client: running the host's addon set until we leave
    String joinIp = "";

    /** What addon Lua scripts may do to the game. Runs on the game thread; changes only apply on the host / solo player. */
    final AddonScriptSandbox.GameBridge bridge = new AddonScriptSandbox.GameBridge() {
        public void spawnMonster(final String kind, final float x, final float y) {
            post(new Runnable() { public void run() { if (w.authority && w.phase == World.PH_PLAY) Mobs.forceSpawn(w, kind, x, y); } });
        }
        public void changeScene(final String sceneId) {
            post(new Runnable() { public void run() { AddonSceneEngine se = AddonEngineController.get().getScenes(); if (se != null) se.loadScene(sceneId); } });
        }
        public void modifyPlayerStat(final String stat, final float v) {
            post(new Runnable() { public void run() { if (w.authority && w.phase == World.PH_PLAY) w.addonStat(stat, v); } });
        }
        public void createItem(String id, String name, int price) {
            AddonEngineController.registerItem(id, name, price);     // thread-safe; the shop reads it on the game thread
        }
    };

    /** Turns game events into addon script events (runs on every device, host and clients alike). */
    void addonEvent(int t, float x, float y, int a) {
        String ev = null, kind = null, item = null;
        EventData d = new EventData();
        d.setPosition(x, y);
        switch (t) {
            case World.EV_HARVEST: ev = AddonEventBus.ON_CROP_HARVEST; d.put("crop", Data.CROP[a % 5].toLowerCase()); break;
            case World.EV_WATER: ev = AddonEventBus.ON_CROP_WATER; break;
            case World.EV_DUSK: ev = AddonEventBus.ON_NIGHT_FALL; break;
            case World.EV_SKIN_ARRIVE: ev = AddonEventBus.ON_MONSTER_SPAWN; kind = "skinwalker"; break;
            case World.EV_DEMON: ev = AddonEventBus.ON_MONSTER_SPAWN; kind = "demon"; break;
            case World.EV_CLOWN: ev = AddonEventBus.ON_MONSTER_SPAWN; kind = "clown"; break;
            case World.EV_CLOWN_BLUE: ev = AddonEventBus.ON_MONSTER_SPAWN; kind = "clown_blue"; break;
            case World.EV_STAG: ev = AddonEventBus.ON_MONSTER_SPAWN; kind = "stag"; break;
            case World.EV_ROAR: ev = AddonEventBus.ON_MONSTER_SPAWN; kind = "wendigo"; break;
            case World.EV_CAST: ev = AddonEventBus.ON_ITEM_USE; item = "fishing_rod"; break;
            case World.EV_SAW: ev = AddonEventBus.ON_ITEM_USE; item = "saw"; break;
            case World.EV_OIL: ev = AddonEventBus.ON_ITEM_USE; item = "oil"; break;
            case World.EV_WELL: ev = AddonEventBus.ON_ITEM_USE; item = "watering_can"; break;
            case World.EV_ADDON_ITEM: {
                AddonEngineController.AddonItem it = AddonEngineController.item(a % 100);
                if (it == null) return;
                ev = AddonEventBus.ON_ITEM_BUY; d.put("itemId", it.id); d.put("slot", a / 100);
                break;
            }
            default: return;
        }
        if (ev == AddonEventBus.ON_CROP_HARVEST || ev == AddonEventBus.ON_CROP_WATER) { d.put("tileX", (int) (x / Data.TILE)); d.put("tileY", (int) (y / Data.TILE)); }
        if (kind != null) d.put("kind", kind);
        if (item != null) d.put("item", item);
        AddonEngineController.fire(ev, d);
    }

    void addonTick(float dt) {
        AddonEngineController.onUpdate(dt);
        World.Player me2 = lp();
        if (me2.moving && me2.state == World.ST_ALIVE) AddonEngineController.onPlayerMove(me2.x, me2.y);
        if (w.authority && w.phase == World.PH_PLAY && AddonEngineController.scene() != null) {
            for (int i = 0; i < World.NP; i++) { World.Player q = w.pl[i]; if (q.active && q.state == World.ST_ALIVE) AddonEngineController.onEntityMove(PID[i], q.x, q.y); }
        }
    }

    /** Host: send the joining player our addon list (hashing/zipping can take a moment, so off the game thread). */
    void sendAddonManifest(final int slot) {
        new Thread(new Runnable() {
            public void run() {
                byte[] pk = AddonEngineController.get().getActiveAddonsManifestPacket();
                net.sendTo(slot, Net.frame(Net.T_ADDONS, pk));
            }
        }, "pp-addon-manifest").start();
    }

    /** Client: compare with the host's addons, offer to download what is missing. */
    void onHostAddons(byte[] pk) {
        addonWait = true; addonsForced = true; net.status = "Syncing addons...";
        AddonEngineController c = AddonEngineController.get();
        c.setLanHostAddress(joinIp);
        c.verifyHostAddons(pk, new LANAddonSync.SyncCallback() {
            public void onSyncComplete() { addonWait = false; say("Addons ready"); }
            public void onSyncAborted(String reason) { addonWait = false; say(reason); leaveGame(); }
        });
    }

    float joinT;
    void leaveGame() {
        if (addonWait) AddonEngineController.get().onHostDisconnected();
        addonWait = false;
        AddonEngineController.get().stopLanHostServer();
        if (addonsForced) { addonsForced = false; AddonEngineController.get().restoreUserSelection(); }
        scripts.stop(false);
        net.stop(); sfx.silence(); sfx.setMuffle(0);
        isHost = isClient = solo = false; screen = S_TITLE; panel = 0; paused = false;
        newSession(false); w.authority = true; w.mySlot = 0; me = 0; gotSnap = false;
    }
    void startRun() {
        if (!w.authority || w.activeCount() < 1) return;
        w.netOn = !solo;
        w.reset(lobbyDiff, lobbyEndless);
        clearFx(); screen = S_PLAY; panel = 0; paused = false; camX = lp().x; camY = lp().y;
        sfx.setEnd(true); sfx.play(Sfx.S_DUSK, 0.6f, 0);
        lastIsland = 1; scripts.clearLog(); startAutoScript();
    }
    void backToLobby() { if (!w.authority) return; scripts.stop(false); w.phase = World.PH_LOBBY; screen = S_LOBBY; panel = 0; w.local.n = 0; }
    void addBot() {
        if (!w.authority || w.phase != World.PH_LOBBY) return;
        for (int i = 1; i < World.NP; i++) {
            World.Player p = w.pl[i];
            if (!p.active && !net.hasConn(i)) { p.active = true; p.bot = true; p.remote = false; p.name = "Bot " + i; p.hat = i + 1; return; }
        }
        say("Game is full");
    }
    void clearFx() { for (int i = 0; i < NPA; i++) pL[i] = 0; for (int i = 0; i < NFT; i++) fL[i] = 0; w.local.n = 0; cueT = 0; shakeT = 0; flashT = 0; }

    // ------------------------------------------------------------ main tick
    void tick(float dt) {
        time += dt;
        if (msgT > 0) msgT -= dt; if (cueT > 0) cueT -= dt; if (shakeT > 0) shakeT -= dt; if (flashT > 0) flashT -= dt;
        pollNet();
        if (isClient && screen == S_LOBBY && !gotSnap && !addonWait) { joinT += dt; if (joinT > 9f) { say("No answer from host"); leaveGame(); return; } }
        boolean live = screen == S_PLAY && w.phase == World.PH_PLAY;
        if (!live && scripts.isRunning()) scripts.stop(true);           // the run ended: the script ends with it
        if (live && !paused) { scripts.update(dt); controlLocal(dt); }
        if (live && w.island != lastIsland) { lastIsland = w.island; if (scripts.isRunning()) scripts.log(ScriptHost.LOG_INFO, "island " + w.island + " (danger x" + ((int) (w.danger() * 100)) / 100f + ")"); }
        if (w.authority && (screen == S_PLAY || screen == S_LOBBY) && !paused) {
            acc += dt; if (acc > 0.25f) acc = 0.25f;
            while (acc >= STEP) { w.update(STEP); acc -= STEP; }
        }
        if (!paused) w.smooth(dt);
        if (screen == S_PLAY && !paused) addonTick(dt);
        netSend(dt);
        drainEvents();
        updateFx(dt);
        camera(dt);
        audio(dt);
    }

    void controlLocal(float dt) {
        World.Player p = lp();
        ScriptPilot pi = scripts.pilot;
        pi.frame(dt);                                   // the running script decides where to go and what to do
        if (p.state == World.ST_ALIVE) {
            w.movePlayer(p, pi.ix, pi.iy, pi.run, dt);
            p.lampRaise = pi.lamp && p.oil > 0;
            p.waterHeld = pi.water;
            p.actHeld = pi.actHold;
            if (!w.authority && p.moving) p.animT += dt;
        } else { p.moving = false; p.sprint = false; p.lampRaise = false; p.waterHeld = false; p.actHeld = false; }
        p.inside = World.inCabin(p.x, p.y);
        if (p.moving && p.state == World.ST_ALIVE) {
            stepT -= dt;
            if (stepT <= 0) { stepT = p.sprint ? 0.2f : 0.3f; sfx.play(p.inside ? Sfx.S_STEPW : Sfx.S_STEP, 0.35f, 0); spawn(p.x, p.y - 2, (float) (Math.random() - 0.5) * 20, -8, 0.45f, 3f, 0x66C8B890, 0); }
        }
    }

    // ------------------------------------------------------------ scripts
    /** The script system reports: started / finished / stopped / error. */
    void scriptStatusChanged(int status, ScriptError err) {
        if (status == ScriptHost.ERROR && err != null) {
            say("Script error" + (err.line > 0 ? " on line " + err.line : "") + ": " + err.type);
            if (time - lastErrSfx > 1f) { lastErrSfx = time; sfx.play(Sfx.S_ERR, 0.8f, 0); }
            consoleBig = true;
        }
        if (scriptListener != null) { try { scriptListener.run(); } catch (Throwable t) { /* editor closed */ } }
    }

    /** Start the saved script of this player when a run begins (if auto-run is on). */
    void startAutoScript() {
        scripts.stop(false);
        if (!autoRun || store == null) return;
        String src = store.read(activeScript);
        if (src == null) return;
        scripts.loader = store;
        scripts.start(src, activeScript);
    }

    /** Run this source text (used by the editor's RUN button). Returns the problems found (empty = running). */
    java.util.ArrayList<ScriptError> runSource(String src, String name) {
        scripts.loader = store;
        if (screen != S_PLAY || w.phase != World.PH_PLAY) {
            java.util.ArrayList<ScriptError> probs = scripts.check(src, name);
            say("Start a run first - then press RUN");
            return probs;
        }
        return scripts.start(src, name);
    }
    void stopScript() { scripts.stop(true); }

    void editorOpened() {
        editorOn = true;
        if (solo && screen == S_PLAY && !paused) { paused = true; pausedByEditor = true; }
    }
    void editorClosed() {
        editorOn = false;
        if (pausedByEditor) { pausedByEditor = false; paused = solo && panel == 3; }
    }

    // ------------------------------------------------------------ networking
    void netSend(float dt) {
        if (isHost && !solo) {
            infoAcc += dt;
            if (infoAcc > 0.5f) { infoAcc = 0; net.hostPlayers = w.activeCount(); net.hostDiff = w.phase == World.PH_LOBBY ? lobbyDiff : w.diff; net.hostPhase = w.phase; }
            snapAcc += dt;
            if (snapAcc >= 0.05f) {
                snapAcc = 0;
                if (net.anyClient()) { net.toAll(Net.snap(w)); } else w.net.n = 0;
            }
        } else if (isClient && net.joined) {
            inAcc += dt;
            if (inAcc >= 0.05f && me >= 0) { inAcc = 0; net.sendHost(Net.input(lp())); }
        }
    }

    private static DataInputStream stream(byte[] d) { return new DataInputStream(new ByteArrayInputStream(d, 1, d.length - 1)); }
    private static String clean(String s) { s = s.replaceAll("[^A-Za-z0-9 _.\\-]", "").trim(); if (s.length() > 12) s = s.substring(0, 12); return s.length() == 0 ? "Player" : s; }

    void pollNet() {
        Net.Msg m;
        while ((m = net.inbox.poll()) != null) {
            byte[] d = m.d;
            if (d.length == 0) continue;
            try {
                if (isHost && !solo) hostMsg(m.slot, d[0], d); else if (isClient) clientMsg(d[0], d);
            } catch (IOException e) { /* bad frame: ignore */ }
        }
    }

    void hostMsg(int slot, int type, byte[] d) throws IOException {
        if (slot < 1 || slot >= World.NP) return;
        World.Player p = w.pl[slot];
        switch (type) {
            case Net.T_HELLO: {
                DataInputStream in = stream(d);
                int ver = in.readInt(); String name = clean(in.readUTF());
                if (ver != Net.VERSION) { net.sendTo(slot, Net.reject("Different game version")); break; }
                if (p.active && p.remote) break;
                if (w.phase == World.PH_PLAY) w.joinMid(slot, name);
                else { p.active = true; p.bot = false; p.remote = true; p.name = name; p.hat = 0; p.hatMask = 1; }
                net.sendTo(slot, Net.welcome(slot));
                sendAddonManifest(slot);
                say(name + " joined");
                sfx.play(Sfx.S_EMOTE, 0.8f, 0);
                break;
            }
            case Net.T_INPUT: {
                if (!p.active || !p.remote) break;
                DataInputStream in = stream(d);
                float x = in.readFloat(), y = in.readFloat(), fx = in.readFloat(), fy = in.readFloat(); int f = in.readUnsignedByte(); float st = in.readFloat();
                if (p.state != World.ST_ALIVE || w.phase != World.PH_PLAY) { p.moving = false; p.lampRaise = false; p.waterHeld = false; p.actHeld = false; break; }
                if (p.teleGuard <= 0 && x == x && y == y) { p.x = World.clamp(x, 0, Data.WW); p.y = World.clamp(y, 0, Data.WH); }
                p.fx = fx; p.fy = fy; p.moving = (f & 1) != 0; p.sprint = (f & 2) != 0; p.lampRaise = (f & 4) != 0 && p.oil > 0;
                p.waterHeld = (f & 8) != 0; p.actHeld = (f & 16) != 0; if (st == st) p.stamina = st;
                break;
            }
            case Net.T_CMD: {
                if (!p.active || !p.remote || w.phase != World.PH_PLAY) break;
                DataInputStream in = stream(d);
                int code = in.readUnsignedByte(), a = in.readInt();
                if (code == Net.C_ACT) w.act(p); else if (code == Net.C_BUY) w.buy(p, a); else if (code == Net.C_SEL) w.setSel(p, a); else if (code == Net.C_EMOTE) w.emote(p, a);
                break;
            }
            case Net.T_LEAVE:
                if (p.remote) { say(p.name + " left"); p.active = false; p.remote = false; p.moving = false; p.actHeld = false; p.waterHeld = false; p.lampRaise = false; }
                break;
            default: break;
        }
    }

    void clientMsg(int type, byte[] d) throws IOException {
        switch (type) {
            case Net.T_WELCOME: me = d[1]; w.mySlot = me; connected = true; say("Connected - you are player " + (me + 1)); break;
            case Net.T_REJECT: say(stream(d).readUTF()); leaveGame(); break;
            case Net.T_ADDONS: onHostAddons(Arrays.copyOfRange(d, 1, d.length)); break;
            case Net.T_SNAP:
                if (addonWait) break;
                if (me < 0) w.mySlot = -1;
                w.read(stream(d)); gotSnap = true; afterSnap();
                break;
            case Net.T_LEAVE: say(net.joined || connected ? "Host left the game" : net.status); leaveGame(); break;
            default: break;
        }
    }

    void afterSnap() {
        if (screen == S_LOBBY && me >= 0 && w.phase != World.PH_LOBBY) { screen = S_PLAY; clearFx(); camX = lp().x; camY = lp().y; sfx.setEnd(true); lastIsland = w.island; scripts.clearLog(); startAutoScript(); }
        else if (screen == S_PLAY && w.phase == World.PH_LOBBY) { screen = S_LOBBY; panel = 0; sfx.silence(); scripts.stop(false); }
    }

    // ------------------------------------------------------------ menu navigation
    void onBack() {
        if (screen == S_TITLE) { host.quit(); return; }
        if (screen == S_PLAY) { panel = panel == 0 ? 3 : 0; if (solo) paused = panel == 3 || pausedByEditor; return; }
        if (screen == S_LOBBY || screen == S_JOIN) { leaveGame(); return; }
        screen = S_TITLE;
    }

    void button(int id) {
        sfx.play(Sfx.S_CLICK, 0.8f, 0);
        if (id >= B_KICK) { int s = id - B_KICK; if (w.authority && s > 0 && w.pl[s].bot && w.phase == World.PH_LOBBY) w.pl[s].active = false; return; }
        if (id >= B_FOUND) { synchronized (net.found) { int i = id - B_FOUND; if (i < net.found.size()) { final String ip = net.found.get(i).ip; post(new Runnable() { public void run() { joinGame(ip); } }); } } return; }
        switch (id) {
            case B_SOLO: startSolo(); break;
            case B_HOST: hostGame(); break;
            case B_JOIN: openJoin(); break;
            case B_HELP: screen = S_HELP; break;
            case B_ADDONS: if (screen == S_TITLE) AddonEngineController.openAddonMenu(getContext()); break;
            case B_NAME: host.prompt("Your name", myName, new Cb() { public void done(String t) { myName = clean(t); host.saveName(myName); if (w.authority && w.phase == World.PH_LOBBY) w.pl[0].name = myName; } }); break;
            case B_SOUND: soundOn = !soundOn; if (soundOn) sfx.start(); else { sfx.silence(); sfx.stop(); } break;
            case B_BACK: onBack(); break;
            case B_IP: host.prompt("Host IP address", lastIp, new Cb() { public void done(String t) { lastIp = t.trim(); joinGame(lastIp); } }); break;
            case B_BOT: addBot(); break;
            case B_DIFF: if (isHost) lobbyDiff = (lobbyDiff + 1) % 3; break;
            case B_MODE: if (isHost) lobbyEndless = !lobbyEndless; break;
            case B_START: startRun(); break;
            case B_LEAVE: leaveGame(); break;
            case B_PAUSE: panel = 3; if (solo) paused = true; break;
            case B_RESUME: panel = 0; paused = pausedByEditor; break;
            case B_CODE: host.openEditor(); break;
            case B_RUNBTN: if (scripts.isRunning()) stopScript(); else if (store != null) { String src = store.read(activeScript); if (src != null) runSource(src, activeScript); } break;
            case B_CONSOLE: consoleBig = !consoleBig; break;
            case B_AUTORUN: autoRun = !autoRun; host.savePref("autorun", autoRun ? "1" : "0"); break;
            case B_AGAIN: backToLobby(); break;
            default: break;
        }
    }
    String lastIp = "192.168.";

    // ------------------------------------------------------------ touch
    void addHit(float x, float y, float ww, float hh2, int id) { if (nh < hx.length) { hx[nh] = x; hy[nh] = y; hw[nh] = ww; hh[nh] = hh2; hid[nh] = id; nh++; } }
    int hitAt(float x, float y) { for (int i = nh - 1; i >= 0; i--) if (x >= hx[i] && x <= hx[i] + hw[i] && y >= hy[i] && y <= hy[i] + hh[i]) return hid[i]; return -1; }
    void pointerDown(int id, float x, float y) {
        if (id < 0 || id >= 32) return;
        ptrHit[id] = -1;
        int h = hitAt(x, y);
        if (h >= 0) { ptrHit[id] = h; pressId = h; }
    }
    void pointerUp(int id, float x, float y) {
        if (id < 0 || id >= 32) return;
        int h = ptrHit[id]; ptrHit[id] = -1; pressId = -1;
        if (h >= 0 && hitAt(x, y) == h) button(h);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) { int i = e.getActionIndex(); pointerDown(e.getPointerId(i), e.getX(i), e.getY(i)); }
        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) { int i = e.getActionIndex(); pointerUp(e.getPointerId(i), e.getX(i), e.getY(i)); }
        else if (a == MotionEvent.ACTION_CANCEL) { for (int i = 0; i < 32; i++) if (ptrHit[i] >= 0) pointerUp(i, -1, -1); }
        return true;
    }
    /** No manual controls any more: the character is driven by the script. Only ESC / back opens the menu. */
    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_ESCAPE) { if (e.getRepeatCount() == 0) onBack(); return true; }
        return false;
    }

    // ------------------------------------------------------------ fx
    void spawn(float x, float y, float vx, float vy, float life, float size, int col, int kind) {
        int i = pHead; pHead = (pHead + 1) % NPA;
        pX[i] = x; pY[i] = y; pVX[i] = vx; pVY[i] = vy; pL[i] = life; pM[i] = life; pS[i] = size; pC[i] = col; pK[i] = kind;
    }
    void burst(float x, float y, int n, int col, float spd, float life, float size, int kind) {
        for (int i = 0; i < n; i++) { double a = Math.random() * 6.283, s = spd * (0.3 + Math.random() * 0.7); spawn(x, y - 6, (float) Math.cos(a) * (float) s, (float) Math.sin(a) * (float) s - spd * 0.3f, life * (0.6f + (float) Math.random() * 0.6f), size, col, kind); }
    }
    void ftext(float x, float y, String s, int col) { int i = fHead; fHead = (fHead + 1) % NFT; fX[i] = x; fY[i] = y; fL[i] = 1.4f; fT[i] = s; fC[i] = col; }
    void updateFx(float dt) {
        for (int i = 0; i < NPA; i++) {
            if (pL[i] <= 0) continue;
            pL[i] -= dt; pX[i] += pVX[i] * dt; pY[i] += pVY[i] * dt;
            if (pK[i] == 0) pVY[i] += 30f * dt;                  // dust / dirt falls
            else if (pK[i] == 1) { pVX[i] += (float) Math.sin(time * 2f + i) * 20f * dt; pVY[i] += 6f * dt; }   // leaf
            else if (pK[i] == 3) { pS[i] += dt * 9f; pVY[i] -= 4f * dt; }                                  // smoke
            else if (pK[i] == 4) pVY[i] += 70f * dt;                                                         // confetti
        }
        for (int i = 0; i < NFT; i++) if (fL[i] > 0) { fL[i] -= dt; fY[i] -= 24f * dt; }
        if (w.phase == World.PH_WIN && screen == S_PLAY) { winFx -= dt; if (winFx <= 0) { winFx = 0.18f; burst(Data.BOAT_X + w.winT * 70f, Data.BOAT_Y - 40, 8, Hud.LEAF[(int) (Math.random() * 4)], 130, 1.4f, 3.4f, 4); } }
        if (screen == S_PLAY) {
            leafT -= dt;
            if (leafT <= 0) { leafT = 0.16f; float vw = sw / zoom, vh = sh / zoom; spawn(camX - vw / 2 - 20 + (float) Math.random() * vw * 1.1f, camY - vh / 2 - 20, 20 + (float) Math.random() * 20, 24 + (float) Math.random() * 14, 7f, 3.2f, Hud.LEAF[(int) (Math.random() * 4)], 1); }
            smokeT -= dt;
            if (smokeT <= 0) { smokeT = 0.4f; spawn(1234, 446, 6, -16, 3.2f, 5f, 0x88C8C8C8, 3); }
        }
    }

    void drainEvents() {
        World.EvQ q = w.local;
        for (int i = 0; i < q.n; i++) onEvent(q.t[i], q.x[i], q.y[i], q.a[i]);
        q.n = 0;
    }

    void onEvent(int t, float x, float y, int a) {
        addonEvent(t, x, y, a);
        World.Player p = lp();
        float d = World.dist(x, y, p.x, p.y), vol = World.clamp(1.15f - d / 1000f, 0.1f, 1f), pan = World.clamp((x - camX) / Math.max(200f, sw / zoom * 0.5f), -1f, 1f);
        boolean mine = d < 4f;
        switch (t) {
            case World.EV_PLANT: sfx.play(Sfx.S_PLANT, vol, pan); burst(x, y, 7, 0xFF6B4423, 60, 0.6f, 3f, 0); break;
            case World.EV_WATER: sfx.play(Sfx.S_WATER, vol * 0.7f, pan); burst(x, y, 5, 0xFF6FB8E8, 40, 0.5f, 2.4f, 0); break;
            case World.EV_HARVEST: sfx.play(Sfx.S_HARVEST, vol, pan); burst(x, y, 8, Data.CROP_COL[a % 5], 80, 0.7f, 3f, 2); ftext(x, y - 20, "+" + Data.CROP[a % 5], 0xFFFFF3A8); break;
            case World.EV_COIN: sfx.play(Sfx.S_COIN, vol, pan); burst(x, y, 5, 0xFFF2C230, 70, 0.6f, 2.6f, 2); ftext(x, y - 24, "+" + a, 0xFFF2C230); break;
            case World.EV_SELL: sfx.play(Sfx.S_SELL, vol, pan); burst(x, y - 20, 12, 0xFFF2C230, 110, 0.9f, 3f, 4); ftext(x, y - 50, "+" + a + " coins", 0xFFF2C230); break;
            case World.EV_BUY: if (d < 160) sfx.play(Sfx.S_BUY, 0.8f, 0); break;
            case World.EV_ERR: if (mine && time - lastErrSfx > 1.2f) { lastErrSfx = time; sfx.play(Sfx.S_ERR, 0.5f, 0); } break;
            case World.EV_WELL: sfx.play(Sfx.S_WELL, vol, pan); burst(x, y - 20, 9, 0xFF8FC8F0, 80, 0.7f, 3f, 0); break;
            case World.EV_OIL: sfx.play(Sfx.S_OIL, vol, pan); ftext(x, y - 40, "-" + a + " coins", 0xFFFFC080); break;
            case World.EV_DOOR: sfx.play(a == 1 ? Sfx.S_DOORO : Sfx.S_DOORC, vol, pan); break;
            case World.EV_CANDLE_OUT: sfx.play(Sfx.S_CANDLE_OUT, vol, pan); burst(x, y - 16, 5, 0x88DDDDDD, 20, 1.2f, 3f, 3); shake(3, 0.25f); break;
            case World.EV_CANDLE_LIT: sfx.play(Sfx.S_CANDLE_LIT, vol, pan); break;
            case World.EV_SKIN_ARRIVE: sfx.play(Sfx.S_SKIN_ARRIVE, 0.9f, pan); cue("Something is crawling in the woods... hide() in the cabin!"); shake(4, 0.5f); break;
            case World.EV_SKIN_LEAVE: sfx.play(Sfx.S_SKIN_LEAVE, 0.7f, pan); cue("It's leaving..."); break;
            case World.EV_SKIN_HIT: sfx.play(Sfx.S_SKIN_HIT, vol, pan); if (a == 1 && d < 60) { shake(9, 0.5f); flash(0x55FF0000); } break;
            case World.EV_DEMON: sfx.play(Sfx.S_DEMON, 1f, pan); cue("The candles are out! Something is under the bed..."); shake(6, 0.6f); break;
            case World.EV_CLOWN: sfx.play(Sfx.S_CLOWN, 0.9f, pan); cue("Carnival music... find the clown before it turns sad!"); break;
            case World.EV_CLOWN_OK: sfx.play(Sfx.S_CLOWN_OK, vol, pan); burst(x, y - 30, 22, 0xFFE8604C, 130, 1.2f, 3.4f, 4); burst(x, y - 30, 14, 0xFF6FB8E8, 130, 1.2f, 3.4f, 4); ftext(x, y - 90, "+" + a + " coins", 0xFFF2C230); break;
            case World.EV_CLOWN_BLUE: sfx.play(Sfx.S_CLOWN_BLUE, 0.9f, pan); cue("The clown turned sad... RUN!"); shake(4, 0.4f); break;
            case World.EV_STAG: sfx.play(Sfx.S_STAG, 0.8f, pan); cue("Something is staring from the dark... lantern(True) or hide()!"); break;
            case World.EV_ROAR: sfx.play(Sfx.S_ROAR, 1f, pan); cue("THE WENDIGO CHARGES!"); shake(8, 0.7f); flash(0x33FF2020); break;
            case World.EV_SCARE: sfx.play(Sfx.S_SCARE, vol, pan); burst(x, y - 30, 14, 0xFFFFF3A8, 140, 0.6f, 3f, 2); break;
            case World.EV_DOWN: sfx.play(Sfx.S_DOWN, vol, pan); burst(x, y - 20, 12, 0xFFFFFFFF, 60, 0.9f, 3f, 2); if (mine) { shake(10, 0.6f); flash(0x66FF0000); host.vibrate(220); cue("You're down! Hold on - a friend can revive(you)."); } break;
            case World.EV_REVIVE: sfx.play(Sfx.S_REVIVE, vol, pan); burst(x, y - 30, 16, 0xFF9AE86A, 90, 0.9f, 3f, 2); ftext(x, y - 60, "Revived!", 0xFF9AE86A); break;
            case World.EV_RESPAWN: sfx.play(Sfx.S_RESPAWN, vol, pan); burst(x, y - 30, 14, 0xFFFFF3A8, 70, 0.9f, 3f, 2); break;
            case World.EV_CAW: sfx.play(Sfx.S_CAW, vol, pan); break;
            case World.EV_DROP: sfx.play(Sfx.S_DROP, vol, pan); burst(x, y, 6, 0xFFF2C230, 50, 0.6f, 2.4f, 2); break;
            case World.EV_CAST: sfx.play(Sfx.S_CAST, vol, pan); break;
            case World.EV_BITE: sfx.play(Sfx.S_BITE, vol, pan); ftext(x, y - 60, "!", 0xFFFFF3A8); break;
            case World.EV_CATCH: sfx.play(Sfx.S_CATCH, vol, pan); ftext(x, y - 60, "Fish +" + a, 0xFF8FD8FF); burst(x - 30, y, 8, 0xFF8FC8F0, 90, 0.7f, 3f, 0); break;
            case World.EV_HORROR: sfx.play(Sfx.S_HORROR, 1f, pan); shake(5, 0.5f); if (d < 120) cue("It bit back! Raise the lantern: lantern(True)"); break;
            case World.EV_SAW: sfx.play(Sfx.S_SAW, vol, pan); burst(x, y - 10, 6, 0xFFD2A066, 70, 0.6f, 2.4f, 0); ftext(x, y - 30, "+1 wood", 0xFFD2A066); break;
            case World.EV_PART: sfx.play(Sfx.S_PART, 0.9f, pan); burst(x, y - 20, 24, 0xFFF2C230, 140, 1.2f, 3.4f, 4); cue("Boat part installed!"); break;
            case World.EV_EMOTE: sfx.play(Sfx.S_EMOTE, vol, pan); break;
            case World.EV_DAWN: sfx.play(Sfx.S_DAWN, 0.7f, 0); cue("The sun is rising..."); break;
            case World.EV_DUSK: sfx.play(Sfx.S_DUSK, 0.7f, 0); cue("Dusk. Light your lantern."); break;
            case World.EV_WIN: sfx.silence(); sfx.setEnd(true); sfx.play(Sfx.S_WIN, 1f, 0); break;
            case World.EV_LOSE: sfx.silence(); sfx.setEnd(false); sfx.play(Sfx.S_LOSE, 1f, 0); break;
            case World.EV_UNLOCK: sfx.play(Sfx.S_UNLOCK, 0.9f, 0); burst(x, y, 24, 0xFF9AE86A, 140, 1.2f, 3.4f, 4); cue("New plot unlocked! New seeds are available."); break;
            case World.EV_UPGRADE: if (d < 200) sfx.play(Sfx.S_UPGRADE, 0.9f, 0); break;
            case World.EV_TIP: sfx.play(Sfx.S_TIP, 0.5f, 0); break;
            case World.EV_ISLAND: sfx.play(Sfx.S_WIN, 0.8f, 0); burst(x, y - 30, 40, 0xFFF2C230, 160, 1.6f, 3.6f, 4); shake(6, 0.6f); flash(0x88000000); cue("Island " + a + "! Everything is " + ((int) (Math.pow(Data.ENDLESS_STEP, a - 1) * 100)) / 100f + "x more dangerous. You washed ashore far from the cabin!"); camX = lp().x; camY = lp().y; break;
            default: break;
        }
    }
    void cue(String s) { cue = s; cueT = 4.5f; }
    void shake(float a, float t) { if (a > shakeA || shakeT <= 0) shakeA = a; shakeT = Math.max(shakeT, t); }
    void flash(int col) { flashCol = col; flashT = 0.4f; }

    // ------------------------------------------------------------ camera + audio
    void camera(float dt) {
        World.Player p = lp();
        float tx = p.rx, ty = p.ry;
        if (p.state == World.ST_DEAD || (me < 0)) {
            float bd = 1e9f;
            for (int i = 0; i < World.NP; i++) { World.Player q = w.pl[i]; if (q.active && q.state != World.ST_DEAD) { float d = World.dist(q.rx, q.ry, camX, camY); if (d < bd) { bd = d; tx = q.rx; ty = q.ry; } } }
        }
        zoom = sh / 560f;
        float k = Math.min(1f, dt * 6f);
        camX += (tx - camX) * k; camY += (ty - 28 - camY) * k;
        float hw2 = sw / zoom / 2, hh2 = sh / zoom / 2;
        camX = World.clamp(camX, hw2 - 40, Data.WW - hw2 + 40); camY = World.clamp(camY, hh2 - 40, Data.WH - hh2 + 40);
        World.Player me2 = lp();
        float tr = (me2.inside && me2.state != World.ST_DEAD) ? 0.14f : 1f;
        roofA += (tr - roofA) * Math.min(1f, dt * 7f);
        doorA += ((w.door ? 1f : 0f) - doorA) * Math.min(1f, dt * 8f);
    }

    void audio(float dt) {
        if (!soundOn) return;
        boolean play = screen == S_PLAY;
        World.Player p = lp();
        float dk = play ? w.dark() : 0f;
        boolean end = play && w.phase != World.PH_PLAY;
        float mus = end ? 0f : (play ? 0.9f : 0.75f);
        sfx.setCh(Sfx.CH_DAY, (1f - dk) * mus, 0); sfx.setCh(Sfx.CH_NIGHT, dk * mus, 0);
        sfx.setCh(Sfx.CH_WIND, play && !end ? 0.28f + 0.35f * dk : 0.12f, 0);
        sfx.setCh(Sfx.CH_END, end ? 0.9f : 0f, 0);
        float vSk = 0, vCl = 0, vBl = 0, vSt = 0, vDm = 0, pSk = 0, pCl = 0, pBl = 0, pSt = 0;
        if (play && !end) {
            World.Mob m = w.mob[World.M_SKIN];
            if (m.on && (m.st == 1 || m.st == 2)) { float d = World.dist(m.rx, m.ry, p.x, p.y); vSk = World.clamp(1.1f - d / 1700f, 0.1f, 1f); pSk = World.clamp((m.rx - p.x) / 700f, -1f, 1f); }
            float bc = 1e9f, bb = 1e9f;
            for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) {
                World.Mob c = w.mob[i]; if (!c.on) continue;
                float d = World.dist(c.rx, c.ry, p.x, p.y);
                if (c.st == 1 && d < bc) { bc = d; vCl = World.clamp(1.1f - d / 1500f, 0.1f, 1f); pCl = World.clamp((c.rx - p.x) / 600f, -1f, 1f); }
                if ((c.st == 3 || c.st == 4) && d < bb) { bb = d; vBl = World.clamp(1.1f - d / 1300f, 0.15f, 1f); pBl = World.clamp((c.rx - p.x) / 600f, -1f, 1f); }
            }
            World.Mob s = w.mob[World.M_STAG];
            if (s.on && s.st >= 1 && s.st <= 3) { float d = World.dist(s.rx, s.ry, p.x, p.y); vSt = World.clamp(1.2f - d / 650f, 0f, 1f); pSt = World.clamp((s.rx - p.x) / 600f, -1f, 1f); }
            World.Mob dm = w.mob[World.M_DEMON];
            if (dm.on && p.inside) vDm = 0.9f;
        }
        sfx.setCh(Sfx.CH_SKIN, vSk, pSk); sfx.setCh(Sfx.CH_CLOWN, vCl, pCl); sfx.setCh(Sfx.CH_BLUE, vBl, pBl); sfx.setCh(Sfx.CH_STAG, vSt, pSt); sfx.setCh(Sfx.CH_DEMON, vDm, 0);
        sfx.setMuffle(play && p.inside && !w.door ? 0.8f : 0f);
        sfx.setReverb(0.06f + 0.16f * dk);
    }
}
