package com.pumpkinpanic.coop;

import java.util.ArrayList;

/**
 * The "hands" of the script: turns script requests (tasks) into joystick-like input for the local player.
 * Only one task owns the character at a time (highest priority first, then first come first served);
 * the others wait. Runs on the game thread, once per frame, before the player is moved.
 * Java 7 compatible.
 */
final class ScriptPilot {
    private static BotNavigator NAV;

    /** Shared path finder for the (static) island map. */
    static BotNavigator nav() {
        if (NAV == null) {
            BotNavigator n = new BotNavigator(Data.WW, Data.WH, 16f, 13f);
            n.addWalkable(Data.WX0, Data.WY0, Data.WX1 - Data.WX0, Data.WY1 - Data.WY0);
            n.addWalkable(Data.DK_X0, Data.WY1 - 40, Data.DK_X1 - Data.DK_X0, Data.DK_Y1 - (Data.WY1 - 40));
            for (int i = 0; i < Data.OBS.length; i++) { if (i == Data.OBS_DOOR) continue; float[] o = Data.OBS[i]; n.addRect(o[0], o[1], o[2], o[3]); }
            n.rebuild();
            NAV = n;
        }
        return NAV;
    }

    final ScriptHost host;
    final ArrayList<ScriptTask> queue = new ArrayList<ScriptTask>();
    // outputs of the current frame (read by GameView.controlLocal)
    float ix, iy;
    boolean run, water, lamp, actHold;
    // persistent switches set by scripts
    boolean sprintPref, lampPref, autoWalk = true;
    ScriptTask active;

    ScriptPilot(ScriptHost h) { host = h; }

    void reset() {
        queue.clear(); active = null; ix = iy = 0; run = water = lamp = actHold = false;
        sprintPref = lampPref = false; autoWalk = true;
    }

    void submit(ScriptTask t) { t.host = host; queue.add(t); }

    void cancelOwner(ScriptHost.ThreadCtx c) {
        for (int i = queue.size() - 1; i >= 0; i--) if (queue.get(i).owner == c) { if (queue.get(i) == active) active = null; queue.remove(i); }
    }
    void cancelAll() { queue.clear(); active = null; ix = iy = 0; water = actHold = false; }

    private ScriptTask pick() {
        ScriptTask best = null;
        for (int i = 0; i < queue.size(); i++) {
            ScriptTask t = queue.get(i);
            if (t.done) continue;
            if (best == null || t.prio > best.prio) best = t;
        }
        return best;
    }

    /** Called every frame while the run is live. dt = real frame time. */
    void frame(float dt) {
        ix = iy = 0; water = false; actHold = false; run = sprintPref; lamp = lampPref;
        World.Player p = host.link.me();
        for (int i = queue.size() - 1; i >= 0; i--) if (queue.get(i).done) queue.remove(i);
        if (p == null || p.state != World.ST_ALIVE) return;
        nav().tick(dt);
        ScriptTask t = pick();
        if (t != active) { active = t; if (t != null && t.started) t.resumed(this); }
        if (t == null) return;
        if (!t.started) { t.started = true; t.start(this); }
        t.age += dt;
        if (t.timeout > 0 && t.age > t.timeout && !t.done) { t.fail(t.what + " took too long (gave up after " + (int) t.timeout + "s)"); }
        else if (t.step(this, dt)) t.done = true;
        if (t.done) { queue.remove(t); active = null; }
    }

    // ------------------------------------------------------------ path walking helper
    /** Walks to a point with A* path finding, then creeps the last steps straight to the exact spot. Create one per task. */
    static final class Mover {
        static final int ARRIVED = 0, MOVING = 1, FAILED = 2;
        private BotNavigator.Agent agent;
        private final float[] dir = new float[2];
        private float lx, ly, still, blockedT, fineT, fineBest;
        private boolean fine;
        String why = "";
        float arrive = 4f;              // final accuracy in world units

        void reset() { if (agent != null) agent.reset(); still = 0; blockedT = 0; fine = false; fineT = 0; }

        int step(ScriptPilot pl, World.Player p, float tx, float ty, float dt) {
            float d = World.dist(p.x, p.y, tx, ty);
            if (d <= arrive) return ARRIVED;
            if (fine) {
                fineT += dt;
                float sc = Math.min(1f, Math.max(0.22f, d / 24f));
                pl.ix = (tx - p.x) / d * sc; pl.iy = (ty - p.y) / d * sc;
                if (d < fineBest - 0.4f) { fineBest = d; fineT = Math.min(fineT, 0.6f); }
                if (fineT > 1.6f) { if (d <= arrive + 30f) return ARRIVED; fine = false; why = "no way to get there"; return failOrWait(dt); }
                return MOVING;
            }
            if (agent == null) { agent = nav().newAgent(); agent.arriveRadius = 10f; agent.slowRadius = 36f; agent.reach = 10f; }
            int st = agent.update(p.x, p.y, tx, ty, dt, dir);
            if (st == BotNavigator.ARRIVED) {
                if (d <= arrive + 40f) { fine = true; fineT = 0; fineBest = d; return MOVING; }
                why = "no way to get there"; return failOrWait(dt);
            }
            if (st == BotNavigator.NO_PATH) { why = "no path to (" + (int) tx + ", " + (int) ty + ")"; return failOrWait(dt); }
            blockedT = 0;
            float sc = Math.max(0.35f, agent.speedScale);
            pl.ix = dir[0] * sc; pl.iy = dir[1] * sc;
            if (World.dist(p.x, p.y, lx, ly) < 40f * dt) {
                still += dt;
                if (still > 5f) { why = "stuck - something is blocking the way"; return FAILED; }
            } else { still = 0; lx = p.x; ly = p.y; }
            return MOVING;
        }

        /** A path can be blocked for a moment (the path finder blocks places it got stuck at for 5 s): wait a bit before giving up. */
        private int failOrWait(float dt) {
            blockedT += dt;
            if (blockedT > 7f) return FAILED;
            return MOVING;
        }
    }
}

/** One thing the character does for a script call (walk somewhere, plant, buy, ...). */
abstract class ScriptTask {
    ScriptHost host;
    ScriptHost.ThreadCtx owner;
    int prio;
    boolean done, started;
    Object result;
    String failReason;          // set when the action did not work
    float age, timeout = 0;
    String what = "action";

    void start(ScriptPilot pl) {}
    /** Called when this task gets the character back after a higher priority task used it. */
    void resumed(ScriptPilot pl) {}
    /** Run one frame. Return true when finished. */
    abstract boolean step(ScriptPilot pl, float dt);

    void ok(Object r) { result = r; done = true; }
    void fail(String why) {
        result = Boolean.FALSE; failReason = why; done = true;
        if (System.getProperty("ppdebug") != null && host != null) { World.Player p = host.link.me(); System.out.println("  [dbg] " + what + " failed: " + why + " at t=" + (int) host.link.world().T + " pos=" + (int) p.x + "," + (int) p.y + " state=" + p.state + " inside=" + p.inside); }
    }
}
