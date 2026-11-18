package com.pumpkinpanic.coop;

import com.pumpkinpanic.coop.ScriptPilot.Mover;

/** The things the character can do for a script: walk, farm, shop, fish, sail ... Java 7 compatible. */
final class ScriptTasks {
    private ScriptTasks() {}

    static final float ACT_DELAY = 0.28f;

    static World W(ScriptTask t) { return t.host.link.world(); }
    static World.Player P(ScriptTask t) { return t.host.link.me(); }
    static boolean auth(ScriptTask t) { return t.host.link.authority(); }

    /** Human readable reason for a World error code. */
    static String reason(int code) {
        switch (code) {
            case 1: return "no seeds of that crop (buy_seeds)";
            case 2: return "the watering can is empty (refill())";
            case 3: return "nothing to sell";
            case 4: return "the whole crew must be near the boat";
            case 5: return "the boat is missing parts";
            case 6: return "not enough coins for oil";
            case 7: return "you need a saw first: buy(Item.Saw)";
            case 8: return "the fish got away";
            case 9: return "you must be inside the cabin to shop";
            case 10: return "that item is not available (already owned, maxed out, or locked)";
            case 11: return "the hull needs 8 wood (chop trees with a saw)";
            case 12: return "not enough coins";
            default: return "it did not work";
        }
    }

    // ------------------------------------------------------------ plain walking
    static final class Go extends ScriptTask {
        final float tx, ty; final Mover mv = new Mover(); final String label;
        Go(float tx, float ty, float arrive, String label) { this.tx = tx; this.ty = ty; this.label = label; mv.arrive = Math.max(3f, arrive); what = "goto"; timeout = 90f; }
        @Override void resumed(ScriptPilot pl) { mv.reset(); }
        boolean step(ScriptPilot pl, float dt) {
            int st = mv.step(pl, P(this), tx, ty, dt);
            if (st == Mover.ARRIVED) { ok(Boolean.TRUE); return true; }
            if (st == Mover.FAILED) { fail(mv.why); return true; }
            return false;
        }
    }

    /** move(North, 3): walk in a straight line without path finding. */
    static final class MoveDir extends ScriptTask {
        final float dx, dy, dist; float sx, sy, lastMoved, blocked;
        MoveDir(float dx, float dy, float dist) { this.dx = dx; this.dy = dy; this.dist = dist; what = "move"; timeout = 60f; }
        @Override void start(ScriptPilot pl) { World.Player p = P(this); sx = p.x; sy = p.y; lastMoved = 0; }
        boolean step(ScriptPilot pl, float dt) {
            World.Player p = P(this);
            float moved = World.dist(p.x, p.y, sx, sy), rem = dist - moved;
            if (rem <= 2f) { ok(Boolean.TRUE); return true; }
            float sc = Math.min(1f, Math.max(0.3f, rem / 30f));
            pl.ix = dx * sc; pl.iy = dy * sc;
            if (moved - lastMoved < 1.2f * dt * 60f * 0.2f) { blocked += dt; if (blocked > 0.45f) { fail("blocked after " + (int) moved + " units"); return true; } }
            else { blocked = 0; lastMoved = moved; }
            return false;
        }
    }

    // ------------------------------------------------------------ base for "walk to a spot, then do something"
    abstract static class Spot extends ScriptTask {
        float sx, sy, reach = 60f, tol = 12f;
        final Mover mv = new Mover();
        boolean there, ending;
        float cool; Object endResult; String endFail;
        String label = "the spot";

        @Override void resumed(ScriptPilot pl) { mv.reset(); there = false; }

        /** Called once when the character stands at the spot (and every frame afterwards until it ends the task). */
        abstract void act(ScriptPilot pl, float dt);

        protected void finish(Object result, float delay) { ending = true; endResult = result; endFail = null; cool = delay; }
        protected void finishFail(String why, float delay) { ending = true; endResult = Boolean.FALSE; endFail = why; cool = delay; }
        protected void finishQuiet(Object result, String note, float delay) { ending = true; endResult = result; endFail = null; cool = delay; if (note != null) host.lastFail = note; }

