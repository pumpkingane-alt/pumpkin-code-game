package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import com.pumpkinpanic.coop.ScriptAst.*;
import com.pumpkinpanic.coop.ScriptValues.*;

/**
 * Tree-walking evaluator for the script language. One interpreter instance = one script run (shared globals);
 * every script thread has its own Ctx and call frames. Java 7 compatible.
 */
final class ScriptInterp {
    static final int MAX_DEPTH = 110;
    static final int NORMAL = 0, BREAK = 1, CONTINUE = 2, RETURN = 3;

    /** Per-thread execution context. The host subclass implements the blocking operations. */
    abstract static class Ctx {
        int ops, limit = 3500, depth;
        volatile boolean killed;
        String name = "main";
        /** Called when the op budget of this frame is used up: hand control back to the game until the next frame. */
        abstract void yieldBudget();
        /** Blocking: wait game-seconds. */
        abstract void sleep(double seconds);
        /** Blocking: wait until the game task is done and return its result. */
        abstract Object waitTask(Object task);
        final void tick() {
            if (++ops >= limit) { ops = 0; yieldBudget(); }
            if (killed) throw new ScriptError.Kill();
        }
    }

    interface Output { void print(int kind, String text); }          // kind 0 = print, 1 = warning, 2 = error, 3 = info
    interface Loader { String source(String module); }                // returns script source or null

    final Env globals = new Env(null, null, true);
    final HashMap<String, Object> builtins = new HashMap<String, Object>();
    final LinkedHashMap<String, Native> natives = new LinkedHashMap<String, Native>();
    final Random rnd = new Random();
    Output out;
    Loader loader;
    final HashMap<String, ModuleObj> modules = new HashMap<String, ModuleObj>();
    final HashSet<String> importing = new HashSet<String>();
    final HashMap<String, Object> store = new HashMap<String, Object>();     // save()/load() memory
    boolean strict;                                                        // failed game actions raise errors
    private final StringBuilder pbuf = new StringBuilder();

    /** print() output: line based, text after the last newline waits for the next print / flushPrint. */
    void printRaw(String s) {
        pbuf.append(s);
        int i;
        while ((i = pbuf.indexOf("\n")) >= 0) { String line = pbuf.substring(0, i); pbuf.delete(0, i + 1); if (out != null) out.print(0, line); }
    }
    void flushPrint() { if (pbuf.length() > 0) { String t = pbuf.toString(); pbuf.setLength(0); if (out != null) out.print(0, t); } }
    long startNs = System.nanoTime();

    ScriptInterp(Output out) {
        this.out = out;
        ScriptLib.install(this);
    }

    static final class ModuleObj implements ScriptObject {
        final String name; final Env env;
        ModuleObj(String n, Env e) { name = n; env = e; }
        public Object getAttr(String n) { return env.vars.get(n); }
        public boolean hasAttr(String n) { return env.vars.containsKey(n); }
        public String typeName() { return "module"; }
        public String[] attrNames() { return env.vars.keySet().toArray(new String[env.vars.size()]); }
        @Override public String toString() { return "<module " + name + ">"; }
    }

    static final class ExcValue implements ScriptObject {
        final String type, msg; final int line;
        ExcValue(String t, String m, int l) { type = t; msg = m; line = l; }
        public Object getAttr(String n) { if (n.equals("message") || n.equals("msg")) return msg; if (n.equals("type")) return type; if (n.equals("line")) return Long.valueOf(line); return null; }
        public boolean hasAttr(String n) { return n.equals("message") || n.equals("msg") || n.equals("type") || n.equals("line"); }
        public String typeName() { return type; }
        public String[] attrNames() { return new String[] {"message", "type", "line"}; }
        @Override public String toString() { return msg; }
    }

    // ------------------------------------------------------------ registration of natives / constants
    void add(Native n) { natives.put(n.name, n); builtins.put(n.name, n); }
    void constant(String name, Object v) { builtins.put(name, v); }

    // ------------------------------------------------------------ running programs
    /** Run a parsed program in the global scope on the given context. */
    void runProgram(Program prog, Ctx cx) {
        Frame f = new Frame(globals, cx, "<main>", prog.file, null);
        execBlock(prog.body, f);
    }

    Frame mainFrame(Ctx cx, String file) { return new Frame(globals, cx, "<main>", file, null); }

    // ------------------------------------------------------------ errors
    ScriptError err(Frame f, Node n, String type, String msg) {
        ScriptError e = new ScriptError(type, msg, n != null && n.line > 0 ? n.line : (f != null ? f.line : 0), n != null ? n.col : 0);
        if (f != null) { e.file = f.file; fillTrace(e, f); }
        return e;
    }
    void fillTrace(ScriptError e, Frame f) {
        if (e.trace.size() > 0) return;
        int n = 0, rep = 0; String last = null;
        for (Frame c = f; c != null && n < 9; c = c.caller) {
            String ln = "in " + (c.fname.startsWith("<") ? c.fname : c.fname + "()") + " at line " + c.line;
            if (ln.equals(last)) { rep++; continue; }
            if (rep > 0) { e.trace.set(e.trace.size() - 1, last + "  (x" + (rep + 1) + ")"); rep = 0; }
            e.trace.add(ln); last = ln; n++;
        }
        if (rep > 0) e.trace.set(e.trace.size() - 1, last + "  (x" + (rep + 1) + ")");
    }
    /** Add position info to an error thrown from native code. */
    ScriptError locate(ScriptError e, Frame f, Node at) {
        if (e.line <= 0) { e.line = at != null && at.line > 0 ? at.line : (f != null ? f.line : 0); e.col = at != null ? at.col : 0; if (f != null && "main".equals(e.file)) e.file = f.file; }
        if (f != null) fillTrace(e, f);
        return e;
    }

    // ------------------------------------------------------------ variable access
    private static Env globalEnv(Env e) { while (e.parent != null && !e.isGlobal) e = e.parent; return e; }

    Object lookup(String name, Frame f, Node at) {
        Env e = f.env;
        while (e != null) {
            if (e.isGlobal) {
                Object v = e.vars.get(name);
                if (v != null || e.vars.containsKey(name)) return v;
                break;
            }
            if (e.fn != null) {
                if (e.fn.globals.contains(name)) { Env g = globalEnv(e); Object v = g.vars.get(name); if (v != null || g.vars.containsKey(name)) return v; break; }
                if (e.fn.locals.contains(name) && !e.fn.nonlocals.contains(name)) {
                    Object v = e.vars.get(name);
                    if (v != null || e.vars.containsKey(name)) return v;
                    throw err(f, at, "UnboundLocalError", "local variable '" + name + "' referenced before assignment").hint(unboundHint(name, f));
                }
            } else if (e.compVars != null && e.compVars.contains(name)) {
                Object v = e.vars.get(name);
                if (v != null || e.vars.containsKey(name)) return v;
            }
            e = e.parent;
        }
        Object b = builtins.get(name);
        if (b != null || builtins.containsKey(name)) return b;
        ScriptError ne = err(f, at, "NameError", "name '" + name + "' is not defined");
        String s = ScriptLib.suggest(name, visibleNames(f));
        if (s != null) ne.hint("did you mean '" + s + "'?");
        else if (name.equals("true") || name.equals("false") || name.equals("null") || name.equals("none")) ne.hint("Python spells it " + (name.equals("null") ? "None" : Character.toUpperCase(name.charAt(0)) + name.substring(1)));
        throw ne;
    }

