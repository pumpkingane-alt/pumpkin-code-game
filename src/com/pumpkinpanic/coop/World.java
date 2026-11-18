package com.pumpkinpanic.coop;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/** Authoritative game simulation + snapshot (de)serialisation. No Android dependencies. */
final class World {
    static final int PH_LOBBY = 0, PH_PLAY = 1, PH_WIN = 2, PH_LOSE = 3;
    static final int ST_ALIVE = 0, ST_DOWN = 1, ST_DEAD = 2;
    static final int M_SKIN = 0, M_DEMON = 1, M_CLOWN0 = 2, M_CLOWN1 = 3, M_STAG = 4, M_CROW = 5, NM = 6;
    static final int NP = 4, NT = 45, NC = 24, NL = 8;
    static final int K_NONE = 0, K_TILE = 1, K_WELL = 2, K_OIL = 3, K_SELL = 4, K_BENCH = 5, K_CLOSET = 6, K_FISH = 7,
        K_BOAT = 8, K_LOG = 9, K_CLOWN = 10, K_REVIVE = 11;

    static final int EV_PLANT = 1, EV_WATER = 2, EV_HARVEST = 3, EV_COIN = 4, EV_SELL = 5, EV_BUY = 6, EV_ERR = 7, EV_WELL = 8,
        EV_OIL = 9, EV_DOOR = 10, EV_CANDLE_OUT = 11, EV_CANDLE_LIT = 12, EV_SKIN_ARRIVE = 13, EV_SKIN_LEAVE = 14,
        EV_SKIN_HIT = 15, EV_DEMON = 16, EV_CLOWN = 17, EV_CLOWN_OK = 18, EV_CLOWN_BLUE = 19, EV_STAG = 20, EV_ROAR = 21,
        EV_SCARE = 22, EV_DOWN = 23, EV_REVIVE = 24, EV_RESPAWN = 25, EV_CAW = 26, EV_DROP = 27, EV_CAST = 28, EV_BITE = 29,
        EV_CATCH = 30, EV_HORROR = 31, EV_SAW = 32, EV_PART = 33, EV_EMOTE = 34, EV_DAWN = 35, EV_DUSK = 36, EV_WIN = 37,
        EV_LOSE = 38, EV_UNLOCK = 39, EV_UPGRADE = 40, EV_TIP = 41, EV_ADDON_ITEM = 42, EV_ISLAND = 43;

    static final class EvQ {
        int n;
        final int[] t = new int[160], a = new int[160];
        final float[] x = new float[160], y = new float[160];
        void add(int ty, float px, float py, int pa) { if (n < t.length) { t[n] = ty; x[n] = px; y[n] = py; a[n] = pa; n++; } }
    }

    static final class Player {
        boolean active, bot, remote;
        String name = "Player";
        float x, y, rx, ry, fx, fy = 1;
        int state; float stateT, inv;
        boolean moving, sprint, lampRaise, waterHeld, actHeld, inside, lampOn, aboard;
        float stamina = 1, water, oil;
        final int[] lvl = new int[4];
        boolean saw;
        final int[] seeds = new int[5], crops = new int[5];
        int fish, fishVal, sel, hat, hatMask = 1;
        int emote; float emoteT;
        float animT, waterCd, reviveProg, fishT;
        int fishState, tele;
        int aimK, aimI; float aimD, teleGuard;
        int lastErr;                       // reason code of the last failed action (host / solo only, read by scripts)
        // host-only bot scratch
        float botT, botAct, botStuck, botWander, botX, botY, lx, ly; int botMode;
        // visual-only
        float stepAcc, shownAnim;
    }

    static final class Mob { boolean on; float x, y, rx, ry, t, face = 1; int st, a; }
    static final class Tile { int type = -1; float grow, water; }
    static final class Coin { boolean on; float x, y, t; int v; }
    static final class LogN { int hp = 4; float t; }

    int phase = PH_LOBBY, diff, nStart = 1, mySlot;
    boolean endless;                 // endless mode: sailing away lands you on a harder copy of the island
    int island = 1, statIslands;     // current island number (1 = first), islands escaped so far
    float islandT0;                  // game time when this island started
    float T, endT, winT;
    int coins, lives, wood, boatMask, plots = 1, tip = -1, candles = 7;
    boolean door, authority = true, netOn;
    int statHarv, statCoins, statDowns, statRev, statFish;
    final Player[] pl = new Player[NP];
    final Tile[] tiles = new Tile[NT];
    final Mob[] mob = new Mob[NM];
    final Coin[] coin = new Coin[NC];
    final LogN[] logs = new LogN[NL];
    final EvQ local = new EvQ(), net = new EvQ();
    final Random rnd = new Random();
    // host-only timers
    float skinT, clownT, crowT, demonArm, emptyT, stagCool, tipT, skinStun, stagLook, crowTx, crowTy, skinHitT, tipHold, stagTx, stagTy;
    boolean skinBlew, tipTimed, firstDusk, firstSkin, firstClown;
    int stagTarget = -1;

    World() {
        for (int i = 0; i < NP; i++) pl[i] = new Player();
        for (int i = 0; i < NT; i++) tiles[i] = new Tile();
        for (int i = 0; i < NM; i++) mob[i] = new Mob();
        for (int i = 0; i < NC; i++) coin[i] = new Coin();
        for (int i = 0; i < NL; i++) logs[i] = new LogN();
    }