        boolean step(ScriptPilot pl, float dt) {
            if (ending) {
                cool -= dt;
                if (cool <= 0) { if (endFail != null) fail(endFail); else ok(endResult); return true; }
                return false;
            }
            if (cool > 0) { cool -= dt; return false; }
            World.Player p = P(this);
            if (!there) {
                float d = World.dist(p.x, p.y, sx, sy);
                if (d <= tol) there = true;
                else if (!pl.autoWalk) {
                    if (d <= reach) there = true;
                    else { fail("too far from " + label + " (" + (int) d + " units away) and auto_walk is off"); return true; }
                } else {
                    mv.arrive = Math.max(2.5f, tol - 3f);
                    int st = mv.step(pl, p, sx, sy, dt);
                    if (st == Mover.ARRIVED) there = true;
                    else if (st == Mover.FAILED) { fail("cannot reach " + label + ": " + mv.why); return true; }
                    else return false;
                }
            }
            act(pl, dt);
            if (ending && cool <= 0) { if (endFail != null) fail(endFail); else ok(endResult); return true; }
            return false;
        }
    }

    /** Send a command to the host / apply directly: ACT. Returns nothing; callers verify the result by state. */
    static void doAct(ScriptTask t) {
        World w = W(t); World.Player p = P(t);
        p.lastErr = 0;
        if (auth(t)) w.act(p); else t.host.link.cmdAct();
    }
    static void doSel(ScriptTask t, int s) {
        World w = W(t); World.Player p = P(t);
        if (p.sel == s) return;
        if (auth(t)) w.setSel(p, s); else { t.host.link.cmdSel(s); p.sel = s; }
    }

    // ------------------------------------------------------------ farming
    static final int PLANT = 0, HARVEST = 1, WATER = 2;

    static final class Farm extends Spot {
        final int kind, crop; int tile; final boolean explicit;
        float wait, waitMax; int phase;
        Farm(int kind, int crop, int tile) {
            this.kind = kind; this.crop = crop; this.tile = tile; explicit = tile >= 0;
            what = kind == PLANT ? "plant" : (kind == HARVEST ? "harvest" : "water");
            timeout = 120f; reach = 46f; tol = 5f;
        }

        private boolean suitable(World w, int i) {
            World.Tile t = w.tiles[i];
            if (kind == PLANT) return t.type < 0;
            if (kind == HARVEST) return t.type >= 0 && t.grow >= 1f;
            return t.type >= 0 && t.grow < 1f && t.water <= 0.85f;
        }

        @Override void start(ScriptPilot pl) {
            World w = W(this); World.Player p = P(this);
            if (!explicit) {
                float best = 1e9f; int bi = -1;
                for (int i = 0; i < w.plots * 9; i++) {
                    if (!suitable(w, i)) continue;
                    float d = World.dist(p.x, p.y, Data.tileX(i), Data.tileY(i));
                    if (!pl.autoWalk && d > 46f) continue;
                    if (d < best) { best = d; bi = i; }
                }
                if (bi < 0) {
                    String why = kind == PLANT ? "no empty soil" + (pl.autoWalk ? "" : " in reach") : (kind == HARVEST ? "nothing is ripe" + (pl.autoWalk ? "" : " in reach") : "no plant needs water" + (pl.autoWalk ? "" : " in reach"));
                    finishQuiet(Boolean.FALSE, what + "(): " + why, 0.05f);
                    return;
                }
                tile = bi;
            } else {
                if (tile < 0 || tile >= w.plots * 9) { finishFail("tile " + tile + " is locked (buy a PlotKey)", 0.05f); return; }
                World.Tile t = w.tiles[tile];
                if (kind == PLANT && t.type >= 0) { finishFail("tile " + tile + " already has a " + Data.CROP[t.type] + " growing", 0.05f); return; }
                if (kind == HARVEST && t.type < 0) { finishFail("tile " + tile + " is empty", 0.05f); return; }
                if (kind == HARVEST && t.grow < 1f) { finishFail("tile " + tile + " is not ripe yet (" + (int) (t.grow * 100) + "%)", 0.05f); return; }
                if (kind == WATER && t.type < 0) { finishFail("tile " + tile + " has no plant to water", 0.05f); return; }
                if (kind == WATER && (t.water > 0.85f || t.grow >= 1f)) { finishQuiet(Boolean.TRUE, null, 0.05f); return; }
            }
            if (kind == PLANT && p.seeds[crop] <= 0) { finishFail("no " + Data.CROP[crop] + " seeds - buy some: buy_seeds(Crop." + Data.CROP[crop] + ", 5)", 0.05f); return; }
            if (kind == WATER && p.water < 1f) { finishFail("the watering can is empty - call refill()", 0.05f); return; }
            sx = Data.tileX(tile); sy = Data.tileY(tile) + 18f;
            label = "tile " + tile;
        }