    private String unboundHint(String name, Frame f) {
        Env g = globalEnv(f.env);
        if (g.vars.containsKey(name)) return "to change the global '" + name + "' inside a function, write  global " + name + "  at the top of the function";
        return "give '" + name + "' a value before you use it";
    }

    ArrayList<String> visibleNames(Frame f) {
        ArrayList<String> names = new ArrayList<String>();
        for (Env e = f.env; e != null; e = e.parent) {
            names.addAll(e.vars.keySet());
            if (e.fn != null) names.addAll(e.fn.locals);
        }
        names.addAll(builtins.keySet());
        return names;
    }

    void assign(String name, Object v, Frame f, Node at) {
        Env e = f.env;
        if (e.fn != null) {
            if (e.fn.globals.contains(name)) { globalEnv(e).vars.put(name, v); return; }
            if (e.fn.nonlocals.contains(name)) {
                for (Env p = e.parent; p != null; p = p.parent) {
                    if (p.fn != null && p.fn.locals.contains(name) && !p.fn.nonlocals.contains(name)) { p.vars.put(name, v); return; }
                }
                throw err(f, at, "SyntaxError", "no binding for nonlocal '" + name + "' found");
            }
            e.vars.put(name, v);
            return;
        }
        if (e.compVars != null) { e.vars.put(name, v); return; }
        e.vars.put(name, v);
    }

    // ------------------------------------------------------------ statements
    int execBlock(Stmt[] b, Frame f) {
        for (int i = 0; i < b.length; i++) {
            int c = exec(b[i], f);
            if (c != NORMAL) return c;
        }
        return NORMAL;
    }

    int exec(Stmt s, Frame f) {
        f.line = s.line;
        f.cx.tick();
        switch (s.kind) {
            case ScriptAst.S_EXPR: eval(((ExprStmt) s).e, f); return NORMAL;
            case ScriptAst.S_ASSIGN: {
                Assign a = (Assign) s;
                Object v = eval(a.value, f);
                for (int i = 0; i < a.targets.length; i++) assignTo(a.targets[i], v, f);
                return NORMAL;
            }
            case ScriptAst.S_AUG: return augAssign((AugAssign) s, f);
            case ScriptAst.S_IF: {
                If n = (If) s;
                for (int i = 0; i < n.tests.length; i++) {
                    if (truthy(eval(n.tests[i], f))) return execBlock(n.blocks[i], f);
                }
                if (n.orelse != null) return execBlock(n.orelse, f);
                return NORMAL;
            }
            case ScriptAst.S_WHILE: {
                While n = (While) s;
                boolean broke = false;
                while (truthy(eval(n.test, f))) {
                    f.cx.tick();
                    int c = execBlock(n.body, f);
                    if (c == BREAK) { broke = true; break; }
                    if (c == RETURN) return c;
                    f.line = n.line;
                }
                if (!broke && n.orelse != null) return execBlock(n.orelse, f);
                return NORMAL;
            }
            case ScriptAst.S_FOR: return execFor((For) s, f);
            case ScriptAst.S_DEF: {
                FuncDef d = (FuncDef) s;
                assign(d.name, makeFn(d, f), f, d);
                return NORMAL;
            }
            case ScriptAst.S_RETURN: { Return r = (Return) s; f.ret = r.value == null ? null : eval(r.value, f); return RETURN; }
            case ScriptAst.S_BREAK: return BREAK;
            case ScriptAst.S_CONT: return CONTINUE;
            case ScriptAst.S_PASS: case ScriptAst.S_GLOBAL: case ScriptAst.S_NONLOCAL: return NORMAL;
            case ScriptAst.S_TRY: return execTry((Try) s, f);
            case ScriptAst.S_RAISE: execRaise((Raise) s, f); return NORMAL;
            case ScriptAst.S_ASSERT: {
                Assert a = (Assert) s;
                if (!truthy(eval(a.test, f))) throw err(f, a, "AssertionError", a.msg == null ? "assertion failed" : ScriptLib.str(eval(a.msg, f)));
                return NORMAL;
            }
            case ScriptAst.S_IMPORT: {
                Import im = (Import) s;
                assign(im.alias, importModule(im.module, f, im), f, im);
                return NORMAL;
            }
            case ScriptAst.S_FROM: {
                FromImport fi = (FromImport) s;
                ModuleObj m = importModule(fi.module, f, fi);
                for (int i = 0; i < fi.names.length; i++) {
                    if (!m.env.vars.containsKey(fi.names[i])) {
                        ScriptError e = err(f, fi, "ImportError", "cannot import name '" + fi.names[i] + "' from '" + fi.module + "'");
                        String sg = ScriptLib.suggest(fi.names[i], new ArrayList<String>(m.env.vars.keySet()));
                        if (sg != null) e.hint("did you mean '" + sg + "'?");
                        throw e;
                    }
                    assign(fi.aliases[i], m.env.vars.get(fi.names[i]), f, fi);
                }
                return NORMAL;
            }
            case ScriptAst.S_DEL: {
                Del d = (Del) s;
                for (int i = 0; i < d.targets.length; i++) delTarget(d.targets[i], f);
                return NORMAL;
            }
            default: throw err(f, s, "RuntimeError", "unknown statement");
        }
    }

    private int execFor(For n, Frame f) {
        Object it = eval(n.iter, f);
        Iter iter = iterate(it, f, n.iter);
        boolean broke = false;
        while (iter.hasNext()) {
            f.cx.tick();
            Object v = iter.next();
            assignTo(n.target, v, f);
            int c = execBlock(n.body, f);
            if (c == BREAK) { broke = true; break; }
            if (c == RETURN) return c;
            f.line = n.line;
        }
        if (!broke && n.orelse != null) return execBlock(n.orelse, f);
        return NORMAL;
    }

    private Fn makeFn(FuncDef d, Frame f) {
        Object[] defs = new Object[d.params.length];
        for (int i = 0; i < defs.length; i++) defs[i] = d.params[i].def == null ? ScriptValues.NOARG : eval(d.params[i].def, f);
        return new Fn(d, f.env, defs);
    }

    private int augAssign(AugAssign a, Frame f) {
        Expr t = a.target;
        if (t.kind == ScriptAst.E_NAME) {
            String id = ((Name) t).id;
            Object cur = lookup(id, f, t);
            Object rhs = eval(a.value, f);
            assign(id, augValue(a.op, cur, rhs, f, a), f, a);
        } else if (t.kind == ScriptAst.E_INDEX) {
            Index ix = (Index) t;
            Object obj = eval(ix.obj, f), key = eval(ix.idx, f);
            Object cur = getIndex(obj, key, f, t);
            Object rhs = eval(a.value, f);
            setIndex(obj, key, augValue(a.op, cur, rhs, f, a), f, t);
        } else if (t.kind == ScriptAst.E_ATTR) {
            Attr at = (Attr) t;
            Object obj = eval(at.obj, f);
            Object cur = getAttr(obj, at.name, f, at);
            Object rhs = eval(a.value, f);
            setAttr(obj, at.name, augValue(a.op, cur, rhs, f, a), f, at);
        } else throw err(f, a, "SyntaxError", "illegal target for augmented assignment");
        return NORMAL;
    }

