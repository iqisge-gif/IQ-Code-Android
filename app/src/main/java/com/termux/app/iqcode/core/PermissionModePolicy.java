package com.termux.app.iqcode.core;

import com.termux.app.iqcode.model.PlanWorkflowState;

/** User-owned permission baseline plus non-escalating runtime policy rules. */
public final class PermissionModePolicy {
    public static final String DEFAULT = "default";
    public static final String ACCEPT_EDITS = "acceptEdits";
    public static final String AUTO = "auto";
    public static final String PLAN = "plan";
    public static final String DONT_ASK = "dontAsk";
    public static final String BYPASS = "bypassPermissions";

    private PermissionModePolicy() { }

    public static String normalize(String mode) {
        if (DEFAULT.equals(mode) || ACCEPT_EDITS.equals(mode) || AUTO.equals(mode)
            || PLAN.equals(mode) || DONT_ASK.equals(mode) || BYPASS.equals(mode)) return mode;
        return DEFAULT;
    }

    public static String effective(String baseMode, PlanWorkflowState workflowState) {
        if (workflowState != null && (workflowState.isPlanning() || workflowState.isAwaitingApproval())) return PLAN;
        return normalize(baseMode);
    }

    /** A custom subagent definition can restrict a parent policy but can never expand it. */
    public static String capSubagent(String parentEffectiveMode, String requestedMode) {
        String parent = normalize(parentEffectiveMode);
        String requested = normalize(requestedMode);
        if (parent.equals(requested)) return parent;
        if (PLAN.equals(parent) || DONT_ASK.equals(parent)) return parent;
        if (BYPASS.equals(parent)) return requested;
        if (DEFAULT.equals(parent)) return (PLAN.equals(requested) || DONT_ASK.equals(requested)) ? requested : parent;
        if (ACCEPT_EDITS.equals(parent) || AUTO.equals(parent)) {
            return (DEFAULT.equals(requested) || PLAN.equals(requested) || DONT_ASK.equals(requested)
                || ACCEPT_EDITS.equals(requested) || AUTO.equals(requested)) ? requested : parent;
        }
        return parent;
    }
}
