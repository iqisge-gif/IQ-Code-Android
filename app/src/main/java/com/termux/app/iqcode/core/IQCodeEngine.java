package com.termux.app.iqcode.core;

import android.content.Context;
import android.os.PowerManager;

import com.termux.app.iqcode.api.ModelProvider;
import com.termux.app.iqcode.api.ModelProviders;
import com.termux.app.iqcode.agents.AgentDefinition;
import com.termux.app.iqcode.agents.AgentTask;
import com.termux.app.iqcode.agents.SubagentManager;
import com.termux.app.iqcode.model.AssistantTurn;
import com.termux.app.iqcode.model.PlanWorkflowState;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolCall;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.storage.PlanStore;
import com.termux.app.iqcode.storage.SessionStore;
import com.termux.app.iqcode.tasks.TaskStore;
import com.termux.app.iqcode.tools.IQTool;
import com.termux.app.iqcode.tools.ToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java-native IQ Code agent loop with persistent/resumable sessions and model-backed context compaction. */
public final class IQCodeEngine {
    public interface Listener {
        void onSessionStarted(SessionConfig config);
        void onTextDelta(String text);
        void onThinkingDelta(String thinking);
        default void onToolBatchStarted(ToolBatch batch) {}
        void onToolUse(ToolCall call);
        default void onToolBatchCompleted(ToolBatch batch) {}
        default void onToolProgress(ToolCall call, String chunk, boolean stderr, long elapsedMs) {}
        void onToolResult(ToolCall call, ToolExecutionResult result);
        void onPermissionRequest(PermissionGate.PermissionRequest request);
        default void onQuestionRequest(QuestionGate.QuestionRequest request) {}
        default void onPlanStateChanged(PlanWorkflowState state) {}
        default void onPlanApprovalRequest(PlanApprovalGate.ApprovalRequest request) {}
        default void onTasksChanged(TaskStore.Snapshot snapshot) {}
        default void onProjectDirectoryChanged(String projectDirectory) {}
        default void onResponseInterruptedBySteering() {}
        default void onResponseRetry() {}
        default void onQueuedPromptApplied() {}
        void onUsage(long inputTokens, long outputTokens);
        void onStatus(String status);
        void onTurnComplete(String stopReason);
        void onError(String message, Throwable error);
    }

    public static final class ToolBatch {
        public final long batchId;
        public final List<ToolCall> toolCalls;
        public final Set<String> breakBeforeToolIds;

        private ToolBatch(long batchId, List<ToolCall> toolCalls, Set<String> breakBeforeToolIds) {
            this.batchId = batchId;
            this.toolCalls = Collections.unmodifiableList(new ArrayList<>(toolCalls));
            this.breakBeforeToolIds = Collections.unmodifiableSet(new LinkedHashSet<>(breakBeforeToolIds));
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "iq-java-agent"));
    private final Context appContext;
    private final ToolRegistry tools;
    private final boolean subagentMode;
    private final Set<String> allowedTools;
    private final String additionalSystemPrompt;
    private final SubagentManager subagents;
    private final PermissionGate permissionGate = new PermissionGate();
    private final QuestionGate questionGate = new QuestionGate();
    private final PlanApprovalGate planApprovalGate = new PlanApprovalGate();
    private final ModelProvider providerOverride;
    private final JSONArray messages = new JSONArray();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private long nextToolBatchId;
    private final Object messageLock = new Object();
    private final Object steeringLock = new Object();
    private final ArrayDeque<PendingPrompt> steeringQueue = new ArrayDeque<>();
    private final AtomicBoolean steeringToolInterrupt = new AtomicBoolean(false);
    private final AtomicBoolean steeringModelInterrupt = new AtomicBoolean(false);
    private volatile Thread agentThread;
    private volatile boolean executingTool;
    private volatile boolean executingModelRequest;
    private volatile ModelProvider activeProvider;
    private volatile SessionConfig activeTurnConfig;
    private volatile String activeTurnId = "";
    private volatile PowerManager.WakeLock taskWakeLock;

    private static final class PendingPrompt {
        final String prompt;
        final JSONArray extraContent;
        final String messageId;
        final long queuedAt;
        PendingPrompt(String prompt, JSONArray extraContent, String messageId) {
            this.prompt = prompt == null ? "" : prompt;
            this.extraContent = extraContent == null ? new JSONArray() : cloneArray(extraContent);
            this.messageId = messageId == null || messageId.trim().isEmpty() ? java.util.UUID.randomUUID().toString() : messageId;
            this.queuedAt = System.currentTimeMillis();
        }
    }

    private volatile Listener listener;
    private volatile Future<?> active;
    private volatile SessionConfig config;
    private volatile SessionStore sessionStore;
    private volatile TaskStore.Subscription taskSubscription;
    private volatile TaskStore.Snapshot taskSnapshot;
    private volatile String boundTaskKey="";
    private volatile long taskBindingGeneration;
    /** Actual context usage reported by the most recent main-loop API response. */
    private volatile long lastMeasuredContextTokens = -1;
    /** Provider-facing message count represented by {@link #lastMeasuredContextTokens}. */
    private volatile int lastMeasuredMessageCount = -1;
    private volatile int consecutiveAutoCompactFailures;
    private volatile Thread manualCompactionThread;

    public IQCodeEngine(Context context, Listener listener) { this(context, listener, null); }
    public IQCodeEngine(Context context, Listener listener, ModelProvider provider) {
        this(context, listener, provider, false, null, "");
    }

    private IQCodeEngine(Context context, Listener listener, ModelProvider provider, boolean subagentMode, Set<String> allowedTools, String additionalSystemPrompt) {
        this.appContext = context.getApplicationContext();
        this.tools = new ToolRegistry(this.appContext);
        this.listener = listener;
        this.providerOverride = provider;
        this.subagentMode = subagentMode;
        this.allowedTools = allowedTools == null ? null : new LinkedHashSet<>(allowedTools);
        this.additionalSystemPrompt = additionalSystemPrompt == null ? "" : additionalSystemPrompt;
        this.subagents = subagentMode ? null : new SubagentManager(this.appContext, provider, request -> { Listener l=this.listener; if(l!=null)l.onPermissionRequest(request); }, request -> { Listener l=this.listener; if(l!=null)l.onQuestionRequest(request); });
    }

    /** Creates an isolated child engine. Agent/Task spawning is intentionally disabled to match IQ Code's no-nested-subagents rule. */
    public static IQCodeEngine createSubagent(Context context, Listener listener, ModelProvider provider, Set<String> allowedTools, String additionalSystemPrompt) {
        return new IQCodeEngine(context, listener, provider, true, allowedTools, additionalSystemPrompt);
    }

    public List<AgentDefinition> listAgentDefinitions() {
        SessionConfig c = config;
        return subagents == null ? new ArrayList<>() : subagents.definitions(c == null ? null : c.projectDirectory);
    }

