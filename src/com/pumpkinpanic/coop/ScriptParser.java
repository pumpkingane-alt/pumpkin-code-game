package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.pumpkinpanic.coop.ScriptAst.*;
import com.pumpkinpanic.coop.ScriptLexer.Tok;

/** Recursive-descent parser for the Python-style script language. Java 7 compatible. */
final class ScriptParser {
    static final String[] KEYWORDS = {"False", "None", "True", "and", "as", "assert", "break", "class", "continue", "def", "del", "elif", "else",
        "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield", "async", "await"};
    private static final HashSet<String> KW = new HashSet<String>();
    static { for (int i = 0; i < KEYWORDS.length; i++) KW.add(KEYWORDS[i]); }
    static boolean isKeyword(String s) { return KW.contains(s); }

    private static final class Scope {
        final boolean func; final HashSet<String> locals, globals, nonlocals; int loops;
        Scope(boolean f, HashSet<String> l, HashSet<String> g, HashSet<String> n) { func = f; locals = l; globals = g; nonlocals = n; }
    }

    private final ArrayList<Tok> toks;
    private int p;
    private final Program prog = new Program();
    private final ArrayList<Scope> scopes = new ArrayList<Scope>();
    private final String file;

    private ScriptParser(ArrayList<Tok> t, String file) { toks = t; this.file = file; prog.file = file; }

    /** Parse a whole program. Throws ScriptError (SyntaxError / IndentationError). */
    static Program parse(String source, String file) {
        ScriptParser ps = new ScriptParser(ScriptLexer.lex(source), file);
        return ps.program();
    }

    /** Parse one expression (used for f-string pieces). */
    static Expr parseExpression(String text, String file, int line) {
        ArrayList<Tok> t = new ScriptLexer(text, line).run();
        ScriptParser ps = new ScriptParser(t, file);
        ps.scopes.add(new Scope(false, ps.prog.globalNames, new HashSet<String>(), new HashSet<String>()));
        while (ps.tok().type == ScriptLexer.NEWLINE) ps.p++;
        Expr e = ps.testList();
        while (ps.tok().type == ScriptLexer.NEWLINE) ps.p++;
        if (ps.tok().type != ScriptLexer.EOF) throw ps.syntax("invalid syntax in f-string expression", ps.tok());
        return e;
    }

    // ------------------------------------------------------------ token helpers
    private Tok tok() { return toks.get(p); }
    private Tok peekTok(int k) { return toks.get(Math.min(toks.size() - 1, p + k)); }
    private Tok next() { Tok t = toks.get(p); if (p < toks.size() - 1) p++; return t; }
    private boolean isOp(String s) { Tok t = tok(); return t.type == ScriptLexer.OP && t.text.equals(s); }
    private boolean isName(String s) { Tok t = tok(); return t.type == ScriptLexer.NAME && t.text.equals(s); }
    private boolean acceptOp(String s) { if (isOp(s)) { p++; return true; } return false; }
    private boolean acceptName(String s) { if (isName(s)) { p++; return true; } return false; }

    private ScriptError syntax(String msg, Tok t) { return new ScriptError("SyntaxError", msg, t.line, t.col).in(file); }

    private String describe(Tok t) {
        switch (t.type) {
            case ScriptLexer.EOF: return "end of file";
            case ScriptLexer.NEWLINE: return "end of line";
            case ScriptLexer.INDENT: return "an indented block";
            case ScriptLexer.DEDENT: return "end of block";
            case ScriptLexer.STR: return "a string";
            case ScriptLexer.INT: case ScriptLexer.FLOAT: return "the number " + t.text;
            default: return "'" + t.text + "'";
        }
    }

    private void expectOp(String s, String hint) {
        if (acceptOp(s)) return;
        Tok t = tok();
        Tok at = t;
        if ((t.type == ScriptLexer.NEWLINE || t.type == ScriptLexer.EOF) && p > 0) at = toks.get(p - 1);
        ScriptError e = syntax("expected '" + s + "' but found " + describe(t), t.type == ScriptLexer.NEWLINE || t.type == ScriptLexer.EOF ? at : t);
        if (t.type == ScriptLexer.NEWLINE || t.type == ScriptLexer.EOF) { e.line = at.line; e.col = at.col + (at.text == null ? 0 : Math.max(1, at.text.length())); }
        if (hint != null) e.hint(hint);
        throw e;
    }

    private String ident(String what) {
        Tok t = tok();
        if (t.type != ScriptLexer.NAME) throw syntax("expected " + what + " but found " + describe(t), t);
        if (KW.contains(t.text)) throw syntax("'" + t.text + "' is a reserved word and cannot be used as " + what, t).hint("pick another name");
        p++;
        return t.text;
    }

    private <T extends Node> T at(T n, Tok t) { n.line = t.line; n.col = t.col; return n; }
    private <T extends Node> T at(T n, Node o) { n.line = o.line; n.col = o.col; return n; }

    // ------------------------------------------------------------ scopes
    private Scope scope() { return scopes.get(scopes.size() - 1); }
    private void bind(String name) {
        Scope s = scope();
        if (s.func) {
            if (s.globals.contains(name)) prog.globalNames.add(name);
            else if (!s.nonlocals.contains(name)) s.locals.add(name);
        } else prog.globalNames.add(name);
    }
    private void bindTarget(Expr e) {
        if (e.kind == ScriptAst.E_NAME) bind(((Name) e).id);
        else if (e.kind == ScriptAst.E_LIST) { Expr[] it = ((ListE) e).items; for (int i = 0; i < it.length; i++) bindTarget(it[i]); }
        else if (e.kind == ScriptAst.E_STAR) bindTarget(((Star) e).e);
    }
    private void checkTarget(Expr e, Tok at) {
        switch (e.kind) {
            case ScriptAst.E_NAME: case ScriptAst.E_ATTR: case ScriptAst.E_INDEX: case ScriptAst.E_SLICE: return;
            case ScriptAst.E_LIST: { Expr[] it = ((ListE) e).items; for (int i = 0; i < it.length; i++) checkTarget(it[i], at); return; }
            case ScriptAst.E_STAR: checkTarget(((Star) e).e, at); return;
            case ScriptAst.E_CALL: throw new ScriptError("SyntaxError", "cannot assign to a function call", e.line, e.col).in(file).hint("the left side of '=' must be a variable");
            case ScriptAst.E_CONST: throw new ScriptError("SyntaxError", "cannot assign to a literal value", e.line, e.col).in(file).hint("did you mean '==' to compare?");
            default: throw new ScriptError("SyntaxError", "cannot assign to this expression", e.line, e.col).in(file).hint("the left side of '=' must be a variable, a[i] or obj.name");
        }
    }

