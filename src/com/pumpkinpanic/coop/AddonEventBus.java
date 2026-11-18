package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event bus for addons. Events are plain strings. They are also the names of the global Lua
 * functions an addon script can define, so  function onCropHarvest(tx, ty, crop) ... end  is
 * called when AddonEngineController posts "onCropHarvest".
 *
 * Event            payload keys (EventData extras)         Lua arguments
 * onUpdate         delta                                    (delta)
 * onPlayerMove     x, y                                     (x, y)
 * onCropHarvest    tileX, tileY, crop                       (tileX, tileY, crop)
 * onCropWater      tileX, tileY                             (tileX, tileY)
 * onMonsterSpawn   kind, x, y                               (kind, x, y)
 * onNightFall      -                                        ()
 * onItemUse        item, x, y                               (item, x, y)
 * onItemBuy        itemId, slot                             (itemId, slot)
 * onSceneEnter     scene                                    (sceneId)
 * onAddonsReady    count                                    (count)
 *
 * Listeners are tagged with an owner (the addon id) so a whole addon can be unregistered at once.
 */
public final class AddonEventBus {

    public static final String ON_UPDATE = "onUpdate";
    public static final String ON_PLAYER_MOVE = "onPlayerMove";
    public static final String ON_CROP_HARVEST = "onCropHarvest";
    public static final String ON_CROP_WATER = "onCropWater";
    public static final String ON_MONSTER_SPAWN = "onMonsterSpawn";
    public static final String ON_NIGHT_FALL = "onNightFall";
    public static final String ON_ITEM_USE = "onItemUse";
    public static final String ON_ITEM_BUY = "onItemBuy";
    public static final String ON_SCENE_ENTER = "onSceneEnter";
    public static final String ON_ADDONS_READY = "onAddonsReady";

    public static final String[] ALL_EVENTS = {
        ON_UPDATE, ON_PLAYER_MOVE, ON_CROP_HARVEST, ON_CROP_WATER, ON_MONSTER_SPAWN,
        ON_NIGHT_FALL, ON_ITEM_USE, ON_ITEM_BUY, ON_SCENE_ENTER, ON_ADDONS_READY
    };

    public interface AddonEventListener {
        void onEvent(String event, EventData payload);
    }

    private static final class Reg {
        final String owner;
        final AddonEventListener listener;
        Reg(String owner, AddonEventListener listener) {
            this.owner = owner;
            this.listener = listener;
        }
    }

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Reg>> listeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Reg>>();

    public AddonEventBus() { }

    /** Safe to call at any time, including from inside a listener. */
    public void registerListener(String event, String ownerId, AddonEventListener listener) {
        if (event == null || listener == null) return;
        CopyOnWriteArrayList<Reg> list = listeners.get(event);
        if (list == null) {
            CopyOnWriteArrayList<Reg> fresh = new CopyOnWriteArrayList<Reg>();
            list = listeners.putIfAbsent(event, fresh);
            if (list == null) list = fresh;
        }
        list.add(new Reg(ownerId == null ? "" : ownerId, listener));
    }

    /** Removes every listener registered by one addon. */
    public void unregisterOwner(String ownerId) {
        for (Map.Entry<String, CopyOnWriteArrayList<Reg>> e : listeners.entrySet()) {
            ArrayList<Reg> gone = new ArrayList<Reg>();
            for (Reg r : e.getValue()) if (r.owner.equals(ownerId)) gone.add(r);
            e.getValue().removeAll(gone);
        }
    }

    public void clear() {
        listeners.clear();
    }

    /**
     * Calls listeners in registration order. A throwing listener is logged and does not stop the
     * others. Returns false when a listener canceled a cancelable event.
     */
    public boolean post(String event, EventData payload) {
        CopyOnWriteArrayList<Reg> list = listeners.get(event);
        if (list == null || list.isEmpty()) return true;
        if (payload == null) payload = new EventData();
        for (Reg r : list) {
            try {
                r.listener.onEvent(event, payload);
            } catch (RuntimeException e) {
                android.util.Log.w("AddonEventBus", "listener of '" + r.owner + "' failed on " + event + ": " + e);
            }
            if (payload.isCanceled()) return false;
        }
        return true;
    }

    /** Event names this addon is currently listening to. */
    public Collection<String> getListenerRegistrations(String ownerId) {
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, CopyOnWriteArrayList<Reg>> e : listeners.entrySet()) {
            for (Reg r : e.getValue()) if (r.owner.equals(ownerId)) out.add(e.getKey());
        }
        return out;
    }
}