    private Object augValue(int op, Object cur, Object rhs, Frame f, Node at) {
        if (op == ScriptAst.ADD && cur instanceof PyList && !((PyList) cur).tuple && rhs instanceof PyList) { ((PyList) cur).a.addAll(new ArrayList<Object>(((PyList) rhs).a)); return cur; }
        return binop(op, cur, rhs, f, at);
    }

    void assignTo(Expr t, Object v, Frame f) {
        switch (t.kind) {
            case ScriptAst.E_NAME: assign(((Name) t).id, v, f, t); return;
            case ScriptAst.E_INDEX: { Index ix = (Index) t; setIndex(eval(ix.obj, f), eval(ix.idx, f), v, f, t); return; }
            case ScriptAst.E_ATTR: { Attr a = (Attr) t; setAttr(eval(a.obj, f), a.name, v, f, t); return; }
            case ScriptAst.E_SLICE: {
                Slice sl = (Slice) t;
                Object target = eval(sl.obj, f);
                if (!(target instanceof PyList) || ((PyList) target).tuple) throw err(f, t, "TypeError", "'" + ScriptValues.typeName(target) + "' object does not support slice assignment");
                ArrayList<Object> dst = ((PyList) target).a;
                if (sl.step != null) throw err(f, t, "TypeError", "slice assignment with a step is not supported");
                long n = dst.size();
                long lo = sl.lo == null ? 0 : ScriptInterp.toL(eval(sl.lo, f)), hi = sl.hi == null ? n : ScriptInterp.toL(eval(sl.hi, f));
                if (lo < 0) lo = Math.max(0, lo + n); if (lo > n) lo = n;
                if (hi < 0) hi = Math.max(0, hi + n); if (hi > n) hi = n; if (hi < lo) hi = lo;
                ArrayList<Object> ins = toList(v, f, t);
                for (long i = hi - 1; i >= lo; i--) dst.remove((int) i);
                dst.addAll((int) lo, ins);
                return;
            }
            case ScriptAst.E_LIST: {
                ListE l = (ListE) t;
                ArrayList<Object> vals = new ArrayList<Object>();
                Iter it = iterate(v, f, t);
                while (it.hasNext()) vals.add(it.next());
                int star = -1;
                for (int i = 0; i < l.items.length; i++) if (l.items[i].kind == ScriptAst.E_STAR) star = i;
                if (star < 0) {
                    if (vals.size() != l.items.length) throw err(f, t, "ValueError", (vals.size() < l.items.length ? "not enough values to unpack (expected " + l.items.length + ", got " + vals.size() + ")" : "too many values to unpack (expected " + l.items.length + ")"));
                    for (int i = 0; i < l.items.length; i++) assignTo(l.items[i], vals.get(i), f);
                } else {
                    int after = l.items.length - star - 1;
                    if (vals.size() < l.items.length - 1) throw err(f, t, "ValueError", "not enough values to unpack (expected at least " + (l.items.length - 1) + ", got " + vals.size() + ")");
                    for (int i = 0; i < star; i++) assignTo(l.items[i], vals.get(i), f);
                    PyList mid = new PyList(false);
                    for (int i = star; i < vals.size() - after; i++) mid.a.add(vals.get(i));
                    assignTo(((Star) l.items[star]).e, mid, f);
                    for (int i = 0; i < after; i++) assignTo(l.items[star + 1 + i], vals.get(vals.size() - after + i), f);
                }
                return;
            }
            default: throw err(f, t, "SyntaxError", "cannot assign to this expression");
        }
    }

    private void delTarget(Expr t, Frame f) {
        if (t.kind == ScriptAst.E_NAME) {
            String id = ((Name) t).id;
            Env e = f.env;
            if (e.vars.containsKey(id)) { e.vars.remove(id); return; }
            Env g = globalEnv(e);
            if (g.vars.containsKey(id)) { g.vars.remove(id); return; }
            throw err(f, t, "NameError", "name '" + id + "' is not defined");
        } else if (t.kind == ScriptAst.E_INDEX) {
            Index ix = (Index) t;
            Object obj = eval(ix.obj, f), key = eval(ix.idx, f);
            if (obj instanceof PyDict) { Object k = ScriptLib.normKey(key, this, f, t); if (!((PyDict) obj).m.containsKey(k)) throw err(f, t, "KeyError", ScriptLib.repr(key)); ((PyDict) obj).m.remove(k); }
            else if (obj instanceof PyList && !((PyList) obj).tuple) { PyList l = (PyList) obj; int i = (int) ScriptLib.index(key, l.a.size(), this, f, t, "list"); l.a.remove(i); }
            else throw err(f, t, "TypeError", "'" + ScriptValues.typeName(obj) + "' object doesn't support item deletion");
        } else throw err(f, t, "SyntaxError", "cannot delete this");
    }

    // ------------------------------------------------------------ try / raise
    private int execTry(Try t, Frame f) {
        int code = NORMAL;
        ScriptError pending = null;
        try {
            code = execBlock(t.body, f);
            if (t.orelse != null && code == NORMAL) code = execBlock(t.orelse, f);
        } catch (ScriptError e) {
            Handler h = null;
            for (int i = 0; i < t.handlers.length && h == null; i++) if (handlerMatches(t.handlers[i], e, f)) h = t.handlers[i];
            if (h == null) pending = e;
            else {
                try {
                    if (h.name != null) assign(h.name, new ExcValue(e.type, e.msg, e.line), f, t);
                    code = execBlock(h.body, f);
                } catch (ScriptError e2) { pending = e2; }
            }
        } catch (StackOverflowError so) {
            pending = err(f, t, "RecursionError", "maximum recursion depth exceeded");
        }
        if (t.fin != null) {
            int fc = execBlock(t.fin, f);
            if (fc != NORMAL) return fc;
        }
        if (pending != null) throw pending;
        return code;
    }

    private boolean handlerMatches(Handler h, ScriptError e, Frame f) {
        if (h.type == null) return true;
        Object tv = eval(h.type, f);
        if (tv instanceof PyList) {
            for (int i = 0; i < ((PyList) tv).a.size(); i++) if (typeMatches(((PyList) tv).a.get(i), e.type)) return true;
            return false;
        }
        return typeMatches(tv, e.type);
    }
    private boolean typeMatches(Object excType, String thrown) {
        if (!(excType instanceof Native)) return false;
        return ScriptLib.excIsA(thrown, ((Native) excType).name);
    }

    private void execRaise(Raise r, Frame f) {
        if (r.e == null) throw err(f, r, "RuntimeError", "re-raise without an active exception");
        Object v = eval(r.e, f);
        if (v instanceof ExcValue) { ExcValue ev = (ExcValue) v; throw err(f, r, ev.type, ev.msg); }
        if (v instanceof Native && ScriptLib.isExcName(((Native) v).name)) throw err(f, r, ((Native) v).name, "");
        throw err(f, r, "TypeError", "can only raise exceptions, e.g.  raise ValueError(\"message\")");
    }

