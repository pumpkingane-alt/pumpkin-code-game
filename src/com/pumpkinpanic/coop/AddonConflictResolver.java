package com.pumpkinpanic.coop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds conflicts between the addons the player switched on, BEFORE anything is loaded.
 * Everything is detected from the files on disk, so no other subsystem has to be booted first:
 *
 *   1. explicit   "incompatible_with" in mod_info.json                    -> FATAL
 *   2. both set   "mainMapOverride"                                        -> FATAL
 *   3. both ship the same texture / sound key (e.g. mob_demon.png)         -> overlap warning
 *   4. both define the same Lua event function (e.g. onCropHarvest)        -> overlap warning
 *
 * resolve() returns the addons that may run: for every FATAL conflict the addon involved in the
 * most conflicts (ties: the later one in load order) is left out for this session. The player's
 * saved choices are not changed.
 */
public class AddonConflictResolver {

    private static final String TAG = "AddonConflictResolver";
    private static final int MAX_LINES_PER_SECTION = 8;
    private static final Pattern FUNC_DEF = Pattern.compile("function\\s+(on[A-Za-z]+)\\s*\\(");
    private static final Pattern FUNC_ASSIGN = Pattern.compile("(?:^|[^A-Za-z0-9_.])(on[A-Za-z]+)\\s*=\\s*function");

    private final Set<String> sharedSafeEvents = new HashSet<String>();
    private volatile List<ConflictWarning> lastWarnings = Collections.emptyList();
    private volatile List<String> lastMessages = Collections.emptyList();

    public AddonConflictResolver() {
        // Events nearly every addon uses: not worth warning about.
        sharedSafeEvents.add(AddonEventBus.ON_UPDATE);
        sharedSafeEvents.add(AddonEventBus.ON_ADDONS_READY);
    }

    /** Events many addons legitimately listen to are ignored by the overlap check. */
    public AddonConflictResolver allowSharedEvent(String eventKey) {
        if (eventKey != null) sharedSafeEvents.add(eventKey);
        return this;
    }

    // ------------------------------------------------------------------ resolve

    /** Returns the subset of enabled addons that can run together (load order kept). */
    public List<AddonManifest> resolve(List<AddonManifest> enabled) {
        List<ConflictWarning> warnings = checkConflicts(enabled);
        lastWarnings = warnings;

        List<ConflictWarning> fatal = new ArrayList<ConflictWarning>();
        for (ConflictWarning w : warnings) if (w.isFatal()) fatal.add(w);

        List<AddonManifest> approved = new ArrayList<AddonManifest>(enabled);
        if (!fatal.isEmpty()) approved.removeAll(pickAddonsToDisable(fatal, enabled));

        List<String> msgs = new ArrayList<String>();
        for (ConflictWarning w : warnings) msgs.add(w.toString());
        lastMessages = msgs;
        return approved;
    }

    /** One line per conflict found by the last resolve(). */
    public List<String> getMessages() { return lastMessages; }

    public List<ConflictWarning> getWarnings() { return lastWarnings; }

    // ------------------------------------------------------------------ detection

    public List<ConflictWarning> checkConflicts(List<AddonManifest> activeAddons) {
        List<ConflictWarning> warnings = new ArrayList<ConflictWarning>();
        if (activeAddons == null) return warnings;

        List<AddonManifest> addons = new ArrayList<AddonManifest>();
        List<Profile> profiles = new ArrayList<Profile>();
        for (AddonManifest manifest : activeAddons) {
            if (manifest == null || manifest.getId() == null) continue;
            addons.add(manifest);
            profiles.add(buildProfile(manifest));
        }

        for (int i = 0; i < addons.size(); i++) {
            for (int j = i + 1; j < addons.size(); j++) {
                AddonManifest a = addons.get(i);
                AddonManifest b = addons.get(j);
                if (a.getId().equals(b.getId())) continue;
                comparePair(a, profiles.get(i), b, profiles.get(j), warnings);
            }
        }
        return warnings;
    }

