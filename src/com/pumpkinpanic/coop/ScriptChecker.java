package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.pumpkinpanic.coop.ScriptAst.*;
import com.pumpkinpanic.coop.ScriptValues.*;

/**
 * Static analysis: finds syntax errors, misspelled names, wrong argument counts, forgotten parentheses,
 * and loops that never wait - before the script runs. Java 7 compatible.
 */
final class ScriptChecker {
    private final ScriptInterp known;
    private final ScriptInterp.Loader loader;
    private final ArrayList<ScriptError> out = new ArrayList<ScriptError>();
    private final HashSet<String> seen = new HashSet<String>();
    private Program prog;
    private String file;
    private final HashMap<String, ArrayList<FuncDef>> topDefs = new HashMap<String, ArrayList<FuncDef>>();
    private final HashMap<String, Integer> globalBinds = new HashMap<String, Integer>();
    private boolean counting;

    private static final class Sc { final FuncDef fn; final Sc parent; HashSet<String> comp; Sc(FuncDef f, Sc p) { fn = f; parent = p; } }

    private ScriptChecker(ScriptInterp known, ScriptInterp.Loader loader) { this.known = known; this.loader = loader; }

    static ArrayList<ScriptError> check(String source, String name, ScriptInterp known, ScriptInterp.Loader loader) {
        ScriptChecker c = new ScriptChecker(known, loader);
        c.run(source, name, 0);
        Collections.sort(c.out, new Comparator<ScriptError>() {
            public int compare(ScriptError a, ScriptError b) {
                if (a.severity != b.severity) return a.severity - b.severity;
                return a.line - b.line;
            }
        });
        return c.out;
    }

    private void add(ScriptError e) {
        e.in(file);
        String key = e.severity + ":" + e.file + ":" + e.line + ":" + e.msg;
        if (seen.add(key)) out.add(e);
    }
    private void error(Node n, String type, String msg, String hint) { ScriptError e = new ScriptError(type, msg, n.line, n.col); if (hint != null) e.hint(hint); add(e); }
    private void warn(Node n, String msg, String hint) { ScriptError e = new ScriptError("Warning", msg, n.line, n.col).warn(); if (hint != null) e.hint(hint); add(e); }

    // ------------------------------------------------------------ entry
    private void run(String source, String name, int depth) {
        String savedFile = file;
        Program savedProg = prog;
        HashMap<String, ArrayList<FuncDef>> savedDefs = new HashMap<String, ArrayList<FuncDef>>(topDefs);
        HashMap<String, Integer> savedBinds = new HashMap<String, Integer>(globalBinds);
        file = name;
        try {
            prog = ScriptParser.parse(source, name);
        } catch (ScriptError e) {
            add(e);
            file = savedFile; prog = savedProg;
            return;
        }
        topDefs.clear(); globalBinds.clear();
        for (int i = 0; i < prog.body.length; i++) if (prog.body[i].kind == ScriptAst.S_DEF) { FuncDef d = (FuncDef) prog.body[i]; ArrayList<FuncDef> l = topDefs.get(d.name); if (l == null) { l = new ArrayList<FuncDef>(); topDefs.put(d.name, l); } l.add(d); }
        counting = true; walkBlock(prog.body, new Sc(null, null));
        counting = false; walkBlock(prog.body, new Sc(null, null));
        file = savedFile; prog = savedProg;
        topDefs.clear(); topDefs.putAll(savedDefs); globalBinds.clear(); globalBinds.putAll(savedBinds);
    }

    // ------------------------------------------------------------ scopes
    private boolean definedIn(String n, Sc sc) {
        for (Sc c = sc; c != null; c = c.parent) {
            if (c.comp != null && c.comp.contains(n)) return true;
            if (c.fn != null && (c.fn.locals.contains(n) || c.fn.nonlocals.contains(n))) return true;
        }
        return prog.globalNames.contains(n);
    }
    private boolean shadowed(String n, Sc sc) { return definedIn(n, sc); }

    private ArrayList<String> visible(Sc sc) {
        ArrayList<String> r = new ArrayList<String>(prog.globalNames);
        for (Sc c = sc; c != null; c = c.parent) { if (c.comp != null) r.addAll(c.comp); if (c.fn != null) r.addAll(c.fn.locals); }
        r.addAll(known.builtins.keySet());
        return r;
    }

