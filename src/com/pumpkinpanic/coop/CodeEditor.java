package com.pumpkinpanic.coop;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

/**
 * Full screen code editor: syntax colours, line numbers, auto indent, live error check, file list, examples,
 * function picker and help. Built from plain Android views (no layout xml). Java 7 compatible.
 */
final class CodeEditor {
    private static final int BG = 0xFF1B1A22, BAR = 0xFF2A2833, TXT = 0xFFE6E1D3, GUTTER = 0xFF76728A,
        C_KW = 0xFFFF9E64, C_STR = 0xFF9ECE6A, C_NUM = 0xFFE0AF68, C_COM = 0xFF7F849C, C_API = 0xFF7DCFFF, C_DEF = 0xFFBB9AF7, C_CONST = 0xFFF7768E;

    private static final class HlSpan extends ForegroundColorSpan { HlSpan(int c) { super(c); } }
    private static final class ErrSpan extends BackgroundColorSpan { ErrSpan(int c) { super(c); } }

    private final Activity act;
    private final GameView gv;
    private final ScriptStore store;
    private final ScriptHost sh;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashSet<String> api = new HashSet<String>();
    private final HashSet<String> kw = new HashSet<String>();

    private Dialog dlg;
    private EditText et;
    private TextView gutter, nameLabel, panelText;
    private ScrollView scroll, panelScroll;
    private LinearLayout panelBody;
    private Button tabProblems, tabConsole;
    private String current;
    private boolean dirty, showConsole, settingText, closed;
    private ArrayList<ScriptError> probs = new ArrayList<ScriptError>();

