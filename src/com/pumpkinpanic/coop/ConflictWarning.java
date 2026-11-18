package com.pumpkinpanic.coop;

/** Describes a conflict between two active addons. */
public class ConflictWarning {

    public enum Severity {
        /** One of the two addons must be toggled off. */
        FATAL_DISABLE_REQUIRED,
        /** Addons can still run together, but they overlap. */
        WARNING_OVERLAP
    }

    private final AddonManifest addonA;
    private final AddonManifest addonB;
    private final String conflictReason;
    private final Severity severity;

    public ConflictWarning(AddonManifest addonA, AddonManifest addonB,
                           String conflictReason, Severity severity) {
        if (addonA == null || addonB == null || severity == null) {
            throw new IllegalArgumentException("addonA, addonB and severity are required");
        }
        this.addonA = addonA;
        this.addonB = addonB;
        this.conflictReason = conflictReason != null ? conflictReason : "";
        this.severity = severity;
    }

    public AddonManifest getAddonA() { return addonA; }
    public AddonManifest getAddonB() { return addonB; }
    public String getConflictReason() { return conflictReason; }
    public Severity getSeverity() { return severity; }

    public boolean isFatal() {
        return severity == Severity.FATAL_DISABLE_REQUIRED;
    }

    @Override
    public String toString() {
        return severity + ": " + addonA.getId() + " <-> " + addonB.getId()
                + " (" + conflictReason + ")";
    }
}
