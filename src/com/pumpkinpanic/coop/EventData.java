package com.pumpkinpanic.coop;

import java.util.HashMap;
import java.util.Map;

/**
 * Payload passed to addon listeners. Not thread-safe; use on the thread that fires the event.
 * Well-known keys per event are listed in AddonEventBus.
 */
public class EventData {

    public static final int NO_ID = -1;

    private float x;
    private float y;
    private int sourceId = NO_ID;
    private int targetId = NO_ID;
    private final boolean cancelable;
    private boolean canceled;
    private Map<String, Object> extras;

    public EventData() {
        this(false);
    }

    public EventData(boolean cancelable) {
        this.cancelable = cancelable;
    }

    public EventData(float x, float y, int targetId, boolean cancelable) {
        this.x = x;
        this.y = y;
        this.targetId = targetId;
        this.cancelable = cancelable;
    }

    public float getX() { return x; }
    public float getY() { return y; }

    public EventData setPosition(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public int getSourceId() { return sourceId; }

    public EventData setSourceId(int sourceId) {
        this.sourceId = sourceId;
        return this;
    }

    public int getTargetId() { return targetId; }

    public EventData setTargetId(int targetId) {
        this.targetId = targetId;
        return this;
    }

    public boolean isCancelable() { return cancelable; }
    public boolean isCanceled() { return canceled; }

    /** Has no effect unless this event was created cancelable. */
    public void setCanceled(boolean canceled) {
        if (cancelable) this.canceled = canceled;
    }

    public EventData putExtra(String key, Object value) {
        if (extras == null) extras = new HashMap<String, Object>();
        extras.put(key, value);
        return this;
    }

    /** Short alias for putExtra. */
    public EventData put(String key, Object value) {
        return putExtra(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key, T defaultValue) {
        Object value = (extras == null) ? null : extras.get(key);
        return (value == null) ? defaultValue : (T) value;
    }

    public float getFloat(String key, float def) {
        Object v = extras == null ? null : extras.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    public int getInt(String key, int def) {
        Object v = extras == null ? null : extras.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    public String getString(String key, String def) {
        Object v = extras == null ? null : extras.get(key);
        return v == null ? def : v.toString();
    }
}
