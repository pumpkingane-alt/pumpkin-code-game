package com.pumpkinpanic.coop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.Random;

/** All procedural "paper cut-out" artwork: ground, props, buildings, plants, characters and creatures. */
final class Art {
    static final int INK = Data.INK;
    final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG), lp = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF r = new RectF();
    final Bitmap[] ground = new Bitmap[4];
    // sorted static props: x, y, type (0 pine, 1 maple, 2 red maple, 3 birch)
    float[] treeX, treeY; int[] treeT; int nTrees;
    float[] fenX, fenY; boolean[] fenV; int nFence;
    static final float[][] JACKS = {{572, 414}, {1948, 414}, {572, 1270}, {1948, 1270}, {1222, 1268}, {1378, 1268}, {1040, 790}, {1162, 790}};
    static final int[] SHIRT = {0xFF3C6EA8, 0xFFB3453A, 0xFF4B8A4F, 0xFF8B5FBF};

    Art() {
        s.setStyle(Paint.Style.STROKE); s.setStrokeJoin(Paint.Join.ROUND); s.setStrokeCap(Paint.Cap.ROUND); s.setColor(INK); s.setStrokeWidth(2.3f);
        lp.setStrokeCap(Paint.Cap.ROUND); lp.setStrokeJoin(Paint.Join.ROUND);
        genProps();
    }

    // ------------------------------------------------------------ primitives
    static int mix(int a, int b, float k) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * k) << 16) | ((int) (ag + (bg - ag) * k) << 8) | (int) (ab + (bb - ab) * k);
    }
    static int al(int col, int a) { return (col & 0xFFFFFF) | ((a & 255) << 24); }
    void ov(Canvas c, float cx, float cy, float rx, float ry, int col) {
        r.set(cx - rx, cy - ry, cx + rx, cy + ry); f.setStyle(Paint.Style.FILL); f.setColor(col); c.drawOval(r, f); c.drawOval(r, s);
    }
    void ovn(Canvas c, float cx, float cy, float rx, float ry, int col) {
        r.set(cx - rx, cy - ry, cx + rx, cy + ry); f.setStyle(Paint.Style.FILL); f.setColor(col); c.drawOval(r, f);
    }
    void rr(Canvas c, float x, float y, float w, float h, float rad, int col) {
        r.set(x, y, x + w, y + h); f.setStyle(Paint.Style.FILL); f.setColor(col); c.drawRoundRect(r, rad, rad, f); c.drawRoundRect(r, rad, rad, s);
    }
    void rrn(Canvas c, float x, float y, float w, float h, float rad, int col) {
        r.set(x, y, x + w, y + h); f.setStyle(Paint.Style.FILL); f.setColor(col); c.drawRoundRect(r, rad, rad, f);
    }
    void tri(Canvas c, int col, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close();
        f.setStyle(Paint.Style.FILL); f.setColor(col); c.drawPath(path, f); c.drawPath(path, s);
    }
    void trin(Canvas c, int col, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close();
        f.setStyle(Paint.Style.FILL); f.setColor(col); c.drawPath(path, f);
    }
    void quad(Canvas c, int col, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.lineTo(x4, y4); path.close();
        f.setStyle(Paint.Style.FILL); f.setColor(col); c.drawPath(path, f); c.drawPath(path, s);
    }
    void ln(Canvas c, float x1, float y1, float x2, float y2, float w, int col) {
        lp.setStyle(Paint.Style.STROKE); lp.setStrokeWidth(w); lp.setColor(col); c.drawLine(x1, y1, x2, y2, lp);
    }
    void limb(Canvas c, float x1, float y1, float x2, float y2, float w, int col) { ln(c, x1, y1, x2, y2, w + 3.2f, INK); ln(c, x1, y1, x2, y2, w, col); }
    // ------------------------------------------------------------ addon sprite overrides
    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    static final String[] PKEY = {"player_0", "player_1", "player_2", "player_3"};
    static final String[] CKEY = {"crop_turnip", "crop_carrot", "crop_corn", "crop_pumpkin", "crop_moonbloom"};

    /**
     * Draws an addon sprite with its bottom-centre on (x, y) in world units (1 px = 1 unit).
     * A horizontal strip of square frames animates at 8 fps from animT (pass 0 to hold frame 0).
     * flip = -1 mirrors it. Returns false when no addon provides this key.
     */
    boolean addonSprite(Canvas c, String key, float x, float y, float flip, float animT) {
        Bitmap[] fr = AddonEngineController.spriteFrames(key);
        if (fr == null || fr.length == 0) return false;
        Bitmap f = fr[fr.length > 1 ? ((int) (animT * 8f) % fr.length + fr.length) % fr.length : 0];
        float w = f.getWidth(), h = f.getHeight();
        c.save(); c.translate(x, y);
        shadow(c, 0, 2, w * 0.3f, Math.max(4f, w * 0.1f), 70);
        c.scale(flip < 0 ? -1f : 1f, 1f);
        c.drawBitmap(f, -w / 2f, -h, spritePaint);
        c.restore();
        return true;
    }

    /** Crop sprite: a strip maps to growth stages, a single image is scaled up as it grows. */
    boolean addonCrop(Canvas c, int type, float grow, float x, float y) {
        Bitmap[] fr = AddonEngineController.spriteFrames(CKEY[type % 5]);
        if (fr == null || fr.length == 0) return false;
        Bitmap f = fr[Math.min(fr.length - 1, (int) (grow * fr.length))];
        float k = fr.length > 1 ? 1f : 0.4f + 0.6f * grow, w = f.getWidth() * k, h = f.getHeight() * k;
        c.drawBitmap(f, null, new RectF(x - w / 2f, y - h, x + w / 2f, y), spritePaint);
        return true;
    }

    void shadow(Canvas c, float cx, float cy, float rx, float ry, int a) { ovn(c, cx, cy, rx, ry, al(0x000000, a)); }
    void glow(Canvas c, float cx, float cy, float rad, int col, int a) {
        f.setStyle(Paint.Style.FILL);
        f.setShader(new RadialGradient(cx, cy, rad, new int[] {al(col, a), al(col, a / 3), al(col, 0)}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, f); f.setShader(null);
    }

    // ------------------------------------------------------------ static props
    void genProps() {
        Random q = new Random(99);
        float[] tx = new float[420], ty = new float[420]; int[] tt = new int[420]; int n = 0;
        for (int row = 0; row < 4; row++) for (float x = -20; x < Data.WW + 40 && n < 410; x += 52 + q.nextInt(26)) { tx[n] = x; ty[n] = 40 + row * 58 + q.nextInt(24); tt[n++] = q.nextInt(10); }
        for (int col = 0; col < 3; col++) for (float y = 260; y < 1480 && n < 410; y += 58 + q.nextInt(24)) { tx[n] = 20 + col * 62 + q.nextInt(24); ty[n] = y; tt[n++] = q.nextInt(10); }
        for (int col = 0; col < 3; col++) for (float y = 260; y < 1480 && n < 410; y += 58 + q.nextInt(24)) { tx[n] = Data.WW - 20 - col * 62 - q.nextInt(24); ty[n] = y; tt[n++] = q.nextInt(10); }
        for (int k = 0; k < 14 && n < 418; k++) { tx[n] = (k < 7 ? 200 + k * 140 : 1480 + (k - 7) * 140) + q.nextInt(30); ty[n] = 1490 + q.nextInt(20); tt[n++] = q.nextInt(10); }
        // sort by y (insertion)
        for (int i = 1; i < n; i++) { float x = tx[i], y = ty[i]; int tp = tt[i], j = i - 1; while (j >= 0 && ty[j] > y) { tx[j + 1] = tx[j]; ty[j + 1] = ty[j]; tt[j + 1] = tt[j]; j--; } tx[j + 1] = x; ty[j + 1] = y; tt[j + 1] = tp; }
        treeX = tx; treeY = ty; treeT = tt; nTrees = n;
        float[] fx = new float[120], fy = new float[120]; boolean[] fv = new boolean[120]; int m = 0;
        for (float x = 560; x < 1960; x += 70) { fx[m] = x; fy[m] = 403; fv[m++] = false; }
        for (float x = 560; x < 1960; x += 70) { if (x >= 1200 && x < 1370) continue; fx[m] = x; fy[m] = 1287; fv[m++] = false; }
        for (float y = 400; y < 1290; y += 70) { if (y >= 930 && y < 1080) continue; fx[m] = 567; fy[m] = y; fv[m++] = true; }
        for (float y = 400; y < 1290; y += 70) { if (y >= 850 && y < 1000) continue; fx[m] = 1953; fy[m] = y; fv[m++] = true; }
        for (int i = 1; i < m; i++) { float x = fx[i], y = fy[i]; boolean v = fv[i]; int j = i - 1; while (j >= 0 && fy[j] > y) { fx[j + 1] = fx[j]; fy[j + 1] = fy[j]; fv[j + 1] = fv[j]; j--; } fx[j + 1] = x; fy[j + 1] = y; fv[j + 1] = v; }
        fenX = fx; fenY = fy; fenV = fv; nFence = m;
    }

    // ------------------------------------------------------------ ground
    void buildGround() {
        for (int i = 0; i < 4; i++) {
            ground[i] = Bitmap.createBitmap(1300, 900, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(ground[i]);
            c.translate(-(i % 2) * 1300f, -(i / 2) * 900f);
            paintGround(c, (i % 2) * 1300f, (i / 2) * 900f);
        }
    }

    private boolean vis(float x, float y, float rad, float ox, float oy) { return x + rad > ox && x - rad < ox + 1300 && y + rad > oy && y - rad < oy + 900; }

    void paintGround(Canvas c, float ox, float oy) {
        Random q = new Random(7);
        c.drawColor(0xFF547F35);
        f.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 520; i++) {
            float x = q.nextFloat() * Data.WW, y = q.nextFloat() * 1540, rd = 40 + q.nextFloat() * 110; int k = q.nextInt(3);
            if (!vis(x, y, rd, ox, oy)) continue;
            f.setColor(k == 0 ? 0x2A9ACB52 : (k == 1 ? 0x2A2F5A2A : 0x1EB8A64A));
            r.set(x - rd, y - rd * 0.7f, x + rd, y + rd * 0.7f); c.drawOval(r, f);
        }
        // forest floor + soft edge
        int dark = 0xFF2B4824;
        f.setColor(dark);
        c.drawRect(0, 0, Data.WW, Data.WY0 - 8, f); c.drawRect(0, 0, Data.WX0 - 8, 1540, f); c.drawRect(Data.WX1 + 8, 0, Data.WW, 1540, f);
        f.setShader(new LinearGradient(0, Data.WY0 - 8, 0, Data.WY0 + 110, al(dark, 255), al(dark, 0), Shader.TileMode.CLAMP)); c.drawRect(0, Data.WY0 - 8, Data.WW, Data.WY0 + 110, f);
        f.setShader(new LinearGradient(Data.WX0 - 8, 0, Data.WX0 + 110, 0, al(dark, 255), al(dark, 0), Shader.TileMode.CLAMP)); c.drawRect(Data.WX0 - 8, 0, Data.WX0 + 110, 1540, f);
        f.setShader(new LinearGradient(Data.WX1 + 8, 0, Data.WX1 - 110, 0, al(dark, 255), al(dark, 0), Shader.TileMode.CLAMP)); c.drawRect(Data.WX1 - 110, 0, Data.WX1 + 8, 1540, f);
        f.setShader(null);
        // dirt paths
        float[][] pp = {{1100, 770}, {1100, 880}, {1180, 1010}, {1300, 1150}, {1300, 1300}, {1300, 1470}, {1230, 840}, {1100, 880}, {960, 840}, {1100, 880}, {940, 1010}};
        for (int i = 0; i + 1 < pp.length; i += (i == 5 ? 2 : 1)) {
            for (int k = 0; k < 24; k++) {
                float u = k / 24f, x = pp[i][0] + (pp[i + 1][0] - pp[i][0]) * u, y = pp[i][1] + (pp[i + 1][1] - pp[i][1]) * u;
                if (!vis(x, y, 60, ox, oy)) continue;
                f.setColor(k % 2 == 0 ? 0xFF7A5230 : 0xFF6E4A2B); c.drawCircle(x + q.nextInt(10) - 5, y + q.nextInt(10) - 5, 32 + q.nextInt(14), f);
            }
        }
        for (int i = 0; i < 5; i++) { float x = Data.PLOTS[i][0], y = Data.PLOTS[i][1]; if (!vis(x + 100, y + 100, 130, ox, oy)) continue; rrn(c, x - 8, y - 8, 216, 216, 14, 0xFF5A3A1E); rrn(c, x - 2, y - 2, 204, 204, 10, 0xFF3F2814); }
        // grass tufts, flowers, leaves
        for (int i = 0; i < 3600; i++) {
            float x = Data.WX0 - 60 + q.nextFloat() * (Data.WX1 - Data.WX0 + 120), y = Data.WY0 - 60 + q.nextFloat() * 1360; int k = q.nextInt(3);
            if (!vis(x, y, 8, ox, oy)) continue;
            int col = k == 0 ? 0xFF3B6B2A : (k == 1 ? 0xFF7FAE45 : 0xFF2F5A26);
            ln(c, x, y, x - 3, y - 6, 1.7f, col); ln(c, x, y, x + 1, y - 8, 1.7f, col); ln(c, x, y, x + 4, y - 5, 1.7f, col);
        }
        int[] fl = {0xFFF2A33C, 0xFFF4F0E0, 0xFFB57BD6, 0xFFE8604C};
        for (int i = 0; i < 260; i++) {
            float x = 560 + q.nextFloat() * 1400, y = 410 + q.nextFloat() * 870;
            if (!vis(x, y, 6, ox, oy)) continue;
            f.setColor(fl[q.nextInt(4)]); c.drawCircle(x, y, 2.6f, f); f.setColor(0xFFFFE27A); c.drawCircle(x, y, 1f, f);
        }
        int[] lf = {0xFFE8762C, 0xFFC94F12, 0xFFE8B02C, 0xFFB03A1E};
        for (int i = 0; i < 380; i++) {
            float x = Data.WX0 - 40 + q.nextFloat() * (Data.WX1 - Data.WX0 + 80), y = Data.WY0 - 40 + q.nextFloat() * 1330;
            if (!vis(x, y, 8, ox, oy)) continue;
            f.setColor(al(lf[q.nextInt(4)], 220)); r.set(x - 4, y - 2.4f, x + 4, y + 2.4f); c.save(); c.rotate(q.nextInt(180), x, y); c.drawOval(r, f); c.restore();
        }
        // lake
        float ly = 1500;
        f.setShader(new LinearGradient(0, ly, 0, 1800, 0xFF3C8A93, 0xFF10304A, Shader.TileMode.CLAMP)); c.drawRect(0, ly, Data.WW, Data.WH, f); f.setShader(null);
        f.setColor(0xFF6B5A3A); path.reset(); path.moveTo(0, ly - 10);
        for (int x = 0; x <= Data.WW; x += 40) path.lineTo(x, ly - 6 + (float) Math.sin(x * 0.07f) * 7); path.lineTo(Data.WW, ly + 14); path.lineTo(0, ly + 14); path.close(); c.drawPath(path, f);
        for (int i = 0; i < 90; i++) { float x = q.nextFloat() * Data.WW, y = ly + 30 + q.nextFloat() * 260; if (!vis(x, y, 30, ox, oy)) continue; lp.setStyle(Paint.Style.STROKE); lp.setStrokeWidth(1.6f); lp.setColor(0x44FFFFFF); r.set(x - 18, y - 4, x + 18, y + 4); c.drawArc(r, 200, 140, false, lp); }
        for (int i = 0; i < 70; i++) { float x = q.nextFloat() * Data.WW, y = ly - 4; if (!vis(x, y, 20, ox, oy)) continue; ln(c, x, y + 4, x - 4, y - 18, 2f, 0xFF3F6B2E); ln(c, x + 5, y + 4, x + 8, y - 14, 2f, 0xFF4F7B36); }
        // dock
        rrn(c, Data.DK_X0 + 6, 1448, Data.DK_X1 - Data.DK_X0, 222, 6, 0x55000000);
        rr(c, Data.DK_X0, 1440, Data.DK_X1 - Data.DK_X0, 215, 5, 0xFF9A6A38);
        for (float y = 1448; y < 1650; y += 14) ln(c, Data.DK_X0 + 2, y, Data.DK_X1 - 2, y, 1.6f, 0xFF5C3A1E);
        for (float y = 1448; y < 1650; y += 70) { ov(c, Data.DK_X0 - 2, y, 5, 5, 0xFF5C3A1E); ov(c, Data.DK_X1 + 2, y, 5, 5, 0xFF5C3A1E); }
    }

    // ------------------------------------------------------------ trees / fence / jack-o'-lantern
    void tree(Canvas c, float x, float y, int type, float t) {
        if (addonSprite(c, "prop_tree", x, y, 1, t)) return;
        float sw = (float) Math.sin(t * 0.8f + x * 0.013f) * 1.8f;
        shadow(c, x + 6, y + 2, 34, 9, 60);
        rr(c, x - 6, y - 40, 12, 40, 3, 0xFF4A2E18);
        if (type < 5) {
            int g1 = 0xFF1F4A32, g2 = 0xFF2C6040, g3 = 0xFF3A7A4C;
            tri(c, g1, x - 38 + sw, y - 28, x + 38 + sw, y - 28, x + sw, y - 82);
            tri(c, g2, x - 31 + sw, y - 56, x + 31 + sw, y - 56, x + sw * 1.4f, y - 112);
            tri(c, g3, x - 23 + sw, y - 86, x + 23 + sw, y - 86, x + sw * 1.8f, y - 138);
        } else {
            int a = type < 8 ? 0xFFD9772B : (type == 8 ? 0xFFB5381E : 0xFFE0B030);
            ov(c, x - 20 + sw, y - 62, 28, 24, mix(a, 0xFF000000, 0.12f)); ov(c, x + 20 + sw, y - 64, 28, 24, mix(a, 0xFF000000, 0.08f)); ov(c, x + sw * 1.4f, y - 90, 34, 28, a);
            ovn(c, x - 8 + sw, y - 98, 14, 9, al(0xFFFFFFFF, 40));
        }
    }
    void fence(Canvas c, float x, float y, boolean vert, boolean last) {
        shadow(c, x + 3, y + 2, 9, 3, 60);
        int w = 0xFF9A6A38, wd = 0xFF6B4423;
        if (vert) { quad(c, w, x - 3, y - 20, x + 3, y - 20, x + 3, y + 70, x - 3, y + 70); ln(c, x, y - 12, x, y + 70, 0.1f, w); }
        else { rrn(c, x, y - 22, 70, 5, 2, wd); rrn(c, x, y - 11, 70, 5, 2, wd); c.drawRect(x, y - 22, x + 70, y - 17, s); c.drawRect(x, y - 11, x + 70, y - 6, s); }
        rr(c, x - 5, y - 30, 10, 32, 3, w); tri(c, 0xFFB98048, x - 5, y - 30, x + 5, y - 30, x, y - 38);
    }
    void jack(Canvas c, float x, float y, float t, float dark) {
        if (addonSprite(c, "prop_jack", x, y, 1, t)) return;
        shadow(c, x, y + 1, 12, 4, 70);
        ov(c, x, y - 10, 11, 9.5f, Data.PUMPKIN); s.setStrokeWidth(1.2f); r.set(x - 5, y - 19, x + 5, y - 1); c.drawOval(r, s); s.setStrokeWidth(2.3f);
        rrn(c, x - 1.5f, y - 23, 3, 5, 1, 0xFF4A6B2A);
        int eye = dark > 0.25f ? 0xFFFFE27A : INK;
        trin(c, eye, x - 6, y - 12, x - 2, y - 12, x - 4, y - 16); trin(c, eye, x + 2, y - 12, x + 6, y - 12, x + 4, y - 16);
        path.reset(); path.moveTo(x - 6, y - 7); path.lineTo(x - 3, y - 4); path.lineTo(x, y - 7); path.lineTo(x + 3, y - 4); path.lineTo(x + 6, y - 7); path.close(); f.setColor(eye); f.setStyle(Paint.Style.FILL); c.drawPath(path, f);
    }

    // ------------------------------------------------------------ farm props
    void well(Canvas c, float t) {
        if (addonSprite(c, "prop_well", Data.WELL_X, Data.WELL_Y + 30, 1, t)) return;
        float x = Data.WELL_X, y = Data.WELL_Y + 30;
        shadow(c, x + 4, y, 36, 11, 70);
        ov(c, x, y - 12, 31, 17, 0xFF8B8B84); ov(c, x, y - 17, 23, 11, 0xFF1F4F6B);
        for (int i = 0; i < 7; i++) ovn(c, x - 28 + i * 9, y - 12 + (float) Math.sin(i * 1.7f) * 10, 5, 3, 0xFFA8A8A0);
        rr(c, x - 28, y - 62, 7, 52, 2, 0xFF6B4423); rr(c, x + 21, y - 62, 7, 52, 2, 0xFF6B4423);
        quad(c, 0xFF8E3B2A, x - 38, y - 60, x + 38, y - 60, x + 26, y - 82, x - 26, y - 82);
        ln(c, x, y - 60, x, y - 38 + (float) Math.sin(t * 2f) * 2f, 1.6f, 0xFF3A2A18); rr(c, x - 5, y - 38 + (float) Math.sin(t * 2f) * 2f, 10, 9, 2, 0xFF8B5A2B);
    }
    void sellBox(Canvas c, float t) {
        if (addonSprite(c, "prop_sellbox", Data.SELL_X, Data.SELL_Y + 14, 1, t)) return;
        float x = Data.SELL_X, y = Data.SELL_Y + 14;
        shadow(c, x + 3, y, 34, 9, 70);
        rr(c, x - 28, y - 32, 56, 34, 4, 0xFF9A6A38); for (int i = 1; i < 4; i++) ln(c, x - 28, y - 32 + i * 8, x + 28, y - 32 + i * 8, 1.4f, 0xFF5C3A1E);
        rr(c, x - 31, y - 38, 62, 9, 3, 0xFFB98048);
        rr(c, x - 16, y - 66, 32, 22, 4, Data.CREAM); ov(c, x, y - 55, 6, 6, 0xFFF2C230); ln(c, x - 22, y - 44, x - 22, y - 30, 2f, 0xFF5C3A1E); ln(c, x + 22, y - 44, x + 22, y - 30, 2f, 0xFF5C3A1E);
    }
    void barrel(Canvas c, float t) {
        if (addonSprite(c, "prop_barrel", Data.OIL_X, Data.OIL_Y + 14, 1, t)) return;
        float x = Data.OIL_X, y = Data.OIL_Y + 14;
        shadow(c, x + 3, y, 24, 8, 70);
        rr(c, x - 19, y - 42, 38, 44, 9, 0xFF3A3A44); ln(c, x - 19, y - 28, x + 19, y - 28, 3f, 0xFF22222A); ln(c, x - 19, y - 12, x + 19, y - 12, 3f, 0xFF22222A);
        ov(c, x, y - 42, 19, 6, 0xFF4A4A56); tri(c, 0xFFF28C28, x - 6, y - 16, x + 6, y - 16, x, y - 36); tri(c, 0xFFFFD36B, x - 3, y - 17, x + 3, y - 17, x, y - 27);
    }
    void log(Canvas c, float x, float y, int hp) {
        if (addonSprite(c, "prop_log", x, y, 1, 0)) return;
        shadow(c, x, y + 3, 30, 8, 60);
        rr(c, x - 28, y - 16, 56, 20, 9, 0xFF7A4A28); ov(c, x - 28, y - 6, 6, 10, 0xFFD2A066); ln(c, x - 22, y - 12, x + 20, y - 12, 1.4f, 0xFF5C3A1E); ln(c, x - 18, y - 4, x + 22, y - 4, 1.4f, 0xFF5C3A1E);
        for (int i = 0; i < 4 - hp; i++) tri(c, 0xFFD2A066, x - 8 + i * 12, y - 16, x - 2 + i * 12, y - 16, x - 5 + i * 12, y - 8);
    }
    void coin(Canvas c, float x, float y, float t) {
        if (addonSprite(c, "prop_coin", x, y, 1, t)) return;
        float b = (float) Math.sin(t * 5f + x) * 2.5f;
        shadow(c, x, y + 2, 8, 3, 70); ov(c, x, y - 9 + b, 8, 8, 0xFFF2C230); ovn(c, x, y - 9 + b, 5, 5, 0xFFFFE27A); ln(c, x - 2, y - 11 + b, x + 2, y - 7 + b, 1.4f, 0xFFB8861A);
    }

    // ------------------------------------------------------------ cabin
    void cabinBack(Canvas c, int candles, float t) {
        float x0 = Data.CX0 + 26, x1 = Data.CX1 - 26, fy0 = Data.IY0, fy1 = Data.IY1;
        rrn(c, Data.CX0 - 6, Data.CY0 + 10, 412, 306, 8, 0x66000000);
        f.setStyle(Paint.Style.FILL); f.setColor(0xFF8A5A33); c.drawRect(x0, fy0, x1, fy1, f);
        for (float y = fy0; y < fy1; y += 17) ln(c, x0, y, x1, y, 1.3f, 0xFF6B4423);
        f.setColor(0xFF5E3A22); c.drawRect(x0, Data.CY0 + 4, x1, fy0, f);
        for (float x = x0; x < x1; x += 22) ln(c, x, Data.CY0 + 4, x, fy0, 1.3f, 0xFF4A2E18);
        rrn(c, 940, 485, 56, 46, 4, 0xFF1A2440); ln(c, 968, 485, 968, 531, 2, 0xFF6B4423); ln(c, 940, 508, 996, 508, 2, 0xFF6B4423);
        ovn(c, 1100, 650, 120, 44, 0xFF8E3B2A); ovn(c, 1100, 650, 96, 32, 0xFFB5523A);
        rr(c, 1030, 548, 140, 9, 2, 0xFF3F2814);
        for (int i = 0; i < 3; i++) {
            float cx = Data.CANDLE_X[i], cy = 548;
            rr(c, cx - 4, cy - 15, 8, 16, 2, 0xFFF3EAD0);
            if ((candles & (1 << i)) != 0) {
                float fl = (float) Math.sin(t * 13f + i * 2f) * 1.4f;
                tri(c, 0xFFFFB347, cx - 4 + fl * 0.3f, cy - 15, cx + 4 + fl * 0.3f, cy - 15, cx + fl, cy - 30); tri(c, 0xFFFFE27A, cx - 2, cy - 15, cx + 2, cy - 15, cx + fl * 0.6f, cy - 25);
            } else ln(c, cx, cy - 16, cx + (float) Math.sin(t * 2f + i) * 3f, cy - 28, 1.4f, 0x66FFFFFF);
        }
        rr(c, 935, 566, 86, 76, 5, 0xFF5C3A1E); rrn(c, 939, 576, 78, 62, 4, 0xFFF3E8CC); rrn(c, 939, 576, 78, 24, 4, 0xFFEADBB4); rrn(c, 939, 606, 78, 32, 4, 0xFFD9772B);
        for (int i = 0; i < 4; i++) ln(c, 939 + i * 20 + 5, 606, 939 + i * 20 + 5, 636, 1.3f, 0xFFB5521A);
        rr(c, 1214, 560, 58, 66, 4, 0xFF6B4423); ln(c, 1243, 562, 1243, 624, 2, INK); ov(c, 1236, 596, 2.4f, 2.4f, 0xFFF2C230); ov(c, 1250, 596, 2.4f, 2.4f, 0xFFF2C230);
        rr(c, 1206, 656, 66, 14, 3, 0xFFB98048); rr(c, 1210, 670, 7, 40, 2, 0xFF5C3A1E); rr(c, 1261, 670, 7, 40, 2, 0xFF5C3A1E);
        ln(c, 1216, 650, 1232, 640, 3, 0xFF8A8A92); rr(c, 1228, 636, 10, 6, 1, 0xFF6B4423); ov(c, 1255, 650, 6, 6, 0xFF9A9AA2); ov(c, 1255, 650, 2, 2, 0xFF3A3A44);
    }

    void cabinFront(Canvas c, float roofA, float doorOpen, float dark, float t) {
        int a = (int) (255 * roofA);
        // front wall strip + door
        f.setStyle(Paint.Style.FILL); f.setColor(0xFF6B4423);
        rr(c, 900, 742, 165, 18, 2, 0xFF6B4423); rr(c, 1135, 742, 165, 18, 2, 0xFF6B4423);
        rr(c, 900, 470, 26, 290, 3, 0xFF6B4423); rr(c, 1274, 470, 26, 290, 3, 0xFF6B4423);
        float dw = 70 * (1f - 0.8f * doorOpen);
        rr(c, 1065, 742, dw, 18, 2, 0xFF8E5A2E);
        if (roofA <= 0.02f) return;
        // roof
        c.save();
        path.reset(); path.moveTo(878, 600); path.lineTo(878, 452); path.quadTo(878, 430, 900, 430); path.lineTo(1300, 430); path.quadTo(1322, 430, 1322, 452); path.lineTo(1322, 600); path.close();
        f.setColor(al(0xFF9A4A32, a)); c.drawPath(path, f);
        path.reset(); path.moveTo(878, 600); path.lineTo(1322, 600); path.lineTo(1322, 750); path.quadTo(1322, 774, 1300, 774); path.lineTo(900, 774); path.quadTo(878, 774, 878, 750); path.close();
        f.setColor(al(0xFF7A3524, a)); c.drawPath(path, f);
        lp.setStyle(Paint.Style.STROKE); lp.setStrokeWidth(2.4f);
        for (int row = 0; row < 11; row++) {
            float y = 440 + row * 15.5f; if (row >= 6) y += 2;
            lp.setColor(al(row < 6 ? 0xFFB8644A : 0xFF5C2417, a * 3 / 4));
            for (float x = 884 + (row & 1) * 11; x < 1316; x += 22) { r.set(x, y, x + 22, y + 14); c.drawArc(r, 0, 180, false, lp); }
        }
        ln(c, 878, 600, 1322, 600, 5, al(0xFF4A1A10, a));
        s.setAlpha(a); path.reset(); path.moveTo(878, 600); path.lineTo(878, 452); path.quadTo(878, 430, 900, 430); path.lineTo(1300, 430); path.quadTo(1322, 430, 1322, 452); path.lineTo(1322, 750); path.quadTo(1322, 774, 1300, 774); path.lineTo(900, 774); path.quadTo(878, 774, 878, 750); path.close();
        c.drawPath(path, s); s.setColor(INK);
        if (a > 100) {
            rrn(c, 1216, 452, 36, 40, 3, 0xFF8B8B84); rrn(c, 1212, 446, 44, 10, 2, 0xFF6B6B66); c.drawRect(1216, 452, 1252, 492, s);
            ov(c, 1100, 510, 20, 20, 0xFF2A1E3A); if (dark > 0.2f) ovn(c, 1100, 510, 15, 15, al(0xFFFFD36B, (int) (200 * dark))); ln(c, 1080, 510, 1120, 510, 2, INK); ln(c, 1100, 490, 1100, 530, 2, INK);
        }
        c.restore();
    }

    void plots(Canvas c, World w, float t) {
        for (int p = 0; p < 5; p++) {
            float px = Data.PLOTS[p][0], py = Data.PLOTS[p][1];
            boolean open = p < w.plots;
            for (int k = 0; k < 9; k++) {
                int i = p * 9 + k; float cx = Data.tileX(i), cy = Data.tileY(i);
                World.Tile tl = w.tiles[i];
                int col = open ? (tl.water > 0 ? Data.SOIL_W : Data.SOIL) : 0xFF3A2A1A;
                rrn(c, cx - 23, cy - 23, 46, 46, 6, col);
                if (open) { lp.setStyle(Paint.Style.STROKE); lp.setStrokeWidth(1.4f); lp.setColor(al(0xFF000000, 60)); ln(c, cx - 17, cy - 8, cx + 17, cy - 8, 1.4f, al(0xFF000000, 50)); ln(c, cx - 17, cy + 8, cx + 17, cy + 8, 1.4f, al(0xFF000000, 50)); }
                if (open && tl.water > 0) ovn(c, cx, cy + 12, 14, 5, al(0xFF6FA8C8, 70));
            }
            if (!open) {
                ln(c, px - 4, py - 4, px + 204, py + 204, 3, 0xFF8B6B3A); ln(c, px + 204, py - 4, px - 4, py + 204, 3, 0xFF8B6B3A);
                rr(c, px + 84, py + 80, 32, 28, 5, 0xFFB9B9C2); r.set(px + 90, py + 62, px + 110, py + 90); s.setStrokeWidth(3.5f); c.drawArc(r, 180, 180, false, s); s.setStrokeWidth(2.3f); ov(c, px + 100, py + 94, 3.2f, 3.2f, INK);
            }
        }
    }

    void boat(Canvas c, World w, float t) {
        if (addonSprite(c, "prop_boat", Data.BOAT_X, Data.BOAT_Y + 18, 1, t)) return;
        float x = Data.BOAT_X + (w.phase == World.PH_WIN ? w.winT * 70f : 0), y = Data.BOAT_Y + (float) Math.sin(t * 1.4f) * 2.5f, rot = (float) Math.sin(t * 1.1f) * 1.8f;
        if (w.phase == World.PH_WIN) rot = 0;
        c.save(); c.translate(x, y); c.rotate(rot);
        ovn(c, 0, 24, 66, 11, al(0xFF000000, 55));
        path.reset(); path.moveTo(-62, -14); path.quadTo(-66, 18, -50, 24); path.lineTo(40, 24); path.quadTo(76, 10, 82, -4); path.quadTo(60, -22, 30, -22); path.lineTo(-50, -22); path.quadTo(-62, -22, -62, -14); path.close();
        f.setStyle(Paint.Style.FILL); f.setColor(0xFF8B5A2B); c.drawPath(path, f); c.drawPath(path, s);
        rrn(c, -54, -14, 108, 30, 10, 0xFF5E3A1E);
        for (int i = 0; i < 6; i++) ln(c, -46 + i * 18, -14, -46 + i * 18, 16, 1.3f, 0xFF3F2814);
        if ((w.boatMask & 1) == 0) { ov(c, -6, 4, 20, 9, 0xFF10304A); tri(c, 0xFF10304A, -26, 4, -14, -6, -18, 10); tri(c, 0xFF10304A, 10, 4, 2, -7, 4, 10); }
        else { rr(c, -22, -6, 32, 6, 1, 0xFFB98048); rr(c, -18, 4, 32, 6, 1, 0xFFB98048); ln(c, -10, -6, -10, 10, 1.4f, INK); }
        if ((w.boatMask & 2) != 0) { rr(c, -76, -14, 20, 24, 3, 0xFF6C7380); rr(c, -80, -10, 8, 12, 2, 0xFF3A3F4A); ov(c, -66, -2, 3, 3, 0xFFF2C230); } else { ln(c, -62, -10, -72, -10, 3, 0xFF5C3A1E); ln(c, -62, 6, -72, 6, 3, 0xFF5C3A1E); }
        if ((w.boatMask & 4) != 0) { c.save(); c.translate(-84, 8); c.rotate(t * 500f); ov(c, 0, -5, 2.6f, 6, 0xFFB9B9C2); ov(c, 0, 5, 2.6f, 6, 0xFFB9B9C2); c.restore(); }
        if ((w.boatMask & 8) != 0) { rr(c, 26, -14, 16, 20, 3, 0xFFC0392B); rr(c, 29, -19, 10, 6, 2, 0xFF8A2519); ln(c, 30, -6, 38, -6, 1.6f, 0xFFF3E8CC); }
        if (w.boatMask == 15) { ln(c, 58, -16, 58, -48, 2.4f, 0xFF3F2814); tri(c, 0xFFF28C28, 58, -48, 58, -30, 80, -39); ov(c, 70, -1, 5, 6, 0xFFFFD36B); }
        c.restore();
    }

    // ------------------------------------------------------------ plants
    void plant(Canvas c, int type, float grow, float water, float x, float y, float t) {
        if (addonCrop(c, type, grow, x, y)) return;
        float sw = (float) Math.sin(t * 1.5f + x * 0.05f) * 1.2f;
        boolean ready = grow >= 1f;
        float g = Math.min(1f, grow);
        shadow(c, x, y + 1, 11 + g * 6, 4, 60);
        if (g < 0.06f) { ov(c, x, y - 2, 8, 4, 0xFF4A2E18); return; }
        int leaf = 0xFF4F9A3C, leaf2 = 0xFF6BB84A;
        switch (type) {
            case 0: { // turnip
                float sc = 0.45f + g * 0.55f;
                if (g > 0.5f) ov(c, x, y - 4 * sc, 8 * sc, 7 * sc, 0xFFE8E0F0); ovn(c, x, y - 7 * sc, 8 * sc, 4.5f * sc, 0xFFB070C8);
                tri(c, leaf, x - 2, y - 8 * sc, x - 10 * sc + sw, y - 24 * sc, x + 1, y - 9 * sc); tri(c, leaf2, x + 1, y - 9 * sc, x + 11 * sc + sw, y - 22 * sc, x + 3, y - 8 * sc); tri(c, leaf, x - 1, y - 9 * sc, x + sw, y - 28 * sc, x + 2, y - 9 * sc);
                break;
            }
            case 1: { // carrot
                float sc = 0.45f + g * 0.55f;
                if (g > 0.5f) tri(c, 0xFFF08A24, x - 5 * sc, y - 4, x + 5 * sc, y - 4, x, y + 3);
                for (int i = -2; i <= 2; i++) tri(c, i % 2 == 0 ? leaf : leaf2, x - 1.5f, y - 3, x + 1.5f, y - 3, x + i * 6 * sc + sw, y - 26 * sc);
                break;
            }
            case 2: { // corn
                float h = 14 + g * 44;
                ln(c, x, y, x + sw, y - h, 5, 0xFF4F8A36); ln(c, x, y - h * 0.4f, x - 14 * g, y - h * 0.8f, 3.4f, leaf); ln(c, x, y - h * 0.55f, x + 15 * g, y - h * 0.95f, 3.4f, leaf2);
                if (ready) { ov(c, x + 5, y - h * 0.55f, 4.5f, 10, 0xFFF2D13C); ln(c, x + 5, y - h * 0.55f - 8, x + 3, y - h * 0.55f - 14, 2, 0xFFB8861A); }
                else if (g > 0.6f) ov(c, x + 5, y - h * 0.5f, 3, 6, 0xFF9ACD50);
                break;
            }
            case 3: { // pumpkin
                float sc = 0.4f + g * 0.6f;
                ln(c, x - 4, y - 6, x - 16 * sc, y - 12 * sc, 2.4f, 0xFF4F8A36); ov(c, x - 16 * sc, y - 12 * sc, 6 * sc, 4 * sc, leaf2);
                ov(c, x, y - 11 * sc, 14 * sc, 11 * sc, Data.PUMPKIN); ovn(c, x - 6 * sc, y - 11 * sc, 4 * sc, 10 * sc, al(0xFFC95F12, 150)); ovn(c, x + 6 * sc, y - 11 * sc, 4 * sc, 10 * sc, al(0xFFC95F12, 150));
                rrn(c, x - 2, y - 24 * sc, 4, 6 * sc, 1, 0xFF4A6B2A);
                break;
            }
            default: { // moonbloom
                float sc = 0.4f + g * 0.6f;
                ln(c, x, y, x + sw, y - 30 * sc, 3, 0xFF3F7A5A); ln(c, x, y - 10 * sc, x - 10 * sc, y - 18 * sc, 2.6f, 0xFF4F9A6C);
                for (int i = 0; i < 6; i++) { double a = i * 1.047 + t * 0.2; ovn(c, x + sw + (float) Math.cos(a) * 8 * sc, y - 32 * sc + (float) Math.sin(a) * 6 * sc, 6 * sc, 4 * sc, 0xFFCFEFFF); }
                ov(c, x + sw, y - 32 * sc, 4 * sc, 4 * sc, 0xFFFFF3A8);
                if (ready || g > 0.5f) glow(c, x + sw, y - 32 * sc, 36 * sc, 0xFF8FD8FF, 90);
                break;
            }
        }
        if (water < 0.22f && !ready) { float bob = (float) Math.sin(t * 4f) * 2f; tri(c, 0xFF6FB8E8, x, y - 46 + bob, x - 5, y - 38 + bob, x + 5, y - 38 + bob); ov(c, x, y - 36 + bob, 5, 5, 0xFF6FB8E8); }
        if (ready) { float a = (t * 3f + x) % 6.28f; ln(c, x + 12, y - 30 + (float) Math.sin(a) * 2, x + 12, y - 24, 2, 0xFFFFF3A8); ln(c, x + 9, y - 27, x + 15, y - 27, 2, 0xFFFFF3A8); }
    }

    // ------------------------------------------------------------ text + bubbles
    final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    void text(Canvas c, String str, float x, float y, float size, int col, Paint.Align al) {
        tp.setTextSize(size); tp.setTextAlign(al);
        tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(Math.max(2f, size * 0.16f)); tp.setStrokeJoin(Paint.Join.ROUND); tp.setColor(INK); c.drawText(str, x, y, tp);
        tp.setStyle(Paint.Style.FILL); tp.setColor(col); c.drawText(str, x, y, tp);
    }
    void textPlain(Canvas c, String str, float x, float y, float size, int col, Paint.Align al) {
        tp.setTextSize(size); tp.setTextAlign(al); tp.setStyle(Paint.Style.FILL); tp.setColor(col); c.drawText(str, x, y, tp);
    }
    void bubble(Canvas c, String msg, float x, float y, float size) {
        tp.setTextSize(size); float w = tp.measureText(msg) + size * 1.1f, h = size * 1.7f;
        rr(c, x - w / 2, y - h, w, h, size * 0.6f, Data.CREAM);
        tri(c, Data.CREAM, x - 4, y - 1, x + 4, y - 1, x, y + 6);
        textPlain(c, msg, x, y - h * 0.3f, size, INK, Paint.Align.CENTER);
    }
    Bitmap lightSprite() {
        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(64, 64, 64, new int[] {0xFFFFFFFF, 0xDDFFFFFF, 0x70FFFFFF, 0x22FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.3f, 0.6f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(64, 64, 64, p);
        return b;
    }

    // ------------------------------------------------------------ characters
    void head(Canvas c, float cx, float cy, int slot, int hat, boolean lit, boolean dead) {
        int col = Data.HEAD_COL[slot & 3], cd = Data.HEAD_COL_D[slot & 3];
        rr(c, cx - 2.6f, cy - 21, 5.2f, 8, 2, 0xFF5A7A2E); ov(c, cx + 6, cy - 17, 6, 2.8f, 0xFF6BA03A);
        ov(c, cx, cy, 17, 14.5f, col);
        s.setStrokeWidth(1.3f); s.setColor(cd);
        r.set(cx - 9.5f, cy - 14, cx + 9.5f, cy + 14); c.drawOval(r, s); r.set(cx - 3.6f, cy - 14.4f, cx + 3.6f, cy + 14.4f); c.drawOval(r, s);
        s.setColor(INK); s.setStrokeWidth(2.3f);
        int fc = lit ? 0xFFFFE27A : INK;
        if (dead) {
            ln(c, cx - 10, cy - 8, cx - 4, cy - 2, 2, INK); ln(c, cx - 4, cy - 8, cx - 10, cy - 2, 2, INK); ln(c, cx + 4, cy - 8, cx + 10, cy - 2, 2, INK); ln(c, cx + 10, cy - 8, cx + 4, cy - 2, 2, INK);
        } else { trin(c, fc, cx - 10, cy - 2, cx - 3, cy - 2, cx - 6.5f, cy - 9); trin(c, fc, cx + 3, cy - 2, cx + 10, cy - 2, cx + 6.5f, cy - 9); }
        trin(c, fc, cx - 2.2f, cy + 1.5f, cx + 2.2f, cy + 1.5f, cx, cy + 5);
        path.reset(); path.moveTo(cx - 9, cy + 6); path.lineTo(cx - 6, cy + 10.5f); path.lineTo(cx - 3, cy + 7); path.lineTo(cx, cy + 10.5f); path.lineTo(cx + 3, cy + 7); path.lineTo(cx + 6, cy + 10.5f); path.lineTo(cx + 9, cy + 6); path.lineTo(cx + 5, cy + 6); path.lineTo(cx - 5, cy + 6); path.close();
        f.setStyle(Paint.Style.FILL); f.setColor(dead ? INK : fc); c.drawPath(path, f);
        switch (hat) {
            case 1: ov(c, cx, cy - 12, 25, 5.5f, 0xFFE0B65C); ov(c, cx, cy - 18, 12, 8, 0xFFEBC670); rrn(c, cx - 12, cy - 15, 24, 3.5f, 1, 0xFFB5381E); break;
            case 2: ov(c, cx, cy - 12, 23, 5, 0xFF3A2A5C); tri(c, 0xFF4A3478, cx - 12, cy - 13, cx + 12, cy - 13, cx + 6, cy - 46); rrn(c, cx - 12, cy - 19, 24, 5, 1, 0xFFF28C28); break;
            case 3: for (int i = 0; i < 6; i++) { double a = i * 1.047; ov(c, cx + 9 + (float) Math.cos(a) * 5, cy - 16 + (float) Math.sin(a) * 5, 3.6f, 3.6f, 0xFFF4F0E0); } ov(c, cx + 9, cy - 16, 3.2f, 3.2f, 0xFFF2C230); break;
            case 4: ov(c, cx, cy - 11, 16, 9, 0xFF3C6EA8); rr(c, cx - 16, cy - 8, 32, 6, 3, 0xFF2C527F); ov(c, cx, cy - 21, 4.5f, 4.5f, 0xFFF4F0E0); break;
            case 5: quad(c, 0xFFF2C230, cx - 12, cy - 12, cx + 12, cy - 12, cx + 12, cy - 19, cx - 12, cy - 19); tri(c, 0xFFF2C230, cx - 12, cy - 19, cx - 6, cy - 19, cx - 9, cy - 28); tri(c, 0xFFF2C230, cx - 3, cy - 19, cx + 3, cy - 19, cx, cy - 30); tri(c, 0xFFF2C230, cx + 6, cy - 19, cx + 12, cy - 19, cx + 9, cy - 28); ov(c, cx, cy - 15.5f, 2.2f, 2.2f, 0xFFE8604C); break;
            case 6: tri(c, 0xFFE8604C, cx - 9, cy - 12, cx + 9, cy - 12, cx, cy - 38); tri(c, 0xFFF2C230, cx - 5, cy - 22, cx + 5, cy - 22, cx, cy - 38); ov(c, cx, cy - 39, 3.4f, 3.4f, 0xFFF4F0E0); break;
            default: break;
        }
    }

    void player(Canvas c, World.Player p, int slot, float t) {
        float x = p.rx, y = p.ry;
        if (p.state == World.ST_DEAD) { float b = (float) Math.sin(t * 3 + slot) * 4; ovn(c, x, y - 36 + b, 11, 15, 0x30FFFFFF); ovn(c, x, y - 40 + b, 5, 6, 0x40FFFFFF); return; }
        if (p.inv > 0 && p.state == World.ST_ALIVE && ((int) (t * 10) & 1) == 0) return;
        if (p.state == World.ST_ALIVE) {
            float fl = p.fx < -0.25f ? -1f : (p.fx > 0.25f ? 1f : (p.shownAnim == 0 ? 1f : p.shownAnim));
            p.shownAnim = fl;
            float at = p.moving ? p.animT * 1.4f : 0f;
            if (addonSprite(c, PKEY[slot & 3], p.rx, p.ry, fl, at) || addonSprite(c, "player", p.rx, p.ry, fl, at)) return;
        }
        float ph = p.animT * 11f;
        boolean mv = p.moving && p.state == World.ST_ALIVE;
        float flip = p.shownAnim == 0 ? 1 : p.shownAnim;
        if (p.fx > 0.25f) flip = 1; else if (p.fx < -0.25f) flip = -1;
        p.shownAnim = flip;
        c.save(); c.translate(x, y);
        shadow(c, 0, 2, 17, 6, 75);
        if (p.state == World.ST_DOWN) { c.rotate(flip * 84f); }
        c.scale(flip, 1f);
        c.rotate(mv ? (float) Math.sin(ph) * 3.2f : (float) Math.sin(t * 2f + slot) * 0.8f, 0, 0);
        float sn = (float) Math.sin(ph), cs = (float) Math.cos(ph);
        float lx = mv ? sn * 5f : 0, lyA = mv ? Math.max(0, cs) * 2.5f : 0, lyB = mv ? Math.max(0, -cs) * 2.5f : 0;
        ov(c, -6 - lx, -3 - lyA, 5.6f, 3.4f, 0xFF3A2A1A); ov(c, 6 + lx, -3 - lyB, 5.6f, 3.4f, 0xFF3A2A1A);
        int shirt = SHIRT[slot & 3];
        rr(c, -11, -29, 22, 24, 6, shirt);
        rrn(c, -7, -29, 14, 9, 3, Data.CREAM); ln(c, -6, -28, -6, -17, 2.2f, shirt); ln(c, 6, -28, 6, -17, 2.2f, shirt);
        ov(c, 0, -13, 3, 3, 0xFFF2C230);
        boolean raised = p.lampRaise && p.state == World.ST_ALIVE;
        float aw = mv ? sn * 3f : 0;
        limb(c, -11, -25, -14, -14 + aw, 4.2f, Data.CREAM);
        boolean pour = p.waterHeld && p.state == World.ST_ALIVE;
        float lanX = 17, lanY = -14 - aw;
        if (raised) { limb(c, 11, -25, 16, -52, 4.2f, Data.CREAM); lanX = 17; lanY = -58; }
        else if (pour) { limb(c, 11, -25, 22, -22, 4.2f, Data.CREAM); }
        else limb(c, 11, -25, 15, -15 - aw, 4.2f, Data.CREAM);
        if (pour) {
            c.save(); c.translate(26, -24); c.rotate(28f);
            rr(c, -8, -6, 16, 12, 3, 0xFF6C8FA8); tri(c, 0xFF6C8FA8, 8, -2, 17, -9, 8, 3); ln(c, -8, -4, -12, -10, 2.2f, 0xFF4A6B88);
            c.restore();
            for (int i = 0; i < 4; i++) { float u = (t * 3f + i * 0.25f) % 1f; ovn(c, 38 + u * 5, -26 + u * 26, 2, 2.6f, 0xFF8FC8F0); }
        }
        if (p.fishState != 0) {
            ln(c, 14, -22, 54, -66, 2.2f, 0xFF5C3A1E);
            float dip = p.fishState >= 3 ? (float) Math.sin(t * 30f) * 3f + 4f : 0f;
            ln(c, 54, -66, 62, -2 + dip * 0.2f, 0.9f, 0xFFF3E8CC);
            ov(c, 62, -2 + dip, 3.6f, 3.6f, p.fishState == 4 ? 0xFFE8302C : 0xFFF4F0E0);
        }
        if (!pour && p.fishState == 0) {
            boolean on = raised || p.lampOn;
            ln(c, lanX, lanY - 6, lanX, lanY - 12, 1.6f, INK);
            rr(c, lanX - 4.5f, lanY - 6, 9, 11, 2, 0xFF3A3A44);
            rrn(c, lanX - 2.8f, lanY - 4, 5.6f, 7, 1, on ? 0xFFFFE27A : 0xFF6B6B62);
        }
        head(c, 0, -42 + (mv ? (float) Math.abs(sn) * -1.2f : (float) Math.sin(t * 3f + slot) * 0.5f), slot, p.hat, p.lampOn || p.lampRaise, p.state == World.ST_DOWN);
        c.restore();
    }

    // ------------------------------------------------------------ creatures
    void skin(Canvas c, World.Mob m, float t) {
        if (addonSprite(c, "mob_skinwalker", m.rx, m.ry, m.face, t)) return;
        float x = m.rx, y = m.ry, fl = m.face == 0 ? 1 : m.face;
        float j1 = (float) Math.sin(t * 31f) * 1.3f, j2 = (float) Math.cos(t * 27f) * 1.3f;
        float ph = t * (m.st == 2 ? 14f : 4f), a = (float) Math.sin(ph) * 6f, b = (float) Math.cos(ph) * 6f;
        c.save(); c.translate(x, y); c.scale(fl, 1f);
        shadow(c, 0, 2, 30, 7, 80);
        int pale = 0xFFC9C2B2;
        limb(c, -14, -13, -26 + a, -28 + j1, 4.6f, pale); limb(c, -26 + a, -28 + j1, -36 + a, -2, 4.2f, pale);
        limb(c, 16, -13, 24 + b, -29 + j2, 4.6f, pale); limb(c, 24 + b, -29 + j2, 36 + b, -2, 4.2f, pale);
        limb(c, -8, -12, -14 - b, -22, 4.6f, pale); limb(c, -14 - b, -22, -20 - b, -2, 4.2f, pale);
        limb(c, 10, -12, 14 + a, -22, 4.6f, pale); limb(c, 14 + a, -22, 22 + a, -2, 4.2f, pale);
        ov(c, 0, -15 + j2, 25, 9.5f, pale);
        for (int i = 0; i < 4; i++) ln(c, -12 + i * 7, -21, -12 + i * 7 + 2, -10, 1.3f, 0xFF8A8478);
        ov(c, 31 + j1, -26, 9, 11, pale);
        ln(c, 26, -34, 22, -42 + j1, 1.6f, INK); ln(c, 30, -36, 30, -46, 1.6f, INK); ln(c, 34, -35, 38, -43, 1.6f, INK);
        ovn(c, 28, -27, 3.2f, 4.4f, 0xFF15110C); ovn(c, 35, -27, 3.2f, 4.4f, 0xFF15110C);
        path.reset(); path.moveTo(27, -20); path.lineTo(30, -18); path.lineTo(33, -20); path.lineTo(36, -18); f.setStyle(Paint.Style.STROKE); t_stroke(c, 1.6f);
        c.restore();
    }
    private void t_stroke(Canvas c, float w) { lp.setStyle(Paint.Style.STROKE); lp.setStrokeWidth(w); lp.setColor(INK); c.drawPath(path, lp); }

    void skinEyes(Canvas c, World.Mob m, float t) {
        float fl = m.face == 0 ? 1 : m.face, x = m.rx + fl * 28, y = m.ry - 27, k = 0.7f + 0.3f * (float) Math.sin(t * 9f);
        glow(c, x, y, 26, 0xFFFFFFFF, (int) (120 * k)); ovn(c, x, y, 2.6f, 3.2f, 0xFFFFFFFF); ovn(c, x + fl * 7, y, 2.6f, 3.2f, 0xFFFFFFFF);
    }

    void demon(Canvas c, World.Mob m, float t) {
        if (addonSprite(c, "mob_demon", m.rx, m.ry, m.face, t)) return;
        float k = m.st == 1 ? Math.min(1f, m.t / 2.2f) : (m.st == 3 ? Math.max(0f, 1f - m.t / 1.4f) : 1f);
        c.save(); c.translate(m.rx, m.ry); c.scale(0.4f + 0.6f * k, 0.3f + 0.7f * k);
        shadow(c, 0, 2, 30, 8, 110);
        int bl = 0xFF0D0A14;
        path.reset(); path.moveTo(-26, 0);
        for (int i = 0; i <= 8; i++) path.lineTo(-26 + i * 6.5f, -4 + (float) Math.sin(t * 6f + i * 1.3f) * 5f);
        path.quadTo(30, -60, 6, -76); path.quadTo(-6, -84, -14, -74); path.quadTo(-34, -56, -26, 0); path.close();
        f.setStyle(Paint.Style.FILL); f.setColor(bl); c.drawPath(path, f);
        for (int i = 0; i < 5; i++) { float w = (float) Math.sin(t * 5f + i) * 6f; ln(c, -22 + i * 11, -20, -28 + i * 14 + w, -4, 2.6f, bl); }
        ln(c, -18, -44, -40, -30 + (float) Math.sin(t * 4f) * 4f, 3, bl); ln(c, 18, -44, 40, -30 + (float) Math.cos(t * 4f) * 4f, 3, bl);
        ovn(c, -8, -58, 5.5f, 7, 0xFFFFFFFF); ovn(c, 8, -58, 5.5f, 7, 0xFFFFFFFF); ovn(c, -8, -57, 1.8f, 4, bl); ovn(c, 8, -57, 1.8f, 4, bl);
        for (int i = 0; i < 6; i++) trin(c, 0xFFFFFFFF, -12 + i * 4.8f, -46, -9 + i * 4.8f, -46, -10.5f + i * 4.8f, -40);
        c.restore();
    }

    void clown(Canvas c, World.Mob m, float t, boolean blue) {
        if (addonSprite(c, blue ? "mob_clown_blue" : "mob_clown", m.rx, m.ry, m.face, t)) return;
        float x = m.rx, y = m.ry, sc = blue ? 1.12f : 1f;
        boolean walk = blue && m.st == 3;
        float bob = walk ? (float) Math.abs(Math.sin(t * 9f)) * 3f : (float) Math.sin(t * 4f) * 2f;
        int main = blue ? 0xFF3C6EA8 : 0xFFD43A2F, hair = blue ? 0xFF7FB3E8 : 0xFFF28C28;
        c.save(); c.translate(x, y); c.scale(sc, sc);
        shadow(c, 0, 2, 20, 6, 75);
        ov(c, -9, -3, 9, 4.2f, main); ov(c, 9, -3, 9, 4.2f, main);
        ov(c, 0, -23 - bob * 0.3f, 14, 17, Data.CREAM);
        for (int i = -1; i <= 1; i++) ovn(c, i * 8, -23 - bob * 0.3f, 3.4f, 15.5f, main);
        r.set(-14, -40 - bob * 0.3f, 14, -6 - bob * 0.3f); c.drawOval(r, s);
        float aw = (float) Math.sin(t * (walk ? 9f : 3f)) * 4f;
        limb(c, -13, -26, -20, -14 + aw, 4.6f, Data.CREAM); limb(c, 13, -26, blue ? 21 : 22, blue ? -12 - aw : -36 + aw, 4.6f, Data.CREAM);
        ov(c, -20, -13 + aw, 4, 4, 0xFFFFFFFF);
        float hy = -49 - bob + (blue ? 3 : 0);
        ov(c, -13, hy - 2, 7, 6, hair); ov(c, 13, hy - 2, 7, 6, hair);
        ov(c, 0, hy, 11, 11, 0xFFF6EBD6);
        tri(c, main, -8, hy - 8, 8, hy - 8, blue ? 4 : 0, hy - 28); ov(c, blue ? 4 : 0, hy - 29, 3, 3, 0xFFF4F0E0);
        ov(c, 0, hy + 1, 3.6f, 3.6f, 0xFFE8302C);
        ovn(c, -4.5f, hy - 3, 1.6f, 2.2f, INK); ovn(c, 4.5f, hy - 3, 1.6f, 2.2f, INK);
        lp.setStyle(Paint.Style.STROKE); lp.setStrokeWidth(2f); lp.setColor(0xFFC0281E);
        if (blue) { r.set(-5, hy + 6, 5, hy + 11); c.drawArc(r, 200, 140, false, lp); float tr = (t * 1.2f) % 1f; ovn(c, -5, hy + tr * 16 - 1, 1.7f, 2.4f, 0xFF8FD8FF); ovn(c, 5, hy + ((tr + 0.5f) % 1f) * 16 - 1, 1.7f, 2.4f, 0xFF8FD8FF); }
        else { r.set(-6, hy + 2, 6, hy + 9); c.drawArc(r, 20, 140, false, lp); ln(c, 22, -36 + aw, 24, -60 + (float) Math.sin(t * 2f) * 3f, 1f, 0xFFF3E8CC); ov(c, 24, -72 + (float) Math.sin(t * 2f) * 3f, 8, 10, 0xFFE8302C); ovn(c, 22, -75 + (float) Math.sin(t * 2f) * 3f, 2, 3, 0x66FFFFFF); }
        c.restore();
    }

    void stag(Canvas c, World.Mob m, float t, boolean wendigo) {
        if (addonSprite(c, wendigo ? "mob_wendigo" : "mob_stag", m.rx, m.ry, m.face, t)) return;
        float fl = m.face == 0 ? 1 : m.face;
        c.save(); c.translate(m.rx, m.ry); c.scale(fl, 1f);
        shadow(c, 0, 2, wendigo ? 30 : 28, 7, 80);
        if (!wendigo) {
            float lg = m.st == 4 ? (float) Math.sin(t * 20f) * 6f : 0f;
            int br = 0xFF8A5A33;
            limb(c, -16, -18, -17 + lg, -2, 4f, 0xFF6B4423); limb(c, -9, -18, -8 - lg, -2, 4f, 0xFF7A4E2B); limb(c, 12, -18, 13 - lg, -2, 4f, 0xFF7A4E2B); limb(c, 18, -18, 19 + lg, -2, 4f, 0xFF6B4423);
            ov(c, 0, -25, 25, 12, br); ovn(c, 0, -20, 20, 6, 0xFFD2A066); ov(c, -26, -28, 4, 6, 0xFFF3E8CC);
            limb(c, 17, -30, 27, -50, 7f, br); ov(c, 32, -52, 9, 6, br); ov(c, 28, -57, 2.6f, 5, br); ovn(c, 37, -51, 2.4f, 2.4f, INK);
            int an = 0xFFE8D8B0;
            limb(c, 29, -58, 25, -74, 2.4f, an); limb(c, 25, -74, 18, -86, 2.2f, an); limb(c, 25, -74, 31, -88, 2.2f, an); limb(c, 27, -65, 36, -76, 2.2f, an); limb(c, 36, -76, 40, -88, 2f, an);
        } else {
            float sh = (float) Math.sin(t * 28f) * 1.4f;
            int dk = 0xFF1B1612;
            limb(c, -8, -44, -16 + sh, -26, 6f, dk); limb(c, -16 + sh, -26, -22, -2, 5f, dk); limb(c, 8, -44, 14 - sh, -26, 6f, dk); limb(c, 14 - sh, -26, 22, -2, 5f, dk);
            ov(c, 0, -58, 15, 26, dk);
            for (int i = 0; i < 5; i++) ln(c, -9, -70 + i * 7, 9, -68 + i * 7, 1.5f, 0xFFB8AE98);
            limb(c, 12, -76, 34, -50 + sh, 5f, dk); limb(c, 34, -50 + sh, 44, -16, 4.6f, dk); ln(c, 44, -16, 48, -8, 1.8f, 0xFFD8CFB8); ln(c, 44, -16, 42, -6, 1.8f, 0xFFD8CFB8);
            limb(c, -10, -76, -28, -48 - sh, 5f, dk); limb(c, -28, -48 - sh, -34, -14, 4.6f, dk);
            ov(c, 14, -92 + sh, 11, 14, 0xFFD8CFB8); ov(c, 20, -84 + sh, 9, 6, 0xFFD8CFB8);
            ovn(c, 12, -96 + sh, 3.4f, 4.4f, 0xFF15110C); ovn(c, 20, -96 + sh, 3, 4, 0xFF15110C);
            int an = 0xFFE8D8B0;
            limb(c, 8, -104 + sh, -2, -124, 3.2f, an); limb(c, -2, -124, -14, -134, 2.6f, an); limb(c, -2, -124, 2, -142, 2.6f, an); limb(c, 16, -104 + sh, 26, -126, 3.2f, an); limb(c, 26, -126, 38, -134, 2.6f, an); limb(c, 26, -126, 24, -144, 2.6f, an);
        }
        c.restore();
    }
    void stagEyes(Canvas c, World.Mob m, float t, boolean wendigo) {
        float fl = m.face == 0 ? 1 : m.face, k = 0.75f + 0.25f * (float) Math.sin(t * 8f);
        float ex = m.rx + fl * (wendigo ? 16 : 37), ey = m.ry - (wendigo ? 96 : 51);
        int col = wendigo ? 0xFFFF3A2A : 0xFFFFE27A;
        glow(c, ex, ey, wendigo ? 30 : 20, col, (int) (150 * k)); ovn(c, ex, ey, 2.6f, 2.6f, 0xFFFFFFFF); ovn(c, ex + fl * (wendigo ? 8 : 0), ey, 2.4f, 2.4f, 0xFFFFFFFF);
    }

    void crow(Canvas c, World.Mob m, float t) {
        if (addonSprite(c, "mob_crow", m.rx, m.ry, m.face, t)) return;
        float x = m.rx, y = m.ry, fl = m.face == 0 ? 1 : m.face;
        boolean fly = m.st == 1 || m.st == 3;
        c.save(); c.translate(x, y); c.scale(fl, 1f);
        if (fly) {
            shadow(c, 0, 26, 9, 3, 50);
            float w = (float) Math.sin(t * 22f) * 11f;
            tri(c, 0xFF1E1A2A, -2, -3, -18, -12 - w, -6, 2); tri(c, 0xFF1E1A2A, 2, -3, 18, -12 + w, 6, 2);
            ov(c, 0, 0, 8, 5.5f, 0xFF1E1A2A); ov(c, 8, -3, 4.4f, 4, 0xFF1E1A2A); tri(c, 0xFFE8A02C, 11, -4, 16, -2, 11, -1);
        } else {
            shadow(c, 0, 8, 10, 3, 60);
            ov(c, 0, -2, 9, 7, 0xFF1E1A2A); ovn(c, -1, -4, 5, 3, 0xFF3A3250); ov(c, 7, -10, 5, 5, 0xFF1E1A2A);
            tri(c, 0xFFE8A02C, 11, -11, 17, -9, 11, -8); ovn(c, 8.5f, -11, 1.3f, 1.3f, 0xFFFFFFFF);
            ln(c, -8, -2, -16, 3, 2.4f, 0xFF1E1A2A); ln(c, -2, 5, -2, 9, 1.4f, 0xFFE8A02C); ln(c, 3, 5, 3, 9, 1.4f, 0xFFE8A02C);
        }
        c.restore();
    }
}