    CodeEditor(Activity a, GameView g) {
        act = a; gv = g; store = g.store; sh = g.scripts;
        density = a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < ScriptParser.KEYWORDS.length; i++) kw.add(ScriptParser.KEYWORDS[i]);
        ScriptInterp in = sh.knownInterp();
        api.addAll(in.builtins.keySet());
        current = g.activeScript;
        if (store == null || !store.exists(current)) current = "main";
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    // ------------------------------------------------------------ building the screen
    private Button button(String text, int bg, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(text); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(bg); b.setPadding(dp(9), 0, dp(9), 0);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        try { b.setAllCaps(false); } catch (Throwable t) { /* old devices */ }
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    void show() {
        if (store == null) { Toast.makeText(act, "Scripts folder not ready yet", Toast.LENGTH_SHORT).show(); return; }
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dlg.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---- top bar
        HorizontalScrollView topScroll = new HorizontalScrollView(act);
        topScroll.setBackgroundColor(BAR); topScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        nameLabel = new TextView(act);
        nameLabel.setTextColor(0xFFFFE9B8); nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); nameLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        nameLabel.setPadding(dp(8), 0, dp(10), 0);
        top.addView(nameLabel);
        top.addView(button("RUN", 0xFF3A8A2A, new View.OnClickListener() { public void onClick(View v) { doRun(); } }));
        top.addView(button("STOP", 0xFFB03A2A, new View.OnClickListener() { public void onClick(View v) { gv.stopScript(); refreshPanel(); } }));
        top.addView(button("CHECK", 0xFF3A6EA8, new View.OnClickListener() { public void onClick(View v) { doCheck(true); } }));
        top.addView(button("SAVE", 0xFF55526A, new View.OnClickListener() { public void onClick(View v) { save(true); } }));
        top.addView(button("FILES", 0xFF55526A, new View.OnClickListener() { public void onClick(View v) { filesDialog(); } }));
        top.addView(button("EXAMPLES", 0xFF55526A, new View.OnClickListener() { public void onClick(View v) { examplesDialog(); } }));
        top.addView(button("FUNCTIONS", 0xFF55526A, new View.OnClickListener() { public void onClick(View v) { functionsDialog(); } }));
        top.addView(button("HELP", 0xFF55526A, new View.OnClickListener() { public void onClick(View v) { helpDialog(); } }));
        top.addView(button("CLOSE", 0xFF55526A, new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); } }));
        topScroll.addView(top);
        root.addView(topScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- editor with line numbers
        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        gutter = new TextView(act);
        gutter.setTextColor(GUTTER); gutter.setTypeface(Typeface.MONOSPACE); gutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        gutter.setGravity(Gravity.RIGHT); gutter.setMinWidth(dp(34)); gutter.setPadding(dp(4), dp(6), dp(6), dp(6));
        gutter.setBackgroundColor(0xFF211F2A); gutter.setIncludeFontPadding(false);
        row.addView(gutter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        et = new EditText(act);
        et.setTypeface(Typeface.MONOSPACE); et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); et.setTextColor(TXT);
        et.setBackgroundColor(0); et.setGravity(Gravity.TOP | Gravity.LEFT); et.setPadding(dp(6), dp(6), dp(6), dp(6)); et.setIncludeFontPadding(false);
        et.setHorizontallyScrolling(false);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        et.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        et.setHighlightColor(0x663A6EA8);
        et.setFilters(new InputFilter[] { new InputFilter() {
            public CharSequence filter(CharSequence src, int start, int end, Spanned dest, int dstart, int dend) {
                if (end - start == 1 && src.charAt(start) == '\n') {
                    int ls = dstart;
                    while (ls > 0 && dest.charAt(ls - 1) != '\n') ls--;
                    int i = ls;
                    while (i < dstart && dest.charAt(i) == ' ') i++;
                    int j = dstart;
                    while (j > ls && dest.charAt(j - 1) == ' ') j--;
                    boolean colon = j > ls && dest.charAt(j - 1) == ':';
                    return "\n" + dest.subSequence(ls, i) + (colon ? "    " : "");
                }
                return null;
            }
        } });
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                if (settingText) return;
                dirty = true; updateName();
                handler.removeCallbacks(hlRun); handler.postDelayed(hlRun, 140);
                handler.removeCallbacks(checkRun); handler.postDelayed(checkRun, 1300);
            }
        });
        row.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- problems / console panel
        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setBackgroundColor(BAR);
        tabProblems = button("Problems", 0xFF3A3850, new View.OnClickListener() { public void onClick(View v) { showConsole = false; refreshPanel(); } });
        tabConsole = button("Console", 0xFF3A3850, new View.OnClickListener() { public void onClick(View v) { showConsole = true; refreshPanel(); } });
        tabs.addView(tabProblems); tabs.addView(tabConsole);
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panelScroll = new ScrollView(act);
        panelScroll.setBackgroundColor(0xFF12111A);
        panelBody = new LinearLayout(act);
        panelBody.setOrientation(LinearLayout.VERTICAL);
        panelBody.setPadding(dp(6), dp(2), dp(6), dp(2));
        panelScroll.addView(panelBody);
        root.addView(panelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        // ---- quick keys
        HorizontalScrollView keysScroll = new HorizontalScrollView(act);
        keysScroll.setBackgroundColor(BAR); keysScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout keys = new LinearLayout(act);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.addView(keyBtn("Tab", "    ", 4)); keys.addView(arrowBtn("\u2190", -1)); keys.addView(arrowBtn("\u2192", 1));
        String[][] ks = {{"()", "()", "1"}, {"[]", "[]", "1"}, {"{}", "{}", "1"}, {"\"\"", "\"\"", "1"}, {":", ":", "1"}, {"=", " = ", "3"}, {"==", " == ", "4"}, {"!=", " != ", "4"},
            {"+", " + ", "3"}, {"-", " - ", "3"}, {"*", " * ", "3"}, {"/", " / ", "3"}, {"%", " % ", "3"}, {"<", " < ", "3"}, {">", " > ", "3"}, {"_", "_", "1"}, {"#", "# ", "2"}, {".", ".", "1"}, {",", ", ", "2"}, {"'", "'", "1"}};
        for (int i = 0; i < ks.length; i++) keys.addView(keyBtn(ks[i][0], ks[i][1], Integer.parseInt(ks[i][2]) ));
        keysScroll.addView(keys);
        root.addView(keysScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dlg.setContentView(root);
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) { onClosed(); }
        });
        gv.editorOpened();
        gv.scriptListener = new Runnable() { public void run() { refreshPanel(); } };
        loadScript(current);
        dlg.show();
        et.requestFocus();
        handler.postDelayed(panelTick, 700);
    }

    private Button keyBtn(final String label, final String insert, final int cursor) {
        Button b = button(label, 0xFF3A3850, new View.OnClickListener() { public void onClick(View v) { insertText(insert, cursor); } });
        b.setTypeface(Typeface.MONOSPACE);
        return b;
    }
    private Button arrowBtn(String label, final int delta) {
        return button(label, 0xFF3A3850, new View.OnClickListener() { public void onClick(View v) {
            int p = Math.max(0, Math.min(et.length(), et.getSelectionStart() + delta));
            et.setSelection(p);
        } });
    }

    private void insertText(String text, int cursorOffset) {
        int s = Math.max(0, et.getSelectionStart()), e = Math.max(0, et.getSelectionEnd());
        int lo = Math.min(s, e), hi = Math.max(s, e);
        et.getText().replace(lo, hi, text);
        et.setSelection(Math.min(et.length(), lo + cursorOffset));
        et.requestFocus();
    }

    // ------------------------------------------------------------ lifecycle
    private final Runnable hlRun = new Runnable() { public void run() { highlight(); updateGutter(); } };
    private final Runnable checkRun = new Runnable() { public void run() { doCheck(false); } };
    private final Runnable panelTick = new Runnable() { public void run() { if (closed) return; if (showConsole) refreshPanel(); handler.postDelayed(this, 600); } };

    private void onClosed() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        save(false);
        gv.scriptListener = null;
        gv.activeScript = current;
        gv.host.savePref("script", current);
        gv.editorClosed();
    }

    private void loadScript(String name) {
        String text = store.read(name);
        if (text == null) text = "";
        current = name;
        gv.activeScript = name;
        settingText = true;
        et.setText(text);
        settingText = false;
        dirty = false;
        probs.clear();
        updateName();
        highlight();
        et.post(new Runnable() { public void run() { updateGutter(); } });
        doCheck(false);
        scroll.scrollTo(0, 0);
    }

    private void updateName() { nameLabel.setText(current + ".py" + (dirty ? " *" : "")); }

    private boolean save(boolean toast) {
        if (current == null) return false;
        boolean ok = store.write(current, et.getText().toString());
        if (ok) { dirty = false; updateName(); }
        if (toast) Toast.makeText(act, ok ? "Saved " + current + ".py" : "Could not save the file", Toast.LENGTH_SHORT).show();
        return ok;
    }

    // ------------------------------------------------------------ run / check
    private void doRun() {
        save(false);
        String src = et.getText().toString();
        gv.activeScript = current;
        gv.host.savePref("script", current);
        ArrayList<ScriptError> p = gv.runSource(src, current);
        probs = p;
        applyProblemSpans(); refreshPanel();
        boolean err = hasError(p);
        if (!err && sh.isRunning()) dlg.dismiss();
        else if (!err) Toast.makeText(act, "Start a run first (Play solo > START), then press RUN", Toast.LENGTH_LONG).show();
    }

    private boolean hasError(ArrayList<ScriptError> p) { for (int i = 0; i < p.size(); i++) if (p.get(i).severity == ScriptError.SEV_ERROR) return true; return false; }

    private void doCheck(boolean toast) {
        String src = et.getText().toString();
        sh.loader = store;
        probs = sh.check(src, current);
        applyProblemSpans(); refreshPanel();
        if (toast) {
            int e = 0, w = 0; for (int i = 0; i < probs.size(); i++) { if (probs.get(i).severity == ScriptError.SEV_ERROR) e++; else w++; }
            Toast.makeText(act, e == 0 && w == 0 ? "No problems found" : e + " error(s), " + w + " warning(s)", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ highlighting
    private void highlight() {
        Editable e = et.getText();
        int n = Math.min(e.length(), 30000);
        HlSpan[] old = e.getSpans(0, e.length(), HlSpan.class);
        for (int i = 0; i < old.length; i++) e.removeSpan(old[i]);
        String s = e.toString();
        int i = 0; boolean afterDef = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '#') { int j = s.indexOf('\n', i); if (j < 0 || j > n) j = n; span(e, i, j, C_COM); i = j; continue; }
            if (c == '"' || c == '\'') {
                boolean triple = i + 2 < n && s.charAt(i + 1) == c && s.charAt(i + 2) == c;
                int j = i + (triple ? 3 : 1);
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (triple) { if (d == c && j + 2 < n + 1 && s.startsWith("" + c + c + c, j)) { j += 3; break; } }
                    else { if (d == c) { j++; break; } if (d == '\n') break; }
                    j++;
                }
                if (j > n) j = n;
                span(e, i, j, C_STR); i = j; continue;
            }
            if (Character.isDigit(c)) { int j = i + 1; while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) j++; span(e, i, j, C_NUM); i = j; continue; }
            if (Character.isLetter(c) || c == '_') {
                int j = i + 1; while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                String w = s.substring(i, j);
                if (afterDef) { span(e, i, j, C_DEF); afterDef = false; }
                else if (w.equals("True") || w.equals("False") || w.equals("None")) span(e, i, j, C_CONST);
                else if (kw.contains(w)) { span(e, i, j, C_KW); afterDef = w.equals("def"); }
                else if (api.contains(w)) span(e, i, j, C_API);
                i = j; continue;
            }
            i++;
        }
    }
    private void span(Editable e, int a, int b, int color) { if (b > a) e.setSpan(new HlSpan(color), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }

    private int lineStart(String s, int line) {
        int off = 0;
        for (int i = 1; i < line; i++) { int j = s.indexOf('\n', off); if (j < 0) return -1; off = j + 1; }
        return off;
    }

    private void applyProblemSpans() {
        Editable e = et.getText();
        ErrSpan[] old = e.getSpans(0, e.length(), ErrSpan.class);
        for (int i = 0; i < old.length; i++) e.removeSpan(old[i]);
        String s = e.toString();
        ArrayList<ScriptError> all = new ArrayList<ScriptError>(probs);
        if (sh.status == ScriptHost.ERROR && sh.error != null) all.add(sh.error);
        for (int i = 0; i < all.size(); i++) {
            ScriptError p = all.get(i);
            if (p.line <= 0 || !current.equals(p.file)) continue;
            int a = lineStart(s, p.line);
            if (a < 0) continue;
            int b = s.indexOf('\n', a); if (b < 0) b = s.length();
            if (b > a) e.setSpan(new ErrSpan(p.severity == ScriptError.SEV_ERROR ? 0x66FF4444 : 0x33FFD36B), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void updateGutter() {
        Layout lay = et.getLayout();
        if (lay == null) { et.post(new Runnable() { public void run() { updateGutter(); } }); return; }
        String t = et.getText().toString();
        StringBuilder sb = new StringBuilder();
        int off = 0, n = 1;
        while (true) {
            int nl = t.indexOf('\n', off);
            int endOff = nl < 0 ? t.length() : nl;
            int first = lay.getLineForOffset(Math.min(off, t.length()));
            int last = lay.getLineForOffset(Math.min(endOff, t.length()));
            sb.append(n);
            for (int k = first; k <= last; k++) sb.append('\n');
            if (nl < 0) break;
            off = nl + 1; n++;
        }
        gutter.setText(sb.toString());
    }

    private void gotoLine(int line) {
        String s = et.getText().toString();
        int a = lineStart(s, line);
        if (a < 0) return;
        et.requestFocus();
        et.setSelection(Math.min(a, s.length()));
        final Layout lay = et.getLayout();
        if (lay != null) {
            final int y = lay.getLineTop(lay.getLineForOffset(a));
            scroll.post(new Runnable() { public void run() { scroll.smoothScrollTo(0, Math.max(0, y - dp(60))); } });
        }
    }

    // ------------------------------------------------------------ problems / console panel
    private TextView panelLine(String text, int color) {
        TextView tv = new TextView(act);
        tv.setText(text); tv.setTextColor(color); tv.setTypeface(Typeface.MONOSPACE); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private void refreshPanel() {
        if (panelBody == null || closed) return;
        applyProblemSpans();
        panelBody.removeAllViews();
        int ne = 0, nw = 0;
        ArrayList<ScriptError> all = new ArrayList<ScriptError>(probs);
        if (sh.status == ScriptHost.ERROR && sh.error != null) {
            boolean dup = false;
            for (int i = 0; i < all.size(); i++) if (all.get(i).line == sh.error.line && all.get(i).msg.equals(sh.error.msg)) dup = true;
            if (!dup) all.add(0, sh.error);
        }
        for (int i = 0; i < all.size(); i++) { if (all.get(i).severity == ScriptError.SEV_ERROR) ne++; else nw++; }
        tabProblems.setText("Problems (" + ne + " / " + nw + ")");
        tabProblems.setBackgroundColor(!showConsole ? 0xFF5A5780 : 0xFF3A3850);
        tabConsole.setBackgroundColor(showConsole ? 0xFF5A5780 : 0xFF3A3850);
        if (showConsole) {
            ArrayList<ScriptHost.LogLine> lines = new ArrayList<ScriptHost.LogLine>();
            synchronized (sh.log) { int from = Math.max(0, sh.log.size() - 60); for (int i = from; i < sh.log.size(); i++) lines.add(sh.log.get(i)); }
            if (lines.isEmpty()) panelBody.addView(panelLine("(console is empty - use print(...) in your script)", GUTTER));
            for (int i = 0; i < lines.size(); i++) {
                ScriptHost.LogLine l = lines.get(i);
                panelBody.addView(panelLine(l.text, l.kind == ScriptHost.LOG_ERROR ? 0xFFFF8A7A : (l.kind == ScriptHost.LOG_WARN ? 0xFFFFD36B : (l.kind == ScriptHost.LOG_INFO ? 0xFF9AD8FF : TXT))));
            }
            panelScroll.post(new Runnable() { public void run() { panelScroll.fullScroll(View.FOCUS_DOWN); } });
            return;
        }
        if (all.isEmpty()) { panelBody.addView(panelLine("No problems. Press RUN, or CHECK to look for mistakes.", 0xFF8FD86A)); return; }
        for (int i = 0; i < all.size(); i++) {
            final ScriptError p = all.get(i);
            boolean err = p.severity == ScriptError.SEV_ERROR;
            TextView tv = panelLine((err ? "ERROR " : "warning ") + p.oneLine() + (!"main".equals(p.file) && !p.file.equals(current) ? "  [" + p.file + "]" : ""), err ? 0xFFFF8A7A : 0xFFFFD36B);
            tv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (p.line > 0) gotoLine(p.line); } });
            panelBody.addView(tv);
            for (int k = 0; k < p.trace.size() && k < 4 && err && sh.error == p; k++) panelBody.addView(panelLine("    " + p.trace.get(k), GUTTER));
        }
    }

    // ------------------------------------------------------------ files
    private void switchTo(String name) {
        save(false);
        loadScript(name);
        gv.host.savePref("script", name);
    }

    private void filesDialog() {
        final ArrayList<String> names = store.list();
        final String[] items = new String[names.size() + 3];
        for (int i = 0; i < names.size(); i++) items[i] = names.get(i) + ".py" + (names.get(i).equals(current) ? "   (open)" : "");
        items[names.size()] = "+ New script...";
        items[names.size() + 1] = "Rename this script...";
        items[names.size() + 2] = "Delete this script...";
        new AlertDialog.Builder(act).setTitle("Scripts").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which < names.size()) { switchTo(names.get(which)); return; }
                if (which == names.size()) askName("New script", "", new GameView.Cb() { public void done(String t) {
                    String n = t.trim();
                    if (n.endsWith(".py")) n = n.substring(0, n.length() - 3);
                    if (!ScriptStore.validName(n)) { Toast.makeText(act, "Use letters, digits and _ (not starting with a digit)", Toast.LENGTH_LONG).show(); return; }
                    if (store.exists(n)) { Toast.makeText(act, "That name is taken", Toast.LENGTH_SHORT).show(); return; }
                    save(false);
                    store.write(n, "# " + n + ".py\n\n");
                    loadScript(n);
                } });
                else if (which == names.size() + 1) askName("Rename " + current, current, new GameView.Cb() { public void done(String t) {
                    String n = t.trim();
                    if (n.endsWith(".py")) n = n.substring(0, n.length() - 3);
                    save(false);
                    if (!store.rename(current, n)) { Toast.makeText(act, "Could not rename (bad or taken name)", Toast.LENGTH_LONG).show(); return; }
                    current = n; gv.activeScript = n; updateName();
                } });
                else confirmDelete();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete() {
        if (current.equals("main") && store.list().size() <= 1) { Toast.makeText(act, "You cannot delete your only script", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(act).setTitle("Delete " + current + ".py?").setMessage("This cannot be undone.")
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                store.delete(current);
                ArrayList<String> left = store.list();
                String next = left.isEmpty() ? "main" : left.get(0);
                if (!store.exists(next)) store.write(next, ScriptTemplates.starter());
                dirty = false;
                loadScript(next);
            } }).setNegativeButton("Cancel", null).show();
    }

    private void askName(String title, String def, final GameView.Cb cb) {
        final EditText in = new EditText(act);
        in.setText(def); in.setSingleLine(true); in.setSelectAllOnFocus(true); in.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(act).setTitle(title).setView(in)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { cb.done(in.getText().toString()); } })
            .setNegativeButton("Cancel", null).show();
    }

    private void examplesDialog() {
        String[] items = new String[ScriptTemplates.TITLES.length];
        for (int i = 0; i < items.length; i++) items[i] = ScriptTemplates.TITLES[i] + " - " + ScriptTemplates.INFO[i];
        new AlertDialog.Builder(act).setTitle("Examples (opens as a new script)").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                save(false);
                String n = store.uniqueName(ScriptTemplates.IDS[which]);
                store.write(n, ScriptTemplates.CODE[which]);
                loadScript(n);
                gv.host.savePref("script", n);
            }
        }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------ function picker + help
    private void functionsDialog() {
        final ArrayList<String[]> all = ScriptDocs.entries(sh.knownInterp());
        final ArrayList<String> cats = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) if (!cats.contains(all.get(i)[0])) cats.add(all.get(i)[0]);
        new AlertDialog.Builder(act).setTitle("Insert a function").setItems(cats.toArray(new String[cats.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                final ArrayList<String[]> inCat = new ArrayList<String[]>();
                for (int i = 0; i < all.size(); i++) if (all.get(i)[0].equals(cats.get(which))) inCat.add(all.get(i));
                String[] labels = new String[inCat.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = inCat.get(i)[1];
                new AlertDialog.Builder(act).setTitle(cats.get(which)).setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d2, int w2) { String[] e = inCat.get(w2); insertText(e[2], Integer.parseInt(e[3])); }
                }).setNegativeButton("Back", null).show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void helpDialog() {
        final ScriptInterp in = sh.knownInterp();
        final TextView tv = new TextView(act);
        tv.setTextColor(TXT); tv.setTypeface(Typeface.MONOSPACE); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setText(ScriptDocs.QUICK);
        final ScrollView sv = new ScrollView(act);
        sv.addView(tv);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL); box.setBackgroundColor(BG);
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button("Quick start", 0xFF3A6EA8, new View.OnClickListener() { public void onClick(View v) { tv.setText(ScriptDocs.QUICK); sv.scrollTo(0, 0); } }));
        bar.addView(button("Language", 0xFF3A6EA8, new View.OnClickListener() { public void onClick(View v) { tv.setText(ScriptDocs.LANGUAGE); sv.scrollTo(0, 0); } }));
        bar.addView(button("All functions", 0xFF3A6EA8, new View.OnClickListener() { public void onClick(View v) { tv.setText(ScriptDocs.api(in)); sv.scrollTo(0, 0); } }));
        box.addView(bar);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        new AlertDialog.Builder(act).setTitle("Help").setView(box).setPositiveButton("Close", null).show();
    }
}
