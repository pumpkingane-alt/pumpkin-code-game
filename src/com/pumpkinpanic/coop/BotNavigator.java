package com.pumpkinpanic.coop;   // change/remove to match the package of your other classes

import java.util.ArrayList;
import java.util.Arrays;

/**
 * BotNavigator - grid A* pathfinding + path smoothing + steering for companion bots.
 *
 * Java 6/7 compatible (no lambdas, no streams) so it builds in AIDE.
 * No Android or game dependencies. Call from the game thread only (shared search buffers).
 *
 * Features
 *  - 8-way A* on a grid, indexed binary heap, zero allocations per search
 *  - obstacles: rectangles, circles, thick wall segments, walkable-area rectangles
 *  - agent radius + safety margin (strict pass), automatic relaxed retry if no path is found
 *  - string-pulling smoothing (straight lines wherever the way is clear)
 *  - dynamic danger map (monsters etc.): paths detour around it, shortcuts never cut through it
 *  - Agent: waypoint following, look-ahead, target tracking, stuck detection + recovery
 *  - partial paths: if the target is unreachable the bot walks to the closest reachable point
 */
public class BotNavigator {

    public static final int ARRIVED = 0, MOVING = 1, NO_PATH = 2;

    private static final float SQRT2 = 1.41421356f;
    private static final int MAX_WP = 256;
    private static final int[] DX = { 1, -1, 0, 0, 1, 1, -1, -1 };
    private static final int[] DY = { 0, 0, 1, -1, 1, -1, 1, -1 };

    public final int worldW, worldH, gw, gh;
    public final float cs;
    private final int n;
    private final float agentRadius, safety;

    private final ArrayList<float[]> rects = new ArrayList<float[]>();
    private final ArrayList<float[]> circles = new ArrayList<float[]>();
    private final ArrayList<float[]> walls = new ArrayList<float[]>();
    private final ArrayList<float[]> walkables = new ArrayList<float[]>();
    private boolean dirty = true;

    private final boolean[] strict, relaxed;
    private boolean[] solid;
    private final byte[] nearWall;
    private final float[] danger;
    private boolean dangerActive;
    private final float[] tempUntil;
    private float time;

    // search buffers
    private final float[] g, f;
    private final int[] parent, visit, closed, heap, heapPos, chain;
    private int heapN, stamp;
    public boolean lastPartial;

    /**
     * @param cellSize    grid cell in world units (16 is a good default for a 2600x1800 world)
     * @param agentRadius collision radius of the bot (the game uses 12)
     */
    public BotNavigator(int worldW, int worldH, float cellSize, float agentRadius) {
        this.worldW = worldW;
        this.worldH = worldH;
        this.cs = cellSize;
        this.gw = (int) Math.ceil(worldW / cellSize);
        this.gh = (int) Math.ceil(worldH / cellSize);
        this.n = gw * gh;
        this.agentRadius = agentRadius;
        this.safety = cellSize * 0.35f;
        strict = new boolean[n];
        relaxed = new boolean[n];
        nearWall = new byte[n];
        danger = new float[n];
        tempUntil = new float[n];
        g = new float[n];
        f = new float[n];
        parent = new int[n];
        visit = new int[n];
        closed = new int[n];
        heap = new int[n + 1];
        heapPos = new int[n];
        chain = new int[n];
        solid = strict;
    }

    // ------------------------------------------------------------------ map building

    /** Solid rectangle (top-left x,y and size). */
    public void addRect(float x, float y, float w, float h) { rects.add(new float[] { x, y, w, h }); dirty = true; }

    /** Solid circle. */
    public void addCircle(float x, float y, float r) { circles.add(new float[] { x, y, r }); dirty = true; }

    /** Thick wall / fence between two points. */
    public void addWall(float x1, float y1, float x2, float y2, float thickness) {
        walls.add(new float[] { x1, y1, x2, y2, thickness });
        dirty = true;
    }

