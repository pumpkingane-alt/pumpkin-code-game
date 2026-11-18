package com.pumpkinpanic.coop;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fully procedural audio: pre-rendered one-shot effects + a real-time stereo synth that plays
 * adaptive music and spatial "audio cue" loops (clown waltz, skinwalker drone, heartbeat...).
 * No audio files are needed.
 */
final class Sfx implements Runnable {
    static final int SR = 22050;

    static final int S_CLICK = 0, S_STEP = 1, S_STEPW = 2, S_PLANT = 3, S_WATER = 4, S_HARVEST = 5, S_COIN = 6, S_SELL = 7, S_BUY = 8,
        S_ERR = 9, S_WELL = 10, S_OIL = 11, S_DOORO = 12, S_DOORC = 13, S_CANDLE_OUT = 14, S_CANDLE_LIT = 15, S_SKIN_ARRIVE = 16,
        S_SKIN_LEAVE = 17, S_SKIN_HIT = 18, S_DEMON = 19, S_CLOWN = 20, S_CLOWN_OK = 21, S_CLOWN_BLUE = 22, S_STAG = 23, S_ROAR = 24,
        S_SCARE = 25, S_DOWN = 26, S_REVIVE = 27, S_RESPAWN = 28, S_CAW = 29, S_DROP = 30, S_CAST = 31, S_BITE = 32, S_CATCH = 33,
        S_HORROR = 34, S_SAW = 35, S_PART = 36, S_EMOTE = 37, S_DAWN = 38, S_DUSK = 39, S_WIN = 40, S_LOSE = 41, S_UNLOCK = 42,
        S_UPGRADE = 43, S_TIP = 44, S_LAMP = 45, NS = 46;

    static final int CH_DAY = 0, CH_NIGHT = 1, CH_SKIN = 2, CH_CLOWN = 3, CH_BLUE = 4, CH_STAG = 5, CH_DEMON = 6, CH_WIND = 7, CH_END = 8, NCH = 9;
    static final int W_SINE = 0, W_TRI = 1, W_SQ = 2, W_SAW = 3;

    private static final Random R = new Random(1234);
    private short[][] bank;
    private final ConcurrentLinkedQueue<float[]> cmds = new ConcurrentLinkedQueue<float[]>();
    private final float[] chTarget = new float[NCH], chPanT = new float[NCH], chCur = new float[NCH], chPan = new float[NCH];
    private final long[] chNext = new long[NCH];
    private final int[] chStep = new int[NCH];
    private volatile float master = 0.8f, musicVol = 1.0f, muffleT, reverbT, endMode;
    private float muffle, reverb, lpL, lpR;
    private boolean ready;
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;
    private long clk;

    // one-shot players
    private static final int NPL = 24;
    private final int[] pId = new int[NPL], pPos = new int[NPL];
    private final float[] pL = new float[NPL], pR = new float[NPL];
    // synth voices
    private static final int NV = 40;
    private final boolean[] vOn = new boolean[NV];
    private final float[] vF = new float[NV], vPh = new float[NV], vA = new float[NV], vDec = new float[NV], vE = new float[NV], vVib = new float[NV],
        vL = new float[NV], vR = new float[NV];
    private final int[] vW = new int[NV], vAge = new int[NV], vAtk = new int[NV], vDur = new int[NV], vCh = new int[NV];
    private int vNext;
    // echo
    private final float[] dlL = new float[SR / 4], dlR = new float[SR / 5];
    private int dlI;
    private float windLp, windLp2;

