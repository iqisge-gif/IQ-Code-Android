import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PermissionModeLockStructureTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String body(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        require(from >= 0 && to > from, "missing source region: " + start);
        return source.substring(from, to);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String engine = read(root, "src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String policy = read(root, "src/com/termux/app/iqcode/core/PermissionModePolicy.java");
        String ui = read(root, "src/com/iqge/MainActivity.java");

        String configure = body(engine, "public synchronized void configure(SessionConfig config)", "private synchronized void bindTaskStore()");
        require(configure.contains("next.permissionMode = PermissionModePolicy.normalize(next.permissionMode)")
                && configure.contains("if (busy.get())")
                && configure.contains("next.permissionMode = PermissionModePolicy.normalize(current.permissionMode)")
                && !configure.contains("permissionModeBeforePlan"),
            "busy engines must retain a normalized user-owned permission baseline");
        require(engine.contains("getUserPermissionMode()") && engine.contains("PermissionModePolicy.effective(c.permissionMode,c.planWorkflowState)")
                && !engine.contains("config.permissionMode = \"plan\"") && !engine.contains("response.permissionMode()"),
            "model plan transitions must be a non-persistent effective overlay, not a base-mode rewrite");
        require(policy.contains("capSubagent") && policy.contains("isPlanning() || workflowState.isAwaitingApproval()"),
            "permission policy must own plan overlay and non-escalating child rules");

        String send = body(ui, "private void sendPrompt()", "private void updateSlashPalette");
        require(send.contains("boolean steering = engine != null && engine.isBusy()")
                && send.contains("} else {\n                engine.configure(config);")
                && send.contains("engine.sendPrompt(enriched, imageBlocks, userItem.messageId);"),
            "the next non-steering turn must configure the engine from the newly selected global mode");

        String picker = body(ui, "private void showPermissionPicker()", "private void handleRootSlash");
        require(picker.contains("if(isPermissionModeLocked())")
                && picker.contains("本轮任务已锁定为")
                && picker.contains("dontAsk"),
            "the bottom picker and /permissions route must reject mid-turn permission changes");

        String chips = body(ui, "private void updateComposerChips()", "private static int parseTokenCount");
        require(chips.contains("isPermissionModeLocked()")
                && chips.contains("composerModeChip.setEnabled(!locked)")
                && chips.contains("权限模式本轮已锁定"),
            "the composer must display the effective permission mode and disable its chip while busy");
        require(ui.contains("return rt==null?config.permissionMode:rt.engine.getUserPermissionMode()")
                && ui.contains("return rt==null?config.permissionMode:rt.engine.getEffectivePermissionMode()")
                && ui.contains("基础："),
            "UI must distinguish the persisted user baseline from a temporary plan overlay");

        String settings = body(ui, "private void showSettings()", "private View buildThemePreview");
        require(settings.contains("boolean permissionLocked=isPermissionModeLocked()")
                && settings.contains("perm.setEnabled(false)")
                && settings.contains("if(!isPermissionModeLocked())config.permissionMode="),
            "settings must neither enable nor persist a permission-mode change during a locked turn");
        String planDialog = body(ui, "private void showPlanApprovalDialog", "private void handleQuestionRequest");
        require(planDialog.contains("批准只确认计划") && planDialog.contains("PlanApprovalGate.Decision.APPROVE")
                && !planDialog.contains("modeLabels") && !planDialog.contains("APPROVE_AUTO"),
            "plan approval must not expose a permission escalation selector");

        String started = body(ui, "private void handleSessionStarted", "private void handleTextDelta");
        String completed = body(ui, "private void handleTurnComplete", "private void handleError");
        String errored = body(ui, "private void handleError", "private void setComposerBusy");
        require(started.contains("updateComposerChips()") && completed.contains("updateComposerChips()") && errored.contains("updateComposerChips()"),
            "composer lock state must refresh at turn start, completion, and error");

        System.out.println("PermissionModeLockStructureTest PASS");
    }
}