    /** Area the bot may walk in. If you add any, everything outside all of them is blocked. */
    public void addWalkable(float x, float y, float w, float h) { walkables.add(new float[] { x, y, w, h }); dirty = true; }

    /** Forget all obstacles. */
    public void clearMap() {
        rects.clear(); circles.clear(); walls.clear(); walkables.clear();
        dirty = true;
    }

    private void ensureBuilt() { if (dirty) rebuild(); }

    /** Rasterise obstacles. Called automatically after you change the map. */
    public void rebuild() {
        rasterize(strict, agentRadius + safety);
        rasterize(relaxed, agentRadius);
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int i = y * gw + x;
                byte nw = 0;
                for (int k = 0; k < 8 && nw == 0; k++) {
                    int nx = x + DX[k], ny = y + DY[k];
                    if (nx >= 0 && ny >= 0 && nx < gw && ny < gh && strict[ny * gw + nx]) nw = 1;
                }
                nearWall[i] = nw;
            }
        }
        dirty = false;
    }

    private void rasterize(boolean[] out, float inflate) {
        Arrays.fill(out, false);
        if (!walkables.isEmpty()) {
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    float cx = x * cs + cs * 0.5f, cy = y * cs + cs * 0.5f;
                    boolean ok = false;
                    for (int k = 0; k < walkables.size(); k++) {
                        float[] w = walkables.get(k);
                        if (cx >= w[0] + inflate && cx <= w[0] + w[2] - inflate
                                && cy >= w[1] + inflate && cy <= w[1] + w[3] - inflate) { ok = true; break; }
                    }
                    if (!ok) out[y * gw + x] = true;
                }
            }
        }
        for (int k = 0; k < rects.size(); k++) {
            float[] r = rects.get(k);
            int x0 = cellX(r[0] - inflate), x1 = cellX(r[0] + r[2] + inflate);
            int y0 = cellY(r[1] - inflate), y1 = cellY(r[1] + r[3] + inflate);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                if (distRect(x * cs + cs * 0.5f, y * cs + cs * 0.5f, r) <= inflate) out[y * gw + x] = true;
            }
        }
        for (int k = 0; k < circles.size(); k++) {
            float[] c = circles.get(k);
            float rr = c[2] + inflate;
            int x0 = cellX(c[0] - rr), x1 = cellX(c[0] + rr), y0 = cellY(c[1] - rr), y1 = cellY(c[1] + rr);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float dx = x * cs + cs * 0.5f - c[0], dy = y * cs + cs * 0.5f - c[1];
                if (dx * dx + dy * dy <= rr * rr) out[y * gw + x] = true;
            }
        }
        for (int k = 0; k < walls.size(); k++) {
            float[] w = walls.get(k);
            float rr = w[4] * 0.5f + inflate;
            int x0 = cellX(Math.min(w[0], w[2]) - rr), x1 = cellX(Math.max(w[0], w[2]) + rr);
            int y0 = cellY(Math.min(w[1], w[3]) - rr), y1 = cellY(Math.max(w[1], w[3]) + rr);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                if (distSeg(x * cs + cs * 0.5f, y * cs + cs * 0.5f, w) <= rr) out[y * gw + x] = true;
            }
        }
    }

    private static float distRect(float px, float py, float[] r) {
        float dx = Math.max(Math.max(r[0] - px, 0f), px - (r[0] + r[2]));
        float dy = Math.max(Math.max(r[1] - py, 0f), py - (r[1] + r[3]));
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float distSeg(float px, float py, float[] s) {
        float vx = s[2] - s[0], vy = s[3] - s[1];
        float l2 = vx * vx + vy * vy;
        float t = l2 <= 0f ? 0f : ((px - s[0]) * vx + (py - s[1]) * vy) / l2;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        float qx = s[0] + vx * t - px, qy = s[1] + vy * t - py;
        return (float) Math.sqrt(qx * qx + qy * qy);
    }

    private int cellX(float x) { int c = (int) (x / cs); return c < 0 ? 0 : (c >= gw ? gw - 1 : c); }
    private int cellY(float y) { int c = (int) (y / cs); return c < 0 ? 0 : (c >= gh ? gh - 1 : c); }

    // ------------------------------------------------------------------ dynamic layers

    /** Advance the internal clock (used by temporary blocks). Call once per frame. */
    public void tick(float dt) { time += dt; }

    public void clearDanger() {
        if (dangerActive) { Arrays.fill(danger, 0f); dangerActive = false; }
    }

    /**
     * Mark a threat. Cells within radius get extra path cost (up to 1+strength x normal at the centre).
     * Call clearDanger() once per frame (or per replan interval) and re-add current threats.
     */
    public void addDanger(float x, float y, float radius, float strength) {
        int x0 = cellX(x - radius), x1 = cellX(x + radius), y0 = cellY(y - radius), y1 = cellY(y + radius);
        for (int cy = y0; cy <= y1; cy++) for (int cx = x0; cx <= x1; cx++) {
            float dx = cx * cs + cs * 0.5f - x, dy = cy * cs + cs * 0.5f - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < radius) danger[cy * gw + cx] += strength * (1f - d / radius);
        }
        dangerActive = true;
    }

    public boolean hasDanger() { return dangerActive; }

    public float dangerAt(float x, float y) { return danger[cellY(y) * gw + cellX(x)]; }

    /** Temporarily block a disk (used when a bot gets stuck on something the map does not know about). */
    private void tempBlockDisk(float cx, float cy, float radius, float keepClearX, float keepClearY, float seconds) {
        int x0 = cellX(cx - radius), x1 = cellX(cx + radius), y0 = cellY(cy - radius), y1 = cellY(cy + radius);
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            float px = x * cs + cs * 0.5f, py = y * cs + cs * 0.5f;
            float dx = px - cx, dy = py - cy;
            if (dx * dx + dy * dy > radius * radius) continue;
            float kx = px - keepClearX, ky = py - keepClearY;
            if (kx * kx + ky * ky < (cs * 1.3f) * (cs * 1.3f)) continue;     // never block the bot's own cell
            tempUntil[y * gw + x] = time + seconds;
        }
    }

    private boolean isBlockedCell(int i) { return solid[i] || tempUntil[i] > time; }

    // ------------------------------------------------------------------ queries

    /** True if a bot centre may stand here. */
    public boolean isWalkable(float x, float y) {
        ensureBuilt();
        solid = strict;
        return !isBlockedCell(cellY(y) * gw + cellX(x));
    }

    /** Centre of the nearest free cell, or null. */
    public float[] nearestFreeCenter(float x, float y) {
        ensureBuilt();
        solid = strict;
        int c = freeCellNear(x, y, 12);
        if (c < 0) return null;
        return new float[] { (c % gw) * cs + cs * 0.5f, (c / gw) * cs + cs * 0.5f };
    }

    /** Straight walk possible (clear of obstacles) between two points? */
    public boolean lineClear(float x1, float y1, float x2, float y2) {
        ensureBuilt();
        solid = strict;
        return lineClear(x1, y1, x2, y2, 1e9f);
    }

    private boolean lineClear(float x1, float y1, float x2, float y2, float maxDanger) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = (int) Math.ceil(len / (cs * 0.5f));
        if (steps < 1) steps = 1;
        int pcx = cellX(x1), pcy = cellY(y1);
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int cx = cellX(x1 + dx * t), cy = cellY(y1 + dy * t);
            int idx = cy * gw + cx;
            if (isBlockedCell(idx) || danger[idx] > maxDanger) return false;
            if (cx != pcx && cy != pcy
                    && isBlockedCell(pcy * gw + cx) && isBlockedCell(cy * gw + pcx)) return false;
            pcx = cx; pcy = cy;
        }
        return true;
    }

    private int freeCellNear(float x, float y, int rings) {
        int cx = cellX(x), cy = cellY(y);
        if (!isBlockedCell(cy * gw + cx)) return cy * gw + cx;
        for (int r = 1; r <= rings; r++) {
            int best = -1;
            float bd = 1e18f;
            for (int oy = -r; oy <= r; oy++) for (int ox = -r; ox <= r; ox++) {
                if (Math.abs(ox) != r && Math.abs(oy) != r) continue;
                int nx = cx + ox, ny = cy + oy;
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                int i = ny * gw + nx;
                if (isBlockedCell(i)) continue;
                float dx = nx * cs + cs * 0.5f - x, dy = ny * cs + cs * 0.5f - y;
                float d = dx * dx + dy * dy;
                if (d < bd) { bd = d; best = i; }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    /**
     * Find a calm spot near (x,y) with low danger, close to a preferred point (e.g. the player or the cabin).
     * Returns {x,y} or null.
     */
    public float[] findSafePoint(float x, float y, float searchRadius, float preferX, float preferY) {
        ensureBuilt();
        solid = strict;
        int x0 = cellX(x - searchRadius), x1 = cellX(x + searchRadius);
        int y0 = cellY(y - searchRadius), y1 = cellY(y + searchRadius);
        float bestS = 1e18f;
        float[] best = null;
        for (int cy = y0; cy <= y1; cy++) for (int cx = x0; cx <= x1; cx++) {
            int i = cy * gw + cx;
            if (isBlockedCell(i)) continue;
            float px = cx * cs + cs * 0.5f, py = cy * cs + cs * 0.5f;
            float d0 = (float) Math.sqrt((px - x) * (px - x) + (py - y) * (py - y));
            if (d0 > searchRadius) continue;
            float d1 = (float) Math.sqrt((px - preferX) * (px - preferX) + (py - preferY) * (py - preferY));
            float s = danger[i] * 300f + d1 * 0.3f + d0 * 0.2f;
            if (s < bestS) { bestS = s; best = new float[] { px, py }; }
        }
        return best;
    }

    // ------------------------------------------------------------------ A*

    /**
     * Find a smoothed path.
     * @param out receives waypoints as x0,y0,x1,y1,... (capacity >= 512 floats). First waypoint is NOT the start.
     * @return number of waypoints (>=1), or -1 when no path exists. If the goal cannot be reached the path ends at
     *         the closest reachable point and lastPartial is true.
     */
    public int findPath(float sx, float sy, float gx, float gy, float[] out) {
        ensureBuilt();
        int c = search(sx, sy, gx, gy, strict, out);
        if (c >= 0 && !lastPartial) return c;
        boolean strictPartial = c >= 0;
        int c2 = search(sx, sy, gx, gy, relaxed, out);
        if (c2 >= 0 && !lastPartial) return c2;
        if (strictPartial) return search(sx, sy, gx, gy, strict, out);
        return c2;
    }

    private int search(float sx, float sy, float gx, float gy, boolean[] sol, float[] out) {
        solid = sol;
        lastPartial = false;
        int s = freeCellNear(sx, sy, 8);
        int goal = freeCellNear(gx, gy, 14);
        if (s < 0 || goal < 0) return -1;
        int ggx = goal % gw, ggy = goal / gw;

        stamp++;
        heapN = 0;
        visit[s] = stamp; g[s] = 0f; parent[s] = -1; f[s] = hEst(s, ggx, ggy);
        heapPos[s] = -1;
        push(s);
        int best = s;
        float bestH = f[s];
        boolean found = false;

        while (heapN > 0) {
            int cur = pop();
            closed[cur] = stamp;
            if (cur == goal) { found = true; break; }
            float hc = hEst(cur, ggx, ggy);
            if (hc < bestH) { bestH = hc; best = cur; }
            int cx = cur % gw, cy = cur / gw;
            for (int k = 0; k < 8; k++) {
                int nx = cx + DX[k], ny = cy + DY[k];
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                int ni = ny * gw + nx;
                if (closed[ni] == stamp || isBlockedCell(ni)) continue;
                if (k >= 4 && (isBlockedCell(cy * gw + nx) || isBlockedCell(ny * gw + cx))) continue;
                float step = (k < 4 ? cs : cs * SQRT2) * (1f + danger[ni]) + (nearWall[ni] != 0 ? cs * 0.35f : 0f);
                float ng = g[cur] + step;
                if (visit[ni] != stamp) {
                    visit[ni] = stamp; g[ni] = ng; parent[ni] = cur; f[ni] = ng + hEst(ni, ggx, ggy);
                    heapPos[ni] = -1;
                    push(ni);
                } else if (ng < g[ni]) {
                    g[ni] = ng; parent[ni] = cur; f[ni] = ng + hEst(ni, ggx, ggy);
                    siftUp(heapPos[ni]);
                }
            }
        }

        int end = found ? goal : best;
        lastPartial = !found;
        if (end == s && !found) return -1;

        // reconstruct (end -> start), then reverse
        int m = 0;
        for (int c = end; c != -1; c = parent[c]) chain[m++] = c;
        for (int a = 0, b = m - 1; a < b; a++, b--) { int t = chain[a]; chain[a] = chain[b]; chain[b] = t; }

        // string-pulling smoothing
        int outN = 0;
        float ox = sx, oy = sy;      // current anchor
        int i = 0;
        while (i < m - 1 && outN < MAX_WP - 1) {
            int jMax = Math.min(m - 1, i + 48);
            int j = jMax;
            for (; j > i + 1; j--) {
                float md = 0f;
                for (int q = i; q <= j; q++) if (danger[chain[q]] > md) md = danger[chain[q]];
                float jx = (chain[j] % gw) * cs + cs * 0.5f, jy = (chain[j] / gw) * cs + cs * 0.5f;
                if (lineClear(ox, oy, jx, jy, md + 0.2f)) break;
            }
            ox = (chain[j] % gw) * cs + cs * 0.5f;
            oy = (chain[j] / gw) * cs + cs * 0.5f;
            out[outN * 2] = ox; out[outN * 2 + 1] = oy; outN++;
            i = j;
        }
        // final point: exact goal if reachable straight from the previous anchor
        if (!lastPartial && cellY(gy) * gw + cellX(gx) == end) {
            if (outN == 0) {
                out[0] = gx; out[1] = gy; outN = 1;
            } else {
                float px = outN > 1 ? out[(outN - 2) * 2] : sx, py = outN > 1 ? out[(outN - 2) * 2 + 1] : sy;
                if (lineClear(px, py, gx, gy, 1e9f)) { out[(outN - 1) * 2] = gx; out[(outN - 1) * 2 + 1] = gy; }
            }
        }
        if (outN == 0) {
            out[0] = (end % gw) * cs + cs * 0.5f; out[1] = (end / gw) * cs + cs * 0.5f; outN = 1;
        }
        return outN;
    }

    private float hEst(int idx, int gx, int gy) {
        int dx = Math.abs(idx % gw - gx), dy = Math.abs(idx / gw - gy);
        int mn = dx < dy ? dx : dy, mx = dx < dy ? dy : dx;
        return cs * (mx + (SQRT2 - 1f) * mn);
    }

    // indexed binary heap keyed by f[]
    private void push(int node) { heapN++; heap[heapN] = node; heapPos[node] = heapN; siftUp(heapN); }

    private int pop() {
        int top = heap[1];
        int last = heap[heapN--];
        heapPos[top] = -1;
        if (heapN > 0) { heap[1] = last; heapPos[last] = 1; siftDown(1); }
        return top;
    }

    private void siftUp(int i) {
        int node = heap[i];
        float fv = f[node];
        while (i > 1) {
            int p = i >> 1;
            if (f[heap[p]] <= fv) break;
            heap[i] = heap[p]; heapPos[heap[i]] = i;
            i = p;
        }
        heap[i] = node; heapPos[node] = i;
    }

    private void siftDown(int i) {
        int node = heap[i];
        float fv = f[node];
        while (true) {
            int c = i << 1;
            if (c > heapN) break;
            if (c < heapN && f[heap[c + 1]] < f[heap[c]]) c++;
            if (f[heap[c]] >= fv) break;
            heap[i] = heap[c]; heapPos[heap[i]] = i;
            i = c;
        }
        heap[i] = node; heapPos[node] = i;
    }

    // ------------------------------------------------------------------ agent

    public Agent newAgent() { return new Agent(this); }

    /**
     * One per bot. Each frame call update(...) and move the bot along dir * speed * speedScale.
     *
     *   float[] dir = new float[2];
     *   int st = agent.update(bot.x, bot.y, player.x, player.y, dt, dir);
     *   if (st == BotNavigator.MOVING) { bot.x += dir[0] * speed * agent.speedScale * dt; ... }
     */
    public static class Agent {
        private final BotNavigator nav;
        /** Stop this far from the target (follow distance). 0 = walk right onto it. */
        public float stopDistance = 0f;
        public float arriveRadius = 8f;
        public float reach = 14f;
        public float slowRadius = 60f;
        public float repathInterval = 0.45f;
        public float targetMoveRepath = 30f;
        /** 0..1, scale your movement speed by this (slows down smoothly near the goal). */
        public float speedScale = 1f;
        public boolean partial;

        private final float[] wp = new float[MAX_WP * 2];
        private int wpN, wpI;
        private float lastTx = -99999f, lastTy = -99999f, repathT, sampleT, sampX, sampY, nudgeT, nudgeSign = 1f;
        private int stuck, unstick;
        private boolean noPath;
        private float lastDx, lastDy;

        private Agent(BotNavigator nav) { this.nav = nav; }

        public void reset() { wpN = 0; wpI = 0; lastTx = -99999f; stuck = 0; unstick = 0; nudgeT = 0f; repathT = 0f; noPath = false; }

        /** @return ARRIVED, MOVING (dir is a unit vector) or NO_PATH. */
        public int update(float x, float y, float tx, float ty, float dt, float[] dir) {
            dir[0] = 0f; dir[1] = 0f;
            speedScale = 1f;
            nav.ensureBuilt();
            nav.solid = nav.strict;

            float dxT = tx - x, dyT = ty - y;
            float d = (float) Math.sqrt(dxT * dxT + dyT * dyT);
            float stopR = Math.max(stopDistance, arriveRadius);
            if (d <= stopR && (stopDistance <= 0f || d < 30f || nav.lineClear(x, y, tx, ty, 1e9f))) {
                wpN = 0; speedScale = 0f; stuck = 0;
                return ARRIVED;
            }

            // pushed into a wall? walk back out
            if (!nav.isWalkable(x, y)) {
                float[] c = nav.nearestFreeCenter(x, y);
                if (c != null) {
                    float vx = c[0] - x, vy = c[1] - y, l = (float) Math.sqrt(vx * vx + vy * vy);
                    if (l > 0.5f) { dir[0] = vx / l; dir[1] = vy / l; return MOVING; }
                }
            }

            if (nudgeT > 0f) {                       // unstick nudge: sidestep for a moment
                nudgeT -= dt;
                dir[0] = -lastDy * nudgeSign; dir[1] = lastDx * nudgeSign;
                return MOVING;
            }

            repathT -= dt;
            float mv = (float) Math.sqrt((tx - lastTx) * (tx - lastTx) + (ty - lastTy) * (ty - lastTy));
            boolean need = (wpI >= wpN && repathT <= 0f)
                    || (mv > targetMoveRepath && repathT <= 0f)
                    || (repathT <= 0f && (nav.dangerActive || stuck > 0));
            if (need) {
                int c = nav.findPath(x, y, tx, ty, wp);
                lastTx = tx; lastTy = ty; repathT = repathInterval;
                wpI = 0;
                noPath = c < 0;
                if (c < 0) { wpN = 0; return NO_PATH; }
                wpN = c;
                partial = nav.lastPartial;
            }

            if (wpN == 0 && noPath) return NO_PATH;

            // advance waypoints (with look-ahead over clear straight lines)
            while (wpI < wpN) {
                float wx = wp[wpI * 2], wy = wp[wpI * 2 + 1];
                float ddx = wx - x, ddy = wy - y;
                if (ddx * ddx + ddy * ddy < reach * reach) { wpI++; continue; }
                if (wpI + 1 < wpN) {
                    float nx = wp[wpI * 2 + 2], ny = wp[wpI * 2 + 3];
                    float ex = nx - x, ey = ny - y;
                    if (ex * ex + ey * ey < 180f * 180f && nav.lineClear(x, y, nx, ny, 1e9f)) { wpI++; continue; }
                }
                break;
            }
            if (wpI >= wpN) { speedScale = 0f; return ARRIVED; }   // end of path (or closest reachable point)

            float wx = wp[wpI * 2], wy = wp[wpI * 2 + 1];
            float vx = wx - x, vy = wy - y;
            float l = (float) Math.sqrt(vx * vx + vy * vy);
            if (l < 0.001f) l = 0.001f;
            dir[0] = vx / l; dir[1] = vy / l;
            lastDx = dir[0]; lastDy = dir[1];

            if (wpI == wpN - 1) {                     // slow down for the final stretch
                float rem = Math.max(0f, l - stopDistance);
                float sc = rem / slowRadius;
                speedScale = sc < 0.25f ? 0.25f : (sc > 1f ? 1f : sc);
            }

            // stuck detection
            sampleT += dt;
            if (sampleT >= 0.3f) {
                float mx = x - sampX, my = y - sampY;
                if (mx * mx + my * my < 2f * 2f) {
                    stuck++;
                    if (stuck >= 2) {
                        nav.tempBlockDisk(x + dir[0] * 34f, y + dir[1] * 34f, 40f, x, y, 5f);
                        repathT = 0f;
                        unstick++;
                        if (unstick >= 3) { nudgeT = 0.35f; nudgeSign = -nudgeSign; unstick = 0; }
                        stuck = 1;
                    }
                } else { stuck = 0; unstick = 0; }
                sampX = x; sampY = y; sampleT = 0f;
            }
            return MOVING;
        }
    }

    // ------------------------------------------------------------------ Pumpkin Panic preset

    /**
     * Static obstacles from the addon reference (world 2600x1800). Fences, trees and logs are NOT included
     * because their positions are not documented: add them with addRect/addCircle/addWall from the real
     * collision list in your source.
     */
    public static void loadPumpkinPanicWorld(BotNavigator nav) {
        nav.addWalkable(210f, 215f, 2180f, 1263f);      // farm: x 210-2390, y 215-1478
        nav.addWalkable(1262f, 1440f, 76f, 215f);       // dock: x 1262-1338 down to y 1655
        // cabin walls x 900-1300, y 470-760, door gap in the bottom wall (x 1065-1135 = assumption)
        nav.addRect(900f, 470f, 400f, 26f);
        nav.addRect(900f, 470f, 26f, 290f);
        nav.addRect(1274f, 470f, 26f, 290f);
        nav.addRect(900f, 734f, 165f, 26f);
        nav.addRect(1135f, 734f, 165f, 26f);
        // well, shipping box, oil barrel
        nav.addCircle(930f, 1015f, 30f);
        nav.addCircle(1235f, 818f, 28f);
        nav.addCircle(962f, 818f, 24f);
    }

    /** ASCII picture of the blocked grid (for checking your obstacle list). '#' blocked, '.' free. */
    public String debugAscii(int step) {
        ensureBuilt();
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < gh; y += step) {
            for (int x = 0; x < gw; x += step) sb.append(strict[y * gw + x] ? '#' : '.');
            sb.append('\n');
        }
        return sb.toString();
    }
}