    // ------------------------------------------------------------ helpers
    static float dist(float ax, float ay, float bx, float by) { float dx = ax - bx, dy = ay - by; return (float) Math.sqrt(dx * dx + dy * dy); }
    static boolean inCabin(float x, float y) { return x > Data.IX0 && x < Data.IX1 && y > Data.IY0 && y < Data.IY1; }
    static float clamp(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }

    void ev(int type, float x, float y, int a) { local.add(type, x, y, a); if (netOn) net.add(type, x, y, a); }
    int activeCount() { int n = 0; for (int i = 0; i < NP; i++) if (pl[i].active) n++; return n; }
    float costMul() { return Data.COST_MUL[diff] * (1f + 0.22f * (nStart - 1)); }

    /** Endless mode: danger factor of this island = 1.5 ^ (island - 1). Always 1 in classic mode. */
    float danger() { return endless ? (float) Math.pow(Data.ENDLESS_STEP, Math.max(0, island - 1)) : 1f; }
    /** Monster speed factor (danger, but capped so that monsters stay beatable with a good script). */
    float spdMul() { return Math.min(danger(), Data.ENDLESS_SPEED_CAP); }
    /** Seconds a downed player can bleed before dying. */
    float bleed() { float b = Data.BLEED[diff]; return endless ? Math.max(Data.ENDLESS_MIN_BLEED, b / danger()) : b; }
    /** Seconds into this island. */
    float islandTime() { return T - islandT0; }

    void err(Player p, int code) { p.lastErr = code; ev(EV_ERR, p.x, p.y, code); }
    float pf() { return 1f + 0.12f * (nStart - 1); }

    static float darkAt(float t) {
        float c = t % Data.CYC;
        if (c < Data.DUSK0) return 0f;
        if (c < Data.NIGHT0) return (c - Data.DUSK0) / (Data.NIGHT0 - Data.DUSK0);
        if (c < Data.DAWN0) return 1f;
        return 1f - (c - Data.DAWN0) / (Data.CYC - Data.DAWN0);
    }
    float dark() { return darkAt(T); }
    int day() { return 1 + (int) (T / Data.CYC); }
    boolean isNight() { return dark() > 0.5f; }

    float lampR(Player p) {
        if (p.state != ST_ALIVE) return 0;
        float b = Data.LAMP_R[p.lvl[0]];
        if (p.oil <= 0) return 62;
        return p.lampRaise ? b * 1.55f : b;
    }

    static boolean blockedRect(float x, float y, float r, int from, int to) {
        for (int i = from; i < to; i++) {
            float[] o = Data.OBS[i];
            if (x + r > o[0] && x - r < o[0] + o[2] && y + r > o[1] && y - r < o[1] + o[3]) return true;
        }
        return false;
    }
    static boolean walkable(float x, float y) {
        if (x >= Data.WX0 && x <= Data.WX1 && y >= Data.WY0 && y <= Data.WY1) return true;
        return x >= Data.DK_X0 && x <= Data.DK_X1 && y > Data.WY1 - 40 && y <= Data.DK_Y1;
    }
    static boolean canStand(float x, float y) {
        return walkable(x, y) && !blockedRect(x, y, 12, 0, Data.OBS_DOOR) && !blockedRect(x, y, 12, Data.OBS_DOOR + 1, Data.OBS.length)
            && !AddonEngineController.sceneBlocked(x, y, 12);
    }

    /** Shared movement used by host, client (own player) and bots. */
    void movePlayer(Player p, float ix, float iy, boolean sprint, float dt) {
        float len = (float) Math.sqrt(ix * ix + iy * iy);
        p.moving = len > 0.15f && p.state == ST_ALIVE;
        p.sprint = false;
        if (!p.moving) return;
        float ux = ix / len, uy = iy / len;
        float spd = 140f * (1f + 0.08f * p.lvl[3]) * Math.min(1f, len);
        if (sprint && p.stamina > 0.04f) { spd *= 1.6f; p.sprint = true; p.stamina = Math.max(0, p.stamina - dt * 0.3f); }
        if (p.fishState != 0) p.fishState = 0;
        p.fx = ux; p.fy = uy;
        float nx = p.x + ux * spd * dt;
        if (canStand(nx, p.y)) p.x = nx;
        float ny = p.y + uy * spd * dt;
        if (canStand(p.x, ny)) p.y = ny;
    }

    // ------------------------------------------------------------ run setup
    void reset(int d, boolean endlessMode) {
        diff = d; T = 0; endless = endlessMode; island = 1; statIslands = 0; islandT0 = 0; phase = PH_PLAY; endT = 0; winT = 0;
        nStart = Math.max(1, activeCount());
        coins = 20; wood = 0; boatMask = 0; plots = 1; tip = 0; tipT = 0; tipTimed = false; candles = 7; door = false;
        lives = Data.LIVES[d] + (d == 2 ? 0 : nStart - 1);
        statHarv = statCoins = statDowns = statRev = statFish = 0;
        for (int i = 0; i < NT; i++) { tiles[i].type = -1; tiles[i].grow = 0; tiles[i].water = 0; }
        for (int i = 0; i < NL; i++) { logs[i].hp = 4; logs[i].t = 0; }
        for (int i = 0; i < NC; i++) coin[i].on = false;
        for (int i = 0; i < NM; i++) { Mob m = mob[i]; m.on = false; m.st = 0; m.t = 0; m.a = 0; }
        Mob cr = mob[M_CROW]; cr.on = true; cr.x = cr.rx = 1100; cr.y = cr.ry = 478;
        skinT = Data.SKIN_FIRST[d] / pf() / danger(); clownT = Data.CLOWN_FIRST[d] / danger(); crowT = 22; demonArm = 4f / danger(); emptyT = 0; stagCool = 0;
        skinBlew = false; firstDusk = firstSkin = firstClown = false; stagTarget = -1; skinStun = 0; tipHold = 0;
        local.n = 0; net.n = 0;
        int k = 0;
        for (int i = 0; i < NP; i++) { if (pl[i].active) { initPlayer(pl[i], k++); pl[i].inv = 0; } }
    }