    // ------------------------------------------------------------ program / blocks
    private Program program() {
        scopes.add(new Scope(false, prog.globalNames, new HashSet<String>(), new HashSet<String>()));
        ArrayList<Stmt> out = new ArrayList<Stmt>();
        while (tok().type != ScriptLexer.EOF) {
            if (tok().type == ScriptLexer.NEWLINE) { p++; continue; }
            if (tok().type == ScriptLexer.INDENT) throw new ScriptError("IndentationError", "unexpected indent", tok().line, tok().col).in(file).hint("this line is indented more than the one above it; remove the extra spaces");
            if (tok().type == ScriptLexer.DEDENT) { p++; continue; }
            statement(out);
        }
        prog.body = out.toArray(new Stmt[out.size()]);
        return prog;
    }

    private Stmt[] block(String what, int headerLine) {
        ArrayList<Stmt> out = new ArrayList<Stmt>();
        if (tok().type == ScriptLexer.NEWLINE) {
            p++;
            if (tok().type != ScriptLexer.INDENT) {
                Tok t = tok();
                throw new ScriptError("IndentationError", "expected an indented block after " + what + " (line " + headerLine + ")", t.line, t.col).in(file).hint("indent the next line by 4 spaces");
            }
            p++;
            while (tok().type != ScriptLexer.DEDENT && tok().type != ScriptLexer.EOF) {
                if (tok().type == ScriptLexer.NEWLINE) { p++; continue; }
                if (tok().type == ScriptLexer.INDENT) throw new ScriptError("IndentationError", "unexpected indent", tok().line, tok().col).in(file).hint("this line is indented more than the one above it");
                statement(out);
            }
            if (tok().type == ScriptLexer.DEDENT) p++;
        } else {
            simpleLine(out);
        }
        if (out.isEmpty()) { Tok t = tok(); throw new ScriptError("IndentationError", "expected an indented block after " + what, t.line, t.col).in(file); }
        return out.toArray(new Stmt[out.size()]);
    }

    private void statement(ArrayList<Stmt> out) {
        Tok t = tok();
        if (t.type == ScriptLexer.NAME) {
            String w = t.text;
            if (w.equals("if")) { out.add(ifStmt()); return; }
            if (w.equals("while")) { out.add(whileStmt()); return; }
            if (w.equals("for")) { out.add(forStmt()); return; }
            if (w.equals("def")) { out.add(defStmt()); return; }
            if (w.equals("try")) { out.add(tryStmt()); return; }
            if (w.equals("class")) throw syntax("classes are not supported", t).hint("use dicts and functions, e.g.  pet = {\"name\": \"Max\"}");
            if (w.equals("with") || w.equals("yield") || w.equals("async") || w.equals("await")) throw syntax("'" + w + "' is not supported in this language", t);
            if (w.equals("elif") || w.equals("else") || w.equals("except") || w.equals("finally"))
                throw syntax("'" + w + "' without a matching " + (w.equals("elif") || w.equals("else") ? "'if'" : "'try'"), t).hint("check the indentation: '" + w + "' must line up with its " + (w.equals("elif") || w.equals("else") ? "'if'" : "'try'"));
        }
        simpleLine(out);
    }

    private void simpleLine(ArrayList<Stmt> out) {
        while (true) {
            out.add(simpleStmt());
            if (acceptOp(";")) { if (tok().type == ScriptLexer.NEWLINE) break; continue; }
            break;
        }
        Tok t = tok();
        if (t.type == ScriptLexer.NEWLINE) { p++; return; }
        if (t.type == ScriptLexer.EOF || t.type == ScriptLexer.DEDENT) return;
        throw badTrailing(t);
    }

    private ScriptError badTrailing(Tok t) {
        Tok prev = p > 0 ? toks.get(p - 1) : t;
        ScriptError e = syntax("invalid syntax: unexpected " + describe(t), t);
        if (prev.type == ScriptLexer.NAME && prev.text.equals("print")) e.hint("print is a function: print(\"text\")");
        else if (t.type == ScriptLexer.NAME && t.text.equals("else") ) e.hint("is the 'if' above missing its ':' or indentation?");
        else if (t.type == ScriptLexer.OP && t.text.equals("=")) e.hint("use '==' to compare values");
        else if (t.type == ScriptLexer.NAME || t.type == ScriptLexer.INT || t.type == ScriptLexer.FLOAT || t.type == ScriptLexer.STR) e.hint("missing an operator or a comma between two values? or put the second one on its own line");
        else if (t.type == ScriptLexer.OP && (t.text.equals("&") || t.text.equals("|"))) e.hint("use 'and' / 'or'");
        return e;
    }