        void act(ScriptPilot pl, float dt) {
            World w = W(this); World.Player p = P(this);
            World.Tile t = w.tiles[tile];
            if (kind == WATER) {
                p.fx = 0; p.fy = -1;
                if (t.type < 0 || t.grow >= 1f || t.water > 0.85f) { finish(Boolean.TRUE, 0.12f); return; }
                if (p.water < 1f && auth(this)) { finishFail("the watering can ran empty - call refill()", 0.1f); return; }
                pl.water = true;
                wait += dt;
                if (wait > 4f) finishFail("the water did not reach the plant", 0.1f);
                return;
            }
            if (phase == 0) {
                if (kind == PLANT) { if (t.type >= 0) { finishFail("tile " + tile + " is already planted", 0.1f); return; } doSel(this, crop); }
                else if (t.type < 0) { finishFail("tile " + tile + " is empty now", 0.1f); return; }
                else if (t.grow < 1f) { finishFail("tile " + tile + " is not ripe yet", 0.1f); return; }
                p.fx = 0; p.fy = -1;
                doAct(this);
                phase = 1; wait = 0;
                if (auth(this)) { checkDone(w, p); }
                return;
            }
            wait += dt;
            checkDone(w, p);
            if (!ending && wait > 1.4f) finishFail((kind == PLANT ? "planting" : "harvesting") + " did not work", ACT_DELAY);
        }

        private void checkDone(World w, World.Player p) {
            World.Tile t = w.tiles[tile];
            boolean okNow = kind == PLANT ? t.type == crop : t.type < 0;
            if (okNow) finish(Boolean.TRUE, ACT_DELAY);
            else if (auth(this)) finishFail(p.lastErr != 0 ? reason(p.lastErr) : (kind == PLANT ? "could not plant here" : "could not harvest"), ACT_DELAY);
        }
    }

    // ------------------------------------------------------------ simple stations
    static final int SELL = 0, WELL = 1, OIL = 2;

    static final class Station extends Spot {
        final int kind; int phase; float wait; int before; float beforeF;
        Station(int kind) {
            this.kind = kind; what = kind == SELL ? "sell" : (kind == WELL ? "refill" : "refuel"); timeout = 90f;
            if (kind == SELL) { sx = Data.SELL_X; sy = Data.SELL_Y + 42; label = "the sell box"; }
            else if (kind == WELL) { sx = Data.WELL_X + 62; sy = Data.WELL_Y + 10; label = "the well"; }
            else { sx = Data.OIL_X; sy = Data.OIL_Y + 45; label = "the oil barrel"; }
            reach = 60f; tol = 10f;
        }
        void act(ScriptPilot pl, float dt) {
            World w = W(this); World.Player p = P(this);
            if (phase == 0) {
                before = w.coins; beforeF = kind == WELL ? p.water : p.oil;
                if (kind == SELL) { int tot = p.fishVal; for (int i = 0; i < 5; i++) tot += p.crops[i] * Data.CROP_SELL[i]; if (tot <= 0) { finishQuiet(Long.valueOf(0), "sell(): nothing to sell", 0.15f); return; } }
                if (kind == WELL && p.water >= Data.CAN_CAP[p.lvl[2]] - 0.5f) { finish(Boolean.TRUE, 0.1f); return; }
                if (kind == OIL && p.oil >= Data.OIL_CAP[p.lvl[1]] - 1f) { finish(Boolean.TRUE, 0.1f); return; }
                doAct(this); phase = 1; wait = 0;
                if (!auth(this)) return;
            }
            wait += dt;
            boolean changed = kind == SELL ? w.coins > before : (kind == WELL ? p.water > beforeF + 0.01f : p.oil > beforeF + 0.01f);
            if (changed) { finish(kind == SELL ? (Object) Long.valueOf(w.coins - before) : Boolean.TRUE, ACT_DELAY + (kind == SELL ? 0.1f : 0f)); return; }
            if (auth(this)) finishFail(p.lastErr != 0 ? reason(p.lastErr) : "it did not work", 0.15f);
            else if (wait > 1.2f) finishFail("the host did not answer", 0.15f);
        }
    }

