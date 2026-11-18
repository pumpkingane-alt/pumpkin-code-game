package com.pumpkinpanic.coop;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Loads addon scenes from {@code <addonsRoot>/<addonId>/maps/<mapName>.json|.tmx}
 * (Tiled-style JSON/TMX; tile data as CSV, flat array, or nested matrix). Scene id: "addonId:mapName".
 * <p>
 * Tile layers become background layers, except a tile layer named "collision", which becomes solid rects.
 * Object layers are read by name: "collision" (rect/polygon/polyline), "spawns" (named points),
 * "camera" (first rect = camera bounds), "triggers" (rects with property "onEnter" = Lua source or path).
 * <p>
 * Scenes are built off to the side and swapped in atomically; a failed load keeps the current scene.
 * Trigger zones are scene-scoped: loadScene() discards zones that were added at runtime.
 */
public final class AddonSceneEngine {

    private static final Logger LOG = Logger.getLogger("AddonSceneEngine");
    private static final Pattern SCENE_ID = Pattern.compile("([A-Za-z0-9_-]{1,64}):([A-Za-z0-9_-]{1,64})");
    private static final int MAX_FILE_BYTES = 8 << 20;
    private static final int MAX_TILES = 1 << 20;      // per scene, all layers combined
    private static final long GID_MASK = 0x0FFFFFFFL;  // strips Tiled flip flags

    /** Runs trigger scripts (e.g. via LuaJ). Scripts come from third-party addons: sandbox them. */
    public interface LuaBridge {
        void run(String luaScript, String entityId, TriggerZone zone);
    }

    // ---- Scene data ------------------------------------------------------------------------

    public static final class TileLayer {
        public final String name;
        public final int[][] tiles; // [row][col], 0 = empty
        TileLayer(String name, int[][] tiles) { this.name = name; this.tiles = tiles; }
    }

    public static final class CollisionShape {
        public final float[] vertices; // x0,y0,x1,y1,... in world pixels
        public final boolean closed;
        CollisionShape(float[] vertices, boolean closed) { this.vertices = vertices; this.closed = closed; }
    }

    public static final class SpawnNode {
        public final String name;
        public final float x, y;
        SpawnNode(String name, float x, float y) { this.name = name; this.x = x; this.y = y; }
    }

    public static final class Bounds {
        public final float x, y, width, height;
        Bounds(float x, float y, float width, float height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }

    public static final class TriggerZone {
        public final float x, y, width, height;
        public final String onEnterLuaScript;
        final Set<String> inside = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        TriggerZone(float x, float y, float width, float height, String script) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.onEnterLuaScript = script;
        }

        boolean overlaps(float ex, float ey, float ew, float eh) {
            return ex < x + width && ex + ew > x && ey < y + height && ey + eh > y;
        }
    }

    public static final class Scene {
        public final String id;
        public final int tileWidth, tileHeight, cols, rows;
        public final List<TileLayer> layers;
        public final List<CollisionShape> collision;
        public final Map<String, SpawnNode> spawns;
        public final Bounds camera;
        final List<TriggerZone> triggers;

        private Scene(String id, Builder b) {
            this.id = id;
            tileWidth = b.tw; tileHeight = b.th; cols = b.cols; rows = b.rows;
            layers = Collections.unmodifiableList(b.layers);
            collision = Collections.unmodifiableList(b.collision);
            spawns = Collections.unmodifiableMap(b.spawns);
            camera = b.camera != null ? b.camera : new Bounds(0, 0, b.cols * b.tw, b.rows * b.th);
            triggers = new CopyOnWriteArrayList<>(b.triggers);
        }
    }

    // ---- Engine ----------------------------------------------------------------------------

    private final File addonsRoot;
    private final AddonEventBus bus;
    private final Map<String, File> addonDirs = new ConcurrentHashMap<String, File>();
    private volatile Scene scene = new Builder(16, 16, 0, 0).build("");
    private volatile LuaBridge luaBridge;

    /** @param addonsRoot folder holding one directory per addon (used when an addon was not registered via loadScenes) */
    public AddonSceneEngine(File addonsRoot) { this.addonsRoot = addonsRoot; this.bus = null; }

    /** Engine used by AddonEngineController: addon folders come from loadScenes(). */
    public AddonSceneEngine(Context context, AddonEventBus bus) { this.addonsRoot = null; this.bus = bus; }

    public void setLuaBridge(LuaBridge bridge) { this.luaBridge = bridge; }

    /** Current scene snapshot (immutable apart from trigger zones). */
    public Scene getScene() { return scene; }

    /** True when a scene is loaded (its id is "addonId:mapName"). */
    public boolean isActive() { return scene.id.length() > 0; }

