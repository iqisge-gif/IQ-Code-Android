package com.termux.app.iqcode.model;

import java.util.UUID;

/**
 * Immutable snapshot of one plan workflow. Replacing the snapshot, rather than mutating individual
 * fields, lets the engine and UI safely exchange revisions while a plan is being edited.
 */
public final class PlanWorkflowState {
    public enum Status {
        IDLE,
        PLANNING,
        AWAITING_APPROVAL,
        EXECUTING,
        CANCELLED
    }

    /** Convenience aliases for callers that do not need to import {@link Status}. */
    public static final Status IDLE = Status.IDLE;
    public static final Status PLANNING = Status.PLANNING;
    public static final Status AWAITING_APPROVAL = Status.AWAITING_APPROVAL;
    public static final Status EXECUTING = Status.EXECUTING;
    public static final Status CANCELLED = Status.CANCELLED;

    public final Status status;
    public final String workflowId;
    public final long revision;
    public final String previousPermissionMode;
    public final String approvedPermissionMode;
    public final String planFile;
    public final String planText;
    public final String feedback;
    public final long updatedAt;

    private PlanWorkflowState(Status status, String workflowId, long revision,
                              String previousPermissionMode, String approvedPermissionMode,
                              String planFile, String planText, String feedback, long updatedAt) {
        this.status = status == null ? Status.IDLE : status;
        this.workflowId = value(workflowId);
        this.revision = Math.max(0L, revision);
        this.previousPermissionMode = permissionMode(previousPermissionMode);
        this.approvedPermissionMode = value(approvedPermissionMode);
        this.planFile = value(planFile);
        this.planText = value(planText);
        this.feedback = value(feedback);
        this.updatedAt = Math.max(0L, updatedAt);
    }

    public static PlanWorkflowState idle() {
        return new PlanWorkflowState(Status.IDLE, "", 0L, "default", "", "", "", "",
            System.currentTimeMillis());
    }

    /** Starts a new workflow with a generated stable ID. */
    public static PlanWorkflowState planning(String previousPermissionMode) {
        return planning(UUID.randomUUID().toString(), previousPermissionMode, "");
    }

    /** Starts a new workflow with an externally chosen ID and optional plan path. */
    public static PlanWorkflowState planning(String workflowId, String previousPermissionMode,
                                             String planFile) {
        String id = value(workflowId);
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        return new PlanWorkflowState(Status.PLANNING, id, 1L, previousPermissionMode, "",
            planFile, "", "", System.currentTimeMillis());
    }

    /** Rehydrates a persisted snapshot without changing its revision or timestamp. */
    public static PlanWorkflowState restore(Status status, String workflowId, long revision,
                                            String previousPermissionMode,
                                            String approvedPermissionMode, String planFile,
                                            String planText, String feedback, long updatedAt) {
        return new PlanWorkflowState(status, workflowId, revision, previousPermissionMode,
            approvedPermissionMode, planFile, planText, feedback, updatedAt);
    }

    public PlanWorkflowState copy() {
        return new PlanWorkflowState(status, workflowId, revision, previousPermissionMode,
            approvedPermissionMode, planFile, planText, feedback, updatedAt);
    }

    /** Publishes a new editable plan revision and clears feedback from the preceding revision. */
    public PlanWorkflowState withPlan(String planFile, String planText) {
        requireWorkflow();
        if (status != Status.PLANNING && status != Status.AWAITING_APPROVAL) {
            throw new IllegalStateException("Cannot edit a plan while workflow is " + status);
        }
        return next(Status.PLANNING, "", planFile, planText, "");
    }

    public PlanWorkflowState withPlanText(String planText) {
        return withPlan(planFile, planText);
    }

    public PlanWorkflowState withPlanFile(String planFile) {
        return withPlan(planFile, planText);
    }

    public PlanWorkflowState awaitingApproval() {
        requireStatus(Status.PLANNING);
        return next(Status.AWAITING_APPROVAL, "", planFile, planText, feedback);
    }

    /** Approval starts execution without changing the user-owned permission baseline. */
    public PlanWorkflowState executing() {
        requireStatus(Status.AWAITING_APPROVAL);
        return next(Status.EXECUTING, "", planFile, planText, "");
    }

    public PlanWorkflowState keepPlanning(String feedback) {
        requireStatus(Status.AWAITING_APPROVAL);
        return next(Status.PLANNING, "", planFile, planText, feedback);
    }

    public PlanWorkflowState finished() {
        requireStatus(Status.EXECUTING);
        return next(Status.IDLE, approvedPermissionMode, planFile, planText, "");
    }

    public PlanWorkflowState cancelled(String feedback) {
        requireWorkflow();
        if (status == Status.CANCELLED) return this;
        if (status == Status.IDLE) throw new IllegalStateException("No plan workflow to cancel");
        return next(Status.CANCELLED, "", planFile, planText, feedback);
    }

    public boolean isIdle() { return status == Status.IDLE; }
    public boolean isPlanning() { return status == Status.PLANNING; }
    public boolean isAwaitingApproval() { return status == Status.AWAITING_APPROVAL; }
    public boolean isExecuting() { return status == Status.EXECUTING; }
    public boolean isCancelled() { return status == Status.CANCELLED; }
    public boolean isActive() {
        return status == Status.PLANNING || status == Status.AWAITING_APPROVAL
            || status == Status.EXECUTING;
    }

    private PlanWorkflowState next(Status nextStatus, String approvedMode, String nextPlanFile,
                                   String nextPlanText, String nextFeedback) {
        long now = Math.max(System.currentTimeMillis(), updatedAt + 1L);
        return new PlanWorkflowState(nextStatus, workflowId, revision + 1L,
            previousPermissionMode, approvedMode, nextPlanFile, nextPlanText, nextFeedback, now);
    }

    private void requireWorkflow() {
        if (workflowId.isEmpty()) throw new IllegalStateException("Plan workflow has no ID");
    }

    private void requireStatus(Status required) {
        requireWorkflow();
        if (status != required) {
            throw new IllegalStateException("Expected workflow state " + required + " but was " + status);
        }
    }

    private static String permissionMode(String mode) {
        String normalized = value(mode);
        return normalized.isEmpty() ? "default" : normalized;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