    // ------------------------------------------------------------ shopping
    static final class Buy extends Spot {
        final int item, qty; int bought, phase; float wait; int coinsBefore;
        Buy(int item, int qty, String name) {
            this.item = item; this.qty = qty; what = "buy"; label = "the workbench"; timeout = 120f;
            sx = 1195; sy = 700; reach = 90f; tol = 14f;
        }
        @Override void start(ScriptPilot pl) {
            World w = W(this); World.Player p = P(this);
            int price = w.price(p, item);
            if (price < 0) { finishFail(unavailable(w, p, item), 0.05f); return; }
            if (item == 40 && w.wood < Data.PART_WOOD) { finishFail("the hull needs " + Data.PART_WOOD + " wood and you have " + w.wood + " (chop trees with a saw)", 0.05f); return; }
            if (price > w.coins) { finishFail("not enough coins: costs " + price + ", you have " + w.coins, 0.05f); return; }
        }
        void act(ScriptPilot pl, float dt) {
            World w = W(this); World.Player p = P(this);
            if (!p.inside) { finishFail("could not get inside the cabin", 0.1f); return; }
            if (phase == 0) {
                if (bought >= qty) { finish(Long.valueOf(bought), 0.05f); return; }
                int price = w.price(p, item);
                if (price < 0) { if (bought > 0) finish(Long.valueOf(bought), 0.05f); else finishFail(unavailable(w, p, item), 0.05f); return; }
                if (price > w.coins) { if (bought > 0) finishFail("bought " + bought + " of " + qty + ": not enough coins", 0.1f); else finishFail("not enough coins: costs " + price + ", you have " + w.coins, 0.05f); return; }
                if (item == 40 && w.wood < Data.PART_WOOD) { finishFail("the hull needs " + Data.PART_WOOD + " wood", 0.05f); return; }
                coinsBefore = w.coins;
                if (auth(this)) {
                    p.lastErr = 0;
                    if (w.buy(p, item)) { bought++; cool = 0.14f; return; }
                    if (bought > 0) finishFail("bought " + bought + " of " + qty + ": " + reason(p.lastErr), 0.1f); else finishFail(reason(p.lastErr), 0.1f);
                    return;
                }
                host.link.cmdBuy(item); phase = 1; wait = 0; return;
            }
            wait += dt;
            if (w.coins < coinsBefore) { bought++; phase = 0; cool = 0.14f; return; }
            if (wait > 1.0f) finishFail(bought > 0 ? "bought " + bought + " of " + qty + " (the host refused the rest)" : "the host refused the purchase", 0.1f);
        }
    }

    static String unavailable(World w, World.Player p, int item) {
        if (item < 5 || (item >= 10 && item < 15)) return "you cannot buy " + Data.CROP[item % 10] + " seeds yet - unlock the plot first: buy(Item.PlotKey)";
        if (item >= 20 && item < 24) return Data.UP_NAME[item - 20] + " is already at the maximum level";
        if (item == 24) return "you already own a saw";
        if (item == 30) return "all plots are already unlocked";
        if (item >= 40 && item < 44) return Data.PART_NAME[item - 40] + " is already built";
        return "that item is not available";
    }