    // ------------------------------------------------------------ statements
    private void walkBlock(Stmt[] b, Sc sc) { for (int i = 0; i < b.length; i++) walkStmt(b[i], sc); }

    private void bound(String name, Sc sc, Node at) {
        if (!counting) return;
        Sc fnSc = sc; while (fnSc != null && fnSc.fn == null) fnSc = fnSc.parent;
        boolean global = fnSc == null || fnSc.fn.globals.contains(name);
        if (global) { Integer c = globalBinds.get(name); globalBinds.put(name, c == null ? 1 : c + 1); }
    }
    private void boundTarget(Expr e, Sc sc) {
        if (e.kind == ScriptAst.E_NAME) bound(((Name) e).id, sc, e);
        else if (e.kind == ScriptAst.E_LIST) for (Expr x : ((ListE) e).items) boundTarget(x, sc);
        else if (e.kind == ScriptAst.E_STAR) boundTarget(((Star) e).e, sc);
    }

    private void checkBuiltinShadow(Expr target, Sc sc) {
        if (counting) return;
        if (target.kind == ScriptAst.E_NAME) {
            String n = ((Name) target).id;
            if (known.builtins.containsKey(n) && !n.equals("pi") && !n.equals("tau") && !n.equals("inf")) {
                Object b = known.builtins.get(n);
                if (b instanceof Native) {
                    Native nat = (Native) b;
                    boolean important = nat.category.equals("Python basics") || nat.category.equals("Math") || nat.category.equals("Errors") || ScriptApi.isBlocking(n);
                    if (important) warn(target, "'" + n + "' is a built-in function and assigning to it hides it", "pick another name, e.g. my_" + n);
                }
                else if (b instanceof ScriptObject || b instanceof Boolean) warn(target, "'" + n + "' is a built-in constant and assigning to it hides it", "pick another name");
            }
        } else if (target.kind == ScriptAst.E_LIST) for (Expr x : ((ListE) target).items) checkBuiltinShadow(x, sc);
    }

