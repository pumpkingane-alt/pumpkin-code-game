package com.pumpkinpanic.coop;

import java.util.ArrayList;

/**
 * Error raised by the script language (syntax errors, runtime errors, failed game actions).
 * Carries Python-style type name, readable message, source position and a call trace.
 * Java 7 compatible.
 */
class ScriptError extends RuntimeException {
    static final int SEV_ERROR = 0, SEV_WARNING = 1;

    final String type;          // "SyntaxError", "NameError", "TypeError", ...
    final String msg;           // human readable text without the type prefix
    int line, col;              // 1-based; 0 = unknown
    String file = "main";
    int severity = SEV_ERROR;
    String hint;                // optional "did you mean ..." style help
    final ArrayList<String> trace = new ArrayList<String>();

    ScriptError(String type, String msg) { this(type, msg, 0, 0); }
    ScriptError(String type, String msg, int line, int col) {
        super(type + ": " + msg);
        this.type = type; this.msg = msg; this.line = line; this.col = col;
    }

    ScriptError at(int l, int c) { if (line <= 0) { line = l; col = c; } return this; }
    ScriptError in(String f) { if (f != null) file = f; return this; }
    ScriptError hint(String h) { hint = h; return this; }
    ScriptError warn() { severity = SEV_WARNING; return this; }

    @Override public synchronized Throwable fillInStackTrace() { return this; }   // cheap: no Java stack

    /** One-line description, e.g.  "line 12: NameError: name 'x' is not defined  (did you mean 'y'?)". */
    String oneLine() {
        StringBuilder sb = new StringBuilder();
        if (line > 0) sb.append("line ").append(line).append(": ");
        sb.append(type).append(": ").append(msg);
        if (hint != null && hint.length() > 0) sb.append("  (").append(hint).append(")");
        return sb.toString();
    }

    /** Multi-line report with the call trace, for the console. */
    String report() {
        StringBuilder sb = new StringBuilder(oneLine());
        if (!"main".equals(file)) sb.append("  [in ").append(file).append("]");
        for (int i = 0; i < trace.size(); i++) sb.append("\n    ").append(trace.get(i));
        return sb.toString();
    }

    /** Thrown inside a script thread to unwind it when the script is stopped. Not catchable by scripts. */
    static final class Kill extends Error {
        Kill() { super("script stopped"); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
