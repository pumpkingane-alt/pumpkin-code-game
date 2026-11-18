package com.pumpkinpanic.coop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import android.util.Log;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

/** Pumpkin Panic: 4-player LAN co-op. Single activity, everything is drawn/synthesised in code. */
public class MainActivity extends Activity implements GameView.Host {
    private GameView view;
    private Vibrator vib;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GameView(this, this);
        SharedPreferences sp = getSharedPreferences("pumpkin", MODE_PRIVATE);
        view.myName = sp.getString("name", "Pumpkin");
        view.soundOn = sp.getBoolean("sound", true);
        view.lastIp = sp.getString("ip", "192.168.");
        setContentView(view);
        try { vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE); } catch (Throwable t) { vib = null; }
        immersive();
        // Addons live in a folder on shared storage: ask once, then boot them either way
        // (without the permission the addons folder falls back to the app's own folder).
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_STORAGE);
        } else { bootAddons(); setupScripts(); }
    }

    /** Scripts are plain .py files: on shared storage (PumpkinPanic/scripts, easy to edit with other apps) or, without permission, in the app folder. */
    private void setupScripts() {
        if (view.store != null) return;
        File dir = null;
        try {
            File ext = new File(Environment.getExternalStorageDirectory(), "PumpkinPanic/scripts");
            ext.mkdirs();
            if (ext.isDirectory() && ext.canWrite()) dir = ext;
        } catch (Throwable t) { dir = null; }
        if (dir == null) dir = new File(getFilesDir(), "scripts");
        view.store = new ScriptStore(dir);
        SharedPreferences sp = getSharedPreferences("pumpkin", MODE_PRIVATE);
        view.autoRun = sp.getBoolean("autorun", true);
        String name = sp.getString("script", "main");
        view.activeScript = view.store.exists(name) ? name : "main";
    }

    private static final int REQ_STORAGE = 4101;

    private void bootAddons() {
        AddonEngineController.get().init(this, new AddonEngineController.BootListener() {
            public void onStage(String stage) { Log.i("PumpkinPanic", "Addons: " + stage); }
            public void onFinished(boolean ok, int activeAddons) { Log.i("PumpkinPanic", "Addon boot " + (ok ? "OK" : "FAILED") + ", active addons: " + activeAddons); }
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_STORAGE) { bootAddons(); setupScripts(); }
    }

    private void immersive() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } catch (Throwable t) { /* old devices */ }
    }

    @Override protected void onResume() { super.onResume(); AddonEngineController.get().attachActivity(this); immersive(); view.resume(); }
    @Override protected void onPause() {
        getSharedPreferences("pumpkin", MODE_PRIVATE).edit().putBoolean("sound", view.soundOn).putString("ip", view.lastIp).apply();
        view.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        view.destroy();
        AddonEngineController addons = AddonEngineController.get();
        addons.detachActivity(this);
        if (isFinishing()) addons.shutdown();            // not on rotation / re-creation
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(); }
    @Override public void onBackPressed() { view.onBack(); }

    // ---- GameView.Host
    public void prompt(final String title, final String def, final GameView.Cb cb) {
        runOnUiThread(new Runnable() {
            public void run() {
                final EditText et = new EditText(MainActivity.this);
                et.setText(def); et.setSingleLine(true); et.setSelectAllOnFocus(true); et.setInputType(InputType.TYPE_CLASS_TEXT);
                new AlertDialog.Builder(MainActivity.this).setTitle(title).setView(et).setCancelable(true)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { cb.done(et.getText().toString()); } })
                    .setNegativeButton("Cancel", null).show();
            }
        });
    }
    public void toast(final String s) { runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); } }); }
    public void vibrate(int ms) { try { if (vib != null) vib.vibrate(ms); } catch (Throwable t) { /* ignore */ } }
    public void saveName(String n) { getSharedPreferences("pumpkin", MODE_PRIVATE).edit().putString("name", n).apply(); }
    public void quit() { finish(); }
    public void openEditor() {
        runOnUiThread(new Runnable() { public void run() {
            setupScripts();
            if (!view.editorOn && !isFinishing()) new CodeEditor(MainActivity.this, view).show();
        } });
    }
    public String loadPref(String key, String def) { return getSharedPreferences("pumpkin", MODE_PRIVATE).getString(key, def); }
    public void savePref(String key, String value) {
        SharedPreferences.Editor ed = getSharedPreferences("pumpkin", MODE_PRIVATE).edit();
        if (key.equals("autorun")) ed.putBoolean("autorun", "1".equals(value)); else ed.putString(key, value);
        ed.apply();
    }
}
