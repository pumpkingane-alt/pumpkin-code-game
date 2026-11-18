package com.pumpkinpanic.coop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans the addons folder, indexes every mod_info.json, remembers which addons the player turned
 * on, and serves addon JSON data. Call scan()/rescan() off the UI thread.
 *
 * New addons start DISABLED: scripts run code, so the player opts in from the Addons menu.
 * Hidden folders (".sync-...") are ignored.
 */
public class AddonDataManager {

    public static final String MANIFEST_FILE_NAME = "mod_info.json";

    private static final String TAG = "AddonDataManager";
    private static final String PREFS = "pumpkin_addons";
    private static final String KEY_ENABLED = "enabled";

    private final File addonsRoot;
    private final SharedPreferences prefs;          // null when built without a Context
    private volatile List<AddonManifest> addons = Collections.emptyList();
    private volatile Map<String, AddonManifest> addonsById = Collections.emptyMap();
    private Set<String> enabledIds = new HashSet<String>();     // guarded by this

    public AddonDataManager(Context context, File addonsRoot) {
        this.addonsRoot = addonsRoot;
        this.prefs = context == null ? null : context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadEnabled();
    }

    public File getAddonsRoot() { return addonsRoot; }

    private synchronized void loadEnabled() {
        enabledIds = new HashSet<String>();
        if (prefs != null) enabledIds.addAll(prefs.getStringSet(KEY_ENABLED, new HashSet<String>()));
    }

    /**
     * (Re)scans the addons folder and re-applies the saved enabled state. Invalid manifests and
     * duplicate ids are skipped. Returns the number of addons indexed.
     */
    public synchronized int scan() {
        loadEnabled();
        List<AddonManifest> found = new ArrayList<AddonManifest>();
        Map<String, AddonManifest> byId = new LinkedHashMap<String, AddonManifest>();

        File[] dirs = addonsRoot.listFiles();
        if (dirs == null) {
            Log.w(TAG, "Addons folder missing or unreadable: " + addonsRoot.getPath());
        } else {
            Arrays.sort(dirs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            for (File dir : dirs) {
                if (dir.getName().startsWith(".")) continue;
                File manifestFile = new File(dir, MANIFEST_FILE_NAME);
                if (!dir.isDirectory() || !manifestFile.isFile()) continue;
                try {
                    AddonManifest manifest = AddonManifest.fromFile(manifestFile);
                    if (byId.containsKey(manifest.getId())) {
                        Log.w(TAG, "Duplicate addon id '" + manifest.getId()
                                + "' in " + dir.getName() + " - skipped");
                        continue;
                    }
                    manifest.setEnabled(enabledIds.contains(manifest.getId()));
                    byId.put(manifest.getId(), manifest);
                    found.add(manifest);
                } catch (IOException e) {
                    Log.w(TAG, "Cannot read " + manifestFile.getPath() + ": " + e.getMessage());
                } catch (JSONException e) {
                    Log.w(TAG, "Invalid " + manifestFile.getPath() + ": " + e.getMessage());
                }
            }
        }

        addons = Collections.unmodifiableList(found);
        addonsById = Collections.unmodifiableMap(byId);
        return found.size();
    }

    /** Same as scan(). */
    public int rescan() { return scan(); }

    /** Read-only snapshot of all indexed addons, ordered by folder name. */
    public List<AddonManifest> getAddons() { return addons; }

    /** Same as getAddons(). */
    public List<AddonManifest> getManifests() { return addons; }

    /** Returns the addon with the given id, or null if not indexed. */
    public AddonManifest getAddon(String addonId) { return addonsById.get(addonId); }

    public boolean isEnabled(String addonId) {
        AddonManifest m = addonsById.get(addonId);
        return m != null && m.isEnabled();
    }

    /** Turns an addon on/off and remembers the choice. Takes effect at the next reload. */
    public synchronized void setEnabled(String addonId, boolean on) {
        AddonManifest m = addonsById.get(addonId);
        if (m != null) m.setEnabled(on);
        if (on) enabledIds.add(addonId); else enabledIds.remove(addonId);
        if (prefs != null) prefs.edit().putStringSet(KEY_ENABLED, new HashSet<String>(enabledIds)).commit();
    }

    /**
     * Reads a JSON file from anywhere inside an addon's folder, e.g.
     * getCustomJSON("my_mod", "monsters/spawn_rates.json").
     * Returns null if the addon/file is missing, the path escapes the addon folder, or the file
     * is not a valid JSON object. The file is read on every call.
     */
    public JSONObject getCustomJSON(String addonId, String relativeFilePath) {
        AddonManifest addon = getAddon(addonId);
        if (addon == null) {
            Log.w(TAG, "Unknown addon id: " + addonId);
            return null;
        }

        File file = addon.resolveFile(relativeFilePath);
        if (file == null || !file.isFile()) {
            Log.w(TAG, "Invalid or missing file '" + relativeFilePath + "' in addon " + addonId);
            return null;
        }

        try {
            return new JSONObject(AddonManifest.readText(file));
        } catch (IOException e) {
            Log.w(TAG, "Cannot read " + file.getPath() + ": " + e.getMessage());
        } catch (JSONException e) {
            Log.w(TAG, "Invalid JSON in " + file.getPath() + ": " + e.getMessage());
        }
        return null;
    }
}