    private void walkStmt(Stmt s, Sc sc) {
        switch (s.kind) {
            case ScriptAst.S_EXPR: {
                Expr e = ((ExprStmt) s).e;
                walkExpr(e, sc);
                if (!counting && (e.kind == ScriptAst.E_NAME || e.kind == ScriptAst.E_ATTR)) {
                    if (e.kind == ScriptAst.E_NAME) {
                        String n = ((Name) e).id;
                        boolean isFn = (!shadowed(n, sc) && known.builtins.get(n) instanceof Native) || (topDefs.containsKey(n) && !shadowedByLocal(n, sc));
                        if (isFn) warn(e, "'" + n + "' does nothing without parentheses", "did you mean " + n + "()?");
                        else if (known.builtins.get(n) != null && !shadowed(n, sc)) warn(e, "this line has no effect", null);
                    }
                }
                return;
            }
            case ScriptAst.S_ASSIGN: {
                Assign a = (Assign) s;
                walkExpr(a.value, sc);
                for (int i = 0; i < a.targets.length; i++) { walkTarget(a.targets[i], sc); boundTarget(a.targets[i], sc); checkBuiltinShadow(a.targets[i], sc); checkShadowGlobal(a.targets[i], sc, false); }
                return;
            }
            case ScriptAst.S_AUG: {
                AugAssign a = (AugAssign) s;
                walkExpr(a.value, sc);
                if (a.target.kind == ScriptAst.E_NAME) {
                    String n = ((Name) a.target).id;
                    walkExpr(a.target, sc);
                    boundTarget(a.target, sc);
                    Sc fnSc = sc; while (fnSc != null && fnSc.fn == null) fnSc = fnSc.parent;
                    if (!counting && fnSc != null && fnSc.fn.locals.contains(n) && !isParam(fnSc.fn, n) && !hasPlainBinding(fnSc.fn, n)) {
                        ScriptError e = new ScriptError("UnboundLocalError", "'" + n + "' is changed inside function " + fnSc.fn.name + "() but has no value there", s.line, s.col);
                        e.hint(prog.globalNames.contains(n) ? "to change the global '" + n + "' write  global " + n + "  at the top of the function" : "give '" + n + "' a value before using +=");
                        add(e);
                    }
                } else { walkExpr(a.target, sc); }
                return;
            }
            case ScriptAst.S_IF: { If n = (If) s; for (int i = 0; i < n.tests.length; i++) { walkExpr(n.tests[i], sc); checkCondition(n.tests[i], sc); walkBlock(n.blocks[i], sc); } if (n.orelse != null) walkBlock(n.orelse, sc); return; }
            case ScriptAst.S_WHILE: {
                While n = (While) s;
                walkExpr(n.test, sc); checkCondition(n.test, sc);
                walkBlock(n.body, sc);
                if (n.orelse != null) walkBlock(n.orelse, sc);
                if (!counting && isConstTrue(n.test) && !loopMayWait(n.body, sc)) warn(n, "this loop never waits or acts, so it will run at full speed and freeze your script", "add an action or wait(0.5) inside the loop");
                return;
            }
            case ScriptAst.S_FOR: {
                For n = (For) s;
                walkExpr(n.iter, sc); walkTarget(n.target, sc); boundTarget(n.target, sc); checkBuiltinShadow(n.target, sc);
                walkBlock(n.body, sc); if (n.orelse != null) walkBlock(n.orelse, sc);
                return;
            }
            case ScriptAst.S_DEF: {
                FuncDef d = (FuncDef) s;
                if (counting && sc.fn == null) bound(d.name, sc, d);
                else if (counting) bound(d.name, sc, d);
                for (int i = 0; i < d.params.length; i++) if (d.params[i].def != null) walkExpr(d.params[i].def, sc);
                Sc inner = new Sc(d, sc);
                walkBlock(d.body, inner);
                return;
            }
            case ScriptAst.S_RETURN: { Return r = (Return) s; if (r.value != null) walkExpr(r.value, sc); return; }
            case ScriptAst.S_TRY: {
                Try t = (Try) s;
                walkBlock(t.body, sc);
                for (int i = 0; i < t.handlers.length; i++) {
                    Handler h = t.handlers[i];
                    if (h.type != null) walkExpr(h.type, sc);
                    if (h.name != null) bound(h.name, sc, s);
                    walkBlock(h.body, sc);
                }
                if (t.orelse != null) walkBlock(t.orelse, sc);
                if (t.fin != null) walkBlock(t.fin, sc);
                return;
            }
            case ScriptAst.S_RAISE: { Raise r = (Raise) s; if (r.e != null) walkExpr(r.e, sc); return; }
            case ScriptAst.S_ASSERT: { Assert a = (Assert) s; walkExpr(a.test, sc); if (a.msg != null) walkExpr(a.msg, sc); return; }
            case ScriptAst.S_IMPORT: { Import im = (Import) s; bound(im.alias, sc, s); checkImport(im.module, s, null); return; }
            case ScriptAst.S_FROM: { FromImport fi = (FromImport) s; for (String a : fi.aliases) bound(a, sc, s); checkImport(fi.module, s, fi.names); return; }
            case ScriptAst.S_DEL: { Del d = (Del) s; for (Expr t : d.targets) walkExpr(t, sc); return; }
            default: return;
        }
    }

    private boolean isParam(FuncDef f, String n) { for (Param p : f.params) if (p.name.equals(n)) return true; return false; }

    /** Is the name given a value in a non-augmented way anywhere in this function? */
    private boolean hasPlainBinding(FuncDef f, String n) {
        if (isParam(f, n)) return true;
        return plainBind(f.body, n);
    }
    private boolean plainBind(Stmt[] b, String n) {
        for (Stmt s : b) {
            switch (s.kind) {
                case ScriptAst.S_ASSIGN: for (Expr t : ((Assign) s).targets) if (targetHas(t, n)) return true; break;
                case ScriptAst.S_FOR: { For f = (For) s; if (targetHas(f.target, n) || plainBind(f.body, n) || (f.orelse != null && plainBind(f.orelse, n))) return true; break; }
                case ScriptAst.S_IF: { If i = (If) s; for (Stmt[] bl : i.blocks) if (plainBind(bl, n)) return true; if (i.orelse != null && plainBind(i.orelse, n)) return true; break; }
                case ScriptAst.S_WHILE: { While w = (While) s; if (plainBind(w.body, n) || (w.orelse != null && plainBind(w.orelse, n))) return true; break; }
                case ScriptAst.S_TRY: { Try t = (Try) s; if (plainBind(t.body, n)) return true; for (Handler h : t.handlers) { if (n.equals(h.name) || plainBind(h.body, n)) return true; } if (t.orelse != null && plainBind(t.orelse, n)) return true; if (t.fin != null && plainBind(t.fin, n)) return true; break; }
                case ScriptAst.S_DEF: if (((FuncDef) s).name.equals(n)) return true; break;
                case ScriptAst.S_IMPORT: if (((Import) s).alias.equals(n)) return true; break;
                case ScriptAst.S_FROM: for (String a : ((FromImport) s).aliases) if (a.equals(n)) return true; break;
                default: break;
            }
        }
        return false;
    }
    private boolean targetHas(Expr t, String n) {
        if (t.kind == ScriptAst.E_NAME) return ((Name) t).id.equals(n);
        if (t.kind == ScriptAst.E_LIST) { for (Expr x : ((ListE) t).items) if (targetHas(x, n)) return true; }
        if (t.kind == ScriptAst.E_STAR) return targetHas(((Star) t).e, n);
        return false;
    }