    // ------------------------------------------------------------ wood
    static final class Chop extends Spot {
        final int n; int chops, phase, target = -1; float wait; int woodBefore;
        Chop(int n) { this.n = n; what = "chop"; timeout = 60f + n * 6f; reach = 50f; tol = 10f; label = "a tree"; }
        @Override void start(ScriptPilot pl) {
            if (!P(this).saw) finishFail("you need a saw first: buy(Item.Saw)", 0.05f);
        }
        private boolean pickLog(World w, World.Player p) {
            float best = 1e9f; int bi = -1;
            for (int i = 0; i < World.NL; i++) {
                if (w.logs[i].hp <= 0) continue;
                float d = World.dist(p.x, p.y, Data.LOGS[i][0], Data.LOGS[i][1]);
                if (!host.pilot.autoWalk && d > 60f) continue;
                if (d < best) { best = d; bi = i; }
            }
            if (bi < 0) return false;
            target = bi; sx = Data.LOGS[bi][0]; sy = Data.LOGS[bi][1] + 30f; there = false; mv.reset();
            return true;
        }
        @Override boolean step(ScriptPilot pl, float dt) {
            if (!ending && target < 0) {
                World w = W(this);
                if (!pickLog(w, P(this))) { finishQuiet(Long.valueOf(chops), "chop(): no tree is standing" + (pl.autoWalk ? "" : " within reach"), 0.1f); }
            }
            return super.step(pl, dt);
        }
        void act(ScriptPilot pl, float dt) {
            World w = W(this); World.Player p = P(this);
            if (chops >= n) { finish(Long.valueOf(chops), 0.05f); return; }
            if (w.logs[target].hp <= 0) { if (!pickLog(w, p)) finishQuiet(Long.valueOf(chops), "chop(): no more trees", 0.05f); return; }
            if (phase == 0) { woodBefore = w.wood; doAct(this); phase = 1; wait = 0; if (auth(this)) { chops++; phase = 0; cool = 0.42f; } return; }
            wait += dt;
            if (w.wood > woodBefore) { chops++; phase = 0; cool = 0.42f; }
            else if (wait > 1.0f) finishFail("the host did not chop", 0.1f);
        }
    }

    // ------------------------------------------------------------ fishing
    static final class Fish extends Spot {
        final int n; int caught, startFish, stage; float wait, castCool;
        Fish(int n) { this.n = n; what = "fish"; sx = 1300; sy = 1612; timeout = 130f * n; reach = 60f; tol = 14f; label = "the dock"; }
        @Override void start(ScriptPilot pl) { startFish = P(this).fish; }
        void act(ScriptPilot pl, float dt) {
            World.Player p = P(this);
            caught = p.fish - startFish;
            if (caught >= n) { finish(Long.valueOf(caught), 0.1f); return; }
            switch (p.fishState) {
                case 0:
                    if (castCool > 0) { castCool -= dt; return; }
                    p.fx = -1; p.fy = 0;
                    doAct(this); castCool = 0.35f; return;
                case 3: doAct(this); castCool = 0.5f; return;
                case 4: pl.lamp = true; return;
                default: return;
            }
        }
    }

    // ------------------------------------------------------------ crew and creatures
    static final class Cheer extends Spot {
        int clown = -1, phase; float wait;
        Cheer() { what = "cheer"; timeout = 60f; reach = 60f; tol = 12f; label = "the clown"; }
        @Override void start(ScriptPilot pl) {
            World w = W(this); World.Player p = P(this);
            float best = 1e9f;
            for (int i = 0; i < 2; i++) { World.Mob m = w.mob[World.M_CLOWN0 + i]; if (m.on && m.st == 1) { float d = World.dist(p.x, p.y, m.x, m.y); if (d < best) { best = d; clown = i; } } }
            if (clown < 0) { finishQuiet(Boolean.FALSE, "cheer(): no clown is waiting", 0.05f); return; }
            World.Mob m = w.mob[World.M_CLOWN0 + clown]; sx = m.x; sy = m.y + 30f;
        }
        void act(ScriptPilot pl, float dt) {
            World w = W(this);
            World.Mob m = w.mob[World.M_CLOWN0 + clown];
            if (!m.on || (m.st != 1 && phase == 0)) { finishFail("the clown is gone", 0.1f); return; }
            if (phase == 0) { doAct(this); phase = 1; wait = 0; if (auth(this)) { if (m.st == 2) finish(Boolean.TRUE, 0.3f); else finishFail("could not cheer it up", 0.1f); } return; }
            wait += dt;
            if (m.st == 2) finish(Boolean.TRUE, 0.3f); else if (wait > 1.2f) finishFail("the clown did not react", 0.1f);
        }
    }

