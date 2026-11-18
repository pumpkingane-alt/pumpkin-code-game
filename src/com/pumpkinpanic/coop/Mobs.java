package com.pumpkinpanic.coop;

/** Monster, crow, clown and bot AI (authority side only). */
final class Mobs {
    private Mobs() {}

    static void update(World w, float dt) {
        candles(w, dt); skin(w, dt); demon(w, dt); clowns(w, dt); stag(w, dt); crow(w, dt); coins(w, dt); logs(w, dt); tips(w, dt);
    }

    static World.Player nearest(World w, float x, float y, int mode) {
        World.Player best = null; float bd = 1e9f;
        for (int i = 0; i < World.NP; i++) {
            World.Player p = w.pl[i];
            if (!p.active || p.state != World.ST_ALIVE) continue;
            if (mode == 1 && p.inside) continue;
            if (mode == 2 && !p.inside) continue;
            float d = World.dist(x, y, p.x, p.y);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }
    static boolean anyInside(World w) { return nearest(w, 0, 0, 2) != null; }

    static void move(World.Mob m, float tx, float ty, float spd, float dt, int solidTo) {
        float dx = tx - m.x, dy = ty - m.y;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < 0.001f) return;
        float step = Math.min(d, spd * dt), ux = dx / d, uy = dy / d;
        float nx = m.x + ux * step;
        if (solidTo <= 0 || !World.blockedRect(nx, m.y, 10, 0, solidTo)) m.x = nx;
        float ny = m.y + uy * step;
        if (solidTo <= 0 || !World.blockedRect(m.x, ny, 10, 0, solidTo)) m.y = ny;
        if (Math.abs(ux) > 0.25f) m.face = ux > 0 ? 1 : -1;
    }

    static int nearestEdge(float x, float y) {
        int b = 0; float bd = 1e9f;
        for (int i = 0; i < Data.EDGE.length; i++) { float d = World.dist(x, y, Data.EDGE[i][0], Data.EDGE[i][1]); if (d < bd) { bd = d; b = i; } }
        return b;
    }

    // ------------------------------------------------------------ candles + demon
    static void blow(World w) {
        for (int i = 0; i < 3; i++) if ((w.candles & (1 << i)) != 0) { w.candles &= ~(1 << i); w.ev(World.EV_CANDLE_OUT, Data.CANDLE_X[i], Data.CANDLE_Y, i); return; }
    }
    static void candles(World w, float dt) {
        if (anyInside(w)) w.emptyT = 0; else w.emptyT += dt;
        if (w.candles != 7 && w.emptyT > 12f && !w.mob[World.M_DEMON].on) { w.candles = 7; w.ev(World.EV_CANDLE_LIT, Data.DOOR_X, 600, 0); }
    }

    static void demon(World w, float dt) {
        World.Mob m = w.mob[World.M_DEMON];
        switch (m.st) {
            case 0:
                if (w.candles == 0 && anyInside(w)) {
                    w.demonArm -= dt;
                    if (w.demonArm <= 0) { m.on = true; m.x = Data.BED_X + 20; m.y = Data.BED_Y + 25; m.st = 1; m.t = 0; w.ev(World.EV_DEMON, m.x, m.y, 0); }
                } else w.demonArm = 4f / w.danger();
                break;
            case 1:
                m.t += dt;
                if (m.t > 2.2f) { m.st = 2; m.t = 0; }
                break;
            case 2: {
                m.t += dt;
                World.Player tg = nearest(w, m.x, m.y, 2);
                boolean warded = false;
                for (int i = 0; i < World.NP; i++) {
                    World.Player p = w.pl[i];
                    if (p.active && p.state == World.ST_ALIVE && p.lampRaise && p.oil > 0 && World.dist(m.x, m.y, p.x, p.y) < 170f) {
                        warded = true;
                        move(m, m.x + (m.x - p.x), m.y + (m.y - p.y), 120f, dt, 0);
                    }
                }
                if (tg == null) { w.skinHitT = 0; m.a++; if (m.a > 200) m.st = 3; }
                else {
                    m.a = 0;
                    if (!warded) move(m, tg.x, tg.y, 118f, dt, 0);
                    if (World.dist(m.x, m.y, tg.x, tg.y) < 26f) w.hurt(tg, 3);
                }
                m.x = World.clamp(m.x, Data.IX0 + 14, Data.IX1 - 14); m.y = World.clamp(m.y, Data.IY0 + 14, Data.IY1 - 10);
                if (w.candles != 0) m.st = 3;
                break;
            }
            default:
                m.t += dt;
                if (m.t > 1.4f) { m.on = false; m.st = 0; m.t = 0; m.a = 0; w.demonArm = 4f / w.danger(); }
                break;
        }
        if (m.st == 3 && m.t == 0) m.t = 0.001f;
    }

