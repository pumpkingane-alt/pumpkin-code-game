package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Runtime value types of the script language. Mapping:
 *   None -> null, bool -> Boolean, int -> Long, float -> Double, str -> String,
 *   list/tuple -> PyList, dict -> PyDict, set -> PySet, range -> PyRange, functions -> Fn / Native.
 * Java 7 compatible.
 */
final class ScriptValues {
    private ScriptValues() {}

    /** Marker for "argument not given" in native function slots. */
    static final Object NOARG = new Object() { @Override public String toString() { return "<noarg>"; } };

    /** Host objects (tiles, monsters, enum constants, modules ...) that scripts can read attributes from. */
    interface ScriptObject {
        Object getAttr(String name);           // null + hasAttr()==false means "no such attribute"
        boolean hasAttr(String name);
        String typeName();
        String[] attrNames();
    }

    static final class PyList {
        final ArrayList<Object> a;
        final boolean tuple;
        PyList(boolean tuple) { a = new ArrayList<Object>(); this.tuple = tuple; }
        PyList(ArrayList<Object> l, boolean tuple) { a = l; this.tuple = tuple; }
        static PyList of(Object... items) { PyList l = new PyList(false); for (int i = 0; i < items.length; i++) l.a.add(items[i]); return l; }
        static PyList tuple(Object... items) { PyList l = new PyList(true); for (int i = 0; i < items.length; i++) l.a.add(items[i]); return l; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PyList)) return false;
            PyList b = (PyList) o;
            if (a.size() != b.a.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!ScriptInterp.valEq(a.get(i), b.a.get(i))) return false;
            return true;
        }
        @Override public int hashCode() { int h = 7; for (int i = 0; i < a.size(); i++) { Object x = a.get(i); h = h * 31 + (x == null ? 0 : x.hashCode()); } return h; }
    }

    static final class PyDict {
        final LinkedHashMap<Object, Object> m = new LinkedHashMap<Object, Object>();
    }

    static final class PySet {
        final LinkedHashSet<Object> s = new LinkedHashSet<Object>();
    }

    static final class PyRange {
        final long start, stop, step;
        PyRange(long a, long b, long c) { start = a; stop = b; step = c; }
        long size() {
            if (step > 0) return start >= stop ? 0 : (stop - start + step - 1) / step;
            return start <= stop ? 0 : (start - stop - step - 1) / (-step);
        }
        long at(long i) { return start + i * step; }
    }

    /** Enum-like constant: Crop.Pumpkin, North, Place.Well ... Compared by identity. */
    static final class EnumVal implements ScriptObject {
        final String kind, name; final int id; final double fx, fy;      // fx/fy used by direction constants
        EnumVal(String kind, String name, int id) { this(kind, name, id, 0, 0); }
        EnumVal(String kind, String name, int id, double fx, double fy) { this.kind = kind; this.name = name; this.id = id; this.fx = fx; this.fy = fy; }
        public Object getAttr(String n) {
            if (n.equals("name")) return name;
            if (n.equals("value") || n.equals("id")) return Long.valueOf(id);
            if (n.equals("kind")) return kind;
            return null;
        }
        public boolean hasAttr(String n) { return n.equals("name") || n.equals("value") || n.equals("id") || n.equals("kind"); }
        public String typeName() { return kind; }
        public String[] attrNames() { return new String[] {"name", "value", "kind"}; }
        @Override public String toString() { return kind.length() == 0 ? name : kind + "." + name; }
    }

    /** A plain namespace object (imported module, or the Crop / Item ... enum groups). */
    static final class Namespace implements ScriptObject {
        final String name; final LinkedHashMap<String, Object> vars = new LinkedHashMap<String, Object>();
        Namespace(String n) { name = n; }
        Namespace put(String k, Object v) { vars.put(k, v); return this; }
        public Object getAttr(String n) { return vars.get(n); }
        public boolean hasAttr(String n) { return vars.containsKey(n); }
        public String typeName() { return "module"; }
        public String[] attrNames() { return vars.keySet().toArray(new String[vars.size()]); }
        @Override public String toString() { return "<module " + name + ">"; }
    }

    /** Variable scope. See ScriptInterp.lookup / assign for the Python-style resolution rules. */
    static final class Env {
        final HashMap<String, Object> vars = new HashMap<String, Object>();
        final Env parent;
        final ScriptAst.FuncDef fn;                 // function scope (null for module / comprehension)
        java.util.HashSet<String> compVars;         // comprehension scope variables
        final boolean isGlobal;
        Env(Env parent, ScriptAst.FuncDef fn, boolean global) { this.parent = parent; this.fn = fn; this.isGlobal = global; }
    }

    /** A user defined function / lambda (closure). */
    static final class Fn {
        final ScriptAst.FuncDef def; final Env closure; final Object[] defaults;
        Fn(ScriptAst.FuncDef d, Env c, Object[] defs) { def = d; closure = c; defaults = defs; }
        @Override public String toString() { return "<function " + def.name + ">"; }
    }

    /** Result of one call frame, used by exec(). */
    static final class Frame {
        final Env env; final ScriptInterp.Ctx cx; final String fname, file; final Frame caller;
        int line; Object ret;
        Frame(Env env, ScriptInterp.Ctx cx, String fname, String file, Frame caller) { this.env = env; this.cx = cx; this.fname = fname; this.file = file; this.caller = caller; }
    }

    /**
     * Built-in function. Two modes:
     *  - slot mode (sig without '*'): arguments are bound by position / keyword into fixed slots, absent optionals are NOARG.
     *  - raw mode (sig contains '*'): call() receives all positional args; keywords in kw (may be null).
     */
    abstract static class Native {
        final String name; final String sig; final String[] params; final boolean[] optional; final boolean raw; final int minArgs, maxArgs;
        String doc = "", category = "";
        Native(String name, String sig) {
            this.name = name; this.sig = sig;
            boolean r = sig.indexOf('*') >= 0;
            raw = r;
            if (r) { params = new String[0]; optional = new boolean[0]; minArgs = 0; maxArgs = -1; }
            else {
                String[] ps = sig.trim().length() == 0 ? new String[0] : sig.split(",");
                params = new String[ps.length]; optional = new boolean[ps.length];
                int mn = 0;
                for (int i = 0; i < ps.length; i++) {
                    String s = ps[i].trim();
                    int eq = s.indexOf('=');
                    if (eq >= 0) { optional[i] = true; params[i] = s.substring(0, eq).trim(); } else { params[i] = s; mn = i + 1; }
                }
                minArgs = mn; maxArgs = ps.length;
            }
        }
        /** slot mode */
        Object call(ScriptValues.Frame f, ScriptInterp in, Object[] a) { return null; }
        /** raw mode */
        Object callRaw(ScriptValues.Frame f, ScriptInterp in, Object[] pos, Map<String, Object> kw) { return null; }
        @Override public String toString() { return "<built-in function " + name + ">"; }
    }

    /** list.append etc: a method bound to its receiver. */
    static final class BoundMethod {
        final Object self; final String name;
        BoundMethod(Object s, String n) { self = s; name = n; }
        @Override public String toString() { return "<method " + name + ">"; }
    }

    /** Type name as shown in error messages. */
    static String typeName(Object o) {
        if (o == null) return "NoneType";
        if (o instanceof Boolean) return "bool";
        if (o instanceof Long) return "int";
        if (o instanceof Double) return "float";
        if (o instanceof String) return "str";
        if (o instanceof PyList) return ((PyList) o).tuple ? "tuple" : "list";
        if (o instanceof PyDict) return "dict";
        if (o instanceof PySet) return "set";
        if (o instanceof PyRange) return "range";
        if (o instanceof Fn) return "function";
        if (o instanceof Native || o instanceof BoundMethod) return "builtin_function";
        if (o instanceof ScriptObject) return ((ScriptObject) o).typeName();
        return o.getClass().getSimpleName();
    }
}