    // ------------------------------------------------------------ public API
    void setMaster(float v) { master = v; }
    void setMusic(float v) { musicVol = v; }
    void setCh(int ch, float vol, float pan) { chTarget[ch] = vol; chPanT[ch] = pan; }
    void setMuffle(float m) { muffleT = m; }
    void setReverb(float r) { reverbT = r; }
    void setEnd(boolean win) { endMode = win ? 1 : 2; chStep[CH_END] = 0; }
    /** Addon sound keys are "sfx_" + these names, e.g. sfx_harvest.ogg. */
    static final String[] NAMES = {"click","step","stepw","plant","water","harvest","coin","sell","buy","err","well","oil","door_open","door_close","candle_out","candle_lit","skin_arrive","skin_leave","skin_hit","demon","clown","clown_ok","clown_blue","stag","roar","scare","down","revive","respawn","caw","drop","cast","bite","catch","horror","saw","part","emote","dawn","dusk","win","lose","unlock","upgrade","tip","lamp"};
    void play(int id, float vol, float pan) {
        if (id < 0 || id >= NS) return;
        if (id < NAMES.length && AddonEngineController.playOverride("sfx_" + NAMES[id], vol)) return;
        cmds.add(new float[] {id, vol, pan});
    }
    void silence() { for (int i = 0; i < NCH; i++) chTarget[i] = 0; }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "pp-audio");
        thread.setDaemon(true);
        thread.start();
    }
    void stop() {
        running = false;
        if (thread != null) { try { thread.join(400); } catch (InterruptedException e) { /* ignore */ } thread = null; }
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception e) { /* ignore */ }
        track = null;
    }

    public void run() {
        if (bank == null) { short[][] bk = new short[NS][]; for (int i = 0; i < NS; i++) bk[i] = toShort(render(i)); bank = bk; }
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, 4096) * 2, AudioTrack.MODE_STREAM);
            track.play();
            ready = true;
        } catch (Throwable t) { ready = false; running = false; return; }
        short[] chunk = new short[1024];
        while (running) {
            mix(chunk, 512);
            try { track.write(chunk, 0, chunk.length); } catch (Throwable t) { break; }
        }
    }

    // ------------------------------------------------------------ mixer
    void mix(short[] out, int frames) {
        float[] c;
        while ((c = cmds.poll()) != null) startShot((int) c[0], c[1], c[2]);
        float mv = musicVol;
        for (int ch = 0; ch < NCH; ch++) {
            float tgt = chTarget[ch];
            chCur[ch] += (tgt - chCur[ch]) * 0.12f;
            chPan[ch] += (chPanT[ch] - chPan[ch]) * 0.2f;
            if (chCur[ch] > 0.012f) {
                if (clk >= chNext[ch]) { int len = gen(ch); chNext[ch] = clk + len; }
            } else { chNext[ch] = clk; chCur[ch] = tgt == 0 ? 0 : chCur[ch]; }
        }
        float mfl = muffleT, rv = reverbT;
        for (int i = 0; i < frames; i++) {
            float l = 0, r = 0;
            // one-shots
            for (int p = 0; p < NPL; p++) {
                if (pId[p] <= 0) continue;
                short[] b = bank[pId[p] - 1];
                if (pPos[p] >= b.length) { pId[p] = 0; continue; }
                float s = b[pPos[p]++] * (1f / 32768f);
                l += s * pL[p]; r += s * pR[p];
            }
            // synth voices
            for (int v = 0; v < NV; v++) {
                if (!vOn[v]) continue;
                float ph = vPh[v] + vF[v] * (1f + vVib[v] * (float) Math.sin(vAge[v] * 0.0009f)) / SR;
                ph -= (float) Math.floor(ph);
                vPh[v] = ph;
                float x;
                switch (vW[v]) {
                    case W_SINE: x = (float) Math.sin(ph * 6.2831853f); break;
                    case W_TRI: x = ph < 0.5f ? 4f * ph - 1f : 3f - 4f * ph; break;
                    case W_SQ: x = ph < 0.5f ? 0.6f : -0.6f; break;
                    default: x = 2f * ph - 1f;
                }
                int age = vAge[v]++;
                float env = (age < vAtk[v] ? (float) age / vAtk[v] : 1f) * vE[v];
                vE[v] *= vDec[v];
                int left = vDur[v] - age;
                if (left < 400) env *= Math.max(0f, left / 400f);
                float s = x * env * vA[v] * chCur[vCh[v]] * mv;
                l += s * vL[v]; r += s * vR[v];
                if (left <= 0 || vE[v] < 0.0006f) vOn[v] = false;
            }
            // wind / crickets bed
            if (chCur[CH_WIND] > 0.012f) {
                float n = R.nextFloat() * 2f - 1f;
                windLp += (n - windLp) * 0.045f; windLp2 += (windLp - windLp2) * 0.2f;
                float lfo = 0.55f + 0.45f * (float) Math.sin(clk * 0.00005f + i * 0.00005f);
                float s = windLp2 * 2.2f * lfo * chCur[CH_WIND] * 0.5f * mv;
                l += s; r += s;
            }
            // muffle (low-pass) + echo
            muffle += (mfl - muffle) * 0.0005f; reverb += (rv - reverb) * 0.0005f;
            float a = 1f - 0.9f * muffle;
            lpL += (l - lpL) * a; lpR += (r - lpR) * a;
            l = lpL; r = lpR;
            float el = dlL[dlI % dlL.length], er = dlR[dlI % dlR.length];
            dlL[dlI % dlL.length] = l + el * 0.42f; dlR[dlI % dlR.length] = r + er * 0.42f;
            dlI++;
            l += el * reverb; r += er * reverb;
            l *= master; r *= master;
            l = soft(l); r = soft(r);
            out[i * 2] = (short) (l * 30000f); out[i * 2 + 1] = (short) (r * 30000f);
            clk++;
        }
    }
    private static float soft(float x) { if (x > 3f) x = 3f; else if (x < -3f) x = -3f; return x * (27f + x * x) / (27f + 9f * x * x); }

    private void startShot(int id, float vol, float pan) {
        int slot = -1;
        for (int i = 0; i < NPL; i++) if (pId[i] == 0) { slot = i; break; }
        if (slot < 0) { slot = 0; int best = -1; for (int i = 0; i < NPL; i++) if (pPos[i] > best) { best = pPos[i]; slot = i; } }
        pId[slot] = id + 1; pPos[slot] = 0;
        float ang = (Math.max(-1f, Math.min(1f, pan)) + 1f) * 0.7853982f;
        pL[slot] = vol * (float) Math.cos(ang); pR[slot] = vol * (float) Math.sin(ang);
    }

    private void note(int ch, int wave, float midi, float amp, float atk, float dec, float dur, float vib) {
        int v = -1;
        for (int i = 0; i < NV; i++) if (!vOn[(vNext + i) % NV]) { v = (vNext + i) % NV; break; }
        if (v < 0) v = vNext;
        vNext = (v + 1) % NV;
        float ang = (Math.max(-1f, Math.min(1f, chPan[ch])) + 1f) * 0.7853982f;
        vOn[v] = true; vW[v] = wave; vF[v] = mtof(midi); vPh[v] = 0; vA[v] = amp; vAge[v] = 0; vAtk[v] = Math.max(1, (int) (atk * SR));
        vDec[v] = (float) Math.exp(-dec / SR); vE[v] = 1f; vDur[v] = (int) (dur * SR); vVib[v] = vib; vCh[v] = ch;
        vL[v] = (float) Math.cos(ang); vR[v] = (float) Math.sin(ang);
    }
    static float mtof(float m) { return 440f * (float) Math.pow(2.0, (m - 69f) / 12f); }

    // ------------------------------------------------------------ music generators; return samples until next call
    private static final int[] DAY_MEL = {76, 0, 0, 79, 0, 76, 0, 0, 72, 0, 0, 76, 0, 0, 74, 0, 77, 0, 0, 81, 0, 77, 0, 0, 79, 0, 0, 74, 0, 0, 72, 0};
    private static final int[][] DAY_CH = {{48, 52, 55}, {45, 48, 52}, {41, 45, 48}, {43, 47, 50}};
    private static final int[] CLOWN_MEL = {76, 76, 79, 84, 83, 79, 81, 81, 84, 79, 0, 0, 77, 77, 81, 86, 84, 81, 79, 76, 74, 72, 0, 0};
    private static final int[] BLUE_MEL = {69, 72, 76, 74, 72, 71, 69, 0};

    private int gen(int ch) {
        int s = chStep[ch]++;
        float rnd = R.nextFloat();
        switch (ch) {
            case CH_DAY: {
                int bar = (s / 8) % 4, st = s % 8;
                int[] cd = DAY_CH[bar];
                if (st == 0 || st == 4) note(ch, W_TRI, cd[0] - 12, 0.30f, 0.01f, 3.2f, 0.9f, 0f);
                note(ch, W_TRI, cd[(st % 4 == 3) ? 1 : st % 3] + 12 + (st & 1) * 12, 0.16f, 0.004f, 5.5f, 0.5f, 0f);
                int m = DAY_MEL[s % 32];
                if (m > 0) { note(ch, W_SINE, m, 0.20f, 0.01f, 2.6f, 0.9f, 0.004f); note(ch, W_SINE, m + 12.07f, 0.04f, 0.01f, 4f, 0.5f, 0f); }
                return (int) (SR * 60f / 92f / 2f);
            }
            case CH_NIGHT: {
                if (s % 8 == 0) { float f = (s / 8) % 2 == 0 ? 33f : 29f; note(ch, W_SAW, f, 0.07f, 1.6f, 0.45f, 4.5f, 0.01f); note(ch, W_SINE, f + 12, 0.18f, 1.2f, 0.4f, 4.5f, 0.006f); }
                if (rnd < 0.28f) { int[] sc = {57, 58, 61, 64, 65, 69}; note(ch, W_SINE, sc[R.nextInt(sc.length)], 0.17f, 0.005f, 1.5f, 2.6f, 0.002f); }
                return SR / 2;
            }
            case CH_SKIN: {
                note(ch, W_SAW, 49, 0.06f, 0.4f, 0.8f, 1.6f, 0.02f); note(ch, W_SAW, 50.1f, 0.06f, 0.4f, 0.8f, 1.6f, 0.03f);
                note(ch, W_SINE, 73 + (s % 3), 0.07f, 0.05f, 1.2f, 1.2f, 0.01f);
                if (rnd < 0.5f) note(ch, W_SQ, 96 + R.nextInt(3), 0.03f, 0.002f, 12f, 0.12f, 0f);
                if (s % 4 == 3) note(ch, W_SAW, 62, 0.07f, 0.05f, 0.8f, 1.8f, 0.05f);
                return SR * 7 / 10;
            }
            case CH_CLOWN: {
                int b = s % 24, beat = s % 3;
                int m = CLOWN_MEL[b];
                if (m > 0) note(ch, W_SQ, m, 0.11f, 0.01f, 3.5f, 0.3f, 0.01f);
                int root = new int[] {48, 48, 53, 53, 55, 55, 48, 48}[b / 3];
                if (beat == 0) note(ch, W_TRI, root - 12, 0.28f, 0.005f, 6f, 0.3f, 0f);
                else { note(ch, W_SQ, root + 4, 0.06f, 0.005f, 14f, 0.15f, 0f); note(ch, W_SQ, root + 7, 0.06f, 0.005f, 14f, 0.15f, 0f); }
                return (int) (SR * 60f / 138f);
            }
            case CH_BLUE: {
                int m = BLUE_MEL[s % 8];
                if (m > 0) { note(ch, W_SINE, m, 0.22f, 0.01f, 1.8f, 1.8f, 0.012f); note(ch, W_SINE, m + 0.35f, 0.08f, 0.01f, 1.8f, 1.8f, 0.02f); }
                if (s % 4 == 0) note(ch, W_TRI, 45, 0.18f, 0.2f, 0.7f, 2.4f, 0f);
                return SR * 9 / 10;
            }
            case CH_STAG: {
                float e = 0.4f + 0.6f * chCur[CH_STAG];
                if ((s & 1) == 0) { note(ch, W_SINE, 43, 0.6f, 0.004f, 14f, 0.22f, 0f); note(ch, W_SINE, 55, 0.25f, 0.002f, 30f, 0.1f, 0f); }
                else { note(ch, W_SINE, 41, 0.4f, 0.004f, 14f, 0.2f, 0f); note(ch, W_SINE, 53, 0.18f, 0.002f, 30f, 0.1f, 0f); }
                if ((s & 3) == 0) note(ch, W_SAW, 36, 0.05f, 0.3f, 1.4f, 1.0f, 0.02f);
                return (int) (SR * (0.95f - 0.35f * e) / ((s & 1) == 0 ? 3.2f : 1.4f));
            }
            case CH_DEMON: {
                note(ch, W_SAW, 35 + R.nextInt(3), 0.08f, 0.6f, 0.9f, 2f, 0.04f);
                if (rnd < 0.6f) note(ch, W_SINE, 82 + R.nextInt(6), 0.05f, 0.3f, 1.4f, 1f, 0.05f);
                return SR * 6 / 10;
            }
            case CH_WIND: {
                if (rnd < 0.7f) note(ch, W_SINE, 106 + R.nextInt(2), 0.05f, 0.002f, 20f, 0.08f, 0.02f);
                return SR / 9 + R.nextInt(SR / 6);
            }
            default: { // CH_END
                if (endMode == 1f) { int[] m = {60, 64, 67, 72, 76, 79, 84, 79}; note(ch, W_TRI, m[s % 8], 0.3f, 0.005f, 3f, 0.8f, 0f); note(ch, W_SINE, m[s % 8] - 12, 0.25f, 0.01f, 2f, 0.9f, 0f); return SR * 28 / 100; }
                int[] m = {69, 65, 64, 60, 57, 0}; if (m[s % 6] > 0) note(ch, W_SINE, m[s % 6], 0.3f, 0.01f, 1f, 1.8f, 0.01f); return SR * 85 / 100;
            }
        }
    }

    // ------------------------------------------------------------ offline sample rendering
    private static short[] toShort(float[] f) {
        short[] s = new short[f.length];
        for (int i = 0; i < f.length; i++) { float v = f[i]; s[i] = (short) (Math.max(-1f, Math.min(1f, v)) * 31000f); }
        return s;
    }
    private static float[] buf(float sec) { return new float[(int) (sec * SR)]; }
    private static float wv(int w, float ph) {
        switch (w) {
            case W_SINE: return (float) Math.sin(ph * 6.2831853f);
            case W_TRI: return ph < 0.5f ? 4f * ph - 1f : 3f - 4f * ph;
            case W_SQ: return ph < 0.5f ? 0.5f : -0.5f;
            default: return 2f * ph - 1f;
        }
    }
    private static void tone(float[] b, float t0, float dur, float f0, float f1, float amp, int wave, float atk, float dec) {
        int s0 = (int) (t0 * SR), n = (int) (dur * SR);
        if (n <= 0) return;
        double ph = 0, f = f0, r = Math.pow(f1 / f0, 1.0 / n);
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            float t = (float) i / SR;
            ph += f / SR; f *= r;
            float env = (t < atk ? t / atk : 1f) * (float) Math.exp(-dec * t);
            float fade = Math.min(1f, (n - i) / 200f);
            b[s0 + i] += wv(wave, (float) (ph - Math.floor(ph))) * env * amp * fade;
        }
    }
    private static void noise(float[] b, float t0, float dur, float amp, float lp0, float lp1, float atk, float dec) {
        int s0 = (int) (t0 * SR), n = (int) (dur * SR);
        float y = 0;
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            float t = (float) i / SR, fc = lp0 + (lp1 - lp0) * i / n;
            float a = 1f - (float) Math.exp(-6.2831853f * fc / SR);
            y += a * ((R.nextFloat() * 2f - 1f) - y);
            float env = (t < atk ? t / atk : 1f) * (float) Math.exp(-dec * t);
            b[s0 + i] += y * env * amp * Math.min(1f, (n - i) / 200f) * 1.8f;
        }
    }
    private static void chime(float[] b, float t0, float f, float amp, float dec) { tone(b, t0, 0.6f, f, f, amp, W_SINE, 0.002f, dec); tone(b, t0, 0.4f, f * 2.01f, f * 2.01f, amp * 0.3f, W_SINE, 0.002f, dec * 1.5f); }

    private static float[] render(int id) {
        float[] b;
        switch (id) {
            case S_CLICK: b = buf(0.07f); tone(b, 0, 0.07f, 900, 600, 0.5f, W_TRI, 0.002f, 40); return b;
            case S_STEP: b = buf(0.09f); noise(b, 0, 0.09f, 0.4f, 1400, 500, 0.003f, 38); return b;
            case S_STEPW: b = buf(0.1f); tone(b, 0, 0.1f, 160, 90, 0.5f, W_SINE, 0.002f, 35); noise(b, 0, 0.05f, 0.2f, 2200, 900, 0.001f, 60); return b;
            case S_PLANT: b = buf(0.25f); tone(b, 0, 0.14f, 140, 70, 0.6f, W_SINE, 0.002f, 22); noise(b, 0.02f, 0.2f, 0.25f, 1800, 600, 0.01f, 14); return b;
            case S_WATER: b = buf(0.5f); noise(b, 0, 0.5f, 0.4f, 3500, 1800, 0.05f, 4); for (int i = 0; i < 6; i++) tone(b, 0.04f * i, 0.06f, 700 + R.nextInt(500), 1400, 0.12f, W_SINE, 0.002f, 40); return b;
            case S_HARVEST: b = buf(0.3f); tone(b, 0, 0.14f, 500, 900, 0.5f, W_SINE, 0.003f, 12); tone(b, 0.05f, 0.18f, 700, 1400, 0.35f, W_TRI, 0.003f, 10); return b;
            case S_COIN: b = buf(0.5f); chime(b, 0, 1568, 0.5f, 12); chime(b, 0.07f, 2093, 0.4f, 11); return b;
            case S_SELL: b = buf(0.9f); chime(b, 0, 1318, 0.4f, 9); chime(b, 0.09f, 1568, 0.4f, 9); chime(b, 0.18f, 2093, 0.45f, 8); noise(b, 0, 0.05f, 0.3f, 5000, 3000, 0.001f, 60); chime(b, 0.3f, 2637, 0.3f, 6); return b;
            case S_BUY: b = buf(0.5f); chime(b, 0, 880, 0.45f, 9); chime(b, 0.1f, 1318, 0.45f, 8); return b;
            case S_ERR: b = buf(0.3f); tone(b, 0, 0.12f, 180, 170, 0.4f, W_SQ, 0.003f, 6); tone(b, 0.14f, 0.14f, 150, 140, 0.4f, W_SQ, 0.003f, 6); return b;
            case S_WELL: b = buf(0.7f); noise(b, 0, 0.5f, 0.35f, 2600, 700, 0.02f, 5); tone(b, 0.1f, 0.4f, 300, 120, 0.25f, W_SINE, 0.01f, 6); for (int i = 0; i < 4; i++) tone(b, 0.2f + 0.08f * i, 0.07f, 500 + R.nextInt(600), 900, 0.1f, W_SINE, 0.002f, 40); return b;
            case S_OIL: b = buf(0.6f); for (int i = 0; i < 5; i++) tone(b, 0.1f * i, 0.09f, 220, 120, 0.3f, W_SINE, 0.005f, 20); noise(b, 0, 0.5f, 0.1f, 900, 400, 0.05f, 4); return b;
            case S_DOORO: b = buf(0.6f); tone(b, 0, 0.55f, 150, 240, 0.22f, W_SAW, 0.05f, 4); noise(b, 0, 0.5f, 0.12f, 800, 1500, 0.1f, 4); return b;
            case S_DOORC: b = buf(0.5f); tone(b, 0, 0.3f, 230, 140, 0.18f, W_SAW, 0.02f, 5); tone(b, 0.28f, 0.15f, 110, 55, 0.7f, W_SINE, 0.002f, 20); noise(b, 0.28f, 0.1f, 0.3f, 1200, 400, 0.001f, 25); return b;
            case S_CANDLE_OUT: b = buf(0.7f); noise(b, 0, 0.6f, 0.5f, 3200, 300, 0.05f, 4); return b;
            case S_CANDLE_LIT: b = buf(0.5f); for (int i = 0; i < 7; i++) noise(b, 0.05f * i + R.nextFloat() * 0.03f, 0.03f, 0.3f, 4000, 2000, 0.001f, 80); tone(b, 0.1f, 0.35f, 400, 800, 0.15f, W_SINE, 0.1f, 6); return b;
            case S_SKIN_ARRIVE: b = buf(2f); tone(b, 0, 1.9f, 95, 42, 0.45f, W_SAW, 0.4f, 1.2f); tone(b, 0, 1.8f, 220, 233, 0.12f, W_SINE, 0.3f, 1f); for (int i = 0; i < 8; i++) noise(b, 0.2f + i * 0.2f, 0.12f, 0.25f, 3000, 1200, 0.005f, 14); return b;
            case S_SKIN_LEAVE: b = buf(1.4f); tone(b, 0, 1.3f, 70, 140, 0.3f, W_SAW, 0.3f, 2f); tone(b, 0, 1.2f, 330, 120, 0.1f, W_SINE, 0.1f, 2f); return b;
            case S_SKIN_HIT: b = buf(0.5f); for (int i = 0; i < 4; i++) noise(b, 0.07f * i, 0.09f, 0.5f, 3000, 900, 0.004f, 18); tone(b, 0, 0.3f, 90, 60, 0.3f, W_SINE, 0.005f, 8); return b;
            case S_DEMON: b = buf(1.8f); tone(b, 0, 1.7f, 80, 48, 0.4f, W_SAW, 0.5f, 1.3f); noise(b, 0, 1.6f, 0.3f, 900, 300, 0.6f, 1.2f); tone(b, 0.2f, 1.2f, 740, 500, 0.06f, W_SINE, 0.5f, 2f); return b;
            case S_CLOWN: b = buf(0.5f); tone(b, 0, 0.14f, 340, 300, 0.4f, W_SQ, 0.005f, 3); tone(b, 0.2f, 0.2f, 340, 260, 0.4f, W_SQ, 0.005f, 3); return b;
            case S_CLOWN_OK: b = buf(0.8f); float[] ar = {523, 659, 784, 1047}; for (int i = 0; i < 4; i++) tone(b, 0.08f * i, 0.2f, ar[i], ar[i], 0.35f, W_TRI, 0.003f, 8); tone(b, 0.4f, 0.25f, 340, 290, 0.3f, W_SQ, 0.005f, 3); return b;
            case S_CLOWN_BLUE: b = buf(1.2f); tone(b, 0, 0.3f, 233, 233, 0.3f, W_SAW, 0.04f, 3); tone(b, 0.3f, 0.3f, 220, 207, 0.3f, W_SAW, 0.02f, 3); tone(b, 0.6f, 0.55f, 196, 120, 0.3f, W_SAW, 0.02f, 2.5f); return b;
            case S_STAG: b = buf(0.8f); noise(b, 0, 0.5f, 0.3f, 600, 300, 0.1f, 4); tone(b, 0.3f, 0.12f, 120, 80, 0.5f, W_SINE, 0.002f, 25); tone(b, 0.5f, 0.12f, 110, 70, 0.5f, W_SINE, 0.002f, 25); return b;
            case S_ROAR: b = buf(1.3f); tone(b, 0, 1.2f, 110, 55, 0.5f, W_SAW, 0.05f, 2f); noise(b, 0, 1.2f, 0.5f, 1800, 400, 0.02f, 2.5f); tone(b, 0, 1f, 420, 200, 0.18f, W_SQ, 0.03f, 3f); return b;
            case S_SCARE: b = buf(0.8f); noise(b, 0, 0.7f, 0.5f, 500, 5000, 0.02f, 4); tone(b, 0, 0.6f, 900, 180, 0.2f, W_SAW, 0.01f, 5); return b;
            case S_DOWN: b = buf(0.9f); tone(b, 0, 0.3f, 130, 38, 0.8f, W_SINE, 0.002f, 8); noise(b, 0, 0.2f, 0.4f, 1500, 300, 0.002f, 14); tone(b, 0.1f, 0.7f, 440, 110, 0.15f, W_TRI, 0.01f, 4); return b;
            case S_REVIVE: b = buf(1f); float[] rv = {523, 659, 784, 1047, 1319}; for (int i = 0; i < 5; i++) chime(b, 0.08f * i, rv[i], 0.35f, 5); return b;
            case S_RESPAWN: b = buf(0.9f); for (int i = 0; i < 6; i++) chime(b, 0.06f * i, 880 + i * 160, 0.25f, 7); return b;
            case S_CAW: b = buf(0.6f); for (int i = 0; i < 2; i++) { tone(b, 0.22f * i, 0.18f, 700, 480, 0.3f, W_SAW, 0.01f, 7); noise(b, 0.22f * i, 0.16f, 0.25f, 2400, 1200, 0.01f, 8); } return b;
            case S_DROP: b = buf(0.4f); chime(b, 0, 1760, 0.35f, 10); return b;
            case S_CAST: b = buf(0.6f); noise(b, 0, 0.35f, 0.4f, 600, 2400, 0.1f, 5); tone(b, 0.35f, 0.15f, 700, 250, 0.4f, W_SINE, 0.002f, 20); return b;
            case S_BITE: b = buf(0.4f); tone(b, 0, 0.1f, 650, 300, 0.5f, W_SINE, 0.002f, 25); tone(b, 0.14f, 0.1f, 750, 350, 0.5f, W_SINE, 0.002f, 25); return b;
            case S_CATCH: b = buf(0.8f); float[] cj = {659, 784, 988, 1319}; for (int i = 0; i < 4; i++) chime(b, 0.07f * i, cj[i], 0.35f, 8); noise(b, 0, 0.1f, 0.2f, 3000, 1000, 0.002f, 30); return b;
            case S_HORROR: b = buf(1.3f); tone(b, 0, 1.2f, 60, 45, 0.5f, W_SAW, 0.1f, 2f); for (int i = 0; i < 8; i++) tone(b, 0.12f * i, 0.08f, 180, 90, 0.2f, W_SINE, 0.003f, 25); noise(b, 0, 1.1f, 0.2f, 700, 300, 0.2f, 2f); return b;
            case S_SAW: b = buf(0.4f); for (int i = 0; i < 4; i++) noise(b, 0.09f * i, 0.08f, 0.45f, 3200, 1600, 0.005f, 14); tone(b, 0, 0.35f, 210, 190, 0.1f, W_SAW, 0.01f, 5); return b;
            case S_PART: b = buf(1.2f); for (int i = 0; i < 3; i++) { tone(b, 0.22f * i, 0.2f, 800, 760, 0.3f, W_SINE, 0.001f, 18); noise(b, 0.22f * i, 0.04f, 0.3f, 5000, 2500, 0.001f, 60); } chime(b, 0.7f, 1046, 0.4f, 5); chime(b, 0.8f, 1568, 0.3f, 5); return b;
            case S_EMOTE: b = buf(0.12f); tone(b, 0, 0.1f, 700, 1100, 0.4f, W_SINE, 0.003f, 25); return b;
            case S_DAWN: b = buf(2.4f); float[] dn = {392, 494, 587, 784}; for (int i = 0; i < 4; i++) tone(b, 0.2f * i, 2f, dn[i], dn[i], 0.15f, W_SINE, 0.6f, 1.4f); return b;
            case S_DUSK: b = buf(2.2f); tone(b, 0, 2f, 220, 220, 0.3f, W_SINE, 0.01f, 1.6f); tone(b, 0, 2f, 330, 330, 0.18f, W_SINE, 0.01f, 1.9f); tone(b, 0.5f, 1.6f, 262, 262, 0.15f, W_SINE, 0.01f, 1.9f); return b;
            case S_WIN: b = buf(1.6f); float[] wn = {523, 659, 784, 1047, 784, 1047, 1319}; for (int i = 0; i < 7; i++) tone(b, 0.13f * i, 0.5f, wn[i], wn[i], 0.3f, W_TRI, 0.004f, 3.5f); return b;
            case S_LOSE: b = buf(1.8f); float[] ls = {440, 392, 349, 262}; for (int i = 0; i < 4; i++) tone(b, 0.3f * i, 0.8f, ls[i], ls[i] * 0.98f, 0.3f, W_SINE, 0.01f, 2.4f); return b;
            case S_UNLOCK: b = buf(0.8f); noise(b, 0, 0.04f, 0.4f, 4000, 2000, 0.001f, 70); tone(b, 0.05f, 0.1f, 300, 260, 0.5f, W_SQ, 0.002f, 25); chime(b, 0.2f, 1046, 0.4f, 6); chime(b, 0.3f, 1568, 0.4f, 6); return b;
            case S_UPGRADE: b = buf(0.7f); float[] up = {392, 494, 587, 784, 988}; for (int i = 0; i < 5; i++) tone(b, 0.06f * i, 0.25f, up[i], up[i], 0.3f, W_TRI, 0.003f, 9); return b;
            case S_TIP: b = buf(0.3f); tone(b, 0, 0.08f, 880, 880, 0.3f, W_SINE, 0.003f, 20); tone(b, 0.1f, 0.12f, 1175, 1175, 0.3f, W_SINE, 0.003f, 16); return b;
            default: b = buf(0.4f); noise(b, 0, 0.35f, 0.4f, 500, 1600, 0.08f, 6); return b; // S_LAMP
        }
    }

    /** Offline helper used by tests: whether the mixer produced finite output. */
    boolean isReady() { return ready; }
}