    private void comparePair(AddonManifest a, Profile pa, AddonManifest b, Profile pb,
                             List<ConflictWarning> out) {
        // 1) Explicit incompatibility (declared by either side)
        boolean aDeclares = pa.incompatibleWith.contains(b.getId());
        boolean bDeclares = pb.incompatibleWith.contains(a.getId());
        if (aDeclares || bDeclares) {
            String by = (aDeclares && bDeclares)
                    ? "both addons"
                    : "'" + nameOf(aDeclares ? a : b) + "'";
            out.add(new ConflictWarning(a, b,
                    "Explicit incompatibility declared in manifest by " + by,
                    ConflictWarning.Severity.FATAL_DISABLE_REQUIRED));
        }

        // 2) Same default texture / sound / main map overridden by both addons
        for (String key : intersect(pa.assetOverrides, pb.assetOverrides)) {
            int sep = key.indexOf(':');
            String type = sep > 0 ? key.substring(0, sep) : "asset";
            String id = sep > 0 ? key.substring(sep + 1) : key;
            out.add(new ConflictWarning(a, b,
                    "Both addons attempt to override " + type + " '" + id + "'",
                    severityForAssetType(type)));
        }

        // 3) Same event listened to by both addons
        for (String event : intersect(pa.listeners, pb.listeners)) {
            if (sharedSafeEvents.contains(event)) continue;
            out.add(new ConflictWarning(a, b,
                    "Both addons register listeners for event '" + event + "'",
                    ConflictWarning.Severity.WARNING_OVERLAP));
        }
    }

    // Two addons replacing the main map can't both work; other overlaps just shadow each other.
    private ConflictWarning.Severity severityForAssetType(String type) {
        return ("map".equalsIgnoreCase(type) || "scene".equalsIgnoreCase(type))
                ? ConflictWarning.Severity.FATAL_DISABLE_REQUIRED
                : ConflictWarning.Severity.WARNING_OVERLAP;
    }

    private Profile buildProfile(AddonManifest manifest) {
        Profile p = new Profile();
        p.incompatibleWith.addAll(manifest.getIncompatibleWith());
        File dir = manifest.getDirectory();
        p.assetOverrides.addAll(AddonAssetPipeline.scanOverrides(dir));
        if (manifest.getMainMapOverride().length() > 0) p.assetOverrides.add("map:main");
        p.listeners.addAll(scanListeners(manifest));
        return p;
    }

