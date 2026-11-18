package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.pumpkinpanic.coop.ScriptValues.Native;

/** Help texts for the in-game editor. The function list is generated from the registered functions, so it is never out of date. */
final class ScriptDocs {
    private ScriptDocs() {}

    static final String QUICK =
        "HOW IT WORKS\n" +
        "You cannot move the character by hand any more. Everything is done by a script written in a Python-like language: press CODE, write your script, press RUN.\n\n" +
        "Every action function (goto, plant, water, harvest, sell, buy ...) walks the character there by itself and waits until the job is finished. They return True / a number when it worked, and False (with a yellow warning in the console) when it did not.\n\n" +
        "QUICK START\n" +
        "  plant(Crop.Turnip)      # walk to empty soil and plant\n" +
        "  water()                 # water the nearest dry plant\n" +
        "  harvest()               # pick the nearest ripe crop\n" +
        "  sell()                  # sell what you carry\n" +
        "  buy(Item.Saw)           # shopping (you walk into the cabin)\n" +
        "  for t in tiles(): ...   # look at every tile you own\n\n" +
        "ERRORS\n" +
        "CHECK finds mistakes without running (red = error, yellow = warning). While the script runs, an error stops it and shows the line, the error type and a hint in the console. Tap a problem to jump to its line.\n\n" +
        "PARALLEL THREADS\n" +
        "spawn(guard, priority=5) runs guard() at the same time as your main code. When two threads want the character, the higher priority one wins and the other waits.\n\n" +
        "ENDLESS MODE\n" +
        "When you finish the boat and call sail() you land on the shore of a fresh copy of the island, 1.5x more dangerous every time. Your gear, lives (+1) and half of your coins come with you. Use island() and danger() in your script.\n\n" +
        "FILES\n" +
        "Scripts are saved as .py files. A script can use another one: import farm  (loads farm.py) or  from farm import water_all.\n";

    static final String LANGUAGE =
        "LANGUAGE CHEAT SHEET (Python style)\n\n" +
        "Values:   5   2.5   \"text\"   True  False  None   [1, 2]   (1, 2)   {\"a\": 1}   {1, 2}\n" +
        "Math:     + - * / // % **      comparison: == != < > <= >=   logic: and or not   in / not in\n" +
        "Assign:   x = 3   x += 1   a, b = b, a   first, *rest = items   items[0] = 9\n" +
        "Text:     f\"coins: {coins()}\"   f\"{x:.2f}\"   \"a,b\".split(\",\")   \" \".join(list)   s.upper()\n" +
        "If:       if a: ...  elif b: ...  else: ...      a if cond else b\n" +
        "Loops:    while cond:   for i in range(5):   for x in items:   break   continue\n" +
        "Lists:    xs.append(1)  len(xs)  xs[-1]  xs[1:3]  sorted(xs, key=fn)  [x*2 for x in xs if x > 1]\n" +
        "Dicts:    d = {}  d[\"k\"] = 1  d.get(\"k\", 0)  for k, v in d.items():  {k: v for ...}\n" +
        "Functions: def f(a, b=2, *rest, **opts):  return a   lambda t: t.dist\n" +
        "Scope:    inside a function use  global name  to change a module variable\n" +
        "Errors:   try: ... except ValueError as e: ...   raise Exception(\"text\")   assert x > 0\n" +
        "Modules:  import farm   from farm import water_all\n" +
        "Built-ins: print len range min max abs sum round int float str sorted enumerate zip map filter any all\n" +
        "Math:     sqrt floor ceil sin cos atan2 clamp lerp dist random randint choice shuffle pi\n" +
        "Not supported: classes, with, yield, import *.\n";

    /** The reference of all game functions grouped by category. */
    static String api(ScriptInterp in) {
        LinkedHashMap<String, StringBuilder> cats = new LinkedHashMap<String, StringBuilder>();
        String[] order = {"Moving", "Farming", "Shop", "Survival", "Status", "World info", "Program", "Python basics", "Math", "Errors"};
        for (int i = 0; i < order.length; i++) cats.put(order[i], new StringBuilder());
        for (Native n : in.natives.values()) {
            StringBuilder sb = cats.get(n.category);
            if (sb == null) { sb = new StringBuilder(); cats.put(n.category, sb); }
            sb.append("  ").append(n.name).append("(").append(n.sig.replace("=None", "=None")).append(")\n");
            if (n.doc.length() > 0 && !n.category.equals("Math") && !n.category.equals("Errors")) sb.append("      ").append(n.doc).append("\n");
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, StringBuilder> e : cats.entrySet()) {
            if (e.getValue().length() == 0) continue;
            out.append("== ").append(e.getKey().toUpperCase()).append(" ==\n").append(e.getValue()).append("\n");
        }
        out.append("== CONSTANTS ==\n  North South East West   (also Up Down Left Right)\n  Crop.Turnip Carrot Corn Pumpkin Moonbloom   (CROPS lists them all)\n  Item.Lantern OilTank WateringCan Boots Saw PlotKey Hull Engine Propeller FuelCan\n  Hat.NoHat Straw Witch Daisy Beanie Crown Party\n  Place.Well SellBox OilBarrel Workbench Closet Cabin Door Dock Boat Bed Shore\n  TILE (size of a tile: 52)  pi  tau  inf\n");
        return out.toString();
    }

    /** Entries for the snippet picker: {category, label, text to insert, cursor offset from the start of the text}. */
    static ArrayList<String[]> entries(ScriptInterp in) {
        ArrayList<String[]> r = new ArrayList<String[]>();
        for (Native n : in.natives.values()) {
            if (n.category.equals("Errors")) continue;
            boolean noArgs = n.sig.trim().length() == 0;
            String insert = n.name + (noArgs ? "()" : "()");
            r.add(new String[] {n.category, n.name + "(" + n.sig + ")", insert, String.valueOf(noArgs ? insert.length() : n.name.length() + 1)});
        }
        String[][] extra = {
            {"Constants", "Crop.Turnip ...", "Crop.Turnip", "11"}, {"Constants", "Item.Saw ...", "Item.Saw", "8"}, {"Constants", "Place.Well ...", "Place.Well", "10"},
            {"Constants", "Hat.Crown ...", "Hat.Crown", "9"}, {"Constants", "North East South West", "North", "5"},
            {"Snippets", "for loop over tiles", "for t in tiles():\n    ", "21"},
            {"Snippets", "while True loop", "while True:\n    wait(1)\n", "18"},
            {"Snippets", "def function", "def name():\n    pass\n", "4"},
            {"Snippets", "if / else", "if True:\n    pass\nelse:\n    pass\n", "3"},
            {"Snippets", "try / except", "try:\n    pass\nexcept Exception as e:\n    print(e)\n", "4"},
            {"Snippets", "spawn a guard thread", "spawn(guard, priority=5)\n", "6"},
            {"Snippets", "list comprehension", "[t for t in tiles() if t.ready]", "1"}};
        for (int i = 0; i < extra.length; i++) r.add(extra[i]);
        return r;
    }
}