    // ------------------------------------------------------------ compound statements
    private Stmt ifStmt() {
        Tok start = next();      // if
        ArrayList<Expr> tests = new ArrayList<Expr>(); ArrayList<Stmt[]> blocks = new ArrayList<Stmt[]>();
        Stmt[] orelse = null;
        tests.add(condition("'if'"));
        expectColon("if", start.line);
        blocks.add(block("'if' statement", start.line));
        while (true) {
            if (isName("elif")) {
                Tok e = next(); tests.add(condition("'elif'")); expectColon("elif", e.line); blocks.add(block("'elif' statement", e.line));
            } else if (isName("else")) {
                Tok e = next();
                if (isName("if")) throw syntax("'else if' is not valid", tok()).hint("write 'elif' instead of 'else if'");
                expectColon("else", e.line); orelse = block("'else' statement", e.line); break;
            } else break;
        }
        If s = at(new If(), start);
        s.tests = tests.toArray(new Expr[tests.size()]); s.blocks = blocks.toArray(new Stmt[blocks.size()][]); s.orelse = orelse;
        return s;
    }

    private Expr condition(String what) {
        Expr e = test();
        if (isOp("=")) throw syntax("invalid syntax: '=' in the condition of " + what, tok()).hint("use '==' to compare; a single '=' assigns a value");
        return e;
    }

    private void expectColon(String kw, int line) {
        if (isOp(":")) { p++; return; }
        Tok t = tok();
        Tok prev = p > 0 ? toks.get(p - 1) : t;
        ScriptError e = new ScriptError("SyntaxError", "expected ':' at the end of the '" + kw + "' line, found " + describe(t), t.type == ScriptLexer.NEWLINE ? prev.line : t.line, t.type == ScriptLexer.NEWLINE ? prev.col + 1 : t.col).in(file);
        e.hint("add a ':' at the end of the line");
        throw e;
    }

    private Stmt whileStmt() {
        Tok start = next();
        While s = at(new While(), start);
        s.test = condition("'while'");
        expectColon("while", start.line);
        scope().loops++;
        s.body = block("'while' statement", start.line);
        scope().loops--;
        if (isName("else")) { Tok e = next(); expectColon("else", e.line); s.orelse = block("'else' statement", e.line); }
        return s;
    }

    private Expr targetList() {
        Tok st = tok();
        ArrayList<Expr> items = new ArrayList<Expr>();
        boolean comma = false;
        while (true) {
            if (isOp("*")) { Tok s = next(); Star sr = at(new Star(), s); sr.e = bitOr(); items.add(sr); }
            else items.add(bitOr());
            if (acceptOp(",")) { comma = true; if (isName("in") || isOp("=")) break; continue; }
            break;
        }
        if (items.size() == 1 && !comma) return items.get(0);
        ListE l = at(new ListE(), st); l.tuple = true; l.items = items.toArray(new Expr[items.size()]);
        return l;
    }

    private Stmt forStmt() {
        Tok start = next();
        For s = at(new For(), start);
        s.target = targetList();
        checkTarget(s.target, start);
        bindTarget(s.target);
        if (!acceptName("in")) throw syntax("expected 'in' in the 'for' line but found " + describe(tok()), tok()).hint("a loop looks like:  for i in range(5):");
        s.iter = testList();
        expectColon("for", start.line);
        scope().loops++;
        s.body = block("'for' statement", start.line);
        scope().loops--;
        if (isName("else")) { Tok e = next(); expectColon("else", e.line); s.orelse = block("'else' statement", e.line); }
        return s;
    }

    private Stmt defStmt() {
        Tok start = next();
        FuncDef f = at(new FuncDef(), start);
        f.name = ident("a function name");
        f.file = file;
        bind(f.name);
        expectOp("(", "a function looks like:  def name(a, b):");
        ArrayList<Param> ps = new ArrayList<Param>();
        HashSet<String> seen = new HashSet<String>();
        boolean sawDefault = false, sawStar = false;
        while (!isOp(")")) {
            Param pr = new Param();
            Tok pt = tok();
            if (acceptOp("**")) pr.dstar = true; else if (acceptOp("*")) { pr.star = true; sawStar = true; }
            pr.name = ident("a parameter name");
            if (!seen.add(pr.name)) throw syntax("duplicate parameter name '" + pr.name + "'", pt);
            if (acceptOp("=")) { pr.def = test(); sawDefault = true; }
            else if (sawDefault && !pr.star && !pr.dstar && !sawStar) throw syntax("parameter '" + pr.name + "' without a default follows one with a default", pt).hint("give it a default value too, e.g.  " + pr.name + "=0");
            ps.add(pr);
            if (!acceptOp(",")) break;
        }
        expectOp(")", "close the parameter list with ')'");
        expectColon("def", start.line);
        f.params = ps.toArray(new Param[ps.size()]);
        int min = 0, max = 0;
        for (int i = 0; i < f.params.length; i++) {
            Param q = f.params[i];
            if (q.star) { max = -1; continue; }
            if (q.dstar) continue;
            if (max >= 0) max++;
            if (q.def == null) min++;
        }
        f.minArgs = min; f.maxArgs = max;
        Scope sc = new Scope(true, f.locals, f.globals, f.nonlocals);
        for (int i = 0; i < f.params.length; i++) f.locals.add(f.params[i].name);
        scopes.add(sc);
        f.body = block("function '" + f.name + "'", start.line);
        scopes.remove(scopes.size() - 1);
        prog.allFuncs.add(f);
        return f;
    }

    private Stmt tryStmt() {
        Tok start = next();
        Try s = at(new Try(), start);
        expectColon("try", start.line);
        s.body = block("'try' statement", start.line);
        ArrayList<Handler> hs = new ArrayList<Handler>();
        while (isName("except")) {
            Tok et = next();
            Handler h = new Handler(); h.line = et.line;
            if (!isOp(":")) {
                h.type = test();
                if (acceptName("as")) { h.name = ident("a variable name"); bind(h.name); }
            }
            expectColon("except", et.line);
            h.body = block("'except' clause", et.line);
            hs.add(h);
        }
        s.handlers = hs.toArray(new Handler[hs.size()]);
        if (isName("else") && !hs.isEmpty()) { Tok e = next(); expectColon("else", e.line); s.orelse = block("'else' clause", e.line); }
        if (isName("finally")) { Tok e = next(); expectColon("finally", e.line); s.fin = block("'finally' clause", e.line); }
        if (hs.isEmpty() && s.fin == null) throw syntax("'try' needs an 'except' or 'finally' clause", start).hint("add  except:  below the try block");
        return s;
    }

