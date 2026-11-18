package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import com.pumpkinpanic.coop.ScriptValues.*;

/** The game functions a script can call (movement, farming, shop, survival, sensors ...). Java 7 compatible. */
final class ScriptApi {
    private ScriptApi() {}

    private static final String[] BLOCKING = {"goto", "move", "plant", "harvest", "water", "refill", "refuel", "sell", "buy", "buy_seeds", "wear", "chop", "fish",
        "cheer", "revive", "hide", "sail", "collect_coins", "wait", "sleep", "join"};
    private static final HashSet<String> BLOCK = new HashSet<String>();
    static { for (int i = 0; i < BLOCKING.length; i++) BLOCK.add(BLOCKING[i]); }
    static boolean isBlocking(String n) { return BLOCK.contains(n); }

    static final String[] PLACE = {"Well", "SellBox", "OilBarrel", "Workbench", "Closet", "Cabin", "Door", "Dock", "Boat", "Bed", "Shore"};
    static final float[][] PLACE_XY = {
        {Data.WELL_X + 62, Data.WELL_Y + 10}, {Data.SELL_X, Data.SELL_Y + 42}, {Data.OIL_X, Data.OIL_Y + 45}, {1195, 700}, {1205, 640},
        {1100, 650}, {Data.DOOR_X, Data.DOOR_Y + 44}, {1300, 1612}, {Data.BOAT_X - 80, Data.BOAT_Y}, {Data.BED_X + 45, Data.BED_Y + 25}, {Data.LAND_X, Data.LAND_Y}};
    static final String[] ITEM_NAMES = {"Lantern", "OilTank", "WateringCan", "Boots", "Saw", "PlotKey", "Hull", "Engine", "Propeller", "FuelCan"};
    static final int[] ITEM_CODES = {20, 21, 22, 23, 24, 30, 40, 41, 42, 43};
    static final String[] HAT_NAMES = {"NoHat", "Straw", "Witch", "Daisy", "Beanie", "Crown", "Party"};

    /** Per-run constants shared by the natives and the objects they return (so that == works). */
    static final class Consts {
        final EnumVal[] crops = new EnumVal[5], places = new EnumVal[PLACE.length], items = new EnumVal[ITEM_NAMES.length], hats = new EnumVal[HAT_NAMES.length];
        EnumVal north, south, east, west;
        Consts() {
            for (int i = 0; i < 5; i++) crops[i] = new EnumVal("Crop", Data.CROP[i], i);
            for (int i = 0; i < places.length; i++) places[i] = new EnumVal("Place", PLACE[i], i);
            for (int i = 0; i < items.length; i++) items[i] = new EnumVal("Item", ITEM_NAMES[i], ITEM_CODES[i]);
            for (int i = 0; i < hats.length; i++) hats[i] = new EnumVal("Hat", HAT_NAMES[i], i);
            north = new EnumVal("", "North", 0, 0, -1); south = new EnumVal("", "South", 1, 0, 1); east = new EnumVal("", "East", 2, 1, 0); west = new EnumVal("", "West", 3, -1, 0);
        }
    }

    // ------------------------------------------------------------ host objects
    static final class TileObj implements ScriptObject {
        final ScriptHost host; final Consts k; final int idx;
        TileObj(ScriptHost h, Consts k, int i) { host = h; this.k = k; idx = i; }
        public String typeName() { return "Tile"; }
        public String[] attrNames() { return new String[] {"index", "plot", "x", "y", "crop", "empty", "growing", "ready", "growth", "water", "thirsty", "locked", "dist"}; }
        public boolean hasAttr(String n) { for (String s : attrNames()) if (s.equals(n)) return true; return false; }
        public Object getAttr(String n) {
            World w = host.link.world(); World.Player p = host.link.me();
            boolean locked = idx >= w.plots * 9;
            World.Tile t = w.tiles[idx];
            boolean planted = !locked && t.type >= 0;
            switch (n) {
                case "index": return Long.valueOf(idx);
                case "plot": return Long.valueOf(idx / 9);
                case "x": return Double.valueOf(Data.tileX(idx));
                case "y": return Double.valueOf(Data.tileY(idx));
                case "crop": return planted ? k.crops[t.type] : null;
                case "empty": return Boolean.valueOf(!locked && t.type < 0);
                case "growing": return Boolean.valueOf(planted && t.grow < 1f);
                case "ready": return Boolean.valueOf(planted && t.grow >= 1f);
                case "growth": return Double.valueOf(planted ? t.grow : 0.0);
                case "water": return Double.valueOf(planted ? t.water : 0.0);
                case "thirsty": return Boolean.valueOf(planted && t.grow < 1f && t.water < 0.35f);
                case "locked": return Boolean.valueOf(locked);
                case "dist": return Double.valueOf(World.dist(p.x, p.y, Data.tileX(idx), Data.tileY(idx)));
                default: return null;
            }
        }
        @Override public String toString() {
            World w = host.link.world();
            if (idx >= w.plots * 9) return "<Tile " + idx + " locked>";
            World.Tile t = w.tiles[idx];
            return "<Tile " + idx + (t.type < 0 ? " empty" : " " + Data.CROP[t.type] + (t.grow >= 1f ? " ripe" : " " + (int) (t.grow * 100) + "%") + " water " + (int) (t.water * 100) + "%") + ">";
        }
        @Override public boolean equals(Object o) { return o instanceof TileObj && ((TileObj) o).idx == idx; }
        @Override public int hashCode() { return idx; }
    }

