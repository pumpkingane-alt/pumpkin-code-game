package com.pumpkinpanic.coop;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprites, sprite sheets, icons and sounds from the ACTIVE addons.
 *
 * Where files live (inside an addon folder):
 *   sprites  <key>.png          in the folder root or in textures/
 *   sounds   <key>.ogg|wav|mp3  in the folder root or in sounds/
 *   icon     icon.png           (root) shown in the Addons menu
 *
 * Several active addons providing the same key: the one loaded first wins (alphabetical by folder).
 * Keys the game looks for are listed in README_ADDONS.txt (player_0, mob_skinwalker, crop_pumpkin,
 * prop_well, ground, tileset, sfx_harvest ...). A key no addon provides returns null, and the game
 * then draws its own built-in art / plays its own synthesised sound.
 *
 * The whole index is built once in loadAssets(), so lookups during a frame never touch the disk
 * for a missing key. Bitmaps are NOT recycled on reload (the render thread may still be drawing
 * them); they are simply dropped and garbage collected.
 */
public final class AddonAssetPipeline {

    private static final String TAG = "AddonAssetPipeline";
    private static final String[] SOUND_EXT = { ".ogg", ".wav", ".mp3" };
    private static final int ICON_MAX_DIM = 256;
    private static final int PRELOAD_LIMIT = 200;

    private final Context appContext;
    private final Resources res;
    private final String pkg;

    private final HashMap<String, File> spriteIndex = new HashMap<String, File>();
    private final HashMap<String, File> soundIndex = new HashMap<String, File>();
    private final HashMap<String, Bitmap> textures = new HashMap<String, Bitmap>();
    private final HashMap<String, Bitmap[]> frames = new HashMap<String, Bitmap[]>();
    private final HashSet<String> failed = new HashSet<String>();
    private final HashMap<String, Integer> soundIds = new HashMap<String, Integer>();
    private final HashSet<Integer> loadedSounds = new HashSet<Integer>();
    private final HashMap<Integer, Float> pendingPlay = new HashMap<Integer, Float>();
    private SoundPool pool;

    public AddonAssetPipeline(Context context) {
        appContext = context.getApplicationContext();
        res = appContext.getResources();
        pkg = appContext.getPackageName();
    }

    // ------------------------------------------------------------------ loading

    /** Rebuilds the index from the given addons (load order = list order) and preloads their files. */
    public synchronized void loadAssets(List<AddonManifest> active) {
        dropEverything();
        if (active == null) return;
        for (AddonManifest m : active) {
            File dir = m.getDirectory();
            indexDir(dir, spriteIndex, soundIndex);
        }
        int n = 0;
        for (String key : spriteIndex.keySet()) {
            if (n++ >= PRELOAD_LIMIT) break;
            getSprite(key);
        }
        n = 0;
        for (String key : soundIndex.keySet()) {
            if (n++ >= PRELOAD_LIMIT) break;
            preloadSound(key);
        }
        Log.i(TAG, "Indexed " + spriteIndex.size() + " sprite(s) and " + soundIndex.size() + " sound(s)");
    }

    /** Frees sounds and drops every cached bitmap. */
    public synchronized void release() {
        dropEverything();
    }

    private void dropEverything() {
        if (pool != null) { pool.release(); pool = null; }
        soundIds.clear();
        loadedSounds.clear();
        pendingPlay.clear();
        spriteIndex.clear();
        soundIndex.clear();
        textures.clear();
        frames.clear();
        failed.clear();
    }

    /**
     * Adds "texture:key" / "sound:key" for every asset file in one addon folder (icon.png
     * excluded). AddonConflictResolver uses this to find addons that override the same thing.
     */
    static Set<String> scanOverrides(File dir) {
        HashMap<String, File> sp = new HashMap<String, File>(), so = new HashMap<String, File>();
        indexDir(dir, sp, so);
        Set<String> out = new LinkedHashSet<String>();
        for (String k : sp.keySet()) out.add("texture:" + k);
        for (String k : so.keySet()) out.add("sound:" + k);
        return out;
    }

