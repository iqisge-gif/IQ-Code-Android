import com.termux.app.iqcode.core.PermissionModePolicy;
import com.termux.app.iqcode.model.PlanWorkflowState;

public final class PermissionModePolicyTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void requireEquals(String expected, String actual, String message) {
        require(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    public static void main(String[] args) {
        PlanWorkflowState planning = PlanWorkflowState.planning("workflow", "default", "/tmp/plan.md");
        PlanWorkflowState awaiting = planning.withPlan("/tmp/plan.md", "plan text").awaitingApproval();
        PlanWorkflowState executing = awaiting.executing();
        PlanWorkflowState cancelled = awaiting.cancelled("cancelled");

        requireEquals("plan", PermissionModePolicy.effective("default", planning), "planning must overlay the user baseline with read-only mode");
        requireEquals("plan", PermissionModePolicy.effective("auto", awaiting), "awaiting approval must overlay every baseline with read-only mode");
        requireEquals("acceptEdits", PermissionModePolicy.effective("acceptEdits", executing), "approved plans must return to the user baseline");
        requireEquals("bypassPermissions", PermissionModePolicy.effective("bypassPermissions", cancelled), "cancelled plans must not restore an old or model-supplied mode");
        requireEquals("default", PermissionModePolicy.normalize("unknown"), "unknown modes must fail closed");
        requireEquals("default", PermissionModePolicy.normalize(null), "null modes must fail closed");

        requireEquals("default", PermissionModePolicy.capSubagent("default", "bypassPermissions"), "default parents must reject subagent escalation");
        requireEquals("plan", PermissionModePolicy.capSubagent("default", "plan"), "default parents may let a child restrict itself to plan mode");
        requireEquals("dontAsk", PermissionModePolicy.capSubagent("default", "dontAsk"), "default parents may let a child deny privileged work entirely");
        requireEquals("acceptEdits", PermissionModePolicy.capSubagent("acceptEdits", "bypassPermissions"), "edit parents must reject bypass escalation");
        requireEquals("default", PermissionModePolicy.capSubagent("auto", "default"), "auto parents may let a child require confirmation");
        requireEquals("plan", PermissionModePolicy.capSubagent("plan", "bypassPermissions"), "plan parents can never be escalated by agent metadata");
        requireEquals("dontAsk", PermissionModePolicy.capSubagent("dontAsk", "auto"), "dontAsk parents can never be escalated by agent metadata");
        requireEquals("default", PermissionModePolicy.capSubagent("bypassPermissions", "default"), "bypass parents may intentionally restrict a child");

        System.out.println("PermissionModePolicyTest PASS");
    }
}