    /**
     * Boot step 5: remembers where each approved addon lives and loads the scene named by the
     * first addon whose mod_info.json has "mainMapOverride" (a map name, or "addonId:mapName").
     */
    public synchronized void loadScenes(List<AddonManifest> approved) {
        addonDirs.clear();
        scene = new Builder(16, 16, 0, 0).build("");
        if (approved == null) return;
        for (AddonManifest m : approved) addonDirs.put(m.getId(), m.getDirectory());
        for (AddonManifest m : approved) {
            String main = m.getMainMapOverride();
            if (main.length() == 0) continue;
            loadScene(main.indexOf(':') > 0 ? main : m.getId() + ":" + main);
            if (isActive()) break;
        }
    }

    /** Drops the current scene and forgets the addon folders. */
    public synchronized void release() {
        addonDirs.clear();
        scene = new Builder(16, 16, 0, 0).build("");
    }

    /** Per-frame hook (scenes are data-driven for now; reserved for animated layers). */
    public void update(float delta) { }

    /** Convenience: runs trigger zones for a single entity called "player". */
    public void onPlayerMoved(float x, float y) { updateTriggers("player", x, y, 28f, 28f); }

    /** True when the circle (cx, cy, r) overlaps a collision shape of the current scene. */
    public boolean collides(float cx, float cy, float r) {
        Scene s = scene;
        for (CollisionShape c : s.collision) {
            float[] v = c.vertices;
            int n = v.length / 2;
            if (n < 2) continue;
            if (c.closed && n >= 3 && inside(v, n, cx, cy)) return true;
            int edges = c.closed ? n : n - 1;
            for (int i = 0; i < edges; i++) {
                int j = (i + 1) % n;
                if (segDist(cx, cy, v[2 * i], v[2 * i + 1], v[2 * j], v[2 * j + 1]) <= r) return true;
            }
        }
        return false;
    }

    private static boolean inside(float[] v, int n, float px, float py) {
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = v[2 * i], yi = v[2 * i + 1], xj = v[2 * j], yj = v[2 * j + 1];
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        float cx = ax + t * dx - px, cy = ay + t * dy - py;
        return (float) Math.sqrt(cx * cx + cy * cy);
    }