    static final String[] MOB_KIND = {"skinwalker", "demon", "clown", "clown", "stag", "crow"};
    static final String[][] MOB_STATE = {
        {"idle", "arriving", "hunting", "leaving"}, {"idle", "appearing", "hunting", "leaving"}, {"idle", "waiting", "happy", "angry", "sad"}, {"idle", "waiting", "happy", "angry", "sad"},
        {"wandering", "stalking", "watching", "charging", "resting"}, {"perched", "flying", "dropping", "returning"}};

    static boolean hostile(int mi, World.Mob m) {
        if (!m.on) return false;
        switch (mi) {
            case World.M_SKIN: case World.M_DEMON: return m.st == 1 || m.st == 2;
            case World.M_CLOWN0: case World.M_CLOWN1: return m.st == 3;
            case World.M_STAG: return m.st >= 1 && m.st <= 3;
            default: return false;
        }
    }

    static final class MonsterObj implements ScriptObject {
        final ScriptHost host; final int mi;
        MonsterObj(ScriptHost h, int mi) { host = h; this.mi = mi; }
        public String typeName() { return "Monster"; }
        public String[] attrNames() { return new String[] {"kind", "name", "x", "y", "dist", "state", "hostile", "waiting", "active"}; }
        public boolean hasAttr(String n) { for (String s : attrNames()) if (s.equals(n)) return true; return false; }
        public Object getAttr(String n) {
            World w = host.link.world(); World.Player p = host.link.me(); World.Mob m = w.mob[mi];
            switch (n) {
                case "kind": case "name": return MOB_KIND[mi];
                case "x": return Double.valueOf(m.x);
                case "y": return Double.valueOf(m.y);
                case "dist": return Double.valueOf(World.dist(p.x, p.y, m.x, m.y));
                case "state": { String[] st = MOB_STATE[mi]; return m.st >= 0 && m.st < st.length ? st[m.st] : "?"; }
                case "hostile": return Boolean.valueOf(hostile(mi, m));
                case "waiting": return Boolean.valueOf(m.on && (mi == World.M_CLOWN0 || mi == World.M_CLOWN1) && m.st == 1);
                case "active": return Boolean.valueOf(m.on);
                default: return null;
            }
        }
        @Override public String toString() { World.Mob m = host.link.world().mob[mi]; String[] st = MOB_STATE[mi]; return "<" + MOB_KIND[mi] + " " + (m.st < st.length ? st[m.st] : "?") + " at " + (int) m.x + "," + (int) m.y + ">"; }
        @Override public boolean equals(Object o) { return o instanceof MonsterObj && ((MonsterObj) o).mi == mi; }
        @Override public int hashCode() { return mi; }
    }

    static final class CrewObj implements ScriptObject {
        final ScriptHost host; final Consts k; final int slot;
        CrewObj(ScriptHost h, Consts k, int s) { host = h; this.k = k; slot = s; }
        public String typeName() { return "Player"; }
        public String[] attrNames() { return new String[] {"name", "x", "y", "state", "down", "alive", "me", "bot", "dist", "hat", "index"}; }
        public boolean hasAttr(String n) { for (String s : attrNames()) if (s.equals(n)) return true; return false; }
        public Object getAttr(String n) {
            World w = host.link.world(); World.Player me = host.link.me(); World.Player q = w.pl[slot];
            switch (n) {
                case "name": return q.name;
                case "x": return Double.valueOf(q.x);
                case "y": return Double.valueOf(q.y);
                case "state": return q.state == World.ST_ALIVE ? "alive" : (q.state == World.ST_DOWN ? "down" : "dead");
                case "down": return Boolean.valueOf(q.state == World.ST_DOWN);
                case "alive": return Boolean.valueOf(q.state == World.ST_ALIVE);
                case "me": return Boolean.valueOf(q == me);
                case "bot": return Boolean.valueOf(q.bot);
                case "dist": return Double.valueOf(World.dist(me.x, me.y, q.x, q.y));
                case "hat": return k.hats[Math.max(0, Math.min(k.hats.length - 1, q.hat))];
                case "index": return Long.valueOf(slot);
                default: return null;
            }
        }
        @Override public String toString() { World.Player q = host.link.world().pl[slot]; return "<Player " + q.name + (q.state == World.ST_ALIVE ? "" : " down") + ">"; }
        @Override public boolean equals(Object o) { return o instanceof CrewObj && ((CrewObj) o).slot == slot; }
        @Override public int hashCode() { return 100 + slot; }
    }

    // ------------------------------------------------------------ install
    private static void reg(ScriptInterp in, ScriptHost host, Consts k, String cat, String name, String sig, String doc) {
        Api a = new Api(host, k, name, sig);
        a.category = cat; a.doc = doc;
        in.add(a);
    }

