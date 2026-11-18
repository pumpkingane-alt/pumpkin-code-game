package com.pumpkinpanic.coop;

import java.util.ArrayList;

/** Tokenizer for the Python-style script language (indentation aware). Java 7 compatible. */
final class ScriptLexer {
    static final int EOF = 0, NAME = 1, INT = 2, FLOAT = 3, STR = 4, OP = 5, NEWLINE = 6, INDENT = 7, DEDENT = 8;

    static final class Tok {
        int type, line, col;
        String text;            // NAME / OP text, STR raw text between the quotes
        long ival; double dval;
        boolean fstr, raw;      // string prefixes
        Tok(int t, String s, int l, int c) { type = t; text = s; line = l; col = c; }
        @Override public String toString() { return type + ":" + text; }
    }

    private final String src;
    private final int lineBase;
    private int pos, line, col;
    private final ArrayList<Tok> out = new ArrayList<Tok>();
    private final ArrayList<Integer> indents = new ArrayList<Integer>();
    private final ArrayList<int[]> brackets = new ArrayList<int[]>();   // {char, line, col}

    ScriptLexer(String source, int firstLine) {
        src = source.replace("\r\n", "\n").replace('\r', '\n');
        lineBase = firstLine;
        line = firstLine; col = 1;
    }

    static ArrayList<Tok> lex(String source) { return new ScriptLexer(source, 1).run(); }

    private ScriptError err(String type, String msg, int l, int c) { return new ScriptError(type, msg, l, c); }

    private char peek(int k) { return pos + k < src.length() ? src.charAt(pos + k) : '\0'; }