    /** Swaps in "addonId:mapName": tile layers, collision, spawn nodes, camera bounds and map-defined triggers. */
    public synchronized void loadScene(String sceneId) {
        try {
            Matcher m = SCENE_ID.matcher(sceneId == null ? "" : sceneId);
            if (!m.matches()) throw new IllegalArgumentException("Scene id must look like addonId:mapName");
            File addonDir = addonDirs.get(m.group(1));
            if (addonDir == null && addonsRoot != null) addonDir = new File(addonsRoot, m.group(1));
            if (addonDir == null) throw new FileNotFoundException("Addon '" + m.group(1) + "' is not active");
            File dir = new File(addonDir, "maps");
            File file = null;
            for (String ext : new String[]{".json", ".tmx"}) {
                File c = new File(dir, m.group(2) + ext);
                if (c.isFile()) { file = c; break; }
            }
            if (file == null) throw new FileNotFoundException(new File(dir, m.group(2)) + ".json|.tmx");
            if (!file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator))
                throw new SecurityException("Map resolves outside the addon's maps folder");
            byte[] data = readCapped(file);
            Builder b = file.getName().endsWith(".tmx") ? parseTmx(data) : parseJson(new String(data, "UTF-8"));
            scene = b.build(sceneId);
            if (bus != null) {
                EventData d = new EventData();
                d.put("scene", sceneId);
                bus.post(AddonEventBus.ON_SCENE_ENTER, d);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "loadScene('" + sceneId + "') failed; keeping current scene", e);
        }
    }

    /** Adds a zone to the current scene; onEnterLuaScript is handed to the LuaBridge on entry. */
    public void addTriggerZone(float x, float y, float width, float height, String onEnterLuaScript) {
        if (!(width > 0) || !(height > 0)) throw new IllegalArgumentException("Trigger zone needs a positive size");
        scene.triggers.add(new TriggerZone(x, y, width, height, onEnterLuaScript));
    }

    /** Call per frame per entity (AABB). Fires onEnter once on the outside-to-inside transition. */
    public void updateTriggers(String entityId, float x, float y, float w, float h) {
        if (entityId == null) entityId = "";
        Scene s = scene;
        for (TriggerZone z : s.triggers) {
            if (!z.overlaps(x, y, w, h)) { z.inside.remove(entityId); continue; }
            if (z.inside.add(entityId)) {
                fire(z, entityId);
                if (scene != s) return; // a script swapped the scene mid-iteration
            }
        }
    }

    /** Call when an entity is destroyed so zones stop tracking it. */
    public void forgetEntity(String entityId) {
        if (entityId == null) return;
        for (TriggerZone z : scene.triggers) z.inside.remove(entityId);
    }

    private void fire(TriggerZone z, String entityId) {
        LuaBridge bridge = luaBridge;
        String script = z.onEnterLuaScript;
        if (bridge == null || script == null || script.isEmpty()) return;
        try {
            bridge.run(script, entityId, z);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Trigger script failed", e);
        }
    }

    // ---- Parsing ---------------------------------------------------------------------------

    private static Builder parseJson(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        Builder b = new Builder(root.optInt("tilewidth", 16), root.optInt("tileheight", 16),
                root.optInt("width", 0), root.optInt("height", 0));
        JSONArray layers = root.optJSONArray("layers");
        for (int i = 0; layers != null && i < layers.length(); i++) {
            JSONObject l = layers.getJSONObject(i);
            String name = l.optString("name", ""), type = l.optString("type", "");
            if (type.equals("tilelayer")) {
                JSONArray d = l.optJSONArray("data");
                if (d == null) throw new IOException("Layer '" + name + "' needs array data (base64 is unsupported)");
                boolean matrix = d.length() > 0 && d.get(0) instanceof JSONArray;
                int cols = matrix ? d.getJSONArray(0).length() : l.optInt("width", b.cols);
                int rows = matrix ? d.length() : l.optInt("height", b.rows);
                int[][] m = b.alloc(cols, rows);
                for (int t = 0; t < d.length(); t++) {
                    if (matrix) {
                        JSONArray row = d.getJSONArray(t);
                        for (int c = 0; c < cols && c < row.length(); c++) m[t][c] = gid(row.getLong(c));
                    } else if (t < cols * rows) {
                        m[t / cols][t % cols] = gid(d.getLong(t));
                    }
                }
                b.tileLayer(name, m);
            } else if (type.equals("objectgroup")) {
                JSONArray objs = l.optJSONArray("objects");
                for (int j = 0; objs != null && j < objs.length(); j++) {
                    JSONObject o = objs.getJSONObject(j);
                    float x = (float) o.optDouble("x", 0), y = (float) o.optDouble("y", 0);
                    boolean closed = o.has("polygon");
                    JSONArray poly = closed ? o.getJSONArray("polygon") : o.optJSONArray("polyline");
                    float[] pts = null;
                    if (poly != null) {
                        pts = new float[poly.length() * 2];
                        for (int k = 0; k < poly.length(); k++) {
                            JSONObject p = poly.getJSONObject(k);
                            pts[2 * k] = x + (float) p.optDouble("x", 0);
                            pts[2 * k + 1] = y + (float) p.optDouble("y", 0);
                        }
                    }
                    b.object(name, o.optString("name", ""), x, y, (float) o.optDouble("width", 0),
                            (float) o.optDouble("height", 0), pts, closed, jsonProps(o));
                }
            }
        }
        return b;
    }

    private static Builder parseTmx(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // block XXE where supported
        } catch (Exception ignored) { }
        Element map = f.newDocumentBuilder().parse(new ByteArrayInputStream(data)).getDocumentElement();
        Builder b = new Builder(intAttr(map, "tilewidth", 16), intAttr(map, "tileheight", 16),
                intAttr(map, "width", 0), intAttr(map, "height", 0));
        for (Node n = map.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String tag = e.getTagName(), name = e.getAttribute("name");
            if (tag.equals("layer")) {
                Element csv = child(e, "data");
                if (csv == null || !csv.getAttribute("encoding").equals("csv"))
                    throw new IOException("Layer '" + name + "' must use <data encoding=\"csv\">");
                int[][] m = b.alloc(intAttr(e, "width", b.cols), intAttr(e, "height", b.rows));
                int cols = m[0].length, total = cols * m.length;
                String[] t = csv.getTextContent().trim().split("\\s*,\\s*");
                for (int k = 0; k < t.length && k < total; k++) m[k / cols][k % cols] = gid(Long.parseLong(t[k]));
                b.tileLayer(name, m);
            } else if (tag.equals("objectgroup")) {
                NodeList os = e.getElementsByTagName("object");
                for (int j = 0; j < os.getLength(); j++) {
                    Element o = (Element) os.item(j);
                    float x = num(o.getAttribute("x")), y = num(o.getAttribute("y"));
                    Element poly = child(o, "polygon");
                    Element shape = poly != null ? poly : child(o, "polyline");
                    b.object(name, o.getAttribute("name"), x, y, num(o.getAttribute("width")),
                            num(o.getAttribute("height")),
                            shape == null ? null : points(shape.getAttribute("points"), x, y),
                            poly != null, tmxProps(o));
                }
            }
        }
        return b;
    }

    private static Map<String, String> jsonProps(JSONObject o) {
        Map<String, String> m = new HashMap<>();
        JSONArray a = o.optJSONArray("properties");        // Tiled >= 1.2: [{name, type, value}]
        JSONObject legacy = o.optJSONObject("properties"); // hand-written: {name: value}
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null) m.put(p.optString("name", ""), p.optString("value", ""));
        }
        if (legacy != null) {
            for (Iterator<String> it = legacy.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, legacy.optString(k, ""));
            }
        }
        return m;
    }

    private static Map<String, String> tmxProps(Element o) {
        Map<String, String> m = new HashMap<>();
        NodeList ps = o.getElementsByTagName("property");
        for (int i = 0; i < ps.getLength(); i++) {
            Element p = (Element) ps.item(i);
            m.put(p.getAttribute("name"), p.hasAttribute("value") ? p.getAttribute("value") : p.getTextContent());
        }
        return m;
    }

    private static float[] points(String s, float ox, float oy) {
        String[] p = s.trim().split("[\\s,]+");
        float[] out = new float[p.length / 2 * 2];
        for (int i = 0; i + 1 < p.length; i += 2) {
            out[i] = ox + num(p[i]);
            out[i + 1] = oy + num(p[i + 1]);
        }
        return out;
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    private static int intAttr(Element e, String attr, int def) {
        return e.hasAttribute(attr) ? (int) num(e.getAttribute(attr)) : def;
    }

    private static float num(String s) {
        try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return 0f; }
    }

    private static int gid(long raw) { return (int) (raw & GID_MASK); }

    private static byte[] readCapped(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE_BYTES) throw new IOException("Map file too large: " + f.getName());
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Mutable scratch space shared by both parsers; turned into an immutable Scene by build(). */
    private static final class Builder {
        final int tw, th;
        int cols, rows;
        long budget = MAX_TILES;
        Bounds camera;
        final List<TileLayer> layers = new ArrayList<>();
        final List<CollisionShape> collision = new ArrayList<>();
        final Map<String, SpawnNode> spawns = new LinkedHashMap<>();
        final List<TriggerZone> triggers = new ArrayList<>();

        Builder(int tw, int th, int cols, int rows) {
            if (tw <= 0 || th <= 0) throw new IllegalArgumentException("Invalid tile size " + tw + "x" + th);
            this.tw = tw; this.th = th; this.cols = cols; this.rows = rows;
        }

        int[][] alloc(int c, int r) throws IOException {
            if (c <= 0 || r <= 0 || (budget -= (long) c * r) < 0)
                throw new IOException("Invalid or oversized tile layer " + c + "x" + r);
            return new int[r][c];
        }

        void tileLayer(String name, int[][] m) {
            cols = Math.max(cols, m[0].length);
            rows = Math.max(rows, m.length);
            if (!"collision".equals(name)) { layers.add(new TileLayer(name, m)); return; }
            for (int r = 0; r < m.length; r++) { // merge horizontal runs of solid tiles into rects
                int c = 0;
                while (c < m[r].length) {
                    if (m[r][c] == 0) { c++; continue; }
                    int start = c;
                    while (c < m[r].length && m[r][c] != 0) c++;
                    collision.add(rect(start * tw, r * th, (c - start) * tw, th));
                }
            }
        }

        void object(String layer, String name, float x, float y, float w, float h,
                    float[] pts, boolean closed, Map<String, String> props) {
            if ("collision".equals(layer)) {
                if (pts != null) { if (pts.length >= 4) collision.add(new CollisionShape(pts, closed)); }
                else if (w > 0 && h > 0) collision.add(rect(x, y, w, h));
            } else if ("spawns".equals(layer)) {
                String id = name.isEmpty() ? "spawn" + spawns.size() : name;
                spawns.put(id, new SpawnNode(id, x, y));
            } else if ("triggers".equals(layer)) {
                String script = props.get("onEnter");
                if (script != null && w > 0 && h > 0) triggers.add(new TriggerZone(x, y, w, h, script));
            } else if ("camera".equals(layer) && camera == null && w > 0 && h > 0) {
                camera = new Bounds(x, y, w, h);
            }
        }

        static CollisionShape rect(float x, float y, float w, float h) {
            return new CollisionShape(new float[]{x, y, x + w, y, x + w, y + h, x, y + h}, true);
        }

        Scene build(String id) { return new Scene(id, this); }
    }
}