    static void install(ScriptInterp in, ScriptHost host) {
        Consts k = new Consts();
        // constants
        in.constant("North", k.north); in.constant("South", k.south); in.constant("East", k.east); in.constant("West", k.west);
        in.constant("Up", k.north); in.constant("Down", k.south); in.constant("Right", k.east); in.constant("Left", k.west);
        Namespace crop = new Namespace("Crop"); for (int i = 0; i < 5; i++) crop.put(Data.CROP[i], k.crops[i]);
        Namespace place = new Namespace("Place"); for (int i = 0; i < PLACE.length; i++) place.put(PLACE[i], k.places[i]);
        Namespace item = new Namespace("Item"); for (int i = 0; i < ITEM_NAMES.length; i++) item.put(ITEM_NAMES[i], k.items[i]);
        Namespace hat = new Namespace("Hat"); for (int i = 0; i < HAT_NAMES.length; i++) hat.put(HAT_NAMES[i], k.hats[i]);
        in.constant("Crop", crop); in.constant("Place", place); in.constant("Item", item); in.constant("Hat", hat);
        in.constant("CROPS", PyList.tuple((Object[]) k.crops));
        in.constant("TILE", Long.valueOf((long) Data.TILE));

        String M = "Moving", F = "Farming", S = "Shop", V = "Survival", I = "Status", T = "World info", P = "Program";
        reg(in, host, k, M, "goto", "a, b=None", "goto(x, y)  /  goto(Place.Well)  /  goto(tile)  /  goto(monster): walk there with path finding. True when you arrived.");
        reg(in, host, k, M, "move", "direction, tiles=1", "move(North)  /  move(East, 3): walk straight for that many tiles (a tile is 52 units). True if you got all the way.");
        reg(in, host, k, M, "face", "direction", "turn to look North / South / East / West without moving");
        reg(in, host, k, M, "sprint", "on=True", "sprint(True) runs faster (uses stamina) until you call sprint(False)");
        reg(in, host, k, M, "auto_walk", "on=True", "auto_walk(False): actions no longer walk for you - you must stand next to the target yourself");
        reg(in, host, k, M, "pos", "", "your position as (x, y)");
        reg(in, host, k, M, "distance", "a, b=None", "distance from you to (x, y) / a Place / tile / monster / player");
        reg(in, host, k, M, "inside", "", "True while you are inside the cabin");
        reg(in, host, k, F, "plant", "crop, tile=None", "plant(Crop.Turnip): walk to the nearest empty soil and plant a seed. plant(Crop.Corn, t) plants on tile t. True on success.");
        reg(in, host, k, F, "harvest", "tile=None", "harvest(): walk to the nearest ripe crop and pick it. harvest(t) picks tile t. True on success.");
        reg(in, host, k, F, "water", "tile=None", "water(): water the nearest plant that needs it. water(t) waters tile t. Plants only grow while wet!");
        reg(in, host, k, F, "refill", "", "walk to the well and refill the watering can");
        reg(in, host, k, F, "refuel", "", "walk to the oil barrel and refill the lantern oil (costs coins)");
        reg(in, host, k, F, "sell", "", "walk to the sell box and sell all crops and fish you carry. Returns the coins you got.");
        reg(in, host, k, F, "chop", "n=1", "chop(n): saw n times at the nearest tree (1 wood each). Needs the saw: buy(Item.Saw). Returns the wood you got.");
        reg(in, host, k, F, "fish", "n=1", "fish(n): go to the dock and catch n fish (reels in bites and scares away monsters with the lamp). Returns the number caught.");
        reg(in, host, k, F, "collect_coins", "max=8", "walk over the coins lying on the ground (crows drop them). Returns the coins gained.");
        reg(in, host, k, F, "tiles", "plot=None", "list of all soil tiles you unlocked: for t in tiles(): ...  Tile attributes: index plot x y crop empty growing ready growth water thirsty dist");
        reg(in, host, k, F, "get_tile", "index", "the tile with this number (0..44)");
        reg(in, host, k, F, "tile_here", "", "the tile you are standing next to, or None");
        reg(in, host, k, F, "can_harvest", "tile=None", "True if a ripe crop is within reach (or if the given tile is ripe)");
        reg(in, host, k, F, "plots", "", "number of unlocked plots (each has 9 tiles)");
        reg(in, host, k, S, "buy", "thing, qty=1", "buy(Item.Saw) / buy(Item.Lantern) / buy(Crop.Corn) (one seed) / buy(Hat.Crown). Walks into the cabin. Returns how many you bought.");
        reg(in, host, k, S, "buy_seeds", "crop, qty=1", "buy_seeds(Crop.Pumpkin, 10): buys seeds (uses the cheaper 5-packs). Returns how many seeds you got.");
        reg(in, host, k, S, "wear", "hat", "wear(Hat.Crown): put on a hat you own (buys it first if it is new)");
        reg(in, host, k, S, "price", "thing", "price(Item.Saw): coins it costs now, or -1 if it is not available");
        reg(in, host, k, S, "can_afford", "thing, qty=1", "True if you have the coins (and wood) for this");
        reg(in, host, k, S, "owned", "thing", "owned(Item.Saw) / owned(Item.Hull) / owned(Hat.Crown): do you have it?");
        reg(in, host, k, S, "level", "item", "upgrade level 0..3 of Item.Lantern / OilTank / WateringCan / Boots");
        reg(in, host, k, V, "lantern", "on=True", "lantern(True) raises the lantern (burns oil, scares some monsters); lantern(False) lowers it");
        reg(in, host, k, V, "hide", "seconds=0", "walk into the cabin where most monsters cannot reach you. hide(5) also stays in for 5 seconds (keeps the character busy so lower priority threads cannot walk out).");
        reg(in, host, k, V, "sail", "", "go to the boat and set sail (needs all 4 boat parts and the whole crew nearby). In endless mode you land on the next island.");
        reg(in, host, k, V, "cheer", "", "walk to the waiting clown and cheer it up (+30 coins, and it will not turn blue)");
        reg(in, host, k, V, "revive", "who=None", "walk to a downed friend and revive them");
        reg(in, host, k, V, "emote", "n", "show a speech bubble: 0 !  1 ?  2 Help!  3 Run!  4 Thanks");
        reg(in, host, k, V, "monsters", "", "list of the monsters that are out now. Attributes: kind state x y dist hostile waiting");
        reg(in, host, k, V, "threat", "", "True while any monster is hunting or about to hunt");
        reg(in, host, k, V, "crew", "", "list of all players. Attributes: name x y state down alive me bot dist");
        reg(in, host, k, V, "me", "", "yourself as a player object");
        reg(in, host, k, I, "coins", "", "the team's coins");
        reg(in, host, k, I, "wood", "", "the team's wood");
        reg(in, host, k, I, "lives", "", "lives left (the run ends at 0)");
        reg(in, host, k, I, "seeds", "crop", "how many seeds of this crop you have");
        reg(in, host, k, I, "held", "crop", "how many harvested crops of this kind you carry");
        reg(in, host, k, I, "fish_count", "", "fish you carry");
        reg(in, host, k, I, "carry_value", "", "coins you would get for everything you carry");
        reg(in, host, k, I, "water_left", "", "water in the can (each pour uses 1)");
        reg(in, host, k, I, "water_max", "", "size of the can");
        reg(in, host, k, I, "oil_left", "", "lantern oil left");
        reg(in, host, k, I, "oil_max", "", "size of the oil tank");
        reg(in, host, k, I, "stamina", "", "0..1: how much sprint energy is left");
        reg(in, host, k, I, "alive", "", "False while you are down or dead");
        reg(in, host, k, I, "inventory", "", "dict with everything you have");
        reg(in, host, k, T, "time", "", "game seconds since the run began");
        reg(in, host, k, T, "day", "", "day number, starting at 1");
        reg(in, host, k, T, "daytime", "", "\"day\", \"dusk\", \"night\" or \"dawn\"");
        reg(in, host, k, T, "darkness", "", "0 (bright) .. 1 (pitch black)");
        reg(in, host, k, T, "is_night", "", "True when it is dark");
        reg(in, host, k, T, "time_to_night", "", "seconds until night starts (0 at night)");
        reg(in, host, k, T, "boat_parts", "", "how many of the 4 boat parts are built");
        reg(in, host, k, T, "boat_ready", "", "True when the boat is finished");
        reg(in, host, k, T, "missing_parts", "", "list of boat parts (Item.Hull ...) still to build");
        reg(in, host, k, T, "island", "", "island number: 1 is the first (endless mode counts up)");
        reg(in, host, k, T, "danger", "", "how much harder this island is: 1.0, 1.5, 2.25 ... (endless mode)");
        reg(in, host, k, T, "is_endless", "", "True in endless mode");
        reg(in, host, k, T, "coins_on_ground", "", "list of (x, y, value) of loose coins");
        reg(in, host, k, P, "wait", "seconds", "wait(2.5): do nothing for that many game seconds. Other threads keep running.");
        reg(in, host, k, P, "sleep", "seconds", "same as wait()");
        reg(in, host, k, P, "spawn", "*args", "spawn(fn, args..., priority=0): run fn in parallel. A higher priority thread gets the character first.");
        reg(in, host, k, P, "join", "thread", "wait until a spawned thread has finished");
        reg(in, host, k, P, "kill", "thread", "stop a spawned thread");
        reg(in, host, k, P, "stop", "", "end the whole script");
        reg(in, host, k, P, "save", "key, value", "remember a value: it survives restarts of the script (and new islands)");
        reg(in, host, k, P, "load", "key, default=None", "read a value saved with save()");
        reg(in, host, k, P, "last_error", "", "text of the last failed action, or None");
        reg(in, host, k, P, "set_strict", "on=True", "set_strict(True): failed actions raise an ActionError (catch it with try / except) instead of just warning");
        reg(in, host, k, P, "warn", "*values", "print a yellow warning in the console");
        reg(in, host, k, P, "clear_log", "", "clear the console");
        reg(in, host, k, P, "exit", "", "same as stop()");
    }