    static final class Revive extends Spot {
        int who; float wait;
        Revive(int who) { this.who = who; what = "revive"; timeout = 40f; reach = 55f; tol = 10f; label = "your friend"; }
        @Override void start(ScriptPilot pl) {
            World w = W(this); World.Player me = P(this);
            if (who < 0) {
                float best = 1e9f;
                for (int i = 0; i < World.NP; i++) { World.Player q = w.pl[i]; if (q != me && q.active && q.state == World.ST_DOWN) { float d = World.dist(me.x, me.y, q.x, q.y); if (d < best) { best = d; who = i; } } }
                if (who < 0) { finishQuiet(Boolean.FALSE, "revive(): nobody is down", 0.05f); return; }
            }
            World.Player q = w.pl[who];
            if (!q.active || q.state != World.ST_DOWN) { finishFail("that player is not down", 0.05f); return; }
            sx = q.x; sy = q.y + 22f;
        }
        void act(ScriptPilot pl, float dt) {
            World w = W(this); World.Player q = w.pl[who];
            if (q.state == World.ST_ALIVE) { finish(Boolean.TRUE, 0.2f); return; }
            if (q.state != World.ST_DOWN) { finishFail("too late", 0.1f); return; }
            pl.actHold = true; wait += dt;
            if (wait > 7f) finishFail("could not finish the revive", 0.1f);
        }
    }

    // ------------------------------------------------------------ places
    static final class Hide extends Spot {
        final float hold; float held;
        Hide(float hold) { this.hold = hold; what = "hide"; sx = 1100; sy = 660; timeout = 60f + hold; reach = 140f; tol = 40f; label = "the cabin"; }
        void act(ScriptPilot pl, float dt) {
            World.Player p = P(this);
            if (!(p.inside || World.dist(p.x, p.y, sx, sy) < 60f)) { finishFail("could not get inside", 0.1f); return; }
            held += dt;
            if (held >= hold) finish(Boolean.TRUE, 0.02f);
        }
    }

    static final class Sail extends Spot {
        int phase, islandBefore, phaseBefore; float wait;
        Sail() { what = "sail"; sx = Data.BOAT_X - 80; sy = Data.BOAT_Y; timeout = 60f; reach = 70f; tol = 14f; label = "the boat"; }
        void act(ScriptPilot pl, float dt) {
            World w = W(this); World.Player p = P(this);
            if (phase == 0) {
                if (w.boatMask != 15) { finishFail("the boat is not finished: " + Integer.bitCount(w.boatMask) + " of 4 parts built", 0.1f); return; }
                islandBefore = w.island; phaseBefore = w.phase;
                doAct(this); phase = 1; wait = 0;
                if (!auth(this)) return;
            }
            wait += dt;
            if (w.island != islandBefore || w.phase != phaseBefore) { finish(Boolean.TRUE, 0.1f); return; }
            if (auth(this)) finishFail(p.lastErr != 0 ? reason(p.lastErr) : "could not set sail", 0.2f);
            else if (wait > 1.5f) finishFail("everyone must be near the boat to set sail", 0.2f);
        }
    }

    static final class Coins extends ScriptTask {
        final int max; int got; final Mover mv = new Mover(); int cx = -1; float lastCoins; int before;
        Coins(int max) { this.max = max; what = "collect_coins"; timeout = 60f; mv.arrive = 8f; }
        @Override void start(ScriptPilot pl) { before = W(this).coins; }
        @Override void resumed(ScriptPilot pl) { mv.reset(); }
        boolean step(ScriptPilot pl, float dt) {
            World w = W(this); World.Player p = P(this);
            if (got >= max) { ok(Long.valueOf(w.coins - before)); return true; }
            float best = 1e9f; int bi = -1;
            for (int i = 0; i < World.NC; i++) {
                World.Coin c = w.coin[i]; if (!c.on) continue;
                float d = World.dist(p.x, p.y, c.x, c.y); if (d < best) { best = d; bi = i; }
            }
            if (bi < 0) { ok(Long.valueOf(w.coins - before)); return true; }
            if (bi != cx) { cx = bi; mv.reset(); }
            World.Coin c = w.coin[bi];
            int st = mv.step(pl, p, c.x, c.y, dt);
            if (st == Mover.ARRIVED) { got++; cx = -1; }
            else if (st == Mover.FAILED) { got++; cx = -1; host.lastFail = "collect_coins(): " + mv.why; }
            return false;
        }
    }
}