    private boolean shadowedByLocal(String n, Sc sc) {
        for (Sc c = sc; c != null; c = c.parent) {
            if (c.comp != null && c.comp.contains(n)) return true;
            if (c.fn != null && c.fn.locals.contains(n)) return true;
        }
        return false;
    }

    /** Warn when a plain assignment inside a function silently creates a local that hides a module level variable. */
    private void checkShadowGlobal(Expr target, Sc sc, boolean aug) {
        if (counting || target.kind != ScriptAst.E_NAME) return;
        String n = ((Name) target).id;
        Sc fnSc = sc; while (fnSc != null && fnSc.fn == null) fnSc = fnSc.parent;
        if (fnSc == null) return;
        FuncDef f = fnSc.fn;
        if (!f.locals.contains(n) || isParam(f, n) || f.globals.contains(n)) return;
        Integer gc = globalBinds.get(n);
        if (gc != null && gc > 0 && prog.globalNames.contains(n) && !known.builtins.containsKey(n) && fnSc.parent != null && fnSc.parent.fn == null && !topDefs.containsKey(n)) {
            warn(target, "'" + n + "' inside " + f.name + "() is a new local variable; the module level '" + n + "' stays unchanged", "write  global " + n + "  at the top of the function if you want to change the global");
        }
    }

    private boolean isConstTrue(Expr e) {
        if (e.kind != ScriptAst.E_CONST) return false;
        Object v = ((Const) e).v;
        return ScriptInterp.truthy(v);
    }

    private void checkCondition(Expr e, Sc sc) {
        if (counting) return;
        if (e.kind == ScriptAst.E_NAME) {
            String n = ((Name) e).id;
            boolean isFn = (!shadowed(n, sc) && known.builtins.get(n) instanceof Native) || (topDefs.containsKey(n) && !shadowedByLocal(n, sc));
            if (isFn) warn(e, "'" + n + "' is a function, so this condition is always true", "did you mean " + n + "()?");
        }
    }

    // ------------------------------------------------------------ loop may wait?
    private boolean loopMayWait(Stmt[] body, Sc sc) { for (Stmt s : body) if (stmtMayWait(s, sc)) return true; return false; }

    private boolean stmtMayWait(Stmt s, Sc sc) {
        switch (s.kind) {
            case ScriptAst.S_EXPR: return exprMayWait(((ExprStmt) s).e, sc);
            case ScriptAst.S_ASSIGN: { for (Expr t : ((Assign) s).targets) if (exprMayWait(t, sc)) return true; return exprMayWait(((Assign) s).value, sc); }
            case ScriptAst.S_AUG: return exprMayWait(((AugAssign) s).value, sc);
            case ScriptAst.S_IF: { If n = (If) s; for (Expr t : n.tests) if (exprMayWait(t, sc)) return true; for (Stmt[] b : n.blocks) if (loopMayWait(b, sc)) return true; return n.orelse != null && loopMayWait(n.orelse, sc); }
            case ScriptAst.S_WHILE: { While n = (While) s; return exprMayWait(n.test, sc) || loopMayWait(n.body, sc); }
            case ScriptAst.S_FOR: { For n = (For) s; return exprMayWait(n.iter, sc) || loopMayWait(n.body, sc); }
            case ScriptAst.S_RETURN: case ScriptAst.S_BREAK: case ScriptAst.S_RAISE: return true;   // the loop can end
            case ScriptAst.S_TRY: { Try t = (Try) s; if (loopMayWait(t.body, sc)) return true; for (Handler h : t.handlers) if (loopMayWait(h.body, sc)) return true; return t.fin != null && loopMayWait(t.fin, sc); }
            case ScriptAst.S_IMPORT: case ScriptAst.S_FROM: return true;
            default: return false;
        }
    }