    /** Names of event functions defined by the addon's Lua files (found by reading the source). */
    private Set<String> scanListeners(AddonManifest manifest) {
        Set<String> events = new LinkedHashSet<String>();
        Set<String> known = new HashSet<String>();
        for (String e : AddonEventBus.ALL_EVENTS) known.add(e);

        List<File> files = new ArrayList<File>();
        File[] scripts = new File(manifest.getDirectory(), "scripts").listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) { return name.endsWith(".lua"); }
        });
        if (scripts != null) Collections.addAll(files, scripts);
        File entry = manifest.resolveFile(manifest.getEntryScript());
        if (entry != null && entry.isFile() && !files.contains(entry)) files.add(entry);

        for (File f : files) {
            try {
                String src = AddonManifest.readText(f);
                Matcher m = FUNC_DEF.matcher(src);
                while (m.find()) if (known.contains(m.group(1))) events.add(m.group(1));
                m = FUNC_ASSIGN.matcher(src);
                while (m.find()) if (known.contains(m.group(1))) events.add(m.group(1));
            } catch (IOException e) {
                Log.w(TAG, "Cannot read " + f.getPath() + ": " + e.getMessage());
            }
        }
        return events;
    }

    // ------------------------------------------------------------------ notification

    /** Shows a dialog telling the user which addon(s) were switched off for this session. Needs an Activity context. */
    public void resolveAndNotify(final Context context, final List<ConflictWarning> warnings) {
        if (context == null || warnings == null || warnings.isEmpty()) return;

        for (ConflictWarning w : warnings) {
            if (w != null) Log.w(TAG, w.toString());
        }

        final boolean fatal = hasFatal(warnings);
        final String message = buildMessage(warnings);

        Runnable show = new Runnable() {
            @Override
            public void run() {
                Activity activity = findActivity(context);
                if (activity == null || activity.isFinishing()) {
                    Log.w(TAG, "No live Activity to show dialog:\n" + message);
                    return;
                }
                new AlertDialog.Builder(activity)
                        .setTitle(fatal ? "Addon conflict" : "Addon overlap warning")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            new Handler(Looper.getMainLooper()).post(show);
        }
    }

    private String buildMessage(List<ConflictWarning> warnings) {
        List<ConflictWarning> fatal = new ArrayList<ConflictWarning>();
        List<ConflictWarning> minor = new ArrayList<ConflictWarning>();
        for (ConflictWarning w : warnings) {
            if (w == null) continue;
            if (w.isFatal()) {
                fatal.add(w);
            } else {
                minor.add(w);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!fatal.isEmpty()) {
            sb.append("These addons cannot run together:\n");
            appendLines(sb, fatal);
            sb.append("\nLeft out for now: ")
                    .append(joinNames(pickAddonsToDisable(fatal, null)))
                    .append("\nOpen the Addons menu to choose which one you want.\n");
        }
        if (!minor.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Possible overlaps (addons still run; the first one loaded wins):\n");
            appendLines(sb, minor);
        }
        return sb.toString().trim();
    }

    private void appendLines(StringBuilder sb, List<ConflictWarning> list) {
        int shown = Math.min(list.size(), MAX_LINES_PER_SECTION);
        for (int i = 0; i < shown; i++) {
            ConflictWarning w = list.get(i);
            sb.append("\u2022 ")
                    .append(nameOf(w.getAddonA())).append(" / ").append(nameOf(w.getAddonB()))
                    .append(": ").append(w.getConflictReason()).append('\n');
        }
        if (list.size() > shown) {
            sb.append("...and ").append(list.size() - shown).append(" more\n");
        }
    }

    // Greedy pick: disable the addon in the most unresolved fatal conflicts (ties -> the later-loaded one).
    private List<AddonManifest> pickAddonsToDisable(List<ConflictWarning> fatal, List<AddonManifest> loadOrder) {
        List<ConflictWarning> remaining = new ArrayList<ConflictWarning>(fatal);
        List<AddonManifest> picks = new ArrayList<AddonManifest>();

        while (!remaining.isEmpty()) {
            Map<AddonManifest, Integer> counts = new LinkedHashMap<AddonManifest, Integer>();
            for (ConflictWarning w : remaining) {
                tally(counts, w.getAddonA());
                tally(counts, w.getAddonB());
            }

            AddonManifest best = null;
            int bestCount = 0, bestIndex = -1;
            for (Map.Entry<AddonManifest, Integer> e : counts.entrySet()) {
                int idx = loadOrder == null ? 0 : loadOrder.indexOf(e.getKey());
                if (e.getValue() > bestCount || (e.getValue() == bestCount && idx >= bestIndex)) {
                    bestCount = e.getValue();
                    bestIndex = idx;
                    best = e.getKey();
                }
            }

            picks.add(best);
            for (Iterator<ConflictWarning> it = remaining.iterator(); it.hasNext(); ) {
                ConflictWarning w = it.next();
                if (best.equals(w.getAddonA()) || best.equals(w.getAddonB())) {
                    it.remove();
                }
            }
        }
        return picks;
    }

    private static void tally(Map<AddonManifest, Integer> counts, AddonManifest m) {
        Integer c = counts.get(m);
        counts.put(m, c == null ? 1 : c + 1);
    }

    // ------------------------------------------------------------------ helpers

    private static String joinNames(List<AddonManifest> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(nameOf(list.get(i)));
        }
        return sb.toString();
    }

    private static boolean hasFatal(List<ConflictWarning> list) {
        for (ConflictWarning w : list) {
            if (w != null && w.isFatal()) return true;
        }
        return false;
    }

    private static Activity findActivity(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static String nameOf(AddonManifest m) {
        String name = m.getName();
        return (name != null && name.length() > 0) ? name : m.getId();
    }

    private static Set<String> intersect(Set<String> x, Set<String> y) {
        Set<String> result = new LinkedHashSet<String>(x);
        result.retainAll(y);
        return result;
    }

    private static class Profile {
        final Set<String> incompatibleWith = new HashSet<String>();
        final Set<String> assetOverrides = new LinkedHashSet<String>();
        final Set<String> listeners = new LinkedHashSet<String>();
    }
}