    // ------------------------------------------------------------ skinwalker
    static void skin(World w, float dt) {
        World.Mob m = w.mob[World.M_SKIN];
        int d = w.diff;
        switch (m.st) {
            case 0:
                w.skinT -= dt;
                if (w.skinT <= 0 && nearest(w, 0, 0, 0) != null) {
                    float[] e = Data.EDGE[w.rnd.nextInt(Data.EDGE.length)];
                    m.on = true; m.x = e[0]; m.y = e[1]; m.st = 1; m.t = 0; w.skinBlew = false; w.skinStun = 0;
                    w.ev(World.EV_SKIN_ARRIVE, m.x, m.y, 0);
                    if (!w.firstSkin) { w.firstSkin = true; w.showTip(7, 14); }
                }
                break;
            case 1:
                m.t += dt;
                if (m.t > 7f) { m.st = 2; m.t = 0; }
                break;
            case 2: {
                m.t += dt;
                World.Player tg = nearest(w, m.x, m.y, 1);
                float spd = Data.SKIN_SPD[d] * w.spdMul();
                boolean warded = false;
                for (int i = 0; i < World.NP; i++) {
                    World.Player p = w.pl[i];
                    if (p.active && p.state == World.ST_ALIVE && p.lampRaise && p.oil > 0 && World.dist(m.x, m.y, p.x, p.y) < w.lampR(p) * 0.85f) warded = true;
                }
                if (w.skinStun > 0) { w.skinStun -= dt; spd = 0; } else if (warded) spd *= 0.2f;
                if (tg != null) {
                    chase(m, tg.x, tg.y, spd, dt, 6);
                    if (spd > 0 && World.dist(m.x, m.y, tg.x, tg.y) < 28f) { w.hurt(tg, 1); w.skinStun = 2.2f; w.ev(World.EV_SKIN_HIT, m.x, m.y, 1); }
                } else {
                    chase(m, Data.DOOR_X + (float) Math.sin(w.T * 0.8f) * 90f, 808f, spd * 0.6f, dt, 6);
                    w.skinHitT -= dt;
                    if (w.skinHitT <= 0 && anyInside(w)) { w.skinHitT = 3.2f; w.ev(World.EV_SKIN_HIT, m.x, m.y, 0); }
                }
                if (!w.skinBlew && m.t > 4f && anyInside(w)) { blow(w); w.skinBlew = true; }
                if (m.t > 36f + d * 4f) { m.st = 3; m.t = 0; m.a = nearestEdge(m.x, m.y); w.ev(World.EV_SKIN_LEAVE, m.x, m.y, 0); }
                break;
            }
            default: {
                m.t += dt;
                float[] e = Data.EDGE[m.a];
                move(m, e[0], e[1], 230f, dt, 0);
                if (World.dist(m.x, m.y, e[0], e[1]) < 30f || m.t > 9f) {
                    m.on = false; m.st = 0; m.t = 0;
                    w.skinT = Data.SKIN_GAP[d] / w.pf() / w.danger() * (0.85f + w.rnd.nextFloat() * 0.3f);
                }
            }
        }
    }

