package com.pumpkinpanic.coop;

import android.content.Context;
import android.util.Log;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs addon Lua scripts (Luaj) in a locked-down environment.
 * Each addon gets its own Globals with only the base, table, string, bit32 and math libraries
 * (no io, os, package, debug, coroutine or luajava, and load/loadfile/dofile are removed).
 *
 * Needs libs/luaj-jse-3.0.x.jar (included in this project).
 *
 * SCRIPT FILES   <addon>/scripts/*.lua, alphabetical; then entryScript from mod_info.json if it
 *                lives elsewhere. All files of one addon share one Lua state.
 *
 * EVENTS         Define a global function named like an event and it is called automatically:
 *                onUpdate(delta)  onPlayerMove(x, y)  onCropHarvest(tileX, tileY, crop)
 *                onCropWater(tileX, tileY)  onMonsterSpawn(kind, x, y)  onNightFall()
 *                onItemUse(item, x, y)  onItemBuy(itemId, slot)  onSceneEnter(sceneId)
 *                onAddonsReady(count)      (see AddonEventBus)
 *                Returning false from a handler cancels a cancelable event.
 *
 * LUA API        spawnMonster(kind, x, y)     changeScene("addon:map")
 *                modifyPlayerStat(stat, n)    createItem(id, name, price)
 *                playSound(key, volume)       addTriggerZone(x, y, w, h, luaSourceOrPath)
 *                log(text)
 *   The first four go through GameBridge (implemented by GameView). They only change the game on
 *   the host / solo player, so every device in a LAN game stays in sync.
 */
public class AddonScriptSandbox {

    /** Implemented by the game; these are the only Java methods scripts can reach. */
    public interface GameBridge {
        void spawnMonster(String entityId, float x, float y);
        void changeScene(String sceneMapId);
        void modifyPlayerStat(String statName, float value);
        void createItem(String itemId, String name, int price);
    }

    private static final String TAG = "AddonScriptSandbox";
    private static final int ERROR_LOG_LIMIT = 5;

    private final AddonEventBus bus;
    private final AddonAssetPipeline assets;
    private final AddonSceneEngine scenes;
    private final Map<String, Globals> addons = new LinkedHashMap<String, Globals>();
    private final Map<String, Integer> errorCounts = new HashMap<String, Integer>();

    public AddonScriptSandbox(Context context, AddonEventBus bus, AddonAssetPipeline assets, AddonSceneEngine scenes) {
        this.bus = bus;
        this.assets = assets;
        this.scenes = scenes;
    }

    // ------------------------------------------------------------------ boot / shutdown

    /** Loads every approved addon's scripts and hooks its event functions onto the bus. */
    public synchronized void boot(List<AddonManifest> approved) {
        shutdown();
        scenes.setLuaBridge(new AddonSceneEngine.LuaBridge() {
            @Override
            public void run(String luaScript, String entityId, AddonSceneEngine.TriggerZone zone) {
                runTrigger(luaScript, entityId);
            }
        });
        if (approved == null) return;
        for (AddonManifest m : approved) loadAddon(m);
    }

    /** Drops every Lua state and unregisters all script listeners from the bus. */
    public synchronized void shutdown() {
        for (String id : addons.keySet()) bus.unregisterOwner(id);
        addons.clear();
        errorCounts.clear();
        scenes.setLuaBridge(null);
    }

    private boolean loadAddon(AddonManifest m) {
        List<File> files = scriptFiles(m);
        if (files.isEmpty()) return false;

        Globals globals = createSandboxGlobals(m);
        try {
            for (File script : files) {
                runScript(globals, "@" + m.getId() + "/" + script.getName(), script);
            }
        } catch (Exception e) {
            Log.e(TAG, "Addon '" + m.getId() + "' failed to load: " + e.getMessage());
            return false;
        }
        addons.put(m.getId(), globals);
        hookEvents(m.getId(), globals);
        return true;
    }