    // ------------------------------------------------------------ imports
    ModuleObj importModule(String name, Frame f, Node at) {
        ModuleObj m = modules.get(name);
        if (m != null) return m;
        if (loader == null) throw err(f, at, "ImportError", "no module named '" + name + "'");
        String src = loader.source(name);
        if (src == null) throw err(f, at, "ImportError", "no script named '" + name + "' (create a file called " + name + ".py in the editor)");
        if (!importing.add(name)) throw err(f, at, "ImportError", "circular import of '" + name + "'");
        try {
            Program p = ScriptParser.parse(src, name);
            Env env = new Env(null, null, true);
            m = new ModuleObj(name, env);
            modules.put(name, m);
            Frame mf = new Frame(env, f.cx, "<module " + name + ">", name, f);
            execBlock(p.body, mf);
        } catch (ScriptError e) {
            modules.remove(name);
            if ("main".equals(e.file) || e.file == null) e.file = name;
            throw e;
        } finally { importing.remove(name); }
        return m;
    }

    // ------------------------------------------------------------ expressions
    static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return ((Boolean) o).booleanValue();
        if (o instanceof Long) return ((Long) o).longValue() != 0;
        if (o instanceof Double) return ((Double) o).doubleValue() != 0.0;
        if (o instanceof String) return ((String) o).length() > 0;
        if (o instanceof PyList) return !((PyList) o).a.isEmpty();
        if (o instanceof PyDict) return !((PyDict) o).m.isEmpty();
        if (o instanceof PySet) return !((PySet) o).s.isEmpty();
        if (o instanceof PyRange) return ((PyRange) o).size() > 0;
        return true;
    }

    Object eval(Expr e, Frame f) {
        switch (e.kind) {
            case ScriptAst.E_CONST: return ((Const) e).v;
            case ScriptAst.E_NAME: return lookup(((Name) e).id, f, e);
            case ScriptAst.E_LIST: {
                ListE l = (ListE) e;
                PyList r = new PyList(l.tuple);
                for (int i = 0; i < l.items.length; i++) {
                    Expr it = l.items[i];
                    if (it.kind == ScriptAst.E_STAR) { Iter x = iterate(eval(((Star) it).e, f), f, it); while (x.hasNext()) r.a.add(x.next()); }
                    else r.a.add(eval(it, f));
                }
                return r;
            }
            case ScriptAst.E_DICT: {
                DictE d = (DictE) e;
                PyDict r = new PyDict();
                for (int i = 0; i < d.keys.length; i++) { Object k = eval(d.keys[i], f); r.m.put(ScriptLib.normKey(k, this, f, d.keys[i]), eval(d.vals[i], f)); }
                return r;
            }
            case ScriptAst.E_SET: {
                SetE s = (SetE) e;
                PySet r = new PySet();
                for (int i = 0; i < s.items.length; i++) r.s.add(ScriptLib.normKey(eval(s.items[i], f), this, f, s.items[i]));
                return r;
            }
            case ScriptAst.E_BIN: {
                Bin b = (Bin) e;
                Object l = eval(b.l, f), r = eval(b.r, f);
                return binop(b.op, l, r, f, b);
            }
            case ScriptAst.E_UNARY: return unary((Unary) e, f);
            case ScriptAst.E_BOOL: {
                BoolOp b = (BoolOp) e;
                Object l = eval(b.l, f);
                if (b.and) return truthy(l) ? eval(b.r, f) : l;
                return truthy(l) ? l : eval(b.r, f);
            }
            case ScriptAst.E_CMP: {
                Compare c = (Compare) e;
                Object left = eval(c.first, f);
                for (int i = 0; i < c.ops.length; i++) {
                    Object right = eval(c.rest[i], f);
                    if (!compare(c.ops[i], left, right, f, c)) return Boolean.FALSE;
                    left = right;
                }
                return Boolean.TRUE;
            }
            case ScriptAst.E_CALL: return evalCall((Call) e, f);
            case ScriptAst.E_ATTR: { Attr a = (Attr) e; return getAttr(eval(a.obj, f), a.name, f, a); }
            case ScriptAst.E_INDEX: { Index ix = (Index) e; Object o = eval(ix.obj, f); return getIndex(o, eval(ix.idx, f), f, e); }
            case ScriptAst.E_SLICE: return evalSlice((Slice) e, f);
            case ScriptAst.E_COND: { Cond c = (Cond) e; return truthy(eval(c.test, f)) ? eval(c.a, f) : eval(c.b, f); }
            case ScriptAst.E_LAMBDA: return makeFn(((Lambda) e).def, f);
            case ScriptAst.E_COMP: return evalComp((ListComp) e, f);
            case ScriptAst.E_FSTR: return evalFStr((FStr) e, f);
            case ScriptAst.E_STAR: throw err(f, e, "SyntaxError", "'*' is only allowed inside a call or a list");
            default: throw err(f, e, "RuntimeError", "unknown expression");
        }
    }

    private Object evalFStr(FStr s, Frame f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.parts.length; i++) {
            Object p = s.parts[i];
            if (p instanceof String) { sb.append((String) p); continue; }
            FPart fp = (FPart) p;
            Object v = eval(fp.e, f);
            if (fp.conv != null) { if (fp.conv.equals("r")) v = ScriptLib.repr(v); else if (fp.conv.equals("s")) v = ScriptLib.str(v); }
            if (fp.spec != null && fp.spec.length() > 0) sb.append(ScriptLib.formatSpec(v, fp.spec, this, f, fp.e));
            else sb.append(ScriptLib.str(v));
        }
        return sb.toString();
    }

    private Object evalComp(ListComp c, Frame f) {
        Env env = new Env(f.env, null, false);
        env.compVars = c.vars;
        Frame cf = new Frame(env, f.cx, f.fname, f.file, f.caller);
        cf.line = f.line;
        Object result;
        PyList lst = null; PyDict dict = null; PySet set = null;
        if (c.val != null) { dict = new PyDict(); result = dict; }
        else if (c.isSet) { set = new PySet(); result = set; }
        else { lst = new PyList(false); result = lst; }
        compLoop(c, 0, cf, lst, dict, set);
        return result;
    }

    private void compLoop(ListComp c, int gi, Frame cf, PyList lst, PyDict dict, PySet set) {
        if (gi == c.gens.length) {
            if (dict != null) { Object k = eval(c.elt, cf); dict.m.put(ScriptLib.normKey(k, this, cf, c.elt), eval(c.val, cf)); }
            else if (set != null) set.s.add(ScriptLib.normKey(eval(c.elt, cf), this, cf, c.elt));
            else lst.a.add(eval(c.elt, cf));
            return;
        }
        Comp g = c.gens[gi];
        Iter it = iterate(eval(g.iter, cf), cf, g.iter);
        while (it.hasNext()) {
            cf.cx.tick();
            assignTo(g.target, it.next(), cf);
            boolean ok = true;
            for (int i = 0; i < g.ifs.length && ok; i++) ok = truthy(eval(g.ifs[i], cf));
            if (ok) compLoop(c, gi + 1, cf, lst, dict, set);
        }
    }

    private Object unary(Unary u, Frame f) {
        Object v = eval(u.e, f);
        switch (u.op) {
            case ScriptAst.U_NOT: return truthy(v) ? Boolean.FALSE : Boolean.TRUE;
            case ScriptAst.U_NEG:
                if (v instanceof Long) return Long.valueOf(-((Long) v).longValue());
                if (v instanceof Double) return Double.valueOf(-((Double) v).doubleValue());
                if (v instanceof Boolean) return Long.valueOf(((Boolean) v).booleanValue() ? -1 : 0);
                break;
            case ScriptAst.U_POS:
                if (v instanceof Long || v instanceof Double) return v;
                if (v instanceof Boolean) return Long.valueOf(((Boolean) v).booleanValue() ? 1 : 0);
                break;
            case ScriptAst.U_INV:
                if (v instanceof Long) return Long.valueOf(~((Long) v).longValue());
                break;
            default: break;
        }
        throw err(f, u, "TypeError", "bad operand type for unary " + (u.op == ScriptAst.U_NEG ? "-" : (u.op == ScriptAst.U_POS ? "+" : "~")) + ": '" + ScriptValues.typeName(v) + "'");
    }

    // ------------------------------------------------------------ arithmetic
    static boolean isNum(Object o) { return o instanceof Long || o instanceof Double || o instanceof Boolean; }
    static boolean isInt(Object o) { return o instanceof Long || o instanceof Boolean; }
    static long toL(Object o) { return o instanceof Boolean ? (((Boolean) o).booleanValue() ? 1 : 0) : ((Long) o).longValue(); }
    static double toD(Object o) { return o instanceof Double ? ((Double) o).doubleValue() : (double) toL(o); }

    private static final String[] OPSYM = {"", "+", "-", "*", "/", "//", "%", "**", "&", "|", "^", "<<", ">>"};

    Object binop(int op, Object a, Object b, Frame f, Node at) {
        if (isNum(a) && isNum(b)) {
            if (isInt(a) && isInt(b)) {
                long x = toL(a), y = toL(b);
                switch (op) {
                    case ScriptAst.ADD: { long r = x + y; if (((x ^ r) & (y ^ r)) < 0) return Double.valueOf((double) x + (double) y); return Long.valueOf(r); }
                    case ScriptAst.SUB: { long r = x - y; if (((x ^ y) & (x ^ r)) < 0) return Double.valueOf((double) x - (double) y); return Long.valueOf(r); }
                    case ScriptAst.MUL: {
                        if (x == 0 || y == 0) return Long.valueOf(0);
                        long r = x * y;
                        if (r / y != x || (x == Long.MIN_VALUE && y == -1) || (y == Long.MIN_VALUE && x == -1)) return Double.valueOf((double) x * (double) y);
                        return Long.valueOf(r);
                    }
                    case ScriptAst.DIV: if (y == 0) throw err(f, at, "ZeroDivisionError", "division by zero").hint("check the divisor before dividing"); return Double.valueOf((double) x / (double) y);
                    case ScriptAst.FDIV: {
                        if (y == 0) throw err(f, at, "ZeroDivisionError", "integer division by zero");
                        long q = x / y; if ((x % y != 0) && ((x < 0) != (y < 0))) q--; return Long.valueOf(q);
                    }
                    case ScriptAst.MOD: {
                        if (y == 0) throw err(f, at, "ZeroDivisionError", "modulo by zero");
                        long m = x % y; if (m != 0 && ((m < 0) != (y < 0))) m += y; return Long.valueOf(m);
                    }
                    case ScriptAst.POW: {
                        if (y < 0) { if (x == 0) throw err(f, at, "ZeroDivisionError", "0 cannot be raised to a negative power"); return Double.valueOf(Math.pow(x, y)); }
                        long r = 1, base = x; long ex = y; boolean over = false;
                        while (ex > 0) {
                            if ((ex & 1) == 1) { long nr = r * base; if (base != 0 && (nr / base != r)) { over = true; break; } r = nr; }
                            ex >>= 1;
                            if (ex > 0) { long nb = base * base; if (base != 0 && nb / base != base) { over = true; break; } base = nb; }
                        }
                        if (over) return Double.valueOf(Math.pow(x, y));
                        return Long.valueOf(r);
                    }
                    case ScriptAst.BAND: return Long.valueOf(x & y);
                    case ScriptAst.BOR: return Long.valueOf(x | y);
                    case ScriptAst.BXOR: return Long.valueOf(x ^ y);
                    case ScriptAst.SHL: if (y < 0) throw err(f, at, "ValueError", "negative shift count"); return Long.valueOf(y > 62 ? 0 : x << y);
                    case ScriptAst.SHR: if (y < 0) throw err(f, at, "ValueError", "negative shift count"); return Long.valueOf(y > 62 ? (x < 0 ? -1 : 0) : x >> y);
                    default: break;
                }
            }
            double x = toD(a), y = toD(b);
            switch (op) {
                case ScriptAst.ADD: return Double.valueOf(x + y);
                case ScriptAst.SUB: return Double.valueOf(x - y);
                case ScriptAst.MUL: return Double.valueOf(x * y);
                case ScriptAst.DIV: if (y == 0) throw err(f, at, "ZeroDivisionError", "float division by zero"); return Double.valueOf(x / y);
                case ScriptAst.FDIV: if (y == 0) throw err(f, at, "ZeroDivisionError", "float floor division by zero"); return Double.valueOf(Math.floor(x / y));
                case ScriptAst.MOD: { if (y == 0) throw err(f, at, "ZeroDivisionError", "float modulo by zero"); double m = x % y; if (m != 0 && ((m < 0) != (y < 0))) m += y; return Double.valueOf(m); }
                case ScriptAst.POW: if (x == 0 && y < 0) throw err(f, at, "ZeroDivisionError", "0.0 cannot be raised to a negative power"); return Double.valueOf(Math.pow(x, y));
                default: break;
            }
        } else {
            switch (op) {
                case ScriptAst.ADD:
                    if (a instanceof String && b instanceof String) return ((String) a).concat((String) b);
                    if (a instanceof PyList && b instanceof PyList) {
                        PyList x = (PyList) a, y = (PyList) b;
                        if (x.tuple != y.tuple) break;
                        PyList r = new PyList(x.tuple); r.a.addAll(x.a); r.a.addAll(y.a); return r;
                    }
                    if (a instanceof String) throw err(f, at, "TypeError", "can only join text with text, not " + ScriptValues.typeName(b)).hint("convert it first: str(" + "value" + ") or use an f-string like f\"text {value}\"");
                    break;
                case ScriptAst.MUL: {
                    if (a instanceof String && isInt(b)) return repeat((String) a, toL(b), f, at);
                    if (b instanceof String && isInt(a)) return repeat((String) b, toL(a), f, at);
                    if (a instanceof PyList && isInt(b)) return repeatList((PyList) a, toL(b), f, at);
                    if (b instanceof PyList && isInt(a)) return repeatList((PyList) b, toL(a), f, at);
                    break;
                }
                case ScriptAst.MOD: if (a instanceof String) return ScriptLib.percentFormat((String) a, b, this, f, at); break;
                case ScriptAst.BOR: case ScriptAst.BAND: case ScriptAst.SUB: case ScriptAst.BXOR:
                    if (a instanceof PySet && b instanceof PySet) {
                        PySet r = new PySet(); PySet x = (PySet) a, y = (PySet) b;
                        if (op == ScriptAst.BOR) { r.s.addAll(x.s); r.s.addAll(y.s); }
                        else if (op == ScriptAst.BAND) { for (Object o : x.s) if (y.s.contains(o)) r.s.add(o); }
                        else if (op == ScriptAst.SUB) { for (Object o : x.s) if (!y.s.contains(o)) r.s.add(o); }
                        else { for (Object o : x.s) if (!y.s.contains(o)) r.s.add(o); for (Object o : y.s) if (!x.s.contains(o)) r.s.add(o); }
                        return r;
                    }
                    break;
                default: break;
            }
        }
        throw err(f, at, "TypeError", "unsupported operand type(s) for " + OPSYM[op] + ": '" + ScriptValues.typeName(a) + "' and '" + ScriptValues.typeName(b) + "'");
    }

    private Object repeat(String s, long n, Frame f, Node at) {
        if (n <= 0) return "";
        if ((long) s.length() * n > 2000000L) throw err(f, at, "MemoryError", "string too large");
        StringBuilder sb = new StringBuilder((int) (s.length() * n));
        for (long i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
    private Object repeatList(PyList l, long n, Frame f, Node at) {
        PyList r = new PyList(l.tuple);
        if (n <= 0) return r;
        if ((long) l.a.size() * n > 3000000L) throw err(f, at, "MemoryError", "list too large");
        for (long i = 0; i < n; i++) r.a.addAll(l.a);
        return r;
    }

    // ------------------------------------------------------------ comparison
    static boolean valEq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (isNum(a) && isNum(b)) {
            if (isInt(a) && isInt(b)) return toL(a) == toL(b);
            return toD(a) == toD(b);
        }
        if (a instanceof String) return a.equals(b);
        if (a instanceof PyList && b instanceof PyList) {
            PyList x = (PyList) a, y = (PyList) b;
            if (x.tuple != y.tuple || x.a.size() != y.a.size()) return false;
            for (int i = 0; i < x.a.size(); i++) if (!valEq(x.a.get(i), y.a.get(i))) return false;
            return true;
        }
        if (a instanceof PyDict && b instanceof PyDict) {
            PyDict x = (PyDict) a, y = (PyDict) b;
            if (x.m.size() != y.m.size()) return false;
            for (Map.Entry<Object, Object> en : x.m.entrySet()) { if (!y.m.containsKey(en.getKey()) || !valEq(en.getValue(), y.m.get(en.getKey()))) return false; }
            return true;
        }
        if (a instanceof PySet && b instanceof PySet) return ((PySet) a).s.equals(((PySet) b).s);
        if (a instanceof PyRange && b instanceof PyRange) { PyRange x = (PyRange) a, y = (PyRange) b; return x.start == y.start && x.stop == y.stop && x.step == y.step; }
        return false;
    }

    int cmp(Object a, Object b, Frame f, Node at, String sym) {
        if (isNum(a) && isNum(b)) {
            if (isInt(a) && isInt(b)) { long x = toL(a), y = toL(b); return x < y ? -1 : (x > y ? 1 : 0); }
            double x = toD(a), y = toD(b); return x < y ? -1 : (x > y ? 1 : (x == y ? 0 : 2));
        }
        if (a instanceof String && b instanceof String) { int c = ((String) a).compareTo((String) b); return c < 0 ? -1 : (c > 0 ? 1 : 0); }
        if (a instanceof PyList && b instanceof PyList) {
            PyList x = (PyList) a, y = (PyList) b;
            int n = Math.min(x.a.size(), y.a.size());
            for (int i = 0; i < n; i++) { if (!valEq(x.a.get(i), y.a.get(i))) return cmp(x.a.get(i), y.a.get(i), f, at, sym); }
            return x.a.size() < y.a.size() ? -1 : (x.a.size() > y.a.size() ? 1 : 0);
        }
        throw err(f, at, "TypeError", "'" + sym + "' not supported between '" + ScriptValues.typeName(a) + "' and '" + ScriptValues.typeName(b) + "'").hint(a instanceof String || b instanceof String ? "convert with int(...) or str(...) first" : (a == null || b == null ? "one of the values is None - did a function return nothing?" : null));
    }

    boolean compare(int op, Object a, Object b, Frame f, Node at) {
        switch (op) {
            case ScriptAst.EQ: return valEq(a, b);
            case ScriptAst.NE: return !valEq(a, b);
            case ScriptAst.LT: return cmp(a, b, f, at, "<") == -1;
            case ScriptAst.GT: return cmp(a, b, f, at, ">") == 1;
            case ScriptAst.LE: { int c = cmp(a, b, f, at, "<="); return c == -1 || c == 0; }
            case ScriptAst.GE: { int c = cmp(a, b, f, at, ">="); return c == 1 || c == 0; }
            case ScriptAst.IS: return identical(a, b);
            case ScriptAst.ISNOT: return !identical(a, b);
            case ScriptAst.IN: return contains(b, a, f, at);
            case ScriptAst.NOTIN: return !contains(b, a, f, at);
            default: return false;
        }
    }

    static boolean identical(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Boolean || a instanceof Long) return a.equals(b);
        return false;
    }

    boolean contains(Object container, Object item, Frame f, Node at) {
        if (container instanceof PyList) { ArrayList<Object> l = ((PyList) container).a; for (int i = 0; i < l.size(); i++) if (valEq(l.get(i), item)) return true; return false; }
        if (container instanceof String) {
            if (!(item instanceof String)) throw err(f, at, "TypeError", "'in <string>' requires a string on the left, not " + ScriptValues.typeName(item));
            return ((String) container).indexOf((String) item) >= 0;
        }
        if (container instanceof PyDict) return ((PyDict) container).m.containsKey(ScriptLib.normKey(item, this, f, at));
        if (container instanceof PySet) return ((PySet) container).s.contains(ScriptLib.normKey(item, this, f, at));
        if (container instanceof PyRange) {
            PyRange r = (PyRange) container;
            if (!isNum(item)) return false;
            double d = toD(item); if (d != Math.rint(d)) return false;
            long v = (long) d;
            if (r.step > 0 ? (v < r.start || v >= r.stop) : (v > r.start || v <= r.stop)) return false;
            return (v - r.start) % r.step == 0;
        }
        throw err(f, at, "TypeError", "argument of type '" + ScriptValues.typeName(container) + "' is not iterable");
    }

    // ------------------------------------------------------------ iteration
    abstract static class Iter { abstract boolean hasNext(); abstract Object next(); }

    Iter iterate(Object o, final Frame f, final Node at) {
        if (o instanceof PyList) {
            final ArrayList<Object> l = ((PyList) o).a;
            return new Iter() { int i; boolean hasNext() { return i < l.size(); } Object next() { return l.get(i++); } };
        }
        if (o instanceof PyRange) {
            final PyRange r = (PyRange) o; final long n = r.size();
            return new Iter() { long i; boolean hasNext() { return i < n; } Object next() { return Long.valueOf(r.at(i++)); } };
        }
        if (o instanceof String) {
            final String s = (String) o;
            return new Iter() { int i; boolean hasNext() { return i < s.length(); } Object next() { return String.valueOf(s.charAt(i++)); } };
        }
        if (o instanceof PyDict) {
            final Object[] keys = ((PyDict) o).m.keySet().toArray();
            return new Iter() { int i; boolean hasNext() { return i < keys.length; } Object next() { return keys[i++]; } };
        }
        if (o instanceof PySet) {
            final Object[] keys = ((PySet) o).s.toArray();
            return new Iter() { int i; boolean hasNext() { return i < keys.length; } Object next() { return keys[i++]; } };
        }
        throw err(f, at, "TypeError", "'" + ScriptValues.typeName(o) + "' object is not iterable").hint(o instanceof Long ? "to repeat n times use  for i in range(n):" : (o == null ? "the value is None - did a function return nothing?" : null));
    }

    ArrayList<Object> toList(Object o, Frame f, Node at) {
        if (o instanceof PyList) return new ArrayList<Object>(((PyList) o).a);
        ArrayList<Object> r = new ArrayList<Object>();
        if (o instanceof PyRange && ((PyRange) o).size() > 5000000L) throw err(f, at, "MemoryError", "range is too large to turn into a list");
        Iter it = iterate(o, f, at);
        while (it.hasNext()) r.add(it.next());
        return r;
    }

    // ------------------------------------------------------------ indexing / attributes
    Object getIndex(Object o, Object key, Frame f, Node at) {
        if (o instanceof PyList) {
            PyList l = (PyList) o;
            if (key instanceof Boolean) key = Long.valueOf(toL(key));
            if (key instanceof PyList) throw err(f, at, "TypeError", "list indices must be integers, not tuple");
            return l.a.get((int) ScriptLib.index(key, l.a.size(), this, f, at, l.tuple ? "tuple" : "list"));
        }
        if (o instanceof String) {
            String s = (String) o;
            return String.valueOf(s.charAt((int) ScriptLib.index(key, s.length(), this, f, at, "string")));
        }
        if (o instanceof PyDict) {
            Object k = ScriptLib.normKey(key, this, f, at);
            PyDict d = (PyDict) o;
            Object v = d.m.get(k);
            if (v == null && !d.m.containsKey(k)) {
                ScriptError e = err(f, at, "KeyError", ScriptLib.repr(key));
                if (key instanceof String) {
                    ArrayList<String> ks = new ArrayList<String>();
                    for (Object kk : d.m.keySet()) if (kk instanceof String) ks.add((String) kk);
                    String sg = ScriptLib.suggest((String) key, ks);
                    if (sg != null) e.hint("did you mean '" + sg + "'?"); else e.hint("use d.get(key, default) to avoid this error");
                } else e.hint("use d.get(key, default) to avoid this error");
                throw e;
            }
            return v;
        }
        if (o instanceof PyRange) {
            PyRange r = (PyRange) o;
            return Long.valueOf(r.at(ScriptLib.index(key, (int) r.size(), this, f, at, "range")));
        }
        throw err(f, at, "TypeError", "'" + ScriptValues.typeName(o) + "' object is not subscriptable").hint(o == null ? "the value is None - did a function return nothing?" : null);
    }

    void setIndex(Object o, Object key, Object v, Frame f, Node at) {
        if (o instanceof PyList && !((PyList) o).tuple) {
            PyList l = (PyList) o;
            l.a.set((int) ScriptLib.index(key, l.a.size(), this, f, at, "list"), v);
            return;
        }
        if (o instanceof PyDict) { ((PyDict) o).m.put(ScriptLib.normKey(key, this, f, at), v); return; }
        throw err(f, at, "TypeError", "'" + ScriptValues.typeName(o) + "' object does not support item assignment").hint(o instanceof PyList ? "tuples cannot be changed; use a list" : (o instanceof String ? "strings cannot be changed; build a new string" : null));
    }

    private Object evalSlice(Slice s, Frame f) {
        Object o = eval(s.obj, f);
        Object lo = s.lo == null ? null : eval(s.lo, f), hi = s.hi == null ? null : eval(s.hi, f), st = s.step == null ? null : eval(s.step, f);
        return ScriptLib.slice(o, lo, hi, st, this, f, s);
    }

    Object getAttr(Object o, String name, Frame f, Node at) {
        if (o instanceof ScriptObject) {
            ScriptObject so = (ScriptObject) o;
            if (so.hasAttr(name)) return so.getAttr(name);
            ScriptError e = err(f, at, "AttributeError", "'" + so.typeName() + "' object has no attribute '" + name + "'");
            String sg = ScriptLib.suggest(name, java.util.Arrays.asList(so.attrNames()));
            e.hint(sg != null ? "did you mean '" + sg + "'?" : "available: " + joinNames(so.attrNames()));
            throw e;
        }
        if (ScriptLib.hasMethod(o, name)) return new BoundMethod(o, name);
        ScriptError e = err(f, at, "AttributeError", "'" + ScriptValues.typeName(o) + "' object has no attribute '" + name + "'");
        String[] ms = ScriptLib.methodNames(o);
        if (ms != null) { String sg = ScriptLib.suggest(name, java.util.Arrays.asList(ms)); e.hint(sg != null ? "did you mean '" + sg + "'?" : "available: " + joinNames(ms)); }
        else if (o == null) e.hint("the value is None - did a function return nothing?");
        throw e;
    }

    private static String joinNames(String[] ns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ns.length && i < 14; i++) { if (i > 0) sb.append(", "); sb.append(ns[i]); }
        if (ns.length > 14) sb.append(", ...");
        return sb.toString();
    }

    void setAttr(Object o, String name, Object v, Frame f, Node at) {
        if (o instanceof ModuleObj) { ((ModuleObj) o).env.vars.put(name, v); return; }
        throw err(f, at, "AttributeError", "cannot change attribute '" + name + "' of a " + ScriptValues.typeName(o));
    }

    // ------------------------------------------------------------ calls
    private Object evalCall(Call c, Frame f) {
        Object fn = eval(c.fn, f);
        ArrayList<Object> args = new ArrayList<Object>(c.args.length);
        for (int i = 0; i < c.args.length; i++) {
            if (c.star[i]) { Iter it = iterate(eval(c.args[i], f), f, c.args[i]); while (it.hasNext()) args.add(it.next()); }
            else args.add(eval(c.args[i], f));
        }
        int nk = c.kwNames.length;
        String[] kn = c.kwNames; Object[] kv = new Object[nk];
        for (int i = 0; i < nk; i++) kv[i] = eval(c.kwVals[i], f);
        if (c.kwStar != null) {
            Object d = eval(c.kwStar, f);
            if (!(d instanceof PyDict)) throw err(f, c, "TypeError", "argument after ** must be a dict");
            ArrayList<String> ns = new ArrayList<String>(java.util.Arrays.asList(kn)); ArrayList<Object> vs = new ArrayList<Object>(java.util.Arrays.asList(kv));
            for (Map.Entry<Object, Object> en : ((PyDict) d).m.entrySet()) {
                if (!(en.getKey() instanceof String)) throw err(f, c, "TypeError", "keywords must be strings");
                ns.add((String) en.getKey()); vs.add(en.getValue());
            }
            kn = ns.toArray(new String[ns.size()]); kv = vs.toArray();
        }
        f.line = c.line > 0 ? c.line : f.line;
        return call(fn, args.toArray(), kn, kv, f, c);
    }

    /** Convenience for natives that call back into script functions (sorted key=, map, ...). */
    Object callSimple(Object fn, Frame f, Node at, Object... args) { return call(fn, args, new String[0], new Object[0], f, at); }

    Object call(Object fn, Object[] args, String[] kn, Object[] kv, Frame f, Node at) {
        if (fn instanceof Fn) return callFn((Fn) fn, args, kn, kv, f, at);
        if (fn instanceof Native) return callNative((Native) fn, args, kn, kv, f, at);
        if (fn instanceof BoundMethod) {
            BoundMethod bm = (BoundMethod) fn;
            if (kn.length > 0 && !ScriptLib.methodTakesKw(bm.name)) throw err(f, at, "TypeError", bm.name + "() does not take keyword arguments");
            try { return ScriptLib.invoke(this, f, bm.self, bm.name, args, kn, kv, at); }
            catch (ScriptError e) { throw locate(e, f, at); }
        }
        ScriptError e = err(f, at, "TypeError", "'" + ScriptValues.typeName(fn) + "' object is not callable");
        if (at instanceof Call && ((Call) at).fn instanceof Name) {
            e.hint(fn == null ? "'" + ((Name) ((Call) at).fn).id + "' is None" : "'" + ((Name) ((Call) at).fn).id + "' is a value, not a function - remove the ( )");
        }
        throw e;
    }

    private Object callFn(Fn fn, Object[] args, String[] kn, Object[] kv, Frame f, Node at) {
        FuncDef d = fn.def;
        Ctx cx = f.cx;
        if (cx.depth >= MAX_DEPTH) throw err(f, at, "RecursionError", "maximum recursion depth exceeded").hint("a function keeps calling itself without stopping - add a base case");
        Env env = new Env(fn.closure, d, false);
        int np = d.params.length, ai = 0;
        Object[] bound = new Object[np];
        boolean[] has = new boolean[np];
        PyList starList = null; PyDict kwDict = null; int starIdx = -1, dstarIdx = -1;
        for (int i = 0; i < np; i++) {
            Param p = d.params[i];
            if (p.star) { starIdx = i; starList = new PyList(true); continue; }
            if (p.dstar) { dstarIdx = i; kwDict = new PyDict(); continue; }
            if (ai < args.length) { bound[i] = args[ai++]; has[i] = true; }
        }
        if (ai < args.length) {
            if (starList != null) { for (; ai < args.length; ai++) starList.a.add(args[ai]); }
            else throw err(f, at, "TypeError", d.name + "() takes " + (d.minArgs == d.maxArgs ? "" + d.maxArgs : "from " + d.minArgs + " to " + d.maxArgs) + " argument" + (d.maxArgs == 1 ? "" : "s") + " but " + args.length + " " + (args.length == 1 ? "was" : "were") + " given");
        }
        for (int k = 0; k < kn.length; k++) {
            int idx = -1;
            for (int i = 0; i < np; i++) if (!d.params[i].star && !d.params[i].dstar && d.params[i].name.equals(kn[k])) idx = i;
            if (idx >= 0) {
                if (has[idx]) throw err(f, at, "TypeError", d.name + "() got multiple values for argument '" + kn[k] + "'");
                bound[idx] = kv[k]; has[idx] = true;
            } else if (kwDict != null) kwDict.m.put(kn[k], kv[k]);
            else {
                ArrayList<String> pn = new ArrayList<String>();
                for (int i = 0; i < np; i++) pn.add(d.params[i].name);
                ScriptError e = err(f, at, "TypeError", d.name + "() got an unexpected keyword argument '" + kn[k] + "'");
                String sg = ScriptLib.suggest(kn[k], pn);
                if (sg != null) e.hint("did you mean '" + sg + "'?"); else if (np > 0) e.hint("parameters: " + joinParams(d));
                throw e;
            }
        }
        for (int i = 0; i < np; i++) {
            Param p = d.params[i];
            if (p.star) { env.vars.put(p.name, starList); continue; }
            if (p.dstar) { env.vars.put(p.name, kwDict); continue; }
            if (has[i]) env.vars.put(p.name, bound[i]);
            else if (fn.defaults[i] != ScriptValues.NOARG) env.vars.put(p.name, fn.defaults[i]);
            else {
                ArrayList<String> miss = new ArrayList<String>();
                for (int j = 0; j < np; j++) if (!has[j] && !d.params[j].star && !d.params[j].dstar && fn.defaults[j] == ScriptValues.NOARG) miss.add("'" + d.params[j].name + "'");
                throw err(f, at, "TypeError", d.name + "() missing " + miss.size() + " required argument" + (miss.size() == 1 ? "" : "s") + ": " + ScriptLib.joinList(miss)).hint("signature: " + d.name + "(" + joinParams(d) + ")");
            }
        }
        Frame nf = new Frame(env, cx, d.name, d.file, f);
        nf.line = d.line;
        cx.depth++;
        try {
            int code = execBlock(d.body, nf);
            return code == RETURN ? nf.ret : null;
        } catch (StackOverflowError so) {
            throw err(nf, at, "RecursionError", "maximum recursion depth exceeded");
        } finally { cx.depth--; }
    }

    private static String joinParams(FuncDef d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.params.length; i++) { if (i > 0) sb.append(", "); Param p = d.params[i]; sb.append(p.dstar ? "**" : (p.star ? "*" : "")).append(p.name); if (p.def != null) sb.append("=..."); }
        return sb.toString();
    }

    Object callNative(Native n, Object[] args, String[] kn, Object[] kv, Frame f, Node at) {
        try {
            if (n.raw) {
                Map<String, Object> kw = null;
                if (kn.length > 0) { kw = new LinkedHashMap<String, Object>(); for (int i = 0; i < kn.length; i++) kw.put(kn[i], kv[i]); }
                return n.callRaw(f, this, args, kw);
            }
            int np = n.params.length;
            if (args.length > np) throw err(f, at, "TypeError", n.name + "() takes " + (n.minArgs == np ? "" + np : "at most " + np) + " argument" + (np == 1 ? "" : "s") + " but " + args.length + " " + (args.length == 1 ? "was" : "were") + " given").hint("usage: " + n.name + "(" + n.sig + ")");
            Object[] slots = new Object[np];
            for (int i = 0; i < np; i++) slots[i] = i < args.length ? args[i] : ScriptValues.NOARG;
            for (int k = 0; k < kn.length; k++) {
                int idx = -1;
                for (int i = 0; i < np; i++) if (n.params[i].equals(kn[k])) idx = i;
                if (idx < 0) {
                    ScriptError e = err(f, at, "TypeError", n.name + "() got an unexpected keyword argument '" + kn[k] + "'");
                    String sg = ScriptLib.suggest(kn[k], java.util.Arrays.asList(n.params));
                    e.hint(sg != null ? "did you mean '" + sg + "'?" : "usage: " + n.name + "(" + n.sig + ")");
                    throw e;
                }
                if (idx < args.length) throw err(f, at, "TypeError", n.name + "() got multiple values for argument '" + kn[k] + "'");
                slots[idx] = kv[k];
            }
            for (int i = 0; i < np; i++) if (slots[i] == ScriptValues.NOARG && !n.optional[i])
                throw err(f, at, "TypeError", n.name + "() missing required argument '" + n.params[i] + "'").hint("usage: " + n.name + "(" + n.sig + ")");
            return n.call(f, this, slots);
        } catch (ScriptError e) {
            throw locate(e, f, at);
        }
    }
}
