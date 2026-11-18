package com.pumpkinpanic.coop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata parsed from an addon's mod_info.json, plus its runtime "enabled" switch.
 *
 * mod_info.json fields:  id (required), title, author, version, description,
 *                        entryScript, mainMapOverride, incompatible_with (array of addon ids)
 */
public final class AddonManifest {

    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private final String id;
    private final String title;
    private final String author;
    private final String version;
    private final String description;
    private final String entryScript;
    private final String mainMapOverride;
    private final List<String> incompatibleWith;
    private final File directory;

    private volatile boolean enabled;
    private String hashSig, hashValue;                 // guarded by this

    private AddonManifest(String id, String title, String author, String version,
                          String description, String entryScript, String mainMapOverride,
                          List<String> incompatibleWith, File directory) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.version = version;
        this.description = description;
        this.entryScript = entryScript;
        this.mainMapOverride = mainMapOverride;
        this.incompatibleWith = Collections.unmodifiableList(incompatibleWith);
        this.directory = directory;
    }

    /** Parses a mod_info.json file. "id" is required; every other field is optional. */
    public static AddonManifest fromFile(File modInfoFile) throws IOException, JSONException {
        JSONObject json = new JSONObject(readText(modInfoFile));

        String id = str(json, "id");
        if (id.length() == 0) {
            throw new JSONException("Missing required field: id");
        }
        String title = str(json, "title");

        List<String> incompatible = new ArrayList<String>();
        JSONArray arr = json.optJSONArray("incompatible_with");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            if (arr.isNull(i)) continue;
            String other = arr.optString(i, "").trim();
            if (other.length() > 0) incompatible.add(other);
        }

        return new AddonManifest(
                id,
                title.length() == 0 ? id : title,
                str(json, "author"),
                str(json, "version"),
                str(json, "description"),
                str(json, "entryScript"),
                str(json, "mainMapOverride"),
                incompatible,
                modInfoFile.getAbsoluteFile().getParentFile());
    }

    /**
     * Resolves a path relative to this addon's folder.
     * Returns null if the path is empty or escapes the addon folder (e.g. "../other").
     */
    public File resolveFile(String relativePath) {
        if (relativePath == null || relativePath.trim().length() == 0) {
            return null;
        }
        try {
            File root = directory.getCanonicalFile();
            File target = new File(root, relativePath.trim().replace('\\', '/')).getCanonicalFile();
            return target.getPath().startsWith(root.getPath() + File.separator) ? target : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * SHA-256 of the addon folder contents (see AddonPackager). Used by LAN sync to decide whether
     * the host's copy and the local copy are identical. Cached until a file changes. Null on error.
     * May read every file the first time: call from a background thread.
     */
    public synchronized String contentHash() {
        String sig = AddonPackager.signature(directory);
        if (hashValue != null && sig.equals(hashSig)) return hashValue;
        try {
            hashValue = AddonPackager.hashFolder(directory);
            hashSig = sig;
        } catch (IOException e) {
            hashValue = null;
            hashSig = null;
        }
        return hashValue;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    /** Same as getTitle(); falls back to the id when the manifest has no title. */
    public String getName() { return title; }
    public String getAuthor() { return author; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public String getEntryScript() { return entryScript; }
    public String getMainMapOverride() { return mainMapOverride; }
    public List<String> getIncompatibleWith() { return incompatibleWith; }
    public File getDirectory() { return directory; }
    /** Same as getDirectory(). */
    public File getRootDir() { return directory; }

    public boolean isEnabled() { return enabled; }
    /** In-memory only. AddonDataManager.setEnabled(id, on) also persists the choice. */
    public void setEnabled(boolean on) { enabled = on; }

    @Override
    public String toString() {
        return "AddonManifest{id=" + id + ", title=" + title + ", version=" + version + "}";
    }

    private static String str(JSONObject json, String key) {
        return json.isNull(key) ? "" : json.optString(key, "").trim();
    }

    /** Reads a UTF-8 text file (BOM stripped, size-capped). */
    static String readText(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_FILE_BYTES) {
                    throw new IOException("File too large: " + file.getPath());
                }
                out.write(buf, 0, n);
            }
            String text = out.toString("UTF-8");
            return (text.length() > 0 && text.charAt(0) == '\uFEFF') ? text.substring(1) : text;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