    /** Fresh character at the cabin (run start, or a player joining mid-run). */
    void initPlayer(Player p, int k) {
        p.state = ST_ALIVE; p.stateT = 0; p.inv = 3f; p.fx = 0; p.fy = 1;
        p.x = p.rx = 1010 + (k % 2) * 90; p.y = p.ry = 650 + (k / 2) * 45;
        for (int j = 0; j < 4; j++) p.lvl[j] = 0;
        p.saw = false; p.sel = 0; p.fish = 0; p.fishVal = 0; p.fishState = 0; p.aboard = false;
        for (int j = 0; j < 5; j++) { p.seeds[j] = 0; p.crops[j] = 0; }
        p.seeds[0] = 5; p.stamina = 1; p.water = Data.CAN_CAP[0]; p.oil = Data.OIL_CAP[0];
        p.emoteT = 0; p.reviveProg = 0; p.tele++; p.teleGuard = 0.6f; p.botMode = 0; p.botT = 0; p.moving = false; p.lampRaise = false; p.waterHeld = false; p.actHeld = false;
    }

    /** Called by the host when a remote player (re)joins a running game. */
    void joinMid(int slot, String name) {
        Player p = pl[slot];
        p.active = true; p.bot = false; p.remote = true; p.name = name;
        initPlayer(p, slot);
    }

    // ------------------------------------------------------------ simulation (authority only)
    void update(float dt) {
        if (phase == PH_WIN) { winT += dt; endT += dt; return; }
        if (phase == PH_LOSE) { endT += dt; return; }
        if (phase != PH_PLAY) return;
        T += dt;
        clock(dt);
        for (int i = 0; i < NP; i++) {
            Player p = pl[i];
            if (!p.active) continue;
            if (p.bot) Mobs.bot(this, p, dt);
            updatePlayer(p, dt);
        }
        plants(dt);
        Mobs.update(this, dt);
        boolean d = false;
        for (int i = 0; i < NP && !d; i++) { Player p = pl[i]; if (p.active && p.state == ST_ALIVE && dist(p.x, p.y, Data.DOOR_X, Data.DOOR_Y) < 75) d = true; }
        for (int i = M_CLOWN0; i <= M_CLOWN1 && !d; i++) { Mob m = mob[i]; if (m.on && m.st == 3 && dist(m.x, m.y, Data.DOOR_X, Data.DOOR_Y) < 80) d = true; }
        if (d != door) { door = d; ev(EV_DOOR, Data.DOOR_X, Data.DOOR_Y, d ? 1 : 0); }
        checkLose();
    }

    void clock(float dt) {
        float c = T % Data.CYC, pc = (T - dt) % Data.CYC;
        if (pc < Data.DUSK0 && c >= Data.DUSK0) { ev(EV_DUSK, 0, 0, 0); if (!firstDusk) { firstDusk = true; showTip(6, 14); } }
        if (pc < Data.DAWN0 && c >= Data.DAWN0) {
            ev(EV_DAWN, 0, 0, 0);
            for (int i = 0; i < NP; i++) { Player p = pl[i]; if (p.active && p.state == ST_DEAD && p.stateT < 0) respawn(p); }
        }
    }

    void updatePlayer(Player p, float dt) {
        p.inside = inCabin(p.x, p.y);
        if (p.teleGuard > 0) p.teleGuard -= dt;
        if (p.inv > 0) p.inv -= dt;
        if (p.emoteT > 0) p.emoteT -= dt;
        if (p.state == ST_DOWN) {
            p.lampRaise = false; p.waterHeld = false; p.moving = false; p.fishState = 0;
            p.stateT -= dt;
            boolean help = false;
            for (int i = 0; i < NP; i++) { Player q = pl[i]; if (q != p && q.active && q.state == ST_ALIVE) help = true; }
            if (!help && p.stateT > 4f) p.stateT = 4f;
            if (p.stateT <= 0) bleedOut(p);
            return;
        }
        if (p.state == ST_DEAD) {
            p.moving = false;
            if (p.stateT > 0) { p.stateT -= dt; if (p.stateT <= 0) respawn(p); }
            return;
        }
        float dk = dark();
        p.lampOn = p.oil > 0 && dk > 0.25f;
        if (p.lampRaise && p.oil > 0) p.oil = Math.max(0, p.oil - 3.4f * dt);
        else { p.lampRaise = false; if (p.lampOn) p.oil = Math.max(0, p.oil - 0.3f * dt); }
        if (!p.remote && !p.sprint && p.stamina < 1f) p.stamina = Math.min(1f, p.stamina + dt * 0.2f);
        if (p.moving) p.animT += dt;
        p.waterCd -= dt;
        if (p.waterHeld && p.waterCd <= 0) { pour(p); p.waterCd = 0.3f; }
        if (p.actHeld) {
            aim(p);
            if (p.aimK == K_REVIVE) {
                p.reviveProg += dt;
                if (p.reviveProg >= 2f) { revive(pl[p.aimI]); p.reviveProg = 0; }
            } else p.reviveProg = 0;
        } else p.reviveProg = 0;
        fishTick(p, dt);
    }

