package com.pumpkinpanic.coop;

import java.util.HashSet;

/** Syntax tree of the script language. Plain data classes, evaluated by ScriptInterp. Java 7 compatible. */
final class ScriptAst {
    private ScriptAst() {}

    // operator codes
    static final int ADD = 1, SUB = 2, MUL = 3, DIV = 4, FDIV = 5, MOD = 6, POW = 7, BAND = 8, BOR = 9, BXOR = 10, SHL = 11, SHR = 12;
    static final int U_NEG = 1, U_POS = 2, U_NOT = 3, U_INV = 4;
    static final int EQ = 1, NE = 2, LT = 3, GT = 4, LE = 5, GE = 6, IN = 7, NOTIN = 8, IS = 9, ISNOT = 10;

    // expression kinds
    static final int E_CONST = 1, E_NAME = 2, E_LIST = 3, E_DICT = 4, E_SET = 5, E_BIN = 6, E_UNARY = 7, E_BOOL = 8, E_CMP = 9, E_CALL = 10,
        E_ATTR = 11, E_INDEX = 12, E_SLICE = 13, E_COND = 14, E_LAMBDA = 15, E_COMP = 16, E_FSTR = 17, E_STAR = 18;
    // statement kinds
    static final int S_EXPR = 1, S_ASSIGN = 2, S_AUG = 3, S_IF = 4, S_WHILE = 5, S_FOR = 6, S_DEF = 7, S_RETURN = 8, S_BREAK = 9, S_CONT = 10,
        S_PASS = 11, S_GLOBAL = 12, S_NONLOCAL = 13, S_TRY = 14, S_RAISE = 15, S_ASSERT = 16, S_IMPORT = 17, S_FROM = 18, S_DEL = 19;

    static abstract class Node { int line, col; }
    static abstract class Expr extends Node { final int kind; Expr(int k) { kind = k; } }
    static abstract class Stmt extends Node { final int kind; Stmt(int k) { kind = k; } }

    // ------------------------------------------------------------ expressions
    static final class Const extends Expr { Object v; Const(Object v) { super(E_CONST); this.v = v; } }
    static final class Name extends Expr { String id; Name(String id) { super(E_NAME); this.id = id; } }
    static final class ListE extends Expr { Expr[] items; boolean tuple; ListE() { super(E_LIST); } }
    static final class SetE extends Expr { Expr[] items; SetE() { super(E_SET); } }
    static final class DictE extends Expr { Expr[] keys, vals; DictE() { super(E_DICT); } }
    static final class Bin extends Expr { int op; Expr l, r; Bin() { super(E_BIN); } }
    static final class Unary extends Expr { int op; Expr e; Unary() { super(E_UNARY); } }
    static final class BoolOp extends Expr { boolean and; Expr l, r; BoolOp() { super(E_BOOL); } }
    static final class Compare extends Expr { Expr first; int[] ops; Expr[] rest; Compare() { super(E_CMP); } }
    static final class Call extends Expr {
        Expr fn; Expr[] args; boolean[] star; String[] kwNames; Expr[] kwVals; Expr kwStar;
        Call() { super(E_CALL); }
    }
    static final class Attr extends Expr { Expr obj; String name; Attr() { super(E_ATTR); } }
    static final class Index extends Expr { Expr obj, idx; Index() { super(E_INDEX); } }
    static final class Slice extends Expr { Expr obj, lo, hi, step; Slice() { super(E_SLICE); } }
    static final class Cond extends Expr { Expr test, a, b; Cond() { super(E_COND); } }
    static final class Star extends Expr { Expr e; Star() { super(E_STAR); } }
    static final class Lambda extends Expr { FuncDef def; Lambda() { super(E_LAMBDA); } }
    static final class Comp { Expr target, iter; Expr[] ifs; }
    static final class ListComp extends Expr {
        Expr elt, val;                 // val != null -> dict comprehension (elt = key)
        Comp[] gens; boolean isSet;
        HashSet<String> vars = new HashSet<String>();
        ListComp() { super(E_COMP); }
    }
    static final class FPart { Expr e; String conv, spec; }
    static final class FStr extends Expr { Object[] parts; FStr() { super(E_FSTR); } }   // String or FPart

    // ------------------------------------------------------------ statements
    static final class ExprStmt extends Stmt { Expr e; ExprStmt() { super(S_EXPR); } }
    static final class Assign extends Stmt { Expr[] targets; Expr value; Assign() { super(S_ASSIGN); } }
    static final class AugAssign extends Stmt { Expr target; int op; Expr value; AugAssign() { super(S_AUG); } }
    static final class If extends Stmt { Expr[] tests; Stmt[][] blocks; Stmt[] orelse; If() { super(S_IF); } }
    static final class While extends Stmt { Expr test; Stmt[] body, orelse; While() { super(S_WHILE); } }
    static final class For extends Stmt { Expr target, iter; Stmt[] body, orelse; For() { super(S_FOR); } }
    static final class Param { String name; Expr def; boolean star, dstar; }
    static final class FuncDef extends Stmt {
        String name = "<lambda>"; Param[] params; Stmt[] body; String file = "main";
        HashSet<String> locals = new HashSet<String>(), globals = new HashSet<String>(), nonlocals = new HashSet<String>();
        int minArgs, maxArgs;          // maxArgs < 0 : unlimited (*args)
        FuncDef() { super(S_DEF); }
    }
    static final class Return extends Stmt { Expr value; Return() { super(S_RETURN); } }
    static final class Simple extends Stmt { Simple(int k) { super(k); } }       // break / continue / pass
    static final class Decl extends Stmt { String[] names; Decl(int k) { super(k); } }   // global / nonlocal
    static final class Handler { Expr type; String name; Stmt[] body; int line; }
    static final class Try extends Stmt { Stmt[] body, orelse, fin; Handler[] handlers; Try() { super(S_TRY); } }
    static final class Raise extends Stmt { Expr e; Raise() { super(S_RAISE); } }
    static final class Assert extends Stmt { Expr test, msg; Assert() { super(S_ASSERT); } }
    static final class Import extends Stmt { String module, alias; Import() { super(S_IMPORT); } }
    static final class FromImport extends Stmt { String module; String[] names, aliases; FromImport() { super(S_FROM); } }
    static final class Del extends Stmt { Expr[] targets; Del() { super(S_DEL); } }

    /** A parsed file. */
    static final class Program {
        String file = "main";
        Stmt[] body;
        HashSet<String> globalNames = new HashSet<String>();     // every name bound at module level
        java.util.ArrayList<FuncDef> allFuncs = new java.util.ArrayList<FuncDef>();
    }
}