    // ------------------------------------------------------------ the natives
    static final class Api extends Native {
        final ScriptHost host; final Consts k;
        Api(ScriptHost h, Consts k, String name, String sig) { super(name, sig.equals("*args") || sig.equals("*values") ? sig : sig); host = h; this.k = k; }
        @Override Object call(Frame f, ScriptInterp in, Object[] a) { return ScriptApi.dispatch(this, f, in, a, null); }
        @Override Object callRaw(Frame f, ScriptInterp in, Object[] pos, Map<String, Object> kw) { return ScriptApi.dispatch(this, f, in, pos, kw); }
    }

    private static ScriptError typeErr(ScriptInterp in, Frame f, String fn, String msg) { return in.err(f, null, "TypeError", fn + "(): " + msg); }

    private static Object[] opt(Object[] a) { Object[] r = a.clone(); for (int i = 0; i < r.length; i++) if (r[i] == ScriptValues.NOARG) r[i] = null; return r; }
    private static boolean given(Object o) { return o != ScriptValues.NOARG && o != null; }

    private static int cropArg(Object o, String fn, ScriptInterp in, Frame f) {
        if (o instanceof EnumVal && ((EnumVal) o).kind.equals("Crop")) return ((EnumVal) o).id;
        if (o instanceof Long && ((Long) o).longValue() >= 0 && ((Long) o).longValue() < 5) return (int) ((Long) o).longValue();
        throw typeErr(in, f, fn, "expected a crop like Crop.Turnip, not " + ScriptLib.describeVal(o));
    }