    ArrayList<Tok> run() {
        indents.add(0);
        boolean atLineStart = true;
        while (pos < src.length()) {
            if (atLineStart && brackets.isEmpty()) {
                int ind = 0, p = pos;
                while (p < src.length()) {
                    char ch = src.charAt(p);
                    if (ch == ' ') ind++;
                    else if (ch == '\t') ind = (ind / 4 + 1) * 4;
                    else break;
                    p++;
                }
                char ch = p < src.length() ? src.charAt(p) : '\n';
                if (ch == '\n' || ch == '#') {            // blank / comment-only line: ignore indentation
                    while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                    if (pos < src.length()) { pos++; line++; }
                    col = 1;
                    continue;
                }
                col = 1 + (p - pos); pos = p;
                int cur = indents.get(indents.size() - 1);
                if (ind > cur) { indents.add(ind); out.add(new Tok(INDENT, "", line, 1)); }
                else if (ind < cur) {
                    while (ind < indents.get(indents.size() - 1)) { indents.remove(indents.size() - 1); out.add(new Tok(DEDENT, "", line, 1)); }
                    if (ind != indents.get(indents.size() - 1)) throw err("IndentationError", "unindent does not match any outer indentation level", line, 1).hint("use the same number of spaces as an earlier line");
                }
                atLineStart = false;
            }
            char c = peek(0);
            if (c == '\n') {
                if (brackets.isEmpty()) { if (!out.isEmpty() && out.get(out.size() - 1).type != NEWLINE) out.add(new Tok(NEWLINE, "", line, col)); atLineStart = true; }
                pos++; line++; col = 1;
                continue;
            }
            if (c == ' ' || c == '\t') { pos++; col++; continue; }
            if (c == '#') { while (pos < src.length() && src.charAt(pos) != '\n') pos++; continue; }
            if (c == '\\' && peek(1) == '\n') { pos += 2; line++; col = 1; continue; }
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) { number(); continue; }
            if (Character.isLetter(c) || c == '_') {
                int s = pos;
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                String w = src.substring(s, pos);
                char q = peek(0);
                if ((q == '"' || q == '\'') && w.length() <= 2 && isStrPrefix(w)) { string(w, s); continue; }
                out.add(new Tok(NAME, w, line, col)); col += pos - s;
                continue;
            }
            if (c == '"' || c == '\'') { string("", pos); continue; }
            operator();
        }
        if (!brackets.isEmpty()) {
            int[] b = brackets.get(brackets.size() - 1);
            throw err("SyntaxError", "'" + (char) b[0] + "' was never closed", b[1], b[2]);
        }
        if (!out.isEmpty() && out.get(out.size() - 1).type != NEWLINE) out.add(new Tok(NEWLINE, "", line, col));
        while (indents.size() > 1) { indents.remove(indents.size() - 1); out.add(new Tok(DEDENT, "", line, 1)); }
        out.add(new Tok(EOF, "", line, col));
        return out;
    }

    private static boolean isStrPrefix(String w) {
        String l = w.toLowerCase(java.util.Locale.ROOT);
        return l.equals("r") || l.equals("f") || l.equals("rf") || l.equals("fr") || l.equals("b") || l.equals("u");
    }

    private void number() {
        int s = pos, l = line, c0 = col;
        boolean isFloat = false;
        if (peek(0) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            pos += 2; int st = pos;
            while (pos < src.length() && (Character.digit(src.charAt(pos), 16) >= 0 || src.charAt(pos) == '_')) pos++;
            if (pos == st) throw err("SyntaxError", "invalid hexadecimal literal", l, c0);
            Tok t = new Tok(INT, src.substring(s, pos), l, c0); t.ival = Long.parseLong(src.substring(st, pos).replace("_", ""), 16); out.add(t); col += pos - s; return;
        }
        if (peek(0) == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            pos += 2; int st = pos;
            while (pos < src.length() && (src.charAt(pos) == '0' || src.charAt(pos) == '1' || src.charAt(pos) == '_')) pos++;
            if (pos == st) throw err("SyntaxError", "invalid binary literal", l, c0);
            Tok t = new Tok(INT, src.substring(s, pos), l, c0); t.ival = Long.parseLong(src.substring(st, pos).replace("_", ""), 2); out.add(t); col += pos - s; return;
        }
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        if (peek(0) == '.' && peek(1) != '.' && !(Character.isLetter(peek(1)) && peek(1) != 'e' && peek(1) != 'E')) {
            isFloat = true; pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        }
        if ((peek(0) == 'e' || peek(0) == 'E') && (Character.isDigit(peek(1)) || ((peek(1) == '+' || peek(1) == '-') && Character.isDigit(peek(2))))) {
            isFloat = true; pos += 2;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (Character.isLetter(src.charAt(pos)) || src.charAt(pos) == '_'))
            throw err("SyntaxError", "invalid number: '" + src.substring(s, Math.min(src.length(), pos + 1)) + "'", l, c0).hint("variable names cannot start with a digit");
        String txt = src.substring(s, pos).replace("_", "");
        Tok t;
        if (isFloat) { t = new Tok(FLOAT, txt, l, c0); t.dval = Double.parseDouble(txt); }
        else {
            t = new Tok(INT, txt, l, c0);
            try { t.ival = Long.parseLong(txt); } catch (NumberFormatException e) { t = new Tok(FLOAT, txt, l, c0); t.dval = Double.parseDouble(txt); }
        }
        out.add(t); col += pos - s;
    }

    private void string(String prefix, int startPos) {
        int l = line, c0 = col;
        char q = src.charAt(pos);
        boolean triple = peek(1) == q && peek(2) == q;
        pos += triple ? 3 : 1;
        int s = pos;
        boolean raw = prefix.toLowerCase(java.util.Locale.ROOT).indexOf('r') >= 0;
        while (true) {
            if (pos >= src.length()) throw err("SyntaxError", "unterminated string literal (started at line " + l + ")", l, c0).hint("add the closing " + (triple ? "\"\"\"" : String.valueOf(q)));
            char ch = src.charAt(pos);
            if (ch == '\\' && pos + 1 < src.length()) { if (src.charAt(pos + 1) == '\n') line++; pos += 2; continue; }
            if (ch == '\n') {
                if (!triple) throw err("SyntaxError", "unterminated string literal", l, c0).hint("a string must end on the same line (use triple quotes for several lines)");
                line++;
            }
            if (ch == q && (!triple || (peek(1) == q && peek(2) == q))) break;
            pos++;
        }
        Tok t = new Tok(STR, src.substring(s, pos), l, c0);
        t.fstr = prefix.toLowerCase(java.util.Locale.ROOT).indexOf('f') >= 0; t.raw = raw;
        pos += triple ? 3 : 1;
        out.add(t);
        col = triple ? 1 + (pos - (src.lastIndexOf('\n', pos - 1) + 1)) : col + (pos - startPos);
    }

    private static final String[] OPS3 = {"**=", "//=", ">>=", "<<=", "..."};
    private static final String[] OPS2 = {"**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "->", "<<", ">>", ":="};

    private void operator() {
        int l = line, c0 = col;
        for (int i = 0; i < OPS3.length; i++) if (src.startsWith(OPS3[i], pos)) { add(OPS3[i], l, c0); return; }
        for (int i = 0; i < OPS2.length; i++) if (src.startsWith(OPS2[i], pos)) { add(OPS2[i], l, c0); return; }
        char c = src.charAt(pos);
        if ("+-*/%<>=()[]{},:.;&|^~@".indexOf(c) >= 0) {
            if (c == '(' || c == '[' || c == '{') brackets.add(new int[] {c, l, c0});
            else if (c == ')' || c == ']' || c == '}') {
                if (brackets.isEmpty()) throw err("SyntaxError", "unmatched '" + c + "'", l, c0).hint("there is no matching opening bracket");
                int[] b = brackets.remove(brackets.size() - 1);
                char want = b[0] == '(' ? ')' : (b[0] == '[' ? ']' : '}');
                if (c != want) throw err("SyntaxError", "closing '" + c + "' does not match opening '" + (char) b[0] + "' on line " + b[1], l, c0);
            }
            add(String.valueOf(c), l, c0);
            return;
        }
        if (c == '!') throw err("SyntaxError", "invalid syntax: '!'", l, c0).hint("use 'not' for negation, '!=' for not-equal");
        if (c == '$' || c == '?' || c == '`') throw err("SyntaxError", "invalid character '" + c + "'", l, c0);
        throw err("SyntaxError", "invalid character '" + c + "' (U+" + Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT) + ")", l, c0).hint("use plain ASCII quotes and symbols");
    }

    private void add(String op, int l, int c0) {
        // bracket bookkeeping for multi-char ops is not needed (they contain no brackets)
        out.add(new Tok(OP, op, l, c0));
        pos += op.length(); col += op.length();
    }

    // ------------------------------------------------------------ string helpers (used by the parser)

    /** Process backslash escapes of a normal (non-raw) string body. */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case '0': sb.append('\0'); break;
                case '\\': sb.append('\\'); break;
                case '\'': sb.append('\''); break;
                case '"': sb.append('"'); break;
                case '\n': break;
                case 'x': if (i + 2 < s.length() + 0 && i + 2 <= s.length() - 0) { try { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; } catch (Exception e) { sb.append("\\x"); } } else sb.append("\\x"); break;
                case 'u': if (i + 4 < s.length() + 1 && i + 5 <= s.length()) { try { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } catch (Exception e) { sb.append("\\u"); } } else sb.append("\\u"); break;
                default: sb.append('\\').append(n);
            }
        }
        return sb.toString();
    }
}