    private boolean exprMayWait(Expr e, Sc sc) {
        if (e == null) return false;
        switch (e.kind) {
            case ScriptAst.E_CALL: {
                Call c = (Call) e;
                if (c.fn.kind == ScriptAst.E_NAME) {
                    String n = ((Name) c.fn).id;
                    Object b = known.builtins.get(n);
                    if (!shadowed(n, sc) && b instanceof Native) { if (ScriptApi.isBlocking(n)) return true; }
                    else return true;     // a user function / unknown callable: assume it may wait
                } else if (c.fn.kind != ScriptAst.E_ATTR) return true;
                else if (exprMayWait(((Attr) c.fn).obj, sc)) return true;
                for (Expr a : c.args) if (exprMayWait(a, sc)) return true;
                for (Expr a : c.kwVals) if (exprMayWait(a, sc)) return true;
                return false;
            }
            case ScriptAst.E_BIN: return exprMayWait(((Bin) e).l, sc) || exprMayWait(((Bin) e).r, sc);
            case ScriptAst.E_UNARY: return exprMayWait(((Unary) e).e, sc);
            case ScriptAst.E_BOOL: return exprMayWait(((BoolOp) e).l, sc) || exprMayWait(((BoolOp) e).r, sc);
            case ScriptAst.E_CMP: { Compare c = (Compare) e; if (exprMayWait(c.first, sc)) return true; for (Expr r : c.rest) if (exprMayWait(r, sc)) return true; return false; }
            case ScriptAst.E_COND: { Cond c = (Cond) e; return exprMayWait(c.test, sc) || exprMayWait(c.a, sc) || exprMayWait(c.b, sc); }
            case ScriptAst.E_LIST: { for (Expr x : ((ListE) e).items) if (exprMayWait(x, sc)) return true; return false; }
            case ScriptAst.E_INDEX: return exprMayWait(((Index) e).obj, sc) || exprMayWait(((Index) e).idx, sc);
            case ScriptAst.E_ATTR: return exprMayWait(((Attr) e).obj, sc);
            case ScriptAst.E_COMP: return true;
            default: return false;
        }
    }

    // ------------------------------------------------------------ imports
    private void checkImport(String module, Node at, String[] names) {
        if (counting) return;
        if (loader == null) { error(at, "ImportError", "no module named '" + module + "'", null); return; }
        String src = loader.source(module);
        if (src == null) { error(at, "ImportError", "no script named '" + module + "'", "create a file called " + module + ".py in the editor (FILES menu)"); return; }
        if (file.equals(module)) return;
        ScriptChecker sub = new ScriptChecker(known, loader);
        sub.file = module;
        Program mp;
        try { mp = ScriptParser.parse(src, module); }
        catch (ScriptError e) { e.in(module); ScriptError w = new ScriptError("ImportError", "the script '" + module + "' has an error: " + e.oneLine(), at.line, at.col); add(w); return; }
        if (names != null) for (String n : names) if (!mp.globalNames.contains(n)) {
            ScriptError e = new ScriptError("ImportError", "'" + module + "' has no '" + n + "'", at.line, at.col);
            String sg = ScriptLib.suggest(n, mp.globalNames); if (sg != null) e.hint("did you mean '" + sg + "'?");
            add(e);
        }
    }

    // ------------------------------------------------------------ expressions
    private void walkTarget(Expr t, Sc sc) {
        switch (t.kind) {
            case ScriptAst.E_NAME: return;
            case ScriptAst.E_LIST: for (Expr x : ((ListE) t).items) walkTarget(x, sc); return;
            case ScriptAst.E_STAR: walkTarget(((Star) t).e, sc); return;
            case ScriptAst.E_INDEX: walkExpr(((Index) t).obj, sc); walkExpr(((Index) t).idx, sc); return;
            case ScriptAst.E_SLICE: { Slice s = (Slice) t; walkExpr(s.obj, sc); if (s.lo != null) walkExpr(s.lo, sc); if (s.hi != null) walkExpr(s.hi, sc); return; }
            case ScriptAst.E_ATTR: walkExpr(((Attr) t).obj, sc); return;
            default: walkExpr(t, sc);
        }
    }