    void plants(float dt) {
        float night = dark();
        for (int i = 0; i < NT; i++) {
            Tile t = tiles[i];
            if (t.type < 0 || t.water <= 0) continue;
            t.water = Math.max(0, t.water - dt / 55f);
            if (t.grow < 1f) {
                float gm = t.type == 4 ? 0.6f + 1.2f * night : 1f;
                t.grow = Math.min(1f, t.grow + dt * gm / Data.CROP_TIME[t.type]);
            }
        }
    }

    void checkLose() {
        boolean hope = false, human = false;
        for (int i = 0; i < NP; i++) if (pl[i].active && !pl[i].bot) human = true;
        for (int i = 0; i < NP; i++) { Player p = pl[i]; if (p.active && !(human && p.bot) && (p.state != ST_DEAD || p.stateT > 0)) hope = true; }
        if (!hope && activeCount() > 0) { phase = PH_LOSE; endT = 0; ev(EV_LOSE, 0, 0, 0); }
    }

    // ------------------------------------------------------------ damage / revive
    void hurt(Player p, int cause) {
        if (p.state != ST_ALIVE || p.inv > 0) return;
        p.state = ST_DOWN; p.stateT = bleed(); p.fishState = 0; p.lampRaise = false; p.waterHeld = false; p.reviveProg = 0;
        statDowns++;
        ev(EV_DOWN, p.x, p.y, cause);
    }
    void revive(Player q) {
        if (q.state != ST_DOWN) return;
        q.state = ST_ALIVE; q.inv = 3f; q.stateT = 0; statRev++;
        ev(EV_REVIVE, q.x, q.y, 0);
    }
    void bleedOut(Player p) {
        p.state = ST_DEAD;
        if (p.bot) p.stateT = 10f;
        else if (lives > 0) { lives--; p.stateT = 8f; } else p.stateT = -1f;
    }
    void respawn(Player p) {
        p.state = ST_ALIVE; p.stateT = 0; p.inv = 4f;
        p.x = p.rx = Data.BED_X + 40 + rnd.nextFloat() * 60; p.y = p.ry = 650 + rnd.nextFloat() * 30; p.tele++; p.teleGuard = 0.6f;
        p.oil = Math.max(p.oil, Data.OIL_CAP[p.lvl[1]] * 0.5f); p.water = Math.max(p.water, 1);
        ev(EV_RESPAWN, p.x, p.y, 0);
    }

    // ------------------------------------------------------------ interaction
    private void cand(Player p, int k, int i, float x, float y, float r) {
        float d = dist(p.x, p.y, x, y);
        if (d < r && d < p.aimD) { p.aimD = d; p.aimK = k; p.aimI = i; }
    }
    void aim(Player p) {
        p.aimK = K_NONE; p.aimI = -1; p.aimD = 1e9f;
        if (p.state != ST_ALIVE) return;
        if (p.fishState != 0) { p.aimK = K_FISH; return; }
        for (int i = 0; i < NP; i++) { Player q = pl[i]; if (q != p && q.active && q.state == ST_DOWN) cand(p, K_REVIVE, i, q.x, q.y, 75); }
        if (p.aimK == K_REVIVE) return;
        for (int i = 0; i < 2; i++) { Mob m = mob[M_CLOWN0 + i]; if (m.on && m.st == 1) cand(p, K_CLOWN, i, m.x, m.y, 72); }
        if (p.aimK == K_CLOWN) return;
        p.aimD = 1e9f;
        for (int i = 0; i < plots * 9; i++) {
            Tile t = tiles[i];
            if (t.type >= 0 && t.grow < 1f) continue;
            cand(p, K_TILE, i, Data.tileX(i), Data.tileY(i), 54);
        }
        if (p.aimK == K_TILE) p.aimD -= 12;
        cand(p, K_WELL, 0, Data.WELL_X, Data.WELL_Y + 10, 78);
        cand(p, K_OIL, 0, Data.OIL_X, Data.OIL_Y, 66);
        cand(p, K_SELL, 0, Data.SELL_X, Data.SELL_Y, 70);
        cand(p, K_BENCH, 0, Data.BENCH_X, Data.BENCH_Y, 80);
        cand(p, K_CLOSET, 0, Data.CLOSET_X, Data.CLOSET_Y, 62);
        cand(p, K_FISH, 0, Data.FISH_X, Data.FISH_Y, 70);
        cand(p, K_BOAT, 0, Data.BOAT_X - 50, Data.BOAT_Y, 90);
        for (int i = 0; i < NL; i++) if (logs[i].hp > 0) cand(p, K_LOG, i, Data.LOGS[i][0], Data.LOGS[i][1], 66);
    }

    String label(Player p) {
        switch (p.aimK) {
            case K_TILE: { Tile t = tiles[p.aimI]; return t.type < 0 ? (p.seeds[p.sel] > 0 ? "Plant" : "No seeds") : "Harvest"; }
            case K_WELL: return "Fill can";
            case K_OIL: return "Refuel";
            case K_SELL: return "Sell";
            case K_BENCH: return "Workbench";
            case K_CLOSET: return "Closet";
            case K_FISH: return p.fishState == 0 ? "Fish" : (p.fishState == 3 ? "REEL!" : (p.fishState == 4 ? "LAMP!" : "Wait..."));
            case K_BOAT: return boatMask == 15 ? "Set sail" : "Boat";
            case K_LOG: return p.saw ? "Saw" : "Need saw";
            case K_CLOWN: return "Cheer up";
            case K_REVIVE: return "Hold: revive";
            default: return "";
        }
    }

