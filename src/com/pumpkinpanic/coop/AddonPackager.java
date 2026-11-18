package com.pumpkinpanic.coop;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Addons are FOLDERS on disk (mod_info.json + textures/scripts/maps...). LAN sync moves them as
 * single .zip files. This class is the one place that knows how:
 *
 *   hashFolder  - SHA-256 over the sorted relative paths + file bytes. Independent of zip
 *                 compression, so host and client agree even if their zlib builds differ.
 *   zipFolder   - zip with sorted entries and a fixed timestamp.
 *   extractZip  - zip-slip safe, entry/size capped.
 *
 * Hidden files and folders (name starts with '.') are ignored everywhere.
 */
final class AddonPackager {
    static final int MAX_FILES = 4000;
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final long FIXED_TIME = 315532800000L;     // 1980-01-01, the zip epoch
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AddonPackager() { }

    /** Sorted relative paths (forward slashes) of every non-hidden file under dir. */
    static List<String> listFiles(File dir) throws IOException {
        List<String> out = new ArrayList<String>();
        walk(dir, "", out);
        Collections.sort(out);
        if (out.size() > MAX_FILES) throw new IOException("Addon has too many files (" + out.size() + ")");
        return out;
    }

    private static void walk(File dir, String prefix, List<String> out) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String n = k.getName();
            if (n.length() == 0 || n.charAt(0) == '.') continue;
            if (k.isDirectory()) {
                walk(k, prefix + n + "/", out);
            } else if (k.isFile()) {
                out.add(prefix + n);
                if (out.size() > MAX_FILES + 1) return;
            }
        }
    }

    /** Cheap change detector: file count, total bytes and newest modification time. */
    static String signature(File dir) {
        try {
            List<String> files = listFiles(dir);
            long total = 0, newest = 0;
            for (String rel : files) {
                File f = new File(dir, rel);
                total += f.length();
                if (f.lastModified() > newest) newest = f.lastModified();
            }
            return files.size() + ":" + total + ":" + newest;
        } catch (IOException e) {
            return "?";
        }
    }

    static String hashFolder(File dir) throws IOException {
        MessageDigest md = sha256();
        long total = 0;
        byte[] buf = new byte[16 * 1024];
        for (String rel : listFiles(dir)) {
            File f = new File(dir, rel);
            long len = f.length();
            total += len;
            if (total > MAX_TOTAL_BYTES) throw new IOException("Addon is too large");
            md.update(rel.getBytes("UTF-8"));
            md.update((byte) 0);
            for (int i = 7; i >= 0; i--) md.update((byte) (len >>> (i * 8)));
            InputStream in = new FileInputStream(f);
            try {
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            } finally {
                close(in);
            }
        }
        return hex(md.digest());
    }

    /** Writes dir as a zip (entries relative to dir). Overwrites outZip. */
    static void zipFolder(File dir, File outZip) throws IOException {
        File tmp = new File(outZip.getPath() + ".tmp");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
        boolean ok = false;
        try {
            zos.setLevel(6);
            byte[] buf = new byte[16 * 1024];
            for (String rel : listFiles(dir)) {
                ZipEntry ze = new ZipEntry(rel);
                ze.setTime(FIXED_TIME);
                zos.putNextEntry(ze);
                InputStream in = new FileInputStream(new File(dir, rel));
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                } finally {
                    close(in);
                }
                zos.closeEntry();
            }
            ok = true;
        } finally {
            try { zos.close(); } catch (IOException ignored) { }
            if (!ok) tmp.delete();
        }
        if (outZip.exists() && !outZip.delete()) throw new IOException("Cannot replace " + outZip.getName());
        if (!tmp.renameTo(outZip)) throw new IOException("Cannot write " + outZip.getName());
    }

    /** Extracts zip into destDir (created). Rejects absolute / ".." paths and oversized archives. */
    static void extractZip(File zip, File destDir) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("Cannot create " + destDir.getPath());
        String root = destDir.getCanonicalPath() + File.separator;
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
        try {
            byte[] buf = new byte[16 * 1024];
            int entries = 0;
            long total = 0;
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                if (++entries > MAX_FILES) throw new IOException("Too many files in addon archive");
                String name = ze.getName().replace('\\', '/');
                if (name.length() == 0 || name.startsWith("/") || name.indexOf(':') >= 0) {
                    throw new IOException("Unsafe path in addon archive: " + name);
                }
                File target = new File(destDir, name);
                String canon = target.getCanonicalPath();
                if (!canon.startsWith(root)) throw new IOException("Unsafe path in addon archive: " + name);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create folder for " + name);
                }
                OutputStream out = new FileOutputStream(target);
                try {
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_TOTAL_BYTES) throw new IOException("Addon archive is too large");
                        out.write(buf, 0, n);
                    }
                } finally {
                    close(out);
                }
            }
        } finally {
            close(zin);
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    /** Folder-name-safe form of an addon id. */
    static String safeName(String id) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length() && sb.length() < 64; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString();
        while (s.startsWith(".")) s = s.substring(1);
        return s.length() == 0 ? "addon" : s;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    private static void close(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) { }
    }
}