    // ------------------------------------------------------------ simple statements
    private Stmt simpleStmt() {
        Tok t = tok();
        if (t.type == ScriptLexer.NAME) {
            String w = t.text;
            if (w.equals("pass")) { p++; return at(new Simple(ScriptAst.S_PASS), t); }
            if (w.equals("break")) { p++; if (scope().loops == 0) throw syntax("'break' outside a loop", t).hint("break only works inside while / for"); return at(new Simple(ScriptAst.S_BREAK), t); }
            if (w.equals("continue")) { p++; if (scope().loops == 0) throw syntax("'continue' outside a loop", t); return at(new Simple(ScriptAst.S_CONT), t); }
            if (w.equals("return")) {
                p++;
                if (!scope().func) throw syntax("'return' outside a function", t).hint("use 'return' only inside a def");
                Return r = at(new Return(), t);
                if (tok().type != ScriptLexer.NEWLINE && !isOp(";") && tok().type != ScriptLexer.EOF && tok().type != ScriptLexer.DEDENT) r.value = testList();
                return r;
            }
            if (w.equals("global") || w.equals("nonlocal")) {
                p++;
                Decl d = at(new Decl(w.equals("global") ? ScriptAst.S_GLOBAL : ScriptAst.S_NONLOCAL), t);
                ArrayList<String> names = new ArrayList<String>();
                do { names.add(ident("a variable name")); } while (acceptOp(","));
                d.names = names.toArray(new String[names.size()]);
                Scope s = scope();
                if (!s.func) { if (w.equals("nonlocal")) throw syntax("'nonlocal' at module level", t); return d; }
                for (int i = 0; i < d.names.length; i++) {
                    if (w.equals("global")) { s.globals.add(d.names[i]); s.locals.remove(d.names[i]); prog.globalNames.add(d.names[i]); }
                    else { s.nonlocals.add(d.names[i]); s.locals.remove(d.names[i]); }
                }
                return d;
            }
            if (w.equals("raise")) {
                p++;
                Raise r = at(new Raise(), t);
                if (tok().type != ScriptLexer.NEWLINE && !isOp(";") && tok().type != ScriptLexer.EOF) r.e = test();
                return r;
            }
            if (w.equals("assert")) {
                p++;
                Assert a = at(new Assert(), t);
                a.test = test();
                if (acceptOp(",")) a.msg = test();
                return a;
            }
            if (w.equals("del")) {
                p++;
                Del d = at(new Del(), t);
                ArrayList<Expr> ts = new ArrayList<Expr>();
                do { Expr e = postfix(); checkTarget(e, t); ts.add(e); } while (acceptOp(","));
                d.targets = ts.toArray(new Expr[ts.size()]);
                return d;
            }
            if (w.equals("import")) {
                p++;
                Import im = at(new Import(), t);
                im.module = ident("a module name");
                if (acceptName("as")) im.alias = ident("a name"); else im.alias = im.module;
                bind(im.alias);
                return im;
            }
            if (w.equals("from")) {
                p++;
                FromImport fi = at(new FromImport(), t);
                fi.module = ident("a module name");
                if (!acceptName("import")) throw syntax("expected 'import' after the module name", tok());
                ArrayList<String> ns = new ArrayList<String>(), as = new ArrayList<String>();
                boolean paren = acceptOp("(");
                if (isOp("*")) throw syntax("'import *' is not supported", tok()).hint("list the names:  from " + fi.module + " import a, b");
                do {
                    if (paren && isOp(")")) break;
                    String n = ident("a name"); String a = n;
                    if (acceptName("as")) a = ident("a name");
                    ns.add(n); as.add(a); bind(a);
                } while (acceptOp(","));
                if (paren) expectOp(")", null);
                fi.names = ns.toArray(new String[ns.size()]); fi.aliases = as.toArray(new String[as.size()]);
                return fi;
            }
        }
        // expression / assignment
        Expr first = testListStar();
        Tok o = tok();
        if (o.type == ScriptLexer.OP) {
            String s = o.text;
            if (s.equals("=")) {
                ArrayList<Expr> targets = new ArrayList<Expr>();
                targets.add(first);
                Expr value = null;
                while (acceptOp("=")) {
                    Expr e = testListStar();
                    if (isOp("=")) targets.add(e); else value = e;
                }
                for (int i = 0; i < targets.size(); i++) { checkTarget(targets.get(i), t); bindTarget(targets.get(i)); }
                Assign a = at(new Assign(), t);
                a.targets = targets.toArray(new Expr[targets.size()]); a.value = value;
                return a;
            }
            int op = augOp(s);
            if (op != 0) {
                p++;
                checkTarget(first, t);
                if (first.kind == ScriptAst.E_LIST) throw syntax("illegal expression for augmented assignment", o);
                bindTarget(first);
                AugAssign a = at(new AugAssign(), t);
                a.target = first; a.op = op; a.value = testList();
                return a;
            }
            if ((s.equals("+") || s.equals("-")) && peekTok(1).type == ScriptLexer.OP && peekTok(1).text.equals(s) && (peekTok(2).type == ScriptLexer.NEWLINE || peekTok(2).type == ScriptLexer.EOF))
                throw syntax("'" + s + s + "' is not an operator in this language", o).hint("use  x " + s + "= 1");
        }
        ExprStmt es = at(new ExprStmt(), t);
        es.e = first;
        return es;
    }