    void act(Player p) {
        if (p.state != ST_ALIVE) return;
        aim(p);
        if (p.fishState != 0) { fishAct(p); return; }
        switch (p.aimK) {
            case K_TILE: {
                Tile t = tiles[p.aimI];
                float tx = Data.tileX(p.aimI), ty = Data.tileY(p.aimI);
                if (t.type < 0) {
                    if (p.seeds[p.sel] > 0) { p.seeds[p.sel]--; t.type = p.sel; t.grow = 0; t.water = 0; ev(EV_PLANT, tx, ty, p.sel); tipDone(1); }
                    else err(p, 1);
                } else if (t.grow >= 1f) {
                    p.crops[t.type]++; ev(EV_HARVEST, tx, ty, t.type); t.type = -1; t.grow = 0; t.water = 0; statHarv++; tipDone(3);
                }
                break;
            }
            case K_WELL: p.water = Data.CAN_CAP[p.lvl[2]]; ev(EV_WELL, Data.WELL_X, Data.WELL_Y, 0); break;
            case K_OIL: {
                float cap = Data.OIL_CAP[p.lvl[1]], need = cap - p.oil;
                if (need < 1f) break;
                int cost = (int) Math.ceil(need / 25f);
                if (cost > coins) { cost = coins; need = cost * 25f; }
                if (cost <= 0) { err(p, 6); break; }
                coins -= cost; p.oil = Math.min(cap, p.oil + need); ev(EV_OIL, Data.OIL_X, Data.OIL_Y, cost);
                break;
            }
            case K_SELL: {
                int tot = p.fishVal;
                for (int i = 0; i < 5; i++) tot += p.crops[i] * Data.CROP_SELL[i];
                if (tot <= 0) { err(p, 3); break; }
                coins += tot; statCoins += tot;
                for (int i = 0; i < 5; i++) p.crops[i] = 0;
                p.fish = 0; p.fishVal = 0;
                ev(EV_SELL, Data.SELL_X, Data.SELL_Y, tot); tipDone(4);
                break;
            }
            case K_FISH: p.fishState = 1; p.fishT = 1f; p.fx = -1; p.fy = 0; ev(EV_CAST, p.x, p.y, 0); break;
            case K_BOAT: {
                if (boatMask != 15) { err(p, 5); break; }
                boolean all = true;
                for (int i = 0; i < NP; i++) { Player q = pl[i]; if (q.active && q.state == ST_ALIVE && dist(q.x, q.y, Data.BOAT_X, Data.BOAT_Y) > 230) all = false; }
                if (!all) { err(p, 4); break; }
                if (endless) { nextIsland(); break; }
                phase = PH_WIN; winT = 0; endT = 0; ev(EV_WIN, Data.BOAT_X, Data.BOAT_Y, 0);
                break;
            }
            case K_LOG: {
                LogN l = logs[p.aimI];
                if (!p.saw) { err(p, 7); break; }
                l.hp--; wood++; ev(EV_SAW, Data.LOGS[p.aimI][0], Data.LOGS[p.aimI][1], wood);
                if (l.hp <= 0) l.t = 100f;
                break;
            }
            case K_CLOWN: {
                Mob m = mob[M_CLOWN0 + p.aimI];
                m.st = 2; m.t = 0;
                coins += 30; statCoins += 30; p.oil = Data.OIL_CAP[p.lvl[1]];
                ev(EV_CLOWN_OK, m.x, m.y, 30);
                break;
            }
            default: break;
        }
    }

    /** Endless mode: the boat is sealed - sail to a fresh copy of the island (harder, and you land on the shore, not in the cabin). */
    void nextIsland() {
        island++; statIslands++;
        T = (float) (Math.floor(T / Data.CYC) + 1) * Data.CYC; islandT0 = T;      // a new morning
        coins = Math.round(coins * Data.ENDLESS_COIN_KEEP);
        wood = 0; boatMask = 0; plots = 1; candles = 7; door = false; tip = -1; tipTimed = false;
        if (diff != 2) lives++;
        for (int i = 0; i < NT; i++) { tiles[i].type = -1; tiles[i].grow = 0; tiles[i].water = 0; }
        for (int i = 0; i < NL; i++) { logs[i].hp = 4; logs[i].t = 0; }
        for (int i = 0; i < NC; i++) coin[i].on = false;
        for (int i = 0; i < NM; i++) { Mob m = mob[i]; m.on = false; m.st = 0; m.t = 0; m.a = 0; }
        Mob cr = mob[M_CROW]; cr.on = true; cr.x = cr.rx = 1100; cr.y = cr.ry = 478;
        float dg = danger();
        skinT = Data.SKIN_FIRST[diff] / pf() / dg; clownT = Data.CLOWN_FIRST[diff] / dg; crowT = 22; demonArm = 4f / dg; emptyT = 0; stagCool = 0;
        skinBlew = false; stagTarget = -1; skinStun = 0;
        int k = 0;
        for (int i = 0; i < NP; i++) { Player p = pl[i]; if (p.active) landPlayer(p, k++); }
        ev(EV_ISLAND, Data.BOAT_X, Data.BOAT_Y, island);
    }