    public List<AgentTask> listAgentTasks() { return subagents == null ? new ArrayList<>() : subagents.tasks(); }
    public boolean stopAgentTask(String id) { return subagents != null && subagents.stop(id); }
    public ToolExecutionResult invokeSubagent(JSONObject input) throws Exception {
        if (subagents == null || subagentMode) return ToolExecutionResult.error("Subagents are unavailable in this engine");
        return subagents.execute(config, getEffectivePermissionMode(), new ToolCall("direct-agent-" + System.currentTimeMillis(), "Agent", input));
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public boolean isBusy() { return busy.get(); }
    public boolean isExecutingTool() { return executingTool; }
    public boolean isExecutingModelRequest() { return executingModelRequest; }
    public int pendingSteeringCount() { synchronized (steeringLock) { return steeringQueue.size(); } }

    /** Queue input while the current response or tool finishes, then apply it at the next protocol-safe boundary. */
    public boolean steerPrompt(String prompt, JSONArray extraContent) { return steerPrompt(prompt, extraContent, ""); }

    /** Queue a human prompt while retaining its UI reference for later history mutation. */
    public boolean steerPrompt(String prompt, JSONArray extraContent, String messageId) {
        if ((prompt == null || prompt.trim().isEmpty()) && (extraContent == null || extraContent.length() == 0)) return false;
        if (!busy.get()) return false;
        PendingPrompt pending = new PendingPrompt(prompt, extraContent, messageId);
        synchronized (steeringLock) { steeringQueue.addLast(pending); }
        SessionStore store = sessionStore;
        if (store != null) {
            try {
                store.appendEvent("user_steer_queued", new JSONObject()
                    .put("queued_at", pending.queuedAt)
                    .put("text_length", pending.prompt.length())
                    .put("attachment_blocks", pending.extraContent.length()));
            } catch (Throwable ignored) { }
        }
        Listener l = listener;
        if (l != null) l.onStatus(executingTool
            ? "已预输入，将在当前工具完成后处理…"
            : executingModelRequest
                ? "已预输入，将在当前回复完成后处理…"
                : "已预输入，将在当前步骤完成后处理…");
        return true;
    }

    public boolean steerPrompt(String prompt) { return steerPrompt(prompt, null, ""); }

    public synchronized void configure(SessionConfig config) {
        SessionConfig next = config.copy();
        next.permissionMode = PermissionModePolicy.normalize(next.permissionMode);
        SessionConfig current = this.config;
        if (current != null) {
            if (sessionStore != null) {
                next.workflowId = current.workflowId;
                PlanWorkflowState state = current.planWorkflowState;
                if (state != null && (state.isPlanning() || state.isAwaitingApproval())) {
                    next.planWorkflowState = state.copy();
                }
            }
            if (busy.get()) {
                // A turn may include multiple model and tool steps. Keep the user-owned baseline
                // stable even if the user changes global settings while it is running.
                next.permissionMode = PermissionModePolicy.normalize(current.permissionMode);
            }
        }
        this.config = next;
        bindTaskStore();
        invalidateMeasuredUsage();
    }

    /** Update only the reasoning effort used by the next model request in an active agent loop. */
    public synchronized void updateReasoningEffort(String effort) {
        String nextEffort=effort==null?"auto":effort.trim();
        if(nextEffort.isEmpty())nextEffort="auto";
        if(config!=null)config.effort=nextEffort;
        if(activeTurnConfig!=null)activeTurnConfig.effort=nextEffort;
    }

    private synchronized void ensureSessionStore() {
        if (sessionStore == null && config != null) {
            sessionStore = new SessionStore(config.projectDirectory);
            if (config.workflowId == null || config.workflowId.trim().isEmpty()) config.workflowId = java.util.UUID.randomUUID().toString();
            bindTaskStore();
            appendConfigEvent();
        }
    }

    private synchronized void bindTaskStore() {
        SessionConfig c = config;
        if (c == null || c.workflowId == null || c.workflowId.trim().isEmpty()) return;
        String key=SessionStore.canonicalProject(c.projectDirectory)+"\n"+c.workflowId;
        if(key.equals(boundTaskKey)&&taskSubscription!=null)return;
        TaskStore.Subscription previous = taskSubscription;
        if (previous != null) previous.unsubscribe();
        taskSubscription = null;taskSnapshot=null;boundTaskKey=key;long generation=++taskBindingGeneration;
        TaskStore store = new TaskStore(c.projectDirectory, c.workflowId);
        taskSubscription = TaskStore.subscribe(c.projectDirectory, c.workflowId, snapshot -> {
            if(generation!=taskBindingGeneration||!key.equals(boundTaskKey)||!c.workflowId.equals(snapshot.workflowId))return;
            TaskStore.Snapshot current = taskSnapshot;
            if (current != null && snapshot.version < current.version) return;
            publishTaskSnapshot(snapshot);
        });
        TaskStore.Snapshot initial=store.snapshot();TaskStore.Snapshot current=taskSnapshot;
        if(generation==taskBindingGeneration&&(current==null||initial.version>=current.version))publishTaskSnapshot(initial);
    }

    private void publishTaskSnapshot(TaskStore.Snapshot snapshot){
        taskSnapshot=snapshot;SessionStore sessions=sessionStore;
        if(sessions!=null)try{sessions.appendEvent("task_snapshot",taskSnapshotPayload(snapshot));}catch(Exception ignored){}
        Listener l=listener;if(l!=null)l.onTasksChanged(snapshot);
    }

    private static JSONObject taskSnapshotPayload(TaskStore.Snapshot snapshot) throws Exception {
        JSONArray tasks = new JSONArray();
        for (JSONObject task : snapshot.tasks) tasks.put(new JSONObject(task.toString()));
        return new JSONObject().put("workflow_id", snapshot.workflowId).put("version", snapshot.version).put("tasks", tasks);
    }

    public synchronized File getSessionFile() { return sessionStore == null ? null : sessionStore.getSessionFile(); }

    public void resetConversation() {
        cancel();
        clearSteeringQueue();
        synchronized (messageLock) { while (messages.length() > 0) messages.remove(messages.length() - 1); }
        invalidateMeasuredUsage();
        consecutiveAutoCompactFailures = 0;
        if (config != null) {
            config.workflowId = java.util.UUID.randomUUID().toString();
            config.planWorkflowState = PlanWorkflowState.idle();
            sessionStore = null;
            bindTaskStore();
        }
    }

    public void resumeConversation(File file) throws Exception {
        cancel();
        clearSteeringQueue();
        // A resumed/re-written file is a new provider context. Do not let a cancelled turn's
        // transport or frozen execution snapshot leak into the replacement history.
        activeProvider = null;
        activeTurnConfig = null;
        activeTurnId = "";
        executingTool = false;
        executingModelRequest = false;
        JSONArray restored = SessionStore.loadMessages(file);
        synchronized (messageLock) {
            while (messages.length() > 0) messages.remove(messages.length() - 1);
            for (int i = 0; i < restored.length(); i++) messages.put(restored.getJSONObject(i));
        }
        invalidateMeasuredUsage();
        consecutiveAutoCompactFailures = 0;
        sessionStore = SessionStore.resume(file);
        repairToolHistory("Recovered an incomplete tool call from an earlier interrupted session.", true);
        if (config != null) {
            SessionStore.SessionSummary s = SessionStore.summarize(file);
            if (s.project != null && !s.project.trim().isEmpty() && new File(s.project).isDirectory()) config.projectDirectory = s.project;
            config.workflowId = SessionStore.loadWorkflowId(file);
            config.planWorkflowState = SessionStore.loadPlanState(file);
            if(config.planWorkflowState.isAwaitingApproval()){
                config.planWorkflowState=config.planWorkflowState.keepPlanning("审批在应用中断时失效，请检查计划并重新提交审批。");
                appendPlanEvent("plan_revision_requested",config.planWorkflowState);
            }
            config.renewTransportSession();
            bindTaskStore();
            Listener l = listener;
            if (l != null) l.onPlanStateChanged(config.planWorkflowState.copy());
        }
        appendConfigEvent();
    }

    public int estimateContextTokens() {
        JSONArray snapshot;
        long measured = lastMeasuredContextTokens;
        int measuredMessages = lastMeasuredMessageCount;
        try {
            synchronized (messageLock) { snapshot = new JSONArray(messages.toString()); }
            SessionConfig current = config;
            if (current != null && !current.visionEnabled) VisionMessageFilter.apply(snapshot, false);
        } catch (Exception ignored) {
            return 0;
        }

        // Claude Code's canonical path uses the last API usage, then estimates only the
        // messages appended since that response. This captures the system prompt and tool
        // schemas that a message-only character count misses.
        if (measured >= 0 && measuredMessages >= 0 && measuredMessages <= snapshot.length()) {
            long suffix = ContextCompactor.roughTokens(snapshot, measuredMessages, snapshot.length());
            return (int)Math.min(Integer.MAX_VALUE, Math.max(0L, measured + suffix));
        }

        long estimated = ContextCompactor.roughTokens(snapshot);
        SessionConfig c = config;
        if (c != null) {
            try {
                estimated += ContextCompactor.roughTextTokens(buildSystemPrompt(c));
                estimated += Math.max(0, effectiveToolSchemas().toString().length() / 2L);
            } catch (Exception ignored) { }
        }
        return (int)Math.min(Integer.MAX_VALUE, Math.max(0L, estimated));
    }

    public int contextPercent() {
        SessionConfig c = config;
        int window = c == null ? 128000 : Math.max(16000, c.contextWindowTokens);
        return Math.min(999, (int)Math.round(estimateContextTokens() * 100.0 / window));
    }

    public int autoCompactThresholdTokens() { return ContextCompactor.autoCompactThreshold(config); }
    public int effectiveContextWindowTokens() { return ContextCompactor.effectiveContextWindow(config); }
    public boolean hasMeasuredContextUsage() {
        synchronized (messageLock) {
            return lastMeasuredContextTokens >= 0 && lastMeasuredMessageCount >= 0
                && lastMeasuredMessageCount <= messages.length();
        }
    }

    /** Model-backed semantic compaction. Optional text is appended to the summary instructions. */
    public String compactContext() throws Exception {
        return compactContext("");
    }

    public String compactContext(String customInstructions) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("请等待当前任务结束后再压缩上下文");
        }
        manualCompactionThread = Thread.currentThread();
        try {
            if (listener != null) listener.onStatus("正在调用模型整理上下文…");
            return compactContextSemantic(customInstructions, false);
        } finally {
            manualCompactionThread = null;
            busy.set(false);
            if (listener != null) listener.onTurnComplete("compacted");
        }
    }

    public void sendPrompt(String prompt) { sendPrompt(prompt, null, ""); }

    /** Sends a prompt plus normalized Anthropic-style extra content blocks (for example image/base64 blocks). */
    public void sendPrompt(String prompt, JSONArray extraContent) { sendPrompt(prompt,extraContent,""); }

    public void sendPrompt(String prompt,JSONArray extraContent,String messageId) {
        if ((prompt == null || prompt.trim().isEmpty()) && (extraContent == null || extraContent.length() == 0)) return;
        if (config == null) throw new IllegalStateException("Engine is not configured");
        ensureSessionStore();
        final JSONArray extras = extraContent == null ? new JSONArray() : cloneArray(extraContent);
        if (!busy.compareAndSet(false, true)) {
            if (steerPrompt(prompt, extras)) return;
            throw new IllegalStateException("IQ is already working");
        }
        final SessionConfig turnConfig = config.copy();
        final String turnId = java.util.UUID.randomUUID().toString();
        activeTurnConfig = turnConfig;
        activeTurnId = turnId;
        final String rootMessageId=messageId==null||messageId.trim().isEmpty()?java.util.UUID.randomUUID().toString():messageId;
        active = executor.submit(() -> runAgent(prompt == null ? "" : prompt, extras, turnConfig, turnId, rootMessageId));
    }

    private void runAgent(String prompt, JSONArray extraContent, SessionConfig turnConfig, String turnId, String rootMessageId) {
        agentThread = Thread.currentThread();
        acquireTaskWakeLock();
        try {
            Listener l = listener;
            if (l != null) l.onSessionStarted(turnConfig.copy());
            // Heal legacy/current-session tool chains before the new user turn is appended so any
            // synthesized failure result stays next to the assistant call that originally created it.
            repairToolHistory("Recovered an incomplete tool call before sending the next message.", true);
            ensureSessionStore();
            SessionStore turnStore=sessionStore;
            if(turnStore!=null){turnStore.appendProfileBinding(turnConfig);turnStore.appendTurnConfig(turnConfig,turnId);}
            appendMessage("user", buildUserContent(prompt, extraContent), rootMessageId, turnId, "human");

            maybeAutoCompact();
            drainSteeringPrompts("before-first-model");
            String system = buildSystemPrompt(turnConfig);
            int emptyEndTurnRecoveries = 0;
            for (int turn = 0; turn < turnConfig.maxAgentTurns; turn++) {
                consumeSteeringInterruptIfNeeded();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
                drainSteeringPrompts("before-model");
                if (listener != null) listener.onStatus(turn == 0 ? "Thinking…" : "Continuing after tool results…");

                SessionConfig requestConfig = turnConfig.copy();
                ModelProvider provider = providerOverride != null ? providerOverride : ModelProviders.forConfig(requestConfig);
                repairToolHistory("Recovered an incomplete tool call before an API request.", true);
                final StringBuilder streamedText = new StringBuilder();
                ModelProvider.StreamListener streamListener = new ModelProvider.StreamListener() {
                    @Override public void onTextDelta(String text) {
                        if (text != null) streamedText.append(text);
                        if (listener != null) listener.onTextDelta(text);
                    }
                    @Override public void onThinkingDelta(String thinking) { if (listener != null) listener.onThinkingDelta(thinking); }
                    @Override public void onToolInputDelta(String id, String name, String partialJson) { }
                    @Override public void onUsage(long inputTokens, long outputTokens) { if (listener != null) listener.onUsage(inputTokens, outputTokens); }
                };
                AssistantTurn assistant;
                executingModelRequest = true;
                activeProvider = provider;
                try {
                    assistant = requestModelWithRetry(requestConfig, provider, system, streamListener, streamedText);
                } catch (Throwable requestError) {
                    if (steeringModelInterrupt.getAndSet(false) && hasPendingSteering()) {
                        Thread.interrupted();
                        appendInterruptedAssistantText(streamedText);
                        Listener currentListener = listener;
                        if (currentListener != null) currentListener.onResponseInterruptedBySteering();
                        drainSteeringPrompts("model-interrupted");
                        maybeAutoCompact();
                        continue;
                    }
                    if (Thread.currentThread().isInterrupted() || isInterruptedFailure(requestError)) throw new InterruptedException("Cancelled");
                    throw requestError;
                } finally {
                    activeProvider = null;
                    executingModelRequest = false;
                }

                boolean modelSteeredDuringCompletion = steeringModelInterrupt.getAndSet(false);
                if (modelSteeredDuringCompletion) Thread.interrupted();
                appendMessage("assistant", assistant.content);
                recordMeasuredUsage(assistant.inputTokens, assistant.outputTokens);

                if (assistant.toolCalls.isEmpty()) {
                    if (hasPendingSteering()) {
                        drainSteeringPrompts("after-model");
                        maybeAutoCompact();
                        continue;
                    }
                    if (shouldContinueAfterStopReason(assistant.stopReason)) {
                        appendInternalContinuation(assistant.stopReason);
                        if (listener != null) listener.onStatus("模型输出被截断，正在自动续接…");
                        continue;
                    }
                    if (!hasMeaningfulAssistantContent(assistant.content)) {
                        emptyEndTurnRecoveries++;
                        if (emptyEndTurnRecoveries <= 2) {
                            appendInternalContinuation("empty_end_turn");
                            if (listener != null) listener.onStatus("模型提前空结束，正在自动恢复…");
                            continue;
                        }
                        throw new IllegalStateException("模型连续 3 次空响应，已停止自动重试以避免无休止循环。请检查 API/模型状态后继续。 ");
                    }
                    emptyEndTurnRecoveries = 0;
                    // Close the tiny race where a user correction lands just as a normal end_turn is
                    // being finalized. Re-check once immediately before publishing completion.
                    if (hasPendingSteering()) {
                        drainSteeringPrompts("completion-race");
                        continue;
                    }
                    finishPlanExecutionIfNeeded();
                    if (listener != null) listener.onTurnComplete(assistant.stopReason == null ? "end_turn" : assistant.stopReason);
                    return;
                }

                ToolBatch toolBatch = buildToolBatch(assistant);
                Listener batchListener = listener;
                if (batchListener != null) batchListener.onToolBatchStarted(toolBatch);
                try {
                    // A correction may arrive while the model is streaming. Tool calls produced by the stale
                    // response must still receive matching tool_result blocks, but they must not execute.
                    if (hasPendingSteering()) {
                        appendSkippedToolResults(assistant.toolCalls,
                            "Not executed because the user sent a correction while this response was in flight.");
                        drainSteeringPrompts("after-model");
                        maybeAutoCompact();
                        continue;
                    }

                    JSONArray toolResults = new JSONArray();
                    JSONArray additionalToolContent = new JSONArray();
                    try {
                        for (int index = 0; index < assistant.toolCalls.size(); index++) {
                            ToolCall call = assistant.toolCalls.get(index);
                            consumeSteeringInterruptIfNeeded();
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
                            if (hasPendingSteering()) {
                                appendSkippedToolResults(toolResults, assistant.toolCalls, index,
                                    "Not executed because the user sent a correction before this tool started.");
                                break;
                            }
                            if (listener != null) listener.onToolUse(call);
                            ToolExecutionResult result;
                            executingTool = true;
                            try { result = executeTool(call); }
                            finally { executingTool = false; }
                            persistToolDiff(call, result);
                            if (listener != null) listener.onToolResult(call, result);
                            JSONObject block = new JSONObject().put("type", "tool_result").put("tool_use_id", call.id).put("content", result.content);
                            if (result.isError) block.put("is_error", true);
                            toolResults.put(block);
                            JSONArray extra = result.additionalContent();
                            for (int i = 0; i < extra.length(); i++) additionalToolContent.put(extra.get(i));

                            if (hasPendingSteering()) {
                                appendSkippedToolResults(toolResults, assistant.toolCalls, index + 1,
                                    "Not executed because the user sent a correction while another tool was running.");
                                break;
                            }
                        }
                    } finally {
                        executingTool = false;
                        // Never lose results that already completed just because a later parallel/sequential tool
                        // was cancelled. Tool results stay first because Anthropic requires that ordering.
                        if (toolResults.length() > 0) {
                            for (int i = 0; i < additionalToolContent.length(); i++) toolResults.put(additionalToolContent.get(i));
                            appendMessage("user", toolResults);
                        }
                    }
                    consumeSteeringInterruptIfNeeded();
                    if (hasPendingSteering()) {
                        drainSteeringPrompts("after-tool");
                        maybeAutoCompact();
                        continue;
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
                    maybeAutoCompact();
                } finally {
                    if (batchListener != null) batchListener.onToolBatchCompleted(toolBatch);
                }
            }
            throw new IllegalStateException("Maximum agent turn count reached (" + turnConfig.maxAgentTurns + "). The task did not reach a stable completion state; use /compact or continue the task.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repairToolHistory("Tool execution was cancelled before a result was recorded. Result unavailable; do not assume success.", true);
            if (listener != null) { listener.onStatus("Cancelled"); listener.onTurnComplete("cancelled"); }
        } catch (Throwable e) {
            if(isInterruptedFailure(e)){repairToolHistory("Tool execution was cancelled before a result was recorded. Result unavailable; do not assume success.",true);if(listener!=null){listener.onStatus("Cancelled");listener.onTurnComplete("cancelled");}return;}
            repairToolHistory("Tool execution ended before a result was recorded because the turn failed. Result unavailable; do not assume success.", true);
            if (listener != null) listener.onError(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        } finally {
            executingTool = false;
            executingModelRequest = false;
            ModelProvider provider = activeProvider;
            Thread worker = agentThread;
            activeProvider = null;
            if (provider != null) provider.cancelRequest(worker);
            agentThread = null;
            steeringToolInterrupt.set(false);
            steeringModelInterrupt.set(false);
            Thread.interrupted();
            releaseTaskWakeLock();
            activeTurnConfig=null;activeTurnId="";
            busy.set(false);
        }
    }

    private ToolBatch buildToolBatch(AssistantTurn assistant) {
        Set<String> breakBefore = new LinkedHashSet<>();
        boolean boundary = false;
        for (int i = 0; i < assistant.content.length(); i++) {
            JSONObject block = assistant.content.optJSONObject(i);
            if (block == null) continue;
            if ("tool_use".equals(block.optString("type"))) {
                String id = block.optString("id", "");
                if (boundary && !id.isEmpty()) breakBefore.add(id);
                boundary = false;
            } else if (isToolBatchBoundary(block)) {
                boundary = true;
            }
        }
        return new ToolBatch(++nextToolBatchId, assistant.toolCalls, breakBefore);
    }

    private static boolean isToolBatchBoundary(JSONObject block) {
        String type = block.optString("type", "");
        if (type.isEmpty()) return false;
        if ("text".equals(type) || "output_text".equals(type)) return !block.optString("text", "").trim().isEmpty();
        if ("thinking".equals(type) || "reasoning".equals(type)) {
            String value = block.optString("thinking", block.optString("text", ""));
            return !value.trim().isEmpty();
        }
        return true;
    }

    private AssistantTurn requestModelWithRetry(SessionConfig requestConfig, ModelProvider provider, String system,
                                                ModelProvider.StreamListener streamListener,
                                                StringBuilder streamedText) throws Exception {
        boolean historyRepairAttempted=false;
        boolean transportRetryAttempted=false;
        while(true){
            try {
                return provider.createMessage(requestConfig, system, providerMessages(), effectiveToolSchemas(), streamListener);
            } catch(Exception error){
                if(Thread.currentThread().isInterrupted()||isInterruptedFailure(error))throw error;
                if(!historyRepairAttempted&&isToolHistoryMismatch(error)&&repairToolHistory(
                        "Recovered a malformed tool-call chain rejected by the API.", true)>0){
                    historyRepairAttempted=true;
                    if(listener!=null)listener.onStatus("修复工具调用上下文后重试…");
                    continue;
                }
                if(!transportRetryAttempted&&canReplayModelRequest(requestConfig)&&isRetryableTransportFailure(error)){
                    transportRetryAttempted=true;
                    streamedText.setLength(0);
                    Listener current=listener;
                    if(current!=null){current.onResponseRetry();current.onStatus("连接中断，正在重试模型请求…");}
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Cancelled");
                    try{Thread.sleep(250L);}catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
                    continue;
                }
                throw error;
            }
        }
    }

    private static boolean canReplayModelRequest(SessionConfig c){
        return c==null||!"codex-responses".equals(c.protocol)||!"native".equals(c.toolMode);
    }

    private static boolean isRetryableTransportFailure(Throwable error){
        Throwable current=error;
        while(current!=null){
            if(current instanceof ModelProvider.StreamFailure){
                String code=((ModelProvider.StreamFailure)current).code.toLowerCase(Locale.US);
                if(code.equals("request_timeout")||code.equals("stream_read_error")||code.equals("server_error")||code.equals("service_unavailable")||code.equals("temporarily_unavailable")||code.equals("connection_reset")||code.equals("unexpected_eof"))return true;
            }
            String message=current.getMessage()==null?"":current.getMessage().toLowerCase(Locale.US);
            if(message.contains("request_timeout")||message.contains("stream_read_error")||message.contains("unexpected end of stream")||message.contains("connection reset")||message.contains("connection closed")||message.contains("socket closed")||message.contains("http 408")||message.contains("http 502")||message.contains("http 503")||message.contains("http 504"))return true;
            current=current.getCause();
        }
        return false;
    }

    private JSONArray buildUserContent(String prompt, JSONArray extraContent) throws Exception {
        JSONArray userContent = new JSONArray();
        if (prompt != null && !prompt.isEmpty()) userContent.put(new JSONObject().put("type", "text").put("text", prompt));
        if (extraContent != null) for (int i = 0; i < extraContent.length(); i++) userContent.put(extraContent.get(i));
        return userContent;
    }

    private boolean hasPendingSteering() { synchronized (steeringLock) { return !steeringQueue.isEmpty(); } }

    private int drainSteeringPrompts(String boundary) throws Exception {
        List<PendingPrompt> drained = new ArrayList<>();
        synchronized (steeringLock) { while (!steeringQueue.isEmpty()) drained.add(steeringQueue.removeFirst()); }
        for (PendingPrompt pending : drained) {
            appendMessage("user", buildUserContent(pending.prompt, pending.extraContent), pending.messageId, activeTurnId, "human");
            SessionStore store = sessionStore;
            if (store != null) {
                try { store.appendEvent("user_steer_applied", new JSONObject()
                    .put("queued_at", pending.queuedAt).put("applied_at", System.currentTimeMillis()).put("boundary", boundary)); }
                catch (Throwable ignored) { }
            }
        }
        if (!drained.isEmpty() && listener != null) { listener.onQueuedPromptApplied(); listener.onStatus("已加载 " + drained.size() + " 条预输入，正在继续…"); }
        return drained.size();
    }

    private void appendSkippedToolResults(List<ToolCall> calls, String reason) throws Exception {
        JSONArray results = new JSONArray();
        appendSkippedToolResults(results, calls, 0, reason);
        if (results.length() > 0) appendMessage("user", results);
    }

    private void appendSkippedToolResults(JSONArray target, List<ToolCall> calls, int start, String reason) throws Exception {
        for (int i = Math.max(0, start); i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            ToolExecutionResult result = ToolExecutionResult.error(reason);
            if (listener != null) { listener.onToolUse(call); listener.onToolResult(call, result); }
            target.put(new JSONObject().put("type", "tool_result").put("tool_use_id", call.id)
                .put("content", result.content).put("is_error", true));
        }
    }

    private void appendInternalContinuation(String stopReason) throws Exception {
        String reason = stopReason == null ? "unknown" : stopReason;
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text",
            "<iq_internal_continue>Previous model output stopped because of " + reason + ". Continue the same task from exactly where it stopped. Do not repeat completed work; inspect the latest tool/results and continue until the user's requested task is actually complete.</iq_internal_continue>"));
        appendMessage("user", content);
    }

    private void appendInterruptedAssistantText(StringBuilder streamedText) throws Exception {
        if (streamedText == null) return;
        String partial = streamedText.toString();
        if (partial.trim().isEmpty()) return;
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", partial));
        appendMessage("assistant", content);
    }

    private static boolean hasMeaningfulAssistantContent(JSONArray content) {
        if (content == null || content.length() == 0) return false;
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type", "");
            if ("text".equals(type) && !block.optString("text", "").trim().isEmpty()) return true;
            if ("tool_use".equals(type)) return true;
        }
        return false;
    }

    private void acquireTaskWakeLock() {
        try {
            PowerManager manager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (manager == null) return;
            PowerManager.WakeLock lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IQCode:AgentTask");
            lock.setReferenceCounted(false);
            lock.acquire(6L * 60L * 60L * 1000L);
            taskWakeLock = lock;
        } catch (Throwable ignored) { }
    }

    private void releaseTaskWakeLock() {
        PowerManager.WakeLock lock = taskWakeLock;
        taskWakeLock = null;
        if (lock == null) return;
        try { if (lock.isHeld()) lock.release(); } catch (Throwable ignored) { }
    }

    private static boolean shouldContinueAfterStopReason(String stopReason) {
        String r = stopReason == null ? "" : stopReason.trim().toLowerCase(Locale.US);
        return r.equals("max_tokens") || r.equals("max_output_tokens") || r.equals("length")
            || r.equals("incomplete") || r.equals("output_limit") || r.equals("token_limit");
    }

    private void consumeSteeringInterruptIfNeeded() {
        if (steeringToolInterrupt.getAndSet(false)) Thread.interrupted();
    }

    private void clearSteeringQueue() {
        synchronized (steeringLock) { steeringQueue.clear(); }
        steeringToolInterrupt.set(false);
        steeringModelInterrupt.set(false);
    }

    private static JSONArray cloneArray(JSONArray source) {
        if (source == null) return new JSONArray();
        try { return new JSONArray(source.toString()); } catch (Exception e) { return new JSONArray(); }
    }

    private void recordMeasuredUsage(long inputTokens, long outputTokens) {
        boolean apiMeasured = inputTokens > 0;
        if (apiMeasured) {
            long total = inputTokens + Math.max(0L, outputTokens);
            lastMeasuredContextTokens = Math.max(0L, total);
            synchronized (messageLock) { lastMeasuredMessageCount = messages.length(); }
        }

        // Persist one usage marker immediately after each assistant API message. The UI can
        // therefore restore the historical "current context" footer without confusing it
        // with cumulative billing or with this response's output-token count.
        SessionStore store = sessionStore;
        SessionConfig c = config;
        if (store != null && c != null) {
            try {
                int current = estimateContextTokens();
                store.appendEvent("context_usage", new JSONObject()
                    .put("context_tokens", current)
                    .put("context_window_tokens", Math.max(16_000, c.contextWindowTokens))
                    .put("input_tokens", Math.max(0L, inputTokens))
                    .put("output_tokens", Math.max(0L, outputTokens))
                    .put("source", apiMeasured ? "api" : "estimate")
                    .put("model", c.model == null ? "" : c.model));
            } catch (Exception ignored) { }
        }
    }

    private void persistToolDiff(ToolCall call, ToolExecutionResult result) {
        if (call == null || result == null || result.diff.isEmpty()) return;
        SessionStore store = sessionStore;
        if (store == null) return;
        try {
            store.appendEvent("tool_diff", new JSONObject()
                .put("tool_use_id", call.id == null ? "" : call.id)
                .put("tool_name", call.name == null ? "" : call.name)
                .put("diff", result.diff)
                .put("additions", result.addedLines)
                .put("deletions", result.deletedLines));
        } catch (Exception ignored) { }
    }

    private void invalidateMeasuredUsage() {
        lastMeasuredContextTokens = -1;
        lastMeasuredMessageCount = -1;
    }

    private void maybeAutoCompact() throws Exception {
        SessionConfig c = config;
        if (c == null || !c.autoCompact) return;
        if (consecutiveAutoCompactFailures >= ContextCompactor.MAX_CONSECUTIVE_AUTO_FAILURES) return;
        int threshold = ContextCompactor.autoCompactThreshold(c);
        if (estimateContextTokens() < threshold) return;

        if (listener != null) listener.onStatus("正在调用模型自动压缩上下文…");
        try {
            String result = compactContextSemantic("", true);
            if (result.startsWith("上下文已经足够精简")) {
                // Pressure comes from system/tool context rather than compactable messages.
                // Count it toward the same circuit breaker so every tool round does not retry.
                consecutiveAutoCompactFailures++;
            } else {
                consecutiveAutoCompactFailures = 0;
            }
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception error) {
            consecutiveAutoCompactFailures++;
            SessionStore store = sessionStore;
            if (store != null) {
                try {
                    store.appendEvent("context_compaction_failed", new JSONObject()
                        .put("trigger", "auto")
                        .put("attempt", consecutiveAutoCompactFailures)
                        .put("error", error.getMessage() == null ? error.toString() : error.getMessage()));
                } catch (Exception ignored) { }
            }
            if (listener != null) {
                if (consecutiveAutoCompactFailures >= ContextCompactor.MAX_CONSECUTIVE_AUTO_FAILURES) {
                    listener.onStatus("自动压缩连续失败 3 次，本会话已停止重试；继续当前请求…");
                } else {
                    listener.onStatus("自动压缩失败（" + consecutiveAutoCompactFailures + "/3），继续当前请求…");
                }
            }
        }
    }

    private String compactContextSemantic(String customInstructions, boolean automatic) throws Exception {
        repairToolHistory(automatic
            ? "Recovered an incomplete tool call before automatic context compaction."
            : "Recovered an incomplete tool call before context compaction.", true);

        ContextCompactor.Plan plan;
        synchronized (messageLock) {
            int requestedCut = ContextCompactor.suggestedCut(messages, config, !automatic);
            int cut = safeCompactionCut(requestedCut);
            if (cut <= 0) {
                return "上下文已经足够精简（约 " + estimateContextTokens() + " tokens）";
            }
            plan = ContextCompactor.createPlan(messages, cut);
        }

        SessionConfig summaryConfig = config.copy();
        summaryConfig.maxTokens = Math.max(1_024,
            Math.min(ContextCompactor.SUMMARY_OUTPUT_RESERVE_TOKENS, Math.max(1_024, summaryConfig.maxTokens)));
        summaryConfig.streamThinking = false;
        summaryConfig.reasoningSummary = "off";
        summaryConfig.preserveReasoningState = false;
        summaryConfig.renewTransportSession();

        ModelProvider provider = providerOverride != null ? providerOverride : ModelProviders.forConfig(summaryConfig);
        ModelProvider.StreamListener silentSummaryListener = new ModelProvider.StreamListener() {
            @Override public void onTextDelta(String text) { }
            @Override public void onThinkingDelta(String thinking) { }
            @Override public void onToolInputDelta(String id, String name, String partialJson) { }
            @Override public void onUsage(long inputTokens, long outputTokens) { }
        };

        AssistantTurn summaryTurn = null;
        Exception lastError = null;
        for (int attempt = 0; attempt <= ContextCompactor.MAX_PROMPT_TOO_LONG_RETRIES; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Context compaction cancelled");
            try {
                if (attempt > 0 && listener != null) {
                    listener.onStatus("摘要请求超过模型窗口，正在按 API 回合缩减后重试 " + attempt + "/2…");
                }
                summaryTurn = provider.createMessage(
                    summaryConfig,
                    "You are IQ Code's context compactor. Produce a faithful continuation summary. Respond with text only and never call tools.",
                    ContextCompactor.buildSummaryMessages(plan, customInstructions, attempt),
                    new JSONArray(),
                    silentSummaryListener);
                lastError = null;
                break;
            } catch (Exception error) {
                lastError = error;
                if (!ContextCompactor.isPromptTooLong(error)
                    || attempt >= ContextCompactor.MAX_PROMPT_TOO_LONG_RETRIES) throw error;
            }
        }
        if (lastError != null) throw lastError;
        String summary = ContextCompactor.extractSummary(summaryTurn);
        JSONArray compacted = ContextCompactor.buildCompactedMessages(plan, summary);

        // The model call runs without holding messageLock. Verify that reset/resume did not
        // replace the conversation while the network request was in flight before committing.
        synchronized (messageLock) {
            if (!messages.toString().equals(plan.originalJson)) {
                throw new IllegalStateException("压缩期间会话已发生变化，未覆盖新的上下文");
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1);
            for (int i = 0; i < compacted.length(); i++) messages.put(compacted.getJSONObject(i));
        }

        invalidateMeasuredUsage();
        consecutiveAutoCompactFailures = 0;
        int afterTokens = estimateContextTokens();
        SessionStore store = sessionStore;
        if (store != null) {
            store.appendCompaction(summary, plan.removedMessages, plan.retainedMessages,
                plan.roughBeforeTokens, afterTokens, automatic ? "auto" : "manual", config.model);
            store.appendContextSnapshot(compacted);
        }
        return "模型语义压缩完成：整理 " + plan.removedMessages + " 条较早消息，原样保留 "
            + plan.retainedMessages + " 条近期消息；当前约 " + afterTokens + " tokens";
    }

    /**
     * Move a requested compaction boundary backwards so the retained suffix never begins with
     * a tool_result whose matching assistant tool_use was compacted away. Responses API requires
     * every function_call_output to have a preceding function_call with the same call_id.
     */
    private int safeCompactionCut(int requestedCut) {
        int n = messages.length();
        int cut = Math.max(0, Math.min(requestedCut, n));
        boolean changed;
        do {
            changed = false;
            Set<String> retainedCalls = new LinkedHashSet<>();
            Set<String> retainedResults = new LinkedHashSet<>();
            for (int i = cut; i < n; i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                JSONArray content = message.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject block = content.optJSONObject(j);
                    if (block == null) continue;
                    String type = block.optString("type", "");
                    if ("tool_use".equals(type)) {
                        String id = block.optString("id", "").trim();
                        if (!id.isEmpty()) retainedCalls.add(id);
                    } else if ("tool_result".equals(type)) {
                        String id = block.optString("tool_use_id", "").trim();
                        if (!id.isEmpty()) retainedResults.add(id);
                    }
                }
            }
            for (String resultId : retainedResults) {
                if (retainedCalls.contains(resultId)) continue;
                int matchingCall = findToolUseBefore(resultId, cut);
                if (matchingCall >= 0 && matchingCall < cut) {
                    cut = matchingCall;
                    changed = true;
                    break;
                }
            }
        } while (changed && cut > 0);
        return cut;
    }

    private int findToolUseBefore(String id, int beforeExclusive) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = Math.min(beforeExclusive, messages.length()) - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray content = message.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject block = content.optJSONObject(j);
                if (block != null && "tool_use".equals(block.optString("type", "")) && id.equals(block.optString("id", ""))) return i;
            }
        }
        return -1;
    }

    /**
     * Repair the normalized local history so every protocol-level tool_use has a later tool_result,
     * and an orphan tool_result never remains a protocol block. This is intentionally performed in
     * the engine (persistent self-heal) in addition to the provider's defensive mapping (wire guard).
     * The human transcript is not deleted; only the provider-facing context snapshot is repaired.
     */
    private int repairToolHistory(String missingResultReason, boolean persistSnapshot) {
        try {
            synchronized (messageLock) {
                Set<String> callsSeenInOrder = new LinkedHashSet<>();
                Set<String> paired = new LinkedHashSet<>();
                Set<String> allCalls = new LinkedHashSet<>();
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null) continue;
                    JSONArray content = message.optJSONArray("content");
                    if (content == null) continue;
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject block = content.optJSONObject(j);
                        if (block == null) continue;
                        String type = block.optString("type", "");
                        if ("tool_use".equals(type)) {
                            String id = block.optString("id", "").trim();
                            if (!id.isEmpty()) { allCalls.add(id); callsSeenInOrder.add(id); }
                        } else if ("tool_result".equals(type)) {
                            String id = block.optString("tool_use_id", "").trim();
                            if (!id.isEmpty() && callsSeenInOrder.contains(id)) paired.add(id);
                        }
                    }
                }

                JSONArray rebuilt = new JSONArray();
                Set<String> encounteredCalls = new LinkedHashSet<>();
                Set<String> synthesized = new LinkedHashSet<>();
                int changes = 0;
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject original = messages.optJSONObject(i);
                    if (original == null) continue;
                    String role = original.optString("role", "user");
                    JSONArray content = original.optJSONArray("content");
                    if (content == null) { rebuilt.put(new JSONObject(original.toString())); continue; }
                    JSONArray clean = new JSONArray();
                    JSONArray missing = new JSONArray();
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject block = content.optJSONObject(j);
                        if (block == null) continue;
                        String type = block.optString("type", "");
                        if ("tool_use".equals(type)) {
                            String id = block.optString("id", "").trim();
                            clean.put(new JSONObject(block.toString()));
                            if (!id.isEmpty()) {
                                encounteredCalls.add(id);
                                if (!paired.contains(id) && !synthesized.contains(id)) {
                                    missing.put(new JSONObject()
                                        .put("type", "tool_result")
                                        .put("tool_use_id", id)
                                        .put("content", missingResultReason == null ? "Tool result unavailable." : missingResultReason)
                                        .put("is_error", true));
                                    synthesized.add(id);
                                    changes++;
                                }
                            }
                            continue;
                        }
                        if ("tool_result".equals(type)) {
                            String id = block.optString("tool_use_id", "").trim();
                            if (id.isEmpty() || !encounteredCalls.contains(id)) {
                                // A result before/no call is invalid in Responses. Preserve its useful text as
                                // ordinary context instead of silently deleting it.
                                String label = id.isEmpty() ? "unknown" : id;
                                clean.put(new JSONObject().put("type", "text")
                                    .put("text", "[Recovered orphan tool result " + label + "]\n" + String.valueOf(block.opt("content"))));
                                changes++;
                            } else {
                                clean.put(new JSONObject(block.toString()));
                            }
                            continue;
                        }
                        clean.put(new JSONObject(block.toString()));
                    }
                    if (clean.length() > 0) rebuilt.put(new JSONObject().put("role", role).put("content", clean));
                    if (missing.length() > 0) rebuilt.put(new JSONObject().put("role", "user").put("content", missing));
                }

                if (changes > 0) {
                    while (messages.length() > 0) messages.remove(messages.length() - 1);
                    for (int i = 0; i < rebuilt.length(); i++) messages.put(rebuilt.getJSONObject(i));
                    invalidateMeasuredUsage();
                    if (persistSnapshot && sessionStore != null) sessionStore.appendContextSnapshot(messages);
                }
                return changes;
            }
        } catch (Throwable ignored) {
            // History repair must never make an otherwise usable conversation impossible to open.
            return 0;
        }
    }

    private static boolean isInterruptedFailure(Throwable error){
        Throwable current=error;while(current!=null){if(current instanceof InterruptedException)return true;String message=current.getMessage()==null?"":current.getMessage().toLowerCase(Locale.US);if(message.contains("thread interrupted")||message.equals("interrupted")||message.contains("operation interrupted"))return true;current=current.getCause();}return false;
    }

    private static boolean isToolHistoryMismatch(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return message.contains("no tool output found for function call")
            || message.contains("no tool call found for function call output")
            || message.contains("function_call_output") && message.contains("call_id");
    }

    private ToolExecutionResult executeTool(ToolCall call) {
        try {
            SessionConfig executionConfig=activeTurnConfig==null?config:activeTurnConfig;
            if (call.input.has("_raw_invalid_json")) return ToolExecutionResult.error("Tool input JSON was incomplete or invalid: " + call.input.optString("_raw_invalid_json"));
            if (("Agent".equals(call.name) || "Task".equals(call.name) || "TaskOutput".equals(call.name) || "TaskStop".equals(call.name))) {
                if (subagentMode || subagents == null) return ToolExecutionResult.error("Subagents cannot spawn other subagents");
                return subagents.execute(executionConfig, getEffectivePermissionMode(), call);
            }
            if ("AskUserQuestion".equals(call.name)) {
                if (allowedTools != null && !allowedTools.contains("AskUserQuestion")) return ToolExecutionResult.error("AskUserQuestion is not available to this subagent");
                JSONArray questions=call.input.optJSONArray("questions");
                if(questions==null) questions=new JSONArray().put(call.input);
                JSONObject answers=questionGate.ask(questions, request -> { Listener l=listener; if(l!=null)l.onQuestionRequest(request); });
                return ToolExecutionResult.ok(new JSONObject().put("answers",answers).toString());
            }
            if ("EnterPlanMode".equals(call.name)) return enterPlanMode();
            if ("ExitPlanMode".equals(call.name)) return requestPlanApproval(call);
            if (allowedTools != null && !allowedTools.contains(call.name)) return ToolExecutionResult.error("Tool " + call.name + " is not available to this subagent");
            if ("Root".equals(call.name) && (executionConfig == null || !executionConfig.rootExecutionEnabled)) return ToolExecutionResult.error("Root tool is disabled in IQ Code settings");
            IQTool tool = tools.get(call.name);
            if (tool == null) return ToolExecutionResult.error("Unknown tool: " + call.name);
            String effectivePermissionMode=getEffectivePermissionMode();
            boolean sandboxFullAccess = executionConfig != null && executionConfig.sandboxAgentFullAccess && !"plan".equals(effectivePermissionMode) && (
                "Sandbox".equals(call.name) || ("Debug".equals(call.name) && !"host".equalsIgnoreCase(call.input.optString("scope","sandbox")))
            );
            boolean allowed = sandboxFullAccess || permissionGate.require(effectivePermissionMode, tool, call, request -> { if (listener != null) listener.onPermissionRequest(request); });
            if (!allowed) return ToolExecutionResult.error("Permission denied for " + call.name + " in mode " + effectivePermissionMode);
            String beforeProject=executionConfig.projectDirectory;
            ToolExecutionResult result=tools.execute(executionConfig, call.name, call.input, (chunk, stderr, elapsedMs) -> {
                Listener l = listener;
                if (l != null) l.onToolProgress(call, chunk, stderr, elapsedMs);
            });
            if(beforeProject!=null&&!beforeProject.equals(executionConfig.projectDirectory) && listener!=null) listener.onProjectDirectoryChanged(executionConfig.projectDirectory);
            return result;
        } catch (InterruptedException e) {
            if (steeringToolInterrupt.get()) {
                Thread.interrupted();
                return ToolExecutionResult.error("Tool execution interrupted because the user sent a correction. Re-evaluate the task using the newest user instruction.");
            }
            Thread.currentThread().interrupt(); return ToolExecutionResult.error("Tool execution cancelled");
        } catch (Throwable e) {
            return ToolExecutionResult.error(e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private ToolExecutionResult enterPlanMode() throws Exception {
        if (subagentMode) return ToolExecutionResult.error("Subagents cannot control the parent plan workflow");
        ensureSessionStore();
        PlanWorkflowState current = config.planWorkflowState;
        if (current != null && (current.isPlanning() || current.isAwaitingApproval()))
            return ToolExecutionResult.ok("Already in plan mode. Continue read-only research and submit the complete plan with ExitPlanMode.");
        String previous = PermissionModePolicy.normalize(config.permissionMode);
        PlanStore plans = new PlanStore(config.projectDirectory);
        PlanWorkflowState state = PlanWorkflowState.planning(config.workflowId, previous, plans.getPlanFile(config.workflowId).getAbsolutePath());
        config.planWorkflowState = state;
        appendPlanEvent("plan_entered", state);
        Listener l = listener;
        if (l != null) l.onPlanStateChanged(state.copy());
        return ToolExecutionResult.ok("Entered plan mode. Read-only exploration is allowed. Build a task list, write a complete plan, then call ExitPlanMode for user approval.\nPlan file: " + state.planFile);
    }

    private ToolExecutionResult requestPlanApproval(ToolCall call) throws Exception {
        if (subagentMode) return ToolExecutionResult.error("Subagents cannot request approval for the parent plan workflow");
        PlanWorkflowState current = config.planWorkflowState;
        if (current == null || !current.isPlanning()) return ToolExecutionResult.error("ExitPlanMode requires an active planning workflow");
        String planText = call.input.optString("plan", "").trim();
        if (planText.isEmpty()) return ToolExecutionResult.error("ExitPlanMode requires the complete implementation plan");
        PlanStore plans = new PlanStore(config.projectDirectory);
        File planFile = plans.write(config.workflowId, planText);
        PlanWorkflowState proposed = current.withPlan(planFile.getAbsolutePath(), planText).awaitingApproval();
        config.planWorkflowState = proposed;
        appendPlanEvent("plan_proposed", proposed);
        Listener l = listener;
        if (l != null) l.onPlanStateChanged(proposed.copy());
        if(listener==null)return ToolExecutionResult.error("Plan approval UI is unavailable; remain in plan mode");
        PlanApprovalGate.ApprovalResponse response = planApprovalGate.request(proposed, request -> {
            Listener activeListener = listener;
            if (activeListener != null) activeListener.onPlanApprovalRequest(request);
        });
        PlanWorkflowState live=config.planWorkflowState;
        if(live==null||!live.isAwaitingApproval()||live.revision!=proposed.revision||!live.workflowId.equals(proposed.workflowId))
            return ToolExecutionResult.error("The plan approval request is no longer active");
        if (response.decision == PlanApprovalGate.Decision.KEEP_PLANNING) {
            PlanWorkflowState planning = proposed.keepPlanning(response.feedback);
            config.planWorkflowState = planning;
            appendPlanEvent("plan_revision_requested", planning);
            if (listener != null) listener.onPlanStateChanged(planning.copy());
            return ToolExecutionResult.ok("The user requested more planning. Remain read-only, revise the complete plan, and call ExitPlanMode again.\nFeedback: " + response.feedback);
        }
        if (response.isApproved()) {
            String finalPlan = proposed.planText;
            PlanWorkflowState executing = proposed.executing();
            config.planWorkflowState = executing;
            appendPlanEvent("plan_approved", executing);
            if (listener != null) listener.onPlanStateChanged(executing.copy());
            return ToolExecutionResult.ok("The user approved the plan. Continue implementation using the user's existing permission mode " + PermissionModePolicy.normalize(config.permissionMode) + ".\nPlan file: " + planFile + "\n\n" + finalPlan);
        }
        PlanWorkflowState cancelled = proposed.cancelled(response.feedback);
        config.planWorkflowState = cancelled;
        appendPlanEvent("plan_cancelled", cancelled);
        if (listener != null) listener.onPlanStateChanged(cancelled.copy());
        return ToolExecutionResult.error("The user cancelled the plan workflow." + (response.feedback.isEmpty() ? "" : "\nFeedback: " + response.feedback));
    }

    private void finishPlanExecutionIfNeeded() {
        PlanWorkflowState state=config==null?null:config.planWorkflowState;
        if(state==null||!state.isExecuting())return;
        PlanWorkflowState finished=state.finished();config.planWorkflowState=finished;appendPlanEvent("plan_finished",finished);Listener l=listener;if(l!=null)l.onPlanStateChanged(finished.copy());
    }

    private void appendPlanEvent(String type, PlanWorkflowState state) {
        SessionStore store = sessionStore;
        if (store == null || state == null) return;
        try { store.appendEvent(type, planStatePayload(state)); } catch (Exception ignored) { }
    }

    private static JSONObject planStatePayload(PlanWorkflowState state) throws Exception {
        return new JSONObject().put("status", state.status.name()).put("workflow_id", state.workflowId)
            .put("revision", state.revision).put("previous_permission_mode", state.previousPermissionMode)
            .put("approved_permission_mode", state.approvedPermissionMode).put("plan_file", state.planFile)
            .put("plan_text", state.planText).put("feedback", state.feedback).put("updated_at", state.updatedAt);
    }

    public boolean respondPlanApproval(String requestId, PlanApprovalGate.Decision decision, String feedback) {
        return planApprovalGate.respond(requestId, decision, feedback);
    }

    public ToolExecutionResult enterPlanModeFromUi() {
        try { return enterPlanMode(); } catch (Exception e) { return ToolExecutionResult.error(e.getMessage()==null?e.toString():e.getMessage()); }
    }

    public ToolExecutionResult cancelPlanModeFromUi(String feedback) {
        SessionConfig c=config;PlanWorkflowState state=c==null?null:c.planWorkflowState;
        if(state==null||state.isIdle())return ToolExecutionResult.error("No active plan workflow");
        planApprovalGate.cancelAll();
        PlanWorkflowState cancelled=state.cancelled(feedback==null?"":feedback);c.planWorkflowState=cancelled;appendPlanEvent("plan_cancelled",cancelled);Listener l=listener;if(l!=null)l.onPlanStateChanged(cancelled.copy());return ToolExecutionResult.ok("Plan workflow cancelled");
    }

    public PlanWorkflowState getPlanWorkflowState() {
        SessionConfig c = config;
        PlanWorkflowState state = c == null ? null : c.planWorkflowState;
        return state == null ? PlanWorkflowState.idle() : state.copy();
    }

    public TaskStore.Snapshot getTaskSnapshot() { return taskSnapshot; }
    public String getUserPermissionMode() { SessionConfig c=config; return c==null?PermissionModePolicy.DEFAULT:PermissionModePolicy.normalize(c.permissionMode); }
    public String getEffectivePermissionMode() { SessionConfig c=config; return c==null?PermissionModePolicy.DEFAULT:PermissionModePolicy.effective(c.permissionMode,c.planWorkflowState); }

    private JSONArray effectiveToolSchemas() {
        JSONArray base = tools.apiSchemas();
        JSONArray out = new JSONArray();
        for (int i=0;i<base.length();i++) {
            JSONObject schema=base.optJSONObject(i); if(schema==null)continue;
            String name=schema.optString("name","");
            if (("WebSearch".equals(name) || "WebFetch".equals(name)) && config != null && !config.webSearchEnabled) continue;
            if ("Root".equals(name) && (config == null || !config.rootExecutionEnabled)) continue;
            if (allowedTools == null || allowedTools.contains(name)) out.put(schema);
        }
        if (allowedTools == null || allowedTools.contains("AskUserQuestion")) out.put(askUserQuestionSchema());
        if (!subagentMode && subagents != null) {
            JSONArray agentSchemas=subagents.apiSchemas(); for(int i=0;i<agentSchemas.length();i++)out.put(agentSchemas.optJSONObject(i));
        }
        return out;
    }

    private static JSONObject askUserQuestionSchema() {
        try {
            JSONObject option=new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("label",new JSONObject().put("type","string"))
                .put("description",new JSONObject().put("type","string")))
                .put("required",new JSONArray().put("label"));
            JSONObject q=new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("question",new JSONObject().put("type","string"))
                .put("header",new JSONObject().put("type","string"))
                .put("multiSelect",new JSONObject().put("type","boolean"))
                .put("options",new JSONObject().put("type","array").put("items",option)))
                .put("required",new JSONArray().put("question"));
            JSONObject input=new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("questions",new JSONObject().put("type","array").put("items",q).put("minItems",1)))
                .put("required",new JSONArray().put("questions"));
            return new JSONObject().put("name","AskUserQuestion").put("description","Ask the user one or more clarifying questions and wait for their answers.").put("input_schema",input);
        } catch(Exception e){throw new IllegalStateException(e);}
    }

    public boolean respondQuestion(String requestId, JSONObject answers) { if(questionGate.respond(requestId, answers)) return true; return subagents != null && subagents.respondQuestion(requestId, answers); }

    public boolean respondPermission(String requestId, boolean allow) {
        if (permissionGate.respond(requestId, allow)) return true;
        return subagents != null && subagents.respondPermission(requestId, allow);
    }
    /** Internal permission bridge used by a parent engine to answer a child subagent request. */
    public boolean respondPermissionLocal(String requestId, boolean allow) { return permissionGate.respond(requestId, allow); }
    public void cancel() {
        clearSteeringQueue();
        permissionGate.cancelAll();
        questionGate.cancelAll();
        planApprovalGate.cancelAll();
        Thread worker = agentThread;
        ModelProvider provider = activeProvider;
        Future<?> f = active; if (f != null) f.cancel(true);
        if (worker != null) worker.interrupt();
        if (provider != null) provider.cancelRequest(worker);
        Thread compacting = manualCompactionThread;
        if (compacting != null) compacting.interrupt();
    }
    public synchronized void shutdown() { cancel(); taskBindingGeneration++;boundTaskKey="";if(taskSubscription!=null){taskSubscription.unsubscribe();taskSubscription=null;} executor.shutdownNow(); }

    private void appendMessage(String role, JSONArray content) throws Exception {
        appendMessage(role,content,"",activeTurnId,"internal");
    }

    private void appendMessage(String role,JSONArray content,String messageId,String turnId,String origin)throws Exception{
        JSONObject message = new JSONObject().put("role", role).put("content", content);
        synchronized (messageLock) { messages.put(message); }
        SessionStore store = sessionStore; if (store != null) store.appendMessage(role,content,messageId,turnId,origin);
    }

    private void appendConfigEvent() {
        try {
            SessionStore store = sessionStore; SessionConfig c = config;
            if (store == null || c == null) return;
            store.appendEvent("config", new JSONObject().put("profile_id",c.profileId).put("profile_revision",c.profileRevision).put("credential_revision",c.credentialRevision).put("model", c.model).put("protocol", c.protocol).put("effort", c.effort).put("vision_enabled", c.visionEnabled).put("permission_mode", c.permissionMode).put("sandbox_agent_full_access",c.sandboxAgentFullAccess).put("root_execution_enabled",c.rootExecutionEnabled).put("project", c.projectDirectory).put("workflow_id",c.workflowId));
        } catch (Exception ignored) { }
    }

    private String buildSystemPrompt(SessionConfig promptConfig) {
        return SystemPromptBuilder.build(promptConfig, additionalSystemPrompt);
    }

    private JSONArray snapshotMessages() throws Exception { synchronized (messageLock) { return new JSONArray(messages.toString()); } }
    private JSONArray providerMessages() throws Exception {
        JSONArray snapshot = snapshotMessages();
        SessionConfig c = config;
        return VisionMessageFilter.apply(snapshot, c == null || c.visionEnabled);
    }
}