    private static TileObj tileArg(Object o, Consts k, ScriptHost h, String fn, ScriptInterp in, Frame f) {
        if (o instanceof TileObj) return (TileObj) o;
        if (o instanceof Long) { long i = ((Long) o).longValue(); if (i >= 0 && i < World.NT) return new TileObj(h, k, (int) i); }
        throw typeErr(in, f, fn, "expected a tile (from tiles()) or a tile number, not " + ScriptLib.describeVal(o));
    }

    /** Shop code of something buyable. */
    private static int shopCode(Object o, String fn, ScriptInterp in, Frame f) {
        if (o instanceof EnumVal) {
            EnumVal e = (EnumVal) o;
            if (e.kind.equals("Item")) return e.id;
            if (e.kind.equals("Crop")) return e.id;
            if (e.kind.equals("Hat")) return 50 + e.id;
        }
        if (o instanceof String) {
            int n = AddonEngineController.itemCount();
            ArrayList<String> ids = new ArrayList<String>();
            for (int i = 0; i < n; i++) { AddonEngineController.AddonItem ai = AddonEngineController.item(i); if (ai != null) { ids.add(ai.id); if (ai.id.equals(o) || ai.name.equals(o)) return World.ADDON_ITEM0 + i; } }
            ScriptError e = in.err(f, null, "ValueError", fn + "(): no addon item called " + ScriptLib.repr(o));
            String sg = ScriptLib.suggest((String) o, ids); if (sg != null) e.hint("did you mean '" + sg + "'?");
            throw e;
        }
        throw typeErr(in, f, fn, "expected Item.X, Crop.X (a seed) or Hat.X, not " + ScriptLib.describeVal(o)).hint("example: buy(Item.Saw)");
    }

    private static double num(Object o, String fn, String pn, ScriptInterp in, Frame f) { return ScriptLib.argNum(o, fn, pn, in, f); }

    /** Resolve goto-style arguments to a point. result[0..1] = x,y ; result[2] = arrive radius */
    private static float[] target(Object[] a, Consts k, ScriptHost h, String fn, ScriptInterp in, Frame f, String[] label) {
        Object x = a[0], y = a.length > 1 ? a[1] : null;
        if (x instanceof EnumVal && ((EnumVal) x).kind.equals("Place")) { int i = ((EnumVal) x).id; label[0] = PLACE[i]; return new float[] {PLACE_XY[i][0], PLACE_XY[i][1], 10f}; }
        if (x instanceof TileObj) { int i = ((TileObj) x).idx; label[0] = "tile " + i; return new float[] {Data.tileX(i), Data.tileY(i) + 18f, 8f}; }
        if (x instanceof MonsterObj) { World.Mob m = h.link.world().mob[((MonsterObj) x).mi]; label[0] = "the " + MOB_KIND[((MonsterObj) x).mi]; return new float[] {m.x, m.y, 30f}; }
        if (x instanceof CrewObj) { World.Player q = h.link.world().pl[((CrewObj) x).slot]; label[0] = q.name; return new float[] {q.x, q.y, 30f}; }
        if (x instanceof PyList && ((PyList) x).a.size() == 2 && y == null) { Object x0 = ((PyList) x).a.get(0), y0 = ((PyList) x).a.get(1); return new float[] {(float) num(x0, fn, "x", in, f), (float) num(y0, fn, "y", in, f), 10f}; }
        if (ScriptInterp.isNum(x)) {
            if (!ScriptInterp.isNum(y)) throw typeErr(in, f, fn, "needs both x and y:  " + fn + "(x, y)").hint("or a place:  " + fn + "(Place.Well)");
            label[0] = "(" + ScriptLib.str(x) + ", " + ScriptLib.str(y) + ")";
            return new float[] {(float) num(x, fn, "x", in, f), (float) num(y, fn, "y", in, f), 10f};
        }
        throw typeErr(in, f, fn, "expected (x, y), a Place, a tile, a monster or a player, not " + ScriptLib.describeVal(x));
    }

    private static Object run(Frame f, ScriptHost host, ScriptTask t, String fname) {
        Object r = f.cx.waitTask(t);
        if (t.failReason != null) host.failed(f, fname, t.failReason);
        return r;
    }

    private static PyList list() { return new PyList(false); }