    private static int augOp(String s) {
        if (s.equals("+=")) return ScriptAst.ADD; if (s.equals("-=")) return ScriptAst.SUB; if (s.equals("*=")) return ScriptAst.MUL;
        if (s.equals("/=")) return ScriptAst.DIV; if (s.equals("//=")) return ScriptAst.FDIV; if (s.equals("%=")) return ScriptAst.MOD;
        if (s.equals("**=")) return ScriptAst.POW; if (s.equals("&=")) return ScriptAst.BAND; if (s.equals("|=")) return ScriptAst.BOR;
        if (s.equals("^=")) return ScriptAst.BXOR; if (s.equals("<<=")) return ScriptAst.SHL; if (s.equals(">>=")) return ScriptAst.SHR;
        return 0;
    }

    // ------------------------------------------------------------ expressions
    private Expr testListStar() { return exprList(true); }
    private Expr testList() { return exprList(false); }

    private boolean endOfExprList() {
        Tok t = tok();
        if (t.type == ScriptLexer.NEWLINE || t.type == ScriptLexer.EOF || t.type == ScriptLexer.DEDENT) return true;
        if (t.type == ScriptLexer.OP) {
            String s = t.text;
            return s.equals("=") || s.equals(")") || s.equals("]") || s.equals("}") || s.equals(":") || s.equals(";") || augOp(s) != 0;
        }
        return false;
    }

    private Expr exprList(boolean allowStar) {
        Tok st = tok();
        ArrayList<Expr> items = new ArrayList<Expr>();
        boolean comma = false;
        while (true) {
            if (allowStar && isOp("*")) { Tok s = next(); Star sr = at(new Star(), s); sr.e = bitOr(); items.add(sr); }
            else items.add(test());
            if (acceptOp(",")) { comma = true; if (endOfExprList()) break; continue; }
            break;
        }
        if (items.size() == 1 && !comma && items.get(0).kind != ScriptAst.E_STAR) return items.get(0);
        ListE l = at(new ListE(), st); l.tuple = true; l.items = items.toArray(new Expr[items.size()]);
        return l;
    }

    private Expr test() {
        Tok t = tok();
        if (isName("lambda")) return lambda();
        Expr e = orTest();
        if (isName("if")) {
            p++;
            Cond c = at(new Cond(), t);
            c.a = e; c.test = orTest();
            if (!acceptName("else")) throw syntax("expected 'else' in the conditional expression", tok()).hint("a if cond else b");
            c.b = test();
            return c;
        }
        return e;
    }

    private Expr lambda() {
        Tok t = next();
        FuncDef f = at(new FuncDef(), t);
        f.file = file;
        ArrayList<Param> ps = new ArrayList<Param>();
        boolean sawDefault = false;
        while (!isOp(":")) {
            Param pr = new Param();
            if (acceptOp("**")) pr.dstar = true; else if (acceptOp("*")) pr.star = true;
            pr.name = ident("a parameter name");
            if (acceptOp("=")) { pr.def = test(); sawDefault = true; }
            ps.add(pr);
            if (!acceptOp(",")) break;
        }
        expectOp(":", "a lambda looks like:  lambda x: x * 2");
        f.params = ps.toArray(new Param[ps.size()]);
        int min = 0, max = 0;
        for (int i = 0; i < f.params.length; i++) { Param q = f.params[i]; if (q.star) { max = -1; continue; } if (q.dstar) continue; if (max >= 0) max++; if (q.def == null) min++; }
        f.minArgs = min; f.maxArgs = max;
        Scope sc = new Scope(true, f.locals, f.globals, f.nonlocals);
        for (int i = 0; i < f.params.length; i++) f.locals.add(f.params[i].name);
        scopes.add(sc);
        Return r = at(new Return(), t);
        r.value = test();
        f.body = new Stmt[] {r};
        scopes.remove(scopes.size() - 1);
        Lambda l = at(new Lambda(), t);
        l.def = f;
        prog.allFuncs.add(f);
        return l;
    }

    private Expr orTest() {
        Expr l = andTest();
        while (isName("or")) { Tok t = next(); BoolOp b = at(new BoolOp(), t); b.and = false; b.l = l; b.r = andTest(); l = b; }
        return l;
    }
    private Expr andTest() {
        Expr l = notTest();
        while (isName("and")) { Tok t = next(); BoolOp b = at(new BoolOp(), t); b.and = true; b.l = l; b.r = notTest(); l = b; }
        return l;
    }
    private Expr notTest() {
        if (isName("not")) { Tok t = next(); Unary u = at(new Unary(), t); u.op = ScriptAst.U_NOT; u.e = notTest(); return u; }
        return comparison();
    }

    private int cmpOp() {
        Tok t = tok();
        if (t.type == ScriptLexer.OP) {
            String s = t.text;
            if (s.equals("==")) return ScriptAst.EQ; if (s.equals("!=")) return ScriptAst.NE; if (s.equals("<")) return ScriptAst.LT;
            if (s.equals(">")) return ScriptAst.GT; if (s.equals("<=")) return ScriptAst.LE; if (s.equals(">=")) return ScriptAst.GE;
            return 0;
        }
        if (t.type == ScriptLexer.NAME) {
            if (t.text.equals("in")) return ScriptAst.IN;
            if (t.text.equals("not") && peekTok(1).type == ScriptLexer.NAME && peekTok(1).text.equals("in")) return ScriptAst.NOTIN;
            if (t.text.equals("is")) return (peekTok(1).type == ScriptLexer.NAME && peekTok(1).text.equals("not")) ? ScriptAst.ISNOT : ScriptAst.IS;
        }
        return 0;
    }

    private Expr comparison() {
        Expr first = bitOr();
        int op = cmpOp();
        if (op == 0) return first;
        Tok st = tok();
        ArrayList<Integer> ops = new ArrayList<Integer>(); ArrayList<Expr> rest = new ArrayList<Expr>();
        while (op != 0) {
            if (op == ScriptAst.NOTIN || op == ScriptAst.ISNOT) p += 2; else p++;
            ops.add(op);
            rest.add(bitOr());
            op = cmpOp();
        }
        Compare c = at(new Compare(), first);
        c.first = first; c.ops = new int[ops.size()]; for (int i = 0; i < c.ops.length; i++) c.ops[i] = ops.get(i);
        c.rest = rest.toArray(new Expr[rest.size()]);
        if (isOp("=") ) { /* handled by caller as an error for conditions */ }
        return c;
    }

