package com.pumpkinpanic.coop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.util.Random;

/** Everything that is drawn: menus, world, lighting and HUD. */
final class Hud {
    static final int[] LEAF = {0xFFE8762C, 0xFFC94F12, 0xFFE8B02C, 0xFFB03A1E};
    static final int LS = 5;
    static final int T_TREE = 0, T_FENCE = 1, T_PLAYER = 2, T_SKIN = 3, T_DEMON = 4, T_CLOWN = 5, T_STAG = 6, T_CROW = 7, T_PLANT = 8, T_WELL = 9, T_SELL = 10,
        T_BARREL = 11, T_LOG = 12, T_COIN = 13, T_CABIN = 14, T_BOAT = 15, T_JACK = 16;

    final GameView g;
    final Art a;
    final Bitmap light;
    Bitmap lightBmp; Canvas lightC;
    final Paint lightP = new Paint(), bmpP = new Paint(), fp = new Paint(Paint.ANTI_ALIAS_FLAG), sp = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Rect srcR = new Rect(0, 0, 128, 128);
    final RectF dst = new RectF(), rc = new RectF();
    final Path path = new Path();
    final float[] sy = new float[1000]; final int[] sk = new int[1000], si = new int[1000]; int sn;
    final World.Player mascot = new World.Player();
    float shx, shy;
    String ips = ""; float ipsT;
    final float[] sx0 = new float[70], sy0 = new float[70];