    private static void indexDir(File dir, HashMap<String, File> sprites, HashMap<String, File> sounds) {
        String[] folders = { "", "textures", "sounds" };
        for (String sub : folders) {
            File d = sub.length() == 0 ? dir : new File(dir, sub);
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                if (!f.isFile()) continue;
                String name = f.getName();
                String lower = name.toLowerCase();
                int dot = name.lastIndexOf('.');
                if (dot <= 0) continue;
                String key = name.substring(0, dot);
                if (!safe(key)) continue;
                if (lower.endsWith(".png")) {
                    if (key.equals("icon") && sub.length() == 0) continue;
                    if (!sprites.containsKey(key)) sprites.put(key, f);
                } else {
                    for (String ext : SOUND_EXT) {
                        if (lower.endsWith(ext) && !sounds.containsKey(key)) sounds.put(key, f);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ sprites

    /** Addon "<assetKey>.png" if an active addon has it, otherwise the drawable named assetKey. Null if neither. */
    public synchronized Bitmap getSprite(String assetKey) {
        if (!safe(assetKey)) return null;
        Bitmap bmp = textures.get(assetKey);
        if (bmp != null) return bmp;
        if (failed.contains(assetKey)) return null;

        File f = spriteIndex.get(assetKey);
        if (f != null) bmp = decodeFile(f, 0);
        if (bmp == null) bmp = decodeDrawable(assetKey);

        if (bmp == null) failed.add(assetKey);
        else textures.put(assetKey, bmp);
        return bmp;
    }

    /**
     * Animation frames for a key. A PNG at least twice as wide as tall whose width is a multiple
     * of its height is read as a horizontal strip of square frames; anything else is one frame.
     * Null when no sprite exists.
     */
    public synchronized Bitmap[] getFrames(String assetKey) {
        Bitmap[] cached = frames.get(assetKey);
        if (cached != null) return cached;
        Bitmap b = getSprite(assetKey);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        Bitmap[] out;
        if (h > 0 && w >= 2 * h && w % h == 0) {
            out = new Bitmap[w / h];
            for (int i = 0; i < out.length; i++) out[i] = Bitmap.createBitmap(b, i * h, 0, h, h);
        } else {
            out = new Bitmap[] { b };
        }
        frames.put(assetKey, out);
        return out;
    }

    /** Slices a sheet into frames, row-major (left to right, top to bottom). Empty array if missing. */
    public synchronized Bitmap[] getSpriteSheet(String assetKey, int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) return new Bitmap[0];
        String cacheKey = assetKey + "#" + frameWidth + "x" + frameHeight;
        Bitmap[] cached = frames.get(cacheKey);
        if (cached != null) return cached;

        Bitmap sheet = getSprite(assetKey);
        if (sheet == null) return new Bitmap[0];

        int cols = sheet.getWidth() / frameWidth;
        int rows = sheet.getHeight() / frameHeight;
        Bitmap[] out = new Bitmap[Math.max(0, cols * rows)];
        int i = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out[i++] = Bitmap.createBitmap(sheet, c * frameWidth, r * frameHeight, frameWidth, frameHeight);
            }
        }
        frames.put(cacheKey, out);
        return out;
    }

    /** Loads "<folder>/icon.png" (downscaled to fit 256px) for the Addons menu. Null if missing. */
    public synchronized Bitmap getAddonIcon(File addonFolder) {
        if (addonFolder == null) return null;
        File f = new File(addonFolder, "icon.png");
        return f.isFile() ? decodeFile(f, ICON_MAX_DIM) : null;
    }

    // ------------------------------------------------------------------ sounds

    public synchronized boolean hasSound(String assetKey) {
        return assetKey != null && soundIndex.containsKey(assetKey);
    }

    /** Loads a sound ahead of time so the first playSound() isn't delayed. */
    public synchronized void preloadSound(String assetKey) {
        if (!safe(assetKey) || soundIds.containsKey(assetKey)) return;
        File f = soundIndex.get(assetKey);
        if (f == null) return;
        if (pool == null) pool = newPool();
        int id = pool.load(f.getAbsolutePath(), 1);
        if (id != 0) soundIds.put(assetKey, id);
    }

    /**
     * Plays an addon sound. Returns true when an addon provides this key (the caller should then
     * skip its own sound), false when nothing was found.
     */
    public synchronized boolean playSound(String assetKey, float volume) {
        if (!safe(assetKey) || !soundIndex.containsKey(assetKey)) return false;
        if (!soundIds.containsKey(assetKey)) preloadSound(assetKey);
        Integer id = soundIds.get(assetKey);
        if (id == null || pool == null) return false;
        if (loadedSounds.contains(id)) pool.play(id, volume, volume, 1, 0, 1f);
        else pendingPlay.put(id, volume);
        return true;
    }

    @SuppressWarnings("deprecation")
    private SoundPool newPool() {
        SoundPool sp = new SoundPool(8, AudioManager.STREAM_MUSIC, 0);
        sp.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                synchronized (AddonAssetPipeline.this) {
                    if (status != 0 || soundPool != pool) return;
                    loadedSounds.add(sampleId);
                    Float v = pendingPlay.remove(sampleId);
                    if (v != null) soundPool.play(sampleId, v, v, 1, 0, 1f);
                }
            }
        });
        return sp;
    }

    // ------------------------------------------------------------------ internals

    /** Rejects path separators, ':' and leading '.' so keys can't escape the addon directory. */
    private static boolean safe(String s) {
        return s != null && s.length() > 0 && s.charAt(0) != '.'
                && s.indexOf('/') < 0 && s.indexOf('\\') < 0 && s.indexOf(':') < 0;
    }

    /** maxDim > 0 downsamples (power-of-two) so the longest side fits; 0 keeps full size. */
    private Bitmap decodeFile(File f, int maxDim) {
        try {
            String path = f.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            if (maxDim > 0) {
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                int sample = 1;
                while (o.outWidth / sample > maxDim || o.outHeight / sample > maxDim) sample *= 2;
                o.inSampleSize = sample;
                o.inJustDecodeBounds = false;
            }
            return BitmapFactory.decodeFile(path, o);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Out of memory decoding " + f.getName());
            return null;
        }
    }

    private Bitmap decodeDrawable(String name) {
        int id = res.getIdentifier(name, "drawable", pkg);
        if (id == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false; // keep native pixel size, no density scaling
            return BitmapFactory.decodeResource(res, id, o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