    private Expr binary(int op, Expr l, Expr r, Tok t) { Bin b = at(new Bin(), t); b.op = op; b.l = l; b.r = r; return b; }

    private Expr bitOr() { Expr l = bitXor(); while (isOp("|") && !(peekTok(1).type == ScriptLexer.OP && peekTok(1).text.equals("|"))) { Tok t = next(); l = binary(ScriptAst.BOR, l, bitXor(), t); } return l; }
    private Expr bitXor() { Expr l = bitAnd(); while (isOp("^")) { Tok t = next(); l = binary(ScriptAst.BXOR, l, bitAnd(), t); } return l; }
    private Expr bitAnd() { Expr l = shift(); while (isOp("&") && !(peekTok(1).type == ScriptLexer.OP && peekTok(1).text.equals("&"))) { Tok t = next(); l = binary(ScriptAst.BAND, l, shift(), t); } return l; }
    private Expr shift() {
        Expr l = arith();
        while (isOp("<<") || isOp(">>")) { Tok t = next(); l = binary(t.text.equals("<<") ? ScriptAst.SHL : ScriptAst.SHR, l, arith(), t); }
        return l;
    }
    private Expr arith() {
        Expr l = term();
        while (isOp("+") || isOp("-")) { Tok t = next(); l = binary(t.text.equals("+") ? ScriptAst.ADD : ScriptAst.SUB, l, term(), t); }
        return l;
    }
    private Expr term() {
        Expr l = factor();
        while (isOp("*") || isOp("/") || isOp("//") || isOp("%")) {
            Tok t = next();
            int op = t.text.equals("*") ? ScriptAst.MUL : (t.text.equals("/") ? ScriptAst.DIV : (t.text.equals("//") ? ScriptAst.FDIV : ScriptAst.MOD));
            l = binary(op, l, factor(), t);
        }
        return l;
    }
    private Expr factor() {
        if (isOp("-") || isOp("+") || isOp("~")) {
            Tok t = next(); Unary u = at(new Unary(), t);
            u.op = t.text.equals("-") ? ScriptAst.U_NEG : (t.text.equals("+") ? ScriptAst.U_POS : ScriptAst.U_INV);
            u.e = factor();
            return u;
        }
        return power();
    }
    private Expr power() {
        Expr base = postfix();
        if (isOp("**")) { Tok t = next(); return binary(ScriptAst.POW, base, factor(), t); }
        return base;
    }

    private Expr postfix() {
        Expr e = atom();
        while (true) {
            Tok t = tok();
            if (t.type != ScriptLexer.OP) break;
            if (t.text.equals("(")) { p++; e = callArgs(e, t); }
            else if (t.text.equals("[")) { p++; e = subscript(e, t); }
            else if (t.text.equals(".")) {
                p++;
                Tok n = tok();
                if (n.type != ScriptLexer.NAME) throw syntax("expected a name after '.' but found " + describe(n), n);
                p++;
                Attr a = at(new Attr(), t); a.obj = e; a.name = n.text; a.col = n.col; e = a;
            } else break;
        }
        return e;
    }

    private Expr callArgs(Expr fn, Tok open) {
        Call c = at(new Call(), fn);
        c.fn = fn;
        c.line = fn.line; c.col = fn.col;
        ArrayList<Expr> args = new ArrayList<Expr>(); ArrayList<Boolean> star = new ArrayList<Boolean>();
        ArrayList<String> kn = new ArrayList<String>(); ArrayList<Expr> kv = new ArrayList<Expr>();
        while (!isOp(")")) {
            if (tok().type == ScriptLexer.NEWLINE || tok().type == ScriptLexer.EOF) break;
            Tok t = tok();
            if (isOp("**")) { p++; c.kwStar = test(); }
            else if (isOp("*")) { p++; args.add(test()); star.add(Boolean.TRUE); }
            else if (t.type == ScriptLexer.NAME && peekTok(1).type == ScriptLexer.OP && peekTok(1).text.equals("=") && !KW.contains(t.text)) {
                p += 2; kn.add(t.text); kv.add(test());
            } else {
                Expr e = test();
                if (isName("for")) e = comprehension(e, null, t, false);
                else if (isOp("=")) throw syntax("keyword argument must be a plain name", tok()).hint("write  name=value  inside the call");
                if (!kn.isEmpty()) throw syntax("a positional argument cannot come after a keyword argument", t).hint("put name=value arguments last");
                args.add(e); star.add(Boolean.FALSE);
            }
            if (!acceptOp(",")) break;
        }
        if (!isOp(")")) {
            Tok t = tok();
            throw syntax("expected ')' to close the call to " + exprName(fn) + "( but found " + describe(t), t).hint("check for a missing ')' or ','");
        }
        p++;
        c.args = args.toArray(new Expr[args.size()]);
        c.star = new boolean[star.size()]; for (int i = 0; i < c.star.length; i++) c.star[i] = star.get(i).booleanValue();
        c.kwNames = kn.toArray(new String[kn.size()]); c.kwVals = kv.toArray(new Expr[kv.size()]);
        return c;
    }

    static String exprName(Expr e) {
        if (e.kind == ScriptAst.E_NAME) return ((Name) e).id;
        if (e.kind == ScriptAst.E_ATTR) return exprName(((Attr) e).obj) + "." + ((Attr) e).name;
        return "...";
    }

