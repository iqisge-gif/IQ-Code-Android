package com.iqge;

import com.iqge.background.KeepAliveService;
import com.iqge.sandbox.IQSandboxEngine;
import com.iqge.sandbox.SandboxDashboardActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.app.iqcode.core.IQCodeEngine;
import com.termux.app.iqcode.api.ModelCatalogClient;
import com.termux.app.iqcode.api.ModelDescriptor;
import com.termux.app.iqcode.agents.AgentDefinition;
import com.termux.app.iqcode.agents.AgentTask;
import com.termux.app.iqcode.core.PermissionGate;
import com.termux.app.iqcode.core.PlanApprovalGate;
import com.termux.app.iqcode.core.QuestionGate;
import com.termux.app.iqcode.model.ApiProfile;
import com.termux.app.iqcode.model.PlanWorkflowState;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolCall;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.storage.ApiSettingsStore;
import com.termux.app.iqcode.storage.McpConfigStore;
import com.termux.app.iqcode.storage.SessionStore;
import com.termux.app.iqcode.tasks.TaskStore;
import com.termux.app.iqcode.termux.TermuxShellExecutor;
import com.termux.app.iqcode.tools.AndroidIntentBridge;
import com.termux.app.iqcode.tools.WebSearchTool;
import com.termux.app.iqcode.tools.WebFetchTool;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IQ Code Desktop-inspired Android workspace.
 * Agent/API logic is Java-native; shell/build execution uses the embedded Termux prefix.
 */
public final class MainActivity extends Activity {
    private static volatile java.lang.ref.WeakReference<MainActivity> overlayHost = new java.lang.ref.WeakReference<>(null);
    public static boolean submitOverlayPrompt(String text){MainActivity a=overlayHost.get();if(a==null||text==null||text.trim().isEmpty())return false;a.runOnUiThread(()->{if(a.prompt!=null){a.prompt.setText(text.trim());a.sendPrompt();}});return true;}
    private static final String UI_PREFS = "iqge_ui_preferences";
    private static final String UI_THEME_KEY = "ui_theme";
    private static final String THEME_CLASSIC = "classic";
    private static final String THEME_NEON = "neon-purple";
    private static final String THEME_DAY = "day";
    private static final String THEME_NIGHT = "night";
    private static final String PALETTE_BG_KEY = "palette_background";
    private static final String PALETTE_SURFACE_KEY = "palette_surface";
    private static final String PALETTE_TEXT_KEY = "palette_text";
    private static final String PALETTE_ACCENT_KEY = "palette_accent";
    private static final String PALETTE_GREEN_KEY = "palette_green";
    private static final String PALETTE_RED_KEY = "palette_red";

    // Runtime palette. The original warm dark theme remains the default; the optional purple-blue
    // theme is derived from the user's navy dashboard reference and can be changed without restart.
    private String uiTheme = THEME_NIGHT;
    private boolean neonTheme;
    private boolean lightTheme;
    private int BG = Color.rgb(18, 17, 15);
    private int TOP = Color.rgb(20, 19, 17);
    private int SIDEBAR = Color.rgb(15, 14, 13);
    private int SURFACE = Color.rgb(25, 24, 22);
    private int SURFACE_2 = Color.rgb(31, 29, 27);
    private int SURFACE_3 = Color.rgb(38, 35, 32);
    private int BORDER = Color.rgb(55, 51, 46);
    private int BORDER_SOFT = Color.rgb(43, 40, 36);
    private int TEXT = Color.rgb(240, 236, 229);
    private int MUTED = Color.rgb(164, 157, 147);
    private int MUTED_2 = Color.rgb(119, 113, 105);
    private int ACCENT = Color.rgb(217, 119, 87);
    private int GREEN = Color.rgb(126, 178, 136);
    private int RED = Color.rgb(214, 120, 111);
    private int USER_BG = Color.rgb(48, 44, 40);
    private int TERMINAL_BG = Color.rgb(10, 11, 12);
    private View canvasRoot;
    private WindowManager overlayWindowManager;
    private FrameLayout overlayShell;
    private FrameLayout overlayContent;
    private WindowManager.LayoutParams overlayLayout;
    private boolean overlayFramePosted;
    private boolean overlayScalePending;
    private long overlayLastCommitAt;
    private final Runnable overlayFrameUpdate=()->{
        overlayFramePosted=false;
        if(overlayShell==null||overlayLayout==null||overlayWindowManager==null)return;
        long now=android.os.SystemClock.uptimeMillis();
        if(now-overlayLastCommitAt<24L){deferOverlayFrame();return;}
        try{overlayWindowManager.updateViewLayout(overlayShell,overlayLayout);overlayLastCommitAt=now;if(overlayScalePending){overlayScalePending=false;scaleWorkspaceOverlay();}}catch(Exception ignored){}
    };
    private void deferOverlayFrame(){if(overlayShell!=null&&!overlayFramePosted){overlayFramePosted=true;overlayShell.postOnAnimation(overlayFrameUpdate);}}

    private static final int VIEW_CHAT = 0;
    private static final int VIEW_CHANGES = 1;
    private static final int VIEW_TERMINAL = 2;
    private static final int VIEW_FILES = 3;
    private static final int REQ_PICK_IMAGE = 4102;

    private static final class ChatItem {
        static final int USER = 1, ASSISTANT = 2, TOOL = 3, RESULT = 4, ERROR = 5;
        final int type;
        final String title;
        final StringBuilder body;
        final StringBuilder thinking = new StringBuilder();
        final List<String> processSteps = new ArrayList<>();
        String persistedBody = "";
        final List<Attachment> images = new ArrayList<>();
        final StringBuilder result = new StringBuilder();
        final StringBuilder diff = new StringBuilder();
        final StringBuilder liveOutput = new StringBuilder();
        boolean completed;
        boolean expanded;
        boolean resultError;
        boolean awaitingPermission;
        boolean progressRenderPosted;
        boolean restoredUnfinished;
        boolean liveLastWasStderr;
        int exitCode;
        int diffAddedLines;
        int diffDeletedLines;
        int liveStdoutChars;
        int liveStderrChars;
        long elapsedMs;
        TextView runningView;
        TextView liveOutputView;
        long contextTokens = -1;
        long inputTokens = -1;
        long outputTokens = -1;
        int contextWindowTokens;
        boolean contextApiMeasured;
        long startedAt = android.os.SystemClock.elapsedRealtime();
        View renderedView;
        LinearLayout thinkingHost;
        TextView thinkingLabel;
        TextView thinkingView;
        boolean thinkingExpanded;
        String toolId = "";
        String messageId = "";
        String messageContentHash = "";
        int legacyRowIndex = -1;
        boolean humanMessage;
        ChatItem(int type, String title, String body) {
            this.type = type; this.title = title; this.body = new StringBuilder(body == null ? "" : body);
            this.persistedBody = this.body.toString();
        }
    }

    private static final class CollapsedToolActivity {
        final long batchId;
        final ToolActivityGrouper.GroupPlan plan;
        final List<ChatItem> members = new ArrayList<>();
        boolean batchCompleted;
        boolean expanded;
        LinearLayout renderedView;
        LinearLayout childrenHost;

        CollapsedToolActivity(long batchId, ToolActivityGrouper.GroupPlan plan) {
            this.batchId = batchId;
            this.plan = plan;
        }
    }

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<ChatItem> transcript = new ArrayList<>();
    private final Map<String, ChatItem> liveToolItems = new ConcurrentHashMap<>();
    private final Map<String, CollapsedToolActivity> collapsedToolsById = new java.util.HashMap<>();
    private final Map<Long, List<CollapsedToolActivity>> collapsedToolsByBatch = new java.util.HashMap<>();
    private long nextRestoredToolBatchId = -1L;
    private final StringBuilder terminalBuffer = new StringBuilder();

    private ApiSettingsStore settingsStore;
    private McpConfigStore mcpStore;
    private SessionConfig config;
    private final ModelCatalogClient modelCatalogClient=new ModelCatalogClient();
    private final AtomicLong modelCatalogGeneration=new AtomicLong();
    private Future<?> modelCatalogFuture;
    private RuntimeInstaller runtime;
    private IQCodeEngine engine;
    private final Map<String, SessionRuntime> sessionRuntimes = new ConcurrentHashMap<>();
    private final Map<String, TextView> sessionRowLabels = new ConcurrentHashMap<>();
    private final Map<String, SessionStore.SessionSummary> sessionRowSummaries = new ConcurrentHashMap<>();
    private volatile SessionRuntime activeRuntime;
    private volatile long activeRuntimeGeneration;
    private long chatTreeGeneration;
    private boolean composerBusyState;

    private final class SessionRuntime {
        final IQCodeEngine engine;
        final StringBuffer liveAssistant = new StringBuffer();
        final StringBuilder liveThinking = new StringBuilder();
        volatile File file;
        volatile String phase = "idle";
        volatile String detail = "";
        volatile boolean busy;
        volatile String boundProfileId="";
        volatile String pendingProfileId="";
        volatile SessionConfig nextConfig;
        volatile PlanWorkflowState planState = PlanWorkflowState.idle();
        volatile TaskStore.Snapshot taskSnapshot;
        volatile long lastEventAt = System.currentTimeMillis();
        boolean reasoningRenderPosted;
        long reasoningGeneration;
        SessionRuntime() {
            nextConfig=config==null?new SessionConfig():config.copy();
            boundProfileId=nextConfig.profileId;
            engine = new IQCodeEngine(MainActivity.this, new RuntimeListener(this));
        }
    }

    private final class RuntimeListener implements IQCodeEngine.Listener {
        final SessionRuntime rt;
        RuntimeListener(SessionRuntime rt) { this.rt = rt; }
        @Override public void onSessionStarted(SessionConfig c){ handleSessionStarted(rt,c); }
        @Override public void onTextDelta(String text){ handleTextDelta(rt,text); }
        @Override public void onThinkingDelta(String thinking){ handleThinkingDelta(rt,thinking); }
        @Override public void onToolBatchStarted(IQCodeEngine.ToolBatch batch){ handleToolBatchStarted(rt,batch); }
        @Override public void onToolUse(ToolCall call){ handleToolUse(rt,call); }
        @Override public void onToolBatchCompleted(IQCodeEngine.ToolBatch batch){ handleToolBatchCompleted(rt,batch); }
        @Override public void onToolProgress(ToolCall call,String chunk,boolean stderr,long elapsedMs){ handleToolProgress(rt,call,chunk,stderr,elapsedMs); }
        @Override public void onToolResult(ToolCall call,ToolExecutionResult result){ handleToolResult(rt,call,result); }
        @Override public void onPermissionRequest(PermissionGate.PermissionRequest request){ handlePermissionRequest(rt,request); }
        @Override public void onQuestionRequest(QuestionGate.QuestionRequest request){ handleQuestionRequest(rt,request); }
        @Override public void onPlanStateChanged(PlanWorkflowState state){ handlePlanStateChanged(rt,state); }
        @Override public void onPlanApprovalRequest(PlanApprovalGate.ApprovalRequest request){ handlePlanApprovalRequest(rt,request); }
        @Override public void onTasksChanged(TaskStore.Snapshot snapshot){ handleTasksChanged(rt,snapshot); }
        @Override public void onProjectDirectoryChanged(String projectDirectory){ handleProjectDirectoryChanged(rt,projectDirectory); }
        @Override public void onResponseInterruptedBySteering(){ handleResponseInterruptedBySteering(rt); }
        @Override public void onResponseRetry(){ handleResponseRetry(rt); }
        @Override public void onQueuedPromptApplied(){ handleQueuedPromptApplied(rt); }
        @Override public void onUsage(long inputTokens,long outputTokens){ handleUsage(rt,inputTokens,outputTokens); }
        @Override public void onStatus(String status){ handleStatus(rt,status); }
        @Override public void onTurnComplete(String stopReason){ handleTurnComplete(rt,stopReason); }
        @Override public void onError(String message,Throwable error){ handleError(rt,message,error); }
    }

    private boolean wide;
    private int currentView = VIEW_CHAT;
    private FrameLayout primaryHost;
    private FrameLayout secondaryHost;
    private LinearLayout chatMessages;
    private LinearLayout chatPage;
    private LinearLayout currentConversationHost;
    private View chatImeSpacer;
    private ScrollView chatScroll;
    private EditText prompt;
    private TextView statusText;
    private TextView tokenText;
    private TextView deviceStatusText;
    private boolean deviceStatusReceiverRegistered;
    private final Runnable deviceClockTicker=new Runnable(){@Override public void run(){updateDeviceStatus();uiHandler.postDelayed(this,1000L);}};
    private final BroadcastReceiver deviceBatteryReceiver=new BroadcastReceiver(){@Override public void onReceive(android.content.Context context,Intent intent){updateDeviceStatus(intent);}};
    private TextView changesText;
    private TermuxTerminalPane terminalPane;
    private ListView filesList;
    private TextView filesPath;
    private File browserDir;
    private ChatItem streamingItem;
    private TextView streamingView;
    private LinearLayout streamingBodyHost;
    private final List<TextView> viewTabs = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final UiCanvasStore.ChangeListener canvasChangeListener=(document,preview)->uiHandler.post(()->refreshUiCanvas(document,preview));
    private boolean toolElapsedTickerPosted;
    private final Runnable toolElapsedTicker = this::refreshToolElapsed;
    private final Runnable streamingFrameRunnable = this::renderStreamingFrame;
    private final Runnable streamingDeltaFlushRunnable = this::flushStreamingDeltas;
    private final Object streamingDeltaLock = new Object();
    private final StringBuilder pendingStreamingDelta = new StringBuilder();
    private SessionRuntime pendingStreamingRuntime;
    private long pendingStreamingGeneration;
    private boolean streamingDeltaFlushPosted;
    private boolean chatAutoFollow = true;
    private boolean chatUserTouching;
    private boolean streamRenderPosted;
    private boolean chatRenderPosted;
    private final Runnable chatRenderRunnable = () -> {
        chatRenderPosted = false;
        if (primaryHost != null && chatMessages != null) renderChat(primaryHost, false);
    };
    private long streamingRenderTreeGeneration;
    private int streamingVisibleChars;
    private boolean streamingFinalizePending;
    private boolean autoFollowScrollPosted;
    private ScrollView autoFollowScrollTarget;
    private LinearLayout autoFollowMessagesTarget;
    private long autoFollowScrollTreeGeneration;
    private android.view.ViewTreeObserver.OnPreDrawListener autoFollowScrollListener;
    private int transcriptRenderLimit = 120;
    private int lastDiffAdds = -1, lastDiffDeletes = -1;
    private TextView diffStats;
    private TextView contextChip;
    private TextView composerModeChip;
    private TextView composerEffortChip;
    private TextView composerModelChip;
    private LinearLayout slashPalette;
    private ScrollView slashPaletteScroll;
    private LinearLayout attachmentStrip;
    private boolean suppressPromptWatcher;
    private Dialog mobileSidebarDialog;
    private final List<Attachment> pendingAttachments = new ArrayList<>();
    private boolean rebuildingTranscript;
    private TextView workingIndicator;
    private AgentProgressView agentProgressView;
    private int workingPulseGeneration;
    private String workingStatus = "正在思考…";
    private TextView sendButton;
    private TextView stopButton;
    private View composerHost;
    private LinearLayout chatBottomHost;
    private View keyboardRoot;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;
    private boolean keyboardVisible;
    private int keyboardOffset;
    private int keyboardImeAnimationDepth;
    private int pendingKeyboardInset=-1;
    private FrameLayout filesWorkspaceHost;
    private EditText fileEditor;
    private File fileEditorFile;
    private TextView fileEditorStatus;
    private boolean fileEditorHighlighting;
    private Runnable fileHighlightRunnable;
    private boolean automaticRuntimeInstallRunning;
    private boolean waitingForAllFilesAccess;
    private boolean waitingForOverlayPermission;
    private boolean storagePermissionDialogVisible;
    private boolean androidIntegrationDialogVisible;
    private boolean apiSetupPending;

    private static final String[] EFFORT_UI_VALUES = {"none", "auto", "low", "medium", "high", "max"};
    private static final String[] EFFORT_UI_LABELS = {"关闭", "自动", "低", "中等", "高", "最大"};

    private static final class Attachment {
        static final int TEXT = 1, IMAGE = 2;
        final int kind;
        final String label;
        final String body;
        final String mimeType;
        final byte[] bytes;
        Attachment(String label, String body) { this.kind = TEXT; this.label = label; this.body = body == null ? "" : body; this.mimeType = null; this.bytes = null; }
        Attachment(String label, String mimeType, byte[] bytes) { this.kind = IMAGE; this.label = label; this.body = ""; this.mimeType = mimeType == null ? "image/jpeg" : mimeType; this.bytes = bytes; }
    }

    private static final class SlashCommand {
        final String name, hint;
        SlashCommand(String name, String hint) { this.name = name; this.hint = hint; }
    }

    private static final SlashCommand[] SLASH_COMMANDS = new SlashCommand[]{
        new SlashCommand("/help", "查看全部 IQ Code Android 指令"),
        new SlashCommand("/compact", "模型语义压缩；可追加摘要侧重点"),
        new SlashCommand("/context", "查看或设置上下文窗口，例如 /context 1m"),
        new SlashCommand("/clear", "清空当前对话并开始新会话"),
        new SlashCommand("/new", "创建一个新的已保存会话"),
        new SlashCommand("/resume", "恢复本地已保存的历史会话"),
        new SlashCommand("/model", "切换当前模型"),
        new SlashCommand("/effort", "设置推理强度"),
        new SlashCommand("/permissions", "设置工具调用权限模式"),
        new SlashCommand("/root", "Agent Root：on / off / check"),
        new SlashCommand("/keepalive", "强制后台保活：on / off / check"),
        new SlashCommand("/mcp", "配置和管理 MCP 服务器"),
        new SlashCommand("/web", "联网搜索设置；也可直接输入 /web 搜索词"),
        new SlashCommand("/terminal", "打开内置 Termux 终端"),
        new SlashCommand("/sandbox", "打开 IQ 沙箱；Agent 可安装、运行和调试虚拟 APK"),
        new SlashCommand("/diff", "打开 Claude Code 风格代码修改 Diff"),
        new SlashCommand("/changes", "打开 Git 变更与 Diff"),
        new SlashCommand("/files", "打开项目文件与代码编辑器"),
        new SlashCommand("/doctor", "检查 Termux 运行环境"),
        new SlashCommand("/repair", "修复中断的 apt/dpkg 状态"),
        new SlashCommand("/skills", "为下一条任务附加本地 Skill"),
        new SlashCommand("/status", "查看模型、项目、上下文、运行时和会话状态"),
        new SlashCommand("/stats", "查看当前会话与运行状态"),
        new SlashCommand("/usage", "查看当前上下文使用情况"),
        new SlashCommand("/copy", "复制最近一条 IQ 回复"),
        new SlashCommand("/plan", "进入计划模式；/plan off 退出"),
        new SlashCommand("/config", "打开 IQ Code 设置"),
        new SlashCommand("/canvas", "打开运行时 UI 画布自定义"),
        new SlashCommand("/memory", "打开项目或用户 IQ.md 记忆"),
        new SlashCommand("/init", "让 IQ 初始化或完善项目 IQ.md"),
        new SlashCommand("/tasks", "查看 Android Agent 的任务与会话文件"),
        new SlashCommand("/agents", "管理内置、项目、用户和正在运行的子 Agent"),
        new SlashCommand("/cancel", "停止当前正在执行的 Agent 回合"),
        new SlashCommand("/runtime", "打开内置 Termux 运行环境控制")
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        overlayHost=new java.lang.ref.WeakReference<>(this);
        UiCanvasStore.addChangeListener(canvasChangeListener);
        applyImmersiveFullscreen();
        applyUiPalette(getSharedPreferences(UI_PREFS,MODE_PRIVATE).getString(UI_THEME_KEY,THEME_CLASSIC));
        applyUiCanvasPalette();
        applySystemBarAppearance();

        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        home.mkdirs(); new File(home, "projects").mkdirs();
        settingsStore = new ApiSettingsStore(this);
        mcpStore = new McpConfigStore();
        config = settingsStore.load();
        if (config.forcedKeepAliveEnabled) KeepAliveService.start(this, config.rootExecutionEnabled);
        if (config.projectDirectory == null || config.projectDirectory.trim().isEmpty()) config.projectDirectory = home.getAbsolutePath();
        if (!new File(config.projectDirectory).isDirectory()) config.projectDirectory = home.getAbsolutePath();
        runtime = new RuntimeInstaller(this);
        io.execute(()->{try{runtime.repairIfInstalled();}catch(Throwable ignored){}});
        SessionRuntime initial = createFreshRuntime();
        activateRuntime(initial, false);

        wide = computeWideLayout();
        View app = buildApp();
        canvasRoot = app;
        setContentView(app);
        UiCanvasController.apply(app, UiCanvasStore.load(this));
        installKeyboardMotion();
        bindContentKeyboardInsets(app);
        UiMotion.appIn(app);
        renderChat(primaryHost);
        if (wide) renderChanges(secondaryHost);
        refreshChrome();
        // First-launch onboarding is deliberately posted after the first frame so the workspace
        // appears immediately. Missing Termux runtime is installed automatically and storage
        // access is requested with a Chinese explanation instead of leaving the terminal half-set-up.
        uiHandler.postDelayed(this::runFirstLaunchSetup, 450);
        uiHandler.postDelayed(this::restoreLastSessionAsync, 80);
    }

    private void restoreLastSessionAsync() {
        final SessionRuntime placeholder=activeRuntime;
        final File lastSession=settingsStore.getLastSessionFile();
        if(lastSession==null)return;
        io.execute(()->{
            try{
                File migrated=SessionStore.migrateLegacySessions(lastSession);
                if(migrated==null)migrated=lastSession;
                if(!migrated.isFile())return;
                if(!migrated.getAbsolutePath().equals(lastSession.getAbsolutePath()))settingsStore.setLastSessionFile(migrated);
                final File file=migrated;
                final SessionRuntime restored=createRuntimeForExisting(file);
                ui(()->{
                    if(activeRuntime!=placeholder||placeholder.engine.isBusy()||isFinishing()||isDestroyed()){
                        restored.engine.shutdown();
                        return;
                    }
                    activateRuntime(restored,true);
                    placeholder.engine.shutdown();
                    currentView=VIEW_CHAT;
                    rebuildWorkspaceForTheme();
                });
            }catch(Exception ignored){}
        });
    }

    private void configureFloatingWindow(){
        if(android.os.Build.VERSION.SDK_INT>=26)getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        WindowManager.LayoutParams p=getWindow().getAttributes();p.width=(int)(getResources().getDisplayMetrics().widthPixels*.88f);p.height=(int)(getResources().getDisplayMetrics().heightPixels*.78f);p.gravity=Gravity.RIGHT|Gravity.BOTTOM;p.x=18;p.y=90;getWindow().setAttributes(p);getWindow().setDimAmount(0f);
    }

    private void applyImmersiveFullscreen(){
        // Keep the workspace genuinely edge-to-edge: status/navigation bars are hidden so the
        // system clock cannot create a separate strip above the IQ Code header.
        // Keep the window fixed; the IME inset path below moves only the composer and reserves its area.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        View decor=getWindow().getDecorView();
        int systemUi=View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        decor.setSystemUiVisibility(systemUi);
        if(android.os.Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null)controller.hide(android.view.WindowInsets.Type.statusBars()|android.view.WindowInsets.Type.navigationBars());
        }
    }

    private void applySystemBarAppearance(){
        getWindow().setStatusBarColor(TOP);
        getWindow().setNavigationBarColor(BG);
        if(android.os.Build.VERSION.SDK_INT>=23){
            View decor=getWindow().getDecorView();
            int systemUi=decor.getSystemUiVisibility()&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(android.os.Build.VERSION.SDK_INT>=26)systemUi&=~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if(lightTheme)systemUi|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(lightTheme&&android.os.Build.VERSION.SDK_INT>=26)systemUi|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(systemUi);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus){
            applyImmersiveFullscreen();
            getWindow().getDecorView().postDelayed(this::applyImmersiveFullscreen,180L);
        }
    }

    private void installKeyboardMotion(){
        if(keyboardRoot!=null)return;
        keyboardRoot=getWindow().getDecorView();
        if(android.os.Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            keyboardRoot.setOnApplyWindowInsetsListener((v,insets)->{
                pendingKeyboardInset=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                if(keyboardImeAnimationDepth==0)applyKeyboardOffset(pendingKeyboardInset,false);
                return insets;
            });
            keyboardRoot.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){
                @Override public void onPrepare(android.view.WindowInsetsAnimation animation){
                    super.onPrepare(animation);
                    if((animation.getTypeMask()&android.view.WindowInsets.Type.ime())!=0)keyboardImeAnimationDepth++;
                }
                @Override public android.view.WindowInsets onProgress(android.view.WindowInsets insets,List<android.view.WindowInsetsAnimation> runningAnimations){
                    boolean imeAnimating=false;
                    for(android.view.WindowInsetsAnimation animation:runningAnimations)if((animation.getTypeMask()&android.view.WindowInsets.Type.ime())!=0){imeAnimating=true;break;}
                    if(imeAnimating){
                        pendingKeyboardInset=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                        applyKeyboardOffset(pendingKeyboardInset,false);
                    }
                    return insets;
                }
                @Override public void onEnd(android.view.WindowInsetsAnimation animation){
                    super.onEnd(animation);
                    if((animation.getTypeMask()&android.view.WindowInsets.Type.ime())!=0){
                        keyboardImeAnimationDepth=Math.max(0,keyboardImeAnimationDepth-1);
                        if(keyboardImeAnimationDepth==0&&pendingKeyboardInset>=0)applyKeyboardOffset(pendingKeyboardInset,false);
                    }
                }
            });
        }
        keyboardLayoutListener=()->{
            View target=composerHost;
            if(target==null||target.getParent()==null)return;
            android.graphics.Rect frame=new android.graphics.Rect();
            keyboardRoot.getWindowVisibleDisplayFrame(frame);
            int obscured=Math.max(0,keyboardRoot.getRootView().getHeight()-frame.bottom);
            int detected=pendingKeyboardInset>=dp(120)?pendingKeyboardInset:obscured;
            if(android.os.Build.VERSION.SDK_INT<30||detected>=dp(120)||keyboardVisible)applyKeyboardOffset(detected,true);
        };
        keyboardRoot.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
        if(android.os.Build.VERSION.SDK_INT>=30)keyboardRoot.requestApplyInsets();
    }

    private void bindContentKeyboardInsets(View content){
        if(android.os.Build.VERSION.SDK_INT<30||content==null)return;
        content.setOnApplyWindowInsetsListener((v,insets)->{
            pendingKeyboardInset=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            if(keyboardImeAnimationDepth==0)applyKeyboardOffset(pendingKeyboardInset,false);
            return insets;
        });
        content.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            if(!content.isShown())return;
            android.graphics.Rect frame=new android.graphics.Rect();
            content.getWindowVisibleDisplayFrame(frame);
            int[] location=new int[2];content.getLocationOnScreen(location);
            int contentBottom=location[1]+content.getHeight();
            int visibleObscured=Math.max(0,contentBottom-frame.bottom);
            int detected=pendingKeyboardInset>=dp(120)?pendingKeyboardInset:visibleObscured;
            if(detected>=dp(120)||keyboardVisible)applyKeyboardOffset(detected,true);
        });
        content.requestApplyInsets();
    }

    private void applyKeyboardOffset(int rawOffset,boolean animate){
        boolean showing=rawOffset>=dp(120);
        int targetOffset=showing?rawOffset:0;
        int offsetDelta=Math.abs(targetOffset-keyboardOffset);
        boolean imeAnimationRunning=android.os.Build.VERSION.SDK_INT>=30&&keyboardImeAnimationDepth>0;
        // The window remains ADJUST_NOTHING so the manual composer translation and chat viewport
        // reservation stay in one coordinate system.
        if(showing==keyboardVisible&&!imeAnimationRunning&&offsetDelta<dp(8))return;
        if(showing==keyboardVisible&&targetOffset==keyboardOffset)return;
        keyboardVisible=showing;
        keyboardOffset=targetOffset;
        applyChatKeyboardOffset(targetOffset);
        // The composer is a normal bottom child of the chat page; do not float it over the transcript.
        moveKeyboardView(chatBottomHost,0,animate && !imeAnimationRunning);
        if(terminalPane!=null)terminalPane.setKeyboardOffset(targetOffset,animate);
    }

    private void moveKeyboardView(View target,int offset,boolean animate){
        if(target==null||target.getParent()==null)return;
        target.animate().cancel();
        if(animate)target.animate().translationY(-offset).setDuration(180L).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        else target.setTranslationY(-offset);
    }

    private void applyChatKeyboardOffset(int offset){
        if(chatScroll==null)return;
        if(chatScroll.getPaddingBottom()!=0||chatScroll.getClipToPadding()){
            chatScroll.setClipToPadding(false);
            chatScroll.setPadding(0,0,0,0);
        }
        if(chatPage!=null){
            int bottom=Math.max(0,offset);
            if(chatPage.getPaddingBottom()!=bottom)chatPage.setPadding(chatPage.getPaddingLeft(),chatPage.getPaddingTop(),chatPage.getPaddingRight(),bottom);
        }
        if(chatMessages!=null){
            if(chatImeSpacer!=null){
                ViewGroup.LayoutParams spacerParams=chatImeSpacer.getLayoutParams();
                int spacerHeight=dp(28);
                if(spacerParams!=null&&spacerParams.height!=spacerHeight){spacerParams.height=spacerHeight;chatImeSpacer.setLayoutParams(spacerParams);}
            }else if(chatMessages.getPaddingBottom()!=dp(28)){
                chatMessages.setPadding(chatMessages.getPaddingLeft(),chatMessages.getPaddingTop(),chatMessages.getPaddingRight(),dp(28));
            }
        }
        if(chatAutoFollow)scheduleAutoFollowScroll();
    }

    private boolean computeWideLayout() {
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        return widthDp >= 720f || (landscape && widthDp >= 560f);
    }

    private void applyUiPalette(String requested) {
        lightTheme=THEME_DAY.equals(requested);
        neonTheme=THEME_NEON.equals(requested);
        uiTheme=lightTheme?THEME_DAY:neonTheme?THEME_NEON:THEME_NIGHT;
        if(lightTheme){
            BG=Color.rgb(246,248,252);TOP=Color.WHITE;SIDEBAR=Color.rgb(238,242,248);
            SURFACE=Color.WHITE;SURFACE_2=Color.rgb(250,251,253);SURFACE_3=Color.rgb(238,242,248);
            BORDER=Color.rgb(216,222,232);BORDER_SOFT=Color.rgb(229,233,240);
            TEXT=Color.rgb(29,36,51);MUTED=Color.rgb(93,104,122);MUTED_2=Color.rgb(129,141,160);
            ACCENT=Color.rgb(83,91,214);GREEN=Color.rgb(28,137,91);RED=Color.rgb(194,65,75);
            USER_BG=Color.rgb(231,234,255);TERMINAL_BG=Color.rgb(239,243,248);
        }else if(neonTheme){
            // Deep navy, violet-blue and turquoise status colors sampled from the reference UI.
            BG = Color.rgb(6, 13, 28);TOP = Color.rgb(8, 16, 34);SIDEBAR = Color.rgb(7, 14, 29);
            SURFACE = Color.rgb(13, 23, 42);SURFACE_2 = Color.rgb(19, 30, 51);SURFACE_3 = Color.rgb(33, 40, 68);
            BORDER = Color.rgb(51, 62, 91);BORDER_SOFT = Color.rgb(31, 43, 66);TEXT = Color.rgb(244, 243, 255);
            MUTED = Color.rgb(185, 184, 207);MUTED_2 = Color.rgb(117, 123, 153);ACCENT = Color.rgb(125, 96, 255);
            GREEN = Color.rgb(28, 190, 145);RED = Color.rgb(255, 111, 132);USER_BG = Color.rgb(28, 37, 65);TERMINAL_BG = Color.rgb(4, 9, 20);
        }else{
            BG = Color.rgb(18, 17, 15);TOP = Color.rgb(20, 19, 17);SIDEBAR = Color.rgb(15, 14, 13);
            SURFACE = Color.rgb(25, 24, 22);SURFACE_2 = Color.rgb(31, 29, 27);SURFACE_3 = Color.rgb(38, 35, 32);
            BORDER = Color.rgb(55, 51, 46);BORDER_SOFT = Color.rgb(43, 40, 36);TEXT = Color.rgb(240, 236, 229);
            MUTED = Color.rgb(164, 157, 147);MUTED_2 = Color.rgb(119, 113, 105);ACCENT = Color.rgb(217, 119, 87);
            GREEN = Color.rgb(126, 178, 136);RED = Color.rgb(214, 120, 111);USER_BG = Color.rgb(48, 44, 40);TERMINAL_BG = Color.rgb(10, 11, 12);
        }
        if("custom".equals(requested))applyStoredCustomPalette();
    }

    private void applyStoredCustomPalette() {
        android.content.SharedPreferences prefs=getSharedPreferences(UI_PREFS,MODE_PRIVATE);
        if(!prefs.contains(PALETTE_BG_KEY))return;
        applyCustomPalette(
            prefs.getInt(PALETTE_BG_KEY,BG),prefs.getInt(PALETTE_SURFACE_KEY,SURFACE),
            prefs.getInt(PALETTE_TEXT_KEY,TEXT),prefs.getInt(PALETTE_ACCENT_KEY,ACCENT),
            prefs.getInt(PALETTE_GREEN_KEY,GREEN),prefs.getInt(PALETTE_RED_KEY,RED));
    }

    private void applyCustomPalette(int background,int surface,int textColor,int accent,int green,int red) {
        neonTheme=false;uiTheme="custom";
        BG=background;SURFACE=surface;TEXT=textColor;ACCENT=accent;GREEN=green;RED=red;
        TOP=mixColor(background,surface,.35f);SIDEBAR=mixColor(background,Color.BLACK,.18f);
        SURFACE_2=mixColor(surface,textColor,.06f);SURFACE_3=mixColor(surface,textColor,.12f);
        BORDER=mixColor(surface,textColor,.20f);BORDER_SOFT=mixColor(surface,textColor,.12f);
        MUTED=mixColor(textColor,background,.38f);MUTED_2=mixColor(textColor,background,.58f);
        USER_BG=mixColor(surface,accent,.16f);TERMINAL_BG=mixColor(background,Color.BLACK,.38f);
    }

    private int mixColor(int from,int to,float amount) {
        float t=Math.max(0f,Math.min(1f,amount));
        return Color.rgb(Math.round(Color.red(from)+(Color.red(to)-Color.red(from))*t),Math.round(Color.green(from)+(Color.green(to)-Color.green(from))*t),Math.round(Color.blue(from)+(Color.blue(to)-Color.blue(from))*t));
    }

    private int parsePaletteColor(EditText input) {
        String value=input.getText().toString().trim();
        if(!value.startsWith("#"))value="#"+value;
        if(value.length()!=7)throw new IllegalArgumentException("颜色必须使用 #RRGGBB 格式");
        return Color.parseColor(value);
    }

    private String paletteHex(int color) { return String.format(Locale.US,"#%06X",color&0xFFFFFF); }

    private void toggleDayNightTheme(){
        String next=lightTheme?THEME_NIGHT:THEME_DAY;saveAndApplyUiTheme(next);toast(lightTheme?"已切换为白天模式":"已切换为夜间模式");
    }

    private void saveAndApplyCustomPalette(int background,int surface,int textColor,int accent,int green,int red) {
        getSharedPreferences(UI_PREFS,MODE_PRIVATE).edit().putString(UI_THEME_KEY,"custom")
            .putInt(PALETTE_BG_KEY,background).putInt(PALETTE_SURFACE_KEY,surface)
            .putInt(PALETTE_TEXT_KEY,textColor).putInt(PALETTE_ACCENT_KEY,accent)
            .putInt(PALETTE_GREEN_KEY,green).putInt(PALETTE_RED_KEY,red).apply();
        applyCustomPalette(background,surface,textColor,accent,green,red);
        if(terminalPane!=null)terminalPane.applyTheme(neonTheme,lightTheme);
        rebuildWorkspaceForTheme();
    }

    private void refreshUiCanvas(org.json.JSONObject document, boolean preview) {
        if (isFinishing() || canvasRoot == null) return;
        boolean paletteChanged=document.optJSONObject("palette")!=null&&!isDefaultUiCanvasPalette(document.optJSONObject("palette"));
        applyUiCanvasPalette(document);
        if (paletteChanged) rebuildWorkspaceForTheme();
        if (canvasRoot != null) UiCanvasController.apply(canvasRoot, document);
        if (canvasRoot != null) canvasRoot.invalidate();
        if (preview) toast("UI 画布预览已应用");
    }

    private boolean isDefaultUiCanvasPalette(org.json.JSONObject palette) {
        if(palette==null)return true;
        try {
            org.json.JSONObject defaults=UiCanvasStore.defaults().optJSONObject("palette");
            if(defaults==null)return false;
            for(String key:new String[]{"background","surface","text","muted","accent","green"})
                if(palette.optInt(key,Integer.MIN_VALUE)!=defaults.optInt(key,Integer.MIN_VALUE))return false;
            return true;
        } catch(Throwable ignored) { return false; }
    }

    private void applyUiCanvasPalette(org.json.JSONObject document) {
        try {
            org.json.JSONObject palette=document==null?null:document.optJSONObject("palette");
            if(palette==null||isDefaultUiCanvasPalette(palette))return;
            BG=palette.optInt("background",BG);SURFACE=palette.optInt("surface",SURFACE);TEXT=palette.optInt("text",TEXT);
            MUTED=palette.optInt("muted",MUTED);ACCENT=palette.optInt("accent",ACCENT);GREEN=palette.optInt("green",GREEN);
        } catch(Throwable ignored) {}
    }

    private void applyUiCanvasPalette() {
        try { applyUiCanvasPalette(UiCanvasStore.load(this)); } catch(Throwable ignored) {}
    }

    private void saveAndApplyUiTheme(String selectedTheme) {
        String normalized=THEME_DAY.equals(selectedTheme)?THEME_DAY:THEME_NIGHT;
        if(normalized.equals(uiTheme))return;
        getSharedPreferences(UI_PREFS,MODE_PRIVATE).edit().putString(UI_THEME_KEY,normalized).remove(PALETTE_BG_KEY).remove(PALETTE_SURFACE_KEY).remove(PALETTE_TEXT_KEY).remove(PALETTE_ACCENT_KEY).remove(PALETTE_GREEN_KEY).remove(PALETTE_RED_KEY).apply();
        applyUiPalette(normalized);
        if(terminalPane!=null)terminalPane.applyTheme(neonTheme,lightTheme);
        rebuildWorkspaceForTheme();
    }

    private void rebuildWorkspaceForTheme() {
        int keepView = currentView;
        boolean keepFollow = chatAutoFollow;
        String draft = prompt == null ? "" : prompt.getText().toString();
        if (mobileSidebarDialog != null) { mobileSidebarDialog.dismiss(); mobileSidebarDialog = null; }
        applySystemBarAppearance();
        wide = computeWideLayout();
        viewTabs.clear();
        View app = buildApp();
        canvasRoot = app;
        setContentView(app);
        UiMotion.themeChanged(app);
        UiCanvasController.apply(app, UiCanvasStore.load(this));
        currentView = keepView;
        chatAutoFollow = keepFollow;
        renderChat(primaryHost, false);
        if (prompt != null && !draft.isEmpty()) { prompt.setText(draft); prompt.setSelection(prompt.length()); }
        if (wide) {
            if (keepView == VIEW_TERMINAL) renderTerminal(secondaryHost);
            else if (keepView == VIEW_FILES) renderFiles(secondaryHost);
            else renderChanges(secondaryHost);
        } else if (keepView != VIEW_CHAT) {
            focusWorkspace(keepView);
        }
        refreshChrome();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int keepView = currentView;
        boolean keepFollow = chatAutoFollow;
        String draft = prompt == null ? "" : prompt.getText().toString();
        wide = computeWideLayout();
        viewTabs.clear();
        View app = buildApp();
        canvasRoot = app;
        setContentView(app);
        UiCanvasController.apply(app, UiCanvasStore.load(this));
        UiMotion.appIn(app);
        currentView = keepView;
        renderChat(primaryHost, false);
        if (prompt != null && !draft.isEmpty()) { prompt.setText(draft); prompt.setSelection(prompt.length()); }
        if (wide) {
            if (keepView == VIEW_TERMINAL) renderTerminal(secondaryHost);
            else if (keepView == VIEW_FILES) renderFiles(secondaryHost);
            else renderChanges(secondaryHost);
        } else if (keepView != VIEW_CHAT) {
            focusWorkspace(keepView);
        }
        refreshChrome();
    }

    private View buildApp() {
        LinearLayout root = vbox();
        root.setTag("workspace.root");
        root.setBackgroundColor(BG);
        View globalBar=buildGlobalBar(); globalBar.setTag("workspace.global_bar");
        root.addView(globalBar, lp(-1, dp(neonTheme ? 58 : 52)));

        LinearLayout body = hbox(); body.setTag("workspace.body");
        body.setBackgroundColor(BG);
        if (wide) {
            View sidebar=buildSidebar(); sidebar.setTag("workspace.sidebar");
            body.addView(sidebar, lp(dp(228), -1));
            body.addView(dividerVertical(), lp(1, -1));
        }
        body.addView(buildSessionArea(), new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        UiMotion.bindInteractive(root);
        return root;
    }

    private void updateDeviceStatus(){
        Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery!=null)updateDeviceStatus(battery);
        else if(deviceStatusText!=null)deviceStatusText.setText(new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new java.util.Date()));
    }

    private void updateDeviceStatus(Intent battery){
        if(deviceStatusText==null)return;
        String clock=new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new java.util.Date());
        int level=battery==null?-1:battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1);
        int scale=battery==null?-1:battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,-1);
        int percent=level>=0&&scale>0?Math.round(level*100f/scale):-1;
        int plugged=battery==null?0:battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0);
        boolean charging=plugged!=0||battery!=null&&battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,-1)==android.os.BatteryManager.BATTERY_STATUS_CHARGING;
        String value=clock+(percent>=0?"  "+percent+"%":"")+(charging?"  充电":percent>=0&&percent<=20?"  低电":"");
        deviceStatusText.setText(value);deviceStatusText.setTextColor(charging?GREEN:percent>=0&&percent<=20?RED:MUTED_2);
    }

    private void startDeviceStatus(){
        if(!deviceStatusReceiverRegistered){registerReceiver(deviceBatteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));deviceStatusReceiverRegistered=true;}
        uiHandler.removeCallbacks(deviceClockTicker);updateDeviceStatus();uiHandler.post(deviceClockTicker);
    }

    private void stopDeviceStatus(){uiHandler.removeCallbacks(deviceClockTicker);if(deviceStatusReceiverRegistered){unregisterReceiver(deviceBatteryReceiver);deviceStatusReceiverRegistered=false;}}

    private void toggleFloatingOverlay(){
        if(overlayShell!=null){restoreWorkspaceFromOverlay();return;}
        if(!android.provider.Settings.canDrawOverlays(this)){
            waitingForOverlayPermission=true;
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
            return;
        }
        showWorkspaceOverlay(true);
    }

    public static boolean reopenWorkspaceOverlay(){
        MainActivity a=overlayHost.get();
        if(a==null)return false;
        a.runOnUiThread(()->a.showWorkspaceOverlay(true));
        return true;
    }

    private void showWorkspaceOverlay(boolean portrait){
        if(canvasRoot==null||overlayShell!=null)return;
        stopService(new Intent(this,FloatingOverlayService.class));
        ViewGroup parent=(ViewGroup)canvasRoot.getParent();if(parent!=null)parent.removeView(canvasRoot);
        FrameLayout placeholder=new FrameLayout(this);placeholder.setBackgroundColor(BG);setContentView(placeholder);
        overlayShell=new FrameLayout(this);overlayShell.setBackground(round(Color.rgb(12,12,16),22,BORDER,1));overlayShell.setClipChildren(true);overlayShell.setClipToOutline(true);overlayShell.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        // Build a compact workspace that measures against the actual overlay window bounds.
        wide=false;viewTabs.clear();canvasRoot=buildApp();bindContentKeyboardInsets(canvasRoot);overlayContent=new FrameLayout(this);overlayContent.setTag("floating.workspace.content");overlayContent.addView(canvasRoot,new FrameLayout.LayoutParams(-1,-1));overlayShell.addView(overlayContent,new FrameLayout.LayoutParams(-1,-1));
        renderChat(primaryHost,false);
        LinearLayout controls=hbox();controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(6),0,dp(4),0);controls.setBackground(round(SURFACE_2,16,BORDER,1));
        TextView drag=text("IQ Code",10,TEXT);drag.setGravity(Gravity.CENTER_VERTICAL);controls.addView(drag,new LinearLayout.LayoutParams(0,dp(32),1));
        TextView vertical=iconButton("▯");vertical.setContentDescription("竖屏悬浮布局");vertical.setOnClickListener(v->resizeWorkspaceOverlay(true));controls.addView(vertical,lp(dp(34),dp(32)));
        TextView horizontal=iconButton("▭");horizontal.setContentDescription("横屏悬浮布局");horizontal.setOnClickListener(v->resizeWorkspaceOverlay(false));controls.addView(horizontal,lp(dp(34),dp(32)));
        TextView restore=iconButton("□");restore.setContentDescription("退出悬浮并返回完整界面");restore.setOnClickListener(v->restoreWorkspaceFromOverlay());controls.addView(restore,lp(dp(34),dp(32)));
        TextView minimize=iconButton("−");minimize.setContentDescription("收缩为悬浮球");minimize.setOnClickListener(v->minimizeWorkspaceOverlay());controls.addView(minimize,lp(dp(34),dp(32)));
        overlayShell.addView(controls,new FrameLayout.LayoutParams(-1,dp(34),Gravity.TOP));drag.setOnTouchListener(new OverlayDragTouch());
        TextView resize=iconButton("◢");resize.setContentDescription("拖动调整悬浮窗大小");FrameLayout.LayoutParams resizeLp=new FrameLayout.LayoutParams(dp(42),dp(42),Gravity.RIGHT|Gravity.BOTTOM);overlayShell.addView(resize,resizeLp);resize.setOnTouchListener(new OverlayResizeTouch());
        int screenW=getResources().getDisplayMetrics().widthPixels,screenH=getResources().getDisplayMetrics().heightPixels;
        overlayWindowManager=(WindowManager)getSystemService(WINDOW_SERVICE);int overlayFlags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;overlayLayout=new WindowManager.LayoutParams(portrait?(int)(screenW*.82f):(int)(screenW*.94f),portrait?(int)(screenH*.72f):(int)(screenH*.48f),android.os.Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,overlayFlags,PixelFormat.TRANSLUCENT);overlayLayout.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;overlayLayout.gravity=Gravity.TOP|Gravity.LEFT;overlayLayout.x=dp(12);overlayLayout.y=dp(56);overlayWindowManager.addView(overlayShell,overlayLayout);scaleWorkspaceOverlay();overlayShell.setFocusableInTouchMode(true);overlayShell.requestFocus();uiHandler.postDelayed(()->{if(overlayShell!=null){overlayShell.requestFocus();moveTaskToBack(true);}},250);
    }

    private void resizeWorkspaceOverlay(boolean portrait){
        if(overlayLayout==null)return;
        int screenW=getResources().getDisplayMetrics().widthPixels,screenH=getResources().getDisplayMetrics().heightPixels;
        int keepView=currentView;String draft=prompt==null?"":prompt.getText().toString();boolean keepFollow=chatAutoFollow;
        if(overlayContent!=null){overlayContent.removeView(canvasRoot);wide=!portrait;viewTabs.clear();canvasRoot=buildApp();bindContentKeyboardInsets(canvasRoot);overlayContent.addView(canvasRoot,new FrameLayout.LayoutParams(-1,-1));UiCanvasController.apply(canvasRoot,UiCanvasStore.load(this));currentView=keepView;chatAutoFollow=keepFollow;renderChat(primaryHost,false);if(prompt!=null&&!draft.isEmpty()){prompt.setText(draft);prompt.setSelection(prompt.length());}if(wide){if(keepView==VIEW_TERMINAL)renderTerminal(secondaryHost);else if(keepView==VIEW_FILES)renderFiles(secondaryHost);else renderChanges(secondaryHost);}else if(keepView!=VIEW_CHAT)focusWorkspace(keepView);refreshChrome();}
        overlayLayout.width=portrait?(int)(screenW*.82f):(int)(screenW*.94f);overlayLayout.height=portrait?(int)(screenH*.72f):(int)(screenH*.48f);overlayWindowManager.updateViewLayout(overlayShell,overlayLayout);scaleWorkspaceOverlay();
    }
    private void scaleWorkspaceOverlay(){
        // The overlay owns a compact workspace tree; resizing is handled by normal measurement.
        if(canvasRoot==null)return;
        canvasRoot.setPivotX(0);canvasRoot.setPivotY(0);canvasRoot.setScaleX(1f);canvasRoot.setScaleY(1f);canvasRoot.setTranslationX(0);canvasRoot.setTranslationY(0);
    }
    private void minimizeWorkspaceOverlay(){cleanupOverlayResizeGhost();detachWorkspaceOverlay(false);startService(new Intent(this,FloatingOverlayService.class).putExtra("ball_only",true));}
    private void restoreWorkspaceFromOverlay(){cleanupOverlayResizeGhost();stopService(new Intent(this,FloatingOverlayService.class));detachWorkspaceOverlay(true);}
    private void cleanupOverlayResizeGhost(){
        if(overlayResizeGhost!=null){
            overlayResizeGhost.removeCallbacks(overlayResizeFrameUpdate);overlayResizeFramePosted=false;
            try{overlayWindowManager.removeView(overlayResizeGhost);}catch(Exception ignored){}
        }
        overlayResizeGhost=null;overlayResizeGhostLp=null;
    }
    private void detachWorkspaceOverlay(boolean restore){if(overlayShell==null)return;try{overlayWindowManager.removeView(overlayShell);}catch(Exception ignored){}ViewGroup p=(ViewGroup)canvasRoot.getParent();if(p!=null)p.removeView(canvasRoot);canvasRoot.setScaleX(1);canvasRoot.setScaleY(1);canvasRoot.setTranslationX(0);canvasRoot.setTranslationY(0);canvasRoot.setLayerType(View.LAYER_TYPE_NONE,null);if(overlayShell!=null)overlayShell.removeCallbacks(overlayFrameUpdate);overlayFramePosted=false;overlayScalePending=false;overlayShell=null;overlayContent=null;overlayLayout=null;if(restore){setContentView(canvasRoot);UiCanvasController.apply(canvasRoot,UiCanvasStore.load(this));startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));}}

    private void scheduleOverlayFrame(boolean scale){if(overlayShell==null)return;overlayScalePending|=scale;if(overlayFramePosted)return;overlayFramePosted=true;overlayShell.postOnAnimation(overlayFrameUpdate);}
    private void commitOverlayFrame(boolean scale){if(overlayShell==null)return;overlayShell.removeCallbacks(overlayFrameUpdate);overlayFramePosted=false;overlayScalePending|=scale;overlayLastCommitAt=0L;overlayFrameUpdate.run();}
    private final class OverlayDragTouch implements View.OnTouchListener{
        float sx,sy;int px,py,lastX,lastY;
        public boolean onTouch(View v,MotionEvent e){
            if(overlayLayout==null||overlayShell==null)return false;
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                sx=e.getRawX();sy=e.getRawY();px=overlayLayout.x;py=overlayLayout.y;lastX=px;lastY=py;
                overlayShell.setTranslationX(0);overlayShell.setTranslationY(0);overlayLastCommitAt=0L;return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                int x=px+(int)(e.getRawX()-sx),y=py+(int)(e.getRawY()-sy);
                if(x!=lastX||y!=lastY){lastX=x;lastY=y;overlayLayout.x=x;overlayLayout.y=y;scheduleOverlayFrame(false);}
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){
                overlayLayout.x=lastX;overlayLayout.y=lastY;commitOverlayFrame(false);return true;
            }
            return false;
        }
    }
    private View overlayResizeGhost;
    private WindowManager.LayoutParams overlayResizeGhostLp;
    private boolean overlayResizeFramePosted;
    private final Runnable overlayResizeFrameUpdate=()->{
        overlayResizeFramePosted=false;
        if(overlayResizeGhost==null||overlayResizeGhostLp==null||overlayWindowManager==null)return;
        try{overlayWindowManager.updateViewLayout(overlayResizeGhost,overlayResizeGhostLp);}catch(Exception ignored){}
    };
    private void scheduleOverlayResizeFrame(){
        if(overlayResizeGhost==null||overlayResizeFramePosted)return;
        overlayResizeFramePosted=true;overlayResizeGhost.postOnAnimation(overlayResizeFrameUpdate);
    }
    private final class OverlayResizeTouch implements View.OnTouchListener{
        float sx,sy;int pw,ph;
        public boolean onTouch(View v,MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                sx=e.getRawX();sy=e.getRawY();pw=overlayLayout.width;ph=overlayLayout.height;
                overlayResizeGhost=new FrameLayout(MainActivity.this);
                overlayResizeGhost.setBackground(round(Color.argb(46,255,255,255),22,ACCENT,3));
                int t=android.os.Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
                overlayResizeGhostLp=new WindowManager.LayoutParams(pw,ph,t,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);
                overlayResizeGhostLp.gravity=Gravity.TOP|Gravity.LEFT;overlayResizeGhostLp.x=overlayLayout.x;overlayResizeGhostLp.y=overlayLayout.y;
                overlayWindowManager.addView(overlayResizeGhost,overlayResizeGhostLp);
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                int w=Math.max(dp(300),pw+(int)(e.getRawX()-sx));int h=Math.max(dp(340),ph+(int)(e.getRawY()-sy));
                if(overlayResizeGhostLp!=null){overlayResizeGhostLp.width=w;overlayResizeGhostLp.height=h;scheduleOverlayResizeFrame();}
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){
                int w=Math.max(dp(300),pw+(int)(e.getRawX()-sx));int h=Math.max(dp(340),ph+(int)(e.getRawY()-sy));
                cleanupOverlayResizeGhost();
                overlayLayout.width=w;overlayLayout.height=h;overlayWindowManager.updateViewLayout(overlayShell,overlayLayout);
                return true;
            }
            return false;
        }
    }




    private View buildGlobalBar() {
        LinearLayout bar = hbox();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(10), 0);
        bar.setBackground(themedTopBackground());

        if (!wide) {
            TextView menu = iconButton("☰");
            menu.setOnClickListener(v -> showMobileSidebar());
            bar.addView(menu, lp(dp(38), dp(38)));
        }

        TextView brand = text("IQ Code", 15, TEXT); brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(brand, new LinearLayout.LayoutParams(0, dp(40), 1));

        tokenText = text("", 11, MUTED_2); tokenText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        if (wide) bar.addView(tokenText, lp(dp(170), dp(40)));
        contextChip = text("ctx", 10, MUTED);
        contextChip.setGravity(Gravity.CENTER);
        contextChip.setPadding(dp(8),0,dp(8),0);
        contextChip.setBackground(neonTheme ? accentGradient(18) : round(SURFACE_2, 18, Color.TRANSPARENT, 0));
        contextChip.setOnClickListener(v -> showContextInfo());
        bar.addView(contextChip, lp(dp(wide ? 92 : 78), dp(30)));
        deviceStatusText=text("",10.5f,MUTED_2);deviceStatusText.setGravity(Gravity.CENTER);deviceStatusText.setSingleLine(true);deviceStatusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        deviceStatusText.setVisibility(wide ? View.VISIBLE : View.GONE);
        bar.addView(deviceStatusText,lp(wide?dp(118):0,dp(34)));
        TextView themeButton=iconButton(lightTheme?"☾":"☼");themeButton.setContentDescription(lightTheme?"切换到夜间模式":"切换到白天模式");themeButton.setOnClickListener(v->toggleDayNightTheme());bar.addView(themeButton,lp(dp(40),dp(40)));
        TextView floatButton = iconButton("▣"); floatButton.setContentDescription("开启系统悬浮球"); floatButton.setOnClickListener(v -> toggleFloatingOverlay()); bar.addView(floatButton, lp(dp(40), dp(40)));
        TextView gear = iconButton("⚙"); gear.setContentDescription("打开设置"); gear.setOnClickListener(v -> showSettings());
        bar.addView(gear, lp(dp(40), dp(40)));
        return bar;
    }

    private View buildSidebar() {
        sessionRowLabels.clear();
        sessionRowSummaries.clear();
        LinearLayout side = vbox(); side.setBackground(sidebarBackground()); side.setClipToOutline(true); side.setPadding(dp(10), dp(12), dp(10), dp(10));
        TextView newSession = sidebarAction("＋  新会话", true);
        newSession.setOnClickListener(v -> newSession());
        side.addView(newSession, lp(-1, dp(42)));
        TextView projectHistory=sidebarAction("↻  当前项目上下文",false);projectHistory.setOnClickListener(v->showResumePicker());side.addView(projectHistory,lp(-1,dp(38)));
        TextView projectPath=sidebarAction("⌂  手动添加 / 切换项目路径",false);projectPath.setContentDescription("手动输入项目路径，并打开该路径绑定的上下文历史");projectPath.setOnClickListener(v->showProjectPathDialog());side.addView(projectPath,lp(-1,dp(38)));

        String projectName=new File(config.projectDirectory).getName();if(projectName.isEmpty())projectName="主目录";
        TextView section = text("项目历史 · "+shorten(projectName,22), 10, MUTED_2); section.setTypeface(Typeface.DEFAULT_BOLD); section.setPadding(dp(8), dp(12), 0, dp(6));
        side.addView(section, lp(-1, dp(44)));
        ScrollView sessionScroll = new ScrollView(this);
        sessionScroll.setVerticalScrollBarEnabled(false);
        LinearLayout sessionList = vbox();
        UiMotion.enableLayoutChanges(sessionList);
        List<SessionStore.SessionSummary> saved = SessionStore.listSessions(config.projectDirectory);
        int shown = Math.min(saved.size(), wide ? 9 : 7);
        for (int i = 0; i < shown; i++) sessionList.addView(sessionRow(saved.get(i)), lp(-1, dp(76)));
        if (shown == 0) {
            TextView empty = text("暂无已保存会话", 10.5f, MUTED_2);
            empty.setGravity(Gravity.CENTER_VERTICAL); empty.setPadding(dp(10),0,0,0);
            sessionList.addView(empty, lp(-1, dp(44)));
        }
        UiMotion.staggerChildren(sessionList, 8);
        sessionScroll.addView(sessionList, new ScrollView.LayoutParams(-1,-2));
        side.addView(sessionScroll, lp(-1, dp(wide ? 224 : 246)));

        TextView views = text("工作区", 10, MUTED_2); views.setTypeface(Typeface.DEFAULT_BOLD); views.setPadding(dp(8), dp(20), 0, dp(6));
        side.addView(views, lp(-1, dp(50)));
        side.addView(sidebarAction("▱  变更", false), lp(-1, dp(38)));
        side.getChildAt(side.getChildCount()-1).setOnClickListener(v -> focusWorkspace(VIEW_CHANGES));
        side.addView(sidebarAction("›_  终端", false), lp(-1, dp(38)));
        side.getChildAt(side.getChildCount()-1).setOnClickListener(v -> focusWorkspace(VIEW_TERMINAL));
        side.addView(sidebarAction("⌘  文件", false), lp(-1, dp(38)));
        side.getChildAt(side.getChildCount()-1).setOnClickListener(v -> focusWorkspace(VIEW_FILES));
        TextView roleCard=sidebarAction("▤  自定义角色卡", false);
        roleCard.setContentDescription("编辑发送给模型的自定义角色卡");
        roleCard.setOnClickListener(v -> showRoleCardDialog());
        side.addView(roleCard, lp(-1, dp(38)));
        TextView sandboxRow=sidebarAction("▣  IQ 沙箱", false);
        sandboxRow.setContentDescription("打开集成的 IQ Sandbox 虚拟安卓环境");
        sandboxRow.setOnClickListener(v -> startActivity(new Intent(this, SandboxDashboardActivity.class)));
        side.addView(sandboxRow, lp(-1, dp(38)));

        View spacer = new View(this); side.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView runtimeRow = sidebarAction(runtime.isInstalled() ? "●  运行环境就绪" : "○  准备内置 Termux 环境", false);
        runtimeRow.setTextColor(runtime.isInstalled() ? GREEN : MUTED);
        runtimeRow.setOnClickListener(v -> runtimeDialog()); side.addView(runtimeRow, lp(-1, dp(38)));
        TextView settings = sidebarAction("⚙  设置", false); settings.setOnClickListener(v -> showSettings()); side.addView(settings, lp(-1, dp(38)));
        return side;
    }

    private View sessionRow(SessionStore.SessionSummary summary) {
        File current = engine == null ? null : engine.getSessionFile();
        boolean active = current != null && current.equals(summary.file);
        String key = runtimeKey(summary.file);
        sessionRowSummaries.put(key, summary);
        SessionRuntime rt = sessionRuntimes.get(key);
        LinearLayout row = hbox(); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(3), dp(2), dp(3));
        if (active) row.setBackground(round(SURFACE_2, 11, Color.TRANSPARENT, 0));

        TextView label = text(sessionLabel(summary, active, rt), 14f, rt != null && rt.busy ? ACCENT : TEXT);
        sessionRowLabels.put(key, label);
        label.setGravity(Gravity.CENTER_VERTICAL);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(dp(5),dp(2),dp(4),dp(2));
        label.setOnClickListener(v -> resumeSession(summary));
        View.OnLongClickListener historyActions=v->{showSessionHistoryActions(summary,()->refreshVisibleSessionRowsFromDiskAsync());return true;};
        label.setOnLongClickListener(historyActions);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(70), 1));

        TextView delete = text("×", 18, MUTED_2); delete.setGravity(Gravity.CENTER);
        delete.setContentDescription("删除会话");
        delete.setOnClickListener(v -> confirmDeleteSession(summary));
        row.addView(delete, lp(dp(34), dp(42)));
        row.setOnLongClickListener(historyActions);
        return row;
    }

    private void confirmDeleteSession(SessionStore.SessionSummary summary) {
        if (summary == null) return;
        final Dialog d = newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = vbox(); panel.setPadding(dp(16),dp(14),dp(16),dp(12)); panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView title = text("删除这个会话？",14,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(title,lp(-1,dp(36)));
        TextView body = text(summary.title + "\n" + relativeSessionTime(summary.activityModifiedAt) + " · " + summary.messageCount + " 条消息",10.8f,MUTED); body.setPadding(0,0,0,dp(12)); panel.addView(body,lp(-1,-2));
        LinearLayout actions = hbox(); actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        TextView cancel = text("取消",11,MUTED); cancel.setGravity(Gravity.CENTER); cancel.setOnClickListener(v->d.dismiss()); actions.addView(cancel,lp(dp(76),dp(38)));
        TextView remove = text("删除",11,RED); remove.setTypeface(Typeface.DEFAULT_BOLD); remove.setGravity(Gravity.CENTER); remove.setOnClickListener(v->{
            String key=runtimeKey(summary.file);
            SessionRuntime doomed=sessionRuntimes.get(key);
            if(doomed!=null&&(doomed.busy||doomed.engine.isBusy()||doomed.planState.isAwaitingApproval())){toast("会话仍在执行，请先停止后再删除");return;}
            File active = activeRuntime == null ? null : activeRuntime.file;
            boolean deletingActive = active != null && runtimeKey(active).equals(key);
            if (!SessionStore.deleteSession(summary.file)) { toast("无法删除会话"); return; }
            d.dismiss();
            if(doomed!=null){sessionRuntimes.remove(key,doomed);doomed.engine.shutdown();}
            sessionRowLabels.remove(key);sessionRowSummaries.remove(key);
            if (deletingActive) { settingsStore.setLastSessionFile(null); newSession(); toast("会话已删除"); }
            else { toast("会话已删除"); refreshVisibleSessionRows(); if (mobileSidebarDialog != null) { mobileSidebarDialog.dismiss(); mobileSidebarDialog = null; showMobileSidebar(); } }
        }); actions.addView(remove,lp(dp(76),dp(38)));
        panel.addView(actions,lp(-1,dp(42)));
        d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(390),(int)(getResources().getDisplayMetrics().widthPixels*.88f)),-2);
    }

    private void showSessionHistoryActions(SessionStore.SessionSummary summary,Runnable refreshed){
        if(summary==null)return;
        String[] options=summary.note.isEmpty()?new String[]{"编辑备注","恢复会话"}:new String[]{"编辑备注","清除备注","恢复会话"};
        showChoicePicker("会话操作",options,-1,which->{
            String selected=options[which];
            if("编辑备注".equals(selected))showSessionNoteEditor(summary,refreshed);
            else if("清除备注".equals(selected))updateSessionNote(summary,"",refreshed);
            else resumeSession(summary);
        });
    }

    private void showSessionNoteEditor(SessionStore.SessionSummary summary,Runnable refreshed){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView title=text("会话备注",14,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(title,lp(-1,dp(40)));EditText note=input(summary.note,false);note.setHint("输入备注，最多 500 个字符");note.setGravity(Gravity.TOP|Gravity.START);note.setMinLines(3);note.setMaxLines(7);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(150));panel.addView(note,np);
        LinearLayout actions=hbox();actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView cancel=text("取消",10.5f,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->d.dismiss());actions.addView(cancel,lp(dp(70),dp(40)));TextView save=pill("保存备注",TEXT);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->{d.dismiss();updateSessionNote(summary,note.getText().toString(),refreshed);});actions.addView(save,lp(dp(92),dp(40)));panel.addView(actions,lp(-1,dp(48)));d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(460),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
    }

    private void updateSessionNote(SessionStore.SessionSummary summary,String note,Runnable refreshed){
        io.submit(()->{try{SessionStore.updateSessionMetadata(summary.file,note,summary.titleOverride);SessionStore.SessionSummary fresh=SessionStore.summarize(summary.file);ui(()->{sessionRowSummaries.put(runtimeKey(fresh.file),fresh);refreshVisibleSessionRows();if(refreshed!=null)refreshed.run();toast(note==null||note.trim().isEmpty()?"备注已清除":"备注已保存");});}catch(Exception error){ui(()->toast("备注保存失败："+(error.getMessage()==null?"":error.getMessage())));}});
    }

    private GradientDrawable sidebarBackground(){
        GradientDrawable background=neonTheme?navyGradient(0):round(SIDEBAR,0,Color.TRANSPARENT,0);
        float radius=dp(16);
        background.setCornerRadii(new float[]{0,0,radius,radius,radius,radius,0,0});
        return background;
    }

    private TextView sidebarAction(String label, boolean primary) {
        TextView t = text(label, 12, primary ? TEXT : MUTED);
        t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(10), 0, dp(8), 0);
        if (primary) t.setBackground(neonTheme ? accentGradient(16) : round(SURFACE_2, 16, Color.TRANSPARENT, 0));
        return t;
    }

    private View buildSessionArea() {
        LinearLayout area = vbox(); area.setTag("workspace.session_area"); area.setBackgroundColor(BG);
        if (wide) {
            LinearLayout panes = hbox();
            primaryHost = new FrameLayout(this); primaryHost.setTag("workspace.primary"); primaryHost.setBackgroundColor(BG);
            secondaryHost = new FrameLayout(this); secondaryHost.setTag("workspace.secondary"); secondaryHost.setBackgroundColor(SURFACE);
            panes.addView(primaryHost, new LinearLayout.LayoutParams(0, -1, 56f));
            panes.addView(dividerVertical(), lp(1, -1));
            LinearLayout right = vbox(); right.setBackgroundColor(SURFACE);
            right.addView(buildWorkspaceTabs(false), lp(-1, dp(44)));
            right.addView(dividerHorizontal(), lp(-1,1));
            right.addView(secondaryHost, new LinearLayout.LayoutParams(-1,0,1));
            panes.addView(right, new LinearLayout.LayoutParams(0, -1, 44f));
            area.addView(panes, new LinearLayout.LayoutParams(-1,0,1));
        } else {
            primaryHost = new FrameLayout(this); primaryHost.setTag("workspace.primary"); primaryHost.setBackgroundColor(BG);
            if (neonTheme) {
                area.addView(primaryHost, new LinearLayout.LayoutParams(-1,0,1));
                area.addView(dividerHorizontal(), lp(-1,1));
                area.addView(buildWorkspaceTabs(true), lp(-1, dp(66)));
            } else {
                area.addView(buildWorkspaceTabs(true), lp(-1, dp(44)));
                area.addView(dividerHorizontal(), lp(-1,1));
                area.addView(primaryHost, new LinearLayout.LayoutParams(-1,0,1));
            }
        }
        return area;
    }

    private View buildWorkspaceTabs(boolean includeChat) {
        LinearLayout tabs = hbox(); tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(10), neonTheme && !wide ? dp(6) : dp(5), dp(10), neonTheme && !wide ? dp(6) : dp(5));
        tabs.setBackground(neonTheme ? elevatedCard(0) : round(wide ? SURFACE : BG,0,Color.TRANSPARENT,0));
        viewTabs.clear();
        if (includeChat) addViewTab(tabs, neonTheme ? "⌂\n对话" : "对话", VIEW_CHAT);
        addViewTab(tabs, neonTheme && !wide ? "⇄\n变更" : "变更", VIEW_CHANGES);
        addViewTab(tabs, neonTheme && !wide ? ">_\n终端" : "终端", VIEW_TERMINAL);
        addViewTab(tabs, neonTheme && !wide ? "▣\n文件" : "文件", VIEW_FILES);
        return tabs;
    }

    private void addViewTab(LinearLayout tabs, String name, int view) {
        TextView t = text(name, 11, MUTED); t.setGravity(Gravity.CENTER); t.setTag(view);
        if (neonTheme && !wide) { t.setTextSize(10.5f); t.setLineSpacing(0,1.08f); }
        t.setOnClickListener(v -> focusWorkspace((Integer)v.getTag()));
        LinearLayout.LayoutParams tabParams=new LinearLayout.LayoutParams(0,dp(neonTheme&&!wide?54:34),1);
        if(neonTheme&&!wide)tabParams.setMargins(dp(3),0,dp(3),0);
        viewTabs.add(t); tabs.addView(t,tabParams);
        styleViewTabs();
    }

    private void styleViewTabs() {
        for (TextView t : viewTabs) {
            int v = (Integer)t.getTag(); boolean active = currentView == v;
            t.setTextColor(active ? (neonTheme ? Color.WHITE : ACCENT) : MUTED);
            t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setBackground(active && neonTheme ? accentGradient(15) : round(Color.TRANSPARENT,15,Color.TRANSPARENT,0));
            UiMotion.selection(t, active);
        }
    }

    private void focusWorkspace(int view) {
        currentView = view; styleViewTabs();
        if (wide) {
            if (view == VIEW_CHAT) { focusChat(); return; }
            if (view == VIEW_CHANGES) renderChanges(secondaryHost);
            else if (view == VIEW_TERMINAL) renderTerminal(secondaryHost);
            else if (view == VIEW_FILES) renderFiles(secondaryHost);
        } else {
            if (view == VIEW_CHAT) renderChat(primaryHost);
            else if (view == VIEW_CHANGES) renderChanges(primaryHost);
            else if (view == VIEW_TERMINAL) renderTerminal(primaryHost);
            else if (view == VIEW_FILES) renderFiles(primaryHost);
        }
    }

    private void focusChat() {
        currentView = VIEW_CHAT; styleViewTabs(); renderChat(primaryHost);
    }

    private void scheduleChatRender() {
        if (chatRenderPosted || primaryHost == null || chatMessages == null) return;
        chatRenderPosted = true;
        primaryHost.postOnAnimation(chatRenderRunnable);
    }

    private void renderChat(FrameLayout host) { renderChat(host, true); }

    private void renderChat(FrameLayout host, boolean animate) {
        final int oldY = chatScroll == null ? 0 : chatScroll.getScrollY();
        final boolean follow = chatAutoFollow;
        cancelAutoFollowScroll();
        final long treeGeneration = ++chatTreeGeneration;
        if(agentProgressView!=null)agentProgressView.clear();
        host.removeAllViews();
        LinearLayout page = vbox(); page.setBackgroundColor(BG); chatPage=page;
        chatScroll = new ScrollView(this){
            private float touchDownY;
            private final int touchSlop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            private void trackTouch(MotionEvent e){
                int action=e.getActionMasked();
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){chatUserTouching=true;cancelAutoFollowScroll();}
                if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE){chatAutoFollow=false;cancelAutoFollowScroll();}
                else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){chatUserTouching=false;updateChatFollowState();}
            }
            @Override public boolean onInterceptTouchEvent(MotionEvent e){
                trackTouch(e);
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN)touchDownY=e.getY();
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getY()-touchDownY)>touchSlop)return true;
                return super.onInterceptTouchEvent(e);
            }
            @Override public boolean onTouchEvent(MotionEvent e){trackTouch(e);return super.onTouchEvent(e);}
        };
        chatScroll.setFillViewport(true);
        chatScroll.setClipToPadding(false);
        chatScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        chatScroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { if(chatUserTouching) updateChatFollowState(); });
        chatMessages = vbox(); chatMessages.setPadding(dp(wide?30:16), dp(18), dp(wide?30:16), 0);
        currentConversationHost=null;
        clearCollapsedToolActivityViews();
        streamingBodyHost = null; streamingView = null;
        streamingRenderTreeGeneration = treeGeneration;
        streamingVisibleChars = streamingItem == null ? 0 : streamingItem.body.length();
        agentProgressView=new AgentProgressView(this);agentProgressView.setPalette(TEXT,MUTED,ACCENT,GREEN,SURFACE_3);
        rebuildingTranscript = true;
        addTranscriptWindow();
        attachChatImeSpacer();
        rebuildingTranscript = false;
        SessionRuntime progressRuntime=activeRuntime;
        if(progressRuntime!=null)agentProgressView.update(runtimeDisplayStatus(progressRuntime),progressRuntime.taskSnapshot,progressRuntime.planState,progressRuntime.busy||progressRuntime.engine.isBusy());
        chatScroll.addView(chatMessages); page.addView(chatScroll, new LinearLayout.LayoutParams(-1,0,1));
        chatBottomHost=vbox();
        LinearLayout.LayoutParams progressLp=new LinearLayout.LayoutParams(-1,-2);
        progressLp.setMargins(dp(wide?24:12),dp(2),dp(wide?24:12),dp(2));
        chatBottomHost.addView(agentProgressView,progressLp);
        composerHost=buildComposer();
        chatBottomHost.addView(composerHost,lp(-1,-2));
        page.addView(chatBottomHost,lp(-1,-2));
        moveKeyboardView(chatBottomHost,0,false);
        host.addView(page);
        applyChatKeyboardOffset(keyboardVisible?keyboardOffset:0);
        page.post(()->applyChatKeyboardOffset(keyboardVisible?keyboardOffset:0));
        syncComposerForActiveRuntime();
        if(animate)UiMotion.pageIn(page);
        UiMotion.bindInteractive(composerHost);
        final ScrollView targetScroll=chatScroll;
        targetScroll.post(() -> {
            if(treeGeneration!=chatTreeGeneration||targetScroll!=chatScroll)return;
            if(follow)scrollChatToEndWithoutFocus(targetScroll,chatMessages,false);else targetScroll.scrollTo(0,oldY);
        });
    }

    private void attachChatImeSpacer(){
        if(chatMessages==null)return;
        chatImeSpacer=new View(this);
        chatMessages.addView(chatImeSpacer,lp(-1,0));
    }

    private void addEmptyState(LinearLayout parent) {
        TextView spacer = new TextView(this); parent.addView(spacer, lp(-1, dp(wide ? 118 : 82)));
        TextView title = text("想让 IQ 做什么？", wide ? 23 : 20, TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER); parent.addView(title, lp(-1, dp(42)));
        TextView sub = text("IQ 可以读取项目、编辑文件、运行命令，并在内置 Termux 环境中验证修改。", 12, MUTED);
        sub.setGravity(Gravity.CENTER); sub.setPadding(dp(20),0,dp(20),0); parent.addView(sub, lp(-1, dp(58)));
    }

    private View buildComposer() {
        LinearLayout wrap = vbox(); wrap.setPadding(dp(wide?24:12), dp(6), dp(wide?24:12), dp(12));
        slashPalette = vbox();
        slashPalette.setBackground(elevatedCard(16));
        slashPaletteScroll = new ScrollView(this);
        slashPaletteScroll.setVisibility(View.GONE);
        slashPaletteScroll.setFillViewport(false);
        slashPaletteScroll.setVerticalScrollBarEnabled(false);
        slashPaletteScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        slashPaletteScroll.addView(slashPalette, new ScrollView.LayoutParams(-1,-2));
        LinearLayout.LayoutParams slashLp = new LinearLayout.LayoutParams(-1,dp(210)); slashLp.setMargins(0,0,0,dp(6));
        wrap.addView(slashPaletteScroll, slashLp);

        LinearLayout card = vbox(); card.setPadding(dp(12), dp(8), dp(8), dp(7)); card.setBackground(round(SURFACE_2, 8, BORDER_SOFT, 1));
        attachmentStrip = vbox();
        UiMotion.enableLayoutChanges(attachmentStrip);
        rebuildAttachmentStrip();
        card.addView(attachmentStrip, lp(-1,-2));
        prompt = new EditText(this); prompt.setTextColor(TEXT); prompt.setHintTextColor(MUTED_2); prompt.setHint("描述任务或向 IQ 提问");
        prompt.setEnabled(true); prompt.setFocusable(true); prompt.setFocusableInTouchMode(true); prompt.setVisibility(View.VISIBLE);
        prompt.setTextSize(14); prompt.setGravity(Gravity.TOP); prompt.setMinLines(2); prompt.setMaxLines(5); prompt.setBackgroundColor(Color.TRANSPARENT); prompt.setPadding(dp(3), dp(2), dp(3), dp(4));
        prompt.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        prompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { if(!suppressPromptWatcher) updateSlashPalette(s == null ? "" : s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        });
        card.addView(prompt, lp(-1,-2));

        LinearLayout footer = hbox(); footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView plus = smallIcon("＋"); plus.setOnClickListener(v -> showAttachMenu()); footer.addView(plus, lp(dp(34),dp(30)));
        composerModeChip = smallPill(permissionLabel(), MUTED); composerModeChip.setOnClickListener(v -> showPermissionPicker()); footer.addView(composerModeChip, new LinearLayout.LayoutParams(0,dp(30),1));
        composerEffortChip = smallPill("推理：" + effortLabel(config.effort), MUTED); composerEffortChip.setOnClickListener(v -> showEffortPicker()); footer.addView(composerEffortChip, new LinearLayout.LayoutParams(0,dp(30),1));
        composerModelChip = smallPill(shorten(currentProfileName()+" · "+config.model, wide?26:16), MUTED); composerModelChip.setGravity(Gravity.CENTER); composerModelChip.setOnClickListener(v -> showModelPicker()); composerModelChip.setOnLongClickListener(v->{showApiProfileManager();return true;}); footer.addView(composerModelChip, new LinearLayout.LayoutParams(0,dp(30),1));
        stopButton=text("■",13,Color.WHITE);stopButton.setGravity(Gravity.CENTER);stopButton.setContentDescription("立即停止当前任务");stopButton.setBackground(round(RED,16,RED,0));stopButton.setVisibility(View.GONE);stopButton.setOnClickListener(v->{if(engine!=null&&engine.isBusy()){engine.cancel();showWorkingIndicator("正在停止…");toast("正在停止当前任务");}});LinearLayout.LayoutParams stopLp=lp(dp(32),dp(32));stopLp.setMargins(0,0,dp(6),0);footer.addView(stopButton,stopLp);
        sendButton = text("↑", 18, Color.WHITE); sendButton.setGravity(Gravity.CENTER); sendButton.setTypeface(Typeface.DEFAULT_BOLD); sendButton.setBackground(neonTheme ? accentGradient(16) : round(ACCENT, 16, ACCENT, 0));
        sendButton.setContentDescription("发送消息；任务运行时会预输入并在当前步骤完成后加载。长按停止当前任务");
        sendButton.setOnClickListener(v -> sendPrompt());
        sendButton.setOnLongClickListener(v -> { if(engine!=null&&engine.isBusy()){engine.cancel();showWorkingIndicator("正在停止…");toast("正在停止当前任务");return true;} return false; });
        footer.addView(sendButton, lp(dp(32),dp(32)));
        card.addView(footer, lp(-1, dp(36))); wrap.addView(card, lp(-1, -1)); return wrap;
    }

    private void sendPrompt() {
        if (prompt == null) return;
        String p = prompt.getText().toString().trim(); if (p.isEmpty()) return;
        if (p.startsWith("/") && executeSlashCommand(p)) {
            suppressPromptWatcher = true; prompt.setText(""); suppressPromptWatcher = false;
            if (slashPaletteScroll != null) slashPaletteScroll.setVisibility(View.GONE);
            return;
        }
        boolean steering = engine != null && engine.isBusy();
        SessionRuntime promptRuntime=activeRuntime;
        if(!steering&&promptRuntime!=null&&promptRuntime.nextConfig!=null)config=promptRuntime.nextConfig.copy();
        if (!steering&&(config.apiKey == null || config.apiKey.isEmpty())) { toast("当前 API 配置缺少密钥"); showApiProfileManager(); return; }
        List<Attachment> sentImages = new ArrayList<>();
        for (Attachment a : pendingAttachments) if (a.kind == Attachment.IMAGE && a.bytes != null && a.bytes.length > 0) sentImages.add(a);
        String visible = p;
        JSONArray imageBlocks = buildImageBlocks();
        String enriched;
        if (p.startsWith("@")) {
            int cut=p.indexOf(' '); String agentName=cut>1?p.substring(1,cut).trim():p.substring(1).trim(); String delegated=cut>0?p.substring(cut+1).trim():"";
            boolean known=false; for(AgentDefinition def:engine.listAgentDefinitions())if(def.name.equalsIgnoreCase(agentName)){known=true;break;}
            if(known && !delegated.isEmpty()) enriched=buildPromptWithAttachments("Delegate this request to the `"+agentName+"` subagent using the Agent tool, wait for the result unless the agent is configured for background execution, then summarize the result for me. Delegated request:\n\n"+delegated);
            else enriched=buildPromptWithAttachments(p);
        } else enriched = buildPromptWithAttachments(p);
        pendingAttachments.clear();
        if(!steering)finalizeStreamingMessage();
        ChatItem userItem = new ChatItem(ChatItem.USER, "你", visible);
        userItem.messageId=java.util.UUID.randomUUID().toString();userItem.humanMessage=true;
        userItem.persistedBody=enriched;
        userItem.messageContentHash=persistedMessageHash(enriched,imageBlocks);
        userItem.images.addAll(sentImages);
        transcript.add(userItem);
        suppressPromptWatcher = true; prompt.setText(""); suppressPromptWatcher = false;
        rebuildAttachmentStrip(); hideSlashPalette();
        if(!steering){streamingItem = null; streamingView = null; streamingBodyHost = null; streamingVisibleChars = 0;}
        if (chatMessages != null) addChatView(userItem); scrollChat();
        showWorkingIndicator(steering ? "已预输入，等待当前回复完成…" : "正在思考…"); setComposerBusy(true);
        try {
            if (steering) {
                boolean wasTool = engine.isExecutingTool();
                boolean wasModel = engine.isExecutingModelRequest();
                if (!engine.steerPrompt(enriched, imageBlocks, userItem.messageId)) {
                    engine.configure(config);
                    engine.sendPrompt(enriched, imageBlocks, userItem.messageId);
                } else toast(wasTool ? "已预输入：当前工具完成后加载"
                    : wasModel ? "已预输入：当前回复完成后加载"
                    : "已预输入：当前步骤完成后加载");
            } else {
                engine.configure(config);
                if(promptRuntime!=null){promptRuntime.boundProfileId=config.profileId;promptRuntime.pendingProfileId="";promptRuntime.nextConfig=config.copy();}
                engine.sendPrompt(enriched, imageBlocks, userItem.messageId);
            }
        }
        catch (Exception e) { hideWorkingIndicator(); setComposerBusy(engine!=null&&engine.isBusy()); ChatItem err = new ChatItem(ChatItem.ERROR, "错误", String.valueOf(e.getMessage())); transcript.add(err); if (chatMessages != null) addChatView(err); }
    }

    private void updateSlashPalette(String raw) {
        if (slashPalette == null || slashPaletteScroll == null) return;
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith("/") || value.contains("\n") || value.contains(" ")) {
            hideSlashPalette();
            return;
        }
        String q = value.toLowerCase(Locale.US);
        slashPalette.removeAllViews();
        int count = 0;
        for (SlashCommand cmd : SLASH_COMMANDS) {
            if (!cmd.name.startsWith(q)) continue;
            LinearLayout row = hbox(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(2), dp(8), dp(2));
            TextView name = text(cmd.name, 11.5f, TEXT); name.setTypeface(Typeface.DEFAULT_BOLD); row.addView(name, lp(dp(104),dp(38)));
            TextView hint = text(cmd.hint, 10.2f, MUTED); hint.setSingleLine(true); hint.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(hint, new LinearLayout.LayoutParams(0,dp(38),1));
            row.setOnClickListener(v -> {
                suppressPromptWatcher = true;
                prompt.setText(cmd.name + " "); prompt.setSelection(prompt.length());
                suppressPromptWatcher = false; hideSlashPalette();
            });
            slashPalette.addView(row, lp(-1, dp(42)));
            count++;
        }
        if (count == 0) { hideSlashPalette(); return; }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) slashPaletteScroll.getLayoutParams();
        if (lp != null) { lp.height = Math.min(dp(252), Math.max(dp(48), count * dp(42))); slashPaletteScroll.setLayoutParams(lp); }
        if (slashPaletteScroll.getVisibility() != View.VISIBLE) {
            slashPaletteScroll.setVisibility(View.VISIBLE); slashPaletteScroll.setAlpha(0f); slashPaletteScroll.setTranslationY(dp(4));
            UiMotion.pageIn(slashPaletteScroll);
        }
        UiMotion.bindInteractive(slashPalette);
        slashPaletteScroll.post(() -> slashPaletteScroll.scrollTo(0,0));
    }

    private void hideSlashPalette() {
        if (slashPaletteScroll == null || slashPaletteScroll.getVisibility() != View.VISIBLE) return;
        UiMotion.fadeOut(slashPaletteScroll,3f,110,() -> {
            if (slashPaletteScroll != null) { slashPaletteScroll.setVisibility(View.GONE); slashPaletteScroll.setTranslationY(0f); }
        });
    }

    private boolean executeSlashCommand(String raw) {
        String line = raw == null ? "" : raw.trim();
        int sp = line.indexOf(' ');
        String cmd = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.US);
        String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
        switch (cmd) {
            case "/help": showCommandHelp(); return true;
            case "/compact": compactContextFromUi(arg); return true;
            case "/context": if (!arg.isEmpty()) { try { config.contextWindowTokens = parseTokenCount(arg); saveConfigQuiet(); refreshChrome(); toast("上下文窗口已设置为 " + formatTokenCount(config.contextWindowTokens)); } catch (Exception e) { toast("请输入 128k、200k、1m、1.5m 这类格式"); } } else showContextInfo(); return true;
            case "/clear":
            case "/new": newSession(); return true;
            case "/resume": showResumePicker(); return true;
            case "/model": if (!arg.isEmpty()) { config.model = arg; saveConfigQuiet(); updateComposerChips(); } else showModelPicker(); return true;
            case "/effort": if (!arg.isEmpty()) applyReasoningEffort(arg,true); else showEffortPicker(); return true;
            case "/permissions": showPermissionPicker(); return true;
            case "/root": handleRootSlash(arg); return true;
            case "/keepalive": handleKeepAliveSlash(arg); return true;
            case "/mcp": handleMcpSlash(arg); return true;
            case "/web": handleWebSlash(arg); return true;
            case "/terminal": focusWorkspace(VIEW_TERMINAL); return true;
            case "/sandbox": startActivity(new Intent(this, SandboxDashboardActivity.class)); return true;
            case "/diff":
            case "/changes": focusWorkspace(VIEW_CHANGES); return true;
            case "/files": focusWorkspace(VIEW_FILES); return true;
            case "/doctor": runDiagnosticCommand("Termux 环境检查", "printf 'HOME=%s\\nPREFIX=%s\\nPATH=%s\\nTMPDIR=%s\\n' \"$HOME\" \"$PREFIX\" \"$PATH\" \"$TMPDIR\"; for x in bash pkg apt dpkg git python node bun java javac clang make cmake; do printf '%-8s ' \"$x\"; command -v \"$x\" || echo missing; done; echo; dpkg --audit 2>&1 || true", 60000); return true;
            case "/repair": runDiagnosticCommand("Termux 修复", "dpkg --configure -a; apt-get -f install -y", 600000); return true;
            case "/skills": showSkillsPicker(); return true;
            case "/status": showStatusInfo(); return true;
            case "/stats": showStatusInfo(); return true;
            case "/usage": showContextInfo(); return true;
            case "/copy": copyLatestAssistantReply(); return true;
            case "/plan": togglePlanMode(arg); return true;
            case "/config": showSettings(); return true;
            case "/canvas": showUiCanvasPanel(); return true;
            case "/memory": showMemoryPicker(); return true;
            case "/init": runInitPrompt(); return true;
            case "/tasks": showTaskFiles(); return true;
            case "/agents": showAgentsPanel(); return true;
            case "/cancel": engine.cancel(); toast("已停止当前任务"); return true;
            case "/runtime": runtimeDialog(); return true;
            default: return false;
        }
    }

    private void showCommandHelp() {
        StringBuilder b = new StringBuilder("可用的 / 指令：\n\n");
        for (SlashCommand c : SLASH_COMMANDS) b.append("` ").append(c.name).append(" ` — ").append(c.hint).append('\n');
        ChatItem item = new ChatItem(ChatItem.ASSISTANT, "IQ Code 指令", b.toString()); transcript.add(item);
        if (chatMessages != null) { addChatView(item); scrollChatSoft(); }
    }

    private void showContextInfo() {
        int tokens = engine == null ? 0 : engine.estimateContextTokens();
        int pct = engine == null ? 0 : engine.contextPercent();
        int threshold = engine == null ? 0 : engine.autoCompactThresholdTokens();
        int effective = engine == null ? 0 : engine.effectiveContextWindowTokens();
        String text = "预计上下文：**" + formatTokenCount(tokens) + " tokens** · 已使用 **" + pct + "%** / " + formatTokenCount(config.contextWindowTokens) + "。\n\n"
            + "自动压缩：" + (config.autoCompact ? "已开启" : "已关闭") + " · 实际阈值 **" + formatTokenCount(threshold) + "** · 可用窗口 **" + formatTokenCount(effective) + "**。\n\n"
            + "阈值按模型输出预留（最多 20k）和 13k 安全缓冲计算；token 优先采用 API 实际用量。输入 `/compact [侧重点]` 可手动调用模型生成语义摘要。";
        ChatItem item = new ChatItem(ChatItem.ASSISTANT, "上下文", text); transcript.add(item);
        if (chatMessages != null) { addChatView(item); scrollChatSoft(); }
    }

    private void showStatusInfo() {
        File sf = engine == null ? null : engine.getSessionFile();
        String body = "**模型：** `" + config.model + "`\n\n" +
            "**协议：** `" + config.protocol + "` · **推理强度：** " + effortLabel(config.effort) + "\n\n" +
            "**项目：** `" + config.projectDirectory + "`\n\n" +
            "**上下文：** " + (engine == null ? 0 : engine.contextPercent()) + "% · 自动压缩 " + (config.autoCompact ? "开启" : "关闭") +
            "\n\n**联网搜索：** " + (config.webSearchEnabled ? "开启（" + config.webSearchProvider + "）" : "关闭") +
            "\n\n**运行环境：** " + (runtime.isInstalled() ? "已就绪" : "未安装") +
            "\n\n**IQ 沙箱：** " + IQSandboxEngine.status() +
            "\n\n**会话：** `" + (sf == null ? "未开始" : sf.getName()) + "`";
        ChatItem item = new ChatItem(ChatItem.ASSISTANT, "状态", body); transcript.add(item);
        if (chatMessages != null) { addChatView(item); scrollChatSoft(); }
    }

    private void copyLatestAssistantReply() {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            ChatItem item = transcript.get(i);
            if (item.type == ChatItem.ASSISTANT && item.body.length() > 0) {
                copyTextToClipboard("IQ Code latest response", item.body.toString());
                return;
            }
        }
        toast("还没有可复制的 IQ 回复");
    }

    private void togglePlanMode(String argument) {
        String raw=argument==null?"":argument.trim();String arg=raw.toLowerCase(Locale.US);PlanWorkflowState state=engine.getPlanWorkflowState();
        if("open".equals(arg)||"打开".equals(arg)){if(state.planFile==null||state.planFile.isEmpty())toast("当前还没有计划文件");else openEditor(new File(state.planFile));return;}
        if("off".equals(arg)||"exit".equals(arg)||"关闭".equals(arg)){ToolExecutionResult result=engine.cancelPlanModeFromUi("用户通过 /plan off 退出");toast(result.isError?result.content:"已退出计划模式");return;}
        if(state.isPlanning()||state.isAwaitingApproval()){if(state.planText==null||state.planText.trim().isEmpty())toast("正在计划模式：继续调研并提交计划");else showPlanPreview(state);return;}
        ToolExecutionResult result=engine.enterPlanModeFromUi();updateComposerChips();toast(result.isError?result.content:"已进入计划模式：只读调研后提交计划审批");
    }

    private void showPlanPreview(PlanWorkflowState state){final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout panel=vbox();panel.setPadding(dp(16),dp(12),dp(16),dp(12));panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));TextView title=text("当前计划 · revision "+state.revision,16,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(title,lp(-1,dp(40)));TextView path=text(state.planFile,9.5f,MUTED_2);path.setTypeface(Typeface.MONOSPACE);panel.addView(path,lp(-1,dp(30)));ScrollView scroll=new ScrollView(this);scroll.addView(MarkdownRenderer.render(this,state.planText,12.5f,neonTheme,lightTheme));panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));TextView close=pill("关闭",TEXT);close.setGravity(Gravity.CENTER);close.setOnClickListener(v->d.dismiss());panel.addView(close,lp(-1,dp(44)));d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(560),(int)(getResources().getDisplayMetrics().widthPixels*.94f)),Math.min(dp(700),(int)(getResources().getDisplayMetrics().heightPixels*.86f)));}

    private void handleWebSlash(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) { showWebPanel(); return; }
        if ("on".equalsIgnoreCase(a) || "开启".equals(a)) { config.webSearchEnabled=true; saveConfigQuiet(); toast("联网搜索已开启"); return; }
        if ("off".equalsIgnoreCase(a) || "关闭".equals(a)) { config.webSearchEnabled=false; saveConfigQuiet(); toast("联网搜索已关闭"); return; }
        if (a.toLowerCase(Locale.US).startsWith("fetch ")) { runManualWebFetch(a.substring(6).trim()); return; }
        runManualWebSearch(a);
    }

    private void showWebPanel() {
        final Dialog d=newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(14),dp(12),dp(14),dp(12)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout top=hbox(); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("联网搜索",15,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); top.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));
        TextView close=iconButton("×"); close.setOnClickListener(v->d.dismiss()); top.addView(close,lp(dp(40),dp(40))); panel.addView(top,lp(-1,dp(44)));
        TextView status=text(config.webSearchEnabled?"已开启 · IQ 可自动调用 WebSearch / WebFetch":"已关闭 · Agent 不会看到联网工具",10.5f,config.webSearchEnabled?GREEN:MUTED); status.setPadding(dp(4),0,dp(4),dp(10)); panel.addView(status,lp(-1,-2));
        TextView toggle=pill(config.webSearchEnabled?"关闭联网搜索":"开启联网搜索",TEXT); toggle.setGravity(Gravity.CENTER); toggle.setOnClickListener(v->{config.webSearchEnabled=!config.webSearchEnabled; saveConfigQuiet(); d.dismiss(); showWebPanel();}); panel.addView(toggle,lp(-1,dp(42)));
        TextView provider=text("搜索后端：" + webProviderLabel(config.webSearchProvider),11,MUTED); provider.setPadding(dp(10),dp(12),dp(10),0); panel.addView(provider,lp(-1,dp(42)));
        TextView hint=text("直接输入 `/web Android 16 新特性` 可手动搜索；`/web fetch https://...` 可抓取网页正文。Agent 在需要最新资料时也会自动调用。",10,MUTED_2); hint.setPadding(dp(8),dp(8),dp(8),0); panel.addView(hint,lp(-1,-2));
        d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(440),(int)(getResources().getDisplayMetrics().widthPixels*.90f)),-2);
    }

    private String webProviderLabel(String value) {
        if ("duckduckgo".equalsIgnoreCase(value)) return "DuckDuckGo";
        if ("bing".equalsIgnoreCase(value)) return "Bing RSS";
        return "自动（DuckDuckGo → Bing）";
    }

    private void runManualWebSearch(String query) {
        if (!config.webSearchEnabled) { toast("联网搜索已关闭，可输入 /web on 开启"); return; }
        if (query==null || query.trim().isEmpty()) { showWebPanel(); return; }
        final ChatItem wait=new ChatItem(ChatItem.ASSISTANT,"联网搜索","正在搜索：`"+query.trim()+"` …"); transcript.add(wait); if(chatMessages!=null){addChatView(wait);scrollChatSoft();}
        io.submit(()->{ ToolExecutionResult r; try { r=new WebSearchTool().execute(config.copy(),new JSONObject().put("query",query.trim())); } catch(Throwable e){ r=ToolExecutionResult.error(e.getMessage()==null?e.toString():e.getMessage()); }
            final ToolExecutionResult out=r; uiHandler.post(()->{ wait.body.setLength(0); wait.body.append(out.content); wait.completed=true; scheduleChatRender(); }); });
    }

    private void runManualWebFetch(String url) {
        if (!config.webSearchEnabled) { toast("联网搜索已关闭，可输入 /web on 开启"); return; }
        if (url==null || url.trim().isEmpty()) { toast("用法：/web fetch https://example.com"); return; }
        final ChatItem wait=new ChatItem(ChatItem.ASSISTANT,"网页读取","正在读取：`"+url.trim()+"` …"); transcript.add(wait); if(chatMessages!=null){addChatView(wait);scrollChatSoft();}
        io.submit(()->{ ToolExecutionResult r; try { r=new WebFetchTool().execute(config.copy(),new JSONObject().put("url",url.trim())); } catch(Throwable e){ r=ToolExecutionResult.error(e.getMessage()==null?e.toString():e.getMessage()); }
            final ToolExecutionResult out=r; uiHandler.post(()->{ wait.body.setLength(0); wait.body.append(out.content); wait.completed=true; scheduleChatRender(); }); });
    }

    private void showMemoryPicker() {
        List<File> files = new ArrayList<>();
        File project = new File(config.projectDirectory, "IQ.md"); if (project.isFile()) files.add(project);
        File user = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".iq/IQ.md"); if (user.isFile()) files.add(user);
        if (files.isEmpty()) { toast("未找到 IQ.md，可使用 /init 创建项目说明"); return; }
        String[] labels = new String[files.size()];
        for (int i=0;i<files.size();i++) labels[i] = files.get(i).equals(project) ? "项目 IQ.md" : "用户 IQ.md";
        showChoicePicker("记忆文件", labels, -1, which -> openEditor(files.get(which)));
    }

    private void runInitPrompt() {
        if (engine.isBusy()) { toast("IQ 正在执行任务，请先等待或停止当前任务"); return; }
        String visible = "/init";
        if(engine.getPlanWorkflowState().isPlanning())engine.cancelPlanModeFromUi("/init 使用快速直接模式");
        String instruction = "This is the built-in fast /init maintenance command. Do not enter plan mode, create tasks, launch subagents, or perform broad repository research. Read only the existing IQ.md/CLAUDE.md/README and primary build manifest or script when present, then directly create or improve IQ.md with concise build, test, architecture, conventions, and repository-specific instructions. Preserve useful existing instructions and run at most one small verification command.";
        transcript.add(new ChatItem(ChatItem.USER, "你", visible));
        if (wide || currentView == VIEW_CHAT) renderChat(primaryHost);
        try { engine.configure(config); engine.sendPrompt(instruction); } catch (Exception e) { toast(e.getMessage()); }
    }

    private void showAgentsPanel() {
        final Dialog d = newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(14),dp(12),dp(14),dp(12)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout top=hbox(); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Agents",15,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); top.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));
        TextView close=iconButton("×"); close.setTextSize(22); close.setOnClickListener(v->d.dismiss()); top.addView(close,lp(dp(40),dp(40))); panel.addView(top,lp(-1,dp(42)));
        TextView hint=text("内置 Agent 始终可用 · 点击 Agent 后用 @名称 调用",10.5f,MUTED); panel.addView(hint,lp(-1,dp(34)));
        ScrollView sv=new ScrollView(this); sv.setVerticalScrollBarEnabled(false); LinearLayout rows=vbox();
        List<AgentTask> running=engine.listAgentTasks();
        TextView rh=text("运行中",9.5f,MUTED_2); rh.setTypeface(Typeface.DEFAULT_BOLD); rh.setPadding(0,dp(10),0,dp(5)); rows.addView(rh,lp(-1,dp(36)));
        boolean any=false;
        for(AgentTask task:running){
            any=true; LinearLayout row=hbox(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8),dp(4),dp(5),dp(4));
            String state="running".equals(task.status)?"●":"completed".equals(task.status)?"✓":"×";
            int color="running".equals(task.status)?ACCENT:"completed".equals(task.status)?GREEN:RED;
            String stateLabel="running".equals(task.status)?"运行中":"completed".equals(task.status)?"已完成":"已失败";
            TextView info=text(state+"  "+task.agentType+"\n"+task.description+" · "+stateLabel,10.8f,TEXT); info.setLineSpacing(0,1.08f); row.addView(info,new LinearLayout.LayoutParams(0,dp(54),1));
            if("running".equals(task.status)){TextView stop=smallPill("停止",RED);stop.setGravity(Gravity.CENTER);stop.setOnClickListener(v->{engine.stopAgentTask(task.id);toast("已停止 "+task.id);d.dismiss();showAgentsPanel();});row.addView(stop,lp(dp(58),dp(34)));}
            else {TextView tag=smallPill(task.id,MUTED_2);tag.setGravity(Gravity.CENTER);row.addView(tag,lp(dp(68),dp(34)));}
            rows.addView(row,lp(-1,dp(62)));
        }
        if(!any){TextView empty=text("当前没有运行中的子 Agent",10.5f,MUTED_2);empty.setPadding(dp(8),0,0,0);empty.setGravity(Gravity.CENTER_VERTICAL);rows.addView(empty,lp(-1,dp(42)));}
        TextView lh=text("可用 Agents",9.5f,MUTED_2); lh.setTypeface(Typeface.DEFAULT_BOLD); lh.setPadding(0,dp(16),0,dp(5)); rows.addView(lh,lp(-1,dp(42)));
        List<AgentDefinition> defs=engine.listAgentDefinitions();String lastSource="";
        for(AgentDefinition def:defs){
            String sourceLabel=agentSourceLabel(def.source);if(!sourceLabel.equals(lastSource)){TextView group=text(sourceLabel,9.5f,MUTED_2);group.setTypeface(Typeface.DEFAULT_BOLD);group.setPadding(dp(4),dp(8),0,dp(4));rows.addView(group,lp(-1,dp(30)));lastSource=sourceLabel;}
            LinearLayout row=vbox(); row.setPadding(dp(12),dp(8),dp(12),dp(8));row.setBackground(round(SURFACE_3,14,Color.TRANSPARENT,0));
            String meta=agentSourceLabel(def.source)+(def.model==null||def.model.isEmpty()||"inherit".equals(def.model)?"":" · "+def.model)+(def.background?" · 后台":"");
            TextView n=text(def.name,11.3f,TEXT); n.setTypeface(Typeface.DEFAULT_BOLD); row.addView(n,lp(-1,dp(25)));
            TextView metaView=text(meta,9.5f,MUTED_2);row.addView(metaView,lp(-1,dp(20)));
            TextView desc=text(def.description==null||def.description.isEmpty()?"自定义子 Agent":def.description,10.2f,MUTED);desc.setMaxLines(2);desc.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(desc,lp(-1,dp(38)));
            row.setOnClickListener(v->{d.dismiss();suppressPromptWatcher=true;prompt.setText("@"+def.name+" ");prompt.setSelection(prompt.length());suppressPromptWatcher=false;prompt.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(prompt,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
            LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,dp(89));rowLp.setMargins(0,0,0,dp(6));rows.addView(row,rowLp);
        }
        TextView foot=text("自定义 Agent：.iq/agents/*.md 或 ~/.iq/agents/*.md",9.8f,MUTED_2);foot.setPadding(dp(8),dp(8),0,dp(8));rows.addView(foot,lp(-1,dp(44)));
        sv.addView(rows,new ScrollView.LayoutParams(-1,-2));panel.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(480),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),Math.min(dp(700),(int)(getResources().getDisplayMetrics().heightPixels*.82f)));
    }

    private String agentSourceLabel(String source){
        if("built-in".equals(source))return "内置 · 始终可用";
        if("project".equals(source)||"projectSettings".equals(source))return "项目 Agent";
        if("user".equals(source)||"userSettings".equals(source))return "用户 Agent";
        if(source==null||source.isEmpty())return "自定义 Agent";
        return source;
    }

    private void showTaskFiles() {
        File root = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".iq");
        StringBuilder b = new StringBuilder("本地 IQ Android 状态：\n\n");
        appendTree(b, root, "", 0, 3);
        ChatItem item = new ChatItem(ChatItem.ASSISTANT, "任务文件", "```text\n" + b + "```"); transcript.add(item);
        if (chatMessages != null) { addChatView(item); scrollChatSoft(); }
    }

    private void appendTree(StringBuilder out, File file, String indent, int depth, int maxDepth) {
        if (file == null || depth > maxDepth || !file.exists()) return;
        if (depth > 0) out.append(indent).append(file.getName()).append(file.isDirectory() ? "/" : "").append('\n');
        if (!file.isDirectory() || depth == maxDepth) return;
        File[] children = file.listFiles(); if (children == null) return;
        Arrays.sort(children, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        int shown = 0;
        for (File child : children) { if (shown++ >= 40) { out.append(indent).append("  …\n"); break; } appendTree(out, child, indent + "  ", depth + 1, maxDepth); }
    }

    private void compactContextFromUi(String instructions) {
        if (engine == null) return;
        if (engine.isBusy()) { toast("请等待当前任务结束后再压缩上下文"); return; }
        setComposerBusy(true);
        showWorkingIndicator("正在调用模型整理上下文…");
        io.execute(() -> {
            try {
                String result = engine.compactContext(instructions);
                ui(() -> { ChatItem item = new ChatItem(ChatItem.ASSISTANT, "上下文压缩", "✓ " + result); stampCurrentContext(item, engine); transcript.add(item); if (chatMessages != null) { addChatView(item); scrollChatSoft(); } refreshChrome(); });
            } catch (Exception e) {
                ui(() -> { hideWorkingIndicator(); setComposerBusy(false); toast(e.getMessage() == null ? e.toString() : e.getMessage()); });
            }
        });
    }

    private void runDiagnosticCommand(String title, String command, int timeout) {
        ChatItem item = new ChatItem(ChatItem.TOOL, "Bash", new JSONObjectSafe().put("command", command).put("cwd", config.projectDirectory).put("timeout_ms", timeout).toString());
        transcript.add(item); if (chatMessages != null) addChatView(item); scrollChatSoft();
        io.execute(() -> {
            try {
                TermuxShellExecutor.Result r = new TermuxShellExecutor(this).execute(command, config.projectDirectory, timeout);
                ui(() -> { item.result.append(r.combined()); item.completed = true; item.exitCode = r.exitCode; item.resultError = r.exitCode != 0; refreshToolItem(item); });
            } catch (Exception e) {
                ui(() -> { item.result.append(e.toString()); item.completed = true; item.resultError = true; item.exitCode = -1; refreshToolItem(item); });
            }
        });
    }

    /** Tiny helper to avoid checked JSONException noise for UI-only tool cards. */
    private static final class JSONObjectSafe {
        private final JSONObject object = new JSONObject();
        JSONObjectSafe put(String k, Object v) { try { object.put(k, v); } catch (Exception ignored) { } return this; }
        @Override public String toString() { return object.toString(); }
    }

    private String buildPromptWithAttachments(String promptText) {
        if (pendingAttachments.isEmpty()) return promptText;
        boolean hasText=false; for(Attachment a:pendingAttachments) if(a.kind==Attachment.TEXT){hasText=true;break;}
        if(!hasText) return promptText;
        StringBuilder b = new StringBuilder(promptText).append("\n\n<attached_context>");
        for (Attachment a : pendingAttachments) {
            if (a.kind != Attachment.TEXT) continue;
            b.append("\n<attachment name=\"").append(a.label.replace("\"", "'")).append("\">\n");
            b.append(a.body.length() > 60000 ? a.body.substring(0,60000) + "\n[…attachment truncated…]" : a.body);
            b.append("\n</attachment>");
        }
        return b.append("\n</attached_context>").toString();
    }

    private JSONArray buildImageBlocks() {
        JSONArray blocks = new JSONArray();
        for (Attachment a : pendingAttachments) {
            if (a.kind != Attachment.IMAGE || a.bytes == null || a.bytes.length == 0) continue;
            try {
                String data = Base64.encodeToString(a.bytes, Base64.NO_WRAP);
                JSONObject source = new JSONObject().put("type", "base64").put("media_type", a.mimeType).put("data", data);
                blocks.put(new JSONObject().put("type", "image").put("source", source).put("name", a.label));
            } catch (Exception ignored) { }
        }
        return blocks;
    }

    private String persistedMessageHash(String text, JSONArray extraContent) {
        try {
            JSONArray content = new JSONArray();
            if (text != null && !text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
            if (extraContent != null) for (int i = 0; i < extraContent.length(); i++) content.put(extraContent.get(i));
            return SessionStore.messageContentHash(content);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void rebuildAttachmentStrip() {
        if (attachmentStrip == null) return;
        attachmentStrip.removeAllViews();
        attachmentStrip.setVisibility(pendingAttachments.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < pendingAttachments.size(); i++) {
            final int index = i; Attachment a = pendingAttachments.get(i);
            LinearLayout chip = hbox(); chip.setGravity(Gravity.CENTER_VERTICAL); chip.setPadding(dp(7),dp(3),dp(7),dp(3)); chip.setBackground(round(SURFACE_3,12,Color.TRANSPARENT,0));
            if (a.kind == Attachment.IMAGE && a.bytes != null) {
                ImageView thumb = new ImageView(this);
                try { Bitmap bm = BitmapFactory.decodeByteArray(a.bytes,0,a.bytes.length); thumb.setImageBitmap(bm); } catch (Throwable ignored) { }
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); chip.addView(thumb,lp(dp(34),dp(34)));
            } else {
                TextView glyph=text("＋",12,ACCENT);glyph.setGravity(Gravity.CENTER);chip.addView(glyph,lp(dp(28),dp(30)));
            }
            TextView name=text(shorten(a.label,36),10.5f,TEXT); name.setGravity(Gravity.CENTER_VERTICAL); chip.addView(name,new LinearLayout.LayoutParams(0,dp(34),1));
            TextView remove=text("×",15,MUTED); remove.setGravity(Gravity.CENTER); remove.setOnClickListener(v -> { if (index < pendingAttachments.size()) pendingAttachments.remove(index); rebuildAttachmentStrip(); }); chip.addView(remove,lp(dp(32),dp(34)));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,a.kind==Attachment.IMAGE?dp(44):dp(36)); cp.setMargins(0,0,0,dp(4)); attachmentStrip.addView(chip,cp);
        }
        UiMotion.bindInteractive(attachmentStrip);
        UiMotion.staggerChildren(attachmentStrip, 5);
    }

    private void showAttachMenu() {
        final Dialog d = newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(14),dp(12),dp(14),dp(14)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout top=hbox(); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView h=text("添加上下文",15,TEXT); h.setTypeface(Typeface.DEFAULT_BOLD); top.addView(h,new LinearLayout.LayoutParams(0,dp(40),1));
        TextView close=iconButton("×"); close.setOnClickListener(v->d.dismiss()); top.addView(close,lp(dp(38),dp(38))); panel.addView(top,lp(-1,dp(44)));
        panel.addView(contextAction("▤", "附加项目文件", "搜索项目文件，可多次附加到下一条消息", v->{d.dismiss();showProjectFilePicker();}));
        panel.addView(contextAction("✦", "Skill 管理器", "查看、编辑、新建、删除并附加 SKILL.md", v->{d.dismiss();showSkillManager();}));
        panel.addView(contextAction("▧", "打开文件工作区", "浏览、增删改文件并使用内联代码编辑器", v->{d.dismiss();focusWorkspace(VIEW_FILES);}));
        panel.addView(contextAction("▣", "上传照片", "从相册或文件中选择图片，作为视觉上下文发送", v->{d.dismiss();launchImagePicker();}));
        TextView tip=text("已附加内容只会加入下一条消息；发送后自动清空。",9.5f,MUTED_2); tip.setPadding(dp(8),dp(8),dp(8),0); panel.addView(tip,lp(-1,-2));
        d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(460),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
    }

    private View contextAction(String icon,String title,String subtitle,View.OnClickListener click){
        LinearLayout row=hbox(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8),dp(7),dp(8),dp(7));
        TextView i=text(icon,17,ACCENT); i.setGravity(Gravity.CENTER); row.addView(i,lp(dp(42),dp(50)));
        LinearLayout copy=vbox(); TextView t=text(title,12.5f,TEXT);t.setTypeface(Typeface.DEFAULT_BOLD);copy.addView(t,lp(-1,dp(25)));TextView sub=text(subtitle,9.5f,MUTED);sub.setMaxLines(2);copy.addView(sub,lp(-1,-2));row.addView(copy,new LinearLayout.LayoutParams(0,dp(52),1));
        TextView arrow=text("›",20,MUTED_2);arrow.setGravity(Gravity.CENTER);row.addView(arrow,lp(dp(28),dp(50)));row.setOnClickListener(click);return row;
    }

    private void showProjectFilePicker(){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout head=hbox();head.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("附加项目文件",15,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);head.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));TextView x=iconButton("×");x.setOnClickListener(v->d.dismiss());head.addView(x,lp(dp(38),dp(38)));panel.addView(head,lp(-1,dp(44)));
        EditText search=input("",false);search.setHint("搜索文件名或路径");panel.addView(search,lp(-1,dp(44)));
        TextView root=text("项目："+config.projectDirectory,9.5f,MUTED_2);root.setSingleLine(true);root.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);root.setPadding(dp(4),dp(6),dp(4),dp(4));panel.addView(root,lp(-1,dp(30)));
        ListView list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        final List<File> all=listProjectFiles(new File(config.projectDirectory),700); final List<File> shown=new ArrayList<>();
        Runnable refresh=()->{String q=search.getText().toString().trim().toLowerCase(Locale.US);shown.clear();List<String> names=new ArrayList<>();for(File f:all){String rel=relativeProjectPath(f);if(q.isEmpty()||rel.toLowerCase(Locale.US).contains(q)){shown.add(f);names.add(fileGlyph(f)+"  "+rel);if(names.size()>=180)break;}}ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names){@Override public View getView(int p,View c,ViewGroup parent){TextView t=(TextView)super.getView(p,c,parent);t.setTextColor(TEXT);t.setTextSize(11);t.setTypeface(Typeface.MONOSPACE);t.setPadding(dp(8),dp(5),dp(8),dp(5));return t;}};list.setAdapter(a);};
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){refresh.run();}public void afterTextChanged(Editable e){}});refresh.run();
        list.setOnItemClickListener((p,v,pos,id)->{if(pos<shown.size()){attachFileDirect(shown.get(pos));toast("已附加："+shown.get(pos).getName());}});
        TextView done=pill("完成",TEXT);done.setGravity(Gravity.CENTER);done.setOnClickListener(v->d.dismiss());panel.addView(done,lp(-1,dp(40)));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(580),(int)(getResources().getDisplayMetrics().widthPixels*.94f)),Math.min(dp(700),(int)(getResources().getDisplayMetrics().heightPixels*.84f)));
    }

    private List<File> listProjectFiles(File root,int limit){List<File> out=new ArrayList<>();ArrayList<File> stack=new ArrayList<>();stack.add(root);while(!stack.isEmpty()&&out.size()<limit){File dir=stack.remove(stack.size()-1);File[] xs=dir.listFiles();if(xs==null)continue;Arrays.sort(xs,(a,b)->a.getName().compareToIgnoreCase(b.getName()));for(File f:xs){String n=f.getName();if(n.equals(".git")||n.equals("node_modules")||n.equals("build")||n.equals(".gradle"))continue;if(f.isDirectory())stack.add(f);else out.add(f);if(out.size()>=limit)break;}}return out;}

    private void showSkillsPicker(){ showSkillManager(); }

    private void showSkillManager(){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout head=hbox();head.setGravity(Gravity.CENTER_VERTICAL);LinearLayout hcopy=vbox();TextView title=text("Skill 管理器",15,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);hcopy.addView(title,lp(-1,dp(24)));TextView sub=text("项目级 .iq/skills 与用户级 ~/.iq/skills",9.5f,MUTED);hcopy.addView(sub,lp(-1,dp(20)));head.addView(hcopy,new LinearLayout.LayoutParams(0,dp(46),1));TextView add=smallPill("＋ 新建",ACCENT);add.setGravity(Gravity.CENTER);add.setOnClickListener(v->{d.dismiss();showNewSkillDialog();});head.addView(add,lp(dp(78),dp(36)));TextView x=iconButton("×");x.setOnClickListener(v->d.dismiss());head.addView(x,lp(dp(38),dp(38)));panel.addView(head,lp(-1,dp(52)));
        ScrollView sv=new ScrollView(this);LinearLayout rows=vbox();List<File> skills=discoverSkills();if(skills.isEmpty()){TextView empty=text("还没有 Skill。点“＋ 新建”创建一个 SKILL.md。",11,MUTED);empty.setGravity(Gravity.CENTER);rows.addView(empty,lp(-1,dp(110)));}else for(File f:skills)rows.addView(skillRow(f,d));sv.addView(rows);panel.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(600),(int)(getResources().getDisplayMetrics().widthPixels*.94f)),Math.min(dp(720),(int)(getResources().getDisplayMetrics().heightPixels*.86f)));
    }

    private View skillRow(File f,Dialog owner){
        LinearLayout row=hbox();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(7),dp(7),dp(7),dp(7));
        TextView glyph=text("✦",15,ACCENT);glyph.setGravity(Gravity.CENTER);row.addView(glyph,lp(dp(38),dp(52)));
        LinearLayout copy=vbox();String name=f.getParentFile().getName();TextView t=text(name,12,TEXT);t.setTypeface(Typeface.DEFAULT_BOLD);copy.addView(t,lp(-1,dp(25)));boolean project=f.getAbsolutePath().startsWith(new File(config.projectDirectory).getAbsolutePath());TextView meta=text((project?"项目级":"用户级")+" · "+shorten(f.getAbsolutePath(),54),9,MUTED);copy.addView(meta,lp(-1,dp(22)));row.addView(copy,new LinearLayout.LayoutParams(0,dp(52),1));
        TextView attach=smallPill("附加",GREEN);attach.setGravity(Gravity.CENTER);attach.setOnClickListener(v->{try{pendingAttachments.add(new Attachment("skill:"+name,new String(readFile(f),StandardCharsets.UTF_8)));rebuildAttachmentStrip();toast("已附加 Skill："+name);}catch(Exception e){toast("读取失败："+e.getMessage());}});row.addView(attach,lp(dp(58),dp(34)));
        TextView edit=smallPill("编辑",TEXT);edit.setGravity(Gravity.CENTER);edit.setOnClickListener(v->{owner.dismiss();showSkillEditor(f);});row.addView(edit,lp(dp(58),dp(34)));return row;
    }

    private void showNewSkillDialog(){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout p=vbox();p.setPadding(dp(14),dp(12),dp(14),dp(12));p.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));TextView h=text("新建 Skill",15,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);p.addView(h,lp(-1,dp(40)));EditText name=input("",false);name.setHint("skill-name");p.addView(labeled("名称",name));Spinner scope=darkSpinner(new String[]{"项目级","用户级"},new String[]{"project","user"},"project");p.addView(labeled("保存位置",scope));TextView create=pill("创建并编辑",TEXT);create.setGravity(Gravity.CENTER);create.setOnClickListener(v->{try{String n=name.getText().toString().trim();if(!n.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("名称只能包含字母、数字、._-");File root=scope.getSelectedItemPosition()==0?new File(config.projectDirectory,".iq/skills"):new File(TermuxConstants.TERMUX_HOME_DIR_PATH,".iq/skills");File dir=new File(root,n);if(!dir.mkdirs()&&!dir.isDirectory())throw new Exception("无法创建目录");File f=new File(dir,"SKILL.md");if(!f.exists()){String template="---\\nname: "+n+"\\ndescription: 请描述这个 Skill 的用途\\n---\\n\\n# "+n+"\\n\\n在这里编写给 IQ 的技能说明。\\n";writeFileBytes(f,template.getBytes(StandardCharsets.UTF_8));}d.dismiss();showSkillEditor(f);}catch(Exception e){toast(e.getMessage());}});p.addView(create,lp(-1,dp(42)));d.setContentView(p);d.show();styleDialogWindow(d,Math.min(dp(440),(int)(getResources().getDisplayMetrics().widthPixels*.90f)),-2);
    }

    private void showSkillEditor(File f){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout panel=vbox();panel.setPadding(dp(12),dp(10),dp(12),dp(10));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));LinearLayout head=hbox();head.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("编辑 Skill · "+f.getParentFile().getName(),14,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);head.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));TextView x=iconButton("×");x.setOnClickListener(v->d.dismiss());head.addView(x,lp(dp(38),dp(38)));panel.addView(head,lp(-1,dp(44)));
        EditText editor=new EditText(this);editor.setTextColor(TEXT);editor.setHintTextColor(MUTED_2);editor.setTextSize(11.5f);editor.setTypeface(Typeface.MONOSPACE);editor.setGravity(Gravity.TOP|Gravity.START);editor.setPadding(dp(12),dp(10),dp(12),dp(10));editor.setBackground(round(SURFACE,14,Color.TRANSPARENT,0));editor.setHorizontallyScrolling(true);editor.setHorizontalScrollBarEnabled(true);try{editor.setText(MarkdownRenderer.highlight(new String(readFile(f),StandardCharsets.UTF_8),"markdown",neonTheme,lightTheme));}catch(Exception e){editor.setText("");toast("读取失败："+e.getMessage());}
        final boolean[] styling={false};final Runnable[] styleTask={null};editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence c,int a,int b,int d){}public void onTextChanged(CharSequence c,int a,int b,int d){if(styling[0])return;if(styleTask[0]!=null)uiHandler.removeCallbacks(styleTask[0]);styleTask[0]=()->{int st=editor.getSelectionStart(),en=editor.getSelectionEnd();String raw=editor.getText().toString();styling[0]=true;editor.setText(MarkdownRenderer.highlight(raw,"markdown",neonTheme,lightTheme));int len=editor.length();editor.setSelection(Math.max(0,Math.min(st,len)),Math.max(0,Math.min(en,len)));styling[0]=false;};uiHandler.postDelayed(styleTask[0],220);}public void afterTextChanged(Editable e){}});panel.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout foot=hbox();foot.setGravity(Gravity.CENTER_VERTICAL);TextView del=text("删除",10.5f,RED);del.setGravity(Gravity.CENTER);del.setOnClickListener(v->confirmDeleteSkill(f,d));foot.addView(del,lp(dp(64),dp(38)));View spacer=new View(this);foot.addView(spacer,new LinearLayout.LayoutParams(0,1,1));TextView attach=smallPill("保存并附加",GREEN);attach.setGravity(Gravity.CENTER);attach.setOnClickListener(v->{try{String body=editor.getText().toString();writeFileBytes(f,body.getBytes(StandardCharsets.UTF_8));pendingAttachments.add(new Attachment("skill:"+f.getParentFile().getName(),body));rebuildAttachmentStrip();toast("已保存并附加");d.dismiss();}catch(Exception e){toast("保存失败："+e.getMessage());}});foot.addView(attach,lp(dp(104),dp(38)));TextView save=pill("保存",TEXT);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->{try{writeFileBytes(f,editor.getText().toString().getBytes(StandardCharsets.UTF_8));toast("Skill 已保存");}catch(Exception e){toast("保存失败："+e.getMessage());}});foot.addView(save,lp(dp(72),dp(38)));panel.addView(foot,lp(-1,dp(48)));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(720),(int)(getResources().getDisplayMetrics().widthPixels*.96f)),Math.min(dp(820),(int)(getResources().getDisplayMetrics().heightPixels*.90f)));
    }

    private void confirmDeleteSkill(File f,Dialog editorDialog){
        showAnimatedAlert(new AlertDialog.Builder(this).setTitle("删除 Skill？").setMessage(f.getParentFile().getName()+"\\n删除后无法撤销。").setNegativeButton("取消",null).setPositiveButton("删除",(x,w)->{deleteRecursive(f.getParentFile());if(editorDialog!=null)editorDialog.dismiss();toast("Skill 已删除");showSkillManager();}).create());
    }

    private void launchImagePicker(){
        try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,REQ_PICK_IMAGE);}catch(Exception e){toast("无法打开图片选择器："+e.getMessage());}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQ_PICK_IMAGE||resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}io.execute(()->{try{String detected=getContentResolver().getType(uri);final String mime=(detected==null||!detected.startsWith("image/"))?"image/jpeg":detected;final String name=queryDisplayName(uri);byte[] bytes=readUriBytes(uri,12*1024*1024);if(bytes.length>10*1024*1024)throw new IllegalArgumentException("图片超过 10 MB，请选择较小图片");final Attachment a=new Attachment(name,mime,bytes);ui(()->{pendingAttachments.add(a);rebuildAttachmentStrip();toast("已添加图片："+name);});}catch(Exception e){final String msg=e.getMessage();ui(()->toast("图片读取失败："+msg));}});
    }

    private String queryDisplayName(Uri uri){String name="image";Cursor c=null;try{c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}finally{if(c!=null)c.close();}return name==null?"image":name;}
    private byte[] readUriBytes(Uri uri,int hardLimit)throws Exception{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new Exception("无法读取图片");byte[] buf=new byte[32768];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>hardLimit)throw new IllegalArgumentException("图片过大");out.write(buf,0,n);}return out.toByteArray();}}

    private List<File> discoverSkills() {
        List<File> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        File[] roots = { new File(config.projectDirectory,".iq/skills"), new File(TermuxConstants.TERMUX_HOME_DIR_PATH,".iq/skills") };
        for (File root : roots) {
            File[] dirs = root.listFiles(); if (dirs == null) continue;
            for (File dir : dirs) {
                File skill = dir.isDirectory() ? new File(dir,"SKILL.md") : null;
                if (skill == null || !skill.isFile()) continue;
                try {
                    if (!seen.add(skill.getCanonicalPath())) continue;
                } catch (Exception ignored) {
                    if (!seen.add(skill.getAbsolutePath())) continue;
                }
                out.add(skill);
            }
        }
        out.sort((a,b)->a.getParentFile().getName().compareToIgnoreCase(b.getParentFile().getName()));
        return out;
    }

    private String relativeProjectPath(File f) {
        try {
            String root = new File(config.projectDirectory).getCanonicalPath(); String p = f.getCanonicalPath();
            if (p.startsWith(root + File.separator)) return p.substring(root.length()+1);
            return p;
        } catch (Exception e) { return f.getPath(); }
    }

    private void showModelPicker() {
        final long generation=modelCatalogGeneration.incrementAndGet();
        if(modelCatalogFuture!=null)modelCatalogFuture.cancel(true);
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        ApiProfile active=settingsStore.getProfile(config.profileId);if(active==null)active=settingsStore.getActiveProfile();
        TextView h=text("选择模型 · "+active.name,14,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(h,lp(-1,dp(38)));
        TextView status=text("正在从当前 API 获取模型…",10.5f,MUTED);panel.addView(status,lp(-1,dp(32)));
        LinearLayout models=vbox();ScrollView scroll=new ScrollView(this);scroll.addView(models);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(310)));
        EditText manual=input(config.model,false);manual.setHint("手动输入模型名");panel.addView(manual,lp(-1,dp(46)));
        LinearLayout footer=hbox();footer.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        TextView manage=smallPill("管理 API",MUTED);manage.setGravity(Gravity.CENTER);manage.setOnClickListener(v->{d.dismiss();showApiProfileManager();});footer.addView(manage,lp(dp(92),dp(40)));
        TextView use=pill("使用模型",TEXT);use.setGravity(Gravity.CENTER);use.setOnClickListener(v->{String m=manual.getText().toString().trim();if(!m.isEmpty()){applySelectedModel(m);d.dismiss();}});footer.addView(use,lp(dp(96),dp(40)));panel.addView(footer,lp(-1,dp(48)));
        d.setOnDismissListener(x->{modelCatalogGeneration.incrementAndGet();if(modelCatalogFuture!=null)modelCatalogFuture.cancel(true);});
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(500),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),Math.min(dp(560),(int)(getResources().getDisplayMetrics().heightPixels*.82f)));
        SessionConfig catalogConfig=settingsStore.resolveProfile(active.id,config);
        modelCatalogFuture=io.submit(()->{
            try{
                List<ModelDescriptor> fetched=modelCatalogClient.fetch(catalogConfig,()->generation!=modelCatalogGeneration.get()||Thread.currentThread().isInterrupted());
                ui(()->{if(generation!=modelCatalogGeneration.get()||!d.isShowing())return;models.removeAllViews();status.setText(fetched.isEmpty()?"API 未返回可用模型，可手动输入":"已获取 "+fetched.size()+" 个模型");for(ModelDescriptor model:fetched){TextView row=smallPill(model.displayName+(model.displayName.equals(model.id)?"":"\n"+model.id),model.id.equals(config.model)?ACCENT:MUTED);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),0,dp(10),0);row.setOnClickListener(v->{manual.setText(model.id);manual.setSelection(manual.length());});models.addView(row,lp(-1,dp(48)));}});
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            catch(Exception error){ui(()->{if(generation!=modelCatalogGeneration.get()||!d.isShowing())return;status.setText(friendlyCatalogError(error));});}
        });
    }

    private void applySelectedModel(String model){
        config.model=model;
        try{
            ApiProfile profile=settingsStore.getProfile(config.profileId);if(profile==null)profile=settingsStore.getActiveProfile();profile.defaultModel=model;settingsStore.saveProfile(profile,"",false);config=settingsStore.resolveProfile(profile.id,config);settingsStore.selectProfile(profile.id);
            SessionRuntime rt=activeRuntime;if(rt!=null){rt.nextConfig=config.copy();if(rt.busy||rt.engine.isBusy())rt.pendingProfileId=profile.id;else{rt.boundProfileId=profile.id;rt.pendingProfileId="";rt.engine.configure(config.copy());}}
        }catch(Exception e){toast("模型保存失败："+(e.getMessage()==null?"":e.getMessage()));return;}
        updateComposerChips();
    }

    private String friendlyCatalogError(Exception error){
        String message=error==null||error.getMessage()==null?"":error.getMessage();
        if(message.length()>120)message=message.substring(0,120)+"…";
        return message.isEmpty()?"无法获取模型，可手动输入":message+"；可手动输入或管理 API";
    }

    private void showApiProfileManager(){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        List<ApiProfile> profiles=settingsStore.getProfiles();String activeId=config.profileId;
        LinearLayout head=hbox();head.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("API 配置",14,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);head.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));TextView add=pill("＋ 新增",TEXT);add.setGravity(Gravity.CENTER);add.setOnClickListener(v->{d.dismiss();showApiProfileEditor(null);});head.addView(add,lp(dp(88),dp(36)));panel.addView(head,lp(-1,dp(44)));
        if (config.apiKey == null || config.apiKey.trim().isEmpty()) {
            TextView hintTop=text("首次使用请打开“IQ Code 官方 API”的编辑，填写 API Key；也可以新增其他兼容 API。",9.8f,MUTED_2);
            hintTop.setPadding(dp(4),0,dp(4),dp(8));
            panel.addView(hintTop,lp(-1,-2));
        }
        LinearLayout rows=vbox();ScrollView scroll=new ScrollView(this);scroll.addView(rows);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        for(ApiProfile profile:profiles){
            LinearLayout row=hbox();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(5),dp(5),dp(5));row.setBackground(round(profile.id.equals(activeId)?SURFACE_3:SURFACE,15,profile.id.equals(activeId)?ACCENT:BORDER,1));
            TextView info=text((profile.id.equals(activeId)?"● ":"")+profile.name+"\n"+profile.protocol+" · "+shorten(profile.defaultModel,28),10.7f,profile.id.equals(activeId)?ACCENT:TEXT);info.setGravity(Gravity.CENTER_VERTICAL);info.setOnClickListener(v->{applyApiProfile(profile.id);d.dismiss();});row.addView(info,new LinearLayout.LayoutParams(0,dp(58),1));
            TextView edit=smallPill("编辑",MUTED);edit.setGravity(Gravity.CENTER);edit.setOnClickListener(v->{d.dismiss();showApiProfileEditor(profile);});row.addView(edit,lp(dp(62),dp(38)));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(66));rp.setMargins(0,dp(4),0,dp(4));rows.addView(row,rp);
        }
        TextView hint=text("点按配置用于当前会话；任务运行中切换将在下一完整轮生效。模型按钮会从当前 API 获取模型。",9.5f,MUTED_2);hint.setPadding(dp(4),dp(8),dp(4),0);panel.addView(hint,lp(-1,dp(58)));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(500),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),Math.min(dp(610),(int)(getResources().getDisplayMetrics().heightPixels*.84f)));
    }

    private void showApiProfileEditor(ApiProfile existing){
        final ApiProfile draft=existing==null?new ApiProfile():existing.copy();
        final boolean official=ApiSettingsStore.OFFICIAL_PROFILE_ID.equals(draft.id);
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView title=text(official?"IQ Code 官方 API":existing==null?"新增 API 配置":"编辑 API 配置",14,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(title,lp(-1,dp(40)));
        EditText name=input(draft.name,false);name.setHint("配置名称");if(!official)panel.addView(labeled("名称",name));
        String[] protocols={"Anthropic Messages","OpenAI Chat","OpenAI Responses","Codex Responses"};String[] values={"anthropic","openai-chat","openai-responses","codex-responses"};Spinner protocol=darkSpinner(protocols,values,draft.protocol);panel.addView(labeled("协议",protocol));
        EditText endpoint=input(draft.baseUrl,false);endpoint.setHint("https://api.example.com");if(!official)panel.addView(labeled("API Base URL",endpoint));
        EditText key=input("",true);key.setHint(existing==null||official?"请填写 API Key":"留空保持已保存密钥不变");
        if(official){
            LinearLayout keyRow=hbox();keyRow.setGravity(Gravity.CENTER_VERTICAL);keyRow.addView(key,new LinearLayout.LayoutParams(0,dp(46),1));
            TextView getKey=smallPill("获取 Key",ACCENT);getKey.setGravity(Gravity.CENTER);getKey.setContentDescription("在浏览器中打开 IQ Code 官方 API");getKey.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ApiSettingsStore.OFFICIAL_SIGNUP_URL)));}catch(Exception error){toast("无法打开浏览器");}});
            LinearLayout.LayoutParams getKeyLp=lp(dp(82),dp(40));getKeyLp.setMargins(dp(8),0,0,0);keyRow.addView(getKey,getKeyLp);panel.addView(labeled("API Key",keyRow));
        }else panel.addView(labeled("API Key",key));
        EditText model=input(draft.defaultModel,false);model.setHint("默认模型（可稍后自动获取）");if(!official)panel.addView(labeled("默认模型",model));
        LinearLayout actions=hbox();actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        if(existing!=null&&!official&&settingsStore.getProfiles().size()>1){TextView delete=text("删除",10.5f,RED);delete.setGravity(Gravity.CENTER);delete.setOnClickListener(v->{try{boolean current=existing.id.equals(config.profileId);settingsStore.deleteProfile(existing.id);if(current)applyApiProfile(settingsStore.getActiveProfileId());d.dismiss();showApiProfileManager();}catch(Exception e){toast((e.getMessage()==null?"":e.getMessage()));}});actions.addView(delete,lp(dp(70),dp(40)));}
        TextView cancel=text("取消",10.5f,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->d.dismiss());actions.addView(cancel,lp(dp(70),dp(40)));
        TextView save=pill("保存并使用",TEXT);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->{
            try{draft.name=name.getText().toString().trim();draft.protocol=values[Math.max(0,protocol.getSelectedItemPosition())];draft.baseUrl=endpoint.getText().toString().trim();draft.defaultModel=model.getText().toString().trim();String replacement=key.getText().toString();ApiProfile saved=settingsStore.saveProfile(draft,replacement,!replacement.isEmpty());applyApiProfile(saved.id);d.dismiss();}
            catch(Exception e){toast("保存失败："+(e.getMessage()==null?"":e.getMessage()));}
        });actions.addView(save,lp(dp(112),dp(40)));panel.addView(actions,lp(-1,dp(48)));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(500),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
    }

    private void applyApiProfile(String profileId){
        SessionRuntime rt=activeRuntime;SessionConfig base=rt!=null&&rt.nextConfig!=null?rt.nextConfig:config;
        SessionConfig resolved=settingsStore.resolveProfile(profileId,base);
        if(resolved==null){toast("API 配置不存在");return;}
        settingsStore.selectProfile(profileId);config=resolved.copy();modelCatalogGeneration.incrementAndGet();if(modelCatalogFuture!=null)modelCatalogFuture.cancel(true);
        if(rt!=null){rt.nextConfig=resolved.copy();if(rt.busy||rt.engine.isBusy())rt.pendingProfileId=profileId;else{rt.boundProfileId=profileId;rt.pendingProfileId="";rt.engine.configure(resolved.copy());}}
        updateComposerChips();toast(rt!=null&&(rt.busy||rt.engine.isBusy())?"API 配置将在下一完整轮生效":"已切换 API 配置");
        if (apiSetupPending && config.apiKey != null && !config.apiKey.trim().isEmpty()) {
            uiHandler.postDelayed(this::continueFirstLaunchSetup, 180L);
        }
    }

    private String currentProfileName(){
        String id=config==null?"":config.profileId;
        for(ApiProfile profile:settingsStore.getProfiles())if(profile.id.equals(id))return profile.name;
        return "未绑定 API";
    }

    private LinearLayout conversationHost(){
        return currentConversationHost==null?chatMessages:currentConversationHost;
    }

    private void addChatRootView(View child,ViewGroup.LayoutParams params){
        if(chatMessages==null||child==null)return;
        int index=chatImeSpacer!=null&&chatImeSpacer.getParent()==chatMessages?chatMessages.indexOfChild(chatImeSpacer):chatMessages.getChildCount();
        chatMessages.addView(child,index,params);
    }

    private LinearLayout beginConversation(){
        LinearLayout group=vbox();
        group.setPadding(dp(10),dp(8),dp(10),dp(8));
        group.setBackground(round(SURFACE_2,14,BORDER_SOFT,1));
        LinearLayout.LayoutParams groupParams=new LinearLayout.LayoutParams(-1,-2);
        groupParams.setMargins(0,dp(6),0,dp(12));
        int index=chatImeSpacer!=null&&chatImeSpacer.getParent()==chatMessages?chatMessages.indexOfChild(chatImeSpacer):chatMessages.getChildCount();
        chatMessages.addView(group,index,groupParams);
        currentConversationHost=vbox();
        group.addView(currentConversationHost,lp(-1,-2));
        return currentConversationHost;
    }

    private void addChatView(ChatItem item) {
        if (chatMessages == null) return;
        LinearLayout destination;
        if(item.type==ChatItem.USER){currentConversationHost=null;destination=chatMessages;}
        else destination=currentConversationHost==null?beginConversation():currentConversationHost;
        if (item.type == ChatItem.USER) {
            LinearLayout row = hbox(); row.setGravity(Gravity.RIGHT);
            LinearLayout bubble = vbox(); bubble.setPadding(dp(8),dp(7),dp(8),item.body.length()>0?dp(8):dp(7)); bubble.setBackground(round(USER_BG, 8, BORDER_SOFT, 1));
            int bubbleMax = wide ? dp(520) : (int)(getResources().getDisplayMetrics().widthPixels * .82f);
            if (!item.images.isEmpty()) bubble.addView(buildSentImagePreview(item.images, bubbleMax));
            if (item.body.length() > 0) {
                TextView copy = text(item.body.toString(), 14, TEXT); copy.setTextIsSelectable(true); copy.setPadding(dp(7), item.images.isEmpty()?dp(2):dp(8), dp(7), dp(2)); copy.setMaxWidth(bubbleMax); bubble.addView(copy, lp(-1,-2));
            }
            LinearLayout userStack = vbox(); userStack.setGravity(Gravity.RIGHT);
            userStack.addView(bubble, lp(-2,-2));
            TextView actions = text("⋯", 15f, MUTED_2);
            actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); actions.setPadding(dp(8),dp(3),dp(4),0);
            actions.setOnClickListener(v -> showMessageActions(item));
            bubble.setOnLongClickListener(v -> { showMessageActions(item); return true; });
            userStack.addView(actions, lp(-2,dp(24)));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.setMargins(dp(34),dp(5),0,dp(5)); row.addView(userStack,bp); addChatRootView(row,lp(-1,-2));
            UiMotion.bindInteractive(row);
            if (!rebuildingTranscript) UiMotion.messageIn(row, true);
            return;
        }
        if (item.type == ChatItem.TOOL) {
            CollapsedToolActivity activity=collapsedToolActivity(item);
            if(activity!=null){
                attachToolToCollapsedActivity(item);
                if(!activity.members.isEmpty()&&activity.members.get(0)==item&&activity.renderedView==null)addCollapsedToolActivity(activity,destination);
                return;
            }
            addToolCard(item,destination); return;
        }
        if (item.type == ChatItem.RESULT) { addToolCard(item,destination); return; }

        boolean grouped=destination!=chatMessages;
        LinearLayout block = vbox(); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,grouped?dp(2):dp(6),0,grouped?dp(4):dp(12)); block.setLayoutParams(p);
        if(item.type==ChatItem.ASSISTANT){
            block.setPadding(grouped?dp(4):dp(10),grouped?dp(3):dp(8),grouped?dp(4):dp(10),grouped?dp(3):dp(8));
            if(!grouped)block.setBackground(round(SURFACE_2,14,BORDER_SOFT,1));
        }
        if(item.type!=ChatItem.ASSISTANT){
            LinearLayout heading = hbox(); heading.setGravity(Gravity.CENTER_VERTICAL);
            if (item.type == ChatItem.ERROR) {
                TextView dot = text("!", 13, RED); dot.setGravity(Gravity.CENTER); heading.addView(dot, lp(dp(24),dp(26)));
            }
            TextView name = text(item.title, 12, item.type == ChatItem.ERROR ? RED : TEXT); name.setTypeface(Typeface.DEFAULT_BOLD); heading.addView(name, new LinearLayout.LayoutParams(0,dp(26),1));
            TextView more = text("⋯", 15f, MUTED_2); more.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); more.setPadding(dp(8),0,dp(2),0); more.setOnClickListener(v -> showMessageActions(item)); heading.addView(more, lp(dp(36),dp(26)));
            block.addView(heading);
        }
        block.setOnLongClickListener(v -> { showMessageActions(item); return true; });
        LinearLayout bodyHost = vbox();
        bodyHost.setPadding(item.type == ChatItem.ERROR ? dp(24) : 0,dp(2),dp(4),dp(4));
        if (item == streamingItem) {
            TextView body = text(item.body.toString(), 14, TEXT);
            body.setText(MarkdownRenderer.inlineText(item.body.toString(),neonTheme,lightTheme), TextView.BufferType.SPANNABLE);
            body.setTextIsSelectable(true); body.setLineSpacing(dp(1),1.10f); body.setPadding(0,0,0,dp(4));
            bodyHost.addView(body, lp(-1,-2));
            streamingView = body; streamingBodyHost = bodyHost;
        } else {
            LinearLayout rich = MarkdownRenderer.render(this, item.body.toString(), 14f, neonTheme, lightTheme);
            bodyHost.addView(rich, lp(-1,-2));
        }
        if (item != streamingItem) addContextFooter(bodyHost, item, false);
        block.addView(bodyHost, lp(-1,-2));
        item.renderedView=block;destination.addView(block);
        UiMotion.bindInteractive(block);
        if (!rebuildingTranscript) UiMotion.messageIn(block, false);
    }

    private void addAssistantThinking(LinearLayout host,ChatItem item){
        LinearLayout box=vbox();box.setPadding(dp(10),dp(6),dp(10),dp(6));box.setBackground(round(SURFACE_3,12,Color.TRANSPARENT,0));
        TextView label=text("⌁  思考过程  ›",10.5f,ACCENT);label.setTypeface(Typeface.DEFAULT_BOLD);box.addView(label,lp(-1,dp(22)));
        TextView detail=text("正在组织回答…",10.3f,MUTED);detail.setMaxLines(5);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);detail.setLineSpacing(0,1.08f);detail.setVisibility(View.GONE);box.addView(detail,lp(-1,-2));
        item.thinkingHost=box;item.thinkingLabel=label;item.thinkingView=detail;box.setOnClickListener(v->{item.thinkingExpanded=!item.thinkingExpanded;refreshAssistantThinking(item);});refreshAssistantThinking(item);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,0,dp(4),dp(8));host.addView(box,bp);
    }

    private void refreshAssistantThinking(ChatItem item){
        if(item==null||item.thinkingView==null||item.thinkingHost==null)return;
        String value=item.thinking.toString().trim();boolean available=!value.isEmpty()||!item.processSteps.isEmpty()||item==streamingItem;item.thinkingHost.setVisibility(available?View.VISIBLE:View.GONE);
        if(item.thinkingLabel!=null)item.thinkingLabel.setText((item.thinkingExpanded?"⌁  思考过程  ⌄":"⌁  思考过程  ›")+(item==streamingItem?" · 进行中":""));
        StringBuilder shown=new StringBuilder();for(String step:item.processSteps){if(shown.length()>0)shown.append('\n');shown.append("· ").append(step);}if(shown.length()==0)shown.append(item==streamingItem?"正在分析请求…":"本轮执行过程已完成");
        item.thinkingView.setText(shown.toString());item.thinkingView.setVisibility(item.thinkingExpanded?View.VISIBLE:View.GONE);
    }

    private void recordAssistantProcessStep(ChatItem item,String step){
        if(item==null||step==null||step.trim().isEmpty())return;String value=step.trim();if(!item.processSteps.isEmpty()&&value.equals(item.processSteps.get(item.processSteps.size()-1)))return;if(item.processSteps.size()>=12)item.processSteps.remove(0);item.processSteps.add(value);refreshAssistantThinking(item);
    }

    /** Claude Code-style per-reply context indicator: current replay context, never cumulative billing. */
    private void addContextFooter(LinearLayout host, ChatItem item, boolean animate) {
        if (host == null || item == null || item.type != ChatItem.ASSISTANT || item.contextTokens < 0) return;
        int window = item.contextWindowTokens > 0 ? item.contextWindowTokens : Math.max(16_000, config.contextWindowTokens);
        int usedPct = Math.max(0, Math.min(999, (int)Math.round(item.contextTokens * 100.0 / window)));
        int remaining = Math.max(0, 100 - Math.min(100, usedPct));
        String label = (item.contextApiMeasured ? "上下文 " : "约上下文 ")
            + formatTokenCount(item.contextTokens) + " / " + formatTokenCount(window)
            + " · 剩余 " + remaining + "%";
        TextView meta = text(label, 9.3f, usedPct >= 90 ? RED : (usedPct >= 72 ? ACCENT : MUTED_2));
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setPadding(0, dp(5), dp(3), dp(1));
        meta.setContentDescription("当前会话上下文占用；不是累计 API 消费");
        host.addView(meta, lp(-1, dp(24)));
        if (animate && !rebuildingTranscript) UiMotion.contentUpdated(meta, false);
    }

    private void stampCurrentContext(ChatItem item, IQCodeEngine source) {
        if (item == null || source == null || item.type != ChatItem.ASSISTANT) return;
        item.contextTokens = source.estimateContextTokens();
        item.contextWindowTokens = Math.max(16_000, config.contextWindowTokens);
        item.contextApiMeasured = source.hasMeasuredContextUsage();
    }

    private void showMessageActions(ChatItem item) {
        if (item == null) return;
        if (item.type == ChatItem.USER) {
            if(item.humanMessage){
                showChoicePicker("消息操作",new String[]{"复制","编辑此消息","删除此消息"},-1,which->{
                    if(which==0)copyMessageText(item);else if(which==1)showEditMessageDialog(item);else confirmDeleteMessage(item);
                });
            }else{
                showChoicePicker("消息操作",new String[]{"复制","再次发送","编辑后发送"},-1,which->{if(which==0)copyMessageText(item);else if(which==1)resendUserMessage(item);else stageMessageForEdit(item);});
            }
            return;
        }
        if (item.type == ChatItem.ASSISTANT || item.type == ChatItem.ERROR) {
            showChoicePicker("消息操作", new String[]{"复制", "重试上一问"}, -1, which -> {
                if (which == 0) copyMessageText(item);
                else {
                    ChatItem previous = previousUserMessage(item);
                    if (previous == null) toast("没有找到可重试的上一条提问");
                    else resendUserMessage(previous);
                }
            });
        }
    }

    private void copyMessageText(ChatItem item) {
        String value = item == null ? "" : item.body.toString();
        copyTextToClipboard("IQ Code message", value);
    }

    private ChatItem previousUserMessage(ChatItem from) {
        int index = transcript.indexOf(from);
        if (index < 0) index = transcript.size();
        for (int i = index - 1; i >= 0; i--) if (transcript.get(i).type == ChatItem.USER) return transcript.get(i);
        return null;
    }

    private void resendUserMessage(ChatItem source) {
        if (source == null || prompt == null) return;
        if (engine != null && engine.isBusy()) { toast("当前会话仍在执行，请先停止后再重试"); return; }
        pendingAttachments.clear(); pendingAttachments.addAll(source.images);
        String replayText=source.persistedBody==null||source.persistedBody.isEmpty()?source.body.toString():source.persistedBody;
        suppressPromptWatcher = true; prompt.setText(replayText); prompt.setSelection(prompt.length()); suppressPromptWatcher = false;
        rebuildAttachmentStrip(); sendPrompt();
    }

    private void stageMessageForEdit(ChatItem source) {
        if (source == null || prompt == null) return;
        pendingAttachments.clear(); pendingAttachments.addAll(source.images);
        String replayText=source.persistedBody==null||source.persistedBody.isEmpty()?source.body.toString():source.persistedBody;
        suppressPromptWatcher = true; prompt.setText(replayText); prompt.setSelection(prompt.length()); suppressPromptWatcher = false;
        rebuildAttachmentStrip(); hideSlashPalette(); prompt.requestFocus();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(prompt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void showEditMessageDialog(ChatItem source){
        if(source==null||source.messageContentHash.trim().isEmpty()||!canMutateActiveHistory())return;
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView title=text("编辑此消息",14,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(title,lp(-1,dp(40)));
        TextView warning=text("这会直接修改当前会话。后续回答会保留，但它们仍可能基于原文字；已执行的文件修改、命令和其他副作用不会回滚。",9.8f,ACCENT);warning.setLineSpacing(0,1.12f);panel.addView(warning,lp(-1,-2));
        String persistedText=source.persistedBody==null||source.persistedBody.isEmpty()?source.body.toString():source.persistedBody;
        EditText edited=input(persistedText,false);edited.setGravity(Gravity.TOP|Gravity.START);edited.setMinLines(4);edited.setMaxLines(10);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(190));ep.setMargins(0,dp(10),0,dp(8));panel.addView(edited,ep);
        LinearLayout actions=hbox();actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView cancel=text("取消",10.5f,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->d.dismiss());actions.addView(cancel,lp(dp(70),dp(40)));
        TextView save=pill("保存修改",TEXT);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->{String value=edited.getText().toString().trim();if(value.isEmpty()){toast("消息不能为空");return;}d.dismiss();mutateActiveMessage(source,value,false);});actions.addView(save,lp(dp(100),dp(40)));panel.addView(actions,lp(-1,dp(48)));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(520),(int)(getResources().getDisplayMetrics().widthPixels*.94f)),-2);
    }

    private void confirmDeleteMessage(ChatItem source){
        if(source==null||source.messageContentHash.trim().isEmpty()||!canMutateActiveHistory())return;
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView title=text("删除此消息？",14,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(title,lp(-1,dp(40)));TextView warning=text("只删除这条用户消息，前后回答都会保留，但后续回答可能失去原上下文；已执行的文件和命令副作用不会回滚。",9.8f,ACCENT);panel.addView(warning,lp(-1,-2));
        LinearLayout actions=hbox();actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView cancel=text("取消",10.5f,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->d.dismiss());actions.addView(cancel,lp(dp(70),dp(40)));TextView remove=text("删除消息",10.5f,RED);remove.setGravity(Gravity.CENTER);remove.setOnClickListener(v->{d.dismiss();mutateActiveMessage(source,"",true);});actions.addView(remove,lp(dp(92),dp(40)));panel.addView(actions,lp(-1,dp(48)));d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(460),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
    }

    private boolean canMutateActiveHistory(){
        SessionRuntime rt=activeRuntime;if(rt==null||rt.file==null){toast("当前会话尚未保存");return false;}
        if(rt.busy||rt.engine.isBusy()||rt.planState.isAwaitingApproval()){toast("当前会话仍在执行，请先停止后再修改历史");return false;}
        return true;
    }

    private void mutateActiveMessage(ChatItem source,String replacement,boolean delete){
        SessionRuntime rt=activeRuntime;File file=rt==null?null:rt.engine.getSessionFile();long generation=activeRuntimeGeneration;
        if(file==null){toast("当前会话尚未保存");return;}
        if(source==null||source.messageContentHash.trim().isEmpty()){
            toast("这条消息缺少有效历史校验，请重新打开会话后再试");return;
        }
        SessionStore.MessageReference reference=new SessionStore.MessageReference(source.messageId,source.legacyRowIndex,source.messageContentHash);
        io.submit(()->{try{
            if(delete)SessionStore.deleteHumanMessage(file,reference);else SessionStore.editHumanMessage(file,reference,replacement);
            ui(()->{
                if(activeRuntime!=rt||activeRuntimeGeneration!=generation||!runtimeKey(file).equals(runtimeKey(activeRuntime==null?null:activeRuntime.file))){
                    refreshVisibleSessionRowsFromDiskAsync();
                    toast("历史已修改；当前已切换到其他会话");
                    return;
                }
                reloadSessionRuntimeAfterMutation(file,delete?"消息已删除":"消息已修改");
            });
        }catch(Exception error){ui(()->toast("修改历史失败："+(error.getMessage()==null?"":error.getMessage())));}});
    }

    private void reloadSessionRuntimeAfterMutation(File file,String message){
        String key=runtimeKey(file);SessionRuntime old=sessionRuntimes.get(key);SessionRuntime replacement;
        try{replacement=buildRuntimeForExisting(file);}
        catch(Exception error){
            if(old!=null)try{old.engine.resumeConversation(file);activateRuntime(old,true);currentView=VIEW_CHAT;styleViewTabs();renderChat(primaryHost,false);refreshVisibleSessionRows();refreshVisibleSessionRowsFromDiskAsync();toast(message);return;}
            catch(Exception fallback){old.engine.shutdown();sessionRuntimes.remove(key,old);}
            toast("重新加载会话失败："+(error.getMessage()==null?"":error.getMessage()));return;
        }
        sessionRuntimes.put(key,replacement);activateRuntime(replacement,true);
        if(old!=null&&old!=replacement)old.engine.shutdown();
        currentView=VIEW_CHAT;styleViewTabs();renderChat(primaryHost,false);refreshVisibleSessionRows();refreshVisibleSessionRowsFromDiskAsync();toast(message);
    }

    private View buildSentImagePreview(List<Attachment> images, int maxWidth) {
        if (images.size() == 1) {
            Attachment a = images.get(0);
            Bitmap bm = decodeSampledBitmap(a.bytes, maxWidth, dp(360));
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(SURFACE, 16, Color.TRANSPARENT, 0)); image.setClipToOutline(true);
            if (bm != null) image.setImageBitmap(bm);
            int targetW=maxWidth;
            int targetH=dp(220);
            if(bm!=null && bm.getWidth()>0){targetH=Math.max(dp(120),Math.min(dp(360),(int)((long)targetW*bm.getHeight()/bm.getWidth())));}
            image.setOnClickListener(v -> showFullImage(a));
            image.setContentDescription(a.label);
            return wrapSized(image,targetW,targetH);
        }
        HorizontalScrollView scroll=new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout strip=hbox(); strip.setGravity(Gravity.CENTER_VERTICAL);
        int size=Math.min(dp(156),Math.max(dp(118),maxWidth/2));
        for(Attachment a:images){
            ImageView image=new ImageView(this); Bitmap bm=decodeSampledBitmap(a.bytes,size,size); if(bm!=null)image.setImageBitmap(bm);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(round(SURFACE,14,Color.TRANSPARENT,0)); image.setClipToOutline(true); image.setOnClickListener(v->showFullImage(a)); image.setContentDescription(a.label);
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(size,size); ip.setMargins(0,0,dp(6),0); strip.addView(image,ip);
        }
        scroll.addView(strip,new HorizontalScrollView.LayoutParams(-2,size)); scroll.setLayoutParams(new LinearLayout.LayoutParams(maxWidth,size)); return scroll;
    }

    private View wrapSized(View child,int width,int height){
        FrameLayout frame=new FrameLayout(this); frame.addView(child,new FrameLayout.LayoutParams(-1,-1)); frame.setLayoutParams(new LinearLayout.LayoutParams(width,height)); return frame;
    }

    private Bitmap decodeSampledBitmap(byte[] bytes,int reqW,int reqH){
        if(bytes==null||bytes.length==0)return null;
        try{
            BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true; BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
            int sample=1; while(o.outWidth/sample>reqW*2 || o.outHeight/sample>reqH*2) sample*=2;
            BitmapFactory.Options d=new BitmapFactory.Options(); d.inSampleSize=Math.max(1,sample); d.inPreferredConfig=Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes,0,bytes.length,d);
        }catch(Throwable ignored){return null;}
    }

    private void showFullImage(Attachment a){
        if(a==null||a.bytes==null)return;
        final Dialog d=newOverlayAwareDialog(android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame=new FrameLayout(this); frame.setBackgroundColor(Color.BLACK);
        ImageView image=new ImageView(this); Bitmap bm=decodeSampledBitmap(a.bytes,getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels); if(bm!=null)image.setImageBitmap(bm);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView close=text("×",28,Color.WHITE); close.setGravity(Gravity.CENTER); close.setBackground(round(Color.argb(150,20,20,20),20,Color.TRANSPARENT,0)); close.setOnClickListener(v->d.dismiss());
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.RIGHT); cp.setMargins(0,dp(18),dp(14),0); frame.addView(close,cp);
        d.setContentView(frame); d.show();
        UiMotion.bindInteractive(frame);
        UiMotion.dialogIn(image);
    }

    private void addToolCard(ChatItem item) { addToolCard(item,chatMessages); }

    private void addToolCard(ChatItem item,LinearLayout host) {
        if(host==null)return;
        item.runningView=null;item.liveOutputView=null;
        // IQ Code's TUI keeps tool activity lightweight: one status row, a compact
        // summary, and output only when useful/expanded. Do the same here instead of
        // stacking large bordered cards for call + result.
        if (item.type == ChatItem.RESULT) {
            LinearLayout fallback = vbox();
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1,-2); fp.setMargins(dp(23),dp(1),0,dp(4)); fallback.setLayoutParams(fp);
            TextView result = text("  ⎿  " + shortenMultiline(item.body.toString(), 900), 10.5f, MUTED_2);
            result.setTypeface(Typeface.MONOSPACE); result.setTextIsSelectable(true);
            fallback.addView(result, lp(-1,-2));
            fallback.setOnLongClickListener(v -> { showToolActions(item); return true; });
            host.addView(fallback); item.renderedView = fallback;
            if (!rebuildingTranscript) UiMotion.toolIn(fallback);
            return;
        }

        LinearLayout row = vbox();
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1,-2);
        rp.setMargins(dp(20), dp(1), dp(2), dp(4));
        row.setLayoutParams(rp);

        LinearLayout top = hbox(); top.setGravity(Gravity.CENTER_VERTICAL);
        int statusColor = item.completed ? (item.resultError ? RED : GREEN) : ACCENT;
        String statusGlyph = item.completed ? (item.resultError ? "×" : "✓") : (item.awaitingPermission ? "○" : "●");
        TextView status = text(statusGlyph, 11, statusColor);
        status.setTypeface(Typeface.MONOSPACE); status.setGravity(Gravity.CENTER);
        top.addView(status, lp(dp(23), dp(25)));

        org.json.JSONObject input = null;
        try { input = new org.json.JSONObject(item.body.toString()); } catch (Exception ignored) {}
        String displayName = userFacingToolName(item.title, input);
        TextView name = text(displayName, 11.5f, TEXT); name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, lp(-2, dp(25)));

        String summary = input == null ? "" : toolSummary(item.title, input);
        // Bash already renders the command on the next line; repeating it in the
        // title row makes the activity look noisy and causes the command to appear twice.
        if ("Bash".equalsIgnoreCase(item.title)) summary = "";
        if (!summary.isEmpty() && !summary.equals(displayName)) {
            TextView summaryView = text("  " + summary, 10.5f, MUTED);
            summaryView.setTypeface(Typeface.MONOSPACE); summaryView.setSingleLine(true);
            summaryView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(summaryView, new LinearLayout.LayoutParams(0, dp(25), 1));
        } else {
            top.addView(new View(this), new LinearLayout.LayoutParams(0, dp(25), 1));
        }

        int[] delta = item.completed && (item.diffAddedLines > 0 || item.diffDeletedLines > 0)
            ? new int[]{item.diffAddedLines,item.diffDeletedLines}
            : (input == null ? new int[]{0,0} : toolDelta(item.title, input));
        if (delta[0] > 0) top.addView(diffBadge("+" + delta[0], GREEN, item.completed && !rebuildingTranscript), lp(-2, dp(24)));
        if (delta[1] > 0) top.addView(diffBadge("−" + delta[1], RED, item.completed && !rebuildingTranscript), lp(-2, dp(24)));

        TextView toolMore = text("⋯", 14f, MUTED_2); toolMore.setGravity(Gravity.CENTER);
        toolMore.setOnClickListener(v -> showToolActions(item));
        top.addView(toolMore, lp(dp(30), dp(25)));
        row.setOnLongClickListener(v -> { showToolActions(item); return true; });

        boolean hasDetails = item.result.length() > 0 || item.diff.length() > 0;
        if (item.completed && hasDetails) {
            TextView chevron = text(item.expanded ? "⌃" : "⌄", 11, MUTED_2); chevron.setGravity(Gravity.CENTER);
            top.addView(chevron, lp(dp(28), dp(25)));
            top.setOnClickListener(v -> {
                item.expanded = !item.expanded;
                int y = chatScroll == null ? 0 : chatScroll.getScrollY();
                boolean follow = chatAutoFollow;
                refreshToolItem(item);
                if (!follow && chatScroll != null) chatScroll.post(() -> chatScroll.scrollTo(0, y));
            });
        }
        row.addView(top, lp(-1,-2));

        if ("Bash".equalsIgnoreCase(item.title) && input != null) {
            String command = input.optString("command", "").trim();
            if (!command.isEmpty()) {
                TextView cmd = text(item.expanded ? command : truncateCommand(command), 10.5f, TEXT);
                cmd.setTypeface(Typeface.MONOSPACE); cmd.setTextIsSelectable(true);
                cmd.setPadding(dp(23), 0, dp(4), dp(2));
                cmd.setLineSpacing(0, 1.06f);
                row.addView(cmd, lp(-1,-2));
            }
        }

        if (item.completed && hasDetails) {
            String resultText = item.result.toString();
            if (item.expanded) {
                if (item.diff.length() > 0) {
                    TextView diff = text("", 10.2f, TEXT);
                    diff.setText(colorDiff(shortenMultiline(item.diff.toString(), 140000)));
                    diff.setTypeface(Typeface.MONOSPACE); diff.setTextIsSelectable(true);
                    diff.setHorizontallyScrolling(true); diff.setHorizontalScrollBarEnabled(true);
                    diff.setPadding(dp(10), dp(8), dp(10), dp(9)); diff.setLineSpacing(0,1.04f);
                    diff.setBackground(round(TERMINAL_BG, 8, Color.TRANSPARENT, 0));
                    HorizontalScrollView diffScroll = new HorizontalScrollView(this);
                    diffScroll.setHorizontalScrollBarEnabled(true); diffScroll.setFillViewport(false);
                    diffScroll.addView(diff, new HorizontalScrollView.LayoutParams(-2,-2));
                    LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(-1,-2); dpv.setMargins(dp(23),dp(3),0,dp(4));
                    row.addView(diffScroll,dpv);
                }
                if (!resultText.trim().isEmpty()) {
                    String output = shortenMultiline(resultText, 12000);
                    LinearLayout outputPanel = vbox();
                    outputPanel.setPadding(dp(23), dp(3), dp(4), dp(5));
                    outputPanel.setBackground(round(TERMINAL_BG, 10, Color.TRANSPARENT, 0));
                    if (output.contains("```") && !item.resultError) {
                        // Agent outputs frequently contain fenced source. Reuse the
                        // full Markdown renderer so expanded tool results get the
                        // same language labels, copy action, wrapping and syntax
                        // colors as normal assistant replies.
                        outputPanel.addView(MarkdownRenderer.render(this, output, 10.5f, neonTheme, lightTheme), lp(-1,-2));
                    } else {
                        TextView body = text("", 10f, item.resultError ? RED : MUTED_2);
                        body.setText(item.resultError ? output : MarkdownRenderer.highlight(output, toolOutputLanguage(item), neonTheme, lightTheme));
                        body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true);
                        body.setLineSpacing(0,1.08f);
                        body.setPadding(dp(8), dp(7), dp(8), dp(7));
                        outputPanel.addView(body, lp(-1,-2));
                    }
                    row.addView(outputPanel,lp(-1,-2));
                }
            } else {
                int changed = item.diffAddedLines + item.diffDeletedLines;
                int lines = countLines(resultText);
                String compact = item.diff.length() > 0
                    ? changed + " 行已修改 · 点按展开"
                    : compactResult(item.title, resultText, lines, item.exitCode);
                TextView info = text("  ⎿  " + compact, 10f, item.resultError ? RED : MUTED_2);
                info.setTypeface(Typeface.MONOSPACE); info.setPadding(dp(23),0,0,dp(2));
                row.addView(info,lp(-1,-2));
            }
        } else if (!item.completed) {
            final boolean bash = "Bash".equalsIgnoreCase(item.title);
            TextView running = text(runningToolLabel(item), 9.5f, item.awaitingPermission ? ACCENT : MUTED_2);
            running.setTypeface(Typeface.MONOSPACE); running.setPadding(dp(23),0,0,dp(2));
            item.runningView=running;
            row.addView(running,lp(-1,-2));
            String live = bash ? liveOutputPreview(item, 7000, wide ? 10 : 7) : liveOutputTail(item, 5000);
            if (!live.isEmpty()) {
                TextView body = text(live, 9.7f, MUTED);
                body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true);
                body.setPadding(dp(9),dp(6),dp(9),dp(6)); body.setBackground(round(TERMINAL_BG,6,Color.TRANSPARENT,0)); item.liveOutputView=body;
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1,-2); bp.setMargins(dp(23),dp(3),0,dp(3)); row.addView(body,bp);
            } else if (bash && !item.awaitingPermission) {
                TextView waiting = text("  ⎿  等待程序输出…", 9.2f, MUTED_2);
                waiting.setTypeface(Typeface.MONOSPACE); waiting.setPadding(dp(23),0,0,dp(2));
                row.addView(waiting, lp(-1,-2));
            }
        }

        host.addView(row); item.renderedView = row;
        if(!item.completed)scheduleToolElapsedTicker();
        UiMotion.bindInteractive(row);
        if (!rebuildingTranscript) UiMotion.toolIn(row);
    }

    private void addCollapsedToolActivity(CollapsedToolActivity activity,LinearLayout host){
        if(activity==null||host==null||activity.members.isEmpty())return;
        LinearLayout root=vbox();
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(dp(16),dp(2),dp(2),dp(5));root.setLayoutParams(rp);
        LinearLayout top=hbox();top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(7),dp(3),dp(4),dp(3));
        int completed=collapsedCompletedCount(activity),failed=collapsedFailedCount(activity);
        boolean done=activity.batchCompleted&&completed>=activity.plan.toolIds.size();
        int color=failed>0?RED:(done?GREEN:ACCENT);
        TextView state=text(done?"✓":"◌",12,color);state.setTypeface(Typeface.MONOSPACE);state.setGravity(Gravity.CENTER);top.addView(state,lp(dp(24),dp(28)));
        TextView label=text(collapsedActivityLabel(activity),11.3f,TEXT);label.setTypeface(Typeface.DEFAULT_BOLD);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(label,new LinearLayout.LayoutParams(0,dp(28),1));
        TextView chevron=text(activity.expanded?"⌃":"⌄",12,MUTED_2);chevron.setGravity(Gravity.CENTER);top.addView(chevron,lp(dp(28),dp(28)));
        View.OnClickListener toggle=v->{activity.expanded=!activity.expanded;refreshCollapsedToolActivity(activity);};
        top.setOnClickListener(toggle);root.setOnClickListener(toggle);root.addView(top,lp(-1,-2));
        String hint=activity.plan.latestHint;
        String detail=hint.isEmpty()?"⎿  "+activity.plan.toolIds.size()+" 项工具 · 点按"+(activity.expanded?"收起":"展开"):"⎿  "+hint+" · 点按"+(activity.expanded?"收起":"展开");
        TextView sub=text(detail,9.7f,MUTED_2);sub.setTypeface(Typeface.MONOSPACE);sub.setPadding(dp(24),0,dp(8),dp(2));sub.setSingleLine(true);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(sub,lp(-1,dp(24)));
        if(activity.expanded){
            LinearLayout children=vbox();children.setPadding(dp(5),dp(1),0,dp(2));
            activity.childrenHost=children;
            boolean wasRebuilding=rebuildingTranscript;rebuildingTranscript=true;
            for(ChatItem item:activity.members)addToolCard(item,children);
            rebuildingTranscript=wasRebuilding;
            if(children.getChildCount()>0)root.addView(children,lp(-1,-2));
        }else{
            activity.childrenHost=null;
            for(ChatItem item:activity.members){item.renderedView=null;item.runningView=null;}
        }
        host.addView(root);activity.renderedView=root;
        root.setContentDescription((done?"已完成":"正在执行")+collapsedActivityLabel(activity)+"，点按"+(activity.expanded?"收起":"展开"));
        UiMotion.bindInteractive(root);
    }

    private String collapsedActivityLabel(CollapsedToolActivity activity){
        ArrayList<String> parts=new ArrayList<>();
        if(activity.plan.searchPatterns>0)parts.add("搜索 "+activity.plan.searchPatterns+" 个模式");
        if(activity.plan.readRequests>0)parts.add("读取 "+activity.plan.readRequests+" 个文件");
        if(activity.plan.locations>0)parts.add("查看 "+activity.plan.locations+" 个位置");
        StringBuilder label=new StringBuilder(activity.batchCompleted?"已":"正在");
        for(int i=0;i<parts.size();i++){if(i>0)label.append("、");label.append(parts.get(i));}
        int failed=collapsedFailedCount(activity);
        int missing=Math.max(0,activity.plan.toolIds.size()-collapsedCompletedCount(activity));
        if(failed>0)label.append(" · ").append(failed).append(" 项失败");
        else if(activity.batchCompleted&&missing>0)label.append(" · ").append(missing).append(" 项未完成");
        return label.toString();
    }

    private int collapsedCompletedCount(CollapsedToolActivity activity){
        int count=0;for(ChatItem item:activity.members)if(item.completed)count++;return count;
    }

    private int collapsedFailedCount(CollapsedToolActivity activity){
        int count=0;for(ChatItem item:activity.members)if(item.completed&&item.resultError)count++;return count;
    }

    private void refreshCollapsedToolActivity(CollapsedToolActivity activity){
        if(activity==null)return;
        View old=activity.renderedView;
        if(old==null){if(chatMessages!=null&&!activity.members.isEmpty())addCollapsedToolActivity(activity,conversationHost());return;}
        if(!(old.getParent() instanceof LinearLayout))return;
        LinearLayout host=(LinearLayout)old.getParent();int index=host.indexOfChild(old);if(index<0)return;
        int oldY=chatScroll==null?0:chatScroll.getScrollY();boolean follow=chatAutoFollow;
        host.removeViewAt(index);activity.renderedView=null;activity.childrenHost=null;
        for(ChatItem item:activity.members){item.renderedView=null;item.runningView=null;}
        boolean wasRebuilding=rebuildingTranscript;rebuildingTranscript=true;addCollapsedToolActivity(activity,host);rebuildingTranscript=wasRebuilding;
        View replacement=activity.renderedView;
        if(replacement!=null&&replacement.getParent()==host){host.removeView(replacement);host.addView(replacement,Math.min(index,host.getChildCount()));UiMotion.contentUpdated(replacement,activity.batchCompleted);}
        if(chatScroll!=null)chatScroll.post(()->{if(follow)scrollChatNow();else chatScroll.scrollTo(0,oldY);});
    }

    private void showToolActions(ChatItem item) {
        if (item == null) return;
        if (item.type == ChatItem.RESULT) {
            showChoicePicker("工具结果", new String[]{"复制输出"}, -1, which -> copyTextToClipboard("IQ Code tool output", item.body.toString()));
            return;
        }
        org.json.JSONObject input = null;
        try { input = new org.json.JSONObject(item.body.toString()); } catch (Exception ignored) { }
        String command = input == null ? "" : input.optString("command", "").trim();
        boolean hasCommand = "Bash".equalsIgnoreCase(item.title) && !command.isEmpty();
        boolean hasFinalOutput = item.result.length() > 0;
        boolean hasLiveOutput; synchronized (item) { hasLiveOutput = item.liveOutput.length() > 0; }
        boolean hasOutput = hasFinalOutput || hasLiveOutput;
        boolean hasDiff = item.diff.length() > 0;
        if (hasCommand && hasOutput) {
            final String cmd = command;
            if (!item.completed) {
                showChoicePicker("工具操作", new String[]{"复制命令", "复制实时输出"}, -1, which -> {
                    if (which == 0) copyTextToClipboard("IQ Code Bash command", cmd);
                    else copyTextToClipboard("IQ Code live tool output", liveOutputTail(item, 40000));
                });
            } else {
                showChoicePicker("工具操作", new String[]{"复制命令", "复制输出", item.expanded ? "折叠输出" : "展开输出"}, -1, which -> {
                    if (which == 0) copyTextToClipboard("IQ Code Bash command", cmd);
                    else if (which == 1) copyTextToClipboard("IQ Code tool output", item.result.toString());
                    else { item.expanded = !item.expanded; refreshToolItem(item); }
                });
            }
        } else if (hasCommand) {
            final String cmd = command;
            showChoicePicker("工具操作", new String[]{"复制命令"}, -1, which -> copyTextToClipboard("IQ Code Bash command", cmd));
        } else if (hasOutput && hasDiff) {
            showChoicePicker("工具操作", new String[]{"复制 Diff", "复制输出", item.expanded ? "折叠修改" : "展开修改"}, -1, which -> {
                if (which == 0) copyTextToClipboard("IQ Code unified diff", item.diff.toString());
                else if (which == 1) copyTextToClipboard("IQ Code tool output", item.result.toString());
                else { item.expanded = !item.expanded; refreshToolItem(item); }
            });
        } else if (hasOutput) {
            showChoicePicker("工具操作", new String[]{"复制输出", item.expanded ? "折叠输出" : "展开输出"}, -1, which -> {
                if (which == 0) copyTextToClipboard("IQ Code tool output", item.result.toString());
                else { item.expanded = !item.expanded; refreshToolItem(item); }
            });
        } else {
            showChoicePicker("工具操作", new String[]{"复制参数"}, -1, which -> copyTextToClipboard("IQ Code tool input", item.body.toString()));
        }
    }

    private void copyTextToClipboard(String label, String value) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value == null ? "" : value));
        toast("已复制");
    }

    private void refreshToolItem(ChatItem item) {
        CollapsedToolActivity activity=collapsedToolActivity(item);
        if(activity!=null){refreshCollapsedToolActivity(activity);return;}
        if(item==null||item.renderedView==null||!(item.renderedView.getParent() instanceof LinearLayout))return;
        LinearLayout host=(LinearLayout)item.renderedView.getParent();
        int index=host.indexOfChild(item.renderedView);if(index<0)return;
        int oldY=chatScroll==null?0:chatScroll.getScrollY();boolean follow=chatAutoFollow;
        host.removeViewAt(index);rebuildingTranscript=true;addToolCard(item,host);rebuildingTranscript=false;
        View replacement=item.renderedView;
        if(replacement!=null&&replacement.getParent()==host){host.removeView(replacement);host.addView(replacement,Math.min(index,host.getChildCount()));UiMotion.contentUpdated(replacement,item.completed);}
        if(chatScroll!=null)chatScroll.post(()->{if(follow)scrollChatNow();else chatScroll.scrollTo(0,oldY);});
    }

    private ChatItem currentRunningBash() {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            ChatItem item = transcript.get(i);
            if (item.type == ChatItem.TOOL && !item.completed && "Bash".equalsIgnoreCase(item.title)) return item;
        }
        return null;
    }

    private long currentToolElapsed(ChatItem item){
        if(item==null)return 0L;
        long local=Math.max(0L,android.os.SystemClock.elapsedRealtime()-item.startedAt);
        synchronized(item){return Math.max(item.elapsedMs,item.completed?item.elapsedMs:local);}
    }

    private String runningToolLabel(ChatItem item){
        if(item.awaitingPermission)return "  ⎿  等待授权…";
        if("Bash".equalsIgnoreCase(item.title)){
            int outChars,errChars;
            synchronized(item){outChars=item.liveStdoutChars;errChars=item.liveStderrChars;}
            return "  ⎿  实时 "+formatElapsed(currentToolElapsed(item))+" · 标准输出 "+formatCharCount(outChars)+" · 错误输出 "+formatCharCount(errChars)+" · 进程运行中";
        }
        return "  ⎿  正在执行 "+formatElapsed(currentToolElapsed(item))+"…";
    }

    private void scheduleToolElapsedTicker(){
        if(toolElapsedTickerPosted)return;
        toolElapsedTickerPosted=true;
        uiHandler.postDelayed(toolElapsedTicker,500L);
    }

    private void refreshToolElapsed(){
        toolElapsedTickerPosted=false;
        boolean running=false;
        for(ChatItem item:transcript){
            if(item.type!=ChatItem.TOOL||item.completed||item.runningView==null||item.runningView.getParent()==null)continue;
            running=true;
            item.runningView.setText(runningToolLabel(item));
        }
        if(running)scheduleToolElapsedTicker();
    }

    private void appendLiveChunk(ChatItem item, String chunk, boolean stderr) {
        if (item == null || chunk == null || chunk.isEmpty()) return;
        String clean = chunk.replace("\r\n", "\n").replace('\r', '\n');
        synchronized (item) {
            if (stderr) item.liveStderrChars += clean.length(); else item.liveStdoutChars += clean.length();
            if (item.liveOutput.length() > 0 && item.liveLastWasStderr != stderr) {
                if (item.liveOutput.charAt(item.liveOutput.length() - 1) != '\n') item.liveOutput.append('\n');
                item.liveOutput.append(stderr ? "[stderr]\n" : "[stdout]\n");
            } else if (item.liveOutput.length() == 0 && stderr) {
                item.liveOutput.append("[stderr]\n");
            }
            item.liveLastWasStderr = stderr;
            item.liveOutput.append(clean);
            final int keep = 40000;
            if (item.liveOutput.length() > keep) item.liveOutput.delete(0, item.liveOutput.length() - keep);
        }
    }

    private String liveOutputTail(ChatItem item, int maxChars) {
        if (item == null) return "";
        synchronized (item) {
            int n = item.liveOutput.length();
            if (n == 0) return "";
            int start = Math.max(0, n - Math.max(1000, maxChars));
            String value = item.liveOutput.substring(start);
            if (start > 0) value = "… earlier output omitted …\n" + value;
            return value;
        }
    }

    /** Returns the newest live lines so the inline Bash row always shows current progress. */
    private String liveOutputPreview(ChatItem item, int maxChars, int maxLines) {
        String value = liveOutputTail(item, maxChars);
        if (value.isEmpty()) return value;
        String[] lines = value.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].isEmpty()) end--;
        int start = Math.max(0, end - Math.max(1, maxLines));
        StringBuilder out = new StringBuilder();
        if (start > 0) out.append("… earlier live output omitted …\n");
        for (int i = start; i < end; i++) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private String formatElapsed(long elapsedMs) {
        long seconds = Math.max(0, elapsedMs) / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60L, seconds % 60L);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds % 60L);
    }

    private String formatCharCount(int chars) {
        if (chars < 1000) return chars + " B";
        if (chars < 1000_000) return String.format(Locale.US, "%.1f KB", chars / 1000f);
        return String.format(Locale.US, "%.1f MB", chars / 1000_000f);
    }

    private TextView diffBadge(String label, int color, boolean animate) {
        TextView b = text(label, 9.5f, color);
        b.setTypeface(Typeface.MONOSPACE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(5), 0, dp(5), 0);
        if (animate) UiMotion.contentUpdated(b,true);
        return b;
    }

    private String userFacingToolName(String name, org.json.JSONObject input) {
        if (name == null) return "Tool";
        if ("Root".equalsIgnoreCase(name)) return "Root 命令";
        if ("Edit".equalsIgnoreCase(name) || "MultiEdit".equalsIgnoreCase(name)) return "修改文件";
        if ("Write".equalsIgnoreCase(name)) return "写入文件";
        if ("Read".equalsIgnoreCase(name)) return "读取文件";
        if ("LS".equalsIgnoreCase(name) || "List".equalsIgnoreCase(name)) return "列出文件";
        if ("AndroidIntent".equalsIgnoreCase(name)) return "手机操作";
        return name;
    }

    private String truncateCommand(String command) {
        String[] lines = command.split("\\n", -1);
        String out = lines.length > 2 ? lines[0] + "\\n" + lines[1] + "…" : command;
        return shorten(out, 190);
    }

    private String compactResult(String tool, String result, int lines, int exitCode) {
        String clean = result == null ? "" : result.trim();
        if ("Bash".equalsIgnoreCase(tool)||"Root".equalsIgnoreCase(tool)) {
            if (exitCode != 0) {
                String why = firstUsefulErrorLine(clean);
                return "退出码 " + exitCode + (why.isEmpty() ? "" : " · " + shorten(why, 105)) + (lines > 1 ? " · 点按展开" : "");
            }
            return lines + (lines == 1 ? " 行" : " 行") + " · 点按展开";
        }
        if (clean.isEmpty()) return "已完成";
        if (lines == 1 && clean.length() <= 96) return clean;
        return lines + (lines == 1 ? " 行" : " 行") + " · 点按展开";
    }

    private String firstUsefulErrorLine(String text) {
        if (text == null) return "";
        String fallback = "";
        for (String line : text.split("\n")) {
            String x = line.trim(); if (x.isEmpty() || x.equals("[stderr]")) continue;
            if (fallback.isEmpty()) fallback = x;
            String l = x.toLowerCase(Locale.US);
            if (l.contains("error") || l.contains("failed") || l.contains("permission denied") || l.contains("unable to") || l.startsWith("e:") || l.contains("not found")) return x;
        }
        return fallback;
    }

    private boolean isPackageManagerTool(ChatItem item) {
        if (item == null) return false;
        try {
            JSONObject j = new JSONObject(item.body.toString());
            String c = j.optString("command", "").toLowerCase(Locale.US);
            return c.contains("pkg ") || c.contains("apt ") || c.contains("apt-get ") || c.contains("dpkg ");
        } catch (Exception e) { return false; }
    }

    private int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1; for (int i=0;i<s.length();i++) if (s.charAt(i)=='\n') n++;
        return n;
    }

    private int[] toolDelta(String name, org.json.JSONObject j) {
        int adds = 0, dels = 0;
        try {
            if ("Edit".equalsIgnoreCase(name)) {
                dels += countNonEmptyLines(j.optString("old_string", ""));
                adds += countNonEmptyLines(j.optString("new_string", ""));
            } else if ("Write".equalsIgnoreCase(name)) {
                adds += countNonEmptyLines(j.optString("content", ""));
            } else if ("MultiEdit".equalsIgnoreCase(name)) {
                org.json.JSONArray edits = j.optJSONArray("edits");
                if (edits != null) for (int i=0;i<edits.length();i++) {
                    org.json.JSONObject e = edits.optJSONObject(i); if (e == null) continue;
                    String oldText = e.optString("old_text", e.optString("old_string", ""));
                    String newText = e.optString("new_text", e.optString("new_string", ""));
                    dels += countNonEmptyLines(oldText);
                    adds += countNonEmptyLines(newText);
                }
            }
        } catch (Throwable ignored) {}
        return new int[]{adds,dels};
    }

    private int countNonEmptyLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0; for (String line : s.split("\\n",-1)) if (!line.isEmpty()) n++;
        return n;
    }

    private String toolOutputLanguage(ChatItem item) {
        if (item == null) return "text";
        try {
            JSONObject input = new JSONObject(item.body.toString());
            String path = input.optString("file_path", input.optString("path", ""));
            String command = input.optString("command", "");
            String source = (path + " " + command).toLowerCase(Locale.US);
            if (source.endsWith(".java") || source.contains(" javac")) return "java";
            if (source.endsWith(".kt")) return "kotlin";
            if (source.endsWith(".py") || source.contains("python")) return "python";
            if (source.endsWith(".json")) return "json";
            if (source.endsWith(".yml") || source.endsWith(".yaml")) return "yaml";
            if (source.endsWith(".js")) return "javascript";
            if (source.endsWith(".sh") || source.contains(" bash") || source.contains(" shell")) return "bash";
        } catch (Exception ignored) { }
        return "text";
    }

    private String toolSummary(String name, org.json.JSONObject j){
        if("Bash".equalsIgnoreCase(name)) return truncateCommand(j.optString("command","").replace('\n',' '));
        if("Agent".equalsIgnoreCase(name)||"Task".equalsIgnoreCase(name)) return shorten(j.optString("subagent_type","general-purpose") + " · " + j.optString("description",""),82);
        if("TaskOutput".equalsIgnoreCase(name)||"TaskStop".equalsIgnoreCase(name)) return shorten(j.optString("task_id",j.optString("taskId","")),82);
        if("Read".equalsIgnoreCase(name)||"Write".equalsIgnoreCase(name)||"Edit".equalsIgnoreCase(name))
            return shorten(j.optString("file_path",j.optString("path","")),82);
        if("MultiEdit".equalsIgnoreCase(name)) {
            org.json.JSONArray edits = j.optJSONArray("edits");
            if (edits == null || edits.length() == 0) return "";
            org.json.JSONObject first = edits.optJSONObject(0);
            String path = first == null ? "" : first.optString("path", "");
            if (edits.length() == 1) return shorten(path, 82);
            return shorten(path, 58) + " · " + edits.length() + " edits";
        }
        if("Grep".equalsIgnoreCase(name)) return shorten(j.optString("pattern",""),64);
        if("Glob".equalsIgnoreCase(name)) return shorten(j.optString("pattern",""),64);
        if("Tree".equalsIgnoreCase(name)||"List".equalsIgnoreCase(name)||"LS".equalsIgnoreCase(name)) return shorten(j.optString("path","."),72);
        if("Move".equalsIgnoreCase(name)) return shorten(j.optString("source","")+" → "+j.optString("destination",""),82);
        if("Delete".equalsIgnoreCase(name)||"删除".equalsIgnoreCase(name)||"Mkdir".equalsIgnoreCase(name)||"Stat".equalsIgnoreCase(name)) return shorten(j.optString("path",""),82);
        return "";
    }

    private void renderChanges(FrameLayout host) {
        host.removeAllViews();
        LinearLayout page = vbox(); page.setBackgroundColor(SURFACE);
        LinearLayout changeHeader=(LinearLayout)paneHeader("变更", "刷新", v -> refreshChanges());
        diffStats=text("",10,MUTED_2); diffStats.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        changeHeader.addView(diffStats,1,new LinearLayout.LayoutParams(dp(92),dp(36)));
        page.addView(changeHeader, lp(-1,dp(46)));
        ScrollView scroll = new ScrollView(this); changesText = text("正在加载 Git 变更…", 11, TEXT); changesText.setTypeface(Typeface.MONOSPACE); changesText.setTextIsSelectable(true); changesText.setPadding(dp(14),dp(12),dp(14),dp(24));
        scroll.addView(changesText); page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); host.addView(page); animatePane(page); refreshChanges();
    }

    private void refreshChanges() {
        if (changesText == null) return; setAnimatedText(changesText,"正在加载 Git 变更…",false);
        io.execute(() -> { try {
            TermuxShellExecutor.Result r = new TermuxShellExecutor(this).execute("git status --short; printf '\\n--- diff ---\\n'; git diff --no-color", config.projectDirectory, 60000);
            String out = r.combined(); int[] stats=countDiff(out); ui(() -> { if (changesText != null) UiMotion.setTextCrossfade(changesText,colorDiff(out)); updateDiffStats(stats[0],stats[1]); });
        } catch (Exception e) { ui(() -> { if(changesText!=null) UiMotion.setTextCrossfade(changesText,"Git 不可用，或运行环境尚未就绪：\n" + e); }); } });
    }

    private CharSequence colorDiff(String src) {
        SpannableString s = new SpannableString(src == null ? "" : src); int pos=0;
        for (String line : (src == null ? "" : src).split("\\n", -1)) {
            int color = TEXT, background = Color.TRANSPARENT;
            if (line.startsWith("+") && !line.startsWith("+++")) { color = GREEN; background = lightTheme ? Color.rgb(228,244,235) : neonTheme ? Color.rgb(9,48,42) : Color.rgb(25,45,31); }
            else if (line.startsWith("-") && !line.startsWith("---")) { color = RED; background = lightTheme ? Color.rgb(251,232,234) : neonTheme ? Color.rgb(54,20,40) : Color.rgb(52,29,29); }
            else if (line.startsWith("@@")) color = ACCENT;
            else if (line.startsWith("diff ") || line.startsWith("--- diff ---")) color = MUTED;
            int end = Math.min(s.length(), pos + line.length()); if (end > pos) { s.setSpan(new ForegroundColorSpan(color),pos,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); if(background!=Color.TRANSPARENT)s.setSpan(new BackgroundColorSpan(background),pos,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); } pos += line.length()+1;
        }
        return s;
    }

    private void renderTerminal(FrameLayout host) {
        host.removeAllViews();
        try {
            if (terminalPane == null) terminalPane = new TermuxTerminalPane(this, runtime, neonTheme, lightTheme);
            else terminalPane.applyTheme(neonTheme,lightTheme);
            if (terminalPane.getParent() != null) ((ViewGroup)terminalPane.getParent()).removeView(terminalPane);
            host.addView(terminalPane, new FrameLayout.LayoutParams(-1,-1));
            terminalPane.setKeyboardOffset(keyboardVisible?keyboardOffset:0,false);
            animatePane(host);
            host.postDelayed(() -> { if (terminalPane != null && currentView == VIEW_TERMINAL) terminalPane.requestKeyboard(); }, 220);
        } catch (Throwable t) {
            LinearLayout error=vbox(); error.setGravity(Gravity.CENTER); error.setPadding(dp(22),dp(22),dp(22),dp(22));
            TextView title=text("终端启动失败",16,RED); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER); error.addView(title,lp(-1,dp(34)));
            TextView msg=text(String.valueOf(t),11,MUTED); msg.setTypeface(Typeface.MONOSPACE); msg.setTextIsSelectable(true); msg.setGravity(Gravity.CENTER); error.addView(msg,lp(-1,-2));
            TextView repair=pill("修复运行环境",TEXT); repair.setOnClickListener(v->{runtime.repairIfInstalled();terminalPane=null;renderTerminal(host);});
            LinearLayout.LayoutParams pp=lp(dp(150),dp(40)); pp.setMargins(0,dp(16),0,0); error.addView(repair,pp); host.addView(error,new FrameLayout.LayoutParams(-1,-1));
        }
    }

    private void renderFiles(FrameLayout host) {
        filesWorkspaceHost = host;
        if (browserDir == null || !browserDir.isDirectory()) browserDir = new File(config.projectDirectory);
        showFileBrowser();
    }

    private void showFileBrowser() {
        if (filesWorkspaceHost == null) return;
        fileEditor = null; fileEditorFile = null; fileEditorStatus = null;
        filesWorkspaceHost.removeAllViews();
        LinearLayout page=vbox(); page.setBackgroundColor(SURFACE);
        LinearLayout header=hbox(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(8),dp(5),dp(8),dp(5));
        TextView up=smallIcon("←"); up.setOnClickListener(v->{if(browserDir!=null&&browserDir.getParentFile()!=null){browserDir=browserDir.getParentFile();refreshFiles();}}); header.addView(up,lp(dp(34),dp(34)));
        filesPath=text("",10.5f,MUTED); filesPath.setSingleLine(true); filesPath.setEllipsize(android.text.TextUtils.TruncateAt.START); header.addView(filesPath,new LinearLayout.LayoutParams(0,dp(34),1));
        TextView add=smallIcon("＋"); add.setContentDescription("新建文件或文件夹"); add.setOnClickListener(v->showFileCreateMenu()); header.addView(add,lp(dp(36),dp(34)));
        TextView root=smallPill("项目",MUTED); root.setGravity(Gravity.CENTER);root.setOnClickListener(v->{browserDir=new File(config.projectDirectory);refreshFiles();});header.addView(root,lp(dp(70),dp(34)));
        page.addView(header,lp(-1,dp(44)));
        View line=dividerHorizontal(); page.addView(line,lp(-1,1));
        filesList=new ListView(this); filesList.setBackgroundColor(SURFACE); filesList.setDivider(null); filesList.setSelector(android.R.color.transparent); filesList.setVerticalScrollBarEnabled(false);
        page.addView(filesList,new LinearLayout.LayoutParams(-1,0,1)); filesWorkspaceHost.addView(page,new FrameLayout.LayoutParams(-1,-1)); animatePane(page); refreshFiles();
    }

    private void refreshFiles() {
        if(browserDir==null||filesList==null)return;
        UiMotion.setTextCrossfade(filesPath,relativeProjectPath(browserDir));
        File[] xs=browserDir.listFiles(); List<File> list=new ArrayList<>(); if(xs!=null)list.addAll(Arrays.asList(xs));
        Collections.sort(list,(a,b)->{if(a.isDirectory()!=b.isDirectory())return a.isDirectory()?-1:1;return a.getName().compareToIgnoreCase(b.getName());});
        List<String> names=new ArrayList<>();for(File f:list)names.add((f.isDirectory()?"▸  ":fileGlyph(f)+"  ")+f.getName());
        ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names){@Override public View getView(int p,View c,ViewGroup parent){TextView t=(TextView)super.getView(p,c,parent);t.setTextColor(list.get(p).isDirectory()?TEXT:MUTED);t.setTextSize(11.5f);t.setTypeface(Typeface.MONOSPACE);t.setPadding(dp(12),0,dp(8),0);t.setBackgroundColor(Color.TRANSPARENT);UiMotion.listItemIn(t,p);return t;}};
        filesList.setAdapter(a);
        filesList.setOnItemClickListener((parent,v,pos,id)->{File f=list.get(pos);if(f.isDirectory()){browserDir=f;refreshFiles();}else openEditor(f);});
        filesList.setOnItemLongClickListener((parent,v,pos,id)->{showFileActions(list.get(pos));return true;});
    }

    private String fileGlyph(File f) {
        String n=f.getName().toLowerCase(Locale.US); if(n.endsWith(".java")||n.endsWith(".kt")||n.endsWith(".js")||n.endsWith(".ts")||n.endsWith(".py")||n.endsWith(".c")||n.endsWith(".cpp"))return "·"; if(n.endsWith(".json")||n.endsWith(".xml")||n.endsWith(".yml")||n.endsWith(".yaml"))return "◇"; return "·";
    }

    private void showFileCreateMenu() {
        showChoicePicker("新建",new String[]{"新建文件","新建文件夹"},-1,which->showCreatePathDialog(which==1));
    }

    private void showCreatePathDialog(boolean directory) {
        final Dialog d=newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(14),dp(12),dp(14),dp(12)); panel.setBackground(round(SURFACE_2,18,Color.TRANSPARENT,0));
        TextView h=text(directory?"新建文件夹":"新建文件",14,TEXT); h.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(h,lp(-1,dp(38)));
        EditText name=input("",false); name.setHint(directory?"文件夹名称":"Main.java"); panel.addView(name,lp(-1,dp(46)));
        LinearLayout actions=hbox(); actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        TextView cancel=text("取消",11,MUTED); cancel.setGravity(Gravity.CENTER); cancel.setOnClickListener(v->d.dismiss()); actions.addView(cancel,lp(dp(74),dp(38)));
        TextView create=text("创建",11,TEXT); create.setTypeface(Typeface.DEFAULT_BOLD); create.setGravity(Gravity.CENTER); create.setOnClickListener(v->{try{String n=name.getText().toString().trim();if(n.isEmpty()||n.contains("/")||n.equals(".")||n.equals(".."))throw new IllegalArgumentException("名称无效");File target=new File(browserDir,n);if(target.exists())throw new IllegalArgumentException("目标已存在");if(directory){if(!target.mkdir())throw new Exception("创建文件夹失败");d.dismiss();refreshFiles();}else{if(!target.createNewFile())throw new Exception("创建文件失败");d.dismiss();refreshFiles();openEditor(target);}}catch(Exception e){toast(e.getMessage());}}); actions.addView(create,lp(dp(76),dp(38))); panel.addView(actions,lp(-1,dp(44)));
        d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(390),(int)(getResources().getDisplayMetrics().widthPixels*.88f)),-2); name.requestFocus();
    }

    private void showFileActions(File file) {
        if(file==null)return;
        String[] items=file.isDirectory()?new String[]{"重命名","删除","复制路径"}:new String[]{"打开编辑器","重命名","删除","复制路径","附加到下一条提问"};
        showChoicePicker(file.getName(),items,-1,which->{
            if(!file.isDirectory()&&which==0){openEditor(file);return;}
            int offset=file.isDirectory()?0:1;
            if(which==offset)showRenamePathDialog(file);
            else if(which==offset+1)confirmDeletePath(file);
            else if(which==offset+2)copyPath(file);
            else if(!file.isDirectory()&&which==offset+3)attachFileDirect(file);
        });
    }

    private void showRenamePathDialog(File file) {
        final Dialog d=newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(14),dp(12),dp(14),dp(12)); panel.setBackground(round(SURFACE_2,18,Color.TRANSPARENT,0));
        TextView h=text("重命名",14,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(h,lp(-1,dp(38))); EditText name=input(file.getName(),false);panel.addView(name,lp(-1,dp(46)));
        TextView save=text("保存重命名",11,TEXT);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()||n.contains("/")){toast("名称无效");return;}File target=new File(file.getParentFile(),n);if(target.exists()){toast("目标已存在");return;}if(file.renameTo(target)){d.dismiss();refreshFiles();}else toast("重命名失败");});panel.addView(save,lp(-1,dp(42)));d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(390),(int)(getResources().getDisplayMetrics().widthPixels*.88f)),-2);
    }

    private void confirmDeletePath(File file) {
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,18,Color.TRANSPARENT,0));
        TextView h=text("删除“" + file.getName() + "”？",14,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(h,lp(-1,dp(38)));TextView hint=text(file.isDirectory()?"该文件夹及其中所有内容都会被删除。":"该文件将被删除。",10.5f,MUTED);panel.addView(hint,lp(-1,dp(42)));
        LinearLayout actions=hbox();actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView cancel=text("取消",11,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->d.dismiss());actions.addView(cancel,lp(dp(74),dp(38)));TextView del=text("删除",11,RED);del.setGravity(Gravity.CENTER);del.setOnClickListener(v->{if(deletePathRecursive(file)){d.dismiss();refreshFiles();}else toast("删除失败");});actions.addView(del,lp(dp(74),dp(38)));panel.addView(actions,lp(-1,dp(42)));d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(400),(int)(getResources().getDisplayMetrics().widthPixels*.88f)),-2);
    }

    private boolean deletePathRecursive(File f){try{if(f.isDirectory()){File[] xs=f.listFiles();if(xs!=null)for(File x:xs)if(!deletePathRecursive(x))return false;}return !f.exists()||f.delete();}catch(Throwable e){return false;}}
    private void copyPath(File f){android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(android.content.Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText("path",f.getAbsolutePath()));toast("路径已复制");}
    private void attachFileDirect(File f){try{if(f.length()>2_000_000)throw new IllegalArgumentException("文件过大");pendingAttachments.add(new Attachment(relativeProjectPath(f),new String(readFile(f),StandardCharsets.UTF_8)));rebuildAttachmentStrip();toast("已附加 " + f.getName());}catch(Exception e){toast(e.getMessage());}}

    private void openEditor(File file) {
        if(file.length()>2_000_000){toast("文件过大，无法使用内置编辑器");return;}
        if (filesWorkspaceHost == null || (!wide && currentView != VIEW_FILES)) focusWorkspace(VIEW_FILES);
        io.execute(()->{try{String source=new String(readFile(file),StandardCharsets.UTF_8);ui(()->showFileEditor(file,source));}catch(Exception e){ui(()->toast("打开失败："+e.getMessage()));}});
    }

    private void showFileEditor(File file,String source) {
        if(filesWorkspaceHost==null)return;
        fileEditorFile=file; filesWorkspaceHost.removeAllViews(); LinearLayout page=vbox(); page.setBackgroundColor(SURFACE);
        LinearLayout bar=hbox();bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(7),dp(5),dp(8),dp(5));TextView back=smallIcon("←");back.setOnClickListener(v->showFileBrowser());bar.addView(back,lp(dp(34),dp(34)));TextView name=text(relativeProjectPath(file),10.8f,TEXT);name.setTypeface(Typeface.MONOSPACE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.START);bar.addView(name,new LinearLayout.LayoutParams(0,dp(34),1));TextView more=smallIcon("⋯");more.setOnClickListener(v->showFileActions(file));bar.addView(more,lp(dp(36),dp(34)));TextView save=text("保存",10.8f,ACCENT);save.setTypeface(Typeface.DEFAULT_BOLD);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->saveFileEditor());bar.addView(save,lp(dp(58),dp(34)));page.addView(bar,lp(-1,dp(44)));page.addView(dividerHorizontal(),lp(-1,1));
        fileEditor=new EditText(this); fileEditor.setText(MarkdownRenderer.highlight(source,languageForFile(file),neonTheme,lightTheme)); fileEditor.setTextColor(TEXT); fileEditor.setHintTextColor(MUTED_2); fileEditor.setBackgroundColor(Color.TRANSPARENT); fileEditor.setTypeface(Typeface.MONOSPACE); fileEditor.setTextSize(11.2f); fileEditor.setGravity(Gravity.TOP|Gravity.LEFT); fileEditor.setPadding(dp(14),dp(12),dp(14),dp(18)); fileEditor.setHorizontallyScrolling(true); fileEditor.setHorizontalScrollBarEnabled(false); fileEditor.setVerticalScrollBarEnabled(true); fileEditor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI); fileEditor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fileEditorStatus=text(languageForFile(file)+" · UTF-8 · 已保存",9.5f,MUTED_2); fileEditorStatus.setPadding(dp(12),0,dp(12),0);
        fileEditor.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}@Override public void onTextChanged(CharSequence s,int st,int b,int c){if(fileEditorHighlighting)return;if(fileEditorStatus!=null)setAnimatedText(fileEditorStatus,languageForFile(file)+" · 已修改",false);scheduleFileHighlight();}@Override public void afterTextChanged(Editable e){}});
        page.addView(fileEditor,new LinearLayout.LayoutParams(-1,0,1));page.addView(fileEditorStatus,lp(-1,dp(28)));filesWorkspaceHost.addView(page,new FrameLayout.LayoutParams(-1,-1));animatePane(page);
    }

    private void scheduleFileHighlight(){if(fileEditor==null)return;if(fileHighlightRunnable!=null)uiHandler.removeCallbacks(fileHighlightRunnable);fileHighlightRunnable=()->{if(fileEditor==null||fileEditorFile==null)return;int start=fileEditor.getSelectionStart(),end=fileEditor.getSelectionEnd();String raw=fileEditor.getText().toString();fileEditorHighlighting=true;fileEditor.setText(MarkdownRenderer.highlight(raw,languageForFile(fileEditorFile),neonTheme,lightTheme));int len=fileEditor.length();fileEditor.setSelection(Math.max(0,Math.min(start,len)),Math.max(0,Math.min(end,len)));fileEditorHighlighting=false;};uiHandler.postDelayed(fileHighlightRunnable,180);}
    private void saveFileEditor(){if(fileEditor==null||fileEditorFile==null)return;String data=fileEditor.getText().toString();io.execute(()->{try{try(FileOutputStream out=new FileOutputStream(fileEditorFile)){out.write(data.getBytes(StandardCharsets.UTF_8));}ui(()->{if(fileEditorStatus!=null)setAnimatedText(fileEditorStatus,languageForFile(fileEditorFile)+" · UTF-8 · 已保存",true);toast("已保存 " + fileEditorFile.getName());refreshChanges();});}catch(Exception e){ui(()->toast("保存失败："+e.getMessage()));}});}
    private String languageForFile(File file){String n=file==null?"":file.getName().toLowerCase(Locale.US);if(n.endsWith(".java"))return"java";if(n.endsWith(".kt")||n.endsWith(".kts"))return"kotlin";if(n.endsWith(".js"))return"javascript";if(n.endsWith(".ts")||n.endsWith(".tsx"))return"typescript";if(n.endsWith(".json"))return"json";if(n.endsWith(".py"))return"python";if(n.endsWith(".sh")||n.endsWith(".bash"))return"bash";if(n.endsWith(".yml")||n.endsWith(".yaml"))return"yaml";if(n.endsWith(".diff")||n.endsWith(".patch"))return"diff";if(n.endsWith(".xml"))return"xml";return"text";}

    private String runtimeKey(File file) {
        if (file == null) return "";
        try { return file.getCanonicalPath(); } catch (Exception e) { return file.getAbsolutePath(); }
    }

    private void registerRuntime(SessionRuntime rt) {
        if (rt == null || rt.file == null) return;
        sessionRuntimes.put(runtimeKey(rt.file), rt);
    }

    private SessionRuntime createFreshRuntime() {
        SessionRuntime rt = new SessionRuntime();
        rt.nextConfig.workflowId = java.util.UUID.randomUUID().toString();
        rt.engine.configure(rt.nextConfig.copy());
        rt.file = null;
        return rt;
    }

    private SessionRuntime createRuntimeForExisting(File file) throws Exception {
        String key = runtimeKey(file);
        SessionRuntime existing = sessionRuntimes.get(key);
        if (existing != null) return existing;
        SessionRuntime created=buildRuntimeForExisting(file);
        registerRuntime(created);
        return created;
    }

    private SessionRuntime buildRuntimeForExisting(File file) throws Exception {
        SessionStore.SessionSummary summary = SessionStore.summarize(file);
        SessionConfig rc = config.copy();
        SessionStore.ProfileBinding binding=SessionStore.loadProfileBinding(file);
        if(binding!=null){
            SessionConfig bound=settingsStore.resolveProfile(binding.profileId,rc);
            if(bound!=null){rc=bound;rc.model=binding.model.isEmpty()?rc.model:binding.model;}
            else{rc.profileId=binding.profileId;rc.apiKey="";rc.model=binding.model;}
        }
        if (summary.project != null && !summary.project.trim().isEmpty() && new File(summary.project).isDirectory()) rc.projectDirectory = summary.project;
        rc.renewTransportSession();
        SessionRuntime rt = new SessionRuntime();
        try {
            rt.nextConfig=rc.copy();rt.boundProfileId=rc.profileId;
            rt.engine.configure(rc);
            rt.engine.resumeConversation(file);
            rt.file = file;
            return rt;
        } catch (Exception error) {
            rt.engine.shutdown();
            throw error;
        }
    }

    private void activateRuntime(SessionRuntime rt, boolean restoreUi) {
        if (rt == null) return;
        activeRuntimeGeneration++;
        activeRuntime = rt;
        synchronized(streamingDeltaLock){pendingStreamingDelta.setLength(0);pendingStreamingRuntime=null;pendingStreamingGeneration=activeRuntimeGeneration;streamingDeltaFlushPosted=false;}
        uiHandler.removeCallbacks(streamingDeltaFlushRunnable);
        uiHandler.removeCallbacks(streamingFrameRunnable);
        liveToolItems.clear();
        clearCollapsedToolActivities();
        engine = rt.engine;
        File liveFile = rt.engine.getSessionFile();
        if (liveFile != null) rt.file = liveFile;
        registerRuntime(rt);
        if(rt.file!=null)settingsStore.setLastSessionFile(rt.file);
        try {
            if (rt.file != null && rt.file.isFile()) {
                SessionStore.SessionSummary summary = SessionStore.summarize(rt.file);
                if (summary.project != null && !summary.project.trim().isEmpty() && new File(summary.project).isDirectory()) config.projectDirectory = summary.project;
            }
            config=rt.nextConfig==null?config:rt.nextConfig.copy();
            if (!rt.engine.isBusy()) rt.engine.configure(config.copy());
            rt.planState=rt.engine.getPlanWorkflowState();rt.taskSnapshot=rt.engine.getTaskSnapshot();
        } catch (Exception ignored) { }
        if (restoreUi && rt.file != null) {
            try { restoreTranscript(rt.file); } catch (Exception e) { transcript.clear(); }
        }
        streamingItem = null; streamingView = null; streamingBodyHost = null; streamingVisibleChars = 0;
        if ((rt.busy || rt.engine.isBusy()) && rt.liveAssistant.length() > 0) {
            streamingItem = new ChatItem(ChatItem.ASSISTANT, "IQ", rt.liveAssistant.toString());
            streamingVisibleChars = streamingItem.body.length();
            transcript.add(streamingItem);
        }
        if (rt.busy || rt.engine.isBusy()) showWorkingIndicator(runtimeDisplayStatus(rt));
        else hideWorkingIndicator();
        syncComposerForActiveRuntime();
        refreshChrome();
        refreshVisibleSessionRows();
    }

    private String runtimeDisplayStatus(SessionRuntime rt) {
        if (rt == null) return "";
        String d = rt.detail == null ? "" : rt.detail.trim();
        if (!d.isEmpty()) {
            String lower = d.toLowerCase(Locale.US);
            if (lower.contains("thinking")) return "正在思考…";
            if (lower.contains("continuing")) return "正在继续处理…";
            if (lower.contains("compact")) return "正在压缩上下文…";
            if (lower.contains("cancel")) return "正在停止…";
            return d;
        }
        if ("tool".equals(rt.phase)) return "正在执行工具…";
        if ("thinking".equals(rt.phase)) return "正在思考…";
        return rt.busy ? "正在处理…" : "";
    }

    private String sessionLabel(SessionStore.SessionSummary summary, boolean active, SessionRuntime rt) {
        return historyDisplayLabel(summary,34);
    }

    private String historyDisplayLabel(SessionStore.SessionSummary summary,int maxChars) {
        if(summary==null)return "";
        String note=summary.note==null?"":summary.note.replace('\n',' ').replace('\r',' ').trim();
        return shorten(note.isEmpty()?summary.title:note,maxChars);
    }

    private void refreshVisibleSessionRows() {
        for (Map.Entry<String,TextView> e : sessionRowLabels.entrySet()) {
            SessionStore.SessionSummary summary = sessionRowSummaries.get(e.getKey());
            if (summary == null) continue;
            SessionRuntime rt = sessionRuntimes.get(e.getKey());
            boolean active = activeRuntime != null && runtimeKey(activeRuntime.file).equals(e.getKey());
            TextView v = e.getValue();
            if (v != null) {
                setAnimatedText(v,sessionLabel(summary, active, rt),rt != null && rt.busy);
                v.setTextColor(rt != null && rt.busy ? ACCENT : (active ? TEXT : MUTED));
            }
        }
    }

    /** Session JSONL parsing is disk I/O; refresh metadata off the main thread only at coarse boundaries. */
    private void refreshVisibleSessionRowsFromDiskAsync() {
        final List<String> keys = new ArrayList<>(sessionRowLabels.keySet());
        if (keys.isEmpty()) return;
        io.execute(() -> {
            final Map<String,SessionStore.SessionSummary> fresh = new java.util.HashMap<>();
            for (String key : keys) {
                try {
                    File f = new File(key);
                    if (f.isFile()) fresh.put(key, SessionStore.summarize(f));
                } catch (Exception ignored) { }
            }
            ui(() -> { sessionRowSummaries.putAll(fresh); refreshVisibleSessionRows(); });
        });
    }

    private void addTranscriptWindow() {
        if (transcript.isEmpty()) { addEmptyState(chatMessages); return; }
        int start = includeCollapsedActivityStart(Math.max(0, transcript.size() - Math.max(40, transcriptRenderLimit)));
        if (start > 0) {
            final int hidden = start;
            TextView older = text("↑ 加载更早消息 · " + hidden + " 条", 10.5f, MUTED);
            older.setGravity(Gravity.CENTER);
            older.setPadding(dp(8),dp(6),dp(8),dp(8));
            older.setOnClickListener(v -> loadEarlierTranscript());
            chatMessages.addView(older, lp(-1,dp(42)));
        }
        for (int i = start; i < transcript.size(); i++) addChatView(transcript.get(i));
    }

    private int includeCollapsedActivityStart(int start){
        int adjusted=start;
        while(adjusted>0&&adjusted<transcript.size()){
            CollapsedToolActivity activity=collapsedToolActivity(transcript.get(adjusted));
            if(activity==null||activity.members.isEmpty())break;
            int first=transcript.indexOf(activity.members.get(0));
            if(first<0||first>=adjusted)break;
            adjusted=first;
        }
        return adjusted;
    }

    private void loadEarlierTranscript() {
        if (chatMessages == null || chatScroll == null) return;
        final int beforeHeight = chatMessages.getHeight();
        final int beforeY = chatScroll.getScrollY();
        chatAutoFollow = false;
        transcriptRenderLimit = Math.min(transcript.size(), transcriptRenderLimit + 120);
        rebuildTranscriptViews();
        final ScrollView targetScroll=chatScroll;final LinearLayout targetMessages=chatMessages;final long treeGeneration=chatTreeGeneration;
        targetMessages.post(() -> {
            if (treeGeneration!=chatTreeGeneration||targetScroll!=chatScroll||targetMessages!=chatMessages) return;
            int addedHeight = Math.max(0, targetMessages.getHeight() - beforeHeight);
            targetScroll.scrollTo(0, Math.max(0, beforeY + addedHeight));
        });
    }

    private void rebuildTranscriptViews() {
        if (chatMessages == null) return;
        clearCollapsedToolActivityViews();
        chatMessages.removeAllViews();
        currentConversationHost=null;
        workingIndicator = null;
        rebuildingTranscript = true;
        addTranscriptWindow();
        attachChatImeSpacer();
        rebuildingTranscript = false;
        if (activeRuntime != null && activeRuntime.busy) showWorkingIndicator(runtimeDisplayStatus(activeRuntime));
        UiMotion.bindInteractive(chatMessages);
        if (chatAutoFollow) scrollChatSoft();
    }

    private View paneHeader(String title, String action, View.OnClickListener click) {
        LinearLayout bar=hbox(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12),dp(5),dp(8),dp(5)); bar.setBackgroundColor(SURFACE);
        TextView t=text(title,12,TEXT);t.setTypeface(Typeface.DEFAULT_BOLD);bar.addView(t,new LinearLayout.LayoutParams(0,dp(36),1)); TextView b=smallPill(action,MUTED);b.setGravity(Gravity.CENTER);b.setOnClickListener(click);bar.addView(b,lp(dp(64),dp(32)));return bar;
    }

    private void newSession() {
        clearCollapsedToolActivities();
        transcript.clear(); transcriptRenderLimit=120; streamingItem=null; streamingView=null; streamingBodyHost=null; streamingVisibleChars=0;
        pendingAttachments.clear();
        config.renewTransportSession();
        SessionRuntime rt = createFreshRuntime();
        activateRuntime(rt, false);
        focusChat();
        if (mobileSidebarDialog != null) { mobileSidebarDialog.dismiss(); mobileSidebarDialog = null; }
    }

    private void showMobileSidebar() {
        if (mobileSidebarDialog != null) mobileSidebarDialog.dismiss();
        final Dialog dialog=newOverlayAwareDialog(); mobileSidebarDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=vbox(); shell.setPadding(0,dp(8),0,dp(8)); shell.setBackground(round(SIDEBAR,22,Color.TRANSPARENT,0));
        LinearLayout top=hbox(); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(14),0,dp(8),0);
        TextView title=text("IQ Code",13,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); top.addView(title,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView close=iconButton("×"); close.setTextSize(22); close.setOnClickListener(v->dialog.dismiss()); top.addView(close,lp(dp(40),dp(40)));
        shell.addView(top,lp(-1,dp(44)));
        ScrollView sidebarScroll=new ScrollView(this);
        sidebarScroll.setVerticalScrollBarEnabled(false);
        sidebarScroll.setFillViewport(true);
        sidebarScroll.addView(buildSidebar(),new ScrollView.LayoutParams(-1,-2));
        shell.addView(sidebarScroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(shell); dialog.setCanceledOnTouchOutside(true); dialog.setOnDismissListener(d -> { if (mobileSidebarDialog == dialog) mobileSidebarDialog = null; }); dialog.show();
        Window w=dialog.getWindow(); if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=.54f;w.setAttributes(a);w.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);w.setLayout(Math.min(dp(330),(int)(getResources().getDisplayMetrics().widthPixels*.88f)),-1);}
        UiMotion.bindInteractive(shell);
        UiMotion.messageIn(shell,false);
    }

    private void resumeSession(SessionStore.SessionSummary summary) {
        if (summary == null) return;
        try {
            SessionRuntime rt = createRuntimeForExisting(summary.file);
            activateRuntime(rt, true);
            if (summary.project != null && !summary.project.trim().isEmpty() && new File(summary.project).isDirectory()) config.projectDirectory = summary.project;
            rt.nextConfig=config.copy();
            if (!rt.engine.isBusy()) rt.engine.configure(config.copy());
            settingsStore.save(config);
            currentView=VIEW_CHAT; styleViewTabs(); renderChat(primaryHost, false);
            if (mobileSidebarDialog != null) { mobileSidebarDialog.dismiss(); mobileSidebarDialog = null; }
        } catch (Exception e) { toast("恢复会话失败：" + e.getMessage()); }
    }

    private void showProjectPathDialog() {
        final Dialog d=newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(16),dp(14),dp(16),dp(12)); panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView title=text("手动添加 / 切换项目路径",15,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(title,lp(-1,dp(38)));
        TextView hint=text("每个规范化项目路径都有独立上下文历史。切换后只显示该路径绑定的会话；原项目正在运行的任务不会被强制取消。",10.2f,MUTED); hint.setLineSpacing(0,1.12f); panel.addView(hint,lp(-1,-2));
        EditText path=input(config.projectDirectory,false); path.setHint("/storage/emulated/0/项目 或 /data/user/0/com.iqge/files/home/projects/项目名");
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(48)); pp.setMargins(0,dp(12),0,0); panel.addView(path,pp);
        LinearLayout actions=hbox(); actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); actions.setPadding(0,dp(10),0,0);
        TextView cancel=text("取消",11,MUTED); cancel.setGravity(Gravity.CENTER); cancel.setOnClickListener(v->d.dismiss()); actions.addView(cancel,lp(dp(72),dp(38)));
        TextView apply=text("打开项目",11,TEXT); apply.setTypeface(Typeface.DEFAULT_BOLD); apply.setGravity(Gravity.CENTER); apply.setBackground(round(ACCENT,14,Color.TRANSPARENT,0));
        apply.setOnClickListener(v->{try{String selected=path.getText().toString().trim();switchProjectPath(selected,true);d.dismiss();}catch(Exception e){toast("切换失败："+e.getMessage());}}); actions.addView(apply,lp(dp(92),dp(38)));
        panel.addView(actions,lp(-1,dp(50))); d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(480),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
        path.requestFocus(); path.setSelection(path.length());
    }

    private boolean switchProjectPath(String requestedProject, boolean openHistory) throws Exception {
        String raw=requestedProject==null?"":requestedProject.trim(); if(raw.isEmpty())throw new IllegalArgumentException("项目目录不能为空");
        File projectDir=new File(raw); if(!projectDir.isDirectory()&&!projectDir.mkdirs())throw new IllegalArgumentException("无法创建项目目录");
        String canonical=projectDir.getCanonicalPath(); String before=SessionStore.canonicalProject(config.projectDirectory);
        boolean changed=!before.equals(canonical); config.projectDirectory=canonical; settingsStore.save(config);
        if(changed) settingsStore.setLastSessionFile(null); else if(engine!=null&&!engine.isBusy()) engine.configure(config);
        browserDir=new File(canonical); if(terminalPane!=null)terminalPane.setNextSessionWorkingDirectory(canonical); refreshChrome(); updateComposerChips();
        if(changed&&openHistory){toast("项目已切换："+new File(canonical).getName());uiHandler.postDelayed(()->showProjectHistoryPicker(true),120);}
        else if(!changed)toast("已经在这个项目路径");
        return changed;
    }

    private void showResumePicker() { showProjectHistoryPicker(false); }

    private String historyPickerLabel(SessionStore.SessionSummary summary){
        return historyDisplayLabel(summary,46);
    }

    private void refreshHistoryPickerRow(TextView row,File file){
        io.submit(()->{try{SessionStore.SessionSummary fresh=SessionStore.summarize(file);ui(()->row.setText(historyPickerLabel(fresh)));}catch(Exception ignored){}});
    }

    private void showProjectHistoryPicker(boolean createOnCancel) {
        List<SessionStore.SessionSummary> sessions = SessionStore.listSessions(config.projectDirectory);
        if (sessions.isEmpty()) { if(createOnCancel){newSession();toast("当前项目没有历史，已创建新上下文");}else toast("当前项目暂无已保存会话"); return; }
        final Dialog d = newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = vbox(); panel.setPadding(dp(12),dp(10),dp(12),dp(10)); panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        final boolean[] resolved={false};
        TextView h = text("选择项目上下文历史",14,TEXT); h.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(h,lp(-1,dp(34)));
        TextView path=text(shortenMiddle(SessionStore.canonicalProject(config.projectDirectory),62),9.5f,MUTED_2);path.setTypeface(Typeface.MONOSPACE);path.setPadding(0,0,0,dp(5));panel.addView(path,lp(-1,dp(32)));
        ScrollView sv = new ScrollView(this); LinearLayout rows = vbox();
        TextView fresh=text("＋  新建空白上下文\n    历史会保存到当前项目路径",11,TEXT);fresh.setPadding(dp(10),dp(7),dp(10),dp(7));fresh.setGravity(Gravity.CENTER_VERTICAL);fresh.setBackground(round(SURFACE_3,12,BORDER,1));fresh.setOnClickListener(v->{resolved[0]=true;d.dismiss();newSession();});rows.addView(fresh,lp(-1,dp(62)));
        int max = Math.min(30, sessions.size());
        for (int i=0;i<max;i++) {
            SessionStore.SessionSummary s = sessions.get(i);
            TextView r=text(historyPickerLabel(s),14,TEXT);
            r.setPadding(dp(10),dp(7),dp(10),dp(7));r.setGravity(Gravity.CENTER_VERTICAL);r.setMaxLines(2);r.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.setOnClickListener(v->{resolved[0]=true;d.dismiss();resumeSession(s);});
            r.setOnLongClickListener(v->{showSessionHistoryActions(s,()->refreshHistoryPickerRow(r,s.file));return true;});
            rows.addView(r,lp(-1,dp(76)));
        }
        sv.addView(rows,new ScrollView.LayoutParams(-1,-2)); panel.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        d.setOnCancelListener(x->{if(createOnCancel&&!resolved[0]){resolved[0]=true;newSession();}});
        d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(470),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),Math.min(dp(640),(int)(getResources().getDisplayMetrics().heightPixels*.82f)));
    }

    private void restoreTranscript(File file) throws Exception {
        clearCollapsedToolActivities();
        transcript.clear();
        liveToolItems.clear();
        transcriptRenderLimit = 120;
        java.util.HashMap<String,ChatItem> tools = new java.util.HashMap<>();
        ChatItem pendingUsageAssistant = null;
        JSONArray rows = SessionStore.readRows(file);
        for (int i=0;i<rows.length();i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            String rowType = row.optString("type", "");
            if ("context_usage".equals(rowType)) {
                JSONObject usage = row.optJSONObject("payload");
                ChatItem assistant = pendingUsageAssistant;
                if (usage != null && assistant != null) {
                    assistant.contextTokens = usage.optLong("context_tokens", -1L);
                    assistant.contextWindowTokens = usage.optInt("context_window_tokens", config.contextWindowTokens);
                    assistant.inputTokens = usage.optLong("input_tokens", -1L);
                    assistant.outputTokens = usage.optLong("output_tokens", -1L);
                    assistant.contextApiMeasured = "api".equals(usage.optString("source", "estimate"));
                }
                pendingUsageAssistant = null;
                continue;
            }
            if ("tool_diff".equals(rowType)) {
                JSONObject payload = row.optJSONObject("payload");
                if (payload != null) {
                    ChatItem tool = tools.get(payload.optString("tool_use_id", ""));
                    if (tool != null) {
                        tool.diff.setLength(0); tool.diff.append(payload.optString("diff", ""));
                        tool.diffAddedLines = payload.optInt("additions", 0);
                        tool.diffDeletedLines = payload.optInt("deletions", 0);
                    }
                }
                continue;
            }
            if (!"message".equals(rowType)) continue;
            String role = row.optString("role", ""); JSONArray content = row.optJSONArray("content"); if (content == null) continue;
            if ("assistant".equals(role)) {
                pendingUsageAssistant = null;
                StringBuilder text = new StringBuilder();
                ArrayList<ToolActivityGrouper.Entry> activityEntries=new ArrayList<>();
                ArrayList<ChatItem> assistantTools=new ArrayList<>();
                boolean boundaryBeforeTool=false;
                for (int j=0;j<content.length();j++) {
                    JSONObject b = content.optJSONObject(j); if (b == null) continue;
                    String type = b.optString("type", "");
                    if ("text".equals(type)) {
                        String blockText=b.optString("text","");
                        if(!blockText.isEmpty()){text.append(blockText);boundaryBeforeTool=true;}
                    } else if ("tool_use".equals(type)) {
                        if (text.length()>0 && !text.toString().trim().isEmpty()) { ChatItem assistant=new ChatItem(ChatItem.ASSISTANT,"IQ",text.toString());SessionStore.MessageReference ref=SessionStore.messageReference(row,i);if(ref!=null){assistant.messageId=ref.messageId;assistant.messageContentHash=ref.contentHash;assistant.legacyRowIndex=ref.legacyRowIndex;assistant.humanMessage=true;}transcript.add(assistant);pendingUsageAssistant=assistant;text.setLength(0); }
                        JSONObject input=b.optJSONObject("input"); String toolName=b.optString("name","Tool"); String toolId=b.optString("id","");
                        activityEntries.add(toolActivityEntry(toolId,toolName,input,boundaryBeforeTool));
                        boundaryBeforeTool=false;
                        if(isWorkflowTool(toolName))continue;
                        ChatItem tool=new ChatItem(ChatItem.TOOL,toolName,input==null?"{}":input.toString());
                        tool.toolId=toolId; transcript.add(tool); assistantTools.add(tool); if(!tool.toolId.isEmpty())tools.put(tool.toolId,tool);
                    } else if(!type.isEmpty()) boundaryBeforeTool=true;
                }
                if (text.length()>0 && !text.toString().trim().isEmpty() && !text.toString().startsWith("Understood. Continuing from the compacted context")) { ChatItem assistant=new ChatItem(ChatItem.ASSISTANT,"IQ",text.toString());SessionStore.MessageReference ref=SessionStore.messageReference(row,i);if(ref!=null){assistant.messageId=ref.messageId;assistant.messageContentHash=ref.contentHash;assistant.legacyRowIndex=ref.legacyRowIndex;assistant.humanMessage=true;}transcript.add(assistant);pendingUsageAssistant=assistant; }
                registerCollapsedToolBatch(nextRestoredToolBatchId--,activityEntries);
                for(ChatItem tool:assistantTools)attachToolToCollapsedActivity(tool);
            } else if ("user".equals(role)) {
                StringBuilder user = new StringBuilder();
                List<Attachment> restoredImages = new ArrayList<>();
                for (int j=0;j<content.length();j++) {
                    JSONObject b=content.optJSONObject(j); if (b==null)continue; String type=b.optString("type","");
                    if ("text".equals(type)) { String t=b.optString("text",""); if(!t.startsWith("<context_summary>")&&!t.startsWith("<iq_internal_continue>"))user.append(t); }
                    else if ("image".equals(type)) {
                        try {
                            JSONObject source=b.optJSONObject("source");
                            if(source!=null && "base64".equals(source.optString("type"))){
                                String data=source.optString("data","");
                                if(!data.isEmpty()){
                                    byte[] bytes=Base64.decode(data,Base64.DEFAULT);
                                    if(bytes.length<=10*1024*1024) restoredImages.add(new Attachment(b.optString("name","图片"),source.optString("media_type","image/jpeg"),bytes));
                                }
                            }
                        } catch(Throwable ignored) { }
                    } else if ("tool_result".equals(type)) {
                        ChatItem tool=tools.get(b.optString("tool_use_id","")); if(tool!=null){tool.completed=true;tool.resultError=b.optBoolean("is_error",false);tool.result.append(String.valueOf(b.opt("content")));}
                    }
                }
                String visible=user.toString().replaceFirst("\\n\\n📷 \\d+ 张图片\\s*$","");
                if(!visible.isEmpty() || !restoredImages.isEmpty()) {
                    ChatItem item=new ChatItem(ChatItem.USER,"你",visible);item.persistedBody=user.toString();item.images.addAll(restoredImages);
                    SessionStore.MessageReference reference=SessionStore.messageReference(row,i);
                    if(reference!=null){item.messageId=reference.messageId;item.messageContentHash=reference.contentHash;item.legacyRowIndex=reference.legacyRowIndex;item.humanMessage=true;}
                    transcript.add(item);
                }
            }
        }
        for(ChatItem tool:tools.values())if(!tool.completed){
            tool.completed=true;tool.resultError=true;tool.restoredUnfinished=true;
            tool.result.append("会话记录未包含该工具的结果。");
        }
        for(List<CollapsedToolActivity> activities:collapsedToolsByBatch.values())for(CollapsedToolActivity activity:activities)activity.batchCompleted=true;
        streamingItem=null; streamingView=null; streamingBodyHost=null; streamingVisibleChars=0;
    }

    private String relativeSessionTime(long when) {
        long diff=Math.max(0,System.currentTimeMillis()-when);
        if(diff<60_000)return "刚刚"; if(diff<3_600_000)return (diff/60_000)+" 分钟前"; if(diff<86_400_000)return (diff/3_600_000)+" 小时前";
        if(diff<604_800_000)return (diff/86_400_000)+" 天前";
        return new SimpleDateFormat("M月d日",Locale.CHINA).format(new Date(when));
    }

    private void runtimeDialog() {
        if (runtime.isInstalled()) {
            final Dialog d = newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout panel=vbox(); panel.setPadding(dp(16),dp(14),dp(16),dp(12)); panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
            TextView title=text("Termux 运行环境",16,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(title,lp(-1,dp(38)));
            TextView info=text("原生 Android/Bionic 环境 · 无 proot\n\nPREFIX\n"+TermuxConstants.TERMUX_PREFIX_DIR_PATH+"\n\nHOME\n"+TermuxConstants.TERMUX_HOME_DIR_PATH,11,MUTED); info.setLineSpacing(0,1.15f); panel.addView(info,lp(-1,-2));
            LinearLayout actions=hbox(); actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); actions.setPadding(0,dp(10),0,0);
            TextView storage=text("手机权限",11,MUTED); storage.setGravity(Gravity.CENTER); storage.setOnClickListener(v->{d.dismiss(); requestAndroidIntegrationSetup(true);}); actions.addView(storage,lp(dp(86),dp(38)));
            TextView reinstall=text("重新初始化",11,ACCENT); reinstall.setGravity(Gravity.CENTER); reinstall.setOnClickListener(v->{d.dismiss();installRuntime(false);}); actions.addView(reinstall,lp(dp(86),dp(38)));
            TextView close=text("关闭",11,TEXT); close.setGravity(Gravity.CENTER); close.setOnClickListener(v->d.dismiss()); actions.addView(close,lp(dp(66),dp(38)));
            panel.addView(actions,lp(-1,dp(48))); d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(420),(int)(getResources().getDisplayMetrics().widthPixels*.90f)),-2);
        } else installRuntime(false);
    }

    private void runFirstLaunchSetup() {
        if (config.apiKey == null || config.apiKey.trim().isEmpty()) {
            apiSetupPending = true;
            showApiProfileManager();
            return;
        }
        continueFirstLaunchSetup();
    }

    private void continueFirstLaunchSetup() {
        apiSetupPending = false;
        if (!runtime.isInstalled()) installRuntime(true);
        else requestAndroidIntegrationSetup(false);
    }

    private void installRuntime() { installRuntime(false); }

    private void installRuntime(boolean automatic) {
        if (automaticRuntimeInstallRunning) return;
        automaticRuntimeInstallRunning = true;
        ProgressDialog pd=new ProgressDialog(this);
        pd.setTitle(automatic ? "正在初始化内置 Termux 环境" : "重新初始化内置 Termux 环境");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL); pd.setMax(100); pd.setCancelable(!automatic); pd.setMessage("正在准备…"); pd.show();
        if(pd.getWindow()!=null){UiMotion.bindInteractive(pd.getWindow().getDecorView());UiMotion.dialogIn(pd.getWindow().getDecorView());}
        io.execute(()->{try{
            runtime.install((msg,pct)->ui(()->{pd.setMessage(localizeRuntimeProgress(msg));pd.setProgress(pct);}));
            ui(()->{automaticRuntimeInstallRunning=false;pd.dismiss();refreshChrome();if(terminalPane!=null)terminalPane.onRuntimeReady();toast("内置 Termux 环境已就绪");requestAndroidIntegrationSetup(false);});
        }catch(Exception e){ui(()->{automaticRuntimeInstallRunning=false;pd.dismiss();showAnimatedAlert(new AlertDialog.Builder(this).setTitle("Termux 初始化失败").setMessage(e.toString()).setPositiveButton("知道了",null).create());});}});
    }

    private String localizeRuntimeProgress(String msg) {
        if (msg == null) return "正在处理…";
        return msg.replace("Reading bundled Termux bootstrap…","正在读取内置 Termux 基础环境…")
            .replace("Installing bundled Termux userspace…","正在安装内置 Termux 原生环境…")
            .replace("Extracted ","已解压 ")
            .replace(" files…"," 个文件…")
            .replace("Configuring com.iqge package compatibility…","正在配置 com.iqge 软件包兼容层…")
            .replace("Termux ready","Termux 已就绪");
    }

    private void requestAndroidIntegrationSetup(boolean force) {
        android.content.SharedPreferences prefs=getSharedPreferences("iqge_onboarding",MODE_PRIVATE);
        if (!force && prefs.getBoolean("android_integration_prompted_v0192",false)) {
            requestStorageAccessWithExplanation(false);
            return;
        }
        if (androidIntegrationDialogVisible) return;
        androidIntegrationDialogVisible=true;
        prefs.edit().putBoolean("android_integration_prompted_v0192",true).apply();

        final Dialog d=newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(18),dp(16),dp(18),dp(12)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        TextView title=text("手机功能与权限",17,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(title,lp(-1,dp(40)));
        TextView body=text("IQ Code 现在会通过 com.iqge 自己的 Android 进程打开浏览器、其他 App 和系统设置，不再让内置 Termux 直接执行 am start，因此不会再出现调用包名与 UID 不匹配。\n\nAndroid 没有一个统一的“允许调用其他 App”危险权限。每次 IQ 要跳转到外部 App 时，会先显示 IQ Code 自己的操作确认弹窗；只有你点“允许”才会启动。\n\n下面只申请实际存在且 IQ Code 会用到的系统权限：共享存储，以及 Android 13+ 的通知权限。",12,MUTED);
        body.setLineSpacing(dp(2),1.12f); panel.addView(body,lp(-1,-2));
        LinearLayout actions=hbox(); actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); actions.setPadding(0,dp(12),0,0);
        TextView later=text("暂不",11,MUTED); later.setGravity(Gravity.CENTER); later.setOnClickListener(v->{androidIntegrationDialogVisible=false;d.dismiss();}); actions.addView(later,lp(dp(74),dp(40)));
        TextView allow=text("设置权限",11,ACCENT); allow.setTypeface(Typeface.DEFAULT_BOLD); allow.setGravity(Gravity.CENTER); allow.setOnClickListener(v->{androidIntegrationDialogVisible=false;d.dismiss();requestAndroidSystemPermissionsNow();}); actions.addView(allow,lp(dp(92),dp(40)));
        panel.addView(actions,lp(-1,dp(50))); d.setOnDismissListener(x->androidIntegrationDialogVisible=false); d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(455),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
    }

    private void requestAndroidSystemPermissionsNow() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},4243);
            return;
        }
        requestStorageAccessWithExplanation(true);
    }

    private boolean hasSharedStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) return isExternalStorageManagerCompat();
        if (android.os.Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private boolean isExternalStorageManagerCompat() {
        if (android.os.Build.VERSION.SDK_INT < 30) return true;
        try {
            java.lang.reflect.Method m = android.os.Environment.class.getMethod("isExternalStorageManager");
            Object v = m.invoke(null);
            return v instanceof Boolean && ((Boolean) v);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestStorageAccessWithExplanation(boolean force) {
        if (hasSharedStorageAccess()) { createStorageLinks(); return; }
        android.content.SharedPreferences prefs=getSharedPreferences("iqge_onboarding",MODE_PRIVATE);
        if (!force && prefs.getBoolean("storage_permission_prompted",false)) return;
        if (storagePermissionDialogVisible) return;
        storagePermissionDialogVisible=true;
        prefs.edit().putBoolean("storage_permission_prompted",true).apply();

        final Dialog d=newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(18),dp(16),dp(18),dp(12)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        TextView title=text("允许访问手机存储",17,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(title,lp(-1,dp(40)));
        TextView body=text("IQ Code 和内置 Termux 需要访问 /storage/emulated/0，才能直接读取、编辑和运行手机共享存储里的项目文件。\n\n授权后会自动创建 ~/storage/shared、downloads、dcim、pictures、music、movies 等快捷入口。\n\n不会使用 root，也不会使用 proot。",12,MUTED); body.setLineSpacing(dp(2),1.12f); panel.addView(body,lp(-1,-2));
        LinearLayout actions=hbox(); actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); actions.setPadding(0,dp(12),0,0);
        TextView later=text("暂不",11,MUTED); later.setGravity(Gravity.CENTER); later.setOnClickListener(v->{storagePermissionDialogVisible=false;d.dismiss();}); actions.addView(later,lp(dp(74),dp(40)));
        TextView allow=text("去授权",11,ACCENT); allow.setTypeface(Typeface.DEFAULT_BOLD); allow.setGravity(Gravity.CENTER); allow.setOnClickListener(v->{storagePermissionDialogVisible=false;d.dismiss();requestStoragePermissionNow();}); actions.addView(allow,lp(dp(84),dp(40)));
        panel.addView(actions,lp(-1,dp(50))); d.setOnDismissListener(x->storagePermissionDialogVisible=false); d.setContentView(panel); d.show(); styleDialogWindow(d,Math.min(dp(430),(int)(getResources().getDisplayMetrics().widthPixels*.90f)),-2);
    }

    private void requestStoragePermissionNow() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            waitingForAllFilesAccess=true;
            try {
                android.content.Intent i=new android.content.Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                    android.net.Uri.parse("package:"+getPackageName()));
                startActivity(i);
            } catch (Throwable first) {
                try { startActivity(new android.content.Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION")); }
                catch (Throwable e) { waitingForAllFilesAccess=false; toast("无法打开存储权限设置："+e.getMessage()); }
            }
            return;
        }
        if(android.os.Build.VERSION.SDK_INT>=23){
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,android.Manifest.permission.WRITE_EXTERNAL_STORAGE},4242);return;
        }
        createStorageLinks();
    }

    private void setupStorage(){ requestStorageAccessWithExplanation(true); }

    @Override protected void onResume() {
        super.onResume();
        startDeviceStatus();
        ToolExecutionResult pendingInstall = AndroidIntentBridge.resumePendingApkInstall(this);
        if (pendingInstall != null) toast(pendingInstall.content);
        if (waitingForOverlayPermission) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                waitingForOverlayPermission=false;
                showWorkspaceOverlay(true);
            }
        }
        if (waitingForAllFilesAccess && android.os.Build.VERSION.SDK_INT >= 30) {
            if (isExternalStorageManagerCompat()) {
                waitingForAllFilesAccess=false; createStorageLinks(); toast("存储权限已开启");
            }
        }
    }

    @Override protected void onPause(){
        stopDeviceStatus();
        super.onPause();
    }

    private void createStorageLinks(){
        try{
            File dir=new File(TermuxConstants.TERMUX_HOME_DIR_PATH,"storage"); if(!dir.exists())dir.mkdirs();
            String root=android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            linkStorage(new File(dir,"shared"),root); linkStorage(new File(dir,"downloads"),root+"/Download"); linkStorage(new File(dir,"dcim"),root+"/DCIM"); linkStorage(new File(dir,"pictures"),root+"/Pictures"); linkStorage(new File(dir,"music"),root+"/Music"); linkStorage(new File(dir,"movies"),root+"/Movies");
            toast("已创建 ~/storage，共享存储可直接访问");
        }catch(Throwable e){toast("创建存储快捷入口失败："+e.getMessage());}
    }

    private void handleMcpSlash(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) { showMcpPanel(); return; }
        String[] parts = a.split("\\s+", 2);
        String op = parts[0].toLowerCase(Locale.US);
        String name = parts.length > 1 ? parts[1].trim() : "";
        if ("list".equals(op) || "列表".equals(op)) { showMcpSummary(); return; }
        if (("enable".equals(op) || "disable".equals(op) || "remove".equals(op) || "delete".equals(op)) && name.isEmpty()) {
            toast("用法：/mcp " + op + " <服务器名称>"); return;
        }
        if ("enable".equals(op) || "disable".equals(op)) {
            List<McpConfigStore.Server> servers = mcpStore.load(); boolean found=false;
            for (McpConfigStore.Server server : servers) if (server.name.equalsIgnoreCase(name)) { server.enabled="enable".equals(op); found=true; }
            if (!found) { toast("未找到 MCP 服务器：" + name); return; }
            try { mcpStore.save(servers); toast(("enable".equals(op)?"已启用：":"已停用：") + name); }
            catch (Exception e) { toast("保存 MCP 配置失败：" + e.getMessage()); }
            return;
        }
        if ("remove".equals(op) || "delete".equals(op)) {
            List<McpConfigStore.Server> servers = mcpStore.load(); boolean removed=false;
            for (int i=servers.size()-1;i>=0;i--) if (servers.get(i).name.equalsIgnoreCase(name)) { servers.remove(i); removed=true; }
            if (!removed) { toast("未找到 MCP 服务器：" + name); return; }
            try { mcpStore.save(servers); toast("已删除 MCP 服务器：" + name); }
            catch (Exception e) { toast("保存 MCP 配置失败：" + e.getMessage()); }
            return;
        }
        if ("add".equals(op) || "添加".equals(op)) { showMcpEditor(null); return; }
        toast("/mcp 支持：list、add、enable <名称>、disable <名称>、remove <名称>");
    }

    private void showMcpSummary() {
        List<McpConfigStore.Server> servers = mcpStore.load();
        StringBuilder b = new StringBuilder("MCP 服务器：\n\n");
        if (servers.isEmpty()) b.append("当前没有配置 MCP 服务器。输入 `/mcp` 打开配置面板。\n");
        for (McpConfigStore.Server s : servers) {
            String endpoint = "stdio".equals(s.type) ? s.command : s.url;
            b.append(s.enabled ? "● " : "○ ").append("**").append(s.name).append("** · `").append(s.type).append("`")
                .append(" · ").append("project".equals(s.scope)?"项目":"用户").append("\n  `").append(endpoint).append("`\n");
        }
        ChatItem item = new ChatItem(ChatItem.ASSISTANT, "MCP", b.toString()); transcript.add(item);
        if (chatMessages != null) { addChatView(item); scrollChatSoft(); }
    }

    private void showMcpPanel() {
        final Dialog d = newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(14),dp(10),dp(14),dp(12)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout titleRow=hbox(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("MCP 服务器",16,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView add=text("＋ 添加",11,ACCENT); add.setGravity(Gravity.CENTER); add.setTypeface(Typeface.DEFAULT_BOLD); add.setOnClickListener(v->{d.dismiss();showMcpEditor(null);}); titleRow.addView(add,lp(dp(72),dp(38)));
        TextView close=iconButton("×"); close.setTextSize(22); close.setOnClickListener(v->d.dismiss()); titleRow.addView(close,lp(dp(40),dp(40))); panel.addView(titleRow,lp(-1,dp(46)));
        TextView sub=text("配置文件："+mcpStore.getFile().getAbsolutePath(),9.5f,MUTED_2); sub.setPadding(dp(4),0,dp(4),dp(8)); panel.addView(sub,lp(-1,-2));
        ScrollView sv=new ScrollView(this); LinearLayout rows=vbox(); List<McpConfigStore.Server> servers=mcpStore.load();
        if(servers.isEmpty()){TextView empty=text("还没有 MCP 服务器。点击右上角“添加”开始配置。",11,MUTED);empty.setPadding(dp(8),dp(22),dp(8),dp(22));rows.addView(empty,lp(-1,-2));}
        for(McpConfigStore.Server server:servers){
            LinearLayout row=vbox(); row.setPadding(dp(10),dp(8),dp(8),dp(8)); row.setBackground(round(SURFACE_3,14,Color.TRANSPARENT,0));
            LinearLayout top=hbox(); top.setGravity(Gravity.CENTER_VERTICAL); TextView dot=text(server.enabled?"●":"○",11,server.enabled?GREEN:MUTED_2);top.addView(dot,lp(dp(24),dp(34)));
            TextView name=text(server.name,12.5f,TEXT);name.setTypeface(Typeface.DEFAULT_BOLD);top.addView(name,new LinearLayout.LayoutParams(0,dp(34),1));
            TextView type=text(server.type.toUpperCase(Locale.US),9.5f,MUTED);type.setGravity(Gravity.CENTER);top.addView(type,lp(dp(58),dp(32)));row.addView(top,lp(-1,dp(36)));
            String endpoint="stdio".equals(server.type)?server.command:server.url; TextView ep=text(endpoint,9.5f,MUTED);ep.setSingleLine(true);ep.setPadding(dp(24),0,dp(6),dp(6));row.addView(ep,lp(-1,dp(26)));
            LinearLayout actions=hbox();actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            TextView toggle=text(server.enabled?"停用":"启用",10,server.enabled?MUTED:GREEN);toggle.setGravity(Gravity.CENTER);toggle.setOnClickListener(v->{List<McpConfigStore.Server> all=mcpStore.load();for(McpConfigStore.Server x:all)if(x.name.equalsIgnoreCase(server.name))x.enabled=!server.enabled;try{mcpStore.save(all);d.dismiss();showMcpPanel();}catch(Exception e){toast("保存失败："+e.getMessage());}});actions.addView(toggle,lp(dp(58),dp(32)));
            TextView edit=text("编辑",10,MUTED);edit.setGravity(Gravity.CENTER);edit.setOnClickListener(v->{d.dismiss();showMcpEditor(server);});actions.addView(edit,lp(dp(54),dp(32)));
            TextView del=text("删除",10,RED);del.setGravity(Gravity.CENTER);del.setOnClickListener(v->showAnimatedAlert(new AlertDialog.Builder(this).setTitle("删除 MCP 服务器？").setMessage(server.name).setNegativeButton("取消",null).setPositiveButton("删除",(x,w)->{List<McpConfigStore.Server> all=mcpStore.load();for(int i=all.size()-1;i>=0;i--)if(all.get(i).name.equalsIgnoreCase(server.name))all.remove(i);try{mcpStore.save(all);d.dismiss();showMcpPanel();}catch(Exception e){toast("删除失败："+e.getMessage());}}).create()));actions.addView(del,lp(dp(54),dp(32))); row.addView(actions,lp(-1,dp(36)));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,0,0,dp(8));rows.addView(row,rp);
        }
        sv.addView(rows);panel.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=text("支持 stdio / HTTP / SSE。命令行也可用：/mcp list、/mcp enable 名称、/mcp disable 名称、/mcp remove 名称。",9.5f,MUTED_2);hint.setPadding(dp(4),dp(8),dp(4),0);panel.addView(hint,lp(-1,-2));
        d.setContentView(panel);d.show();Window w=d.getWindow();if(w!=null){if(overlayShell!=null&&android.os.Build.VERSION.SDK_INT>=26)w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=.60f;w.setAttributes(a);int width=Math.min(dp(wide?620:520),(int)(getResources().getDisplayMetrics().widthPixels*.92f));int height=Math.min(dp(700),(int)(getResources().getDisplayMetrics().heightPixels*.84f));w.setLayout(width,height);UiMotion.bindInteractive(w.getDecorView());UiMotion.dialogIn(w.getDecorView());}
    }

    private void showMcpEditor(McpConfigStore.Server existing) {
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);final boolean editing=existing!=null;McpConfigStore.Server base=editing?existing.copy():new McpConfigStore.Server();
        LinearLayout panel=vbox();panel.setPadding(dp(16),dp(10),dp(16),dp(12));panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout titleRow=hbox();titleRow.setGravity(Gravity.CENTER_VERTICAL);TextView title=text(editing?"编辑 MCP 服务器":"添加 MCP 服务器",16,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));TextView close=iconButton("×");close.setTextSize(22);close.setOnClickListener(v->d.dismiss());titleRow.addView(close,lp(dp(40),dp(40)));panel.addView(titleRow,lp(-1,dp(46)));
        LinearLayout form=vbox();
        EditText name=input(base.name,false);name.setHint("例如 filesystem");form.addView(labeled("服务器名称",name));
        String[] typeLabels={"标准输入输出（stdio）","Streamable HTTP","旧版 SSE"};String[] typeValues={"stdio","http","sse"};Spinner type=darkSpinner(typeLabels,typeValues,base.type);form.addView(labeled("连接方式",type));
        EditText command=input(base.command,false);command.setHint("例如 npx");form.addView(labeled("启动命令（stdio）",command));
        EditText args=input(joinLines(base.args),false);args.setHint("参数用换行分隔，例如 -y\\n@modelcontextprotocol/server-filesystem");args.setSingleLine(false);args.setMinLines(2);args.setMaxLines(4);args.setGravity(Gravity.TOP);form.addView(labeledTall("命令参数（每行一个）",args,dp(82)));
        EditText url=input(base.url,false);url.setHint("例如 http://127.0.0.1:8787/mcp");form.addView(labeled("服务器 URL（HTTP/SSE）",url));
        EditText env=input(base.env==null||base.env.length()==0?"":base.env.toString(),false);env.setHint("{\"TOKEN\":\"...\"}");form.addView(labeled("环境变量 JSON（stdio，可选）",env));
        EditText headers=input(base.headers==null||base.headers.length()==0?"":base.headers.toString(),false);headers.setHint("{\"Authorization\":\"Bearer ...\"}");form.addView(labeled("请求头 JSON（HTTP/SSE，可选）",headers));
        String[] scopeLabels={"用户级（所有项目）","项目级（当前项目）"};String[] scopeValues={"user","project"};Spinner scope=darkSpinner(scopeLabels,scopeValues,base.scope);form.addView(labeled("作用范围",scope));
        String[] enabledLabels={"启用","停用"};String[] enabledValues={"true","false"};Spinner enabled=darkSpinner(enabledLabels,enabledValues,base.enabled?"true":"false");form.addView(labeled("状态",enabled));
        ScrollView sv=new ScrollView(this);sv.addView(form);panel.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=hbox();footer.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView cancel=text("取消",11,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->d.dismiss());footer.addView(cancel,lp(dp(72),dp(38)));TextView save=text("保存",11,TEXT);save.setTypeface(Typeface.DEFAULT_BOLD);save.setGravity(Gravity.CENTER);save.setBackground(round(ACCENT,14,Color.TRANSPARENT,0));save.setOnClickListener(v->{try{McpConfigStore.Server out=new McpConfigStore.Server();out.name=name.getText().toString().trim();if(out.name.isEmpty())throw new Exception("服务器名称不能为空");int ti=type.getSelectedItemPosition();out.type=typeValues[Math.max(0,ti)];out.command=command.getText().toString().trim();out.url=url.getText().toString().trim();if("stdio".equals(out.type)&&out.command.isEmpty())throw new Exception("stdio 服务器必须填写启动命令");if(!"stdio".equals(out.type)&&out.url.isEmpty())throw new Exception("HTTP/SSE 服务器必须填写 URL");for(String line:args.getText().toString().split("\\r?\\n")){String x=line.trim();if(!x.isEmpty())out.args.add(x);}String ev=env.getText().toString().trim();out.env=ev.isEmpty()?new JSONObject():new JSONObject(ev);String hv=headers.getText().toString().trim();out.headers=hv.isEmpty()?new JSONObject():new JSONObject(hv);out.scope=scopeValues[Math.max(0,scope.getSelectedItemPosition())];out.enabled=enabled.getSelectedItemPosition()==0;List<McpConfigStore.Server> all=mcpStore.load();for(McpConfigStore.Server x:all)if(!editing&&x.name.equalsIgnoreCase(out.name))throw new Exception("已存在同名 MCP 服务器");if(editing){boolean replaced=false;for(int i=0;i<all.size();i++)if(all.get(i).name.equalsIgnoreCase(existing.name)){all.set(i,out);replaced=true;break;}if(!replaced)all.add(out);}else all.add(out);mcpStore.save(all);d.dismiss();toast("MCP 配置已保存");showMcpPanel();}catch(Exception e){toast("保存失败："+e.getMessage());}});footer.addView(save,lp(dp(78),dp(38)));panel.addView(footer,lp(-1,dp(48)));
        d.setContentView(panel);d.show();Window w=d.getWindow();if(w!=null){if(overlayShell!=null&&android.os.Build.VERSION.SDK_INT>=26)w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=.62f;w.setAttributes(a);int width=Math.min(dp(wide?620:520),(int)(getResources().getDisplayMetrics().widthPixels*.92f));int height=Math.min(dp(760),(int)(getResources().getDisplayMetrics().heightPixels*.90f));w.setLayout(width,height);UiMotion.bindInteractive(w.getDecorView());UiMotion.dialogIn(w.getDecorView());}
    }

    private String joinLines(List<String> values){StringBuilder b=new StringBuilder();for(String x:values){if(b.length()>0)b.append('\n');b.append(x);}return b.toString();}
    private View labeledTall(String label,View field,int height){LinearLayout box=vbox();TextView l=text(label,10,MUTED);l.setPadding(dp(2),dp(5),0,0);box.addView(l,lp(-1,dp(25)));box.addView(field,lp(-1,height));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,0,0,dp(5));box.setLayoutParams(bp);return box;}

    private void linkStorage(File link,String target)throws Exception{try{link.delete();}catch(Throwable ignored){} android.system.Os.symlink(target,link.getAbsolutePath());}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==4242){boolean ok=grantResults.length>0;for(int x:grantResults)ok&=x==android.content.pm.PackageManager.PERMISSION_GRANTED;if(ok){createStorageLinks();toast("存储权限已开启");}else toast("未授予存储权限，可在设置中稍后开启");return;}
        if(requestCode==4243){boolean ok=grantResults.length>0&&grantResults[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;toast(ok?"通知权限已开启":"通知权限未开启，可稍后在系统设置中修改");requestStorageAccessWithExplanation(true);}
    }

    private void showPermissionPicker() {
        if(isPermissionModeLocked()){
            toast("本轮任务已锁定为 "+permissionLabelFor(activePermissionMode())+"；完成后可切换");
            return;
        }
        String[] labels={"每次询问","自动允许文件编辑","自动判断","规划模式（只读）","不主动询问","跳过权限检查"};
        String[] values={"default","acceptEdits","auto","plan","dontAsk","bypassPermissions"};
        int checked=Math.max(0,Arrays.asList(values).indexOf(config.permissionMode));
        showChoicePicker("权限模式",labels,checked,which->{config.permissionMode=values[which];saveConfigQuiet();updateComposerChips();});
    }

    private void handleRootSlash(String arg){
        String value=arg==null?"":arg.trim().toLowerCase(Locale.US);
        if("on".equals(value)||"enable".equals(value)){config.rootExecutionEnabled=true;saveConfigQuiet();toast("Agent Root 已开启；Root 调用将请求 su 授权");return;}
        if("off".equals(value)||"disable".equals(value)){config.rootExecutionEnabled=false;saveConfigQuiet();if(config.forcedKeepAliveEnabled)KeepAliveService.start(this,false);toast("Agent Root 已关闭");return;}
        if("check".equals(value)||"status".equals(value)){checkRootAccess();return;}
        showChoicePicker("Agent Root 权限",new String[]{"关闭 Root 工具","开启 Root 工具","检测 Magisk/KernelSU Root"},config.rootExecutionEnabled?1:0,which->{if(which==2){checkRootAccess();return;}config.rootExecutionEnabled=which==1;saveConfigQuiet();toast(config.rootExecutionEnabled?"Agent Root 已开启":"Agent Root 已关闭");});
    }

    private void handleKeepAliveSlash(String arg){
        String value=arg==null?"":arg.trim().toLowerCase(Locale.US);
        if("on".equals(value)||"enable".equals(value)){config.forcedKeepAliveEnabled=true;saveConfigQuiet();KeepAliveService.start(this,config.rootExecutionEnabled);toast("强制后台保活已开启；Root 可用时会自动强化系统后台策略");return;}
        if("off".equals(value)||"disable".equals(value)||"revoke".equals(value)){config.forcedKeepAliveEnabled=false;saveConfigQuiet();KeepAliveService.stop(this);toast("强制后台保活已关闭");return;}
        toast(config.forcedKeepAliveEnabled?"强制后台保活：已开启":"强制后台保活：未开启；使用 /keepalive on 开启");
    }

    private void checkRootAccess(){
        toast("正在请求 Root 管理器验证…");
        io.execute(()->{try{
            TermuxShellExecutor.Result result=new TermuxShellExecutor(this).executeAsRoot("uid=$(id -u); printf 'uid=%s\\n' \"$uid\"; id; [ \"$uid\" = 0 ]",config.projectDirectory,60_000);
            String detail=result.combined();boolean ok=result.exitCode==0&&!result.timedOut&&detail.contains("uid=0");
            ui(()->showAnimatedAlert(new AlertDialog.Builder(this).setTitle(ok?"Root 可用":"Root 不可用").setMessage(ok?"IQ Code 已通过 su 获得 Android uid 0。\\n\\n"+detail:"没有获得 uid 0。请确认设备已安装 Magisk/KernelSU，并在 Root 管理器中允许 com.iqge。\\n\\n"+detail).setPositiveButton("确定",null).create()));
        }catch(Exception e){ui(()->showAnimatedAlert(new AlertDialog.Builder(this).setTitle("Root 不可用").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("确定",null).create()));}});
    }

    private void showEffortPicker() {
        String[] values=EFFORT_UI_VALUES;
        String[] labels=EFFORT_UI_LABELS;
        int checked=Math.max(0,Arrays.asList(values).indexOf(uiEffortValue(config.effort)));
        showChoicePicker("推理强度",labels,checked,which->applyReasoningEffort(values[which],true));
    }

    private static String uiEffortValue(String value) {
        if (value == null) return "auto";
        String v=value.trim().toLowerCase(Locale.US);
        if ("minimal".equals(v)) return "low";
        if ("adaptive".equals(v)) return "auto";
        if ("xhigh".equals(v)||"xxhigh".equals(v)||"ultra".equals(v)) return "max";
        return Arrays.asList(EFFORT_UI_VALUES).contains(v) ? v : "auto";
    }

    private static String effortLabel(String value) {
        String selected=uiEffortValue(value);
        int index=Arrays.asList(EFFORT_UI_VALUES).indexOf(selected);
        return index<0 ? EFFORT_UI_LABELS[1] : EFFORT_UI_LABELS[index];
    }

    private void applyReasoningEffort(String effort,boolean notify){
        String selected=effort==null?"auto":effort.trim();if(selected.isEmpty())selected="auto";
        config.effort=selected;
        SessionRuntime rt=activeRuntime;
        boolean busy=rt!=null&&rt.engine.isBusy();
        if(rt!=null){
            if(rt.nextConfig==null)rt.nextConfig=config.copy();else rt.nextConfig.effort=selected;
            if(busy)rt.engine.updateReasoningEffort(selected);else rt.engine.configure(rt.nextConfig.copy());
        }
        try{settingsStore.save(config);refreshChrome();updateComposerChips();if(notify)toast(busy?"推理强度已更新，将从下一次模型请求生效":"推理强度已更新");}
        catch(Exception error){toast("保存失败："+error.getMessage());}
    }

    /** Opens the role-card editor from the mobile workspace menu. */
    private boolean editingRoleCard;
    private void showRoleCardDialog() {
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        LinearLayout head=hbox();TextView title=text("自定义角色卡",16,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);head.addView(title,new LinearLayout.LayoutParams(0,dp(42),1));TextView close=iconButton("×");close.setOnClickListener(v->d.dismiss());head.addView(close,lp(dp(40),dp(40)));panel.addView(head,lp(-1,dp(44)));
        TextView hint=text("可添加多个角色卡；点击卡片即可选择，选中内容会注入每次模型请求的系统上下文。",10,MUTED);panel.addView(hint,lp(-1,dp(42)));
        LinearLayout rows=vbox();JSONArray cards=settingsStore.getRoleCards();String active=settingsStore.getActiveRoleCardId();
        for(int i=0;i<cards.length();i++){JSONObject card=cards.optJSONObject(i);if(card==null)continue;String id=card.optString("id");TextView row=text((id.equals(active)?"● ":"○ ")+card.optString("name","未命名角色"),12,TEXT);row.setPadding(dp(10),dp(8),dp(10),dp(8));row.setTypeface(Typeface.DEFAULT_BOLD);if(id.equals(active))row.setBackground(round(ACCENT,12,Color.TRANSPARENT,0));row.setOnClickListener(v->{try{applyRoleCardToRuntimes(card.optString("content",""));settingsStore.saveRoleCards(cards,id);d.dismiss();toast("已启用角色卡："+card.optString("name"));}catch(Exception e){toast("选择失败："+e.getMessage());}});row.setOnLongClickListener(v->{showRoleCardActions(card,cards,d);return true;});rows.addView(row,lp(-1,dp(44)));}
        ScrollView scroll=new ScrollView(this);scroll.addView(rows);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView disable=pill("停用当前角色卡",MUTED);disable.setGravity(Gravity.CENTER);disable.setOnClickListener(v->{try{settingsStore.saveRoleCards(cards,"");applyRoleCardToRuntimes("");d.dismiss();toast("角色卡已停用");}catch(Exception e){toast("停用失败："+e.getMessage());}});panel.addView(disable,lp(-1,dp(40)));
        TextView add=pill("＋ 添加角色卡",TEXT);add.setGravity(Gravity.CENTER);add.setOnClickListener(v->{d.dismiss();showRoleCardEditor(null);});panel.addView(add,lp(-1,dp(44)));
        d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(480),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),Math.min(dp(620),(int)(getResources().getDisplayMetrics().heightPixels*.82f)));
    }

    private void showRoleCardActions(JSONObject card, JSONArray cards, Dialog parent){
        final Dialog menu=newOverlayAwareDialog();menu.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout p=vbox();p.setPadding(dp(14),dp(12),dp(14),dp(12));p.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView title=text("角色卡操作",15,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);p.addView(title,lp(-1,dp(40)));
        TextView edit=pill("编辑角色卡",TEXT);edit.setGravity(Gravity.CENTER);edit.setOnClickListener(v->{menu.dismiss();parent.dismiss();showRoleCardEditor(card);});p.addView(edit,lp(-1,dp(44)));
        TextView remove=pill("删除角色卡",RED);remove.setGravity(Gravity.CENTER);remove.setOnClickListener(v->{try{for(int i=cards.length()-1;i>=0;i--)if(card.optString("id").equals(cards.optJSONObject(i).optString("id")))cards.remove(i);String active=settingsStore.getActiveRoleCardId();if(card.optString("id").equals(active)){settingsStore.saveRoleCards(cards,"");applyRoleCardToRuntimes("");}else settingsStore.saveRoleCards(cards,active);menu.dismiss();parent.dismiss();showRoleCardDialog();toast("角色卡已删除");}catch(Exception e){toast("删除失败："+e.getMessage());}});p.addView(remove,lp(-1,dp(44)));
        TextView cancel=pill("取消",MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->menu.dismiss());p.addView(cancel,lp(-1,dp(40)));menu.setContentView(p);menu.show();styleDialogWindow(menu,Math.min(dp(360),(int)(getResources().getDisplayMetrics().widthPixels*.86f)),-2);
    }

    private void showRoleCardEditor(JSONObject existing){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout p=vbox();p.setPadding(dp(14),dp(12),dp(14),dp(12));p.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        EditText name=input(existing==null?"":existing.optString("name",""),false);name.setHint("角色名称");p.addView(name,lp(-1,dp(48)));EditText body=input(existing==null?"":existing.optString("content",""),false);body.setHint("角色卡内容");body.setSingleLine(false);body.setMaxLines(Integer.MAX_VALUE);body.setGravity(Gravity.TOP|Gravity.START);body.setMinLines(8);p.addView(body,new LinearLayout.LayoutParams(-1,0,1));TextView save=pill("保存",TEXT);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->{try{JSONArray a=settingsStore.getRoleCards();JSONObject c=existing==null?new JSONObject().put("id",java.util.UUID.randomUUID().toString()):existing;c.put("name",name.getText().toString().trim());c.put("content",body.getText().toString());if(existing==null)a.put(c);settingsStore.saveRoleCards(a,c.optString("id"));applyRoleCardToRuntimes(c.optString("content"));d.dismiss();toast("角色卡已保存并启用");showRoleCardDialog();}catch(Exception e){toast("保存失败："+e.getMessage());}});p.addView(save,lp(-1,dp(44)));d.setContentView(p);d.show();styleDialogWindow(d,Math.min(dp(500),(int)(getResources().getDisplayMetrics().widthPixels*.94f)),Math.min(dp(680),(int)(getResources().getDisplayMetrics().heightPixels*.86f)));
    }

    private void showOtherSettings(){
        final Dialog dialog=newOverlayAwareDialog();dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(16),dp(12),dp(16),dp(12));panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout header=hbox();header.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("其他设置",17,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));TextView close=iconButton("×");close.setOnClickListener(v->dialog.dismiss());header.addView(close,lp(dp(40),dp(40)));panel.addView(header,lp(-1,dp(46)));
        TextView hint=text("自定义头部提示词会作为系统指令随模型请求发送。它只从下一完整任务生效，不能覆盖应用安全规则、权限模式、工具白名单或 Root 限制。请勿填写 API 密钥。",10.5f,MUTED);hint.setLineSpacing(0,1.12f);panel.addView(hint,lp(-1,-2));
        EditText editor=new EditText(this);editor.setText((editingRoleCard?config.roleCard:config.customSystemPrompt)==null?"":(editingRoleCard?config.roleCard:config.customSystemPrompt));editor.setTextColor(TEXT);editor.setHintTextColor(MUTED_2);editor.setHint("例如：始终先给出简短计划，再执行修改…");editor.setGravity(Gravity.TOP|Gravity.START);editor.setTextSize(12.5f);editor.setPadding(dp(11),dp(10),dp(11),dp(10));editor.setMinLines(8);editor.setMaxLines(16);editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);editor.setBackground(round(SURFACE_3,15,Color.TRANSPARENT,0));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,0,1);ep.setMargins(0,dp(12),0,dp(6));panel.addView(editor,ep);
        TextView count=text("提示词长度不设上限（受模型上下文窗口限制）",9.5f,MUTED_2);count.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);panel.addView(count,lp(-1,dp(24)));
        LinearLayout footer=hbox();footer.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView clear=text("清空",10.5f,MUTED);clear.setGravity(Gravity.CENTER);clear.setOnClickListener(v->{editor.setText("");editor.requestFocus();});footer.addView(clear,lp(dp(70),dp(40)));TextView cancel=text("取消",10.5f,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->dialog.dismiss());footer.addView(cancel,lp(dp(70),dp(40)));TextView save=pill("保存",TEXT);save.setGravity(Gravity.CENTER);save.setOnClickListener(v->{try{String value=editor.getText().toString();boolean role=editingRoleCard;if(role)applyRoleCardToRuntimes(value);else applyCustomSystemPromptToRuntimes(value);editingRoleCard=false;dialog.dismiss();toast((role?"角色卡":"自定义头部提示词")+"已保存，将从下一完整任务生效");}catch(Exception error){toast("保存失败："+error.getMessage());}});footer.addView(save,lp(dp(78),dp(40)));panel.addView(footer,lp(-1,dp(48)));
        dialog.setContentView(panel);dialog.show();Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setGravity(Gravity.CENTER);int width=Math.min(dp(wide?620:500),(int)(getResources().getDisplayMetrics().widthPixels*.92f));int height=Math.min(dp(wide?720:680),(int)(getResources().getDisplayMetrics().heightPixels*.88f));w.setLayout(width,height);UiMotion.bindInteractive(w.getDecorView());UiMotion.dialogIn(w.getDecorView());}
    }

    private void applyRoleCardToRuntimes(String value)throws Exception{
        SessionConfig saved=config.copy();saved.roleCard=value;settingsStore.save(saved);config=saved;
        for(SessionRuntime rt:new ArrayList<>(sessionRuntimes.values())){if(rt==null)continue;if(rt.nextConfig==null)rt.nextConfig=config.copy();else rt.nextConfig.roleCard=value;if(!rt.engine.isBusy())rt.engine.configure(rt.nextConfig.copy());}
    }

    private void applyCustomSystemPromptToRuntimes(String value)throws Exception{
        SessionConfig saved=config.copy();saved.customSystemPrompt=value;settingsStore.save(saved);config=saved;
        for(SessionRuntime rt:new ArrayList<>(sessionRuntimes.values())){if(rt==null)continue;if(rt.nextConfig==null)rt.nextConfig=config.copy();else rt.nextConfig.customSystemPrompt=config.customSystemPrompt;if(!rt.engine.isBusy())rt.engine.configure(rt.nextConfig.copy());}
        SessionRuntime rt=activeRuntime;if(rt!=null&&!sessionRuntimes.containsValue(rt)){if(rt.nextConfig==null)rt.nextConfig=config.copy();else rt.nextConfig.customSystemPrompt=config.customSystemPrompt;if(!rt.engine.isBusy())rt.engine.configure(rt.nextConfig.copy());}
        refreshChrome();updateComposerChips();
    }

    private void showUiCanvasPanel(){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout p=vbox();p.setPadding(dp(18),dp(12),dp(18),dp(12));p.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        LinearLayout h=hbox();h.setGravity(Gravity.CENTER_VERTICAL);TextView t=text("运行时 UI 画布",17,TEXT);t.setTypeface(Typeface.DEFAULT_BOLD);h.addView(t,new LinearLayout.LayoutParams(0,dp(44),1));TextView x=iconButton("×");x.setOnClickListener(v->d.dismiss());h.addView(x,lp(dp(40),dp(40)));p.addView(h,lp(-1,dp(46)));
        TextView info=text("仅修改当前运行中的允许节点：颜色、可见性、间距、文字大小和安全顺序。变更由 Agent 的 ui_canvas 工具或源代码完成；新行为仍应编辑源码。",10.5f,MUTED);info.setLineSpacing(0,1.12f);p.addView(info,lp(-1,-2));
        LinearLayout actions=hbox();actions.setGravity(Gravity.CENTER_VERTICAL);TextView reset=text("恢复默认",11,MUTED);reset.setGravity(Gravity.CENTER);reset.setOnClickListener(v->{UiCanvasStore.reset(this);rebuildWorkspaceForTheme();d.dismiss();});actions.addView(reset,lp(dp(90),dp(42)));TextView export=text("复制 JSON",11,TEXT);export.setGravity(Gravity.CENTER);export.setOnClickListener(v->{android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText("ui_canvas",UiCanvasStore.export(UiCanvasStore.load(this))));toast("UI 画布 JSON 已复制");});actions.addView(export,lp(dp(100),dp(42)));TextView close=text("完成",11,TEXT);close.setGravity(Gravity.CENTER);close.setBackground(round(ACCENT,14,Color.TRANSPARENT,0));close.setOnClickListener(v->d.dismiss());actions.addView(close,new LinearLayout.LayoutParams(dp(78),dp(38)));p.addView(actions,lp(-1,dp(54)));d.setContentView(p);d.show();Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setLayout(Math.min(dp(480),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);}
    }

    private void showSettings() {
        final Dialog dialog=newOverlayAwareDialog();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(18),dp(12),dp(18),dp(12)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));

        LinearLayout titleRow=hbox(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("IQ Code 设置",17,TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView x=iconButton("×"); x.setTextSize(22); x.setOnClickListener(v->dialog.dismiss()); titleRow.addView(x,lp(dp(40),dp(40))); panel.addView(titleRow,lp(-1,dp(46)));

        LinearLayout form=vbox(); form.setPadding(0,dp(4),0,dp(8));
        View appearanceSection=settingsSection("外观");form.addView(appearanceSection);
        String[] themeValues={THEME_NIGHT,THEME_DAY};String[] themeLabels={"夜间模式","白天模式"};Spinner themeMode=darkSpinner(themeLabels,themeValues,lightTheme?THEME_DAY:THEME_NIGHT);form.addView(labeled("主题模式",themeMode));
        TextView themeModeHint=text("右上角 ☼/☾ 可快速切换；切换时会平滑重建界面。",9.5f,MUTED_2);themeModeHint.setPadding(dp(2),0,dp(2),dp(8));form.addView(themeModeHint,lp(-1,-2));
        EditText paletteBackground=paletteInput(BG);form.addView(labeled("背景色",paletteBackground));
        EditText paletteSurface=paletteInput(SURFACE);form.addView(labeled("面板色",paletteSurface));
        EditText paletteText=paletteInput(TEXT);form.addView(labeled("文字色",paletteText));
        EditText paletteAccent=paletteInput(ACCENT);form.addView(labeled("强调色",paletteAccent));
        EditText paletteGreen=paletteInput(GREEN);form.addView(labeled("成功色",paletteGreen));
        EditText paletteRed=paletteInput(RED);form.addView(labeled("错误色",paletteRed));
        TextView themeHint=text("输入 #RRGGBB 颜色；边框、次级文字和层级表面会由调色板自动生成。",9.5f,MUTED_2);themeHint.setPadding(dp(2),0,dp(2),dp(8));form.addView(themeHint,lp(-1,-2));
        View modelSection=settingsSection("模型与权限");form.addView(modelSection);
        TextView apiProfiles=text(currentProfileName()+" · "+config.protocol+" · "+shorten(config.model,30)+"  ›",11.5f,TEXT);apiProfiles.setGravity(Gravity.CENTER_VERTICAL);apiProfiles.setPadding(dp(12),0,dp(12),0);apiProfiles.setBackground(round(SURFACE_3,15,Color.TRANSPARENT,0));apiProfiles.setOnClickListener(v->{dialog.dismiss();showApiProfileManager();});form.addView(labeled("API 配置记录",apiProfiles));
        TextView apiHint=text("API 地址、协议、默认模型和密钥按配置记录独立保存；密钥不会在设置页回填。点模型按钮可自动获取当前 API 的模型。",9.5f,MUTED_2);apiHint.setPadding(dp(2),0,dp(2),dp(8));form.addView(apiHint,lp(-1,-2));
        String[] visionValues={"on","off"}; String[] visionLabels={"开启（发送图片给模型）","关闭（仅保留本地预览）"};
        Spinner vision=darkSpinner(visionLabels,visionValues,config.visionEnabled?"on":"off"); form.addView(labeled("视觉图片输入（Vision）",vision));
        TextView visionHint=text("模型不支持 vision 时请选择关闭；当前图片和历史图片都不会发送，但聊天预览与会话记录仍会保留。",9.5f,MUTED_2);visionHint.setPadding(dp(2),0,dp(2),dp(8));form.addView(visionHint,lp(-1,-2));

        String[] effortValues=EFFORT_UI_VALUES;
        String[] effortLabels=EFFORT_UI_LABELS;
        Spinner effort=darkSpinner(effortLabels,effortValues,uiEffortValue(config.effort));form.addView(labeled("推理强度",effort));

        String[] permValues={"default","acceptEdits","auto","plan","dontAsk","bypassPermissions"};
        String[] permLabels={"每次询问","自动允许文件编辑","自动判断","规划模式（只读）","不主动询问","跳过权限检查"};
        boolean permissionLocked=isPermissionModeLocked();
        Spinner perm=darkSpinner(permLabels,permValues,permissionLocked?activePermissionMode():config.permissionMode);
        if(permissionLocked){perm.setEnabled(false);perm.setAlpha(.55f);}
        form.addView(labeled("权限模式",perm));
        if(permissionLocked){TextView permissionLockHint=text("当前任务已锁定为 "+permissionLabelFor(activePermissionMode())+"；任务结束后可修改。",9.5f,ACCENT);permissionLockHint.setPadding(dp(2),0,dp(2),dp(8));form.addView(permissionLockHint,lp(-1,-2));}
        View agentSection=settingsSection("Agent 与安全");form.addView(agentSection);
        String[] sandboxAccessValues={"on","off"};String[] sandboxAccessLabels={"开启（Agent 对 IQSandbox Guest 全权限，不逐次询问）","关闭（跟随普通权限模式）"};
        Spinner sandboxAccess=darkSpinner(sandboxAccessLabels,sandboxAccessValues,config.sandboxAgentFullAccess?"on":"off");form.addView(labeled("Agent 沙箱全权调试",sandboxAccess));
        TextView sandboxAccessHint=text("只绕过 IQSandbox/Sandbox Debug 的逐次确认：允许容器内安装、UI 操作、动态内存、Frida Hook/脚本。不会自动放开真机 host、Root、zygote/SystemUI 或其他无关 App。",9.5f,ACCENT);sandboxAccessHint.setPadding(dp(2),0,dp(2),dp(8));form.addView(sandboxAccessHint,lp(-1,-2));
        String[] rootValues={"off","on"};String[] rootLabels={"关闭（不向 Agent 暴露 Root 工具）","开启（允许 Agent 请求 uid 0）"};
        Spinner rootAccess=darkSpinner(rootLabels,rootValues,config.rootExecutionEnabled?"on":"off");form.addView(labeled("Agent Root 权限",rootAccess));
        TextView rootHint=text("高风险功能：需要 Magisk/KernelSU 和 su 授权。开启后主 Agent 与通用子 Agent可请求 Root；普通权限模式仍会逐次确认。点此检测 Root。",9.5f,RED);rootHint.setPadding(dp(2),0,dp(2),dp(8));rootHint.setOnClickListener(v->checkRootAccess());form.addView(rootHint,lp(-1,-2));
        String[] keepAliveValues={"off","on"};String[] keepAliveLabels={"关闭","开启（前台服务 + WakeLock；Root 时强制系统后台策略）"};
        Spinner keepAlive=darkSpinner(keepAliveLabels,keepAliveValues,config.forcedKeepAliveEnabled?"on":"off");form.addView(labeled("强制后台保活",keepAlive));

        View networkSection=settingsSection("联网");form.addView(networkSection);
        String[] webEnabledLabels={"开启联网搜索","关闭联网搜索"}; String[] webEnabledValues={"on","off"};
        Spinner webEnabled=darkSpinner(webEnabledLabels,webEnabledValues,config.webSearchEnabled?"on":"off"); form.addView(labeled("联网搜索",webEnabled));
        String[] webProviderValues={"auto","duckduckgo","bing"}; String[] webProviderLabels={"自动（DuckDuckGo → Bing）","DuckDuckGo","Bing RSS"};
        Spinner webProvider=darkSpinner(webProviderLabels,webProviderValues,config.webSearchProvider); form.addView(labeled("搜索后端",webProvider));
        EditText webResults=input(Integer.toString(config.webSearchMaxResults),false); webResults.setInputType(InputType.TYPE_CLASS_NUMBER); form.addView(labeled("默认搜索结果数（1-10）",webResults));
        EditText webTimeout=input(Integer.toString(Math.max(1,config.webTimeoutMs/1000)),false); webTimeout.setInputType(InputType.TYPE_CLASS_NUMBER); form.addView(labeled("联网超时（秒）",webTimeout));

        View contextSection=settingsSection("上下文与项目");form.addView(contextSection);
        EditText contextWindow=input(formatTokenCount(config.contextWindowTokens),false); contextWindow.setHint("128k / 200k / 1m / 1.5m"); contextWindow.setInputType(InputType.TYPE_CLASS_TEXT); form.addView(labeled("上下文窗口",contextWindow));
        String[] compactLabels={"开启自动压缩","关闭自动压缩"}; String[] compactValues={"on","off"};
        Spinner autoCompact=darkSpinner(compactLabels,compactValues,config.autoCompact?"on":"off"); form.addView(labeled("上下文压缩",autoCompact));
        EditText compactAt=input(Integer.toString((int)Math.round(config.autoCompactRatio*100)),false); compactAt.setInputType(InputType.TYPE_CLASS_NUMBER); form.addView(labeled("自动压缩上限（50–100%，安全缓冲优先）",compactAt));
        EditText project=input(config.projectDirectory,false);project.setHint("/storage/emulated/0/项目 或 /data/user/0/com.iqge/files/home/projects/项目名");form.addView(labeled("项目目录",project));
        TextView projectHint=text("项目路径与上下文历史一一绑定；保存新路径后会自动显示该项目可恢复的历史记录。",9.5f,MUTED_2);projectHint.setPadding(dp(2),0,dp(2),dp(8));form.addView(projectHint,lp(-1,-2));

        View extensionsSection=settingsSection("扩展功能");form.addView(extensionsSection);
        TextView mcpEntry=text("MCP 服务器配置  ›",11.5f,TEXT);mcpEntry.setGravity(Gravity.CENTER_VERTICAL);mcpEntry.setPadding(dp(12),0,dp(12),0);mcpEntry.setBackground(round(SURFACE_3,15,Color.TRANSPARENT,0));mcpEntry.setOnClickListener(v->{dialog.dismiss();showMcpPanel();});form.addView(labeled("Model Context Protocol（MCP）",mcpEntry));
        TextView canvasEntry=text("运行时 UI 画布  ›",11.5f,TEXT);canvasEntry.setGravity(Gravity.CENTER_VERTICAL);canvasEntry.setPadding(dp(12),0,dp(12),0);canvasEntry.setBackground(round(SURFACE_3,15,Color.TRANSPARENT,0));canvasEntry.setOnClickListener(v->{dialog.dismiss();showUiCanvasPanel();});form.addView(labeled("无需重编译的界面调整",canvasEntry));
        TextView otherEntry=text("自定义头部提示词  ›",11.5f,TEXT);otherEntry.setGravity(Gravity.CENTER_VERTICAL);otherEntry.setPadding(dp(12),0,dp(12),0);otherEntry.setBackground(round(SURFACE_3,15,Color.TRANSPARENT,0));otherEntry.setOnClickListener(v->{dialog.dismiss();showOtherSettings();});form.addView(labeled("其他设置",otherEntry));

        View[] sectionMarkers={appearanceSection,modelSection,agentSection,networkSection,contextSection,extensionsSection};
        String[] sectionLabels={"外观","模型与权限","Agent 与安全","联网","上下文与项目","扩展功能"};
        ArrayList<LinearLayout> pageLayouts=new ArrayList<>();
        for(int i=0;i<sectionMarkers.length;i++){LinearLayout page=vbox();page.setPadding(dp(2),0,dp(2),dp(8));pageLayouts.add(page);}
        ArrayList<View> settingChildren=new ArrayList<>();for(int i=0;i<form.getChildCount();i++)settingChildren.add(form.getChildAt(i));
        int pageIndex=0;
        for(View child:settingChildren){for(int i=0;i<sectionMarkers.length;i++)if(child==sectionMarkers[i])pageIndex=i;form.removeView(child);pageLayouts.get(pageIndex).addView(child);}
        HorizontalScrollView categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);categoryScroll.setFillViewport(true);
        LinearLayout categories=hbox();categories.setGravity(Gravity.CENTER_VERTICAL);categories.setPadding(0,dp(2),0,dp(4));
        FrameLayout pageHost=new FrameLayout(this);ArrayList<ScrollView> pageScrolls=new ArrayList<>();
        for(int i=0;i<pageLayouts.size();i++){final int selected=i;ScrollView pageScroll=new ScrollView(this);pageScroll.setFillViewport(true);pageScroll.setVerticalScrollBarEnabled(false);pageScroll.addView(pageLayouts.get(i));pageScroll.setVisibility(i==0?View.VISIBLE:View.GONE);pageHost.addView(pageScroll,new FrameLayout.LayoutParams(-1,-1));pageScrolls.add(pageScroll);
            TextView tab=settingsTab(sectionLabels[i],()->{for(int j=0;j<pageScrolls.size();j++)pageScrolls.get(j).setVisibility(j==selected?View.VISIBLE:View.GONE);categoryScroll.smoothScrollTo(Math.max(0,tabLeft(categories,selected)-dp(18)),0);});LinearLayout.LayoutParams tabLp=new LinearLayout.LayoutParams(-2,dp(40));tabLp.setMargins(0,0,dp(6),0);categories.addView(tab,tabLp);}
        categoryScroll.addView(categories,new android.widget.FrameLayout.LayoutParams(-1,dp(44)));panel.addView(categoryScroll,lp(-1,dp(48)));panel.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout footer=hbox(); footer.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); footer.setPadding(0,dp(8),0,0);
        TextView cancel=text("取消",11,MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->dialog.dismiss());footer.addView(cancel,lp(dp(78),dp(38)));
        TextView save=text("保存",11,TEXT);save.setTypeface(Typeface.DEFAULT_BOLD);save.setGravity(Gravity.CENTER);save.setBackground(round(ACCENT,14,Color.TRANSPARENT,0));
        save.setOnClickListener(v->{try{
            int selectedBackground=parsePaletteColor(paletteBackground),selectedSurface=parsePaletteColor(paletteSurface),selectedText=parsePaletteColor(paletteText),selectedAccent=parsePaletteColor(paletteAccent),selectedGreen=parsePaletteColor(paletteGreen),selectedRed=parsePaletteColor(paletteRed);
            boolean paletteChanged=selectedBackground!=BG||selectedSurface!=SURFACE||selectedText!=TEXT||selectedAccent!=ACCENT||selectedGreen!=GREEN||selectedRed!=RED;
            String selectedTheme=themeValues[Math.max(0,themeMode.getSelectedItemPosition())];
            config.visionEnabled=vision.getSelectedItemPosition()==0;
            String selectedEffort=effortValues[Math.max(0,effort.getSelectedItemPosition())];
            config.effort=selectedEffort;SessionRuntime effortRuntime=activeRuntime;if(effortRuntime!=null){if(effortRuntime.nextConfig==null)effortRuntime.nextConfig=config.copy();else effortRuntime.nextConfig.effort=selectedEffort;if(effortRuntime.engine.isBusy())effortRuntime.engine.updateReasoningEffort(selectedEffort);}
            if(!isPermissionModeLocked())config.permissionMode=permValues[Math.max(0,perm.getSelectedItemPosition())];
            config.sandboxAgentFullAccess=sandboxAccess.getSelectedItemPosition()==0;
            config.rootExecutionEnabled=rootAccess.getSelectedItemPosition()==1;
            config.forcedKeepAliveEnabled=keepAlive.getSelectedItemPosition()==1;
            config.webSearchEnabled=webEnabled.getSelectedItemPosition()==0;
            config.webSearchProvider=webProviderValues[Math.max(0,webProvider.getSelectedItemPosition())];
            config.webSearchMaxResults=Math.max(1,Math.min(10,Integer.parseInt(webResults.getText().toString().trim())));
            config.webTimeoutMs=Math.max(5000,Math.min(60000,Integer.parseInt(webTimeout.getText().toString().trim())*1000));
            config.contextWindowTokens=parseTokenCount(contextWindow.getText().toString());
            config.autoCompact=autoCompact.getSelectedItemPosition()==0;
            int compactPct=Math.max(50,Math.min(100,Integer.parseInt(compactAt.getText().toString().trim()))); config.autoCompactRatio=compactPct/100.0;
            String requestedProject=project.getText().toString().trim();
            boolean projectChanged=switchProjectPath(requestedProject,false);
            settingsStore.save(config);if(config.forcedKeepAliveEnabled)KeepAliveService.start(this,config.rootExecutionEnabled);else KeepAliveService.stop(this);if(!projectChanged&&engine!=null&&!engine.isBusy())engine.configure(config);dialog.dismiss();if(paletteChanged)saveAndApplyCustomPalette(selectedBackground,selectedSurface,selectedText,selectedAccent,selectedGreen,selectedRed);else saveAndApplyUiTheme(selectedTheme);updateComposerChips();toast(projectChanged?"项目已切换，请选择上下文历史":"设置已保存");if(projectChanged)uiHandler.postDelayed(()->showProjectHistoryPicker(true),180);
        }catch(Exception e){toast("保存失败："+e.getMessage());}});
        footer.addView(save,lp(dp(82),dp(38)));panel.addView(footer,lp(-1,dp(48)));

        dialog.setContentView(panel);dialog.show();
        Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=.38f;w.setAttributes(a);w.setGravity(Gravity.CENTER);int width=Math.min(dp(860),(int)(getResources().getDisplayMetrics().widthPixels*.96f));int height=Math.min(dp(720),(int)(getResources().getDisplayMetrics().heightPixels*.88f));w.setLayout(width,height);UiMotion.bindInteractive(w.getDecorView());UiMotion.dialogIn(w.getDecorView());}
    }

    private View buildThemePreview(Spinner themeSpinner){
        LinearLayout row=hbox();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,0,0,dp(6));
        themeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){refreshThemePreview(row,themeSpinner);}
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        refreshThemePreview(row,themeSpinner);
        return row;
    }

    private void refreshThemePreview(LinearLayout row,Spinner themeSpinner){
        row.removeAllViews();boolean neonSelected=themeSpinner.getSelectedItemPosition()==1;
        View classic=themePreviewCard("原版暖黑","暖橙 · 极简",false,!neonSelected,themeSpinner);
        View neon=themePreviewCard("霓虹紫蓝","紫蓝 · 青绿",true,neonSelected,themeSpinner);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(68),1);left.setMargins(0,0,dp(4),0);row.addView(classic,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(68),1);right.setMargins(dp(4),0,0,0);row.addView(neon,right);
        UiMotion.staggerChildren(row,2);UiMotion.bindInteractive(row);
    }

    private View themePreviewCard(String name,String detail,boolean neon,boolean selected,Spinner themeSpinner){
        int start=neon?Color.rgb(18,24,52):Color.rgb(31,29,27);
        int end=neon?Color.rgb(47,37,92):Color.rgb(20,19,17);
        int stroke=selected?(neon?Color.rgb(151,129,255):Color.rgb(217,119,87)):(neon?Color.rgb(72,70,139):Color.rgb(55,51,46));
        LinearLayout card=vbox();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(12),dp(7),dp(10),dp(7));card.setBackground(gradient(start,end,15,stroke,selected?2:1));
        TextView heading=text((selected?"●  ":"○  ")+name,11.2f,neon?Color.rgb(244,243,255):Color.rgb(240,236,229));heading.setTypeface(Typeface.DEFAULT_BOLD);card.addView(heading,lp(-1,dp(25)));
        TextView sub=text(detail,9.3f,neon?Color.rgb(142,149,179):Color.rgb(164,157,147));card.addView(sub,lp(-1,dp(22)));
        card.setOnClickListener(v->{themeSpinner.setSelection(neon?1:0);UiMotion.contentUpdated(card,true);});
        return card;
    }

    private Spinner darkSpinner(String[] values,String selected){ return darkSpinner(values,values,selected); }

    private Spinner darkSpinner(String[] labels,String[] values,String selected){
        Spinner sp=new Spinner(this);
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int pos,View convert,ViewGroup parent){TextView t=(TextView)super.getView(pos,convert,parent);t.setTextColor(TEXT);t.setTextSize(12);t.setPadding(dp(11),0,dp(11),0);t.setBackgroundColor(Color.TRANSPARENT);return t;}
            @Override public View getDropDownView(int pos,View convert,ViewGroup parent){TextView t=(TextView)super.getDropDownView(pos,convert,parent);t.setTextColor(TEXT);t.setTextSize(12);t.setPadding(dp(12),dp(10),dp(12),dp(10));t.setBackgroundColor(SURFACE_3);return t;}
        };
        sp.setAdapter(a); int idx=Math.max(0,Arrays.asList(values).indexOf(selected)); sp.setSelection(idx); sp.setTag(values); sp.setBackground(round(SURFACE_3,15,Color.TRANSPARENT,0)); return sp;
    }

    private void showChoicePicker(String title,String[] labels,int checked,java.util.function.IntConsumer choose){
        final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox();panel.setPadding(dp(12),dp(10),dp(12),dp(10));panel.setBackground(round(SURFACE_2,20,Color.TRANSPARENT,0));
        TextView h=text(title,14,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);h.setPadding(dp(8),0,dp(8),dp(8));panel.addView(h,lp(-1,dp(42)));
        for(int i=0;i<labels.length;i++){final int idx=i;TextView r=text((i==checked?"●  ":"    ")+labels[i],12,i==checked?TEXT:MUTED);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(10),0,dp(10),0);if(i==checked)r.setBackground(round(SURFACE_3,9,Color.TRANSPARENT,0));r.setOnClickListener(v->{choose.accept(idx);d.dismiss();});panel.addView(r,lp(-1,dp(42)));}
        d.setContentView(panel);d.show();Window w=d.getWindow();if(w!=null){if(overlayShell!=null&&android.os.Build.VERSION.SDK_INT>=26)w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=.55f;w.setAttributes(a);w.setLayout(Math.min(dp(360),(int)(getResources().getDisplayMetrics().widthPixels*.86f)),-2);UiMotion.bindInteractive(w.getDecorView());UiMotion.dialogIn(w.getDecorView());}
    }

    private TextView settingsTab(String label,Runnable action){
        TextView tab=text(label,11.5f,TEXT);tab.setGravity(Gravity.CENTER);tab.setPadding(dp(14),0,dp(14),0);tab.setSingleLine(true);tab.setBackground(round(SURFACE_3,14,Color.TRANSPARENT,0));tab.setOnClickListener(v->{action.run();UiMotion.contentUpdated(tab,true);});return tab;
    }
    private int tabLeft(LinearLayout tabs,int index){return index>=0&&index<tabs.getChildCount()?tabs.getChildAt(index).getLeft():0;}
    private View settingsSection(String label){
        LinearLayout row=hbox();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(14),0,dp(5));
        TextView title=text(label,11.5f,ACCENT);title.setTypeface(Typeface.DEFAULT_BOLD);row.addView(title,lp(-2,dp(24)));
        View line=new View(this);line.setBackgroundColor(BORDER);LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(0,dp(1),1);lineLp.setMargins(dp(10),0,0,0);row.addView(line,lineLp);
        return row;
    }
    private View labeled(String label,View field){LinearLayout box=vbox();TextView l=text(label,10,MUTED);l.setPadding(dp(2),dp(5),0,0);box.addView(l,lp(-1,dp(25)));box.addView(field,lp(-1,dp(44)));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,0,0,dp(5));box.setLayoutParams(bp);return box;}
    private EditText input(String value,boolean secret){EditText e=new EditText(this);e.setText(value==null?"":value);e.setTextColor(TEXT);e.setHintTextColor(MUTED_2);e.setSingleLine(true);e.setTextSize(12);e.setPadding(dp(11),0,dp(11),0);e.setBackground(round(SURFACE_3,15,Color.TRANSPARENT,0));if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
    private EditText paletteInput(int color){EditText e=input(paletteHex(color),false);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);GradientDrawable swatch=round(color,6,BORDER,1);swatch.setSize(dp(24),dp(24));e.setCompoundDrawablesWithIntrinsicBounds(swatch,null,null,null);e.setCompoundDrawablePadding(dp(10));return e;}

    private void saveConfigQuiet(){
        try{
            settingsStore.save(config);
            SessionRuntime rt=activeRuntime;
            if(rt!=null){
                if(rt.nextConfig==null)rt.nextConfig=config.copy();
                else{
                    String stagedPrompt=rt.nextConfig.customSystemPrompt;
                    rt.nextConfig=config.copy();
                    if(stagedPrompt!=null)rt.nextConfig.customSystemPrompt=stagedPrompt;
                }
                if(!rt.engine.isBusy())rt.engine.configure(rt.nextConfig.copy());
            }else if(engine!=null&&!engine.isBusy())engine.configure(config.copy());
            refreshChrome();
        }catch(Exception e){toast("保存失败："+e.getMessage());}
    }

    private void updateComposerChips() {
        boolean locked=isPermissionModeLocked();
        if (composerModeChip != null) {
            setAnimatedText(composerModeChip,permissionLabel(),false);
            composerModeChip.setEnabled(!locked);composerModeChip.setAlpha(locked?.58f:1f);
            composerModeChip.setContentDescription(locked?"权限模式本轮已锁定："+permissionLabelFor(activePermissionMode()):"选择权限模式");
        }
        if (composerEffortChip != null) {
            setAnimatedText(composerEffortChip,"推理：" + effortLabel(config.effort),false);
            composerEffortChip.setEnabled(true);composerEffortChip.setAlpha(1f);
            composerEffortChip.setContentDescription(isPermissionModeLocked()?"调整推理强度；从下一次模型请求生效":"调整推理强度");
        }
        if (composerModelChip != null) setAnimatedText(composerModelChip,shorten(currentProfileName()+" · "+config.model, wide ? 26 : 16),false);
    }

    private static int parseTokenCount(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.US).replace(",", "").replace("_", "");
        if (s.isEmpty()) throw new IllegalArgumentException("Empty token count");
        double multiplier = 1d;
        if (s.endsWith("k")) { multiplier = 1_000d; s = s.substring(0,s.length()-1); }
        else if (s.endsWith("m")) { multiplier = 1_000_000d; s = s.substring(0,s.length()-1); }
        double value = Double.parseDouble(s) * multiplier;
        if (!Double.isFinite(value) || value < 16_000d || value > 2_000_000d) throw new IllegalArgumentException("Context must be 16k–2m");
        return (int)Math.round(value);
    }

    private static String formatTokenCount(long value) {
        if (value >= 1_000_000L) { double m=value/1_000_000d; return Math.abs(m-Math.rint(m))<0.0001 ? String.format(Locale.US,"%.0fm",m) : String.format(Locale.US,"%.1fm",m); }
        if (value >= 1_000L) { double k=value/1_000d; return Math.abs(k-Math.rint(k))<0.0001 ? String.format(Locale.US,"%.0fk",k) : String.format(Locale.US,"%.1fk",k); }
        return Long.toString(value);
    }

    private boolean isPermissionModeLocked(){
        SessionRuntime rt=activeRuntime;
        return rt!=null&&(rt.busy||rt.engine.isBusy());
    }

    private String userPermissionMode(){
        SessionRuntime rt=activeRuntime;
        return rt==null?config.permissionMode:rt.engine.getUserPermissionMode();
    }

    private String activePermissionMode(){
        SessionRuntime rt=activeRuntime;
        return rt==null?config.permissionMode:rt.engine.getEffectivePermissionMode();
    }

    private static String permissionLabelFor(String mode){
        if("acceptEdits".equals(mode))return "自动编辑"; if("plan".equals(mode))return "规划"; if("auto".equals(mode))return "自动"; if("dontAsk".equals(mode))return "不询问"; if("bypassPermissions".equals(mode))return "跳过权限"; return "每次询问";
    }

    private String permissionLabel(){
        String effective=activePermissionMode(),base=userPermissionMode();
        String label=permissionLabelFor(effective);
        if("plan".equals(effective)&&!"plan".equals(base))label+=" · 基础："+permissionLabelFor(base);
        return label+(isPermissionModeLocked()?" · 锁定":"");
    }

    private void refreshChrome() {
        if (statusText != null) setAnimatedText(statusText,runtime.isInstalled()?"已就绪":"运行环境未安装",false);
        if (tokenText != null) setAnimatedText(tokenText,(runtime.isInstalled()?"● ":"○ ")+shorten(config.model,22),false);
        updateContextChip(engine == null ? 0 : engine.estimateContextTokens(), false);
    }

    private void updateContextChip(long currentTokens, boolean animate) {
        if (contextChip == null) return;
        int window = Math.max(16_000, config.contextWindowTokens);
        int pct = Math.max(0, Math.min(999, (int)Math.round(currentTokens * 100.0 / window)));
        setAnimatedText(contextChip,formatTokenCount(currentTokens) + " / " + formatTokenCount(window),animate);
        contextChip.setTextColor(pct >= 90 ? RED : (pct >= 72 ? (neonTheme ? Color.WHITE : ACCENT) : (neonTheme ? Color.WHITE : MUTED)));
        contextChip.setBackground(pct >= 90
            ? round(neonTheme ? Color.rgb(54,20,40) : Color.rgb(52,31,29),18,Color.TRANSPARENT,0)
            : (neonTheme ? accentGradient(18) : round(SURFACE_2,18,Color.TRANSPARENT,0)));
    }

    private boolean isActiveRuntime(SessionRuntime rt) { return rt != null && rt == activeRuntime; }

    private void touchRuntime(SessionRuntime rt, String phase, String detail, boolean busy) {
        if (rt == null) return;
        String nextPhase = phase == null ? rt.phase : phase;
        String nextDetail = detail == null ? "" : detail;
        boolean visibleStateChanged = !nextPhase.equals(rt.phase) || !nextDetail.equals(rt.detail) || busy != rt.busy;
        rt.phase = nextPhase;
        rt.detail = nextDetail;
        rt.busy = busy;
        rt.lastEventAt = System.currentTimeMillis();
        File f = rt.engine.getSessionFile(); if (f != null) { rt.file = f; registerRuntime(rt); }
        // Streaming text can arrive dozens of times per second. Refreshing the sidebar used to
        // reread and parse every JSONL session on every delta, blocking the Android main thread.
        if (visibleStateChanged) ui(this::refreshVisibleSessionRows);
    }

    private void handleSessionStarted(SessionRuntime rt, SessionConfig c){
        rt.boundProfileId=c.profileId;
        if(rt.pendingProfileId.isEmpty()){
            String selectedEffort=rt.nextConfig==null?null:rt.nextConfig.effort;
            String selectedPrompt=rt.nextConfig==null?null:rt.nextConfig.customSystemPrompt;
            rt.nextConfig=c.copy();
            if(selectedEffort!=null&&!selectedEffort.trim().isEmpty())rt.nextConfig.effort=selectedEffort;
            if(selectedPrompt!=null)rt.nextConfig.customSystemPrompt=selectedPrompt;
        }
        File sessionFile=rt.engine.getSessionFile();
        if(sessionFile!=null){rt.file=sessionFile;registerRuntime(rt);if(isActiveRuntime(rt))settingsStore.setLastSessionFile(sessionFile);}
        rt.liveAssistant.setLength(0);
        touchRuntime(rt,"thinking","正在思考…",true);
        if(!isActiveRuntime(rt))return;
        postRuntimeUi(rt,()->{if(streamingItem==null||streamingItem.body.length()>0){streamingItem=null;streamingView=null;streamingBodyHost=null;streamingVisibleChars=0;}showWorkingIndicator("正在思考…");ensureStreamingAssistantOnUi(rt);if(streamingItem!=null)recordAssistantProcessStep(streamingItem,"开始分析请求");setComposerBusy(true);updateComposerChips();if(tokenText!=null)setAnimatedText(tokenText,"● 工作中 · "+shorten(c.model,18),true);});
    }

    private void handleTextDelta(SessionRuntime rt,String delta){
        if(delta==null||delta.isEmpty())return;
        rt.liveAssistant.append(delta);
        touchRuntime(rt,"thinking",rt.detail,true);
        if(!isActiveRuntime(rt))return;
        queueStreamingDelta(rt,delta);
    }

    /** Coalesce provider deltas at a short frame-friendly cadence so text follows the API without UI bursts. */
    private void queueStreamingDelta(SessionRuntime rt,String delta){
        synchronized(streamingDeltaLock){
            if(pendingStreamingRuntime!=rt||pendingStreamingGeneration!=activeRuntimeGeneration){
                pendingStreamingDelta.setLength(0);
                pendingStreamingRuntime=rt;
                pendingStreamingGeneration=activeRuntimeGeneration;
            }
            pendingStreamingDelta.append(delta);
            if(streamingDeltaFlushPosted)return;
            streamingDeltaFlushPosted=true;
        }
        uiHandler.postDelayed(streamingDeltaFlushRunnable,32L);
    }

    private void flushStreamingDeltas(){
        SessionRuntime rt;
        String chunk;
        long generation;
        synchronized(streamingDeltaLock){
            streamingDeltaFlushPosted=false;
            rt=pendingStreamingRuntime;
            generation=pendingStreamingGeneration;
            chunk=pendingStreamingDelta.toString();
            pendingStreamingDelta.setLength(0);
        }
        if(rt==null||chunk.isEmpty()||!isActiveRuntime(rt)||generation!=activeRuntimeGeneration)return;
        appendStreamingChunkOnUi(chunk);
    }

    /** Must run on the UI thread. Used before tool/completion events so delayed deltas never reorder. */
    private void flushStreamingDeltasNow(SessionRuntime rt){
        String chunk="";
        synchronized(streamingDeltaLock){
            if(pendingStreamingRuntime==rt&&pendingStreamingGeneration==activeRuntimeGeneration&&pendingStreamingDelta.length()>0){
                chunk=pendingStreamingDelta.toString();
                pendingStreamingDelta.setLength(0);
            }
            streamingDeltaFlushPosted=false;
        }
        uiHandler.removeCallbacks(streamingDeltaFlushRunnable);
        if(!chunk.isEmpty()&&isActiveRuntime(rt))appendStreamingChunkOnUi(chunk);
    }

    private void discardStreamingDeltas(SessionRuntime rt){
        synchronized(streamingDeltaLock){
            if(pendingStreamingRuntime==rt){
                pendingStreamingDelta.setLength(0);
                pendingStreamingRuntime=null;
                pendingStreamingGeneration=0L;
                streamingDeltaFlushPosted=false;
            }
        }
        uiHandler.removeCallbacks(streamingDeltaFlushRunnable);
    }

    private void ensureStreamingAssistantOnUi(SessionRuntime rt){
        if(streamingItem!=null)return;
        if(agentProgressView==null){agentProgressView=new AgentProgressView(this);agentProgressView.setPalette(TEXT,MUTED,ACCENT,GREEN,SURFACE_3);}
        streamingItem=new ChatItem(ChatItem.ASSISTANT,"IQ","");streamingVisibleChars=0;transcript.add(streamingItem);
        if(chatMessages!=null)addChatView(streamingItem);
    }

    private void appendStreamingChunkOnUi(String chunk){
        showWorkingIndicator("正在回复…");
        ensureStreamingAssistantOnUi(activeRuntime);
        streamingItem.body.append(chunk);
        scheduleStreamingRender();
    }

    private void handleToolBatchStarted(SessionRuntime rt,IQCodeEngine.ToolBatch batch){
        if(batch==null||!isActiveRuntime(rt))return;
        postRuntimeUi(rt,()->registerCollapsedToolBatch(batch.batchId,toolActivityEntries(batch)));
    }

    private void handleToolBatchCompleted(SessionRuntime rt,IQCodeEngine.ToolBatch batch){
        if(batch==null||!isActiveRuntime(rt))return;
        postRuntimeUi(rt,()->{
            List<CollapsedToolActivity> activities=collapsedToolsByBatch.get(batch.batchId);
            if(activities==null)return;
            for(CollapsedToolActivity activity:activities){
                for(ChatItem item:activity.members)if(!item.completed){
                    item.completed=true;item.resultError=true;item.runningView=null;
                    item.result.append("工具在返回结果前结束。");
                }
                activity.batchCompleted=true;refreshCollapsedToolActivity(activity);
            }
        });
    }

    private List<ToolActivityGrouper.Entry> toolActivityEntries(IQCodeEngine.ToolBatch batch){
        ArrayList<ToolActivityGrouper.Entry> entries=new ArrayList<>();
        for(ToolCall call:batch.toolCalls){
            if(call!=null)entries.add(toolActivityEntry(call.id,call.name,call.input,batch.breakBeforeToolIds.contains(call.id)));
        }
        return entries;
    }

    private ToolActivityGrouper.Entry toolActivityEntry(String toolId,String name,JSONObject input,boolean boundaryBefore){
        String toolName=name==null?"":name;
        int readRequests="Read".equals(toolName)?1:("ReadMany".equals(toolName)?readManyCount(input):0);
        return new ToolActivityGrouper.Entry(toolId,toolName,readRequests,toolActivityHint(toolName,input),boundaryBefore);
    }

    private static int readManyCount(JSONObject input){
        if(input==null)return 0;
        JSONArray paths=input.optJSONArray("paths");
        return paths==null?0:paths.length();
    }

    private String toolActivityHint(String name,JSONObject input){
        if(input==null)return "";
        String value="";
        if("Read".equals(name))value=input.optString("file_path","");
        else if("ReadMany".equals(name)){
            JSONArray paths=input.optJSONArray("paths");
            if(paths!=null&&paths.length()>0)value=paths.optString(0,"")+(paths.length()>1?" · +"+(paths.length()-1):"");
        } else if("Grep".equals(name)||"Glob".equals(name)){
            value=input.optString("pattern","");
            String path=input.optString("path","");
            if(!path.isEmpty())value=value.isEmpty()?path:value+" · "+path;
        } else if("LS".equals(name)||"Tree".equals(name)||"Stat".equals(name))value=input.optString("path",input.optString("file_path",""));
        return shortenMiddle(value,72);
    }

    private void registerCollapsedToolBatch(long batchId,List<ToolActivityGrouper.Entry> entries){
        List<ToolActivityGrouper.GroupPlan> plans=ToolActivityGrouper.group(entries);
        if(plans.isEmpty())return;
        ArrayList<CollapsedToolActivity> activities=new ArrayList<>();
        for(ToolActivityGrouper.GroupPlan plan:plans){
            CollapsedToolActivity activity=new CollapsedToolActivity(batchId,plan);
            activities.add(activity);
            for(String toolId:plan.toolIds)collapsedToolsById.put(toolId,activity);
        }
        collapsedToolsByBatch.put(batchId,activities);
    }

    private ChatItem newToolItem(ToolCall call){
        ChatItem tool=new ChatItem(ChatItem.TOOL,call==null?"工具":call.name,call==null||call.input==null?"{}":call.input.toString());
        tool.toolId=call==null||call.id==null?"":call.id;
        if(!tool.toolId.isEmpty())liveToolItems.put(tool.toolId,tool);
        return tool;
    }

    private CollapsedToolActivity collapsedToolActivity(ChatItem item){
        return item==null||item.toolId==null||item.toolId.isEmpty()?null:collapsedToolsById.get(item.toolId);
    }

    private void attachToolToCollapsedActivity(ChatItem item){
        CollapsedToolActivity activity=collapsedToolActivity(item);
        if(activity!=null&&!activity.members.contains(item))activity.members.add(item);
    }

    private void clearCollapsedToolActivities(){
        clearCollapsedToolActivityViews();
        collapsedToolsById.clear();
        collapsedToolsByBatch.clear();
        nextRestoredToolBatchId=-1L;
    }

    private void clearCollapsedToolActivityViews(){
        for(List<CollapsedToolActivity> activities:collapsedToolsByBatch.values())for(CollapsedToolActivity activity:activities){
            activity.renderedView=null;activity.childrenHost=null;
            for(ChatItem item:activity.members){item.renderedView=null;item.runningView=null;}
        }
    }

    private void handleToolUse(SessionRuntime rt,ToolCall call){
        // The assistant turn has been persisted before tool execution begins, so a
        // background switch can reload it from JSONL without duplicating live text.
        rt.liveAssistant.setLength(0);
        String name=call==null?"工具":call.name;
        touchRuntime(rt,"tool","正在执行 "+name+"…",true);
        if(!isActiveRuntime(rt))return;
        if(isWorkflowTool(name)){postRuntimeUi(rt,()->{flushStreamingDeltasNow(rt);finalizeStreamingMessage();showWorkingIndicator("正在执行 "+name+"…");ensureStreamingAssistantOnUi(rt);if(streamingItem!=null)recordAssistantProcessStep(streamingItem,"调用工具："+name);});return;}
        final ChatItem tool=newToolItem(call);
        postRuntimeUi(rt,()->{
            flushStreamingDeltasNow(rt);
            finalizeStreamingMessage();
            hideWorkingIndicator();
            boolean follow=chatAutoFollow;
            if(!transcript.contains(tool)) transcript.add(tool);
            attachToolToCollapsedActivity(tool);
            if(wide||currentView==VIEW_CHAT){
                CollapsedToolActivity activity=collapsedToolActivity(tool);
                if(activity!=null){
                    if(activity.renderedView==null)addCollapsedToolActivity(activity,conversationHost());
                    else refreshCollapsedToolActivity(activity);
                }else if(chatMessages!=null)addChatView(tool);
                if(follow)scrollChatSoft();
            }
            showWorkingIndicator("正在执行 "+name+"…");
            ensureStreamingAssistantOnUi(rt);if(streamingItem!=null)recordAssistantProcessStep(streamingItem,"调用工具："+name);
        });
    }

    private void handleToolProgress(SessionRuntime rt, ToolCall call, String chunk, boolean stderr, long elapsedMs) {
        if (call == null) return;
        ChatItem tool = call.id == null ? null : liveToolItems.get(call.id);
        if (tool == null) return;
        synchronized (tool) {
            tool.elapsedMs = Math.max(tool.elapsedMs, elapsedMs);
            if (chunk != null && !chunk.isEmpty()) appendLiveChunk(tool, chunk, stderr);
            if (tool.progressRenderPosted) return;
            tool.progressRenderPosted = true;
        }
        final long generation=activeRuntimeGeneration;
        uiHandler.postDelayed(() -> {
            synchronized (tool) { tool.progressRenderPosted = false; }
            if (tool.completed || !isActiveRuntime(rt) || generation!=activeRuntimeGeneration) return;
            String live=liveOutputPreview(tool,7000,wide?10:7);
            if(tool.liveOutputView!=null&&tool.liveOutputView.getParent()!=null)tool.liveOutputView.setText(live);
            else refreshToolItem(tool);
        }, 300);
    }

    private void handleToolResult(SessionRuntime rt,ToolCall call,ToolExecutionResult result){
        touchRuntime(rt,"thinking","正在继续处理…",true);
        if(!isActiveRuntime(rt)){ if(call!=null&&call.id!=null) liveToolItems.remove(call.id); return; }
        if(call!=null&&isWorkflowTool(call.name)){if(call.id!=null)liveToolItems.remove(call.id);postRuntimeUi(rt,()->{ChatItem stale=findPendingTool(call);if(stale!=null){stale.elapsedMs=Math.max(stale.elapsedMs,currentToolElapsed(stale));stale.completed=true;stale.resultError=false;stale.runningView=null;refreshToolItem(stale);}showWorkingIndicator("正在继续处理…");});return;}
        postRuntimeUi(rt,()->{
            boolean follow=chatAutoFollow;
            ChatItem tool=call!=null&&call.id!=null&&!call.id.isEmpty()?liveToolItems.get(call.id):findPendingTool(call);
            if(tool==null&&call!=null&&call.id!=null&&!call.id.isEmpty())tool=findPendingTool(call);
            if(call!=null&&call.id!=null) liveToolItems.remove(call.id);
            if(tool==null&&call!=null&&call.id!=null&&!call.id.isEmpty()&&collapsedToolsById.containsKey(call.id)){
                tool=newToolItem(call);
                liveToolItems.remove(call.id);
                transcript.add(tool);
                attachToolToCollapsedActivity(tool);
            }
            if(tool==null){ChatItem legacy=new ChatItem(ChatItem.RESULT,call==null?"工具":call.name,result.content);transcript.add(legacy);if(chatMessages!=null)addChatView(legacy);}
            else{
                tool.result.setLength(0); tool.result.append(result.content==null?"":result.content);
                tool.diff.setLength(0); tool.diff.append(result.diff==null?"":result.diff);
                tool.diffAddedLines=result.addedLines; tool.diffDeletedLines=result.deletedLines;
                tool.elapsedMs=Math.max(tool.elapsedMs,currentToolElapsed(tool));
                tool.completed=true; tool.runningView=null; tool.resultError=result.isError; tool.exitCode=result.exitCode;
                if (result.isError && "Bash".equalsIgnoreCase(tool.title) && isPackageManagerTool(tool)) tool.expanded = true;
                if(!transcript.contains(tool)){transcript.add(tool);attachToolToCollapsedActivity(tool);if(chatMessages!=null)addChatView(tool);}
                refreshToolItem(tool);
            }
            showWorkingIndicator("正在继续处理…");
            ensureStreamingAssistantOnUi(rt);if(streamingItem!=null)recordAssistantProcessStep(streamingItem,"工具完成："+(call==null?"工具":call.name));
            if(follow)scrollChatSoft();
            if(call!=null&&( "Edit".equalsIgnoreCase(call.name)||"Write".equalsIgnoreCase(call.name)||"MultiEdit".equalsIgnoreCase(call.name)||"Delete".equalsIgnoreCase(call.name)||"Move".equalsIgnoreCase(call.name))) refreshChanges();
        });
    }

    private static boolean isWorkflowTool(String name){return "TaskCreate".equals(name)||"TaskGet".equals(name)||"TaskList".equals(name)||"TaskUpdate".equals(name)||"TodoWrite".equals(name)||"EnterPlanMode".equals(name)||"ExitPlanMode".equals(name);}

    private ChatItem findPendingTool(ToolCall call){
        if (call != null && call.id != null && !call.id.isEmpty()) {
            for(int i=transcript.size()-1;i>=0;i--){
                ChatItem x=transcript.get(i);
                if(x.type==ChatItem.TOOL&&!x.completed&&call.id.equals(x.toolId)) return x;
            }
            return null;
        }
        String name = call == null ? null : call.name;
        for(int i=transcript.size()-1;i>=0;i--){
            ChatItem x=transcript.get(i);
            if(x.type==ChatItem.TOOL&&!x.completed&&(name==null||name.equalsIgnoreCase(x.title))) return x;
        }
        return null;
    }

    private void handleQueuedPromptApplied(SessionRuntime rt){if(!isActiveRuntime(rt))return;postRuntimeUi(rt,()->{flushStreamingDeltasNow(rt);finalizeStreamingMessage();showWorkingIndicator("预输入已加载，正在继续…");});}

    private void handleResponseRetry(SessionRuntime rt){
        rt.liveAssistant.setLength(0);
        if(!isActiveRuntime(rt))return;
        postRuntimeUi(rt,()->{
            discardStreamingDeltas(rt);
            if(streamingItem!=null)transcript.remove(streamingItem);
            streamingItem=null;streamingView=null;streamingBodyHost=null;streamingVisibleChars=0;
            if(chatMessages!=null)rebuildTranscriptViews();
            showWorkingIndicator("连接中断，正在重试模型请求…");
            setComposerBusy(true);
        });
    }

    private void handleResponseInterruptedBySteering(SessionRuntime rt) {
        rt.liveAssistant.setLength(0);
        touchRuntime(rt,"thinking","旧响应已打断，正在按最新纠正重新规划…",true);
        if(!isActiveRuntime(rt))return;
        postRuntimeUi(rt,() -> {
            discardStreamingDeltas(rt);
            finalizeStreamingMessage();
            streamingItem=null; streamingView=null; streamingBodyHost=null; streamingVisibleChars=0;
            showWorkingIndicator("旧响应已打断，正在按最新纠正重新规划…");
            setComposerBusy(true);
        });
    }

    private void handleProjectDirectoryChanged(SessionRuntime rt,String projectDirectory) {
        if(!isActiveRuntime(rt))return;
        postRuntimeUi(rt,() -> {
            if(projectDirectory==null||projectDirectory.trim().isEmpty())return;
            config.projectDirectory=projectDirectory; try{settingsStore.save(config);}catch(Exception ignored){} browserDir=new File(projectDirectory);
            refreshChrome(); if(changesText!=null)refreshChanges(); if(filesWorkspaceHost!=null&&currentView==VIEW_FILES)showFileBrowser();
            if(terminalPane!=null) terminalPane.setNextSessionWorkingDirectory(projectDirectory);
        });
    }

    private void handlePlanStateChanged(SessionRuntime rt,PlanWorkflowState state){
        rt.planState=state==null?PlanWorkflowState.idle():state.copy();
        if(!isActiveRuntime(rt))return;
        postRuntimeUi(rt,()->{updateComposerChips();if(agentProgressView!=null){agentProgressView.update(runtimeDisplayStatus(rt),rt.taskSnapshot,rt.planState,rt.busy);ensureStreamingAssistantOnUi(rt);if(chatAutoFollow)scrollChatSoft();}});
    }

    private void handleTasksChanged(SessionRuntime rt,TaskStore.Snapshot snapshot){
        if(snapshot==null)return;TaskStore.Snapshot previous=rt.taskSnapshot;if(previous!=null&&snapshot.version<previous.version)return;rt.taskSnapshot=snapshot;
        if(!isActiveRuntime(rt))return;postRuntimeUi(rt,()->{if(agentProgressView!=null)agentProgressView.update(runtimeDisplayStatus(rt),snapshot,rt.planState,rt.busy);ensureStreamingAssistantOnUi(rt);if(agentProgressView!=null&&chatAutoFollow)scrollChatSoft();});
    }

    private void handlePlanApprovalRequest(SessionRuntime rt,PlanApprovalGate.ApprovalRequest request){
        ui(()->showPlanApprovalDialog(rt,request));
    }

    private void showPlanApprovalDialog(SessionRuntime rt,PlanApprovalGate.ApprovalRequest request){
        if(request==null)return;final boolean[] resolved={false};final Dialog d=newOverlayAwareDialog();d.requestWindowFeature(Window.FEATURE_NO_TITLE);d.setCanceledOnTouchOutside(false);
        LinearLayout panel=vbox();panel.setPadding(dp(16),dp(12),dp(16),dp(12));panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        TextView title=text("实现计划 · revision "+request.plan.revision,16,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(title,lp(-1,dp(40)));
        TextView path=text(request.plan.planFile,9.5f,MUTED_2);path.setTypeface(Typeface.MONOSPACE);path.setPadding(0,0,0,dp(6));panel.addView(path,lp(-1,-2));
        ScrollView planScroll=new ScrollView(this);LinearLayout rendered=MarkdownRenderer.render(this,request.plan.planText,12.5f,neonTheme,lightTheme);planScroll.addView(rendered,new ScrollView.LayoutParams(-1,-2));panel.addView(planScroll,new LinearLayout.LayoutParams(-1,0,1));
        EditText feedback=input("",false);feedback.setHint("继续规划时填写反馈（可选）");LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(44));fp.setMargins(0,dp(8),0,0);panel.addView(feedback,fp);
        TextView policy=text("批准只确认计划；后续操作仍按你的权限模式："+permissionLabelFor(userPermissionMode()),10.2f,ACCENT);policy.setPadding(dp(3),dp(8),dp(3),dp(2));panel.addView(policy,lp(-1,-2));
        LinearLayout footer=hbox();footer.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView revise=smallPill("继续规划",MUTED);revise.setGravity(Gravity.CENTER);revise.setOnClickListener(v->{resolved[0]=true;d.dismiss();rt.engine.respondPlanApproval(request.requestId,PlanApprovalGate.Decision.KEEP_PLANNING,feedback.getText().toString().trim());});footer.addView(revise,lp(dp(92),dp(40)));
        TextView approve=pill("批准并开始",TEXT);approve.setGravity(Gravity.CENTER);approve.setOnClickListener(v->{resolved[0]=true;d.dismiss();rt.engine.respondPlanApproval(request.requestId,PlanApprovalGate.Decision.APPROVE,"");});footer.addView(approve,lp(dp(112),dp(40)));panel.addView(footer,lp(-1,dp(50)));
        d.setOnCancelListener(x->{if(!resolved[0]){resolved[0]=true;rt.engine.respondPlanApproval(request.requestId,PlanApprovalGate.Decision.KEEP_PLANNING,feedback.getText().toString().trim());}});d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(560),(int)(getResources().getDisplayMetrics().widthPixels*.94f)),Math.min(dp(720),(int)(getResources().getDisplayMetrics().heightPixels*.88f)));
    }

    private void handleQuestionRequest(SessionRuntime rt,QuestionGate.QuestionRequest request) {
        ui(() -> showQuestionStep(rt.engine, request, 0, new JSONObject()));
    }

    private void showQuestionStep(IQCodeEngine targetEngine, QuestionGate.QuestionRequest request, int index, JSONObject answers) {
        if (request == null) return;
        if (index >= request.questions.length()) { targetEngine.respondQuestion(request.requestId, answers); return; }
        JSONObject q=request.questions.optJSONObject(index); if(q==null){showQuestionStep(targetEngine,request,index+1,answers);return;}
        final Dialog d=newOverlayAwareDialog(); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vbox(); panel.setPadding(dp(16),dp(14),dp(16),dp(12)); panel.setBackground(round(SURFACE_2,22,Color.TRANSPARENT,0));
        String header=q.optString("header","问题"); TextView h=text(header,11,ACCENT); h.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(h,lp(-1,dp(30)));
        String question=q.optString("question","IQ 应该怎么做？"); TextView qt=text(question,14,TEXT); qt.setLineSpacing(0,1.12f); panel.addView(qt,lp(-1,-2));
        JSONArray options=q.optJSONArray("options"); boolean multi=q.optBoolean("multiSelect",false); java.util.LinkedHashSet<String> selected=new java.util.LinkedHashSet<>();
        LinearLayout optionHost=vbox(); LinearLayout.LayoutParams oh=new LinearLayout.LayoutParams(-1,-2);oh.setMargins(0,dp(10),0,0);panel.addView(optionHost,oh);
        if(options!=null)for(int i=0;i<options.length();i++){
            JSONObject o=options.optJSONObject(i); if(o==null)continue; String label=o.optString("label",""); String desc=o.optString("description","");
            TextView row=text((multi?"○  ":"")+label+(desc.isEmpty()?"":"\n    "+desc),11.2f,TEXT);row.setPadding(dp(10),dp(8),dp(10),dp(8));row.setBackground(round(SURFACE_3,14,Color.TRANSPARENT,0));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,0,0,dp(6));optionHost.addView(row,rp);
            row.setOnClickListener(v->{
                if(multi){if(selected.contains(label)){selected.remove(label);setAnimatedText(row,"○  "+label+(desc.isEmpty()?"":"\n    "+desc),true);}else{selected.add(label);setAnimatedText(row,"●  "+label+(desc.isEmpty()?"":"\n    "+desc),true);}}
                else {try{answers.put(question,label);}catch(Exception ignored){}d.dismiss();showQuestionStep(targetEngine,request,index+1,answers);}
            });
        }
        EditText other=input("",false); other.setHint("其他回答…"); LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(44));op.setMargins(0,dp(4),0,0);panel.addView(other,op);
        LinearLayout footer=hbox();footer.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);footer.setPadding(0,dp(10),0,0);
        TextView cancel=smallPill("取消",MUTED);cancel.setGravity(Gravity.CENTER);cancel.setOnClickListener(v->{d.dismiss();targetEngine.respondQuestion(request.requestId,new JSONObject());});footer.addView(cancel,lp(dp(64),dp(38)));
        TextView next=pill(index+1>=request.questions.length()?"提交":"下一步",TEXT);next.setGravity(Gravity.CENTER);next.setOnClickListener(v->{
            try{String custom=other.getText().toString().trim();if(!custom.isEmpty())answers.put(question,custom);else if(multi)answers.put(question,new JSONArray(selected));else if(options==null||options.length()==0){toast("请输入回答");return;}else{toast("请选择一个选项");return;}}catch(Exception ignored){}
            d.dismiss();showQuestionStep(targetEngine,request,index+1,answers);
        });footer.addView(next,lp(dp(76),dp(38)));panel.addView(footer,lp(-1,dp(50)));
        d.setOnCancelListener(x->targetEngine.respondQuestion(request.requestId,new JSONObject()));d.setContentView(panel);d.show();styleDialogWindow(d,Math.min(dp(500),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
    }

    private void handlePermissionRequest(SessionRuntime rt,PermissionGate.PermissionRequest r){
        ui(() -> {
            ChatItem pending = findPendingTool(r.call);
            if (pending != null) {
                pending.awaitingPermission = true;
                refreshToolItem(pending);
            }
            showPermissionDialog(rt, r, pending);
        });
    }

    /**
     * Custom IQ Code permission surface.
     *
     * Do not use AlertDialog here: the stock Material dialog is visually disconnected
     * from the rest of the workspace and dumps raw JSON at the user.  This panel keeps
     * the command readable, exposes the important metadata, and keeps the tool row in
     * an explicit "等待授权" state until the user decides.
     */
    private void showPermissionDialog(SessionRuntime rt, PermissionGate.PermissionRequest r, ChatItem pending) {
        final Dialog d = newOverlayAwareDialog();
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean[] resolved = { false };

        final JSONObject input = r.call == null || r.call.input == null ? new JSONObject() : r.call.input;
        final String tool = r.call == null || r.call.name == null ? "操作" : r.call.name;
        final boolean rootCommand="Root".equalsIgnoreCase(tool);
        final boolean bash = "Bash".equalsIgnoreCase(tool)||rootCommand;
        final String command = bash ? input.optString("command", "").trim() : "";
        final String cwd = input.optString("cwd", input.optString("working_directory", config.projectDirectory == null ? "" : config.projectDirectory));
        final long timeout = input.optLong("timeout_ms", 10000L);

        LinearLayout panel = vbox();
        panel.setPadding(dp(18), dp(17), dp(18), dp(15));
        panel.setBackground(round(SURFACE_2, 24, BORDER, 1));

        // Header
        LinearLayout head = hbox();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView glyph = text(permissionGlyph(tool), 15, rootCommand?RED:ACCENT);
        glyph.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(round(SURFACE_3, 16, rootCommand?RED:ACCENT, 1));
        head.addView(glyph, lp(dp(50), dp(50)));

        LinearLayout titles = vbox();
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.setMargins(dp(12), 0, 0, 0);
        TextView title = text("允许执行 " + userFacingToolName(tool, input) + "？", 17.5f, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title, lp(-1, dp(28)));
        TextView subtitle = text(permissionSubtitle(tool), 10.5f, MUTED);
        subtitle.setLineSpacing(0, 1.08f);
        titles.addView(subtitle, lp(-1, -2));
        head.addView(titles, tp);
        panel.addView(head, lp(-1, -2));

        LinearLayout details = vbox();
        ScrollView detailsScroll = new ScrollView(this);
        detailsScroll.setFillViewport(false);
        detailsScroll.setVerticalScrollBarEnabled(true);
        detailsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        detailsScroll.addView(details, new ScrollView.LayoutParams(-1, -2));
        panel.addView(detailsScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (!isActiveRuntime(rt)) {
            TextView background = text("后台会话正在请求权限", 9.5f, ACCENT);
            background.setPadding(dp(8), 0, dp(8), 0);
            background.setGravity(Gravity.CENTER_VERTICAL);
            background.setBackground(round(SURFACE_3, 12, Color.TRANSPARENT, 0));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(26));
            bp.setMargins(dp(62), dp(7), 0, 0);
            details.addView(background, bp);
        }

        // Main operation content. Bash gets a real code block instead of raw JSON.
        if (bash) {
            LinearLayout codeBox = vbox();
            codeBox.setPadding(dp(13), dp(10), dp(12), dp(10));
            codeBox.setBackground(round(TERMINAL_BG, 15, BORDER, 1));

            LinearLayout codeHead = hbox();
            codeHead.setGravity(Gravity.CENTER_VERTICAL);
            TextView lang = text(rootCommand?"ROOT · UID 0":"BASH", 8.5f, rootCommand?RED:MUTED_2);
            lang.setTypeface(Typeface.DEFAULT_BOLD);
            codeHead.addView(lang, new LinearLayout.LayoutParams(0, dp(24), 1));
            TextView copy = text("复制命令", 9.2f, MUTED);
            copy.setGravity(Gravity.CENTER);
            copy.setOnClickListener(v -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(rootCommand?"IQ Code Root":"IQ Code Bash", command));
                    toast("命令已复制");
                } catch (Exception ignored) {}
            });
            codeHead.addView(copy, lp(dp(66), dp(24)));
            codeBox.addView(codeHead, lp(-1, dp(24)));

            TextView code = text(command.isEmpty() ? "(empty command)" : command, 12.3f, TEXT);
            code.setTypeface(Typeface.MONOSPACE);
            code.setTextIsSelectable(true);
            code.setLineSpacing(dp(1), 1.08f);
            code.setMaxLines(9);
            codeBox.addView(code, lp(-1, -2));

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(0, dp(14), 0, 0);
            details.addView(codeBox, cp);

            // Metadata row: no raw JSON.
            LinearLayout meta = hbox();
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
            mp.setMargins(0, dp(12), 0, 0);

            LinearLayout cwdCol = vbox();
            TextView cwdLabel = text("工作目录", 9.4f, MUTED_2);
            cwdCol.addView(cwdLabel, lp(-1, dp(20)));
            TextView cwdValue = text(cwd == null || cwd.trim().isEmpty() ? "当前项目目录" : shortenMiddle(cwd, 38), 10.3f, TEXT);
            cwdValue.setTypeface(Typeface.MONOSPACE);
            cwdCol.addView(cwdValue, lp(-1, dp(24)));
            meta.addView(cwdCol, new LinearLayout.LayoutParams(0, dp(46), 1));

            View sep = dividerVertical();
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(1, dp(38));
            sp.setMargins(dp(10), dp(4), dp(12), 0);
            meta.addView(sep, sp);

            LinearLayout timeCol = vbox();
            TextView timeLabel = text("超时时间", 9.4f, MUTED_2);
            timeCol.addView(timeLabel, lp(-1, dp(20)));
            TextView timeValue = text(formatTimeout(timeout), 10.3f, TEXT);
            timeValue.setTypeface(Typeface.MONOSPACE);
            timeCol.addView(timeValue, lp(-1, dp(24)));
            meta.addView(timeCol, lp(dp(112), dp(46)));
            details.addView(meta, mp);
        } else {
            TextView inputText = text(prettyPermissionInput(tool, input), 11.2f, TEXT);
            inputText.setTypeface(Typeface.MONOSPACE);
            inputText.setTextIsSelectable(true);
            inputText.setLineSpacing(dp(1), 1.08f);
            inputText.setPadding(dp(12), dp(10), dp(12), dp(10));
            inputText.setBackground(round(TERMINAL_BG, 15, BORDER, 1));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(0, dp(14), 0, 0);
            details.addView(inputText, ip);
        }

        TextView mode = text("权限模式 · " + permissionModeLabel(r.mode), 9.5f, MUTED_2);
        LinearLayout.LayoutParams modep = new LinearLayout.LayoutParams(-1, dp(30));
        modep.setMargins(0, dp(7), 0, 0);
        details.addView(mode, modep);

        // Actions.
        LinearLayout actions = hbox();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(48));
        ap.setMargins(0, dp(8), 0, 0);

        TextView deny = text("拒绝", 11.8f, TEXT);
        deny.setTypeface(Typeface.DEFAULT_BOLD);
        deny.setGravity(Gravity.CENTER);
        deny.setBackground(round(SURFACE_3, 16, BORDER, 1));
        LinearLayout.LayoutParams denyP = new LinearLayout.LayoutParams(0, dp(46), 1);
        denyP.setMargins(0, 0, dp(7), 0);
        actions.addView(deny, denyP);

        TextView allow = text("允许", 11.8f, Color.WHITE);
        allow.setTypeface(Typeface.DEFAULT_BOLD);
        allow.setGravity(Gravity.CENTER);
        allow.setBackground(round(ACCENT, 16, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams allowP = new LinearLayout.LayoutParams(0, dp(46), 1);
        allowP.setMargins(dp(7), 0, 0, 0);
        actions.addView(allow, allowP);
        panel.addView(actions, ap);

        View.OnClickListener resolveDeny = v -> {
            if (resolved[0]) return;
            resolved[0] = true;
            finishPermissionUi(pending);
            d.dismiss();
            rt.engine.respondPermission(r.requestId, false);
        };
        View.OnClickListener resolveAllow = v -> {
            if (resolved[0]) return;
            resolved[0] = true;
            finishPermissionUi(pending);
            d.dismiss();
            rt.engine.respondPermission(r.requestId, true);
        };
        deny.setOnClickListener(resolveDeny);
        allow.setOnClickListener(resolveAllow);
        d.setOnCancelListener(x -> {
            if (resolved[0]) return;
            resolved[0] = true;
            finishPermissionUi(pending);
            rt.engine.respondPermission(r.requestId, false);
        });

        d.setContentView(panel);
        d.show();
        stylePermissionDialogWindow(d, Math.min(dp(560), (int)(getResources().getDisplayMetrics().widthPixels * .91f)));
    }

    private void finishPermissionUi(ChatItem pending) {
        if (pending == null) return;
        pending.awaitingPermission = false;
        refreshToolItem(pending);
    }

    private String permissionGlyph(String tool) {
        if ("Root".equalsIgnoreCase(tool)) return "#";
        if ("Bash".equalsIgnoreCase(tool)) return ">_";
        if ("AndroidIntent".equalsIgnoreCase(tool)) return "↗";
        if ("Write".equalsIgnoreCase(tool) || "Edit".equalsIgnoreCase(tool) || "MultiEdit".equalsIgnoreCase(tool)) return "✎";
        if ("Delete".equalsIgnoreCase(tool)) return "×";
        if ("Move".equalsIgnoreCase(tool)) return "→";
        return "!";
    }

    private String permissionSubtitle(String tool) {
        if ("Root".equalsIgnoreCase(tool)) return "高风险：IQ Code 请求通过 Magisk/KernelSU 以 Android uid 0 执行系统命令";
        if ("Bash".equalsIgnoreCase(tool)) return "IQ Code 请求在本地 Termux 环境中执行以下命令";
        if ("AndroidIntent".equalsIgnoreCase(tool)) return "IQ Code 请求通过 Android 应用进程打开手机 App、网页或系统页面";
        if ("Write".equalsIgnoreCase(tool) || "Edit".equalsIgnoreCase(tool) || "MultiEdit".equalsIgnoreCase(tool))
            return "IQ Code 请求修改当前项目中的文件";
        if ("Delete".equalsIgnoreCase(tool)) return "IQ Code 请求删除项目中的文件或目录";
        return "IQ Code 请求执行一个需要确认的操作";
    }

    private String permissionModeLabel(String mode) {
        if ("acceptEdits".equals(mode)) return "自动允许编辑";
        if ("plan".equals(mode)) return "规划模式";
        if ("auto".equals(mode)) return "自动判断";
        if ("dontAsk".equals(mode)) return "不主动询问";
        if ("bypassPermissions".equals(mode)) return "跳过权限检查";
        return "每次询问";
    }

    private String prettyPermissionInput(String tool, JSONObject input) {
        if (input == null) return "";
        if ("AndroidIntent".equalsIgnoreCase(tool)) {
            StringBuilder out=new StringBuilder();
            String op=input.optString("operation","intent"); out.append("操作: ").append(op);
            String uri=input.optString("uri",input.optString("data","")); if(!uri.isEmpty())out.append("\n地址: ").append(uri);
            String pkg=input.optString("package",""); if(!pkg.isEmpty())out.append("\n应用包名: ").append(pkg); String app=input.optString("app_name",""); if(!app.isEmpty())out.append("\n应用名称: ").append(app);
            String action=input.optString("action",""); if(!action.isEmpty())out.append("\nAction: ").append(action);
            String component=input.optString("component",""); if(!component.isEmpty())out.append("\n组件: ").append(component);
            return out.toString();
        }
        String path = input.optString("file_path", input.optString("path", ""));
        if (!path.isEmpty()) {
            if ("Move".equalsIgnoreCase(tool)) {
                return input.optString("source", path) + "\n→ " + input.optString("destination", "");
            }
            return path;
        }
        try { return input.toString(2); } catch (Exception ignored) { return input.toString(); }
    }

    private String formatTimeout(long millis) {
        if (millis <= 0) return "默认";
        if (millis >= 60000 && millis % 60000 == 0) return (millis / 60000) + " 分钟";
        if (millis >= 1000 && millis % 1000 == 0) return (millis / 1000) + " 秒";
        return String.format(Locale.US, "%,d ms", millis);
    }

    private static String shortenMiddle(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        int left = Math.max(6, (max - 1) / 2);
        int right = Math.max(6, max - left - 1);
        return value.substring(0, left) + "…" + value.substring(value.length() - right);
    }

    private void stylePermissionDialogWindow(Dialog d, int width) {
        Window w = d == null ? null : d.getWindow();
        if (w == null) return;
        if(overlayShell!=null&&android.os.Build.VERSION.SDK_INT>=26)w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.view.WindowManager.LayoutParams a = w.getAttributes();
        a.dimAmount = .66f;
        w.setAttributes(a);
        w.setGravity(Gravity.CENTER);
        int height=Math.min(dp(720),(int)(getResources().getDisplayMetrics().heightPixels*.88f));
        w.setLayout(width, height);
        View decor = w.getDecorView();
        UiMotion.bindInteractive(decor);
        UiMotion.dialogIn(decor);
    }

    private void handleUsage(SessionRuntime rt,long in,long out){
        if(!isActiveRuntime(rt))return;
        final long current=Math.max(0L,in)+Math.max(0L,out);
        postRuntimeUi(rt,()->{
            long shown=current>0?current:rt.engine.estimateContextTokens();
            int window=Math.max(16_000,config.contextWindowTokens);
            int used=Math.max(0,Math.min(100,(int)Math.round(shown*100.0/window)));
            if(tokenText!=null)setAnimatedText(tokenText,"上下文 "+formatTokenCount(shown)+" · 剩余 "+Math.max(0,100-used)+"%",true);
            updateContextChip(shown,true);
        });
    }

    private void handleStatus(SessionRuntime rt,String status){
        touchRuntime(rt, "tool".equals(rt.phase)?"tool":"thinking", status==null?"正在处理…":status, true);
        if(!isActiveRuntime(rt))return;
        postRuntimeUi(rt,()->{workingStatus=status==null?"正在处理…":status;showWorkingIndicator(runtimeDisplayStatus(rt));ensureStreamingAssistantOnUi(rt);if(streamingItem!=null)recordAssistantProcessStep(streamingItem,runtimeDisplayStatus(rt));if(tokenText!=null)setAnimatedText(tokenText,shorten(runtimeDisplayStatus(rt),22),false);});
    }

    private void handleThinkingDelta(SessionRuntime rt,String delta){
        if(delta==null||delta.isEmpty())return;
        boolean schedule;long generation;
        synchronized(rt.liveThinking){
            appendCollapsedWhitespace(rt.liveThinking,delta);
            if(rt.liveThinking.length()>1200)rt.liveThinking.delete(0,rt.liveThinking.length()-1200);
            schedule=!rt.reasoningRenderPosted;
            rt.reasoningRenderPosted=true;generation=rt.reasoningGeneration;
        }
        if(schedule){
            final long renderGeneration=generation;
            uiHandler.postDelayed(()->{
                String preview;
                synchronized(rt.liveThinking){
                    if(renderGeneration!=rt.reasoningGeneration)return;
                    preview=rt.liveThinking.toString();rt.reasoningRenderPosted=false;
                }
                if(!isActiveRuntime(rt)||!rt.busy)return;
                ensureStreamingAssistantOnUi(rt);if(streamingItem!=null){streamingItem.thinking.setLength(0);streamingItem.thinking.append(preview);refreshAssistantThinking(streamingItem);}if(agentProgressView!=null)agentProgressView.setReasoningPreview(preview);if(chatAutoFollow)scrollChatSoft();
            },64L);
        }
    }

    private static void appendCollapsedWhitespace(StringBuilder out,String value){
        boolean previousWhitespace=out.length()>0&&Character.isWhitespace(out.charAt(out.length()-1));
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            boolean whitespace=Character.isWhitespace(c);
            if(!whitespace)out.append(c);
            else if(!previousWhitespace)out.append(' ');
            previousWhitespace=whitespace;
        }
    }

    private void clearThinkingPreview(SessionRuntime rt){
        synchronized(rt.liveThinking){rt.liveThinking.setLength(0);rt.reasoningRenderPosted=false;rt.reasoningGeneration++;}
        postRuntimeUi(rt,()->{if(agentProgressView!=null)agentProgressView.setReasoningPreview("");});
    }

    private void handleTurnComplete(SessionRuntime rt,String reason){
        clearThinkingPreview(rt);
        touchRuntime(rt,"done","已完成",false);
        if(!isActiveRuntime(rt)){rt.liveAssistant.setLength(0);refreshVisibleSessionRowsFromDiskAsync();return;}
        postRuntimeUi(rt,()->{if(rt.busy)return;flushStreamingDeltasNow(rt);if(streamingItem!=null)recordAssistantProcessStep(streamingItem,"完成回复");hideWorkingIndicator();setComposerBusy(false);updateComposerChips();stampCurrentContext(streamingItem,rt.engine);finishStreamingWhenRevealed();rt.liveAssistant.setLength(0);refreshChrome();refreshVisibleSessionRows();if(changesText!=null)refreshChanges();});
        refreshVisibleSessionRowsFromDiskAsync();
    }

    private void handleError(SessionRuntime rt,String message,Throwable e){
        clearThinkingPreview(rt);
        touchRuntime(rt,"error",message==null?"出错":message,false);
        if(!isActiveRuntime(rt)){rt.liveAssistant.setLength(0);refreshVisibleSessionRowsFromDiskAsync();return;}
        postRuntimeUi(rt,()->{if(rt.busy)return;flushStreamingDeltasNow(rt);if(streamingItem!=null)recordAssistantProcessStep(streamingItem,"发生错误");hideWorkingIndicator();setComposerBusy(false);updateComposerChips();stampCurrentContext(streamingItem,rt.engine);finishStreamingWhenRevealed();rt.liveAssistant.setLength(0);ChatItem err=new ChatItem(ChatItem.ERROR,"错误",friendlyNetworkError(message,e));transcript.add(err);if(chatMessages!=null)addChatView(err);if(chatAutoFollow)scrollChatSoft();refreshChrome();refreshVisibleSessionRows();});
        refreshVisibleSessionRowsFromDiskAsync();
    }

    private void setComposerBusy(boolean busy) {
        composerBusyState=busy;
        if (sendButton == null) return;
        // Keep the send affordance stable while the Agent is working. A separate stop button
        // is shown beside it; tapping send queues a steering message for the current task.
        sendButton.setText("↑");
        sendButton.setTextSize(18);
        sendButton.setContentDescription(busy ? "发送消息；当前任务完成后处理" : "发送消息");
        if(stopButton!=null){stopButton.setVisibility(busy?View.VISIBLE:View.GONE);UiMotion.state(stopButton,busy?1f:.9f,busy?1f:.9f,150);}
        UiMotion.state(sendButton,busy ? .96f : 1f,busy ? .98f : 1f,150);
    }

    private void syncComposerForActiveRuntime(){
        SessionRuntime rt=activeRuntime;
        setComposerBusy(rt!=null&&(rt.busy||rt.engine.isBusy()));
        updateComposerChips();
    }

    private void postRuntimeUi(SessionRuntime rt,Runnable action){
        if(rt==null||action==null||!isActiveRuntime(rt))return;
        final long generation=activeRuntimeGeneration;
        ui(()->{if(!isActiveRuntime(rt)||generation!=activeRuntimeGeneration)return;action.run();});
    }

    private void showWorkingIndicator(String status) {
        workingStatus = status == null || status.trim().isEmpty() ? "工作中…" : status.trim();
        ui(()->{SessionRuntime rt=activeRuntime;if(rt!=null){ensureStreamingAssistantOnUi(rt);if(agentProgressView!=null){agentProgressView.update(workingStatus,rt.taskSnapshot,rt.planState,true);if(chatAutoFollow)scrollChatSoft();}}});
    }

    private void hideWorkingIndicator() {
        workingPulseGeneration++;
        workingIndicator=null;
        ui(()->{SessionRuntime rt=activeRuntime;if(agentProgressView!=null&&rt!=null){agentProgressView.update(runtimeDisplayStatus(rt),rt.taskSnapshot,rt.planState,false);if(chatAutoFollow)scrollChatSoft();}});
    }

    private String friendlyNetworkError(String message, Throwable e) {
        String m=(message==null?"":message)+(e==null?"":"\n"+e.toString());
        String lower=m.toLowerCase(Locale.US);
        if(lower.contains("model_capability_not_supported")&&lower.contains("vision")) return "当前模型不支持视觉图片输入。请打开 API 设置，将“视觉图片输入（Vision）”切换为关闭，或改用支持视觉的模型。";
        if(lower.contains("cleartext")) return "系统或网络组件仍阻止了该 HTTP 地址；请检查设备网络策略，或改用 HTTPS。";
        return message==null?String.valueOf(e):message;
    }

    private void scheduleStreamingRender(){
        if(streamingView==null)return;
        if(streamRenderPosted)return;
        streamRenderPosted=true;
        // TextView layout and markdown-sized transcripts are expensive on the UI thread.
        // A bounded cadence leaves input and IME dispatch room while keeping output fluid.
        streamingView.postDelayed(streamingFrameRunnable, 64L);
    }

    private void cancelStreamingRender(){
        if(streamingView!=null)streamingView.removeCallbacks(streamingFrameRunnable);
        uiHandler.removeCallbacks(streamingFrameRunnable);
        streamRenderPosted=false;
    }

    private void renderStreamingFrame(){
        if(streamingRenderTreeGeneration!=chatTreeGeneration||!streamRenderPosted||streamingItem==null||streamingView==null){streamRenderPosted=false;return;}
        StringBuilder target=streamingItem.body;
        int targetLength=target.length();
        if(!streamingView.isAttachedToWindow()){
            streamingVisibleChars=targetLength;streamRenderPosted=false;
            if(streamingFinalizePending)finalizeStreamingMessage();
            return;
        }
        android.text.Editable current=streamingView.getEditableText();
        if(current!=null&&current.length()>=2&&current.subSequence(current.length()-2,current.length()).toString().equals(" ▍"))
            current.delete(current.length()-2,current.length());
        if(streamingVisibleChars>targetLength||streamingView.length()!=streamingVisibleChars){
            streamingVisibleChars=targetLength;
            String visible=target.substring(0,streamingVisibleChars);
            android.text.SpannableStringBuilder animated=new android.text.SpannableStringBuilder(visible);
            int cursorStart=animated.length();
            animated.append(" ▍");
            animated.setSpan(new ForegroundColorSpan(ACCENT),cursorStart,animated.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            streamingView.setText(animated,TextView.BufferType.SPANNABLE);
        }else if(targetLength>streamingVisibleChars){
            String visible=target.substring(streamingVisibleChars);
            android.text.SpannableStringBuilder animated=new android.text.SpannableStringBuilder(visible);
            int cursorStart=animated.length();
            animated.append(" ▍");
            animated.setSpan(new ForegroundColorSpan(ACCENT),cursorStart,animated.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            streamingView.append(animated);
        }
        streamingVisibleChars=targetLength;
        if(agentProgressView!=null)agentProgressView.setOutputBacklog(0);
        if(chatAutoFollow)scheduleAutoFollowScroll();
        streamRenderPosted=false;
        if(streamingFinalizePending)streamingView.post(this::finalizeStreamingMessage);
    }

    private void finishStreamingWhenRevealed(){
        if(streamingItem==null||streamingView==null){finalizeStreamingMessage();return;}
        if(streamingVisibleChars>=streamingItem.body.length()){finalizeStreamingMessage();return;}
        streamingFinalizePending=true;
        scheduleStreamingRender();
    }

    private void scheduleAutoFollowScroll(){
        if(autoFollowScrollPosted||!chatAutoFollow||chatScroll==null||chatMessages==null||chatUserTouching)return;
        final ScrollView targetScroll=chatScroll;final LinearLayout targetMessages=chatMessages;final long treeGeneration=chatTreeGeneration;
        android.view.ViewTreeObserver observer=targetScroll.getViewTreeObserver();
        if(!observer.isAlive())return;
        autoFollowScrollPosted=true;autoFollowScrollTarget=targetScroll;autoFollowMessagesTarget=targetMessages;autoFollowScrollTreeGeneration=treeGeneration;
        autoFollowScrollListener=()->{
            long scheduledGeneration=autoFollowScrollTreeGeneration;
            cancelAutoFollowScroll();
            if(scheduledGeneration!=treeGeneration||treeGeneration!=chatTreeGeneration||targetScroll!=chatScroll||targetMessages!=chatMessages)return true;
            if(chatAutoFollow&&!chatUserTouching)scrollChatToEndWithoutFocus(targetScroll,targetMessages,false);
            return true;
        };
        observer.addOnPreDrawListener(autoFollowScrollListener);
    }

    private void cancelAutoFollowScroll(){
        ScrollView target=autoFollowScrollTarget;
        android.view.ViewTreeObserver.OnPreDrawListener listener=autoFollowScrollListener;
        if(target!=null&&listener!=null){
            android.view.ViewTreeObserver observer=target.getViewTreeObserver();
            if(observer.isAlive())observer.removeOnPreDrawListener(listener);
        }
        autoFollowScrollPosted=false;autoFollowScrollTarget=null;autoFollowMessagesTarget=null;autoFollowScrollTreeGeneration=0L;autoFollowScrollListener=null;
    }
    private boolean discardEmptyStreamingMessage(){
        if(streamingItem==null||streamingItem.body.length()>0&&!streamingItem.body.toString().trim().isEmpty())return false;
        if(streamingItem.renderedView!=null&&streamingItem.renderedView.getParent() instanceof ViewGroup)
            ((ViewGroup)streamingItem.renderedView.getParent()).removeView(streamingItem.renderedView);
        else if(streamingBodyHost!=null&&streamingBodyHost.getParent() instanceof ViewGroup)
            ((ViewGroup)streamingBodyHost.getParent()).removeView(streamingBodyHost);
        transcript.remove(streamingItem);
        streamingItem=null;streamingView=null;streamingBodyHost=null;streamingVisibleChars=0;
        return true;
    }

    private void finalizeStreamingMessage(){
        streamingFinalizePending=false;
        cancelAutoFollowScroll();
        cancelStreamingRender();
        if(agentProgressView!=null)agentProgressView.setOutputBacklog(0);
        if(discardEmptyStreamingMessage())return;
        boolean follow=chatAutoFollow;int oldY=chatScroll==null?0:chatScroll.getScrollY();
        if(streamingItem!=null&&streamingItem.thinking.length()==0&&streamingItem.thinkingHost!=null)streamingItem.thinkingHost.setVisibility(View.GONE);
        if(streamingBodyHost!=null){
            streamingBodyHost.removeAllViews();
            LinearLayout rich=MarkdownRenderer.render(this,streamingItem.body.toString(),14f,neonTheme,lightTheme);
            streamingBodyHost.addView(rich,lp(-1,-2));
            UiMotion.contentUpdated(streamingBodyHost,true);
            UiMotion.staggerChildren(rich,8);
            UiMotion.bindInteractive(rich);
            addContextFooter(streamingBodyHost,streamingItem,true);
        }
        streamingItem=null;streamingView=null;streamingBodyHost=null;streamingVisibleChars=0;
        final ScrollView targetScroll=chatScroll;final LinearLayout targetMessages=chatMessages;final long treeGeneration=chatTreeGeneration;
        if(targetScroll!=null)targetScroll.post(()->{if(treeGeneration!=chatTreeGeneration||targetScroll!=chatScroll||targetMessages!=chatMessages)return;if(follow)scrollChatToEndWithoutFocus(targetScroll,targetMessages,true);else targetScroll.scrollTo(0,oldY);});
    }
    private int chatBottomScrollY(ScrollView targetScroll,LinearLayout targetMessages){
        if(targetScroll==null||targetMessages==null)return 0;
        int viewport=Math.max(0,targetScroll.getHeight()-targetScroll.getPaddingTop()-targetScroll.getPaddingBottom());
        return Math.max(0,targetMessages.getHeight()-viewport);
    }
    private void updateChatFollowState(){
        if(chatScroll==null||chatMessages==null)return;
        chatAutoFollow=chatBottomScrollY(chatScroll,chatMessages)-chatScroll.getScrollY()<=dp(72);
        if(!chatAutoFollow)cancelAutoFollowScroll();
    }
    private void scrollChatNow(){
        final ScrollView targetScroll=chatScroll;final LinearLayout targetMessages=chatMessages;final long treeGeneration=chatTreeGeneration;
        if(targetScroll!=null&&targetMessages!=null)targetScroll.post(()->{if(treeGeneration==chatTreeGeneration&&targetScroll==chatScroll&&targetMessages==chatMessages)scrollChatToEndWithoutFocus(targetScroll,targetMessages,false);});
    }
    private void scrollChatToEndWithoutFocus(ScrollView targetScroll,LinearLayout targetMessages,boolean smooth){
        if(targetScroll==null||targetMessages==null)return;
        int y=chatBottomScrollY(targetScroll,targetMessages);
        if(smooth)targetScroll.smoothScrollTo(0,y);else targetScroll.scrollTo(0,y);
    }
    private void scrollChatSoft(){
        final ScrollView targetScroll=chatScroll;final LinearLayout targetMessages=chatMessages;final long treeGeneration=chatTreeGeneration;
        if(targetScroll!=null&&targetMessages!=null)targetScroll.post(()->{if(treeGeneration!=chatTreeGeneration||targetScroll!=chatScroll||targetMessages!=chatMessages||!chatAutoFollow||chatUserTouching)return;targetScroll.scrollTo(0,chatBottomScrollY(targetScroll,targetMessages));});
    }
    private void scrollChat(){chatAutoFollow=true;cancelAutoFollowScroll();scrollChatSoft();}

    private int[] countDiff(String src){int a=0,d=0;if(src!=null)for(String line:src.split("\n")){if(line.startsWith("+")&&!line.startsWith("+++"))a++;else if(line.startsWith("-")&&!line.startsWith("---"))d++;}return new int[]{a,d};}
    private void updateDiffStats(int adds,int dels){
        if(diffStats==null)return;diffStats.setText("+"+adds+"  −"+dels);
        SpannableString sp=new SpannableString(diffStats.getText());int cut=sp.toString().indexOf("  ");
        if(cut>0){sp.setSpan(new ForegroundColorSpan(GREEN),0,cut,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);sp.setSpan(new ForegroundColorSpan(RED),cut+2,sp.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}diffStats.setText(sp);
        if(adds!=lastDiffAdds||dels!=lastDiffDeletes){lastDiffAdds=adds;lastDiffDeletes=dels;UiMotion.contentUpdated(diffStats,true);}
    }

    @Override protected void onDestroy(){
        stopDeviceStatus();
        UiCanvasStore.removeChangeListener(canvasChangeListener);
        if(keyboardRoot!=null&&keyboardLayoutListener!=null)keyboardRoot.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
        cancelAutoFollowScroll();
        streamRenderPosted=false;chatRenderPosted=false;uiHandler.removeCallbacks(streamingFrameRunnable);uiHandler.removeCallbacks(streamingDeltaFlushRunnable);uiHandler.removeCallbacks(toolElapsedTicker);if(primaryHost!=null)primaryHost.removeCallbacks(chatRenderRunnable);if(agentProgressView!=null)agentProgressView.clear();if(terminalPane!=null)terminalPane.closeAll();for(SessionRuntime rt:sessionRuntimes.values())try{rt.engine.shutdown();}catch(Exception ignored){}sessionRuntimes.clear();super.onDestroy();io.shutdownNow();
    }

    private void animatePane(View v){UiMotion.pageIn(v);UiMotion.bindInteractive(v);}
    private void animateMessage(View v){UiMotion.messageIn(v,false);UiMotion.bindInteractive(v);}

    private Dialog newOverlayAwareDialog(){return newOverlayAwareDialog(0);}
    private Dialog newOverlayAwareDialog(int theme){
        Dialog d=theme==0?new Dialog(this):new Dialog(this,theme);
        Window w=d.getWindow();
        if(w!=null&&overlayShell!=null&&android.os.Build.VERSION.SDK_INT>=26){
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return d;
    }

    private void styleDialogWindow(Dialog d, int width, int height) {
        Window w = d == null ? null : d.getWindow(); if (w == null) return;
        if(overlayShell!=null&&android.os.Build.VERSION.SDK_INT>=26)w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.view.WindowManager.LayoutParams a = w.getAttributes(); a.dimAmount = .56f; w.setAttributes(a);
        w.setGravity(Gravity.CENTER); w.setLayout(width, height);
        View decor = w.getDecorView();
        UiMotion.bindInteractive(decor);
        UiMotion.dialogIn(decor);
    }

    private void showAnimatedAlert(AlertDialog dialog){
        if(dialog==null)return;
        dialog.show();
        Window w=dialog.getWindow();
        if(w!=null){
            if(overlayShell!=null&&android.os.Build.VERSION.SDK_INT>=26){
                w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
            w.setLayout(Math.min(dp(460),(int)(getResources().getDisplayMetrics().widthPixels*.92f)),-2);
            UiMotion.bindInteractive(w.getDecorView());
            UiMotion.dialogIn(w.getDecorView());
        }
    }

    // --- Styling helpers ---
    private TextView iconButton(String s){TextView t=text(s,17,MUTED);t.setGravity(Gravity.CENTER);t.setBackground(round(Color.TRANSPARENT,8,Color.TRANSPARENT,0));return t;}
    private TextView smallIcon(String s){TextView t=text(s,15,MUTED);t.setGravity(Gravity.CENTER);return t;}
    private TextView pill(String s,int color){TextView t=text(s,11,color);t.setGravity(Gravity.CENTER);t.setPadding(dp(9),0,dp(9),0);t.setBackground(round(SURFACE_2,16,Color.TRANSPARENT,0));return t;}
    private TextView smallPill(String s,int color){TextView t=text(s,10,color);t.setPadding(dp(7),0,dp(7),0);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private View dividerVertical(){View v=new View(this);v.setBackgroundColor(BORDER_SOFT);return v;}
    private View dividerHorizontal(){View v=new View(this);v.setBackgroundColor(BORDER_SOFT);return v;}
    private LinearLayout vbox(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}
    private LinearLayout hbox(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.HORIZONTAL);return x;}
    private TextView text(String s,float sp,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setFontFeatureSettings("kern");return t;}
    private void setAnimatedText(TextView view,CharSequence value,boolean emphasized){if(view==null)return;CharSequence next=value==null?"":value;if(String.valueOf(view.getText()).contentEquals(next))return;view.setText(next);UiMotion.contentUpdated(view,emphasized);}
    private GradientDrawable round(int fill,float radiusDp,int stroke,int strokeDp){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(radiusDp*getResources().getDisplayMetrics().density);if(strokeDp>0)g.setStroke(dp(strokeDp),stroke);return g;}
    private GradientDrawable gradient(int start,int end,float radiusDp,int stroke,int strokeDp){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{start,end});g.setCornerRadius(radiusDp*getResources().getDisplayMetrics().density);if(strokeDp>0)g.setStroke(dp(strokeDp),stroke);return g;}
    private GradientDrawable themedTopBackground(){return neonTheme?gradient(Color.rgb(8,16,34),Color.rgb(18,22,52),0,BORDER_SOFT,0):round(TOP,0,Color.TRANSPARENT,0);}
    private GradientDrawable navyGradient(float radiusDp){return gradient(Color.rgb(8,16,34),Color.rgb(13,22,43),radiusDp,BORDER,0);}
    private GradientDrawable elevatedCard(float radiusDp){return neonTheme?gradient(Color.rgb(28,36,65),Color.rgb(15,27,48),radiusDp,BORDER,1):round(SURFACE_2,radiusDp,Color.TRANSPARENT,0);}
    private GradientDrawable accentGradient(float radiusDp){return neonTheme?gradient(Color.rgb(91,103,255),Color.rgb(142,78,255),radiusDp,Color.rgb(151,129,255),1):round(ACCENT,radiusDp,Color.TRANSPARENT,0);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void ui(Runnable r){runOnUiThread(r);}
    private static String shorten(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,Math.max(0,n-1))+"…";}
    private static String shortenMultiline(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,n)+"\n…";}
    private static String cleanBaseUrl(String s){return s==null?"":s.trim();}
    private static byte[] readFile(File f)throws Exception{try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[(int)f.length()];int o=0,n;while(o<b.length&&(n=in.read(b,o,b.length-o))>0)o+=n;return b;}}
    private static void writeFileBytes(File f,byte[] data)throws Exception{File parent=f.getParentFile();if(parent!=null)parent.mkdirs();try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}}
    private static boolean deleteRecursive(File f){if(f==null||!f.exists())return true;if(f.isDirectory()){File[] xs=f.listFiles();if(xs!=null)for(File x:xs)if(!deleteRecursive(x))return false;}return f.delete();}
}
