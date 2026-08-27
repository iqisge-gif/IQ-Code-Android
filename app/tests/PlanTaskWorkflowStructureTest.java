import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PlanTaskWorkflowStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String file)throws Exception{return new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String engine=read(root,"src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String exit=read(root,"src/com/termux/app/iqcode/tools/ExitPlanModeTool.java");
        String taskStore=read(root,"src/com/termux/app/iqcode/tasks/TaskStore.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        String progress=read(root,"src/com/iqge/AgentProgressView.java");
        String prompt=read(root,"src/com/termux/app/iqcode/core/SystemPromptBuilder.java");
        require(engine.contains("planApprovalGate.request")&&engine.contains("onPlanApprovalRequest")&&engine.contains("plan_approved"),"ExitPlanMode must wait for explicit approval in the engine");
        require(!exit.contains("c.permissionMode=prev"),"ExitPlanMode tool must not restore write permission directly");
        require(taskStore.contains("FileChannel")&&taskStore.contains("workflow_id")&&taskStore.contains("Snapshot")&&taskStore.contains("subscribe("),"TaskStore must be concurrent, workflow-scoped and observable");
        require(ui.contains("showPlanApprovalDialog")&&ui.contains("AgentProgressView")&&ui.contains("PlanApprovalGate.Decision.APPROVE")&&!ui.contains("APPROVE_ACCEPT_EDITS"),"Android UI must approve plans without offering a model-controlled permission escalation");
        require(engine.contains("PermissionModePolicy.effective")&&!engine.contains("config.permissionMode = \"plan\"")&&!engine.contains("response.permissionMode()"),"plan workflow must be an effective read-only overlay without rewriting the user permission baseline");
        require(progress.contains("activeForm")&&progress.contains("completed")&&progress.contains("另有"),"progress view must render active and completed tasks");
        int activeRow=progress.indexOf("if(\"in_progress\".equals(task.optString(\"status\")))ordered.add(task)");
        int pendingRow=progress.indexOf("if(\"pending\".equals(task.optString(\"status\")))ordered.add(task)");
        int completedRow=progress.indexOf("if(\"completed\".equals(task.optString(\"status\")))ordered.add(task)");
        require(activeRow>=0&&pendingRow>activeRow&&completedRow>pendingRow,"progress rows must order active, pending, then completed tasks");
        require(progress.contains("COMPLETED_HOLD_MS")&&progress.contains("completedHoldUntil")&&progress.contains("hasNewlyCompleted")&&progress.contains("postDelayed")&&progress.contains("completedHoldUntil>System.currentTimeMillis())for"),"completed tasks must remain visible briefly after being marked done and disappear after the hold");
        require(ui.contains("postRuntimeUi(rt,()->{if(agentProgressView!=null)agentProgressView.update(runtimeDisplayStatus(rt),snapshot"),"queued task snapshots must be checked again on the UI thread");
        require(prompt.contains("ExitPlanMode requests user approval")&&prompt.contains("mark it in_progress"),"system prompt must enforce plan and task lifecycle");
        System.out.println("PlanTaskWorkflowStructureTest PASS");
    }
}
