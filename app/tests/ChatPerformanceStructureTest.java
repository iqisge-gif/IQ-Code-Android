import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ChatPerformanceStructureTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }
    private static String methodBody(String source, String signature, String nextSignature) {
        int start = source.indexOf(signature);
        int end = source.indexOf(nextSignature, start + signature.length());
        if (start < 0 || end < 0) throw new AssertionError("missing method boundary: " + signature);
        return source.substring(start, end);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String ui = read(root, "src/com/iqge/MainActivity.java");
        String terminal = read(root, "src/com/iqge/TermuxTerminalPane.java");
        String motion = read(root, "src/com/iqge/UiMotion.java");
        String manifest = read(root, "AndroidManifest.xml");

        require(motion.contains("now-motionStateCheckedAt<1_000L")
                && motion.contains("cachedAnimationsEnabled")
                && motion.contains("cachedPowerSave"),
            "high-frequency motion must cache system animation and power state instead of querying providers on every touch");
        require(motion.contains("float targetAlpha=active?1f:.78f")
                && !motion.contains("view.setScaleX(.94f)"),
            "navigation selection should use restrained alpha transitions without layout-jarring scale pulses");

        require(ui.contains("queueStreamingDelta(rt,delta)")
                && ui.contains("uiHandler.postDelayed(streamingDeltaFlushRunnable,32L)")
                && ui.contains("flushStreamingDeltasNow(rt)"),
            "SSE text deltas must be coalesced and force-flushed at protocol/UI boundaries");

        String streaming = methodBody(ui, "private void renderStreamingFrame()", "private void scheduleAutoFollowScroll()");
        require(streaming.contains("int targetLength=target.length()")
                && streaming.contains("streamingVisibleChars=targetLength")
                && streaming.contains("target.substring(0,streamingVisibleChars)")
                && !streaming.contains("body.toString()")
                && !streaming.contains("revealBudget")
                && !streaming.contains("RelativeSizeSpan")
                && !streaming.contains("MarkdownRenderer.inlineText"),
            "streaming frames must render every available provider character without an artificial speed limit or Markdown reparse");
        require(ui.contains("finishStreamingWhenRevealed()")
                && ui.contains("if(streamingFinalizePending)streamingView.post(this::finalizeStreamingMessage)"),
            "provider completion must still finalize on the rendering boundary without skipping the final frame");
        String autoFollow=methodBody(ui,"private void scheduleAutoFollowScroll()","private void finalizeStreamingMessage()");
        require(autoFollow.contains("addOnPreDrawListener")
                && autoFollow.contains("scrollChatToEndWithoutFocus(targetScroll,targetMessages,false)")
                && autoFollow.contains("removeOnPreDrawListener")
                && !autoFollow.contains("uiHandler.postDelayed"),
            "continuous streaming follow must align one direct bottom scroll with layout instead of restarting smooth animations");
        String cancelStreaming=methodBody(ui,"private void cancelStreamingRender()","private void renderStreamingFrame()");
        require(ui.contains("cancelAutoFollowScroll();\n        final long treeGeneration = ++chatTreeGeneration")
                && ui.contains("if(e.getActionMasked()==MotionEvent.ACTION_DOWN){chatUserTouching=true;cancelAutoFollowScroll();}")
                && cancelStreaming.contains("streamRenderPosted=false"),
            "stale streaming follow callbacks must be cancelled for tree changes, user scrolls, and stream completion");
        String bottom=methodBody(ui,"private int chatBottomScrollY","private void updateChatFollowState");
        require(bottom.contains("getPaddingTop()")&&bottom.contains("getPaddingBottom()"),
            "chat bottom geometry must account for IME padding");

        String rows = methodBody(ui, "private void refreshVisibleSessionRows()", "private void refreshVisibleSessionRowsFromDiskAsync()");
        require(rows.contains("sessionRowSummaries.get")
                && !rows.contains("SessionStore.summarize"),
            "visible session status refresh must use cached metadata and perform no JSONL disk parsing on the main thread");

        require(ui.contains("appendLiveChunk(tool, chunk, stderr)")
                && ui.contains("}, 300);"),
            "tool output card refreshes must be throttled");

        require(ui.contains("transcriptRenderLimit = 120")
                && ui.contains("addTranscriptWindow()")
                && ui.contains("↑ 加载更早消息"),
            "long transcripts must use progressive rendering without losing access to old messages");

        String activate=methodBody(ui,"private void activateRuntime(SessionRuntime rt, boolean restoreUi)","private String runtimeDisplayStatus");
        String resume=methodBody(ui,"private void resumeSession(SessionStore.SessionSummary summary)","private void showProjectPathDialog");
        require(ui.contains("activeRuntimeGeneration++")
                && ui.contains("composerBusyState=busy")
                && ui.contains("syncComposerForActiveRuntime()")
                && ui.contains("postRuntimeUi(rt"),
            "runtime UI state must survive view recreation and reject stale callbacks");
        require(ui.contains("private void renderChat(FrameLayout host, boolean animate)")
                && ui.contains("final long treeGeneration = ++chatTreeGeneration")
                && ui.contains("if(animate)UiMotion.pageIn(page);")
                && !ui.contains("UiMotion.staggerChildren(chatMessages,12)"),
            "chat tree rebuilds must use one restrained page transition without replaying message animations");
        require(!activate.contains("rebuildTranscriptViews()")
                && resume.contains("renderChat(primaryHost, false)"),
            "session restore must commit one static chat render instead of rebuilding twice");
        require(!ui.contains("UiMotion.staggerChildren(chatMessages, 12);"),
            "transcript refresh must not replay a full stagger animation");
        require(ui.contains("targetScroll!=chatScroll")
                && ui.contains("targetMessages!=chatMessages")
                && ui.contains("treeGeneration!=chatTreeGeneration"),
            "delayed chat scrolling must be bound to the current chat tree");
        require(ui.contains("discardEmptyStreamingMessage()")
                && ui.contains("!text.toString().trim().isEmpty()")
                && ui.contains("removeView(streamingBodyHost)"),
            "blank assistant turns must not leave empty chat cards during streaming or restore");

        require(ui.contains("installKeyboardMotion")
                && ui.contains("SOFT_INPUT_ADJUST_NOTHING")
                && ui.contains("keyboardImeAnimationDepth")
                && ui.contains("pendingKeyboardInset")
                && ui.contains("public void onPrepare(android.view.WindowInsetsAnimation animation)")
                && ui.contains("public void onEnd(android.view.WindowInsetsAnimation animation)")
                && ui.contains("if(keyboardImeAnimationDepth==0)applyKeyboardOffset(pendingKeyboardInset,false)")
                && ui.contains("applyKeyboardOffset(pendingKeyboardInset,false)")
                && ui.contains("private void bindContentKeyboardInsets")
                && ui.contains("keyboardRoot.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener)")
                && ui.contains("showing==keyboardVisible&&!imeAnimationRunning&&offsetDelta<dp(8)")
                && ui.contains("showing==keyboardVisible&&targetOffset==keyboardOffset")
                && manifest.contains("android:windowSoftInputMode=\"adjustNothing\""),
            "IME insets must use one coordinated progress path and ignore idle keyboard-height jitter");
        require(ui.contains("scrollChatToEndWithoutFocus")
                && !ui.contains("fullScroll(View.FOCUS_DOWN)")
                && ui.contains("prompt.setFocusableInTouchMode(true)"),
            "automatic chat scrolling must not steal focus from the composer");
        require(ui.contains("SystemClock.elapsedRealtime")
                && ui.contains("toolElapsedTicker")
                && ui.contains("runningToolLabel(item)")
                && ui.contains("currentToolElapsed(item)"),
            "non-streaming tools must use a local elapsed-time ticker instead of waiting for progress callbacks");
        require(ui.contains("overlayShell.postOnAnimation(overlayFrameUpdate)")
                && !ui.contains("overlayShell.postDelayed(overlayFrameUpdate")
                && !ui.contains("overlayShell.setLayerType(View.LAYER_TYPE_HARDWARE,null)"),
            "floating-window dragging must follow display frames without toggling a full-window hardware layer");
        require(ui.contains("terminalPane.setKeyboardOffset")
                && ui.contains("applyChatKeyboardOffset")
                && terminal.contains("public void setKeyboardOffset")
                && terminal.contains("terminalHost.setPadding(0,0,0,target)")
                && terminal.contains("extraKeysHost.setTranslationY(-target)"),
            "chat and terminal content must reserve the IME area instead of letting the keyboard cover them");
        require(ui.contains("liveToolItems.get(call.id)")
                && ui.contains("tool.elapsedMs=Math.max(tool.elapsedMs,currentToolElapsed(tool))")
                && ui.contains("if(isWorkflowTool(toolName))continue;"),
            "tool results must close the exact call and historical workflow cards must not remain running");
        require(ui.contains("class CollapsedToolActivity")
                && ui.contains("onToolBatchStarted(IQCodeEngine.ToolBatch batch)")
                && ui.contains("handleToolBatchStarted(rt,batch)")
                && ui.contains("工具在返回结果前结束。")
                && ui.contains("collapsedToolsById")
                && ui.contains("addCollapsedToolActivity"),
            "adjacent read/search tool calls must have a dedicated collapsed UI projection");
        String collapsed=methodBody(ui,"private void refreshCollapsedToolActivity(CollapsedToolActivity activity)","private void showToolActions");
        require(collapsed.contains("host.removeViewAt(index)")
                && collapsed.contains("addCollapsedToolActivity(activity,host)")
                && !collapsed.contains("rebuildTranscriptViews()")
                && !collapsed.contains("renderChat("),
            "tool activity updates must refresh only their local group instead of rebuilding chat");
        String history=methodBody(ui,"private void restoreTranscript(File file)","private String relativeSessionTime");
        require(history.contains("activityEntries")
                && history.contains("registerCollapsedToolBatch(nextRestoredToolBatchId--,activityEntries)")
                && history.contains("attachToolToCollapsedActivity(tool)")
                && history.contains("会话记录未包含该工具的结果。"),
            "historical tool sequences must rebuild the same groups and close missing results");
        require(manifest.contains("versionCode=\"2280\"") && manifest.contains("versionName=\"0.21.18\""),
            "release version must be 0.21.18");
        System.out.println("ChatPerformanceStructureTest PASS");
    }
}