    private void walkExpr(Expr e, Sc sc) {
        if (e == null) return;
        switch (e.kind) {
            case ScriptAst.E_NAME: {
                if (counting) return;
                String n = ((Name) e).id;
                if (!definedIn(n, sc) && !known.builtins.containsKey(n)) {
                    ScriptError err = new ScriptError("NameError", "name '" + n + "' is not defined", e.line, e.col);
                    String sg = ScriptLib.suggest(n, visible(sc));
                    if (sg != null) err.hint("did you mean '" + sg + "'?");
                    else if (n.equals("true") || n.equals("false")) err.hint("write " + Character.toUpperCase(n.charAt(0)) + n.substring(1));
                    else if (n.equals("null") || n.equals("nil")) err.hint("write None");
                    else err.hint("create it first with  " + n + " = ...  or check the spelling");
                    add(err);
                }
                return;
            }
            case ScriptAst.E_LIST: for (Expr x : ((ListE) e).items) walkExpr(x, sc); return;
            case ScriptAst.E_SET: for (Expr x : ((SetE) e).items) walkExpr(x, sc); return;
            case ScriptAst.E_DICT: { DictE d = (DictE) e; for (Expr x : d.keys) walkExpr(x, sc); for (Expr x : d.vals) walkExpr(x, sc); return; }
            case ScriptAst.E_BIN: walkExpr(((Bin) e).l, sc); walkExpr(((Bin) e).r, sc); return;
            case ScriptAst.E_UNARY: walkExpr(((Unary) e).e, sc); return;
            case ScriptAst.E_BOOL: walkExpr(((BoolOp) e).l, sc); walkExpr(((BoolOp) e).r, sc); return;
            case ScriptAst.E_CMP: { Compare c = (Compare) e; walkExpr(c.first, sc); for (Expr r : c.rest) walkExpr(r, sc); return; }
            case ScriptAst.E_CALL: walkCall((Call) e, sc); return;
            case ScriptAst.E_ATTR: { Attr a = (Attr) e; walkExpr(a.obj, sc); checkMember(a, sc); return; }
            case ScriptAst.E_INDEX: walkExpr(((Index) e).obj, sc); walkExpr(((Index) e).idx, sc); return;
            case ScriptAst.E_SLICE: { Slice s = (Slice) e; walkExpr(s.obj, sc); if (s.lo != null) walkExpr(s.lo, sc); if (s.hi != null) walkExpr(s.hi, sc); if (s.step != null) walkExpr(s.step, sc); return; }
            case ScriptAst.E_COND: { Cond c = (Cond) e; walkExpr(c.test, sc); walkExpr(c.a, sc); walkExpr(c.b, sc); return; }
            case ScriptAst.E_LAMBDA: {
                FuncDef d = ((Lambda) e).def;
                for (Param p : d.params) if (p.def != null) walkExpr(p.def, sc);
                walkBlock(d.body, new Sc(d, sc));
                return;
            }
            case ScriptAst.E_COMP: {
                ListComp c = (ListComp) e;
                Sc inner = new Sc(null, sc); inner.comp = c.vars;
                for (Comp g : c.gens) { walkExpr(g.iter, inner); for (Expr i : g.ifs) walkExpr(i, inner); }
                walkExpr(c.elt, inner); if (c.val != null) walkExpr(c.val, inner);
                return;
            }
            case ScriptAst.E_FSTR: { for (Object p : ((FStr) e).parts) if (p instanceof FPart) walkExpr(((FPart) p).e, sc); return; }
            case ScriptAst.E_STAR: walkExpr(((Star) e).e, sc); return;
            default: return;
        }
    }