    /** Put a player on the shore of a new island (keeps gear, seeds and carried goods). */
    void landPlayer(Player p, int k) {
        p.state = ST_ALIVE; p.stateT = 0; p.inv = 5f; p.fx = 0; p.fy = -1;
        p.x = p.rx = Data.LAND_X + (k % 2) * 34; p.y = p.ry = Data.LAND_Y + (k / 2) * 30;
        p.stamina = 1; p.water = Data.CAN_CAP[p.lvl[2]]; p.oil = Math.max(p.oil, Data.OIL_CAP[p.lvl[1]] * 0.6f);
        p.fishState = 0; p.aboard = false; p.emoteT = 0; p.reviveProg = 0; p.tele++; p.teleGuard = 0.6f; p.moving = false; p.lampRaise = false; p.waterHeld = false; p.actHeld = false;
    }

    void fishAct(Player p) {
        if (p.fishState == 2) { p.fishState = 0; err(p, 8); }
        else if (p.fishState == 3) {
            int v = 6 + rnd.nextInt(15);
            if (rnd.nextFloat() < 0.06f) v = 70;
            p.fish++; p.fishVal += v; statFish++; p.fishState = 0;
            ev(EV_CATCH, p.x, p.y, v);
        }
    }

    void fishTick(Player p, float dt) {
        if (p.fishState == 0) return;
        if (p.moving || dist(p.x, p.y, Data.FISH_X, Data.FISH_Y) > 90) { p.fishState = 0; return; }
        p.fishT -= dt;
        switch (p.fishState) {
            case 1: if (p.fishT <= 0) { p.fishState = 2; p.fishT = 3f + rnd.nextFloat() * 6f; } break;
            case 2:
                if (p.fishT <= 0) {
                    boolean horror = dark() > 0.5f ? rnd.nextFloat() < 0.22f : (T > Data.CYC && rnd.nextFloat() < 0.07f);
                    p.fishState = horror ? 4 : 3; p.fishT = horror ? 1.3f : 1.1f;
                    ev(horror ? EV_HORROR : EV_BITE, p.x - 30, p.y, 0);
                }
                break;
            case 3: if (p.fishT <= 0) { p.fishState = 0; err(p, 8); } break;
            case 4:
                if (p.lampRaise) { p.fishState = 0; ev(EV_SCARE, p.x - 30, p.y, 0); }
                else if (p.fishT <= 0) { p.fishState = 0; hurt(p, 5); }
                break;
            default: break;
        }
    }

    void pour(Player p) {
        if (p.water < 1f) { err(p, 2); p.waterCd = 0.9f; return; }
        float r = Data.WATER_R[p.lvl[2]];
        int best = -1; float bd = 1e9f;
        float hx = p.x + p.fx * 18, hy = p.y + p.fy * 18;
        for (int i = 0; i < plots * 9; i++) {
            Tile t = tiles[i];
            if (t.type < 0 || t.water > 0.85f) continue;
            float d = dist(hx, hy, Data.tileX(i), Data.tileY(i));
            if (d < r * 0.8f && d < bd) { bd = d; best = i; }
        }
        if (best < 0) return;
        p.water -= 1f;
        float splash = p.lvl[2] >= 2 ? 80f : (p.lvl[2] == 1 ? 56f : 1f);
        float bx = Data.tileX(best), by = Data.tileY(best);
        for (int i = 0; i < plots * 9; i++) {
            Tile t = tiles[i];
            if (t.type >= 0 && dist(bx, by, Data.tileX(i), Data.tileY(i)) <= splash) t.water = 1f;
        }
        ev(EV_WATER, bx, by, 0); tipDone(2);
    }

    void emote(Player p, int e) { if (e < 0 || e >= Data.EMOTE.length) return; p.emote = e; p.emoteT = 3.5f; ev(EV_EMOTE, p.x, p.y, e); }

    // ------------------------------------------------------------ shop
    static final int ADDON_ITEM0 = 1000;

    int price(Player p, int item) {
        float m = costMul();
        if (item >= 0 && item < 5) return item < plots ? (int) Math.ceil(Data.SEED_COST[item] * Data.COST_MUL[diff]) : -1;
        if (item >= 10 && item < 15) return item - 10 < plots ? (int) Math.ceil(Data.SEED_COST[item - 10] * Data.COST_MUL[diff] * 5 * 0.9f) : -1;
        if (item >= 20 && item < 24) { int u = item - 20; return p.lvl[u] >= 3 ? -1 : Math.round(Data.UP_COST[u][p.lvl[u]] * m); }
        if (item == 24) return p.saw ? -1 : Math.round(Data.SAW_COST * m);
        if (item == 30) return plots >= 5 ? -1 : Math.round(Data.KEY_COST[plots - 1] * m);
        if (item >= 40 && item < 44) return (boatMask & (1 << (item - 40))) != 0 ? -1 : Math.round(Data.PART_COST[item - 40] * m);
        if (item >= ADDON_ITEM0) { AddonEngineController.AddonItem ai = AddonEngineController.item(item - ADDON_ITEM0); return ai == null ? -1 : ai.price; }
        if (item >= 50 && item < 50 + Data.HAT.length) { int h = item - 50; return (p.hatMask & (1 << h)) != 0 ? 0 : Data.HAT_COST[h]; }
        return -1;
    }

