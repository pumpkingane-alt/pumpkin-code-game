package com.pumpkinpanic.coop;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-game Addon Manager (full-screen dialog). Needs API 14+ (android.widget.Switch).
 *
 * Show it from your game Activity (create a new instance each time):
 *     new AddonManagerDialog(this).show();
 *
 * Layout: res/layout/addon_manager.xml. No manifest changes needed.
 * Rows are built in code, so this is the only Java file and the only XML file.
 * Only the four methods under "PROJECT HOOKS" touch your code. Their class and
 * method names are assumptions: rename them to match AddonDataManager / AddonAssetPipeline.
 */
public class AddonManagerDialog extends Dialog {

    private static final int TITLE = 0xFFF3E5CF, BODY = 0xFFD8CEE6, MUTED = 0xFFA99BBB;

    /** Plain copy of one addon's data, so the UI never reads AddonDataManager directly. */
    private static class Row {
        String id, title, author, version, description;
        boolean enabled;
        File folder;
    }

    private static class Holder {
        ImageView icon;
        LinearLayout body;
        TextView title, version, author, desc;
        Switch toggle;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RowAdapter adapter = new RowAdapter();
    private final Set<String> iconRequested = new HashSet<String>();
    private final LruCache<String, Bitmap> icons =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
                @Override
                protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
            };

    private ExecutorService io;   // single thread: scans and icon loads never overlap
    private boolean busy;
    private boolean dirty;       // a switch was flipped: reload addons when the menu closes
    private int session;          // bumped on start/stop so late callbacks are ignored

    private TextView summary, empty;
    private View busyOverlay;
    private Button rescanButton;

    /** @param context the game Activity (a Dialog needs an Activity context) */
    public AddonManagerDialog(Context context) {
        super(context, android.R.style.Theme_Holo_NoActionBar_Fullscreen);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.addon_manager);

        summary = (TextView) findViewById(R.id.addonSummary);
        empty = (TextView) findViewById(R.id.addonEmpty);
        empty.setText("No addons found.\nPut addon folders (each with a mod_info.json) in:\n" + AddonEngineController.getAddonsDir().getPath() + "\nthen tap Re-scan Addons.");
        busyOverlay = findViewById(R.id.addonBusy);
        rescanButton = (Button) findViewById(R.id.addonRescan);
        ((ListView) findViewById(R.id.addonList)).setAdapter(adapter);

        rescanButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { reload(true); }
        });
        findViewById(R.id.addonClose).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismiss(); }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        session++;
        busy = false;
        io = Executors.newSingleThreadExecutor();
        reload(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        session++;      // ignore results that arrive after the dialog closed
        io.shutdown();
        if (dirty) {
            dirty = false;
            Toast.makeText(getContext(), "Applying addons...", Toast.LENGTH_SHORT).show();
            AddonEngineController.get().reloadAsync(null);
        }  // a running re-scan still finishes, so nothing is left half-reloaded
    }

    // ------------------------------------------------------------ loading

    /** Loads the addon list. With rescanFirst it re-detects addons and reloads scripts + textures first. */
    private void reload(final boolean rescanFirst) {
        if (busy) return;
        setBusy(true);
        final int s = session;
        bg(new Runnable() {
            public void run() {
                List<Row> loaded = null;
                String err = null;
                try {
                    if (rescanFirst) rescanAddons();
                    loaded = fetchRows();
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final List<Row> fresh = loaded;
                final String error = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (s != session) return;
                        setBusy(false);
                        if (error != null) {
                            toast((rescanFirst ? "Re-scan failed: " : "Could not load addons: ")
                                    + error + ". Try again.");
                            return;
                        }
                        icons.evictAll();          // textures were reloaded, so drop cached icons
                        iconRequested.clear();
                        adapter.setData(fresh);
                        empty.setVisibility(fresh.isEmpty() ? View.VISIBLE : View.GONE);
                        updateSummary();
                        if (rescanFirst) toast("Addons re-scanned: " + fresh.size() + " found");
                    }
                });
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        busyOverlay.setVisibility(b ? View.VISIBLE : View.GONE);
        rescanButton.setEnabled(!b);
    }

    private void bg(Runnable r) {
        if (io != null && !io.isShutdown()) io.execute(r);
    }

    private void updateSummary() {
        int total = adapter.getCount();
        summary.setText(total == 0 ? "" : adapter.enabledCount() + " of " + total + " enabled");
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ icons

    private void bindIcon(final ImageView iv, final Row r) {
        iv.setTag(r.id);
        Bitmap cached = icons.get(r.id);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        iv.setImageResource(android.R.drawable.sym_def_app_icon);

        // Already queued? A null result stays in this set as a "no icon" marker until the next reload.
        if (!iconRequested.add(r.id)) return;

        final int s = session;
        bg(new Runnable() {
            public void run() {
                Bitmap loaded = null;
                try { loaded = loadIcon(r); } catch (Exception ignored) { }
                if (loaded == null) return;
                final Bitmap bmp = loaded;
                ui.post(new Runnable() {
                    public void run() {
                        if (s != session) return;
                        icons.put(r.id, bmp);
                        iconRequested.remove(r.id);
                        if (r.id.equals(iv.getTag())) iv.setImageBitmap(bmp);  // row may have been recycled
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ list rows

    private void dim(Holder h, boolean on) {
        float a = on ? 1f : 0.45f;
        h.icon.setAlpha(a);
        h.body.setAlpha(a);
    }

    private int dp(int v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private View newRow() {
        Context c = getContext();
        Holder h = new Holder();

        LinearLayout row = new LinearLayout(c);   // horizontal by default
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));

        h.icon = new ImageView(c);
        h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(h.icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        h.body = new LinearLayout(c);
        h.body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bodyLp.setMargins(dp(14), 0, dp(8), 0);
        row.addView(h.body, bodyLp);

        LinearLayout titleLine = new LinearLayout(c);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        h.title = label(c, 17, TITLE);
        h.title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        h.title.setSingleLine(true);
        h.title.setEllipsize(TextUtils.TruncateAt.END);
        h.version = label(c, 13, MUTED);
        h.version.setPadding(dp(8), 0, 0, 0);
        titleLine.addView(h.title,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleLine.addView(h.version);
        h.body.addView(titleLine);

        h.author = label(c, 13, MUTED);
        h.body.addView(h.author);

        h.desc = label(c, 14, BODY);
        h.desc.setMaxLines(3);
        h.desc.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(4);
        h.body.addView(h.desc, descLp);

        h.toggle = new Switch(c);
        row.addView(h.toggle);

        row.setTag(h);
        return row;
    }

    private class RowAdapter extends BaseAdapter {
        private List<Row> data = new ArrayList<Row>();

        void setData(List<Row> rows) {
            data = rows;
            notifyDataSetChanged();
        }

        int enabledCount() {
            int n = 0;
            for (Row r : data) if (r.enabled) n++;
            return n;
        }

        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = newRow();
            final Holder h = (Holder) v.getTag();
            final Row r = data.get(pos);

            h.title.setText(TextUtils.isEmpty(r.title) ? r.id : r.title);

            String ver = r.version == null ? "" : r.version.trim();
            if (ver.length() > 0 && Character.isDigit(ver.charAt(0))) ver = "v" + ver;
            h.version.setText(ver);

            h.author.setText(TextUtils.isEmpty(r.author) ? "Unknown author" : "by " + r.author);
            h.desc.setText(r.description);
            h.desc.setVisibility(TextUtils.isEmpty(r.description) ? View.GONE : View.VISIBLE);

            // Recycled rows: detach the old listener before setChecked so it doesn't fire.
            h.toggle.setOnCheckedChangeListener(null);
            h.toggle.setChecked(r.enabled);
            dim(h, r.enabled);
            h.toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton btn, boolean on) {
                    try {
                        applyEnabled(r, on);
                        dirty = true;
                        r.enabled = on;
                        dim(h, on);
                        updateSummary();
                    } catch (Exception e) {
                        btn.setOnCheckedChangeListener(null);   // revert the switch
                        btn.setChecked(!on);
                        btn.setOnCheckedChangeListener(this);
                        toast("Could not " + (on ? "enable " : "disable ") + h.title.getText() + ". Try again.");
                    }
                }
            });

            bindIcon(h.icon, r);
            return v;
        }
    }

    // ============================ ENGINE HOOKS ============================

    /** Reads every detected addon. Background thread. */
    private List<Row> fetchRows() throws Exception {
        AddonDataManager dm = AddonEngineController.get().getData();
        if (dm == null) throw new Exception("addon system not ready");
        List<Row> out = new ArrayList<Row>();
        for (AddonManifest a : dm.getAddons()) {
            Row r = new Row();
            r.id = a.getId();
            r.title = a.getTitle();
            r.author = a.getAuthor();
            r.version = a.getVersion();
            r.description = a.getDescription();
            r.enabled = a.isEnabled();
            r.folder = a.getDirectory();
            out.add(r);
        }
        return out;
    }

    /** Turns one addon on or off (saved immediately, applied when the menu closes). */
    private void applyEnabled(Row r, boolean on) throws Exception {
        AddonEngineController.get().getData().setEnabled(r.id, on);
    }

    /** Decodes the addon's icon.png; null if missing. Background thread. */
    private Bitmap loadIcon(Row r) throws Exception {
        AddonAssetPipeline ap = AddonEngineController.get().getAssets();
        return ap == null ? null : ap.getAddonIcon(r.folder);
    }

    /** Re-detects addon folders and refreshes the list. Background thread. */
    private void rescanAddons() throws Exception {
        AddonEngineController.get().getData().rescan();
    }
}