    private Expr subscript(Expr obj, Tok open) {
        Expr lo = null, hi = null, step = null; boolean slice = false;
        if (!isOp(":")) lo = test();
        if (isOp(",") && !slice) {
            ArrayList<Expr> items = new ArrayList<Expr>(); items.add(lo);
            while (acceptOp(",")) { if (isOp("]")) break; items.add(test()); }
            ListE tup = at(new ListE(), open); tup.tuple = true; tup.items = items.toArray(new Expr[items.size()]); lo = tup;
        }
        if (isOp(":")) {
            slice = true; p++;
            if (!isOp(":") && !isOp("]")) hi = test();
            if (acceptOp(":")) { if (!isOp("]")) step = test(); }
        }
        if (!isOp("]")) { Tok t = tok(); throw syntax("expected ']' but found " + describe(t), t).hint("check for a missing ']'"); }
        p++;
        if (slice) { Slice s = at(new Slice(), obj); s.obj = obj; s.lo = lo; s.hi = hi; s.step = step; return s; }
        Index ix = at(new Index(), obj); ix.obj = obj; ix.idx = lo;
        return ix;
    }

    private Expr comprehension(Expr elt, Expr val, Tok t, boolean isSet) {
        ListComp lc = at(new ListComp(), t);
        lc.elt = elt; lc.val = val; lc.isSet = isSet;
        ArrayList<Comp> gens = new ArrayList<Comp>();
        while (isName("for")) {
            p++;
            Comp c = new Comp();
            c.target = targetList();
            collectNames(c.target, lc.vars);
            if (!acceptName("in")) throw syntax("expected 'in' in the comprehension", tok());
            c.iter = orTest();
            ArrayList<Expr> ifs = new ArrayList<Expr>();
            while (isName("if")) { p++; ifs.add(orTest()); }
            c.ifs = ifs.toArray(new Expr[ifs.size()]);
            gens.add(c);
        }
        lc.gens = gens.toArray(new Comp[gens.size()]);
        return lc;
    }

    private void collectNames(Expr e, HashSet<String> out) {
        if (e.kind == ScriptAst.E_NAME) out.add(((Name) e).id);
        else if (e.kind == ScriptAst.E_LIST) { Expr[] it = ((ListE) e).items; for (int i = 0; i < it.length; i++) collectNames(it[i], out); }
        else if (e.kind == ScriptAst.E_STAR) collectNames(((Star) e).e, out);
        else throw new ScriptError("SyntaxError", "cannot use this as a loop variable", e.line, e.col).in(file);
    }

    private Expr atom() {
        Tok t = tok();
        switch (t.type) {
            case ScriptLexer.INT: p++; return at(new Const(Long.valueOf(t.ival)), t);
            case ScriptLexer.FLOAT: p++; return at(new Const(Double.valueOf(t.dval)), t);
            case ScriptLexer.STR: return strings();
            case ScriptLexer.NAME: {
                String w = t.text;
                if (w.equals("True")) { p++; return at(new Const(Boolean.TRUE), t); }
                if (w.equals("False")) { p++; return at(new Const(Boolean.FALSE), t); }
                if (w.equals("None")) { p++; return at(new Const(null), t); }
                if (KW.contains(w)) {
                    ScriptError e = syntax("unexpected '" + w + "' here", t);
                    if (w.equals("else") || w.equals("elif")) e.hint("check the indentation and the ':' of the line above");
                    else if (w.equals("in") || w.equals("is") || w.equals("and") || w.equals("or")) e.hint("'" + w + "' needs a value on its left side");
                    throw e;
                }
                p++;
                return at(new Name(w), t);
            }
            case ScriptLexer.OP: {
                String s = t.text;
                if (s.equals("(")) {
                    p++;
                    if (acceptOp(")")) { ListE l = at(new ListE(), t); l.tuple = true; l.items = new Expr[0]; return l; }
                    Expr e = exprList(true);
                    if (isName("for")) { e = comprehension(e, null, t, false); }
                    if (!isOp(")")) { Tok b = tok(); throw syntax("expected ')' but found " + describe(b), b).hint("a '(' on line " + t.line + " is not closed"); }
                    p++;
                    return e;
                }
                if (s.equals("[")) {
                    p++;
                    ListE l = at(new ListE(), t);
                    if (acceptOp("]")) { l.items = new Expr[0]; return l; }
                    ArrayList<Expr> items = new ArrayList<Expr>();
                    Expr first = isOp("*") ? starItem() : test();
                    if (isName("for")) {
                        Expr c = comprehension(first, null, t, false);
                        if (!isOp("]")) { Tok b = tok(); throw syntax("expected ']' but found " + describe(b), b); }
                        p++; return c;
                    }
                    items.add(first);
                    while (acceptOp(",")) { if (isOp("]")) break; items.add(isOp("*") ? starItem() : test()); }
                    if (!isOp("]")) { Tok b = tok(); throw syntax("expected ']' or ',' but found " + describe(b), b).hint("a '[' on line " + t.line + " is not closed, or a ',' is missing between items"); }
                    p++;
                    l.items = items.toArray(new Expr[items.size()]);
                    return l;
                }
                if (s.equals("{")) return braces(t);
                if (s.equals("&") && peekTok(1).type == ScriptLexer.OP && peekTok(1).text.equals("&")) throw syntax("'&&' is not valid", t).hint("use 'and'");
                if (s.equals("|") && peekTok(1).type == ScriptLexer.OP && peekTok(1).text.equals("|")) throw syntax("'||' is not valid", t).hint("use 'or'");
                if (s.equals("...")) { p++; return at(new Const(null), t); }
                throw syntax("invalid syntax: unexpected " + describe(t), t);
            }
            case ScriptLexer.NEWLINE: case ScriptLexer.EOF: {
                Tok prev = p > 0 ? toks.get(p - 1) : t;
                ScriptError e = new ScriptError("SyntaxError", "unexpected end of line: a value is missing here", prev.line, prev.col + Math.max(1, prev.text == null ? 1 : prev.text.length())).in(file);
                e.hint("the line ends after '" + (prev.text == null ? "" : prev.text) + "' - add the missing value");
                throw e;
            }
            default: throw syntax("invalid syntax: unexpected " + describe(t), t);
        }
    }