    Hud(GameView gv) {
        g = gv; a = gv.art; light = a.lightSprite();
        lightP.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT)); bmpP.setFilterBitmap(true);
        a.tp.setTypeface(Typeface.create("casual", Typeface.BOLD));
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(3f); sp.setColor(Data.INK); sp.setStrokeJoin(Paint.Join.ROUND);
        mascot.active = true; mascot.lampRaise = true; mascot.lampOn = true; mascot.hat = 2; mascot.fx = 1; mascot.state = World.ST_ALIVE;
        Random r = new Random(5);
        for (int i = 0; i < 70; i++) { sx0[i] = r.nextFloat(); sy0[i] = r.nextFloat() * 0.6f; }
        resize();
    }
    void resize() { lightBmp = Bitmap.createBitmap(Math.max(8, (int) (g.sw / LS)), Math.max(8, (int) (g.sh / LS)), Bitmap.Config.ARGB_8888); lightC = new Canvas(lightBmp); }

    // ------------------------------------------------------------ ui helpers
    float U() { return g.u; }
    void panel(Canvas c, float x, float y, float w, float h) {
        rc.set(x + 4 * U(), y + 6 * U(), x + w + 4 * U(), y + h + 6 * U()); fp.setStyle(Paint.Style.FILL); fp.setColor(0x66000000); c.drawRoundRect(rc, 14 * U(), 14 * U(), fp);
        rc.set(x, y, x + w, y + h); fp.setColor(Data.PARCH); c.drawRoundRect(rc, 14 * U(), 14 * U(), fp);
        rc.set(x + 6 * U(), y + 6 * U(), x + w - 6 * U(), y + h - 6 * U()); sp.setStrokeWidth(1.6f * U()); sp.setColor(0xFFBFA56A); c.drawRoundRect(rc, 10 * U(), 10 * U(), sp);
        rc.set(x, y, x + w, y + h); sp.setStrokeWidth(2.6f * U()); sp.setColor(Data.INK); c.drawRoundRect(rc, 14 * U(), 14 * U(), sp);
    }
    void btn(Canvas c, String txt, float x, float y, float w, float h, int id, int style) {
        boolean press = g.pressId == id && id > 0 && style != 3;
        float off = press ? 2 * U() : 0;
        int fill = style == 0 ? Data.PUMPKIN : (style == 1 ? Data.PARCH : (style == 2 ? 0xFFC0452B : 0xFF9A9078));
        if (press) fill = Art.mix(fill, 0xFF000000, 0.15f);
        rc.set(x, y + off + 4 * U(), x + w, y + h + off + 4 * U()); fp.setStyle(Paint.Style.FILL); fp.setColor(0x55000000); c.drawRoundRect(rc, 12 * U(), 12 * U(), fp);
        rc.set(x, y + off, x + w, y + h + off); fp.setColor(fill); c.drawRoundRect(rc, 12 * U(), 12 * U(), fp);
        rc.set(x + 4 * U(), y + off + 4 * U(), x + w - 4 * U(), y + off + h * 0.45f); fp.setColor(0x33FFFFFF); c.drawRoundRect(rc, 8 * U(), 8 * U(), fp);
        rc.set(x, y + off, x + w, y + h + off); sp.setStrokeWidth(2.4f * U()); sp.setColor(Data.INK); c.drawRoundRect(rc, 12 * U(), 12 * U(), sp);
        float fs = Math.min(h * 0.46f, w / Math.max(4, txt.length()) * 1.5f);
        if (style == 0 || style == 2) a.text(c, txt, x + w / 2, y + off + h / 2 + fs * 0.34f, fs, Data.CREAM, Paint.Align.CENTER);
        else a.textPlain(c, txt, x + w / 2, y + off + h / 2 + fs * 0.34f, fs, style == 3 ? 0xFF6A6250 : Data.INK, Paint.Align.CENTER);
        if (style != 3 && id > 0) g.addHit(x, y, w, h, id);
    }
    void round(Canvas c, float cx, float cy, float r, int col, boolean on) {
        fp.setStyle(Paint.Style.FILL); fp.setColor(0x55000000); c.drawCircle(cx, cy + 4 * U(), r, fp);
        fp.setColor(on ? Art.mix(col, 0xFFFFFFFF, 0.25f) : col); c.drawCircle(cx, cy, r, fp);
        fp.setColor(0x33FFFFFF); c.drawCircle(cx, cy - r * 0.25f, r * 0.7f, fp);
        sp.setStrokeWidth(2.6f * U()); sp.setColor(Data.INK); c.drawCircle(cx, cy, r, sp);
    }
    void pill(Canvas c, float x, float y, float w, float h, int col) {
        rc.set(x, y, x + w, y + h); fp.setStyle(Paint.Style.FILL); fp.setColor(col); c.drawRoundRect(rc, h / 2, h / 2, fp); sp.setStrokeWidth(2f * U()); sp.setColor(Data.INK); c.drawRoundRect(rc, h / 2, h / 2, sp);
    }
    void bar(Canvas c, float x, float y, float w, float h, float v, int col) {
        rc.set(x, y, x + w, y + h); fp.setStyle(Paint.Style.FILL); fp.setColor(0xAA1A120A); c.drawRoundRect(rc, h / 2, h / 2, fp);
        if (v > 0.01f) { rc.set(x + 1.5f * U(), y + 1.5f * U(), x + 1.5f * U() + (w - 3 * U()) * Math.min(1f, v), y + h - 1.5f * U()); fp.setColor(col); c.drawRoundRect(rc, h / 2, h / 2, fp); }
        rc.set(x, y, x + w, y + h); sp.setStrokeWidth(2f * U()); sp.setColor(Data.INK); c.drawRoundRect(rc, h / 2, h / 2, sp);
    }
    void txt(Canvas c, String s, float x, float y, float size, int col, Paint.Align al) { a.text(c, s, x, y, size * U(), col, al); }
    void plain(Canvas c, String s, float x, float y, float size, int col, Paint.Align al) { a.textPlain(c, s, x, y, size * U(), col, al); }
    void coinIcon(Canvas c, float x, float y, float r) { fp.setStyle(Paint.Style.FILL); fp.setColor(0xFFF2C230); c.drawCircle(x, y, r, fp); fp.setColor(0xFFFFE27A); c.drawCircle(x, y, r * 0.62f, fp); sp.setStrokeWidth(1.8f * U()); c.drawCircle(x, y, r, sp); }

    // ------------------------------------------------------------ entry
    void draw(Canvas c) {
        g.nh = 0;
        switch (g.screen) {
            case GameView.S_TITLE: title(c); break;
            case GameView.S_HELP: title(c); help(c); break;
            case GameView.S_JOIN: title(c); join(c); break;
            case GameView.S_LOBBY: title(c); lobby(c); break;
            default: play(c); break;
        }
        if (g.msgT > 0 && g.screen != GameView.S_PLAY) { float k = Math.min(1f, g.msgT); pill(c, g.sw / 2 - 230 * U(), g.sh - 56 * U(), 460 * U(), 34 * U(), 0xEEEAD7A5); plain(c, g.msg, g.sw / 2, g.sh - 33 * U(), 16, Data.INK, Paint.Align.CENTER); }
    }

    // ------------------------------------------------------------ title backdrop + menus
    void title(Canvas c) {
        float sw = g.sw, sh = g.sh, u = U(), t = g.time;
        fp.setStyle(Paint.Style.FILL);
        fp.setShader(new LinearGradient(0, 0, 0, sh, new int[] {0xFF140A2C, 0xFF3B1F5C, 0xFF8E3B5A, 0xFFF28C28}, new float[] {0f, 0.42f, 0.74f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, sw, sh, fp); fp.setShader(null);
        fp.setColor(0xCCFFF3D0);
        for (int i = 0; i < 70; i++) { float tw = 0.5f + 0.5f * (float) Math.sin(t * 2f + i); c.drawCircle(sx0[i] * sw, sy0[i] * sh, (0.8f + (i % 3) * 0.5f) * u * (0.6f + 0.4f * tw), fp); }
        float mx = sw * 0.78f, my = sh * 0.27f, mr = 72 * u;
        a.glow(c, mx, my, mr * 2.6f, 0xFFFFE9A8, 90);
        fp.setColor(0xFFFFF1C4); c.drawCircle(mx, my, mr, fp); fp.setColor(0x22B89A5A); c.drawCircle(mx - mr * 0.3f, my - mr * 0.2f, mr * 0.22f, fp); c.drawCircle(mx + mr * 0.25f, my + mr * 0.3f, mr * 0.16f, fp); c.drawCircle(mx + mr * 0.35f, my - mr * 0.35f, mr * 0.1f, fp);
        for (int l = 0; l < 2; l++) {
            path.reset(); path.moveTo(0, sh);
            for (int x = 0; x <= 24; x++) path.lineTo(x * sw / 24f, sh * (0.66f + l * 0.08f) + (float) Math.sin(x * 0.6f + l * 2f) * 22 * u - (l == 0 ? 30 * u : 0));
            path.lineTo(sw, sh); path.close(); fp.setColor(l == 0 ? 0xFF2A1740 : 0xFF1B0F2B); c.drawPath(path, fp);
        }
        for (int i = 0; i < 16; i++) { float x = (i + 0.5f) * sw / 16f, h = (60 + (i * 37) % 50) * u, y = sh * 0.80f + (i % 3) * 8 * u; a.trin(c, 0xFF120A1E, x - 26 * u, y, x + 26 * u, y, x, y - h); a.trin(c, 0xFF120A1E, x - 20 * u, y - h * 0.4f, x + 20 * u, y - h * 0.4f, x, y - h * 1.2f); }
        float cx = sw * 0.70f, cy = sh * 0.83f;
        a.trin(c, 0xFF0E0818, cx - 70 * u, cy - 40 * u, cx + 70 * u, cy - 40 * u, cx, cy - 92 * u); fp.setColor(0xFF0E0818); c.drawRect(cx - 56 * u, cy - 42 * u, cx + 56 * u, cy + 10 * u, fp);
        float fl = 0.8f + 0.2f * (float) Math.sin(t * 9f); a.glow(c, cx - 20 * u, cy - 18 * u, 46 * u, 0xFFFFB347, (int) (150 * fl)); fp.setColor(0xFFFFD36B); c.drawRect(cx - 28 * u, cy - 26 * u, cx - 12 * u, cy - 10 * u, fp);
        fp.setColor(0xFF0A0610); c.drawRect(0, sh * 0.9f, sw, sh, fp);
        for (int i = 0; i < 26; i++) { float k = (t * 0.07f + i * 0.137f) % 1f; float x = (i * 0.083f + (float) Math.sin(t * 0.6f + i) * 0.03f) % 1f * sw, y = sh * (1.02f - k * 1.1f); a.glow(c, x, y, 7 * u, i % 3 == 0 ? 0xFFFFE27A : 0xFFFF9A3C, (int) (220 * (1f - k))); }
        for (int i = 0; i < 14; i++) { float k = (t * 0.05f + i * 0.0713f) % 1f; float x = sw * (1.05f - k * 1.2f) + (float) Math.sin(t + i) * 30 * u, y = sh * (0.1f + ((i * 0.37f) % 0.7f)) + (float) Math.sin(t * 1.3f + i) * 20 * u; fp.setColor(LEAF[i % 4]); rc.set(x - 5 * u, y - 3 * u, x + 5 * u, y + 3 * u); c.save(); c.rotate(t * 60f + i * 40f, x, y); c.drawOval(rc, fp); c.restore(); }
        c.save(); c.translate(sw * 0.16f, sh * 0.95f); c.scale(3.3f * u / 2f * 1.15f, 3.3f * u / 2f * 1.15f);
        mascot.rx = 0; mascot.ry = 0; mascot.fx = 1; a.player(c, mascot, 0, t); a.glow(c, 17, -58, 90, 0xFFFFB347, 150); c.restore();
        if (g.screen == GameView.S_TITLE) {
            bigTitle(c, "PUMPKIN", sw / 2, sh * 0.2f, 70 * u); bigTitle(c, "PANIC", sw / 2, sh * 0.2f + 66 * u, 78 * u);
            txt(c, "co-op farm horror - controlled by code", sw / 2, sh * 0.2f + 96 * u, 17, Data.CREAM, Paint.Align.CENTER);
            float bw = 300 * u, bh = 50 * u, bx = sw / 2 - bw / 2, by = sh * 0.46f, gap = 12 * u;
            btn(c, "Play solo", bx, by, bw, bh, GameView.B_SOLO, 0);
            btn(c, "Host co-op", bx, by + (bh + gap), bw, bh, GameView.B_HOST, 0);
            btn(c, "Join co-op", bx, by + (bh + gap) * 2, bw, bh, GameView.B_JOIN, 0);
            float hw2 = (bw - gap) / 2f; int na = AddonEngineController.activeCount();
            btn(c, "How to play", bx, by + (bh + gap) * 3, hw2, bh, GameView.B_HELP, 1);
            btn(c, na > 0 ? "Addons (" + na + ")" : "Addons", bx + hw2 + gap, by + (bh + gap) * 3, hw2, bh, GameView.B_ADDONS, 1);
            btn(c, "Name: " + g.myName, sw - 214 * u, 14 * u, 200 * u, 38 * u, GameView.B_NAME, 1);
            btn(c, g.soundOn ? "Sound on" : "Sound off", sw - 214 * u, 58 * u, 200 * u, 38 * u, GameView.B_SOUND, 1);
            btn(c, "Code editor", sw - 214 * u, 102 * u, 200 * u, 38 * u, GameView.B_CODE, 1);
        }
    }
    void bigTitle(Canvas c, String s, float cx, float y, float size) {
        a.tp.setTextSize(size); float tot = 0; for (int i = 0; i < s.length(); i++) tot += a.tp.measureText(s.substring(i, i + 1)) + size * 0.02f;
        float x = cx - tot / 2;
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1); a.tp.setTextSize(size); float wd = a.tp.measureText(ch);
            c.save(); c.translate(x + wd / 2, y + (float) Math.sin(g.time * 3f + i * 0.9f) * size * 0.035f); c.rotate((float) Math.sin(g.time * 2f + i * 1.3f) * 3f);
            a.textPlain(c, ch, 3 * U(), 7 * U(), size, 0x88000000, Paint.Align.CENTER); a.text(c, ch, 0, 0, size, i % 2 == 0 ? Data.PUMPKIN : 0xFFFFB347, Paint.Align.CENTER);
            c.restore(); x += wd + size * 0.02f;
        }
    }

    void help(Canvas c) {
        float sw = g.sw, sh = g.sh, u = U(), w = 700 * u, h = 400 * u, x = (sw - w) / 2, y = (sh - h) / 2;
        panel(c, x, y, w, h);
        txt(c, "How to play", sw / 2, y + 40 * u, 28, Data.PUMPKIN_D, Paint.Align.CENTER);
        String[] L = {"No manual controls: write a script (CODE button), your character runs it.",
            "plant()  water()  harvest()  sell()  buy(Item.Saw): each call walks there itself.",
            "Plants only grow while watered. Sell crops, buy seeds, plots, upgrades, boat parts.",
            "Night is deadly: lantern(True), or hide() in the cabin. Read monsters() to react.",
            "Carnival music = a waiting clown: cheer(). A friend is down: revive(player).",
            "Repair the boat (4 parts), then sail(). Endless: a harder island each time (x1.5).",
            "Errors show in the console and the editor: the line and what went wrong.",
            "LAN: one player hosts, friends join. Everybody runs their own script."};
        for (int i = 0; i < L.length; i++) plain(c, L[i], x + 26 * u, y + 76 * u + i * 33 * u, 14, Data.INK, Paint.Align.LEFT);
        btn(c, "Back", sw / 2 - 90 * u, y + h - 58 * u, 180 * u, 42 * u, GameView.B_BACK, 0);
    }

    void join(Canvas c) {
        float sw = g.sw, sh = g.sh, u = U(), w = 640 * u, h = 420 * u, x = (sw - w) / 2, y = (sh - h) / 2;
        panel(c, x, y, w, h);
        txt(c, "Join a co-op game", sw / 2, y + 40 * u, 28, Data.PUMPKIN_D, Paint.Align.CENTER);
        String dots = "...".substring(0, 1 + (int) (g.time * 2) % 3);
        plain(c, "Searching your Wi-Fi / hotspot" + dots, sw / 2, y + 66 * u, 15, 0xFF6B5A3A, Paint.Align.CENTER);
        int n = 0;
        synchronized (g.net.found) {
            for (int i = 0; i < g.net.found.size() && i < 5; i++) {
                Net.Found f = g.net.found.get(i);
                String s = f.name + "  (" + f.players + "/4)  " + Data.DIFF[Math.max(0, Math.min(2, f.diff))] + (f.phase == 1 ? "  in progress" : "") + "   " + f.ip;
                btn(c, s, x + 30 * u, y + 84 * u + n * 48 * u, w - 60 * u, 42 * u, GameView.B_FOUND + i, f.players >= 4 ? 3 : 1); n++;
            }
        }
        if (n == 0) plain(c, "No games found yet. Ask a friend to tap Host co-op.", sw / 2, y + 150 * u, 15, 0xFF6B5A3A, Paint.Align.CENTER);
        if (g.net.status.length() > 0) plain(c, g.net.status, sw / 2, y + h - 98 * u, 14, 0xFF9A2A1A, Paint.Align.CENTER);
        btn(c, "Enter IP address", x + 30 * u, y + h - 76 * u, 270 * u, 46 * u, GameView.B_IP, 0);
        btn(c, "Back", x + w - 300 * u, y + h - 76 * u, 270 * u, 46 * u, GameView.B_BACK, 1);
    }

    void lobby(Canvas c) {
        float sw = g.sw, sh = g.sh, u = U(), w = 760 * u, h = 440 * u, x = (sw - w) / 2, y = (sh - h) / 2;
        World wd = g.w;
        panel(c, x, y, w, h);
        boolean waiting = g.isClient && !g.gotSnap;
        txt(c, g.solo ? "Solo farm" : (g.isHost ? "Hosting co-op" : "Co-op lobby"), sw / 2, y + 38 * u, 28, Data.PUMPKIN_D, Paint.Align.CENTER);
        if (waiting) { plain(c, g.net.status.length() > 0 ? g.net.status : "Connecting...", sw / 2, y + h / 2, 18, Data.INK, Paint.Align.CENTER); btn(c, "Cancel", sw / 2 - 90 * u, y + h - 62 * u, 180 * u, 42 * u, GameView.B_LEAVE, 1); return; }
        for (int i = 0; i < World.NP; i++) {
            World.Player p = wd.pl[i];
            float cx = x + 24 * u + i * 178 * u, cy = y + 60 * u, cw = 164 * u, ch = 190 * u;
            rc.set(cx, cy, cx + cw, cy + ch); fp.setStyle(Paint.Style.FILL); fp.setColor(p.active ? 0xFFF6E7C1 : 0xFFD9C79A); c.drawRoundRect(rc, 12 * u, 12 * u, fp); sp.setStrokeWidth(2.2f * u); sp.setColor(i == g.me ? Data.PUMPKIN : Data.INK); c.drawRoundRect(rc, 12 * u, 12 * u, sp);
            if (p.active) {
                c.save(); c.translate(cx + cw / 2, cy + 102 * u); c.scale(2.6f * u / 2, 2.6f * u / 2); a.head(c, 0, 0, i, p.hat, true, false); c.restore();
                plain(c, p.name, cx + cw / 2, cy + 150 * u, 17, Data.INK, Paint.Align.CENTER);
                String tag = i == 0 ? (g.isHost ? "HOST - you" : "HOST") : (p.bot ? "Helper bot" : (i == g.me ? "You" : "Player"));
                plain(c, tag, cx + cw / 2, cy + 172 * u, 13, 0xFF6B5A3A, Paint.Align.CENTER);
                if (g.isHost && p.bot) btn(c, "x", cx + cw - 34 * u, cy + 6 * u, 28 * u, 28 * u, GameView.B_KICK + i, 2);
            } else plain(c, "Empty", cx + cw / 2, cy + 108 * u, 16, 0xFF8A7F68, Paint.Align.CENTER);
        }
        int diff = g.isHost ? g.lobbyDiff : wd.diff;
        String[] dd = {"Normal: 6 shared lives, gentle monsters", "Hard: 3 lives, faster monsters", "Nightmare: no extra lives!"};
        plain(c, "Difficulty: " + Data.DIFF[diff] + "  -  " + dd[diff].substring(dd[diff].indexOf(':') + 2), sw / 2, y + 276 * u, 16, Data.INK, Paint.Align.CENTER);
        plain(c, !g.isHost ? "Mode: chosen by the host" : (g.lobbyEndless ? "Mode: Endless  -  sail to a copy of the island that is 1.5x harder, again and again" : "Mode: Classic  -  repair the boat and escape"), sw / 2, y + 298 * u, 15, Data.INK, Paint.Align.CENTER);
        if (g.isHost && !g.solo) {
            if (g.time - ipsT > 3f) { ips = Net.localIps(); ipsT = g.time; }
            plain(c, "Friends: tap Join co-op on the same Wi-Fi/hotspot. Your IP: " + ips, sw / 2, y + 322 * u, 14, 0xFF6B5A3A, Paint.Align.CENTER);
        }
        float by = y + h - 70 * u;
        if (g.isHost) {
            btn(c, "Add bot", x + 20 * u, by, 118 * u, 46 * u, GameView.B_BOT, 1);
            btn(c, "Difficulty", x + 148 * u, by, 146 * u, 46 * u, GameView.B_DIFF, 1);
            btn(c, g.lobbyEndless ? "Mode: Endless" : "Mode: Classic", x + 304 * u, by, 170 * u, 46 * u, GameView.B_MODE, 1);
            btn(c, "START", x + 484 * u, by, 130 * u, 46 * u, GameView.B_START, 0);
            btn(c, "Leave", x + w - 130 * u, by, 110 * u, 46 * u, GameView.B_LEAVE, 2);
        } else {
            plain(c, "Waiting for the host to start...", x + 30 * u, by + 30 * u, 17, Data.INK, Paint.Align.LEFT);
            btn(c, "Leave", x + w - 184 * u, by, 160 * u, 46 * u, GameView.B_LEAVE, 2);
        }
    }

    // ------------------------------------------------------------ gameplay frame
    void add(float y, int kind, int idx) { if (sn < sy.length) { sy[sn] = y; sk[sn] = kind; si[sn] = idx; sn++; } }

    void play(Canvas c) {
        float sw = g.sw, sh = g.sh;
        if (!g.groundReady) { fp.setStyle(Paint.Style.FILL); fp.setColor(0xFF2B1A3A); c.drawRect(0, 0, sw, sh, fp); txt(c, "Carving pumpkins...", sw / 2, sh / 2, 26, Data.CREAM, Paint.Align.CENTER); return; }
        World w = g.w; World.Player me = g.lp();
        float z = g.zoom, t = g.time;
        shx = 0; shy = 0;
        if (g.shakeT > 0) { float k = Math.min(1f, g.shakeT * 2f) * g.shakeA * U(); shx = (float) Math.sin(t * 70f) * k; shy = (float) Math.cos(t * 83f) * k; }
        c.save(); c.translate(sw / 2 + shx, sh / 2 + shy); c.scale(z, z); c.translate(-g.camX, -g.camY);
        float vx0 = g.camX - sw / 2 / z - 90, vx1 = g.camX + sw / 2 / z + 90, vy0 = g.camY - sh / 2 / z - 160, vy1 = g.camY + sh / 2 / z + 90;
        Bitmap addonGround = AddonEngineController.sprite("ground");
        if (addonGround != null) { rc.set(0, 0, Data.WW, Data.WH); c.drawBitmap(addonGround, null, rc, null); }
        else {
            for (int i = 0; i < 4; i++) { float ox = (i % 2) * 1300f, oy = (i / 2) * 900f; if (ox + 1300 > vx0 && ox < vx1 && oy + 900 > vy0 && oy < vy1 && a.ground[i] != null) c.drawBitmap(a.ground[i], ox, oy, null); }
        }
        sceneTiles(c, vx0, vy0, vx1, vy1);
        a.plots(c, w, t);
        me.aimK = 0; if (g.panel == 0 && me.state == World.ST_ALIVE && w.phase == World.PH_PLAY) w.aim(me);
        aimMarker(c, me, t);
        a.cabinBack(c, w.candles, t);
        sn = 0;
        for (int i = 0; i < a.nTrees; i++) { float y = a.treeY[i]; if (y < vy0 || y - 160 > vy1) continue; if (a.treeX[i] > vx0 - 50 && a.treeX[i] < vx1 + 50) add(y, T_TREE, i); }
        for (int i = 0; i < a.nFence; i++) { float y = a.fenY[i]; if (y > vy0 && y - 40 < vy1 && a.fenX[i] > vx0 - 80 && a.fenX[i] < vx1 + 80) add(y, T_FENCE, i); }
        for (int i = 0; i < Art.JACKS.length; i++) add(Art.JACKS[i][1], T_JACK, i);
        for (int i = 0; i < w.plots * 9; i++) if (w.tiles[i].type >= 0) add(Data.tileY(i) + 10, T_PLANT, i);
        add(Data.WELL_Y + 30, T_WELL, 0); add(Data.SELL_Y + 14, T_SELL, 0); add(Data.OIL_Y + 14, T_BARREL, 0); add(760, T_CABIN, 0); add(Data.BOAT_Y + 18, T_BOAT, 0);
        for (int i = 0; i < World.NL; i++) if (w.logs[i].hp > 0) add(Data.LOGS[i][1], T_LOG, i);
        for (int i = 0; i < World.NC; i++) if (w.coin[i].on) add(w.coin[i].y, T_COIN, i);
        for (int i = 0; i < World.NP; i++) if (w.pl[i].active) add(w.pl[i].ry, T_PLAYER, i);
        for (int i = 0; i < World.NM; i++) {
            World.Mob m = w.mob[i]; if (!m.on) continue;
            int kind = i == World.M_SKIN ? T_SKIN : (i == World.M_DEMON ? T_DEMON : (i == World.M_STAG ? T_STAG : (i == World.M_CROW ? T_CROW : T_CLOWN)));
            add(i == World.M_CROW && m.st == 0 ? 800 : m.ry, kind, i);
        }
        for (int i = 1; i < sn; i++) { float y = sy[i]; int k = sk[i], ix = si[i], j = i - 1; while (j >= 0 && sy[j] > y) { sy[j + 1] = sy[j]; sk[j + 1] = sk[j]; si[j + 1] = si[j]; j--; } sy[j + 1] = y; sk[j + 1] = k; si[j + 1] = ix; }
        for (int n = 0; n < sn; n++) {
            int ix = si[n];
            switch (sk[n]) {
                case T_TREE: a.tree(c, a.treeX[ix], a.treeY[ix], a.treeT[ix], t); break;
                case T_FENCE: a.fence(c, a.fenX[ix], a.fenY[ix], a.fenV[ix], false); break;
                case T_JACK: a.jack(c, Art.JACKS[ix][0], Art.JACKS[ix][1], t, w.dark()); break;
                case T_PLANT: { World.Tile tl = w.tiles[ix]; a.plant(c, tl.type, tl.grow, tl.water, Data.tileX(ix), Data.tileY(ix) + 10, t); break; }
                case T_WELL: a.well(c, t); break;
                case T_SELL: a.sellBox(c, t); break;
                case T_BARREL: a.barrel(c, t); break;
                case T_CABIN: a.cabinFront(c, g.roofA, g.doorA, w.dark(), t); break;
                case T_BOAT: a.boat(c, w, t); break;
                case T_LOG: a.log(c, Data.LOGS[ix][0], Data.LOGS[ix][1], w.logs[ix].hp); break;
                case T_COIN: a.coin(c, w.coin[ix].x, w.coin[ix].y, t); break;
                case T_PLAYER: playerExtras(c, w, ix, t); break;
                case T_SKIN: a.skin(c, w.mob[ix], t); break;
                case T_DEMON: a.demon(c, w.mob[ix], t); break;
                case T_CLOWN: a.clown(c, w.mob[ix], t, w.mob[ix].st >= 3); break;
                case T_STAG: a.stag(c, w.mob[ix], t, w.mob[ix].st >= 3); break;
                case T_CROW: a.crow(c, w.mob[ix], t); break;
                default: break;
            }
        }
        particles(c, t);
        for (int i = 0; i < World.NP; i++) { World.Player p = w.pl[i]; if (p.active && p.state == World.ST_ALIVE && (p.lampOn || p.lampRaise)) a.glow(c, p.rx + 14, p.ry - 20, w.lampR(p) * 0.7f, 0xFFFFB347, 46); }
        c.restore();
        lighting(c, w);
        c.save(); c.translate(sw / 2 + shx, sh / 2 + shy); c.scale(z, z); c.translate(-g.camX, -g.camY);
        emissive(c, w, t);
        for (int i = 0; i < GameView.NFT; i++) if (g.fL[i] > 0) { int al = (int) (255 * Math.min(1f, g.fL[i] * 1.5f)); a.text(c, g.fT[i], g.fX[i], g.fY[i], 15, Art.al(g.fC[i], al), Paint.Align.CENTER); }
        c.restore();
        vignettes(c, w);
        hud(c, w, me);
    }

    /** Draws the tile layers of the active addon scene (tileset.png, one grid of tiles, first tile = gid 1). */
    void sceneTiles(Canvas c, float vx0, float vy0, float vx1, float vy1) {
        AddonSceneEngine.Scene sc = AddonEngineController.scene();
        if (sc == null || sc.tileWidth <= 0 || sc.tileHeight <= 0) return;
        AddonAssetPipeline ap = AddonEngineController.get().getAssets();
        if (ap == null) return;
        Bitmap[] ts = ap.getSpriteSheet("tileset", sc.tileWidth, sc.tileHeight);
        if (ts.length == 0) return;
        int c0 = Math.max(0, (int) (vx0 / sc.tileWidth)), c1 = (int) (vx1 / sc.tileWidth) + 1;
        int r0 = Math.max(0, (int) (vy0 / sc.tileHeight)), r1 = (int) (vy1 / sc.tileHeight) + 1;
        for (AddonSceneEngine.TileLayer l : sc.layers) {
            for (int r = r0; r < r1 && r < l.tiles.length; r++) {
                int[] row = l.tiles[r];
                for (int col = c0; col < c1 && col < row.length; col++) {
                    int gid = row[col];
                    if (gid > 0 && gid <= ts.length) c.drawBitmap(ts[gid - 1], col * sc.tileWidth, r * sc.tileHeight, null);
                }
            }
        }
    }

    void aimMarker(Canvas c, World.Player p, float t) {
        if (p.aimK == World.K_NONE) return;
        World w = g.w; float x = p.x, y = p.y;
        switch (p.aimK) {
            case World.K_TILE: x = Data.tileX(p.aimI); y = Data.tileY(p.aimI); rc.set(x - 26, y - 26, x + 26, y + 26); sp.setColor(0xFFFFF3A8); sp.setStrokeWidth(2.6f); c.drawRoundRect(rc, 8, 8, sp); sp.setColor(Data.INK); return;
            case World.K_WELL: x = Data.WELL_X; y = Data.WELL_Y - 40; break;
            case World.K_OIL: x = Data.OIL_X; y = Data.OIL_Y - 52; break;
            case World.K_SELL: x = Data.SELL_X; y = Data.SELL_Y - 76; break;
            case World.K_BENCH: x = Data.BENCH_X; y = Data.BENCH_Y - 46; break;
            case World.K_CLOSET: x = Data.CLOSET_X; y = Data.CLOSET_Y - 50; break;
            case World.K_FISH: x = Data.FISH_X; y = Data.FISH_Y - 70; break;
            case World.K_BOAT: x = Data.BOAT_X; y = Data.BOAT_Y - 60; break;
            case World.K_LOG: x = Data.LOGS[p.aimI][0]; y = Data.LOGS[p.aimI][1] - 30; break;
            case World.K_CLOWN: x = w.mob[World.M_CLOWN0 + p.aimI].x; y = w.mob[World.M_CLOWN0 + p.aimI].y - 90; break;
            case World.K_REVIVE: x = w.pl[p.aimI].x; y = w.pl[p.aimI].y - 80; break;
            default: break;
        }
        float b = (float) Math.abs(Math.sin(t * 4f)) * 5f; a.tri(c, 0xFFFFF3A8, x - 7, y - 10 - b, x + 7, y - 10 - b, x, y - b);
    }

    void playerExtras(Canvas c, World w, int i, float t) {
        World.Player p = w.pl[i];
        a.player(c, p, i, t);
        if (p.state == World.ST_DEAD) return;
        if (i != g.me || true) a.text(c, p.name, p.rx, p.ry - 74, 11, i == g.me ? 0xFFFFF3A8 : Data.CREAM, Paint.Align.CENTER);
        if (p.emoteT > 0) a.bubble(c, Data.EMOTE[p.emote % Data.EMOTE.length], p.rx, p.ry - 84, 14);
        if (p.state == World.ST_DOWN) {
            float k = Math.max(0f, p.stateT / w.bleed()); rc.set(p.rx - 16, p.ry - 56, p.rx + 16, p.ry - 24); sp.setColor(0xFFE8302C); sp.setStrokeWidth(3f); c.drawArc(rc, -90, 360 * k, false, sp); sp.setColor(Data.INK);
            a.text(c, "HELP!", p.rx, p.ry - 62, 12, 0xFFFF8A7A, Paint.Align.CENTER);
        }
        if (p.reviveProg > 0.01f) { rc.set(p.rx - 14, p.ry - 90, p.rx + 14, p.ry - 62); sp.setColor(0xFF9AE86A); sp.setStrokeWidth(3.4f); c.drawArc(rc, -90, 360 * Math.min(1f, p.reviveProg / 2f), false, sp); sp.setColor(Data.INK); }
    }

    void particles(Canvas c, float t) {
        fp.setStyle(Paint.Style.FILL);
        for (int i = 0; i < GameView.NPA; i++) {
            if (g.pL[i] <= 0) continue;
            float k = Math.min(1f, g.pL[i] / g.pM[i] * 1.6f);
            int col = g.pC[i], al = (int) (((col >>> 24) == 0 ? 255 : (col >>> 24)) * k);
            fp.setColor(Art.al(col, g.pK[i] == 3 ? (int) (al * 0.6f) : al));
            if (g.pK[i] == 1) { rc.set(g.pX[i] - g.pS[i], g.pY[i] - g.pS[i] * 0.6f, g.pX[i] + g.pS[i], g.pY[i] + g.pS[i] * 0.6f); c.save(); c.rotate(t * 120f + i * 30, g.pX[i], g.pY[i]); c.drawOval(rc, fp); c.restore(); }
            else if (g.pK[i] == 4) { rc.set(g.pX[i] - g.pS[i], g.pY[i] - g.pS[i] * 0.5f, g.pX[i] + g.pS[i], g.pY[i] + g.pS[i] * 0.5f); c.save(); c.rotate(t * 300f + i * 50, g.pX[i], g.pY[i]); c.drawRect(rc, fp); c.restore(); }
            else c.drawCircle(g.pX[i], g.pY[i], g.pS[i], fp);
        }
    }

    void addLight(float wx, float wy, float r, float k) {
        float z = g.zoom, sx = ((wx - g.camX) * z + g.sw / 2 + shx) / LS, syy = ((wy - g.camY) * z + g.sh / 2 + shy) / LS, rr = r * z / LS;
        if (sx + rr < 0 || sx - rr > lightBmp.getWidth() || syy + rr < 0 || syy - rr > lightBmp.getHeight()) return;
        dst.set(sx - rr, syy - rr, sx + rr, syy + rr); lightP.setAlpha((int) (255 * Math.min(1f, k))); lightC.drawBitmap(light, srcR, dst, lightP);
    }
    void lighting(Canvas c, World w) {
        float dk = w.dark();
        if (dk < 0.03f) return;
        int col = Art.mix(0xFF3B1F5C, 0xFF070920, Math.min(1f, dk * 1.3f));
        lightC.drawColor(((int) (dk * 238) << 24) | (col & 0xFFFFFF), PorterDuff.Mode.SRC);
        float t = g.time;
        for (int i = 0; i < World.NP; i++) {
            World.Player p = w.pl[i];
            if (!p.active || p.state != World.ST_ALIVE) continue;
            addLight(p.rx + 12, p.ry - 22, w.lampR(p), 1f); addLight(p.rx, p.ry - 38, 64, 0.9f);
        }
        for (int i = 0; i < 3; i++) if ((w.candles & (1 << i)) != 0) addLight(Data.CANDLE_X[i], 540, 120 + (float) Math.sin(t * 11f + i) * 6f, 0.95f);
        for (int i = 0; i < Art.JACKS.length; i++) addLight(Art.JACKS[i][0], Art.JACKS[i][1] - 10, 78, 0.85f);
        for (int i = 0; i < w.plots * 9; i++) { World.Tile tl = w.tiles[i]; if (tl.type == 4 && tl.grow > 0.4f) addLight(Data.tileX(i), Data.tileY(i) - 20, 62, 0.8f); }
        for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) { World.Mob m = w.mob[i]; if (m.on && m.st == 1) addLight(m.rx, m.ry - 30, 90, 0.9f); }
        if (w.boatMask == 15) addLight(Data.BOAT_X + 70, Data.BOAT_Y - 4, 130, 0.9f);
        addLight(1100, 510, 80, 0.7f);
        for (int i = 0; i < World.NC; i++) if (w.coin[i].on) addLight(w.coin[i].x, w.coin[i].y - 8, 34, 0.7f);
        dst.set(0, 0, g.sw, g.sh); c.drawBitmap(lightBmp, null, dst, bmpP);
    }
    void emissive(Canvas c, World w, float t) {
        float dk = w.dark();
        World.Mob m = w.mob[World.M_SKIN];
        if (m.on && (m.st >= 1) && dk > 0.1f) a.skinEyes(c, m, t);
        World.Mob s = w.mob[World.M_STAG];
        if (s.on && dk > 0.2f && s.st >= 1) a.stagEyes(c, s, t, s.st >= 3);
        World.Mob d = w.mob[World.M_DEMON];
        if (d.on) { a.glow(c, d.rx - 6, d.ry - 52, 24, 0xFFFFFFFF, 90); a.glow(c, d.rx + 6, d.ry - 52, 24, 0xFFFFFFFF, 90); }
        if (dk > 0.2f) for (int i = 0; i < World.NP; i++) { World.Player p = w.pl[i]; if (p.active && p.state == World.ST_ALIVE) a.glow(c, p.rx, p.ry - 42, 20, 0xFFFFC040, (int) (100 * dk)); }
    }

    void vignettes(Canvas c, World w) {
        float sw = g.sw, sh = g.sh, danger = 0f; World.Player p = g.lp();
        World.Mob sk = w.mob[World.M_SKIN]; if (sk.on && sk.st == 2 && !p.inside) danger = Math.max(danger, 1f - World.dist(sk.rx, sk.ry, p.rx, p.ry) / 650f);
        World.Mob sg = w.mob[World.M_STAG]; if (sg.on && sg.st >= 2 && sg.st <= 3) danger = Math.max(danger, 1.1f - World.dist(sg.rx, sg.ry, p.rx, p.ry) / 700f);
        for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) { World.Mob m = w.mob[i]; if (m.on && m.st == 3) danger = Math.max(danger, 1f - World.dist(m.rx, m.ry, p.rx, p.ry) / 350f); }
        if (w.mob[World.M_DEMON].on && p.inside) danger = Math.max(danger, 0.7f);
        if (p.state == World.ST_DOWN) danger = 1f;
        float pulse = 0.75f + 0.25f * (float) Math.sin(g.time * 7f);
        if (danger > 0.05f) {
            int al = (int) (Math.min(1f, danger) * pulse * 150);
            fp.setStyle(Paint.Style.FILL); fp.setShader(new RadialGradient(sw / 2, sh / 2, Math.max(sw, sh) * 0.62f, new int[] {0x00000000, 0x00000000, Art.al(0xFF8A0000, al)}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP)); c.drawRect(0, 0, sw, sh, fp); fp.setShader(null);
        }
        if (g.flashT > 0) { fp.setStyle(Paint.Style.FILL); fp.setColor(Art.al(g.flashCol, (int) ((g.flashCol >>> 24) * Math.min(1f, g.flashT * 3f)))); c.drawRect(0, 0, sw, sh, fp); }
    }

    // ------------------------------------------------------------ HUD
    void hud(Canvas c, World w, World.Player me) {
        float sw = g.sw, sh = g.sh, u = U(), t = g.time;
        boolean live = w.phase == World.PH_PLAY, alive = me.state == World.ST_ALIVE;
        float dk = w.dark(), cyc = w.T % Data.CYC;
        String ph = cyc < 20 ? "Morning" : (cyc < Data.DUSK0 ? "Day" : (cyc < Data.NIGHT0 ? "Dusk" : (cyc < Data.DAWN0 ? "Night" : "Dawn")));
        pill(c, 10 * u, 10 * u, 156 * u, 40 * u, 0xEEEAD7A5);
        float ix = 32 * u, iy = 30 * u;
        if (dk < 0.5f) { fp.setStyle(Paint.Style.FILL); fp.setColor(0xFFF2A12C); for (int i = 0; i < 8; i++) { double an = i * 0.785 + t * 0.4; c.drawCircle(ix + (float) Math.cos(an) * 13 * u, iy + (float) Math.sin(an) * 13 * u, 2 * u, fp); } c.drawCircle(ix, iy, 9 * u, fp); }
        else { fp.setStyle(Paint.Style.FILL); fp.setColor(0xFFFFF1C4); c.drawCircle(ix, iy, 10 * u, fp); fp.setColor(0xFFEAD7A5); c.drawCircle(ix + 5 * u, iy - 3 * u, 8 * u, fp); }
        plain(c, "Day " + w.day(), 52 * u, 27 * u, 15, Data.INK, Paint.Align.LEFT); plain(c, ph, 52 * u, 43 * u, 12, 0xFF6B5A3A, Paint.Align.LEFT);
        int row = 0;
        for (int i = 0; i < World.NP; i++) {
            World.Player p = w.pl[i]; if (!p.active) continue;
            float y = 56 * u + row * 25 * u; row++;
            pill(c, 10 * u, y, 150 * u, 22 * u, i == g.me ? 0xEEFFE9B8 : 0xCCEAD7A5);
            c.save(); c.translate(24 * u, y + 17 * u); c.scale(0.55f * u / 2, 0.55f * u / 2); a.head(c, 0, 0, i, p.hat, false, p.state == World.ST_DOWN); c.restore();
            String st = p.state == World.ST_DOWN ? "DOWN " + (int) p.stateT + "s" : (p.state == World.ST_DEAD ? "zzz" : "");
            plain(c, p.name, 40 * u, y + 16 * u, 12, Data.INK, Paint.Align.LEFT);
            if (st.length() > 0) plain(c, st, 156 * u, y + 16 * u, 11, 0xFFB5281A, Paint.Align.RIGHT);
        }
        float cx = sw / 2 - 170 * u, cy = 10 * u;
        if (cx + 346 * u > sw - 236 * u) cx = Math.max(172 * u, sw - 236 * u - 346 * u);
        pill(c, cx, cy, 124 * u, 34 * u, 0xEEEAD7A5); coinIcon(c, cx + 20 * u, cy + 17 * u, 10 * u); plain(c, "" + w.coins, cx + 36 * u, cy + 25 * u, 19, Data.INK, Paint.Align.LEFT);
        pill(c, cx + 132 * u, cy, 100 * u, 34 * u, 0xEEEAD7A5); a.ovn(c, cx + 152 * u, cy + 18 * u, 9 * u, 8 * u, Data.PUMPKIN); plain(c, w.diff == 2 ? "none" : "x" + w.lives, cx + 168 * u, cy + 24 * u, 16, Data.INK, Paint.Align.LEFT);
        int parts = Integer.bitCount(w.boatMask);
        pill(c, cx + 240 * u, cy, 100 * u, 34 * u, 0xEEEAD7A5); plain(c, "Boat " + parts + "/4", cx + 290 * u, cy + 24 * u, 15, Data.INK, Paint.Align.CENTER);
        if (w.wood > 0 || me.saw) { pill(c, cx + 240 * u, cy + 38 * u, 100 * u, 26 * u, 0xEEEAD7A5); plain(c, "Wood " + w.wood, cx + 290 * u, cy + 57 * u, 13, Data.INK, Paint.Align.CENTER); }
        round(c, sw - 34 * u, 34 * u, 21 * u, Data.PARCH, g.pressId == GameView.B_PAUSE); fp.setColor(Data.INK); fp.setStyle(Paint.Style.FILL); c.drawRect(sw - 41 * u, 25 * u, sw - 36 * u, 43 * u, fp); c.drawRect(sw - 32 * u, 25 * u, sw - 27 * u, 43 * u, fp);
        g.addHit(sw - 55 * u, 13 * u, 42 * u, 42 * u, GameView.B_PAUSE);
        btn(c, "CODE", sw - 142 * u, 12 * u, 80 * u, 42 * u, GameView.B_CODE, 0);
        btn(c, g.scripts.isRunning() ? "STOP" : "RUN", sw - 228 * u, 12 * u, 80 * u, 42 * u, GameView.B_RUNBTN, g.scripts.isRunning() ? 2 : 1);
        scriptPill(c, sw - 228 * u, 58 * u, 214 * u, 24 * u);
        if (w.endless) {
            pill(c, cx, cy + 38 * u, 232 * u, 26 * u, w.island > 1 ? 0xEEF2B8A0 : 0xEEEAD7A5);
            plain(c, "Island " + w.island + "   danger x" + ((int) (w.danger() * 100)) / 100f, cx + 116 * u, cy + 57 * u, 14, Data.INK, Paint.Align.CENTER);
        }

        // bars + hotbar
        float bx = 40 * u, by = sh - 146 * u;
        float cap = Data.CAN_CAP[me.lvl[2]], ocap = Data.OIL_CAP[me.lvl[1]];
        a.tri(c, 0xFF6FB8E8, 24 * u, by - 1 * u, 18 * u, by + 9 * u, 30 * u, by + 9 * u); a.ovn(c, 24 * u, by + 10 * u, 6.4f * u, 6 * u, 0xFF6FB8E8);
        bar(c, bx, by, 176 * u, 17 * u, me.water / cap, 0xFF4F9FD8); plain(c, (int) me.water + "/" + (int) cap, bx + 88 * u, by + 14 * u, 12, 0xFFFFFFFF, Paint.Align.CENTER);
        a.tri(c, 0xFFF28C28, 24 * u, by + 22 * u, 17 * u, by + 40 * u, 31 * u, by + 40 * u);
        bar(c, bx, by + 24 * u, 176 * u, 17 * u, me.oil / ocap, me.oil < ocap * 0.2f && ((int) (t * 3) & 1) == 0 ? 0xFFE8302C : 0xFFF2A12C); plain(c, "Oil " + (int) me.oil, bx + 88 * u, by + 38 * u, 12, 0xFFFFFFFF, Paint.Align.CENTER);
        bar(c, bx, by + 47 * u, 176 * u, 8 * u, me.stamina, 0xFF8FD86A);
        float cxx = 14 * u, cyy = sh - 178 * u;
        for (int k = 0; k < 5; k++) if (me.crops[k] > 0) { pill(c, cxx, cyy, 50 * u, 22 * u, 0xDDEAD7A5); a.ovn(c, cxx + 13 * u, cyy + 11 * u, 7 * u, 7 * u, Data.CROP_COL[k]); plain(c, "" + me.crops[k], cxx + 24 * u, cyy + 17 * u, 14, Data.INK, Paint.Align.LEFT); cxx += 54 * u; }
        if (me.fish > 0) { pill(c, cxx, cyy, 56 * u, 22 * u, 0xDDEAD7A5); a.ovn(c, cxx + 14 * u, cyy + 11 * u, 8 * u, 4.5f * u, 0xFF8FD8FF); plain(c, "" + me.fish, cxx + 28 * u, cyy + 17 * u, 14, Data.INK, Paint.Align.LEFT); }
        for (int k = 0; k < 5; k++) {
            float sx = 14 * u + k * 58 * u, syy = sh - 66 * u; boolean open = k < w.plots, sel = me.sel == k;
            rc.set(sx, syy, sx + 50 * u, syy + 50 * u); fp.setStyle(Paint.Style.FILL); fp.setColor(open ? (sel ? 0xFFFFE9B8 : 0xEEEAD7A5) : 0x99555040); c.drawRoundRect(rc, 10 * u, 10 * u, fp);
            sp.setStrokeWidth((sel ? 4 : 2) * u); sp.setColor(sel ? Data.PUMPKIN : Data.INK); c.drawRoundRect(rc, 10 * u, 10 * u, sp); sp.setColor(Data.INK);
            if (open) {
                c.save(); c.translate(sx + 25 * u, syy + 40 * u); c.scale(0.8f * u / 2, 0.8f * u / 2); a.plant(c, k, 1f, 1f, 0, 0, t); c.restore();
                plain(c, "x" + me.seeds[k], sx + 46 * u, syy + 47 * u, 12, me.seeds[k] > 0 ? Data.INK : 0xFFB5281A, Paint.Align.RIGHT);
            } else plain(c, "?", sx + 25 * u, syy + 33 * u, 22, 0xFFB0A890, Paint.Align.CENTER);
        }

        // fishing prompts (the script reacts to them)
        if (live && alive) {
            if (me.fishState == 4) { float k = 0.6f + 0.4f * (float) Math.sin(t * 20f); a.text(c, "RAISE THE LANTERN!", sw / 2, sh * 0.32f, 34 * u, Art.al(0xFFFF4A3A, (int) (255 * k)), Paint.Align.CENTER); }
            else if (me.fishState == 3) a.text(c, "REEL!", sw / 2, sh * 0.32f, 38 * u, 0xFFFFF3A8, Paint.Align.CENTER);
        }
        float consTop = console(c, sw, sh, u);
        // tip, cue, toast
        if (w.tip >= 0 && w.tip < Data.TIPS.length && live) {
            String s = Data.TIPS[w.tip]; a.tp.setTextSize(15 * u); float tw = a.tp.measureText(s) + 58 * u;
            float tx = sw / 2 - tw / 2, ty = 74 * u; pill(c, tx, ty, tw, 32 * u, 0xF2F6E7C1);
            c.save(); c.translate(tx + 20 * u, ty + 26 * u); c.scale(0.9f * u / 2, 0.9f * u / 2); mascotCrow.rx = 0; mascotCrow.ry = 0; mascotCrow.st = 0; a.crow(c, mascotCrow, t); c.restore();
            plain(c, s, tx + 42 * u, ty + 22 * u, 15, Data.INK, Paint.Align.LEFT);
        }
        float yy = Math.min(sh - 150 * u, consTop - 38 * u);
        if (g.cueT > 0 && live) { a.tp.setTextSize(15 * u); float tw = a.tp.measureText(g.cue) + 30 * u; pill(c, sw / 2 - tw / 2, yy, tw, 30 * u, 0xDD1A120A); plain(c, g.cue, sw / 2, yy + 21 * u, 15, 0xFFFFE9B8, Paint.Align.CENTER); yy -= 36 * u; }
        if (g.msgT > 0) { a.tp.setTextSize(15 * u); float tw = a.tp.measureText(g.msg) + 30 * u; pill(c, sw / 2 - tw / 2, yy, tw, 30 * u, 0xEEEAD7A5); plain(c, g.msg, sw / 2, yy + 21 * u, 15, Data.INK, Paint.Align.CENTER); }
        if (live) arrows(c, w);
        if (me.state == World.ST_DOWN && live) {
            a.text(c, "YOU'RE DOWN", sw / 2, sh * 0.42f, 40 * u, 0xFFFF6A5A, Paint.Align.CENTER);
            bar(c, sw / 2 - 120 * u, sh * 0.42f + 14 * u, 240 * u, 14 * u, me.stateT / w.bleed(), 0xFFE8302C);
            plain(c, "A friend can revive you with  revive(player)", sw / 2, sh * 0.42f + 52 * u, 15, 0xFFFFE9B8, Paint.Align.CENTER);
        } else if (me.state == World.ST_DEAD && live) {
            a.text(c, me.stateT > 0 ? "Respawning in " + (int) Math.ceil(me.stateT) : "Waiting for dawn...", sw / 2, sh * 0.42f, 34 * u, 0xFFFFE9B8, Paint.Align.CENTER);
        }
        // panels
        if (g.panel == 3) pauseMenu(c);
        if (!live) endCard(c, w);
    }
    final World.Mob mascotCrow = new World.Mob();

    // ------------------------------------------------------------ script console + status
    final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
    final ScriptHost.LogLine[] cons = new ScriptHost.LogLine[16];

    /** Small status pill under the CODE button. */
    void scriptPill(Canvas c, float x, float y, float w, float h) {
        ScriptHost sc = g.scripts;
        int st = sc.status;
        int col = st == ScriptHost.RUNNING ? 0xEEC8E6A0 : (st == ScriptHost.ERROR ? 0xEEF4B0A4 : 0xCCEAD7A5);
        pill(c, x, y, w, h, col);
        fp.setStyle(Paint.Style.FILL);
        fp.setColor(st == ScriptHost.RUNNING ? 0xFF3A9A2A : (st == ScriptHost.ERROR ? 0xFFD02A1A : 0xFF8A7F68));
        c.drawCircle(x + 13 * U(), y + h / 2, 5 * U(), fp);
        String s;
        if (st == ScriptHost.RUNNING) s = "running: " + sc.scriptName;
        else if (st == ScriptHost.ERROR && sc.error != null) s = "error" + (sc.error.line > 0 ? " on line " + sc.error.line : "") + ": " + sc.error.type;
        else if (st == ScriptHost.FINISHED) s = "script finished";
        else if (st == ScriptHost.STOPPED) s = "script stopped";
        else s = g.autoRun ? "auto-run on" : "no script running";
        plain(c, s, x + 24 * U(), y + h / 2 + 4.5f * U(), 12, Data.INK, Paint.Align.LEFT);
    }

    /** The script console (print output, warnings, errors). Returns its top edge. Tap it to make it bigger. */
    float console(Canvas c, float sw, float sh, float u) {
        ScriptHost sc = g.scripts;
        boolean big = g.consoleBig, err = sc.status == ScriptHost.ERROR;
        int maxLines = big ? 14 : 6, n = 0;
        long now = System.currentTimeMillis();
        synchronized (sc.log) {
            for (int i = sc.log.size() - 1; i >= 0 && n < maxLines; i--) {
                ScriptHost.LogLine l = sc.log.get(i);
                if (!big && !err && now - l.at > 14000) break;
                cons[n++] = l;
            }
        }
        if (n == 0) return sh;
        float x = 312 * u, wd = sw - x - 12 * u, lh = 13.5f * u, hh = n * lh + 10 * u, y = sh - 12 * u - hh;
        rc.set(x, y, x + wd, y + hh); fp.setStyle(Paint.Style.FILL); fp.setColor(0xD0120C18); c.drawRoundRect(rc, 8 * u, 8 * u, fp);
        sp.setStrokeWidth(1.6f * u); sp.setColor(err ? 0xFFD02A1A : 0xFF6B5A3A); c.drawRoundRect(rc, 8 * u, 8 * u, sp); sp.setColor(Data.INK);
        mono.setTypeface(Typeface.MONOSPACE); mono.setTextSize(11.5f * u);
        float cw = Math.max(1f, mono.measureText("M"));
        int maxChars = Math.max(8, (int) ((wd - 16 * u) / cw));
        for (int k = 0; k < n; k++) {
            ScriptHost.LogLine l = cons[n - 1 - k];
            int col = l.kind == ScriptHost.LOG_ERROR ? 0xFFFF8A7A : (l.kind == ScriptHost.LOG_WARN ? 0xFFFFD36B : (l.kind == ScriptHost.LOG_INFO ? 0xFF9AD8FF : 0xFFEDE5CC));
            String t = l.text.length() > maxChars ? l.text.substring(0, maxChars - 1) + "~" : l.text;
            mono.setColor(col);
            c.drawText(t, x + 8 * u, y + 5 * u + (k + 1) * lh - 3 * u, mono);
        }
        g.addHit(x, y, wd, hh, GameView.B_CONSOLE);
        return y;
    }

    void arrows(Canvas c, World w) {
        World.Mob m = w.mob[World.M_SKIN]; if (m.on && (m.st == 1 || m.st == 2)) arrow(c, m.rx, m.ry, 0xFFFFFFFF);
        for (int i = World.M_CLOWN0; i <= World.M_CLOWN1; i++) { World.Mob k = w.mob[i]; if (k.on && k.st == 1) arrow(c, k.rx, k.ry, 0xFFE8302C); else if (k.on && k.st == 3) arrow(c, k.rx, k.ry, 0xFF4F8FE0); }
        World.Mob s = w.mob[World.M_STAG]; if (s.on && s.st >= 1 && s.st <= 3) arrow(c, s.rx, s.ry, 0xFFFFE27A);
    }
    void arrow(Canvas c, float wx, float wy, int col) {
        float sw = g.sw, sh = g.sh, u = U(), z = g.zoom, sx = (wx - g.camX) * z + sw / 2, sy2 = (wy - g.camY) * z + sh / 2, mg = 46 * u;
        if (sx > mg && sx < sw - mg && sy2 > mg && sy2 < sh - mg) return;
        float dx = sx - sw / 2, dy = sy2 - sh / 2, k = Math.min((sw / 2 - mg) / Math.max(1e-3f, Math.abs(dx)), (sh / 2 - mg) / Math.max(1e-3f, Math.abs(dy)));
        float ex = sw / 2 + dx * k, ey = sh / 2 + dy * k, ang = (float) Math.toDegrees(Math.atan2(dy, dx));
        float pulse = 1f + 0.18f * (float) Math.sin(g.time * 8f);
        c.save(); c.translate(ex, ey); c.rotate(ang); c.scale(pulse, pulse);
        a.tri(c, col, 16 * u, 0, -10 * u, -13 * u, -10 * u, 13 * u); c.restore();
    }

    void pauseMenu(Canvas c) {
        float sw = g.sw, sh = g.sh, u = U(), w = 380 * u, h = 372 * u, x = (sw - w) / 2, y = (sh - h) / 2;
        fp.setStyle(Paint.Style.FILL); fp.setColor(0x88000000); c.drawRect(0, 0, sw, sh, fp);
        panel(c, x, y, w, h);
        txt(c, g.solo ? "Paused" : "Menu", sw / 2, y + 44 * u, 30, Data.PUMPKIN_D, Paint.Align.CENTER);
        if (!g.solo) plain(c, "The farm keeps running!", sw / 2, y + 68 * u, 13, 0xFF6B5A3A, Paint.Align.CENTER);
        btn(c, "Resume", x + 40 * u, y + 84 * u, w - 80 * u, 46 * u, GameView.B_RESUME, 0);
        btn(c, "Open code editor", x + 40 * u, y + 138 * u, w - 80 * u, 46 * u, GameView.B_CODE, 1);
        btn(c, g.autoRun ? "Auto-run script: on" : "Auto-run script: off", x + 40 * u, y + 192 * u, w - 80 * u, 46 * u, GameView.B_AUTORUN, 1);
        btn(c, g.soundOn ? "Sound: on" : "Sound: off", x + 40 * u, y + 246 * u, w - 80 * u, 46 * u, GameView.B_SOUND, 1);
        btn(c, g.isHost && !g.solo ? "End session" : "Leave game", x + 40 * u, y + 300 * u, w - 80 * u, 46 * u, GameView.B_LEAVE, 2);
    }
    void endCard(Canvas c, World w) {
        boolean win = w.phase == World.PH_WIN;
        if (w.phase == World.PH_LOBBY) return;
        if (w.endT < (win ? 3.6f : 1.6f)) return;
        float sw = g.sw, sh = g.sh, u = U(), pw = 560 * u, ph = 380 * u, x = (sw - pw) / 2, y = (sh - ph) / 2;
        fp.setStyle(Paint.Style.FILL); fp.setColor(0x99000000); c.drawRect(0, 0, sw, sh, fp);
        panel(c, x, y, pw, ph);
        txt(c, win ? "You escaped the island!" : (w.endless ? "The islands claimed you..." : "The farm claimed you..."), sw / 2, y + 56 * u, 32, win ? Data.PUMPKIN_D : 0xFFB5281A, Paint.Align.CENTER);
        int mins = (int) (w.T / 60), secs = (int) (w.T % 60);
        String[] L = {w.endless ? "Islands escaped: " + w.statIslands + "   (you fell on island " + w.island + ")" : "Time on the island: " + mins + "m " + secs + "s  (day " + w.day() + ")",
            w.endless ? "Total time: " + mins + "m " + secs + "s  (day " + w.day() + ")" : "Crops harvested: " + w.statHarv, "Crops harvested: " + w.statHarv + "   Fish: " + w.statFish, "Coins earned: " + w.statCoins, "Times downed: " + w.statDowns + "   Revives: " + w.statRev};
        for (int i = 0; i < L.length; i++) plain(c, L[i], sw / 2, y + 108 * u + i * 30 * u, 18, Data.INK, Paint.Align.CENTER);
        if (g.isHost) btn(c, "Back to lobby", x + 40 * u, y + ph - 70 * u, 240 * u, 48 * u, GameView.B_AGAIN, 0); else plain(c, "Waiting for the host...", x + 40 * u, y + ph - 40 * u, 15, 0xFF6B5A3A, Paint.Align.LEFT);
        btn(c, "Leave", x + pw - 200 * u, y + ph - 70 * u, 160 * u, 48 * u, GameView.B_LEAVE, 2);
    }
}
