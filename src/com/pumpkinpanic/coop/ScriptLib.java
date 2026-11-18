package com.pumpkinpanic.coop;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.pumpkinpanic.coop.ScriptValues.*;

/** Built-in functions, methods of str / list / dict / set, formatting helpers. Java 7 compatible. */
final class ScriptLib {
    private ScriptLib() {}

    // ------------------------------------------------------------ small helpers
    static String joinList(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(", "); sb.append(l.get(i)); }
        return sb.toString();
    }

    static int lev(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i; int best = cur[0];
            for (int j = 1; j <= m; j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
                if (cur[j] < best) best = cur[j];
            }
            if (best > max) return max + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[m];
    }

    /** Closest known name for "did you mean ...?" or null. */
    static String suggest(String name, Collection<String> cands) {
        if (name == null || name.length() == 0) return null;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        int max = Math.max(1, Math.min(3, name.length() / 3));
        String best = null; int bd = max + 1;
        for (String c : cands) {
            if (c == null || c.equals(name) || c.startsWith("_")) continue;
            if (c.toLowerCase(java.util.Locale.ROOT).equals(lower) && !(c.length() > 1 && c.equals(c.toUpperCase(java.util.Locale.ROOT)) && !name.equals(name.toUpperCase(java.util.Locale.ROOT)))) return c;
            int d = lev(lower, c.toLowerCase(java.util.Locale.ROOT), max);
            if (d == 0) d = 2;                               // same letters, different case (e.g. tile / TILE): weaker than a real near-miss
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    // ------------------------------------------------------------ str / repr
    static String fmtDouble(double d) {
        if (d != d) return "nan";
        if (d == Double.POSITIVE_INFINITY) return "inf";
        if (d == Double.NEGATIVE_INFINITY) return "-inf";
        if (d == Math.rint(d) && Math.abs(d) < 1e16) { return Long.toString((long) d) + ".0"; }
        String s = Double.toString(d);
        int e = s.indexOf('E');
        if (e < 0) return s;
        double ad = Math.abs(d);
        if (ad >= 1e-4 && ad < 1e16) { String r = new BigDecimal(s).toPlainString(); return r.indexOf('.') < 0 ? r + ".0" : r; }
        String mant = s.substring(0, e); int ex = Integer.parseInt(s.substring(e + 1));
        if (mant.endsWith(".0")) mant = mant.substring(0, mant.length() - 2);
        return mant + "e" + (ex < 0 ? "-" : "+") + (Math.abs(ex) < 10 ? "0" : "") + Math.abs(ex);
    }

    static String quote(String s) {
        char q = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        StringBuilder sb = new StringBuilder(); sb.append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n"); else if (c == '\t') sb.append("\\t"); else if (c == '\r') sb.append("\\r");
            else if (c == '\\') sb.append("\\\\"); else if (c == q) sb.append('\\').append(c); else sb.append(c);
        }
        sb.append(q);
        return sb.toString();
    }

    static String str(Object o) { return o instanceof String ? (String) o : repr(o, 0); }
    static String repr(Object o) { return repr(o, 0); }

    static String repr(Object o, int depth) {
        if (o == null) return "None";
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? "True" : "False";
        if (o instanceof Long) return o.toString();
        if (o instanceof Double) return fmtDouble(((Double) o).doubleValue());
        if (o instanceof String) return depth == 0 && false ? (String) o : quote((String) o);
        if (depth > 12) return "...";
        if (o instanceof PyList) {
            PyList l = (PyList) o;
            StringBuilder sb = new StringBuilder(l.tuple ? "(" : "[");
            for (int i = 0; i < l.a.size(); i++) { if (i > 0) sb.append(", "); sb.append(repr(l.a.get(i), depth + 1)); if (sb.length() > 4000) { sb.append(", ..."); break; } }
            if (l.tuple && l.a.size() == 1) sb.append(",");
            sb.append(l.tuple ? ")" : "]");
            return sb.toString();
        }
        if (o instanceof PyDict) {
            StringBuilder sb = new StringBuilder("{"); boolean first = true;
            for (Map.Entry<Object, Object> e : ((PyDict) o).m.entrySet()) {
                if (!first) sb.append(", "); first = false;
                sb.append(repr(e.getKey(), depth + 1)).append(": ").append(repr(e.getValue(), depth + 1));
                if (sb.length() > 4000) { sb.append(", ..."); break; }
            }
            return sb.append("}").toString();
        }
        if (o instanceof PySet) {
            PySet s = (PySet) o;
            if (s.s.isEmpty()) return "set()";
            StringBuilder sb = new StringBuilder("{"); boolean first = true;
            for (Object x : s.s) { if (!first) sb.append(", "); first = false; sb.append(repr(x, depth + 1)); }
            return sb.append("}").toString();
        }
        if (o instanceof PyRange) { PyRange r = (PyRange) o; return "range(" + r.start + ", " + r.stop + (r.step != 1 ? ", " + r.step : "") + ")"; }
        if (o instanceof Native && isTypeName(((Native) o).name)) return "<class '" + ((Native) o).name + "'>";
        return o.toString();
    }

    static boolean isTypeName(String n) { return n.equals("int") || n.equals("float") || n.equals("str") || n.equals("bool") || n.equals("list") || n.equals("dict") || n.equals("tuple") || n.equals("set"); }

    // ------------------------------------------------------------ exceptions
    static final String[] EXC = {"Exception", "ValueError", "TypeError", "IndexError", "KeyError", "ZeroDivisionError", "NameError", "AttributeError",
        "RuntimeError", "AssertionError", "ArithmeticError", "LookupError", "ImportError", "StopIteration", "MemoryError", "RecursionError",
        "UnboundLocalError", "OverflowError", "ActionError", "TimeoutError"};
    static boolean isExcName(String n) { for (int i = 0; i < EXC.length; i++) if (EXC[i].equals(n)) return true; return false; }
    static String excParent(String t) {
        if (t.equals("Exception")) return null;
        if (t.equals("ZeroDivisionError") || t.equals("OverflowError")) return "ArithmeticError";
        if (t.equals("IndexError") || t.equals("KeyError")) return "LookupError";
        if (t.equals("UnboundLocalError")) return "NameError";
        if (t.equals("RecursionError") || t.equals("ActionError") || t.equals("TimeoutError")) return "RuntimeError";
        return "Exception";
    }
    static boolean excIsA(String thrown, String catchType) {
        for (String t = thrown; t != null; t = excParent(t)) if (t.equals(catchType)) return true;
        return false;
    }

    // ------------------------------------------------------------ keys, indexes, slices
    static Object normKey(Object k, ScriptInterp in, Frame f, ScriptAst.Node at) {
        if (k == null || k instanceof String || k instanceof Long) return k;
        if (k instanceof Boolean) return Long.valueOf(((Boolean) k).booleanValue() ? 1 : 0);
        if (k instanceof Double) { double d = ((Double) k).doubleValue(); if (d == Math.rint(d) && Math.abs(d) < 9e15) return Long.valueOf((long) d); return k; }
        if (k instanceof PyList) {
            PyList l = (PyList) k;
            if (!l.tuple) throw in.err(f, at, "TypeError", "unhashable type: 'list'").hint("use a tuple (a, b) instead of a list [a, b] as a dict key");
            PyList r = new PyList(true);
            for (int i = 0; i < l.a.size(); i++) r.a.add(normKey(l.a.get(i), in, f, at));
            return r;
        }
        if (k instanceof PyDict || k instanceof PySet) throw in.err(f, at, "TypeError", "unhashable type: '" + ScriptValues.typeName(k) + "'");
        return k;
    }

    static long index(Object key, int size, ScriptInterp in, Frame f, ScriptAst.Node at, String what) {
        if (!(key instanceof Long) && !(key instanceof Boolean)) {
            if (key instanceof Double) throw in.err(f, at, "TypeError", what + " indices must be integers, not float").hint("use int(x) or x // 1 to get a whole number");
            throw in.err(f, at, "TypeError", what + " indices must be integers, not " + ScriptValues.typeName(key));
        }
        long i = ScriptInterp.toL(key);
        long j = i < 0 ? i + size : i;
        if (j < 0 || j >= size) {
            ScriptError e = in.err(f, at, "IndexError", what + " index out of range: index " + i + " but the " + what + " has " + size + " item" + (size == 1 ? "" : "s"));
            e.hint(size == 0 ? "the " + what + " is empty - check len(...) first" : "valid indexes are 0 to " + (size - 1) + " (or -1 for the last)");
            throw e;
        }
        return j;
    }

    private static long sliceInt(Object o, ScriptInterp in, Frame f, ScriptAst.Node at) {
        if (o instanceof Long || o instanceof Boolean) return ScriptInterp.toL(o);
        throw in.err(f, at, "TypeError", "slice indices must be integers or None");
    }

    static Object slice(Object o, Object lo, Object hi, Object st, ScriptInterp in, Frame f, ScriptAst.Node at) {
        int n;
        if (o instanceof PyList) n = ((PyList) o).a.size();
        else if (o instanceof String) n = ((String) o).length();
        else if (o instanceof PyRange) n = (int) ((PyRange) o).size();
        else throw in.err(f, at, "TypeError", "'" + ScriptValues.typeName(o) + "' object cannot be sliced");
        long step = st == null ? 1 : sliceInt(st, in, f, at);
        if (step == 0) throw in.err(f, at, "ValueError", "slice step cannot be zero");
        long a, b;
        if (step > 0) {
            a = lo == null ? 0 : sliceInt(lo, in, f, at); b = hi == null ? n : sliceInt(hi, in, f, at);
            if (a < 0) a = Math.max(0, a + n); if (a > n) a = n;
            if (b < 0) b = Math.max(0, b + n); if (b > n) b = n;
        } else {
            a = lo == null ? n - 1 : sliceInt(lo, in, f, at); b = hi == null ? -1 : sliceInt(hi, in, f, at);
            if (lo != null) { if (a < 0) a += n; if (a >= n) a = n - 1; if (a < -1) a = -1; }
            if (hi != null) { if (b < 0) b += n; if (b >= n) b = n - 1; if (b < -1) b = -1; }
        }
        ArrayList<Object> items = new ArrayList<Object>();
        StringBuilder sb = o instanceof String ? new StringBuilder() : null;
        for (long i = a; step > 0 ? i < b : i > b; i += step) {
            if (o instanceof PyList) items.add(((PyList) o).a.get((int) i));
            else if (o instanceof String) sb.append(((String) o).charAt((int) i));
            else items.add(Long.valueOf(((PyRange) o).at(i)));
        }
        if (o instanceof String) return sb.toString();
        return new PyList(items, o instanceof PyList && ((PyList) o).tuple);
    }

    // ------------------------------------------------------------ number parsing / args
    static long argInt(Object o, String fn, String pn, ScriptInterp in, Frame f) {
        if (o instanceof Long) return ((Long) o).longValue();
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? 1 : 0;
        if (o instanceof Double && ((Double) o).doubleValue() == Math.rint(((Double) o).doubleValue())) return (long) ((Double) o).doubleValue();
        throw in.err(f, null, "TypeError", fn + "() argument '" + pn + "' must be a whole number, not " + describeVal(o));
    }
    static double argNum(Object o, String fn, String pn, ScriptInterp in, Frame f) {
        if (ScriptInterp.isNum(o)) return ScriptInterp.toD(o);
        throw in.err(f, null, "TypeError", fn + "() argument '" + pn + "' must be a number, not " + describeVal(o));
    }
    static String argStr(Object o, String fn, String pn, ScriptInterp in, Frame f) {
        if (o instanceof String) return (String) o;
        throw in.err(f, null, "TypeError", fn + "() argument '" + pn + "' must be text (str), not " + describeVal(o));
    }
    static String describeVal(Object o) { return ScriptValues.typeName(o) + (o == null ? "" : " " + shorten(repr(o), 24)); }
    static String shorten(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "..." : s; }

    // ------------------------------------------------------------ number formatting
    static String thousands(String intPart) {
        StringBuilder sb = new StringBuilder();
        int n = intPart.length();
        for (int i = 0; i < n; i++) { sb.append(intPart.charAt(i)); int rem = n - 1 - i; if (rem > 0 && rem % 3 == 0) sb.append(','); }
        return sb.toString();
    }

    static String formatSpec(Object v, String spec, ScriptInterp in, Frame f, ScriptAst.Node at) {
        char fill = ' ', align = 0, sign = '-', type = 0; boolean zero = false, comma = false; int width = 0, prec = -1, i = 0, n = spec.length();
        if (n >= 2 && "<>^=".indexOf(spec.charAt(1)) >= 0) { fill = spec.charAt(0); align = spec.charAt(1); i = 2; }
        else if (n >= 1 && "<>^=".indexOf(spec.charAt(0)) >= 0) { align = spec.charAt(0); i = 1; }
        if (i < n && (spec.charAt(i) == '+' || spec.charAt(i) == '-' || spec.charAt(i) == ' ')) sign = spec.charAt(i++);
        if (i < n && spec.charAt(i) == '#') i++;
        if (i < n && spec.charAt(i) == '0') { zero = true; i++; }
        int ws = i; while (i < n && Character.isDigit(spec.charAt(i))) i++;
        if (i > ws) width = Integer.parseInt(spec.substring(ws, i));
        if (i < n && (spec.charAt(i) == ',' || spec.charAt(i) == '_')) { comma = true; i++; }
        if (i < n && spec.charAt(i) == '.') { i++; int ps = i; while (i < n && Character.isDigit(spec.charAt(i))) i++; if (i == ps) throw in.err(f, at, "ValueError", "invalid format specifier '" + spec + "'"); prec = Integer.parseInt(spec.substring(ps, i)); }
        if (i < n) type = spec.charAt(i++);
        if (i < n) throw in.err(f, at, "ValueError", "invalid format specifier '" + spec + "'").hint("examples:  {x:.2f}  {n:5d}  {s:>10}  {n:,}");
        String body; boolean numeric = ScriptInterp.isNum(v) && !(v instanceof Boolean && type == 0);
        if (type == 's' || (!numeric && type == 0)) {
            body = str(v); if (prec >= 0 && prec < body.length()) body = body.substring(0, prec);
            if (type == 's' && !(v instanceof String)) body = str(v);
        } else {
            if (!ScriptInterp.isNum(v)) throw in.err(f, at, "ValueError", "format type '" + type + "' needs a number, not " + ScriptValues.typeName(v));
            boolean neg;
            if (type == 'd' || type == 'x' || type == 'X' || type == 'b' || type == 'o' || (type == 0 && ScriptInterp.isInt(v))) {
                if (!ScriptInterp.isInt(v)) { double dd = ScriptInterp.toD(v); if (type != 0 || dd != Math.rint(dd)) throw in.err(f, at, "ValueError", "format type '" + (type == 0 ? 'd' : type) + "' needs a whole number"); }
                long x = ScriptInterp.isInt(v) ? ScriptInterp.toL(v) : (long) ScriptInterp.toD(v);
                neg = x < 0; long ax = Math.abs(x);
                String s = type == 'x' ? Long.toHexString(ax) : (type == 'X' ? Long.toHexString(ax).toUpperCase(java.util.Locale.ROOT) : (type == 'b' ? Long.toBinaryString(ax) : (type == 'o' ? Long.toOctalString(ax) : Long.toString(ax))));
                if (comma) s = thousands(s);
                body = (neg ? "-" : (sign == '+' ? "+" : (sign == ' ' ? " " : ""))) + s;
            } else {
                double d = ScriptInterp.toD(v);
                neg = d < 0 || (d == 0 && 1 / d < 0);
                double ad = Math.abs(d); String s;
                if (d != d) s = "nan"; else if (Double.isInfinite(d)) s = "inf";
                else if (type == '%') { s = new BigDecimal(ad * 100).setScale(prec < 0 ? 6 : prec, RoundingMode.HALF_EVEN).toPlainString() + "%"; }
                else if (type == 'e' || type == 'E') { s = String.format(java.util.Locale.US, "%." + (prec < 0 ? 6 : prec) + "e", ad); if (type == 'E') s = s.toUpperCase(java.util.Locale.ROOT); }
                else if (type == 'g' || type == 'G') { s = String.format(java.util.Locale.US, "%." + (prec < 0 ? 6 : Math.max(1, prec)) + "g", ad); }
                else if (type == 'f' || type == 'F' || prec >= 0) { s = new BigDecimal(ad).setScale(prec < 0 ? 6 : prec, RoundingMode.HALF_EVEN).toPlainString(); }
                else s = fmtDouble(ad);
                if (comma && !(d != d)) { int dot = s.indexOf('.'); String ip = dot < 0 ? s : s.substring(0, dot); s = thousands(ip) + (dot < 0 ? "" : s.substring(dot)); }
                body = (neg ? "-" : (sign == '+' ? "+" : (sign == ' ' ? " " : ""))) + s;
            }
            if (zero && align == 0) { align = '='; fill = '0'; }
        }
        if (body.length() >= width) return body;
        int pad = width - body.length();
        if (align == 0) align = numeric ? '>' : '<';
        StringBuilder sb = new StringBuilder();
        if (align == '<') { sb.append(body); for (int k = 0; k < pad; k++) sb.append(fill); }
        else if (align == '>') { for (int k = 0; k < pad; k++) sb.append(fill); sb.append(body); }
        else if (align == '^') { int l = pad / 2; for (int k = 0; k < l; k++) sb.append(fill); sb.append(body); for (int k = 0; k < pad - l; k++) sb.append(fill); }
        else { int sg = (body.startsWith("-") || body.startsWith("+") || body.startsWith(" ")) ? 1 : 0; sb.append(body, 0, sg); for (int k = 0; k < pad; k++) sb.append(fill); sb.append(body.substring(sg)); }
        return sb.toString();
    }

    static String strFormat(String fmt, Object[] args, Map<String, Object> kw, ScriptInterp in, Frame f, ScriptAst.Node at) {
        StringBuilder sb = new StringBuilder(); int auto = 0, n = fmt.length();
        for (int i = 0; i < n; i++) {
            char c = fmt.charAt(i);
            if (c == '{') {
                if (i + 1 < n && fmt.charAt(i + 1) == '{') { sb.append('{'); i++; continue; }
                int j = fmt.indexOf('}', i);
                if (j < 0) throw in.err(f, at, "ValueError", "single '{' in the format string").hint("use '{{' for a literal brace");
                String field = fmt.substring(i + 1, j), spec = null, conv = null;
                int colon = field.indexOf(':'); if (colon >= 0) { spec = field.substring(colon + 1); field = field.substring(0, colon); }
                int bang = field.indexOf('!'); if (bang >= 0) { conv = field.substring(bang + 1); field = field.substring(0, bang); }
                Object v;
                if (field.length() == 0) { if (auto >= args.length) throw in.err(f, at, "IndexError", "not enough values for the {} placeholders in format()"); v = args[auto++]; }
                else if (Character.isDigit(field.charAt(0))) { int k = Integer.parseInt(field); if (k >= args.length) throw in.err(f, at, "IndexError", "format() has no argument number " + k); v = args[k]; }
                else {
                    if (kw == null || !kw.containsKey(field)) throw in.err(f, at, "KeyError", "format() has no keyword argument '" + field + "'");
                    v = kw.get(field);
                }
                if (conv != null) { if (conv.equals("r")) v = repr(v); else if (conv.equals("s")) v = str(v); }
                sb.append(spec != null && spec.length() > 0 ? formatSpec(v, spec, in, f, at) : str(v));
                i = j;
            } else if (c == '}') {
                if (i + 1 < n && fmt.charAt(i + 1) == '}') { sb.append('}'); i++; continue; }
                throw in.err(f, at, "ValueError", "single '}' in the format string").hint("use '}}' for a literal brace");
            } else sb.append(c);
        }
        return sb.toString();
    }

    static String percentFormat(String fmt, Object arg, ScriptInterp in, Frame f, ScriptAst.Node at) {
        Object[] args = arg instanceof PyList && ((PyList) arg).tuple ? ((PyList) arg).a.toArray() : new Object[] {arg};
        StringBuilder sb = new StringBuilder(); int ai = 0, n = fmt.length();
        for (int i = 0; i < n; i++) {
            char c = fmt.charAt(i);
            if (c != '%') { sb.append(c); continue; }
            i++;
            if (i >= n) throw in.err(f, at, "ValueError", "incomplete format");
            if (fmt.charAt(i) == '%') { sb.append('%'); continue; }
            StringBuilder spec = new StringBuilder();
            while (i < n && "-+ 0#".indexOf(fmt.charAt(i)) >= 0) spec.append(fmt.charAt(i++));
            while (i < n && Character.isDigit(fmt.charAt(i))) spec.append(fmt.charAt(i++));
            if (i < n && fmt.charAt(i) == '.') { spec.append('.'); i++; while (i < n && Character.isDigit(fmt.charAt(i))) spec.append(fmt.charAt(i++)); }
            if (i >= n) throw in.err(f, at, "ValueError", "incomplete format");
            char t = fmt.charAt(i);
            if (ai >= args.length) throw in.err(f, at, "TypeError", "not enough arguments for the format string");
            Object v = args[ai++];
            String sp = spec.toString(); boolean left = sp.indexOf('-') >= 0; sp = sp.replace("-", "").replace("+", "").replace("#", "");
            boolean zero = sp.startsWith("0"); if (zero) sp = sp.substring(1);
            String w = sp, pr = null; int dot = sp.indexOf('.'); if (dot >= 0) { w = sp.substring(0, dot); pr = sp.substring(dot); }
            String s;
            switch (t) {
                case 's': s = formatSpec(str(v), (pr == null ? "" : pr), in, f, at); break;
                case 'r': s = repr(v); break;
                case 'd': case 'i': s = formatSpec(Long.valueOf((long) ScriptInterp.toD(requireNum(v, in, f, at))), "d", in, f, at); break;
                case 'f': case 'e': case 'g': s = formatSpec(requireNum(v, in, f, at), (pr == null ? ".6" : pr) + t, in, f, at); break;
                case 'x': case 'X': case 'o': s = formatSpec(Long.valueOf((long) ScriptInterp.toD(requireNum(v, in, f, at))), "" + t, in, f, at); break;
                case 'c': s = v instanceof String ? (String) v : String.valueOf((char) ScriptInterp.toL(requireNum(v, in, f, at))); break;
                default: throw in.err(f, at, "ValueError", "unsupported format character '" + t + "'");
            }
            if (w.length() > 0) {
                int wd = Integer.parseInt(w);
                while (s.length() < wd) s = left ? s + " " : (zero && t != 's' ? (s.startsWith("-") ? "-0" + s.substring(1) : "0" + s) : " " + s);
            }
            sb.append(s);
        }
        if (ai < args.length && !(arg instanceof PyDict)) throw in.err(f, at, "TypeError", "not all arguments were used by the format string");
        return sb.toString();
    }
    private static Object requireNum(Object v, ScriptInterp in, Frame f, ScriptAst.Node at) {
        if (!ScriptInterp.isNum(v)) throw in.err(f, at, "TypeError", "a number is required for this format, not " + ScriptValues.typeName(v));
        return v;
    }

    // ------------------------------------------------------------ methods
    static final String[] STR_M = {"upper", "lower", "strip", "lstrip", "rstrip", "split", "rsplit", "splitlines", "join", "replace", "startswith", "endswith", "find", "rfind", "index", "count", "format",
        "isdigit", "isalpha", "isalnum", "isspace", "isupper", "islower", "title", "capitalize", "zfill", "center", "ljust", "rjust", "swapcase", "partition"};
    static final String[] LIST_M = {"append", "extend", "insert", "pop", "remove", "index", "count", "sort", "reverse", "copy", "clear"};
    static final String[] TUPLE_M = {"index", "count"};
    static final String[] DICT_M = {"get", "keys", "values", "items", "pop", "setdefault", "update", "clear", "copy", "popitem"};
    static final String[] SET_M = {"add", "remove", "discard", "clear", "copy", "union", "intersection", "difference", "update", "issubset", "issuperset", "pop"};

    static String[] methodNames(Object o) {
        if (o instanceof String) return STR_M;
        if (o instanceof PyList) return ((PyList) o).tuple ? TUPLE_M : LIST_M;
        if (o instanceof PyDict) return DICT_M;
        if (o instanceof PySet) return SET_M;
        return null;
    }
    static boolean hasMethod(Object o, String name) {
        String[] m = methodNames(o);
        if (m == null) return false;
        for (int i = 0; i < m.length; i++) if (m[i].equals(name)) return true;
        return false;
    }
    static boolean methodTakesKw(String name) { return name.equals("sort") || name.equals("format") || name.equals("split") || name.equals("rsplit"); }

    private static void arity(String name, Object[] a, int min, int max, ScriptInterp in, Frame f) {
        if (a.length < min || a.length > max)
            throw in.err(f, null, "TypeError", name + "() takes " + (min == max ? "exactly " + min : "from " + min + " to " + max) + " argument" + (max == 1 ? "" : "s") + " but " + a.length + " " + (a.length == 1 ? "was" : "were") + " given");
    }

    static Object invoke(final ScriptInterp in, final Frame f, Object self, String name, Object[] a, String[] kn, Object[] kv, ScriptAst.Node at) {
        if (self instanceof String) return strMethod(in, f, (String) self, name, a, kn, kv, at);
        if (self instanceof PyList) return listMethod(in, f, (PyList) self, name, a, kn, kv, at);
        if (self instanceof PyDict) return dictMethod(in, f, (PyDict) self, name, a, at);
        if (self instanceof PySet) return setMethod(in, f, (PySet) self, name, a, at);
        throw in.err(f, at, "AttributeError", "no method " + name);
    }

    private static String str1(Object o, String m, ScriptInterp in, Frame f) {
        if (o instanceof String) return (String) o;
        throw in.err(f, null, "TypeError", m + "() argument must be text (str), not " + ScriptValues.typeName(o));
    }

    private static Object strMethod(ScriptInterp in, Frame f, String s, String name, Object[] a, String[] kn, Object[] kv, ScriptAst.Node at) {
        switch (name) {
            case "upper": arity(name, a, 0, 0, in, f); return s.toUpperCase(java.util.Locale.ROOT);
            case "lower": arity(name, a, 0, 0, in, f); return s.toLowerCase(java.util.Locale.ROOT);
            case "title": { StringBuilder sb = new StringBuilder(); boolean up = true; for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c)); up = !Character.isLetter(c); } return sb.toString(); }
            case "capitalize": return s.length() == 0 ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
            case "swapcase": { StringBuilder sb = new StringBuilder(); for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c)); } return sb.toString(); }
            case "strip": case "lstrip": case "rstrip": {
                arity(name, a, 0, 1, in, f);
                String chars = a.length > 0 && a[0] != null ? str1(a[0], name, in, f) : null;
                int st = 0, en = s.length();
                if (!name.equals("rstrip")) while (st < en && (chars == null ? Character.isWhitespace(s.charAt(st)) : chars.indexOf(s.charAt(st)) >= 0)) st++;
                if (!name.equals("lstrip")) while (en > st && (chars == null ? Character.isWhitespace(s.charAt(en - 1)) : chars.indexOf(s.charAt(en - 1)) >= 0)) en--;
                return s.substring(st, en);
            }
            case "split": case "rsplit": {
                String sep = null; long max = -1;
                if (a.length > 0 && a[0] != null) sep = str1(a[0], name, in, f);
                if (a.length > 1) max = argInt(a[1], name, "maxsplit", in, f);
                for (int i = 0; i < kn.length; i++) { if (kn[i].equals("sep")) sep = kv[i] == null ? null : str1(kv[i], name, in, f); else if (kn[i].equals("maxsplit")) max = argInt(kv[i], name, "maxsplit", in, f); }
                PyList r = new PyList(false);
                if (sep == null) { String t = s.trim(); if (t.length() > 0) for (String p : t.split("\\s+")) r.a.add(p); return r; }
                if (sep.length() == 0) throw in.err(f, at, "ValueError", "empty separator");
                int pos = 0, cnt = 0;
                while (true) {
                    int k = s.indexOf(sep, pos);
                    if (k < 0 || (max >= 0 && cnt >= max)) { r.a.add(s.substring(pos)); break; }
                    r.a.add(s.substring(pos, k)); pos = k + sep.length(); cnt++;
                }
                return r;
            }
            case "splitlines": { PyList r = new PyList(false); if (s.length() > 0) for (String p : s.split("\n", -1)) r.a.add(p); if (r.a.size() > 0 && "".equals(r.a.get(r.a.size() - 1))) r.a.remove(r.a.size() - 1); return r; }
            case "join": {
                arity(name, a, 1, 1, in, f);
                StringBuilder sb = new StringBuilder(); boolean first = true;
                ScriptInterp.Iter it = in.iterate(a[0], f, at);
                while (it.hasNext()) {
                    Object x = it.next();
                    if (!(x instanceof String)) throw in.err(f, at, "TypeError", "join() needs text items but found " + ScriptValues.typeName(x)).hint("convert first: sep.join([str(x) for x in items])");
                    if (!first) sb.append(s); first = false; sb.append((String) x);
                }
                return sb.toString();
            }
            case "replace": arity(name, a, 2, 3, in, f); return s.replace(str1(a[0], name, in, f), str1(a[1], name, in, f));
            case "startswith": case "endswith": {
                arity(name, a, 1, 1, in, f);
                if (a[0] instanceof PyList) { for (Object o : ((PyList) a[0]).a) if (name.equals("startswith") ? s.startsWith(str1(o, name, in, f)) : s.endsWith(str1(o, name, in, f))) return Boolean.TRUE; return Boolean.FALSE; }
                return name.equals("startswith") ? s.startsWith(str1(a[0], name, in, f)) : s.endsWith(str1(a[0], name, in, f));
            }
            case "find": arity(name, a, 1, 2, in, f); return Long.valueOf(s.indexOf(str1(a[0], name, in, f), a.length > 1 ? (int) argInt(a[1], name, "start", in, f) : 0));
            case "rfind": arity(name, a, 1, 1, in, f); return Long.valueOf(s.lastIndexOf(str1(a[0], name, in, f)));
            case "index": { arity(name, a, 1, 1, in, f); int k = s.indexOf(str1(a[0], name, in, f)); if (k < 0) throw in.err(f, at, "ValueError", "substring not found").hint("use find() which returns -1 instead of raising"); return Long.valueOf(k); }
            case "count": { arity(name, a, 1, 1, in, f); String sub = str1(a[0], name, in, f); if (sub.length() == 0) return Long.valueOf(s.length() + 1); int c = 0, p = 0; while ((p = s.indexOf(sub, p)) >= 0) { c++; p += sub.length(); } return Long.valueOf(c); }
            case "format": {
                java.util.LinkedHashMap<String, Object> kw = new java.util.LinkedHashMap<String, Object>();
                for (int i = 0; i < kn.length; i++) kw.put(kn[i], kv[i]);
                return strFormat(s, a, kw, in, f, at);
            }
            case "isdigit": { if (s.length() == 0) return Boolean.FALSE; for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return Boolean.FALSE; return Boolean.TRUE; }
            case "isalpha": { if (s.length() == 0) return Boolean.FALSE; for (int i = 0; i < s.length(); i++) if (!Character.isLetter(s.charAt(i))) return Boolean.FALSE; return Boolean.TRUE; }
            case "isalnum": { if (s.length() == 0) return Boolean.FALSE; for (int i = 0; i < s.length(); i++) if (!Character.isLetterOrDigit(s.charAt(i))) return Boolean.FALSE; return Boolean.TRUE; }
            case "isspace": { if (s.length() == 0) return Boolean.FALSE; for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) return Boolean.FALSE; return Boolean.TRUE; }
            case "isupper": return s.length() > 0 && s.equals(s.toUpperCase(java.util.Locale.ROOT)) && !s.equals(s.toLowerCase(java.util.Locale.ROOT));
            case "islower": return s.length() > 0 && s.equals(s.toLowerCase(java.util.Locale.ROOT)) && !s.equals(s.toUpperCase(java.util.Locale.ROOT));
            case "zfill": { arity(name, a, 1, 1, in, f); int w = (int) argInt(a[0], name, "width", in, f); String r = s; boolean neg = r.startsWith("-"); if (neg) r = r.substring(1); while (r.length() + (neg ? 1 : 0) < w) r = "0" + r; return neg ? "-" + r : r; }
            case "center": case "ljust": case "rjust": {
                arity(name, a, 1, 2, in, f);
                int w = (int) argInt(a[0], name, "width", in, f); String fill = a.length > 1 ? str1(a[1], name, in, f) : " ";
                if (fill.length() != 1) throw in.err(f, at, "TypeError", name + "() fill must be one character");
                int pad = w - s.length(); if (pad <= 0) return s;
                int l = name.equals("ljust") ? 0 : (name.equals("rjust") ? pad : pad / 2);
                StringBuilder sb = new StringBuilder(); for (int i = 0; i < l; i++) sb.append(fill); sb.append(s); for (int i = 0; i < pad - l; i++) sb.append(fill);
                return sb.toString();
            }
            case "partition": { arity(name, a, 1, 1, in, f); String sep = str1(a[0], name, in, f); int k = s.indexOf(sep); return k < 0 ? PyList.tuple(s, "", "") : PyList.tuple(s.substring(0, k), sep, s.substring(k + sep.length())); }
            default: throw in.err(f, at, "AttributeError", "str has no method '" + name + "'");
        }
    }

    private static Object listMethod(final ScriptInterp in, final Frame f, PyList l, String name, Object[] a, String[] kn, Object[] kv, ScriptAst.Node at) {
        ArrayList<Object> x = l.a;
        if (l.tuple) {
            if (name.equals("index") || name.equals("count")) { /* fallthrough to shared impl below */ }
            else throw in.err(f, at, "AttributeError", "tuple has no method '" + name + "'");
        }
        switch (name) {
            case "append": arity(name, a, 1, 1, in, f); x.add(a[0]); return null;
            case "extend": { arity(name, a, 1, 1, in, f); x.addAll(in.toList(a[0], f, at)); return null; }
            case "insert": { arity(name, a, 2, 2, in, f); long i = argInt(a[0], name, "index", in, f); if (i < 0) i = Math.max(0, i + x.size()); if (i > x.size()) i = x.size(); x.add((int) i, a[1]); return null; }
            case "pop": {
                arity(name, a, 0, 1, in, f);
                if (x.isEmpty()) throw in.err(f, at, "IndexError", "pop from an empty list").hint("check  if len(list) > 0  first");
                int i = a.length == 0 ? x.size() - 1 : (int) index(a[0], x.size(), in, f, at, "list");
                return x.remove(i);
            }
            case "remove": { arity(name, a, 1, 1, in, f); for (int i = 0; i < x.size(); i++) if (ScriptInterp.valEq(x.get(i), a[0])) { x.remove(i); return null; } throw in.err(f, at, "ValueError", "list.remove(x): x is not in the list"); }
            case "index": { arity(name, a, 1, 1, in, f); for (int i = 0; i < x.size(); i++) if (ScriptInterp.valEq(x.get(i), a[0])) return Long.valueOf(i); throw in.err(f, at, "ValueError", repr(a[0]) + " is not in the list"); }
            case "count": { arity(name, a, 1, 1, in, f); int c = 0; for (int i = 0; i < x.size(); i++) if (ScriptInterp.valEq(x.get(i), a[0])) c++; return Long.valueOf(c); }
            case "reverse": Collections.reverse(x); return null;
            case "copy": return new PyList(new ArrayList<Object>(x), false);
            case "clear": x.clear(); return null;
            case "sort": {
                Object key = null; boolean rev = false;
                for (int i = 0; i < kn.length; i++) { if (kn[i].equals("key")) key = kv[i]; else if (kn[i].equals("reverse")) rev = ScriptInterp.truthy(kv[i]); else throw in.err(f, at, "TypeError", "sort() got an unexpected keyword argument '" + kn[i] + "'").hint("use sort(key=..., reverse=True)"); }
                if (a.length > 0) throw in.err(f, at, "TypeError", "sort() takes no positional arguments").hint("use sort(key=..., reverse=True)");
                sortList(in, f, x, key, rev, at);
                return null;
            }
            default: throw in.err(f, at, "AttributeError", "list has no method '" + name + "'");
        }
    }

    static void sortList(final ScriptInterp in, final Frame f, ArrayList<Object> x, final Object key, boolean rev, final ScriptAst.Node at) {
        final ArrayList<Object[]> pairs = new ArrayList<Object[]>(x.size());
        for (int i = 0; i < x.size(); i++) pairs.add(new Object[] {key == null ? x.get(i) : in.callSimple(key, f, at, x.get(i)), x.get(i)});
        Comparator<Object[]> c = new Comparator<Object[]>() {
            public int compare(Object[] p, Object[] q) { int r = in.cmp(p[0], q[0], f, at, "<"); return r == 2 ? 0 : r; }
        };
        Collections.sort(pairs, c);
        if (rev) Collections.reverse(pairs);
        x.clear();
        for (int i = 0; i < pairs.size(); i++) x.add(pairs.get(i)[1]);
    }

    private static Object dictMethod(ScriptInterp in, Frame f, PyDict d, String name, Object[] a, ScriptAst.Node at) {
        switch (name) {
            case "get": { arity(name, a, 1, 2, in, f); Object k = normKey(a[0], in, f, at); Object v = d.m.get(k); return v != null || d.m.containsKey(k) ? v : (a.length > 1 ? a[1] : null); }
            case "keys": { PyList r = new PyList(false); r.a.addAll(d.m.keySet()); return r; }
            case "values": { PyList r = new PyList(false); r.a.addAll(d.m.values()); return r; }
            case "items": { PyList r = new PyList(false); for (Map.Entry<Object, Object> e : d.m.entrySet()) r.a.add(PyList.tuple(e.getKey(), e.getValue())); return r; }
            case "pop": {
                arity(name, a, 1, 2, in, f); Object k = normKey(a[0], in, f, at);
                if (d.m.containsKey(k)) return d.m.remove(k);
                if (a.length > 1) return a[1];
                throw in.err(f, at, "KeyError", repr(a[0]));
            }
            case "popitem": { if (d.m.isEmpty()) throw in.err(f, at, "KeyError", "popitem(): dictionary is empty"); Object last = null; for (Object k : d.m.keySet()) last = k; Object v = d.m.remove(last); return PyList.tuple(last, v); }
            case "setdefault": { arity(name, a, 1, 2, in, f); Object k = normKey(a[0], in, f, at); if (!d.m.containsKey(k)) d.m.put(k, a.length > 1 ? a[1] : null); return d.m.get(k); }
            case "update": {
                arity(name, a, 1, 1, in, f);
                if (a[0] instanceof PyDict) d.m.putAll(((PyDict) a[0]).m);
                else { ScriptInterp.Iter it = in.iterate(a[0], f, at); while (it.hasNext()) { Object p = it.next(); if (!(p instanceof PyList) || ((PyList) p).a.size() != 2) throw in.err(f, at, "ValueError", "update() needs a dict or a list of (key, value) pairs"); d.m.put(normKey(((PyList) p).a.get(0), in, f, at), ((PyList) p).a.get(1)); } }
                return null;
            }
            case "clear": d.m.clear(); return null;
            case "copy": { PyDict r = new PyDict(); r.m.putAll(d.m); return r; }
            default: throw in.err(f, at, "AttributeError", "dict has no method '" + name + "'");
        }
    }

    private static Object setMethod(ScriptInterp in, Frame f, PySet s, String name, Object[] a, ScriptAst.Node at) {
        switch (name) {
            case "add": arity(name, a, 1, 1, in, f); s.s.add(normKey(a[0], in, f, at)); return null;
            case "remove": { arity(name, a, 1, 1, in, f); Object k = normKey(a[0], in, f, at); if (!s.s.remove(k)) throw in.err(f, at, "KeyError", repr(a[0])).hint("use discard() to ignore missing items"); return null; }
            case "discard": arity(name, a, 1, 1, in, f); s.s.remove(normKey(a[0], in, f, at)); return null;
            case "clear": s.s.clear(); return null;
            case "copy": { PySet r = new PySet(); r.s.addAll(s.s); return r; }
            case "pop": { if (s.s.isEmpty()) throw in.err(f, at, "KeyError", "pop from an empty set"); Object k = s.s.iterator().next(); s.s.remove(k); return k; }
            case "union": case "update": case "intersection": case "difference": case "issubset": case "issuperset": {
                arity(name, a, 1, 1, in, f);
                HashSet<Object> o = new HashSet<Object>();
                ScriptInterp.Iter it = in.iterate(a[0], f, at);
                while (it.hasNext()) o.add(normKey(it.next(), in, f, at));
                if (name.equals("update")) { s.s.addAll(o); return null; }
                if (name.equals("issubset")) return o.containsAll(s.s);
                if (name.equals("issuperset")) return s.s.containsAll(o);
                PySet r = new PySet();
                if (name.equals("union")) { r.s.addAll(s.s); r.s.addAll(o); }
                else if (name.equals("intersection")) { for (Object x : s.s) if (o.contains(x)) r.s.add(x); }
                else { for (Object x : s.s) if (!o.contains(x)) r.s.add(x); }
                return r;
            }
            default: throw in.err(f, at, "AttributeError", "set has no method '" + name + "'");
        }
    }

    // ------------------------------------------------------------ builtin functions
    /** Built-in function with a name; dispatch by switch. */
    static final class Bi extends Native {
        Bi(String name, String sig, String doc) { super(name, sig); this.doc = doc; this.category = "Python basics"; }
        @Override Object call(Frame f, ScriptInterp in, Object[] a) { return ScriptLib.callBuiltin(name, f, in, a, null); }
        @Override Object callRaw(Frame f, ScriptInterp in, Object[] pos, Map<String, Object> kw) { return ScriptLib.callBuiltin(name, f, in, pos, kw); }
    }

    private static void def(ScriptInterp in, String name, String sig, String doc) { Bi b = new Bi(name, sig, doc); in.add(b); }

    static void install(ScriptInterp in) {
        def(in, "print", "*values", "print(*values, sep=\" \", end=\"\\n\")  show values in the console");
        def(in, "len", "x", "number of items in a list / string / dict");
        def(in, "range", "a, b=None, c=None", "range(n) or range(start, stop, step): the numbers for a for loop");
        def(in, "min", "*values", "smallest value:  min(a, b)  or  min(list)  or  min(list, key=fn)");
        def(in, "max", "*values", "biggest value:  max(a, b)  or  max(list)  or  max(list, key=fn)");
        def(in, "abs", "x", "absolute value");
        def(in, "sum", "items, start=0", "add up all numbers in a list");
        def(in, "int", "x=0, base=None", "convert to a whole number: int(\"42\"), int(3.9) -> 3");
        def(in, "float", "x=0", "convert to a decimal number");
        def(in, "str", "x=\"\"", "convert to text");
        def(in, "bool", "x=False", "True / False version of a value");
        def(in, "round", "x, digits=None", "round(2.567, 1) -> 2.6");
        def(in, "sorted", "items, key=None, reverse=False", "new sorted list:  sorted(tiles(), key=lambda t: t.dist)");
        def(in, "reversed", "items", "list in reverse order");
        def(in, "list", "items=None", "make a list");
        def(in, "tuple", "items=None", "make a tuple");
        def(in, "dict", "*pairs", "make a dict:  dict(a=1, b=2)");
        def(in, "set", "items=None", "make a set (unique values)");
        def(in, "enumerate", "items, start=0", "(index, item) pairs:  for i, x in enumerate(xs):");
        def(in, "zip", "*lists", "pair up lists:  for a, b in zip(xs, ys):");
        def(in, "map", "*args", "map(fn, items) -> list of fn(item)");
        def(in, "filter", "fn, items", "filter(fn, items) -> items where fn(item) is true");
        def(in, "any", "items", "True if any item is true");
        def(in, "all", "items", "True if all items are true");
        def(in, "type", "x", "the type of a value");
        def(in, "isinstance", "x, kind", "isinstance(x, int)");
        def(in, "repr", "x", "text form of a value, with quotes on strings");
        def(in, "chr", "n", "character with this code");
        def(in, "ord", "c", "code of a character");
        def(in, "divmod", "a, b", "(a // b, a % b)");
        def(in, "pow", "a, b", "a to the power b");
        def(in, "format", "x, spec=\"\"", "format(3.14159, \".2f\") -> \"3.14\"");
        def(in, "callable", "x", "is it a function?");
        def(in, "hasattr", "obj, name", "does the object have this attribute?");
        def(in, "getattr", "obj, name, default=None", "read an attribute by name");
        def(in, "dir", "obj=None", "list of attribute names of an object");
        def(in, "help", "what=None", "help(plant) prints the description of a function");
        // math
        String[] m1 = {"sqrt", "floor", "ceil", "sin", "cos", "tan", "asin", "acos", "atan", "exp", "radians", "degrees", "sign"};
        for (int i = 0; i < m1.length; i++) { Bi b = new Bi(m1[i], "x", m1[i] + "(x)  math function"); b.category = "Math"; in.add(b); }
        Bi b;
        b = new Bi("atan2", "y, x", "atan2(y, x)  angle in radians"); b.category = "Math"; in.add(b);
        b = new Bi("log", "x, base=None", "natural logarithm, or log(x, base)"); b.category = "Math"; in.add(b);
        b = new Bi("hypot", "a, b", "sqrt(a*a + b*b)"); b.category = "Math"; in.add(b);
        b = new Bi("clamp", "x, lo, hi", "limit x to the range lo..hi"); b.category = "Math"; in.add(b);
        b = new Bi("lerp", "a, b, t", "a + (b - a) * t"); b.category = "Math"; in.add(b);
        b = new Bi("dist", "x1, y1, x2, y2", "distance between two points"); b.category = "Math"; in.add(b);
        b = new Bi("random", "", "random decimal between 0 and 1"); b.category = "Math"; in.add(b);
        b = new Bi("randint", "a, b", "random whole number from a to b (both included)"); b.category = "Math"; in.add(b);
        b = new Bi("randrange", "a, b=None", "random whole number: randrange(5) -> 0..4"); b.category = "Math"; in.add(b);
        b = new Bi("uniform", "a, b", "random decimal from a to b"); b.category = "Math"; in.add(b);
        b = new Bi("choice", "items", "random item of a list"); b.category = "Math"; in.add(b);
        b = new Bi("shuffle", "items", "shuffle a list in place"); b.category = "Math"; in.add(b);
        b = new Bi("sample", "items, k", "k random different items"); b.category = "Math"; in.add(b);
        b = new Bi("seed", "n", "make random numbers repeatable"); b.category = "Math"; in.add(b);
        in.constant("pi", Double.valueOf(Math.PI));
        in.constant("tau", Double.valueOf(Math.PI * 2));
        in.constant("inf", Double.valueOf(Double.POSITIVE_INFINITY));
        for (int i = 0; i < EXC.length; i++) {
            final String en = EXC[i];
            Native n = new Native(en, "message=\"\"") {
                @Override Object call(Frame f, ScriptInterp in2, Object[] a) { return new ScriptInterp.ExcValue(name, a[0] == ScriptValues.NOARG ? "" : str(a[0]), f.line); }
            };
            n.doc = "error type: raise " + en + "(\"message\")"; n.category = "Errors";
            in.add(n);
        }
    }

    private static double d1(Object[] a, String fn, ScriptInterp in, Frame f) { return argNum(a[0], fn, "x", in, f); }

    static Object callBuiltin(String name, Frame f, ScriptInterp in, Object[] a, Map<String, Object> kw) {
        switch (name) {
            case "print": {
                String sep = " ", end = "\n";
                if (kw != null) for (Map.Entry<String, Object> e : kw.entrySet()) {
                    if (e.getKey().equals("sep")) sep = str(e.getValue()); else if (e.getKey().equals("end")) end = str(e.getValue());
                    else throw in.err(f, null, "TypeError", "print() got an unexpected keyword argument '" + e.getKey() + "'").hint("print accepts sep= and end=");
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(sep); sb.append(str(a[i])); }
                in.printRaw(sb.toString() + end);
                return null;
            }
            case "len": {
                Object x = a[0];
                if (x instanceof String) return Long.valueOf(((String) x).length());
                if (x instanceof PyList) return Long.valueOf(((PyList) x).a.size());
                if (x instanceof PyDict) return Long.valueOf(((PyDict) x).m.size());
                if (x instanceof PySet) return Long.valueOf(((PySet) x).s.size());
                if (x instanceof PyRange) return Long.valueOf(((PyRange) x).size());
                throw in.err(f, null, "TypeError", "object of type '" + ScriptValues.typeName(x) + "' has no len()").hint(x == null ? "the value is None - did a function return nothing?" : null);
            }
            case "range": {
                long s = 0, e, st = 1;
                if (a[1] == ScriptValues.NOARG || a[1] == null) e = argInt(a[0], name, "stop", in, f);
                else { s = argInt(a[0], name, "start", in, f); e = argInt(a[1], name, "stop", in, f); if (a[2] != ScriptValues.NOARG && a[2] != null) st = argInt(a[2], name, "step", in, f); }
                if (st == 0) throw in.err(f, null, "ValueError", "range() step must not be zero");
                return new PyRange(s, e, st);
            }
            case "min": case "max": {
                boolean mx = name.equals("max"); Object key = null, dflt = ScriptValues.NOARG;
                if (kw != null) for (Map.Entry<String, Object> e : kw.entrySet()) { if (e.getKey().equals("key")) key = e.getValue(); else if (e.getKey().equals("default")) dflt = e.getValue(); else throw in.err(f, null, "TypeError", name + "() got an unexpected keyword argument '" + e.getKey() + "'"); }
                ArrayList<Object> items = a.length == 1 ? in.toList(a[0], f, null) : new ArrayList<Object>(java.util.Arrays.asList(a));
                if (a.length == 0) throw in.err(f, null, "TypeError", name + "() needs at least one argument");
                if (items.isEmpty()) { if (dflt != ScriptValues.NOARG) return dflt; throw in.err(f, null, "ValueError", name + "() arg is an empty list").hint("check the list is not empty, or pass default=..."); }
                Object best = items.get(0), bk = key == null ? best : in.callSimple(key, f, null, best);
                for (int i = 1; i < items.size(); i++) {
                    Object it = items.get(i), k = key == null ? it : in.callSimple(key, f, null, it);
                    int c = in.cmp(k, bk, f, null, mx ? ">" : "<");
                    if (mx ? c == 1 : c == -1) { best = it; bk = k; }
                }
                return best;
            }
            case "abs": { Object x = a[0]; if (x instanceof Long) return Long.valueOf(Math.abs(((Long) x).longValue())); if (x instanceof Boolean) return Long.valueOf(ScriptInterp.toL(x)); if (x instanceof Double) return Double.valueOf(Math.abs(((Double) x).doubleValue())); throw in.err(f, null, "TypeError", "abs() needs a number, not " + ScriptValues.typeName(x)); }
            case "sum": {
                Object acc = a[1] == ScriptValues.NOARG ? Long.valueOf(0) : a[1];
                ScriptInterp.Iter it = in.iterate(a[0], f, null);
                while (it.hasNext()) acc = in.binop(ScriptAst.ADD, acc, it.next(), f, null);
                return acc;
            }
            case "int": {
                Object x = a[0] == ScriptValues.NOARG ? Long.valueOf(0) : a[0];
                if (x instanceof Long) return x;
                if (x instanceof Boolean) return Long.valueOf(ScriptInterp.toL(x));
                if (x instanceof Double) { double d = ((Double) x).doubleValue(); if (d != d || Double.isInfinite(d)) throw in.err(f, null, "ValueError", "cannot convert " + fmtDouble(d) + " to int"); return Long.valueOf((long) d); }
                if (x instanceof String) {
                    String s = ((String) x).trim(); int base = a[1] == ScriptValues.NOARG || a[1] == null ? 10 : (int) argInt(a[1], name, "base", in, f);
                    try { return Long.valueOf(Long.parseLong(s.replace("_", ""), base)); }
                    catch (NumberFormatException e) {
                        try { double d = Double.parseDouble(s); if (base == 10 && s.indexOf('.') >= 0) throw in.err(f, null, "ValueError", "invalid literal for int(): " + quote((String) x)).hint("use int(float(text)) for decimal text"); } catch (NumberFormatException e2) { /* fallthrough */ }
                        throw in.err(f, null, "ValueError", "invalid literal for int(): " + quote((String) x));
                    }
                }
                throw in.err(f, null, "TypeError", "int() can't convert " + ScriptValues.typeName(x));
            }
            case "float": {
                Object x = a[0] == ScriptValues.NOARG ? Long.valueOf(0) : a[0];
                if (ScriptInterp.isNum(x)) return Double.valueOf(ScriptInterp.toD(x));
                if (x instanceof String) {
                    String s = ((String) x).trim().toLowerCase(java.util.Locale.ROOT);
                    if (s.equals("inf") || s.equals("infinity")) return Double.valueOf(Double.POSITIVE_INFINITY);
                    if (s.equals("-inf")) return Double.valueOf(Double.NEGATIVE_INFINITY);
                    if (s.equals("nan")) return Double.valueOf(Double.NaN);
                    try { return Double.valueOf(Double.parseDouble(s.replace("_", ""))); } catch (NumberFormatException e) { throw in.err(f, null, "ValueError", "could not convert text to float: " + quote((String) x)); }
                }
                throw in.err(f, null, "TypeError", "float() can't convert " + ScriptValues.typeName(x));
            }
            case "str": return a[0] == ScriptValues.NOARG ? "" : str(a[0]);
            case "bool": return a[0] == ScriptValues.NOARG ? Boolean.FALSE : Boolean.valueOf(ScriptInterp.truthy(a[0]));
            case "round": {
                double d = argNum(a[0], name, "x", in, f);
                if (a[1] == ScriptValues.NOARG || a[1] == null) { if (d != d || Double.isInfinite(d)) throw in.err(f, null, "ValueError", "cannot round " + fmtDouble(d)); return Long.valueOf((long) Math.rint(d)); }
                int nd = (int) argInt(a[1], name, "digits", in, f);
                if (d != d || Double.isInfinite(d)) return Double.valueOf(d);
                if (a[0] instanceof Long && nd >= 0) return a[0];
                return Double.valueOf(new BigDecimal(d).setScale(nd, RoundingMode.HALF_EVEN).doubleValue());
            }
            case "sorted": {
                ArrayList<Object> l = in.toList(a[0], f, null);
                sortList(in, f, l, a[1] == ScriptValues.NOARG ? null : a[1], a[2] != ScriptValues.NOARG && ScriptInterp.truthy(a[2]), null);
                return new PyList(l, false);
            }
            case "reversed": { ArrayList<Object> l = in.toList(a[0], f, null); Collections.reverse(l); return new PyList(l, false); }
            case "list": return new PyList(a[0] == ScriptValues.NOARG || a[0] == null ? new ArrayList<Object>() : in.toList(a[0], f, null), false);
            case "tuple": return new PyList(a[0] == ScriptValues.NOARG || a[0] == null ? new ArrayList<Object>() : in.toList(a[0], f, null), true);
            case "set": {
                PySet s = new PySet();
                if (a[0] != ScriptValues.NOARG && a[0] != null) { ScriptInterp.Iter it = in.iterate(a[0], f, null); while (it.hasNext()) s.s.add(normKey(it.next(), in, f, null)); }
                return s;
            }
            case "dict": {
                PyDict d = new PyDict();
                if (a.length > 1) throw in.err(f, null, "TypeError", "dict() takes at most 1 positional argument");
                if (a.length == 1) {
                    if (a[0] instanceof PyDict) d.m.putAll(((PyDict) a[0]).m);
                    else { ScriptInterp.Iter it = in.iterate(a[0], f, null); while (it.hasNext()) { Object p = it.next(); if (!(p instanceof PyList) || ((PyList) p).a.size() != 2) throw in.err(f, null, "ValueError", "dict() needs a list of (key, value) pairs"); d.m.put(normKey(((PyList) p).a.get(0), in, f, null), ((PyList) p).a.get(1)); } }
                }
                if (kw != null) d.m.putAll(kw);
                return d;
            }
            case "enumerate": {
                PyList r = new PyList(false); long i = a[1] == ScriptValues.NOARG ? 0 : argInt(a[1], name, "start", in, f);
                ScriptInterp.Iter it = in.iterate(a[0], f, null);
                while (it.hasNext()) r.a.add(PyList.tuple(Long.valueOf(i++), it.next()));
                return r;
            }
            case "zip": {
                ArrayList<ArrayList<Object>> ls = new ArrayList<ArrayList<Object>>(); int n = Integer.MAX_VALUE;
                for (int i = 0; i < a.length; i++) { ArrayList<Object> l = in.toList(a[i], f, null); ls.add(l); n = Math.min(n, l.size()); }
                PyList r = new PyList(false);
                if (a.length == 0) return r;
                for (int i = 0; i < n; i++) { PyList t = new PyList(true); for (int j = 0; j < ls.size(); j++) t.a.add(ls.get(j).get(i)); r.a.add(t); }
                return r;
            }
            case "map": {
                if (a.length < 2) throw in.err(f, null, "TypeError", "map() needs a function and a list").hint("map(fn, items)");
                PyList r = new PyList(false);
                if (a.length == 2) { ScriptInterp.Iter it = in.iterate(a[1], f, null); while (it.hasNext()) r.a.add(in.callSimple(a[0], f, null, it.next())); return r; }
                ArrayList<ArrayList<Object>> ls = new ArrayList<ArrayList<Object>>(); int n = Integer.MAX_VALUE;
                for (int i = 1; i < a.length; i++) { ArrayList<Object> l = in.toList(a[i], f, null); ls.add(l); n = Math.min(n, l.size()); }
                for (int i = 0; i < n; i++) { Object[] args = new Object[ls.size()]; for (int j = 0; j < args.length; j++) args[j] = ls.get(j).get(i); r.a.add(in.call(a[0], args, new String[0], new Object[0], f, null)); }
                return r;
            }
            case "filter": {
                PyList r = new PyList(false); ScriptInterp.Iter it = in.iterate(a[1], f, null);
                while (it.hasNext()) { Object x = it.next(); if (ScriptInterp.truthy(a[0] == null ? x : in.callSimple(a[0], f, null, x))) r.a.add(x); }
                return r;
            }
            case "any": { ScriptInterp.Iter it = in.iterate(a[0], f, null); while (it.hasNext()) if (ScriptInterp.truthy(it.next())) return Boolean.TRUE; return Boolean.FALSE; }
            case "all": { ScriptInterp.Iter it = in.iterate(a[0], f, null); while (it.hasNext()) if (!ScriptInterp.truthy(it.next())) return Boolean.FALSE; return Boolean.TRUE; }
            case "type": {
                Object x = a[0]; String tn = ScriptValues.typeName(x);
                Object t = in.builtins.get(tn);
                return t instanceof Native && isTypeName(tn) ? t : tn;
            }
            case "isinstance": {
                Object k = a[1];
                if (k instanceof PyList) { for (Object o : ((PyList) k).a) if (isInst(a[0], o)) return Boolean.TRUE; return Boolean.FALSE; }
                return Boolean.valueOf(isInst(a[0], k));
            }
            case "repr": return repr(a[0]);
            case "chr": return String.valueOf((char) argInt(a[0], name, "n", in, f));
            case "ord": { String s = argStr(a[0], name, "c", in, f); if (s.length() != 1) throw in.err(f, null, "TypeError", "ord() expects a single character"); return Long.valueOf(s.charAt(0)); }
            case "divmod": { Object q = in.binop(ScriptAst.FDIV, a[0], a[1], f, null), r = in.binop(ScriptAst.MOD, a[0], a[1], f, null); return PyList.tuple(q, r); }
            case "pow": return in.binop(ScriptAst.POW, a[0], a[1], f, null);
            case "format": return formatSpec(a[0], a[1] == ScriptValues.NOARG ? "" : argStr(a[1], name, "spec", in, f), in, f, null);
            case "callable": return Boolean.valueOf(a[0] instanceof Fn || a[0] instanceof Native || a[0] instanceof BoundMethod);
            case "hasattr": { String n = argStr(a[1], name, "name", in, f); return Boolean.valueOf(a[0] instanceof ScriptObject ? ((ScriptObject) a[0]).hasAttr(n) : hasMethod(a[0], n)); }
            case "getattr": {
                String n = argStr(a[1], name, "name", in, f);
                if (a[0] instanceof ScriptObject && ((ScriptObject) a[0]).hasAttr(n)) return ((ScriptObject) a[0]).getAttr(n);
                if (hasMethod(a[0], n)) return new BoundMethod(a[0], n);
                if (a[2] != ScriptValues.NOARG) return a[2];
                throw in.err(f, null, "AttributeError", "'" + ScriptValues.typeName(a[0]) + "' object has no attribute '" + n + "'");
            }
            case "dir": {
                PyList r = new PyList(false);
                if (a[0] == ScriptValues.NOARG || a[0] == null) { ArrayList<String> ns = new ArrayList<String>(in.globals.vars.keySet()); Collections.sort(ns); for (String s : ns) r.a.add(s); return r; }
                String[] ns = a[0] instanceof ScriptObject ? ((ScriptObject) a[0]).attrNames() : methodNames(a[0]);
                if (ns != null) for (String s : ns) r.a.add(s);
                return r;
            }
            case "help": return help(a[0], f, in);
            case "sqrt": { double d = d1(a, name, in, f); if (d < 0) throw in.err(f, null, "ValueError", "sqrt() of a negative number"); return Double.valueOf(Math.sqrt(d)); }
            case "floor": { double d = d1(a, name, in, f); return Long.valueOf((long) Math.floor(d)); }
            case "ceil": { double d = d1(a, name, in, f); return Long.valueOf((long) Math.ceil(d)); }
            case "sin": return Double.valueOf(Math.sin(d1(a, name, in, f)));
            case "cos": return Double.valueOf(Math.cos(d1(a, name, in, f)));
            case "tan": return Double.valueOf(Math.tan(d1(a, name, in, f)));
            case "asin": return Double.valueOf(Math.asin(d1(a, name, in, f)));
            case "acos": return Double.valueOf(Math.acos(d1(a, name, in, f)));
            case "atan": return Double.valueOf(Math.atan(d1(a, name, in, f)));
            case "exp": return Double.valueOf(Math.exp(d1(a, name, in, f)));
            case "radians": return Double.valueOf(Math.toRadians(d1(a, name, in, f)));
            case "degrees": return Double.valueOf(Math.toDegrees(d1(a, name, in, f)));
            case "sign": { double d = d1(a, name, in, f); return Long.valueOf(d > 0 ? 1 : (d < 0 ? -1 : 0)); }
            case "atan2": return Double.valueOf(Math.atan2(argNum(a[0], name, "y", in, f), argNum(a[1], name, "x", in, f)));
            case "log": {
                double d = argNum(a[0], name, "x", in, f);
                if (d <= 0) throw in.err(f, null, "ValueError", "log() needs a positive number");
                if (a[1] == ScriptValues.NOARG || a[1] == null) return Double.valueOf(Math.log(d));
                return Double.valueOf(Math.log(d) / Math.log(argNum(a[1], name, "base", in, f)));
            }
            case "hypot": return Double.valueOf(Math.hypot(argNum(a[0], name, "a", in, f), argNum(a[1], name, "b", in, f)));
            case "clamp": {
                Object x = a[0], lo = a[1], hi = a[2];
                argNum(x, name, "x", in, f); argNum(lo, name, "lo", in, f); argNum(hi, name, "hi", in, f);
                if (in.cmp(x, lo, f, null, "<") == -1) return lo;
                if (in.cmp(x, hi, f, null, ">") == 1) return hi;
                return x;
            }
            case "lerp": { double x = argNum(a[0], name, "a", in, f), y = argNum(a[1], name, "b", in, f), t = argNum(a[2], name, "t", in, f); return Double.valueOf(x + (y - x) * t); }
            case "dist": return Double.valueOf(Math.hypot(argNum(a[2], name, "x2", in, f) - argNum(a[0], name, "x1", in, f), argNum(a[3], name, "y2", in, f) - argNum(a[1], name, "y1", in, f)));
            case "random": return Double.valueOf(in.rnd.nextDouble());
            case "randint": {
                long lo = argInt(a[0], name, "a", in, f), hi = argInt(a[1], name, "b", in, f);
                if (hi < lo) throw in.err(f, null, "ValueError", "randint(a, b) needs a <= b");
                return Long.valueOf(lo + (long) (in.rnd.nextDouble() * (hi - lo + 1)));
            }
            case "randrange": {
                long lo = 0, hi;
                if (a[1] == ScriptValues.NOARG || a[1] == null) hi = argInt(a[0], name, "stop", in, f); else { lo = argInt(a[0], name, "start", in, f); hi = argInt(a[1], name, "stop", in, f); }
                if (hi <= lo) throw in.err(f, null, "ValueError", "randrange() range is empty");
                return Long.valueOf(lo + (long) (in.rnd.nextDouble() * (hi - lo)));
            }
            case "uniform": { double lo = argNum(a[0], name, "a", in, f), hi = argNum(a[1], name, "b", in, f); return Double.valueOf(lo + in.rnd.nextDouble() * (hi - lo)); }
            case "choice": {
                ArrayList<Object> l = in.toList(a[0], f, null);
                if (l.isEmpty()) throw in.err(f, null, "IndexError", "choice() from an empty list").hint("check the list is not empty first");
                return l.get(in.rnd.nextInt(l.size()));
            }
            case "shuffle": { if (!(a[0] instanceof PyList) || ((PyList) a[0]).tuple) throw in.err(f, null, "TypeError", "shuffle() needs a list"); Collections.shuffle(((PyList) a[0]).a, in.rnd); return null; }
            case "sample": {
                ArrayList<Object> l = in.toList(a[0], f, null); long k = argInt(a[1], name, "k", in, f);
                if (k < 0 || k > l.size()) throw in.err(f, null, "ValueError", "sample larger than the list");
                Collections.shuffle(l, in.rnd);
                return new PyList(new ArrayList<Object>(l.subList(0, (int) k)), false);
            }
            case "seed": in.rnd.setSeed(argInt(a[0], name, "n", in, f)); return null;
            default: throw in.err(f, null, "RuntimeError", "builtin not implemented: " + name);
        }
    }

    private static boolean isInst(Object x, Object k) {
        if (!(k instanceof Native)) return false;
        String n = ((Native) k).name;
        switch (n) {
            case "int": return x instanceof Long || x instanceof Boolean;
            case "float": return x instanceof Double;
            case "str": return x instanceof String;
            case "bool": return x instanceof Boolean;
            case "list": return x instanceof PyList && !((PyList) x).tuple;
            case "tuple": return x instanceof PyList && ((PyList) x).tuple;
            case "dict": return x instanceof PyDict;
            case "set": return x instanceof PySet;
            default: return false;
        }
    }

    private static Object help(Object what, Frame f, ScriptInterp in) {
        if (in.out == null) return null;
        if (what == ScriptValues.NOARG || what == null) {
            in.out.print(3, "help(name) shows how a function works, e.g.  help(plant)");
            java.util.LinkedHashMap<String, StringBuilder> cats = new java.util.LinkedHashMap<String, StringBuilder>();
            for (Native n : in.natives.values()) { StringBuilder sb = cats.get(n.category); if (sb == null) { sb = new StringBuilder(); cats.put(n.category, sb); } if (sb.length() > 0) sb.append(", "); sb.append(n.name); }
            for (Map.Entry<String, StringBuilder> e : cats.entrySet()) in.out.print(3, e.getKey() + ": " + e.getValue());
            return null;
        }
        Native n = null;
        if (what instanceof Native) n = (Native) what;
        else if (what instanceof String) n = in.natives.get((String) what);
        if (n == null) { in.out.print(3, "no help for " + repr(what)); return null; }
        in.out.print(3, n.name + "(" + n.sig + ")");
        if (n.doc.length() > 0) in.out.print(3, "  " + n.doc);
        return null;
    }
}