    boolean buy(Player p, int item) {
        if (p.state != ST_ALIVE || !p.inside) { err(p, 9); return false; }
        int c = price(p, item);
        if (c < 0) { err(p, 10); return false; }
        if (item == 40 && wood < Data.PART_WOOD) { err(p, 11); return false; }
        if (c > coins) { err(p, 12); return false; }
        coins -= c;
        if (item < 5) p.seeds[item]++;
        else if (item < 15) p.seeds[item - 10] += 5;
        else if (item < 24) { int u = item - 20; p.lvl[u]++; if (u == 1) p.oil = Data.OIL_CAP[p.lvl[1]]; if (u == 2) p.water = Data.CAN_CAP[p.lvl[2]]; ev(EV_UPGRADE, p.x, p.y, u); }
        else if (item == 24) p.saw = true;
        else if (item == 30) { plots++; ev(EV_UNLOCK, Data.PLOTS[plots - 1][0] + 100, Data.PLOTS[plots - 1][1] + 100, plots); }
        else if (item >= ADDON_ITEM0) {
            int slot = 0; for (int i = 0; i < NP; i++) if (pl[i] == p) slot = i;
            ev(EV_ADDON_ITEM, p.x, p.y, slot * 100 + (item - ADDON_ITEM0));
        } else if (item < 44) {
            int b = item - 40;
            if (b == 0) wood -= Data.PART_WOOD;
            boatMask |= 1 << b; ev(EV_PART, Data.BOAT_X, Data.BOAT_Y, b);
            if (boatMask == 15) showTip(9, 20);
        } else if (item >= 50) { if (c == 0 && (p.hatMask & (1 << (item - 50))) != 0) { /* equip only */ } else p.hatMask |= 1 << (item - 50); p.hat = item - 50; }
        ev(EV_BUY, p.x, p.y, item);
        if (tip == 5) { tip = -1; }
        return true;
    }

    /** Addon scripts: change a stat. Team stats: coins, wood, lives. Per player (everyone): oil, water, stamina. */
    void addonStat(String stat, float v) {
        if ("coins".equals(stat)) coins = Math.max(0, coins + Math.round(v));
        else if ("wood".equals(stat)) wood = Math.max(0, wood + Math.round(v));
        else if ("lives".equals(stat)) lives = Math.max(0, lives + Math.round(v));
        else for (int i = 0; i < NP; i++) {
            Player p = pl[i]; if (!p.active) continue;
            if ("oil".equals(stat)) p.oil = clamp(p.oil + v, 0, Data.OIL_CAP[p.lvl[1]]);
            else if ("water".equals(stat)) p.water = clamp(p.water + v, 0, Data.CAN_CAP[p.lvl[2]]);
            else if ("stamina".equals(stat)) p.stamina = clamp(p.stamina + v, 0, 1);
        }
    }

    void setSel(Player p, int s) { if (s >= 0 && s < 5) p.sel = s; }

    // ------------------------------------------------------------ tutorial
    void tipDone(int step) { if (tip == step) { tip = step + 1; tipT = 0; tipTimed = false; ev(EV_TIP, 0, 0, tip); } }
    void showTip(int id, float secs) {
        if (tip >= 0 && tip <= 5 && T < 150) return;
        tip = id; tipTimed = true; tipHold = secs; ev(EV_TIP, 0, 0, id);
    }

    // ------------------------------------------------------------ client-side smoothing (all roles)
    void smooth(float dt) {
        float k = Math.min(1f, dt * 14f);
        for (int i = 0; i < NP; i++) {
            Player p = pl[i];
            if (!p.active) continue;
            if (!authority && i != mySlot) { p.rx += (p.x - p.rx) * k; p.ry += (p.y - p.ry) * k; if (dist(p.x, p.y, p.rx, p.ry) > 200) { p.rx = p.x; p.ry = p.y; } }
            else { p.rx = p.x; p.ry = p.y; }
        }
        for (int i = 0; i < NM; i++) {
            Mob m = mob[i];
            if (authority) { m.rx = m.x; m.ry = m.y; }
            else { m.rx += (m.x - m.rx) * k; m.ry += (m.y - m.ry) * k; if (dist(m.x, m.y, m.rx, m.ry) > 250) { m.rx = m.x; m.ry = m.y; } }
        }
    }

    // ------------------------------------------------------------ snapshot
    void write(DataOutputStream o) throws IOException {
        o.writeByte(phase); o.writeByte(diff); o.writeFloat(T); o.writeInt(coins); o.writeByte(lives); o.writeByte(wood);
        o.writeByte(boatMask); o.writeByte(plots); o.writeByte(tip); o.writeByte(candles); o.writeBoolean(door); o.writeByte(nStart);
        o.writeFloat(winT); o.writeFloat(endT);
        o.writeBoolean(endless); o.writeByte(island); o.writeByte(statIslands); o.writeFloat(islandT0);
        o.writeShort(statHarv); o.writeShort(statCoins); o.writeShort(statDowns); o.writeShort(statRev); o.writeShort(statFish);
        for (int i = 0; i < NP; i++) {
            Player p = pl[i];
            o.writeByte((p.active ? 1 : 0) | (p.bot ? 2 : 0));
            if (!p.active) continue;
            o.writeUTF(p.name);
            o.writeFloat(p.x); o.writeFloat(p.y); o.writeFloat(p.fx); o.writeFloat(p.fy);
            o.writeByte(p.state); o.writeFloat(p.stateT); o.writeFloat(p.inv);
            int f = (p.moving ? 1 : 0) | (p.sprint ? 2 : 0) | (p.lampRaise ? 4 : 0) | (p.waterHeld ? 8 : 0) | (p.actHeld ? 16 : 0)
                | (p.inside ? 32 : 0) | (p.lampOn ? 64 : 0) | (p.saw ? 128 : 0);
            o.writeByte(f);
            o.writeFloat(p.stamina); o.writeFloat(p.water); o.writeFloat(p.oil);
            for (int j = 0; j < 4; j++) o.writeByte(p.lvl[j]);
            for (int j = 0; j < 5; j++) { o.writeByte(p.seeds[j]); o.writeByte(p.crops[j]); }
            o.writeShort(p.fish); o.writeShort(p.fishVal); o.writeByte(p.sel); o.writeByte(p.hat); o.writeInt(p.hatMask);
            o.writeByte(p.emote); o.writeFloat(p.emoteT); o.writeFloat(p.reviveProg); o.writeByte(p.fishState); o.writeFloat(p.fishT);
            o.writeByte(p.tele); o.writeFloat(p.animT); o.writeBoolean(p.aboard);
        }
        for (int i = 0; i < NT; i++) { Tile t = tiles[i]; o.writeByte(t.type); o.writeShort((int) (t.grow * 1000)); o.writeByte((int) (t.water * 100)); }
        for (int i = 0; i < NM; i++) { Mob m = mob[i]; o.writeBoolean(m.on); o.writeFloat(m.x); o.writeFloat(m.y); o.writeFloat(m.t); o.writeByte(m.st); o.writeByte(m.a); o.writeByte((int) m.face); }
        for (int i = 0; i < NC; i++) { Coin c = coin[i]; o.writeBoolean(c.on); if (c.on) { o.writeFloat(c.x); o.writeFloat(c.y); o.writeByte(c.v); } }
        for (int i = 0; i < NL; i++) o.writeByte(logs[i].hp);
        int n = net.n;
        o.writeByte(n);
        for (int i = 0; i < n; i++) { o.writeByte(net.t[i]); o.writeFloat(net.x[i]); o.writeFloat(net.y[i]); o.writeShort(net.a[i]); }
        net.n = 0;
    }