    static Object dispatch(Api n, Frame f, ScriptInterp in, Object[] args, Map<String, Object> kw) {
        ScriptHost host = n.host; Consts k = n.k;
        World w = host.link.world(); World.Player p = host.link.me();
        String name = n.name;
        Object[] a = n.raw ? args : opt(args);
        if (w == null || p == null) throw in.err(f, null, "RuntimeError", name + "(): the game is not running");
        switch (name) {
            // ---------------------------------------------------------------- moving
            case "goto": {
                String[] label = {"there"};
                float[] t = target(a, k, host, name, in, f, label);
                return run(f, host, new ScriptTasks.Go(t[0], t[1], t[2], label[0]), name);
            }
            case "move": {
                Object d = a[0];
                if (!(d instanceof EnumVal) || !(((EnumVal) d).kind.length() == 0)) throw typeErr(in, f, name, "expected North, South, East or West, not " + ScriptLib.describeVal(d));
                EnumVal e = (EnumVal) d;
                double tiles = a[1] == null ? 1 : num(a[1], name, "tiles", in, f);
                if (tiles <= 0) return Boolean.TRUE;
                return run(f, host, new ScriptTasks.MoveDir((float) e.fx, (float) e.fy, (float) (tiles * Data.TILE)), name);
            }
            case "face": {
                Object d = a[0];
                if (!(d instanceof EnumVal) || ((EnumVal) d).kind.length() != 0) throw typeErr(in, f, name, "expected North, South, East or West");
                p.fx = (float) ((EnumVal) d).fx; p.fy = (float) ((EnumVal) d).fy;
                return null;
            }
            case "sprint": host.pilot.sprintPref = a[0] == null || ScriptInterp.truthy(a[0]); return null;
            case "auto_walk": host.pilot.autoWalk = a[0] == null || ScriptInterp.truthy(a[0]); return null;
            case "pos": return PyList.tuple(Double.valueOf(p.x), Double.valueOf(p.y));
            case "distance": {
                String[] label = {""};
                float[] t = target(a, k, host, name, in, f, label);
                return Double.valueOf(World.dist(p.x, p.y, t[0], t[1]));
            }
            case "inside": return Boolean.valueOf(p.inside);

            // ---------------------------------------------------------------- farming
            case "plant": {
                int crop = cropArg(a[0], name, in, f);
                int tile = a[1] == null ? -1 : tileArg(a[1], k, host, name, in, f).idx;
                return run(f, host, new ScriptTasks.Farm(ScriptTasks.PLANT, crop, tile), name);
            }
            case "harvest": return run(f, host, new ScriptTasks.Farm(ScriptTasks.HARVEST, 0, a[0] == null ? -1 : tileArg(a[0], k, host, name, in, f).idx), name);
            case "water": return run(f, host, new ScriptTasks.Farm(ScriptTasks.WATER, 0, a[0] == null ? -1 : tileArg(a[0], k, host, name, in, f).idx), name);
            case "refill": return run(f, host, new ScriptTasks.Station(ScriptTasks.WELL), name);
            case "refuel": return run(f, host, new ScriptTasks.Station(ScriptTasks.OIL), name);
            case "sell": return run(f, host, new ScriptTasks.Station(ScriptTasks.SELL), name);
            case "chop": { long cnt = a[0] == null ? 1 : ScriptLib.argInt(a[0], name, "n", in, f); if (cnt <= 0) return Long.valueOf(0); return run(f, host, new ScriptTasks.Chop((int) Math.min(cnt, 200)), name); }
            case "fish": { long cnt = a[0] == null ? 1 : ScriptLib.argInt(a[0], name, "n", in, f); if (cnt <= 0) return Long.valueOf(0); return run(f, host, new ScriptTasks.Fish((int) Math.min(cnt, 50)), name); }
            case "collect_coins": { long mx = a[0] == null ? 8 : ScriptLib.argInt(a[0], name, "max", in, f); return run(f, host, new ScriptTasks.Coins((int) Math.max(1, Math.min(mx, 40))), name); }
            case "tiles": {
                PyList r = list();
                int plot = a[0] == null ? -1 : (int) ScriptLib.argInt(a[0], name, "plot", in, f);
                for (int i = 0; i < w.plots * 9; i++) if (plot < 0 || i / 9 == plot) r.a.add(new TileObj(host, k, i));
                return r;
            }
            case "get_tile": { long i = ScriptLib.argInt(a[0], name, "index", in, f); if (i < 0 || i >= World.NT) throw in.err(f, null, "IndexError", "get_tile(): there are tiles 0 to " + (World.NT - 1)); return new TileObj(host, k, (int) i); }
            case "tile_here": {
                float best = 46f; int bi = -1;
                for (int i = 0; i < w.plots * 9; i++) { float d = World.dist(p.x, p.y, Data.tileX(i), Data.tileY(i)); if (d < best) { best = d; bi = i; } }
                return bi < 0 ? null : new TileObj(host, k, bi);
            }
            case "can_harvest": {
                if (a[0] != null) { TileObj t = tileArg(a[0], k, host, name, in, f); return Boolean.valueOf(t.idx < w.plots * 9 && w.tiles[t.idx].type >= 0 && w.tiles[t.idx].grow >= 1f); }
                for (int i = 0; i < w.plots * 9; i++) if (w.tiles[i].type >= 0 && w.tiles[i].grow >= 1f && World.dist(p.x, p.y, Data.tileX(i), Data.tileY(i)) < 46f) return Boolean.TRUE;
                return Boolean.FALSE;
            }
            case "plots": return Long.valueOf(w.plots);

            // ---------------------------------------------------------------- shop
            case "buy": {
                int code = shopCode(a[0], name, in, f);
                long qty = a[1] == null ? 1 : ScriptLib.argInt(a[1], name, "qty", in, f);
                if (qty <= 0) return Long.valueOf(0);
                return run(f, host, new ScriptTasks.Buy(code, (int) Math.min(qty, 200), name), name);
            }
            case "buy_seeds": {
                int c = cropArg(a[0], name, in, f);
                long qty = a[1] == null ? 1 : ScriptLib.argInt(a[1], name, "qty", in, f);
                if (qty <= 0) return Long.valueOf(0);
                if (qty > 200) qty = 200;
                long got = 0;
                long packs = qty / 5, singles = qty % 5;
                if (packs > 0) { Object r = run(f, host, new ScriptTasks.Buy(10 + c, (int) packs, name), name); got += 5 * ScriptInterp.toL(r instanceof Long ? r : Long.valueOf(0)); if (got < packs * 5) return Long.valueOf(got); }
                if (singles > 0) { Object r = run(f, host, new ScriptTasks.Buy(c, (int) singles, name), name); got += r instanceof Long ? ((Long) r).longValue() : 0; }
                return Long.valueOf(got);
            }
            case "wear": {
                if (!(a[0] instanceof EnumVal) || !((EnumVal) a[0]).kind.equals("Hat")) throw typeErr(in, f, name, "expected a hat like Hat.Crown");
                Object r = run(f, host, new ScriptTasks.Buy(50 + ((EnumVal) a[0]).id, 1, name), name);
                return Boolean.valueOf(r instanceof Long && ((Long) r).longValue() > 0);
            }
            case "price": return Long.valueOf(w.price(p, shopCode(a[0], name, in, f)));
            case "can_afford": {
                int code = shopCode(a[0], name, in, f);
                long qty = a[1] == null ? 1 : ScriptLib.argInt(a[1], name, "qty", in, f);
                int pr = w.price(p, code);
                if (pr < 0) return Boolean.FALSE;
                if (code == 40 && w.wood < Data.PART_WOOD) return Boolean.FALSE;
                return Boolean.valueOf((long) pr * qty <= w.coins);
            }
            case "owned": {
                Object o = a[0];
                if (o instanceof EnumVal) {
                    EnumVal e = (EnumVal) o;
                    if (e.kind.equals("Hat")) return Boolean.valueOf((p.hatMask & (1 << e.id)) != 0);
                    if (e.kind.equals("Item")) {
                        if (e.id == 24) return Boolean.valueOf(p.saw);
                        if (e.id == 30) return Boolean.valueOf(w.plots > 1);
                        if (e.id >= 40) return Boolean.valueOf((w.boatMask & (1 << (e.id - 40))) != 0);
                        return Boolean.valueOf(p.lvl[e.id - 20] > 0);
                    }
                }
                throw typeErr(in, f, name, "expected Item.X or Hat.X");
            }
            case "level": {
                Object o = a[0];
                if (o instanceof EnumVal && ((EnumVal) o).kind.equals("Item") && ((EnumVal) o).id >= 20 && ((EnumVal) o).id < 24) return Long.valueOf(p.lvl[((EnumVal) o).id - 20]);
                throw typeErr(in, f, name, "expected Item.Lantern, Item.OilTank, Item.WateringCan or Item.Boots");
            }

            // ---------------------------------------------------------------- survival
            case "lantern": host.pilot.lampPref = a[0] == null || ScriptInterp.truthy(a[0]); return null;
            case "hide": return run(f, host, new ScriptTasks.Hide(a[0] == null ? 0f : (float) num(a[0], name, "seconds", in, f)), name);
            case "sail": return run(f, host, new ScriptTasks.Sail(), name);
            case "cheer": return run(f, host, new ScriptTasks.Cheer(), name);
            case "revive": {
                int who = -1;
                if (a[0] != null) { if (!(a[0] instanceof CrewObj)) throw typeErr(in, f, name, "expected a player from crew()"); who = ((CrewObj) a[0]).slot; }
                return run(f, host, new ScriptTasks.Revive(who), name);
            }
            case "emote": {
                int e = (int) ScriptLib.argInt(a[0], name, "n", in, f);
                if (e < 0 || e >= Data.EMOTE.length) throw in.err(f, null, "ValueError", "emote(): choose 0 to " + (Data.EMOTE.length - 1));
                if (host.link.authority()) w.emote(p, e); else host.link.cmdEmote(e);
                return null;
            }
            case "monsters": {
                PyList r = list();
                for (int i = 0; i < World.NM; i++) if (w.mob[i].on) r.a.add(new MonsterObj(host, i));
                return r;
            }
            case "threat": { for (int i = 0; i < World.NM; i++) if (hostile(i, w.mob[i])) return Boolean.TRUE; return Boolean.FALSE; }
            case "crew": { PyList r = list(); for (int i = 0; i < World.NP; i++) if (w.pl[i].active) r.a.add(new CrewObj(host, k, i)); return r; }
            case "me": { for (int i = 0; i < World.NP; i++) if (w.pl[i] == p) return new CrewObj(host, k, i); return null; }

            // ---------------------------------------------------------------- status
            case "coins": return Long.valueOf(w.coins);
            case "wood": return Long.valueOf(w.wood);
            case "lives": return Long.valueOf(w.lives);
            case "seeds": return Long.valueOf(p.seeds[cropArg(a[0], name, in, f)]);
            case "held": return Long.valueOf(p.crops[cropArg(a[0], name, in, f)]);
            case "fish_count": return Long.valueOf(p.fish);
            case "carry_value": { long tot = p.fishVal; for (int i = 0; i < 5; i++) tot += (long) p.crops[i] * Data.CROP_SELL[i]; return Long.valueOf(tot); }
            case "water_left": return Double.valueOf(p.water);
            case "water_max": return Double.valueOf(Data.CAN_CAP[p.lvl[2]]);
            case "oil_left": return Double.valueOf(p.oil);
            case "oil_max": return Double.valueOf(Data.OIL_CAP[p.lvl[1]]);
            case "stamina": return Double.valueOf(p.stamina);
            case "alive": return Boolean.valueOf(p.state == World.ST_ALIVE);
            case "inventory": {
                PyDict d = new PyDict();
                d.m.put("coins", Long.valueOf(w.coins)); d.m.put("wood", Long.valueOf(w.wood)); d.m.put("lives", Long.valueOf(w.lives));
                d.m.put("saw", Boolean.valueOf(p.saw)); d.m.put("fish", Long.valueOf(p.fish));
                PyDict sd = new PyDict(), cd = new PyDict();
                for (int i = 0; i < 5; i++) { sd.m.put(Data.CROP[i], Long.valueOf(p.seeds[i])); cd.m.put(Data.CROP[i], Long.valueOf(p.crops[i])); }
                d.m.put("seeds", sd); d.m.put("crops", cd);
                return d;
            }

            // ---------------------------------------------------------------- world info
            case "time": return Double.valueOf(w.T);
            case "day": return Long.valueOf(w.day());
            case "daytime": { float c = w.T % Data.CYC; return c < Data.DUSK0 ? "day" : (c < Data.NIGHT0 ? "dusk" : (c < Data.DAWN0 ? "night" : "dawn")); }
            case "darkness": return Double.valueOf(w.dark());
            case "is_night": return Boolean.valueOf(w.isNight());
            case "time_to_night": { float c = w.T % Data.CYC; return Double.valueOf(c >= Data.NIGHT0 ? 0.0 : Data.NIGHT0 - c); }
            case "boat_parts": return Long.valueOf(Integer.bitCount(w.boatMask));
            case "boat_ready": return Boolean.valueOf(w.boatMask == 15);
            case "missing_parts": { PyList r = list(); for (int i = 0; i < 4; i++) if ((w.boatMask & (1 << i)) == 0) r.a.add(k.items[6 + i]); return r; }
            case "island": return Long.valueOf(w.island);
            case "danger": return Double.valueOf(w.danger());
            case "is_endless": return Boolean.valueOf(w.endless);
            case "coins_on_ground": { PyList r = list(); for (int i = 0; i < World.NC; i++) { World.Coin c = w.coin[i]; if (c.on) r.a.add(PyList.tuple(Double.valueOf(c.x), Double.valueOf(c.y), Long.valueOf(c.v))); } return r; }

            // ---------------------------------------------------------------- program
            case "wait": case "sleep": {
                double s = num(a[0], name, "seconds", in, f);
                if (s < 0) throw in.err(f, null, "ValueError", name + "(): seconds cannot be negative");
                in.flushPrint();
                f.cx.sleep(s);
                return null;
            }
            case "spawn": {
                if (args.length < 1) throw typeErr(in, f, name, "needs a function:  spawn(my_function)");
                Object fn = args[0];
                if (!(fn instanceof Fn) && !(fn instanceof Native)) throw typeErr(in, f, name, "the first argument must be a function (write the name without parentheses)").hint("spawn(guard)  not  spawn(guard())");
                int prio = 0;
                if (kw != null) for (Map.Entry<String, Object> e : kw.entrySet()) { if (e.getKey().equals("priority")) prio = (int) ScriptLib.argInt(e.getValue(), name, "priority", in, f); else throw typeErr(in, f, name, "unknown option '" + e.getKey() + "' (only priority=)"); }
                int live = 0; for (ScriptHost.ThreadCtx c : host.threads) if (!c.finished) live++;
                if (live >= 8) throw in.err(f, null, "RuntimeError", "spawn(): too many threads (max 8)");
                Object[] rest = new Object[args.length - 1]; System.arraycopy(args, 1, rest, 0, rest.length);
                String tn = fn instanceof Fn ? ((Fn) fn).def.name : ((Native) fn).name;
                return host.spawn(fn, rest, tn, prio);
            }
            case "join": {
                if (!(a[0] instanceof ScriptHost.ThreadCtx)) throw typeErr(in, f, name, "expected a thread from spawn()");
                ScriptHost.ThreadCtx t = (ScriptHost.ThreadCtx) a[0];
                if (!t.finished) { ScriptHost.ThreadCtx me = (ScriptHost.ThreadCtx) f.cx; if (t == me) throw in.err(f, null, "RuntimeError", "join(): a thread cannot wait for itself"); me.joining = t; host.handBack(me); }
                return t.result;
            }
            case "kill": {
                if (!(a[0] instanceof ScriptHost.ThreadCtx)) throw typeErr(in, f, name, "expected a thread from spawn()");
                ScriptHost.ThreadCtx t = (ScriptHost.ThreadCtx) a[0];
                if (t == f.cx) throw new ScriptError.Kill();
                t.killed = true; t.waiting = null; t.sleeping = false; t.joining = null; host.pilot.cancelOwner(t);
                return null;
            }
            case "stop": case "exit": { in.flushPrint(); host.requestStop(); throw new ScriptError.Kill(); }
            case "save": in.store.put(ScriptLib.argStr(a[0], name, "key", in, f), a[1]); return null;
            case "load": { String key = ScriptLib.argStr(a[0], name, "key", in, f); return in.store.containsKey(key) ? in.store.get(key) : a[1]; }
            case "last_error": return host.lastFail;
            case "set_strict": in.strict = a[0] == null || ScriptInterp.truthy(a[0]); return null;
            case "warn": { StringBuilder sb = new StringBuilder(); for (int i = 0; i < args.length; i++) { if (i > 0) sb.append(' '); sb.append(ScriptLib.str(args[i])); } in.flushPrint(); host.log(ScriptHost.LOG_WARN, sb.toString()); return null; }
            case "clear_log": host.clearLog(); return null;
            default: throw in.err(f, null, "RuntimeError", "function not implemented: " + name);
        }
    }
}
