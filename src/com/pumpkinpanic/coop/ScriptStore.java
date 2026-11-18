package com.pumpkinpanic.coop;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Scripts saved as plain text files (name.py) in one folder. Also lets scripts import each other. Java 7 compatible. */
final class ScriptStore implements ScriptInterp.Loader {
    final File dir;

    ScriptStore(File dir) {
        this.dir = dir;
        try { dir.mkdirs(); } catch (Exception e) { /* ignore */ }
        if (!exists("main")) write("main", ScriptTemplates.starter());
    }

    static boolean validName(String n) {
        if (n == null || n.length() == 0 || n.length() > 40) return false;
        char c = n.charAt(0);
        if (!(Character.isLetter(c) || c == '_')) return false;
        for (int i = 1; i < n.length(); i++) { c = n.charAt(i); if (!(Character.isLetterOrDigit(c) || c == '_')) return false; }
        return !ScriptParser.isKeyword(n);
    }

    private File file(String name) { return new File(dir, name + ".py"); }
    boolean exists(String name) { return validName(name) && file(name).isFile(); }

    ArrayList<String> list() {
        ArrayList<String> r = new ArrayList<String>();
        File[] fs = dir.listFiles();
        if (fs != null) for (int i = 0; i < fs.length; i++) {
            String n = fs[i].getName();
            if (fs[i].isFile() && n.endsWith(".py")) { String b = n.substring(0, n.length() - 3); if (validName(b)) r.add(b); }
        }
        Collections.sort(r, new Comparator<String>() {
            public int compare(String a, String b) { if (a.equals("main")) return b.equals("main") ? 0 : -1; if (b.equals("main")) return 1; return a.compareToIgnoreCase(b); }
        });
        return r;
    }

    String read(String name) {
        if (!validName(name)) return null;
        File f = file(name);
        if (!f.isFile()) return null;
        try {
            Reader r = new InputStreamReader(new FileInputStream(f), "UTF-8");
            try {
                StringBuilder sb = new StringBuilder(); char[] buf = new char[4096]; int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                return sb.toString();
            } finally { r.close(); }
        } catch (IOException e) { return null; }
    }

    boolean write(String name, String text) {
        if (!validName(name)) return false;
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file(name)), "UTF-8");
            try { w.write(text); } finally { w.close(); }
            return true;
        } catch (IOException e) { return false; }
    }

    boolean delete(String name) { return validName(name) && file(name).delete(); }

    boolean rename(String from, String to) {
        if (!validName(from) || !validName(to) || exists(to)) return false;
        return file(from).renameTo(file(to));
    }

    String uniqueName(String base) {
        String b = validName(base) ? base : "script";
        if (!exists(b)) return b;
        for (int i = 2; i < 1000; i++) if (!exists(b + i)) return b + i;
        return b + System.currentTimeMillis();
    }

    public String source(String module) { return read(module); }
}