    void read(DataInputStream in) throws IOException {
        phase = in.readByte(); diff = in.readByte(); float ts = in.readFloat(); coins = in.readInt(); lives = in.readByte(); wood = in.readByte();
        boatMask = in.readByte(); plots = in.readByte(); tip = in.readByte(); candles = in.readByte(); door = in.readBoolean(); nStart = in.readByte();
        winT = in.readFloat(); endT = in.readFloat();
        endless = in.readBoolean(); island = in.readUnsignedByte(); statIslands = in.readUnsignedByte(); islandT0 = in.readFloat();
        if (Math.abs(ts - T) > 1.5f) T = ts; else T += (ts - T) * 0.35f;
        statHarv = in.readShort(); statCoins = in.readShort(); statDowns = in.readShort(); statRev = in.readShort(); statFish = in.readShort();
        for (int i = 0; i < NP; i++) {
            Player p = pl[i];
            int fl = in.readUnsignedByte();
            p.active = (fl & 1) != 0; p.bot = (fl & 2) != 0;
            if (!p.active) continue;
            p.name = in.readUTF();
            boolean me = i == mySlot;
            float x = in.readFloat(), y = in.readFloat(), fx = in.readFloat(), fy = in.readFloat();
            p.state = in.readByte(); p.stateT = in.readFloat(); p.inv = in.readFloat();
            int f = in.readUnsignedByte();
            float st = in.readFloat();
            p.water = in.readFloat(); p.oil = in.readFloat();
            for (int j = 0; j < 4; j++) p.lvl[j] = in.readByte();
            for (int j = 0; j < 5; j++) { p.seeds[j] = in.readUnsignedByte(); p.crops[j] = in.readUnsignedByte(); }
            p.fish = in.readShort(); p.fishVal = in.readShort(); p.sel = in.readByte(); p.hat = in.readByte(); p.hatMask = in.readInt();
            p.emote = in.readByte(); p.emoteT = in.readFloat(); p.reviveProg = in.readFloat(); p.fishState = in.readByte(); p.fishT = in.readFloat();
            int tele = in.readUnsignedByte(); float an = in.readFloat(); p.aboard = in.readBoolean();
            p.saw = (f & 128) != 0; p.inside = (f & 32) != 0; p.lampOn = (f & 64) != 0;
            if (!me) {
                p.x = x; p.y = y; p.fx = fx; p.fy = fy; p.moving = (f & 1) != 0; p.sprint = (f & 2) != 0; p.lampRaise = (f & 4) != 0;
                p.waterHeld = (f & 8) != 0; p.actHeld = (f & 16) != 0; p.stamina = st; p.animT = an;
            } else if (tele != (p.tele & 255) || p.state != ST_ALIVE) {
                p.x = x; p.y = y; p.rx = x; p.ry = y;
            }
            p.tele = tele;
        }
        for (int i = 0; i < NT; i++) { Tile t = tiles[i]; t.type = in.readByte(); t.grow = in.readShort() / 1000f; t.water = in.readByte() / 100f; }
        for (int i = 0; i < NM; i++) {
            Mob m = mob[i]; boolean was = m.on;
            m.on = in.readBoolean(); m.x = in.readFloat(); m.y = in.readFloat(); m.t = in.readFloat(); m.st = in.readByte(); m.a = in.readByte(); m.face = in.readByte();
            if (m.on && !was) { m.rx = m.x; m.ry = m.y; }
        }
        for (int i = 0; i < NC; i++) { Coin c = coin[i]; c.on = in.readBoolean(); if (c.on) { c.x = in.readFloat(); c.y = in.readFloat(); c.v = in.readByte(); } }
        for (int i = 0; i < NL; i++) logs[i].hp = in.readByte();
        int n = in.readUnsignedByte();
        for (int i = 0; i < n; i++) { int t = in.readByte(); float x = in.readFloat(), y = in.readFloat(); int a = in.readShort(); local.add(t, x, y, a); }
    }
}