    private static List<File> scriptFiles(AddonManifest m) {
        List<File> out = new ArrayList<File>();
        File[] scripts = new File(m.getDirectory(), "scripts").listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".lua");
            }
        });
        if (scripts != null) {
            Arrays.sort(scripts);
            out.addAll(Arrays.asList(scripts));
        }
        File entry = m.resolveFile(m.getEntryScript());
        if (entry != null && entry.isFile() && entry.getName().endsWith(".lua") && !out.contains(entry)) out.add(entry);
        return out;
    }

    private void hookEvents(final String addonId, final Globals globals) {
        for (final String event : AddonEventBus.ALL_EVENTS) {
            if (!globals.get(event).isfunction()) continue;
            bus.registerListener(event, addonId, new AddonEventBus.AddonEventListener() {
                @Override
                public void onEvent(String ev, EventData payload) {
                    synchronized (AddonScriptSandbox.this) {
                        if (addons.get(addonId) != globals) return;      // addon was unloaded meanwhile
                        call(addonId, globals, ev, payload);
                    }
                }
            });
        }
    }

    private void call(String addonId, Globals globals, String event, EventData d) {
        LuaValue fn = globals.get(event);
        if (!fn.isfunction()) return;
        try {
            Varargs r = fn.invoke(LuaValue.varargsOf(argsFor(event, d)));
            if (r.narg() > 0 && r.arg1().isboolean() && !r.arg1().toboolean()) d.setCanceled(true);
        } catch (RuntimeException e) {
            String key = addonId + "/" + event;
            Integer n = errorCounts.get(key);
            int count = n == null ? 1 : n + 1;
            errorCounts.put(key, count);
            if (count <= ERROR_LOG_LIMIT) Log.e(TAG, key + "() failed: " + e.getMessage());
        }
    }

    private static LuaValue[] argsFor(String event, EventData d) {
        if (AddonEventBus.ON_UPDATE.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getFloat("delta", 0f)) };
        } else if (AddonEventBus.ON_PLAYER_MOVE.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getFloat("x", 0f)), LuaValue.valueOf(d.getFloat("y", 0f)) };
        } else if (AddonEventBus.ON_CROP_HARVEST.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getInt("tileX", 0)), LuaValue.valueOf(d.getInt("tileY", 0)),
                    LuaValue.valueOf(d.getString("crop", "")) };
        } else if (AddonEventBus.ON_CROP_WATER.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getInt("tileX", 0)), LuaValue.valueOf(d.getInt("tileY", 0)) };
        } else if (AddonEventBus.ON_MONSTER_SPAWN.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getString("kind", "")),
                    LuaValue.valueOf(d.getFloat("x", 0f)), LuaValue.valueOf(d.getFloat("y", 0f)) };
        } else if (AddonEventBus.ON_ITEM_USE.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getString("item", "")),
                    LuaValue.valueOf(d.getFloat("x", 0f)), LuaValue.valueOf(d.getFloat("y", 0f)) };
        } else if (AddonEventBus.ON_ITEM_BUY.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getString("itemId", "")), LuaValue.valueOf(d.getInt("slot", 0)) };
        } else if (AddonEventBus.ON_SCENE_ENTER.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getString("scene", "")) };
        } else if (AddonEventBus.ON_ADDONS_READY.equals(event)) {
            return new LuaValue[] { LuaValue.valueOf(d.getInt("count", 0)) };
        }
        return new LuaValue[0];
    }

    // ------------------------------------------------------------------ trigger zones

    /** Runs a scene trigger's onEnter script in the Lua state of the addon that owns the current scene. */
    private synchronized void runTrigger(String script, String entityId) {
        String sceneId = scenes.getScene().id;
        int colon = sceneId.indexOf(':');
        String addonId = colon > 0 ? sceneId.substring(0, colon) : "";
        Globals g = addons.get(addonId);
        if (g == null) g = addons.isEmpty() ? null : addons.values().iterator().next();
        if (g == null) return;
        try {
            LuaValue chunk;
            String trimmed = script.trim();
            File f = null;
            if (trimmed.endsWith(".lua") && trimmed.indexOf('\n') < 0 && trimmed.indexOf('(') < 0) {
                AddonManifest owner = ownerOf(addonId);
                if (owner != null) f = owner.resolveFile(trimmed);
            }
            if (f != null && f.isFile()) {
                chunk = g.load(new FileInputStream(f), "@" + addonId + "/" + f.getName(), "t", g);
            } else {
                chunk = g.load(trimmed, "@" + addonId + "/trigger");
            }
            g.set("triggerEntity", LuaValue.valueOf(entityId == null ? "" : entityId));
            chunk.call();
        } catch (IOException e) {
            Log.w(TAG, "Trigger script unreadable: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.w(TAG, "Trigger script failed: " + e.getMessage());
        }
    }

    private AddonManifest ownerOf(String addonId) {
        AddonEngineController c = AddonEngineController.get();
        for (AddonManifest m : c.getActiveAddons()) if (m.getId().equals(addonId)) return m;
        return null;
    }

    // ------------------------------------------------------------------ Lua environment

    private static void runScript(Globals globals, String chunkName, File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            // "t" = source text only; precompiled bytecode is rejected.
            globals.load(in, chunkName, "t", globals).call();
        } finally {
            in.close();
        }
    }

    private Globals createSandboxGlobals(final AddonManifest owner) {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new Bit32Lib());
        g.load(new JseMathLib());
        LoadState.install(g);
        LuaC.install(g);

        // Block file access and runtime code loading.
        g.set("dofile", LuaValue.NIL);
        g.set("loadfile", LuaValue.NIL);
        g.set("load", LuaValue.NIL);

        bindGameApi(g, owner.getId());
        return g;
    }

    private static GameBridge game() {
        return AddonEngineController.getGameBridge();
    }

    private void bindGameApi(Globals g, final String addonId) {
        g.set("spawnMonster", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue entityId, LuaValue x, LuaValue y) {
                GameBridge b = game();
                if (b != null) b.spawnMonster(entityId.checkjstring(), (float) x.checkdouble(), (float) y.checkdouble());
                return LuaValue.NIL;
            }
        });
        g.set("changeScene", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue sceneMapId) {
                GameBridge b = game();
                if (b != null) b.changeScene(sceneMapId.checkjstring());
                return LuaValue.NIL;
            }
        });
        g.set("modifyPlayerStat", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue statName, LuaValue value) {
                GameBridge b = game();
                if (b != null) b.modifyPlayerStat(statName.checkjstring(), (float) value.checkdouble());
                return LuaValue.NIL;
            }
        });
        g.set("createItem", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue itemId, LuaValue name, LuaValue price) {
                GameBridge b = game();
                if (b != null) b.createItem(itemId.checkjstring(), name.checkjstring(), price.checkint());
                return LuaValue.NIL;
            }
        });
        g.set("playSound", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue key, LuaValue volume) {
                float v = volume.isnil() ? 1f : (float) volume.checkdouble();
                assets.playSound(key.checkjstring(), Math.max(0f, Math.min(1f, v)));
                return LuaValue.NIL;
            }
        });
        g.set("log", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue text) {
                Log.i(TAG, addonId + ": " + text.tojstring());
                return LuaValue.NIL;
            }
        });
        g.set("addTriggerZone", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                try {
                    scenes.addTriggerZone((float) a.checkdouble(1), (float) a.checkdouble(2),
                            (float) a.checkdouble(3), (float) a.checkdouble(4), a.checkjstring(5));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, addonId + ": addTriggerZone: " + e.getMessage());
                }
                return LuaValue.NIL;
            }
        });
    }
}