    private Expr starItem() { Tok s = next(); Star sr = at(new Star(), s); sr.e = bitOr(); return sr; }

    private Expr braces(Tok open) {
        p++;
        if (acceptOp("}")) { DictE d = at(new DictE(), open); d.keys = new Expr[0]; d.vals = new Expr[0]; return d; }
        Expr first = test();
        if (isOp(":")) {
            p++;
            Expr v = test();
            if (isName("for")) {
                Expr c = comprehension(first, v, open, false);
                if (!isOp("}")) { Tok b = tok(); throw syntax("expected '}' but found " + describe(b), b); }
                p++; return c;
            }
            ArrayList<Expr> ks = new ArrayList<Expr>(), vs = new ArrayList<Expr>();
            ks.add(first); vs.add(v);
            while (acceptOp(",")) {
                if (isOp("}")) break;
                Expr k = test();
                if (!acceptOp(":")) { Tok b = tok(); throw syntax("expected ':' between a dict key and its value but found " + describe(b), b); }
                ks.add(k); vs.add(test());
            }
            if (!isOp("}")) { Tok b = tok(); throw syntax("expected '}' or ',' but found " + describe(b), b).hint("a '{' on line " + open.line + " is not closed, or a ',' is missing"); }
            p++;
            DictE d = at(new DictE(), open); d.keys = ks.toArray(new Expr[ks.size()]); d.vals = vs.toArray(new Expr[vs.size()]);
            return d;
        }
        if (isName("for")) {
            Expr c = comprehension(first, null, open, true);
            if (!isOp("}")) { Tok b = tok(); throw syntax("expected '}' but found " + describe(b), b); }
            p++; return c;
        }
        ArrayList<Expr> items = new ArrayList<Expr>(); items.add(first);
        while (acceptOp(",")) { if (isOp("}")) break; items.add(test()); }
        if (!isOp("}")) { Tok b = tok(); throw syntax("expected '}' but found " + describe(b), b); }
        p++;
        SetE s = at(new SetE(), open); s.items = items.toArray(new Expr[items.size()]);
        return s;
    }

    // ------------------------------------------------------------ strings / f-strings
    private Expr strings() {
        Tok first = tok();
        ArrayList<Object> parts = new ArrayList<Object>();
        boolean anyF = false;
        StringBuilder lit = new StringBuilder();
        while (tok().type == ScriptLexer.STR) {
            Tok t = next();
            if (t.fstr) {
                anyF = true;
                fstringParts(t, parts, lit);
            } else lit.append(t.raw ? t.text : ScriptLexer.unescape(t.text));
        }
        if (!anyF) return at(new Const(lit.toString()), first);
        if (lit.length() > 0) parts.add(lit.toString());
        FStr f = at(new FStr(), first);
        f.parts = parts.toArray(new Object[parts.size()]);
        return f;
    }

    private void fstringParts(Tok t, ArrayList<Object> parts, StringBuilder lit) {
        String s = t.text;
        int i = 0, n = s.length();
        StringBuilder cur = new StringBuilder();
        int line = t.line;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') line++;
            if (c == '{') {
                if (i + 1 < n && s.charAt(i + 1) == '{') { cur.append('{'); i += 2; continue; }
                // flush literal
                lit.append(t.raw ? cur.toString() : ScriptLexer.unescape(cur.toString())); cur.setLength(0);
                if (lit.length() > 0) { parts.add(lit.toString()); lit.setLength(0); }
                int depth = 0, j = i + 1; char quote = 0;
                int exprEnd = -1, specStart = -1, convPos = -1;
                for (; j < n; j++) {
                    char d = s.charAt(j);
                    if (quote != 0) { if (d == quote) quote = 0; continue; }
                    if (d == '\'' || d == '"') { quote = d; continue; }
                    if (d == '(' || d == '[' || d == '{') depth++;
                    else if (d == ')' || d == ']') depth--;
                    else if (d == '}') { if (depth == 0) break; depth--; }
                    else if (depth == 0 && d == '!' && j + 1 < n && s.charAt(j + 1) != '=' && exprEnd < 0) { exprEnd = j; convPos = j + 1; }
                    else if (depth == 0 && d == ':' && exprEnd < 0) { exprEnd = j; specStart = j + 1; break; }
                    else if (depth == 0 && d == ':' && convPos >= 0 && specStart < 0) { specStart = j + 1; break; }
                }
                int close = j;
                if (specStart >= 0) { int dd = 0; for (close = specStart; close < n; close++) { char d = s.charAt(close); if (d == '{') dd++; else if (d == '}') { if (dd == 0) break; dd--; } } }
                if (close >= n) throw new ScriptError("SyntaxError", "f-string: expecting '}'", t.line, t.col).in(file).hint("every '{' in an f-string needs a closing '}' (use '{{' for a literal brace)");
                String exprText = s.substring(i + 1, exprEnd >= 0 ? exprEnd : close).trim();
                if (exprText.length() == 0) throw new ScriptError("SyntaxError", "f-string: empty expression between { }", line, t.col).in(file);
                FPart fp = new FPart();
                fp.e = parseExpression(exprText, file, line);
                if (convPos >= 0) fp.conv = s.substring(convPos, specStart >= 0 ? specStart - 1 : close).trim();
                if (specStart >= 0) fp.spec = s.substring(specStart, close);
                parts.add(fp);
                i = close + 1;
                continue;
            }
            if (c == '}') {
                if (i + 1 < n && s.charAt(i + 1) == '}') { cur.append('}'); i += 2; continue; }
                throw new ScriptError("SyntaxError", "f-string: single '}' is not allowed", line, t.col).in(file).hint("use '}}' for a literal brace");
            }
            cur.append(c); i++;
        }
        lit.append(t.raw ? cur.toString() : ScriptLexer.unescape(cur.toString()));
    }
}