    /** Crop.Pumkin, Item.Sawr, ... : members of built-in constant groups. */
    private void checkMember(Attr a, Sc sc) {
        if (counting || a.obj.kind != ScriptAst.E_NAME) return;
        String n = ((Name) a.obj).id;
        if (shadowed(n, sc)) return;
        Object o = known.builtins.get(n);
        if (o instanceof Namespace) {
            Namespace ns = (Namespace) o;
            if (!ns.hasAttr(a.name)) {
                ScriptError e = new ScriptError("AttributeError", "'" + n + "' has no member '" + a.name + "'", a.line, a.col);
                String sg = ScriptLib.suggest(a.name, ns.vars.keySet());
                e.hint(sg != null ? "did you mean '" + n + "." + sg + "'?" : "available: " + joinKeys(ns));
                add(e);
            }
        }
    }
    private static String joinKeys(Namespace ns) {
        StringBuilder sb = new StringBuilder(); int i = 0;
        for (String k : ns.vars.keySet()) { if (i++ > 0) sb.append(", "); sb.append(k); if (i > 14) { sb.append(", ..."); break; } }
        return sb.toString();
    }

    private void walkCall(Call c, Sc sc) {
        walkExpr(c.fn, sc);
        for (Expr a : c.args) walkExpr(a, sc);
        for (Expr a : c.kwVals) walkExpr(a, sc);
        if (c.kwStar != null) walkExpr(c.kwStar, sc);
        if (counting || c.fn.kind != ScriptAst.E_NAME) return;
        String n = ((Name) c.fn).id;
        if (n.equals("spawn") && !shadowed(n, sc) && c.args.length > 0 && c.args[0].kind == ScriptAst.E_CALL)
            error(c.args[0], "TypeError", "spawn() needs the function itself, but you called it here", "write  spawn(" + ScriptParser.exprName(((Call) c.args[0]).fn) + ")  without the ( )");
        boolean hasStar = c.kwStar != null;
        for (boolean s : c.star) if (s) hasStar = true;
        if (hasStar) return;
        int pos = c.args.length;
        String[] kw = c.kwNames;
        if (!shadowedByLocal(n, sc) && !prog.globalNames.contains(n) && known.builtins.get(n) instanceof Native) {
            Native nat = (Native) known.builtins.get(n);
            if (nat.raw) return;
            checkArity(c, n, nat.params, nat.optional, pos, kw, nat.sig);
        } else if (topDefs.containsKey(n) && topDefs.get(n).size() == 1 && !shadowedByLocal(n, sc)) {
            Integer gc = globalBinds.get(n);
            if (gc != null && gc == 1) {
                FuncDef d = topDefs.get(n).get(0);
                boolean varargs = false; for (Param p : d.params) if (p.star || p.dstar) varargs = true;
                if (varargs) return;
                String[] names = new String[d.params.length]; boolean[] opt = new boolean[names.length];
                StringBuilder sig = new StringBuilder();
                for (int i = 0; i < names.length; i++) { names[i] = d.params[i].name; opt[i] = d.params[i].def != null; if (i > 0) sig.append(", "); sig.append(names[i]); if (opt[i]) sig.append("=..."); }
                checkArity(c, n, names, opt, pos, kw, sig.toString());
            }
        }
    }

    private void checkArity(Call c, String fname, String[] params, boolean[] optional, int pos, String[] kw, String sig) {
        int np = params.length;
        if (pos > np) {
            int req = 0; for (int i = 0; i < np; i++) if (!optional[i]) req++;
            error(c, "TypeError", fname + "() takes " + (req == np ? "" + np : "at most " + np) + " argument" + (np == 1 ? "" : "s") + " but " + pos + " " + (pos == 1 ? "was" : "were") + " given", "usage: " + fname + "(" + sig + ")");
            return;
        }
        boolean[] given = new boolean[np];
        for (int i = 0; i < pos; i++) given[i] = true;
        for (int k = 0; k < kw.length; k++) {
            int idx = -1;
            for (int i = 0; i < np; i++) if (params[i].equals(kw[k])) idx = i;
            if (idx < 0) {
                ScriptError e = new ScriptError("TypeError", fname + "() got an unexpected keyword argument '" + kw[k] + "'", c.line, c.col);
                String sg = ScriptLib.suggest(kw[k], java.util.Arrays.asList(params));
                e.hint(sg != null ? "did you mean '" + sg + "'?" : "usage: " + fname + "(" + sig + ")");
                add(e); return;
            }
            if (given[idx]) { error(c, "TypeError", fname + "() got multiple values for argument '" + kw[k] + "'", null); return; }
            given[idx] = true;
        }
        for (int i = 0; i < np; i++) if (!given[i] && !optional[i]) { error(c, "TypeError", fname + "() is missing the argument '" + params[i] + "'", "usage: " + fname + "(" + sig + ")"); return; }
    }
}