    // ------------------------------------------------------------ clowns
    static void clowns(World w, float dt) {
        int maxC = w.nStart >= 3 ? 2 : 1;
        w.clownT -= dt;
        if (w.clownT <= 0) {
            int slot = -1;
            for (int i = 0; i < maxC; i++) if (!w.mob[World.M_CLOWN0 + i].on) { slot = i; break; }
            World.Player any = nearest(w, 0, 0, 0);
            if (slot >= 0 && any != null) {
                World.Mob m = w.mob[World.M_CLOWN0 + slot];
                float px = 0, py = 0;
                for (int tries = 0; tries < 12; tries++) {
                    px = Data.WX0 + 60 + w.rnd.nextFloat() * (Data.WX1 - Data.WX0 - 120);
                    py = Data.WY0 + 60 + w.rnd.nextFloat() * (Data.WY1 - Data.WY0 - 140);
                    boolean bad = px > Data.CX0 - 80 && px < Data.CX1 + 80 && py > Data.CY0 - 80 && py < Data.CY1 + 80;
                    World.Player n = nearest(w, px, py, 0);
                    if (!bad && n != null && World.dist(px, py, n.x, n.y) > 320f) break;
                }
                m.on = true; m.x = px; m.y = py; m.st = 1; m.t = 0; m.a = w.rnd.nextInt(3);
                w.ev(World.EV_CLOWN, px, py, 0);
                if (!w.firstClown) { w.firstClown = true; w.showTip(8, 14); }
                w.clownT = Data.CLOWN_GAP[w.diff] / w.pf() / w.danger() * (0.85f + w.rnd.nextFloat() * 0.3f);
            } else w.clownT = 5f;
        }
        for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) {
            World.Mob m = w.mob[i];
            if (!m.on) continue;
            m.t += dt;
            switch (m.st) {
                case 1:
                    if (m.t > (w.endless ? Math.max(14f, Data.CLOWN_WAIT[w.diff] / w.danger()) : Data.CLOWN_WAIT[w.diff])) { m.st = 3; m.t = 0; w.ev(World.EV_CLOWN_BLUE, m.x, m.y, 0); }
                    break;
                case 2:
                    if (m.t > 2.6f) { m.on = false; m.st = 0; }
                    break;
                case 3: {
                    World.Player tg = nearest(w, m.x, m.y, 0);
                    if (tg == null) { m.st = 4; m.t = 0; break; }
                    route(m.x, m.y, tg.x, tg.y, WP);
                    float tx = WP[0], ty = WP[1];
                    move(m, tx, ty, 138f * w.spdMul(), dt, 5);
                    if (World.dist(m.x, m.y, tg.x, tg.y) < 26f) { w.hurt(tg, 4); m.t += 4f; }
                    if (m.t > 42f) { m.st = 4; m.t = 0; }
                    break;
                }
                case 4:
                    if (m.t > 2.5f) { m.on = false; m.st = 0; }
                    break;
                default: break;
            }
        }
    }

    // ------------------------------------------------------------ stag / wendigo
    static World.Player stagTarget(World w) {
        if (w.stagTarget < 0) return null;
        World.Player p = w.pl[w.stagTarget];
        return (p.active && p.state == World.ST_ALIVE && !p.inside) ? p : null;
    }
    static void stagScare(World w, World.Mob m) {
        m.st = 4; m.t = 0; w.stagCool = 22f / w.danger(); w.ev(World.EV_SCARE, m.x, m.y, 1);
    }

    static void stag(World w, float dt) {
        World.Mob m = w.mob[World.M_STAG];
        int d = w.diff;
        if (w.islandTime() < Data.STAG_START[d] / w.danger()) { m.on = false; return; }
        if (!m.on) { m.on = true; float[] e = Data.EDGE[w.rnd.nextInt(Data.EDGE.length)]; m.x = e[0]; m.y = e[1]; m.st = 0; m.t = 0; w.stagTx = e[0]; w.stagTy = e[1]; }
        float dk = w.dark();
        w.stagCool -= dt;
        switch (m.st) {
            case 0: {
                m.t += dt;
                World.Player near = nearest(w, m.x, m.y, 0);
                if (near != null && World.dist(m.x, m.y, near.x, near.y) < 150f && dk < 0.4f) { move(m, m.x + (m.x - near.x), m.y + (m.y - near.y), 120f, dt, 0); }
                else if (World.dist(m.x, m.y, w.stagTx, w.stagTy) > 20f) move(m, w.stagTx, w.stagTy, 38f, dt, 0);
                if (m.t > 12f) { m.t = 0; float[] e = Data.EDGE[w.rnd.nextInt(Data.EDGE.length)]; w.stagTx = e[0]; w.stagTy = e[1]; }
                if (dk > 0.55f && w.stagCool <= 0) {
                    int cnt = 0, pick = -1;
                    for (int i = 0; i < World.NP; i++) { World.Player p = w.pl[i]; if (p.active && p.state == World.ST_ALIVE && !p.inside) { cnt++; if (w.rnd.nextInt(cnt) == 0) pick = i; } }
                    if (pick >= 0) { w.stagTarget = pick; m.st = 1; m.t = 0; }
                }
                break;
            }
            case 1: {
                World.Player tg = stagTarget(w);
                if (tg == null || dk < 0.3f) { m.st = 0; m.t = 0; break; }
                float want = Math.max(160f, w.lampR(tg) + 45f);
                if (World.dist(m.x, m.y, tg.x, tg.y) > want) chase(m, tg.x, tg.y, 80f, dt, 6);
                else { m.st = 2; m.t = 0; m.a = 30 + w.rnd.nextInt(35); w.stagLook = 0; w.ev(World.EV_STAG, m.x, m.y, 0); }
                break;
            }
            case 2: {
                World.Player tg = stagTarget(w);
                if (tg == null || dk < 0.3f) { m.st = 0; m.t = 0; break; }
                m.t += dt;
                m.face = tg.x > m.x ? 1 : -1;
                if (tg.lampRaise && tg.oil > 0 && World.dist(m.x, m.y, tg.x, tg.y) < w.lampR(tg) + 40f) { w.stagLook += dt; if (w.stagLook > 0.9f) { stagScare(w, m); break; } }
                else w.stagLook = Math.max(0, w.stagLook - dt);
                if (m.t * 10f > m.a) { m.st = 3; m.t = 0; w.ev(World.EV_ROAR, m.x, m.y, 0); }
                break;
            }
            case 3: {
                m.t += dt;
                World.Player tg = nearest(w, m.x, m.y, 1);
                if (tg == null || m.t > 10f) { m.st = 4; m.t = 0; w.stagCool = 14f / w.danger(); break; }
                boolean scared = false;
                for (int i = 0; i < World.NP; i++) {
                    World.Player p = w.pl[i];
                    if (p.active && p.state == World.ST_ALIVE && p.lampRaise && p.oil > 0 && World.dist(m.x, m.y, p.x, p.y) < 210f) scared = true;
                }
                if (scared) { w.stagLook += dt; if (w.stagLook > 0.5f) { stagScare(w, m); break; } } else w.stagLook = 0;
                chase(m, tg.x, tg.y, Data.STAG_SPD[d] * w.spdMul(), dt, 6);
                if (World.dist(m.x, m.y, tg.x, tg.y) < 30f) { w.hurt(tg, 2); m.st = 4; m.t = 0; w.stagCool = 16f / w.danger(); }
                break;
            }
            default: {
                m.t += dt;
                int e = nearestEdge(m.x, m.y);
                move(m, Data.EDGE[e][0], Data.EDGE[e][1], 300f, dt, 0);
                if (m.t > 5f) { m.st = 0; m.t = 0; }
            }
        }
    }

    // ------------------------------------------------------------ crow, coins, logs, tips
    static void dropCoin(World w, float x, float y, int v) {
        int pick = 0; float old = -1;
        for (int i = 0; i < World.NC; i++) {
            World.Coin c = w.coin[i];
            if (!c.on) { pick = i; break; }
            if (c.t > old) { old = c.t; pick = i; }
        }
        World.Coin c = w.coin[pick]; c.on = true; c.x = x; c.y = y; c.v = v; c.t = 0;
    }

    static void crow(World w, float dt) {
        World.Mob m = w.mob[World.M_CROW];
        m.on = true;
        switch (m.st) {
            case 0:
                m.x = 1100; m.y = 478; w.crowT -= dt;
                if (w.crowT <= 0 && nearest(w, 0, 0, 0) != null) {
                    float px, py;
                    do { px = 640 + w.rnd.nextFloat() * 1260; py = 440 + w.rnd.nextFloat() * 800; } while (px > Data.CX0 - 30 && px < Data.CX1 + 30 && py < Data.CY1 + 40);
                    w.crowTx = px; w.crowTy = py; m.st = 1; m.t = 0; w.ev(World.EV_CAW, m.x, m.y, 0);
                }
                break;
            case 1:
                move(m, w.crowTx, w.crowTy, 250f, dt, 0);
                if (World.dist(m.x, m.y, w.crowTx, w.crowTy) < 10f) { m.st = 2; m.t = 0; }
                break;
            case 2:
                m.t += dt;
                if (m.t > 0.8f) { dropCoin(w, m.x, m.y + 16, 4 + w.day() * 2 + w.rnd.nextInt(7)); w.ev(World.EV_DROP, m.x, m.y, 0); m.st = 3; m.t = 0; }
                break;
            default:
                move(m, 1100, 478, 250f, dt, 0);
                if (World.dist(m.x, m.y, 1100, 478) < 10f) { m.st = 0; w.crowT = 38f + w.rnd.nextFloat() * 30f; }
        }
    }

    static void coins(World w, float dt) {
        for (int i = 0; i < World.NC; i++) {
            World.Coin c = w.coin[i];
            if (!c.on) continue;
            c.t += dt;
            for (int j = 0; j < World.NP; j++) {
                World.Player p = w.pl[j];
                if (p.active && p.state == World.ST_ALIVE && World.dist(p.x, p.y, c.x, c.y) < 28f) {
                    w.coins += c.v; w.statCoins += c.v; c.on = false; w.ev(World.EV_COIN, c.x, c.y, c.v); break;
                }
            }
        }
    }

    static void logs(World w, float dt) {
        for (int i = 0; i < World.NL; i++) { World.LogN l = w.logs[i]; if (l.hp <= 0) { l.t -= dt; if (l.t <= 0) l.hp = 4; } }
    }

    static void tips(World w, float dt) {
        if (w.tipTimed) { w.tipHold -= dt; if (w.tipHold <= 0) { w.tip = -1; w.tipTimed = false; } return; }
        w.tipT += dt;
        if (w.tip == 0 && w.tipT > 8f) w.tipDone(0);
        if (w.tip == 5 && w.tipT > 70f) w.tip = -1;
    }

    static final float RX0 = 898, RY0 = 468, RX1 = 1302, RY1 = 762;   // cabin hull
    static final float EX0 = 858, EY0 = 428, EX1 = 1342, EY1 = 800;   // waypoint corners around it
    static boolean hitsHull(float x0, float y0, float x1, float y1) {
        for (int i = 0; i <= 16; i++) {
            float f = i / 16f, px = x0 + (x1 - x0) * f, py = y0 + (y1 - y0) * f;
            if (px > RX0 && px < RX1 && py > RY0 && py < RY1) return true;
        }
        return false;
    }
    /** Walk around the cabin when the straight line is blocked by it. */
    static void detour(float x, float y, float tx, float ty, float[] out) {
        out[0] = tx; out[1] = ty;
        if (!hitsHull(x, y, tx, ty)) return;
        float bx = tx, by = ty, bc = 1e9f;
        for (int c = 0; c < 4; c++) {
            float cx = (c & 1) == 0 ? EX0 : EX1, cy = (c & 2) == 0 ? EY0 : EY1;
            if (World.dist(x, y, cx, cy) < 18f || hitsHull(x, y, cx, cy)) continue;
            float cost = World.dist(x, y, cx, cy) + World.dist(cx, cy, tx, ty);
            if (cost < bc) { bc = cost; bx = cx; by = cy; }
        }
        out[0] = bx; out[1] = by;
    }
    /** Door-aware waypoint: next point to head for when travelling between inside/outside the cabin. */
    static void route(float x, float y, float tx, float ty, float[] out) {
        boolean in = World.inCabin(x, y), tIn = World.inCabin(tx, ty);
        float gx = tx, gy = ty;
        boolean aligned = Math.abs(x - Data.DOOR_X) < 26f;
        if (tIn && !in) { gx = Data.DOOR_X; gy = (aligned && y < 812f) ? 700f : 805f; if (!aligned && y < 790f) { detour(x, y, gx, gy, out); return; } }
        else if (!tIn && in) { gx = Data.DOOR_X; gy = (aligned && y > 700f) ? 810f : 712f; }
        else if (!tIn) {
            if (Math.abs(x - Data.DOOR_X) < 50f && y > 735f && y < 768f) { gx = Data.DOOR_X; gy = 806f; }
            else { detour(x, y, tx, ty, out); return; }
        }
        out[0] = gx; out[1] = gy;
    }
    static void chase(World.Mob m, float tx, float ty, float spd, float dt, int solidTo) {
        detour(m.x, m.y, tx, ty, WP);
        move(m, WP[0], WP[1], spd, dt, solidTo);
    }
    private static final float[] WP = new float[2];

    // ------------------------------------------------------------ bots (farm helpers)
    static float goTo(World w, World.Player p, float tx, float ty, boolean run, float dt) {
        route(p.x, p.y, tx, ty, WP);
        float gx = WP[0], gy = WP[1];
        float dx = gx - p.x, dy = gy - p.y, d = (float) Math.sqrt(dx * dx + dy * dy);
        if (p.botWander > 0f) { p.botWander -= dt; dx = p.botX; dy = p.botY; d = 1f; }
        else if (d < 6f) return World.dist(p.x, p.y, tx, ty);
        float ox = p.x, oy = p.y;
        w.movePlayer(p, dx / d, dy / d, run, dt);
        if (World.dist(ox, oy, p.x, p.y) < 0.25f * dt * 140f) {
            p.botStuck += dt;
            if (p.botStuck > 0.7f) { p.botStuck = 0; p.botWander = 0.55f; double a = w.rnd.nextFloat() * 6.283; p.botX = (float) Math.cos(a); p.botY = (float) Math.sin(a); }
        } else p.botStuck = 0;
        return World.dist(p.x, p.y, tx, ty);
    }

    static void flee(World w, World.Player p, float cx, float cy, float dt) {
        float dx = p.x - cx, dy = p.y - cy, d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < 1f) { dx = 1; dy = 0; d = 1; }
        dx /= d; dy /= d;
        float tx = World.clamp(p.x + dx * 300f, 640f, 1900f), ty = World.clamp(p.y + dy * 300f, 430f, 1250f);
        if (World.dist(p.x, p.y, tx, ty) < 140f) { tx = World.clamp(p.x - dy * 300f, 640f, 1900f); ty = World.clamp(p.y + dx * 300f, 430f, 1250f); }
        goTo(w, p, tx, ty, true, dt);
    }

    static void bot(World w, World.Player p, float dt) {
        p.waterHeld = false; p.lampRaise = false; p.actHeld = false; p.moving = false; p.sprint = false;
        if (p.state != World.ST_ALIVE) return;
        p.botAct -= dt;
        float dk = w.dark();
        int idx = 0;
        for (int i = 0; i < World.NP; i++) if (w.pl[i] == p) idx = i;
        World.Mob sk = w.mob[World.M_SKIN], sg = w.mob[World.M_STAG], dm = w.mob[World.M_DEMON];
        boolean hide = dk > 0.6f || (sk.on && (sk.st == 1 || sk.st == 2)) || (sg.on && sg.st >= 2 && sg.st <= 3) || (p.oil < 6f && dk > 0.3f);
        boolean demonIn = dm.on && dm.st != 3;
        for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) {
            World.Mob c = w.mob[i];
            if (c.on && c.st == 3 && World.dist(p.x, p.y, c.x, c.y) < 340f) { flee(w, p, c.x, c.y, dt); return; }
        }
        if (demonIn && p.inside) { goTo(w, p, Data.DOOR_X, 830f, true, dt); return; }
        if (hide) { if (!p.inside) goTo(w, p, 990f + idx * 55f, 690f, true, dt); return; }
        // help: revive a downed mate, cheer a waiting clown
        for (int i = 0; i < World.NP; i++) {
            World.Player q = w.pl[i];
            if (q != p && q.active && q.state == World.ST_DOWN && !q.inside) {
                if (goTo(w, p, q.x, q.y + 18f, false, dt) < 50f) p.actHeld = true;
                return;
            }
        }
        for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) {
            World.Mob c = w.mob[i];
            if (c.on && c.st == 1) {
                if (goTo(w, p, c.x, c.y + 24f, false, dt) < 60f) { w.aim(p); if (p.aimK == World.K_CLOWN) w.act(p); }
                return;
            }
        }
        int crops = 0;
        for (int i = 0; i < 5; i++) crops += p.crops[i];
        int ready = -1, thirsty = -1, empty = -1;
        float rd = 1e9f, td = 1e9f, ed = 1e9f;
        for (int i = 0; i < w.plots * 9; i++) {
            World.Tile t = w.tiles[i];
            float d = World.dist(p.x, p.y, Data.tileX(i), Data.tileY(i));
            if (t.type >= 0 && t.grow >= 1f) { if (d < rd) { rd = d; ready = i; } }
            else if (t.type >= 0 && t.water < 0.3f) { if (d < td) { td = d; thirsty = i; } }
            else if (t.type < 0) { if (d < ed) { ed = d; empty = i; } }
        }
        int seed = -1;
        for (int k = 4; k >= 0; k--) if (p.seeds[k] > 0) { seed = k; break; }
        boolean canPlant = empty >= 0 && seed >= 0;
        int goal; // 1 sell 2 well 3 harvest 4 water 5 plant 6 buy 7 idle
        if (crops >= 6 || (crops > 0 && ready < 0 && thirsty < 0 && !canPlant)) goal = 1;
        else if (p.water < 1f && thirsty >= 0) goal = 2;
        else if (ready >= 0) goal = 3;
        else if (thirsty >= 0) goal = 4;
        else if (canPlant) goal = 5;
        else if (seed < 0 && w.coins >= 8) goal = 6;
        else goal = 7;
        switch (goal) {
            case 1: goTo(w, p, Data.SELL_X, Data.SELL_Y + 42f, false, dt); w.aim(p); if (p.aimK == World.K_SELL && p.botAct <= 0) { w.act(p); p.botAct = 0.6f; } break;
            case 2: goTo(w, p, Data.WELL_X + 62f, Data.WELL_Y + 10f, false, dt); w.aim(p); if (p.aimK == World.K_WELL && p.botAct <= 0) { w.act(p); p.botAct = 0.6f; } break;
            case 3: goTo(w, p, Data.tileX(ready), Data.tileY(ready) + 18f, false, dt); w.aim(p); if (p.aimK == World.K_TILE && p.botAct <= 0) { w.act(p); p.botAct = 0.35f; } break;
            case 4: {
                float r = goTo(w, p, Data.tileX(thirsty), Data.tileY(thirsty) + 24f, false, dt);
                if (r < 40f) { p.fx = 0; p.fy = -1; p.waterHeld = true; }
                break;
            }
            case 5: goTo(w, p, Data.tileX(empty), Data.tileY(empty) + 18f, false, dt); w.aim(p); if (p.aimK == World.K_TILE && p.botAct <= 0) { p.sel = seed; w.act(p); p.botAct = 0.35f; } break;
            case 6: {
                float r = goTo(w, p, 1195f, 700f, false, dt);
                if (p.inside && r < 110f && p.botAct <= 0) {
                    p.botAct = 1.0f;
                    for (int k = w.plots - 1; k >= 0; k--) { int pr = w.price(p, 10 + k); if (pr > 0 && pr <= w.coins / 2) { w.buy(p, 10 + k); break; } }
                    if (p.seeds[0] == 0 && p.seeds[1] == 0 && p.seeds[2] == 0 && p.seeds[3] == 0 && p.seeds[4] == 0 && w.coins >= 3) w.buy(p, 0);
                }
                break;
            }
            default:
                if (w.boatMask == 15) goTo(w, p, Data.BOAT_X - 70f, Data.BOAT_Y + 10f, false, dt);
                else goTo(w, p, 1100f + (idx - 1.5f) * 60f, 890f, false, dt);
        }
    }

    /**
     * Used by addon scripts (spawnMonster). kind: skinwalker, demon, clown, clown_blue, stag,
     * wendigo. Returns false for an unknown kind or when that monster is already out.
     */
    static boolean forceSpawn(World w, String kind, float x, float y) {
        x = World.clamp(x, 0, Data.WW); y = World.clamp(y, 0, Data.WH);
        if ("skinwalker".equals(kind)) {
            World.Mob m = w.mob[World.M_SKIN]; if (m.on) return false;
            m.on = true; m.x = x; m.y = y; m.st = 1; m.t = 0; w.skinBlew = false; w.skinStun = 0;
            w.ev(World.EV_SKIN_ARRIVE, x, y, 0); return true;
        } else if ("demon".equals(kind)) {
            World.Mob m = w.mob[World.M_DEMON]; if (m.on) return false;
            m.on = true; m.x = x; m.y = y; m.st = 1; m.t = 0; w.ev(World.EV_DEMON, x, y, 0); return true;
        } else if ("clown".equals(kind) || "clown_blue".equals(kind)) {
            for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) {
                World.Mob m = w.mob[i]; if (m.on) continue;
                boolean blue = "clown_blue".equals(kind);
                m.on = true; m.x = x; m.y = y; m.st = blue ? 3 : 1; m.t = 0; m.a = w.rnd.nextInt(3);
                w.ev(blue ? World.EV_CLOWN_BLUE : World.EV_CLOWN, x, y, 0); return true;
            }
            return false;
        } else if ("stag".equals(kind) || "wendigo".equals(kind)) {
            World.Mob m = w.mob[World.M_STAG];
            boolean wen = "wendigo".equals(kind);
            m.on = true; m.x = x; m.y = y; m.st = wen ? 3 : 0; m.t = 0; w.stagTx = x; w.stagTy = y;
            if (wen) w.ev(World.EV_ROAR, x, y, 0);
            return true;
        }
        return false;
    }
}
