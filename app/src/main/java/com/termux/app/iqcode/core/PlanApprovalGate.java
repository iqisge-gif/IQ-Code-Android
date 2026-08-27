package com.termux.app.iqcode.core;

import com.termux.app.iqcode.model.PlanWorkflowState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** UI-mediated approval gate for a completed plan. Waiting never implies approval. */
public final class PlanApprovalGate {
    public enum Decision {
        APPROVE(true),
        KEEP_PLANNING(false),
        CANCEL(false);

        private final boolean approved;

        Decision(boolean approved) { this.approved = approved; }

        public boolean isApproved() { return approved; }
    }

    public static final class ApprovalResponse {
        public final Decision decision;
        public final String feedback;

        ApprovalResponse(Decision decision, String feedback) {
            this.decision = decision == null ? Decision.CANCEL : decision;
            this.feedback = feedback == null ? "" : feedback;
        }

        public boolean isApproved() { return decision.isApproved(); }
    }

    public static final class ApprovalRequest {
        public final String requestId;
        public final PlanWorkflowState plan;

        private final CountDownLatch latch = new CountDownLatch(1);
        private ApprovalResponse response;

        ApprovalRequest(PlanWorkflowState plan) {
            requestId = UUID.randomUUID().toString();
            this.plan = plan == null ? PlanWorkflowState.idle() : plan.copy();
        }

        public synchronized ApprovalResponse getResponse() {
            return response;
        }

        private synchronized boolean complete(ApprovalResponse result) {
            if (response != null) return false;
            response = result == null ? new ApprovalResponse(Decision.CANCEL, "") : result;
            latch.countDown();
            return true;
        }
    }

    private final Map<String, ApprovalRequest> pending = new ConcurrentHashMap<>();

    /**
     * Registers an approval request and blocks until respond() or cancelAll() completes it. There is
     * intentionally no timeout/default branch: an unattended request can never approve execution.
     */
    public ApprovalResponse request(PlanWorkflowState plan, Consumer<ApprovalRequest> notify)
        throws InterruptedException {
        ApprovalRequest request = new ApprovalRequest(plan);
        pending.put(request.requestId, request);
        try {
            if (notify != null) notify.accept(request);
            request.latch.await();
            ApprovalResponse response = request.getResponse();
            return response == null ? new ApprovalResponse(Decision.CANCEL, "") : response;
        } catch (InterruptedException e) {
            request.complete(new ApprovalResponse(Decision.CANCEL, ""));
            throw e;
        } catch (RuntimeException e) {
            request.complete(new ApprovalResponse(Decision.CANCEL, ""));
            throw e;
        } finally {
            pending.remove(request.requestId, request);
        }
    }

    public ApprovalResponse awaitApproval(PlanWorkflowState plan,
                                           Consumer<ApprovalRequest> notify)
        throws InterruptedException {
        return request(plan, notify);
    }

    public boolean respond(String requestId, Decision decision) {
        return respond(requestId, decision, "");
    }

    public boolean respond(String requestId, Decision decision, String feedback) {
        if (requestId == null || decision == null) return false;
        ApprovalRequest request = pending.get(requestId);
        ApprovalResponse response = new ApprovalResponse(decision, feedback);
        if (request == null || !request.complete(response)) return false;
        pending.remove(requestId, request);
        return true;
    }

    public void cancelAll() {
        for (ApprovalRequest request : pending.values()) {
            request.complete(new ApprovalResponse(Decision.CANCEL, ""));
            pending.remove(request.requestId, request);
        }
    }

    /** Returns immutable request snapshots for UI recreation after a configuration change. */
    public List<ApprovalRequest> pendingRequests() {
        return new ArrayList<>(pending.values());
    }
}
