package com.iqge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.iqge.sandbox.SandboxTermuxBridge;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.JNI;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Upstream Termux TerminalView + TerminalEmulator + JNI PTY embedded in the IQ workspace.
 * The surrounding chrome follows Termux's terminal interaction model: left session drawer,
 * native text selection/copy-paste, two-row extra keys, volume Ctrl/Fn mappings and IME input.
 * No proot and no TextView terminal emulation.
 */
public final class TermuxTerminalPane extends FrameLayout implements TerminalViewClient, TerminalSessionClient {
    private int BG, BAR, DRAWER_BG, KEY_BG, DIVIDER, SELECTED_BG, TEXT, MUTED, ACCENT, ERROR;
    private boolean neonTheme, lightTheme;
    private static final String DEFAULT_EXTRA_KEYS = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]";

    private final RuntimeInstaller runtime;
    private final List<TerminalSession> sessions = new ArrayList<>();
    private final LinearLayout content;
    private final LinearLayout toolbar;
    private final FrameLayout terminalHost;
    private final LinearLayout extraKeysHost;
    private final LinearLayout drawer;
    private final LinearLayout drawerSessions;
    private final View drawerScrim;
    private final TextView title;
    private final TextView drawerTitle;
    private final View drawerDivider;
    private TerminalView terminalView;
    private int selected = -1;
    private float terminalTextSp = 14f;
    private boolean ctrl, alt, shift, fn;
    private boolean drawerOpen;
    private boolean backMapsEscape;
    private android.os.PowerManager.WakeLock wakeLock;
    private Throwable nativeLoadError;
    private String nextSessionWorkingDirectory = TermuxConstants.TERMUX_HOME_DIR_PATH;

    public TermuxTerminalPane(Context context, RuntimeInstaller runtime) {
        this(context,runtime,false,false);
    }

    public TermuxTerminalPane(Context context, RuntimeInstaller runtime, boolean neonTheme) {
        this(context,runtime,neonTheme,false);
    }

    public TermuxTerminalPane(Context context, RuntimeInstaller runtime, boolean neonTheme, boolean lightTheme) {
        super(context);
        this.runtime = runtime;
        applyPaletteValues(neonTheme,lightTheme);
        setBackgroundColor(BG);
        setLayoutParams(new ViewGroup.LayoutParams(-1, -1));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(BG);
        addView(content, new FrameLayout.LayoutParams(-1, -1));

        toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.setBackgroundColor(BAR);
        toolbar.addView(action("☰", v -> toggleDrawer()), lp(dp(44), dp(42)));
        title = label("Terminal", 12, TEXT, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        toolbar.addView(action("⌨", v -> toggleKeyboard()), lp(dp(40), dp(36)));
        toolbar.addView(action("⋮", v -> showQuickActions()), lp(dp(40), dp(36)));
        content.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(42)));

        terminalHost = new FrameLayout(context);
        terminalHost.setBackgroundColor(BG);
        content.addView(terminalHost, new LinearLayout.LayoutParams(-1, 0, 1));

        extraKeysHost = new LinearLayout(context);
        extraKeysHost.setOrientation(LinearLayout.VERTICAL);
        extraKeysHost.setBackgroundColor(BAR);
        UiMotion.enableLayoutChanges(extraKeysHost);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        content.addView(extraKeysHost, new LinearLayout.LayoutParams(-1, dp(landscape?68:82)));

        // Tiny left-edge gesture target, matching the discoverability of Termux's session drawer.
        View edge = new View(context);
        FrameLayout.LayoutParams edgeLp = new FrameLayout.LayoutParams(dp(18), -1, Gravity.LEFT);
        edge.setOnTouchListener(new OnTouchListener() {
            float downX;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getX(); return true; }
                if (e.getActionMasked() == MotionEvent.ACTION_UP) { if (e.getX() - downX > dp(4)) openDrawer(); return true; }
                return true;
            }
        });
        addView(edge, edgeLp);

        drawerScrim = new View(context);
        drawerScrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        drawerScrim.setVisibility(GONE);
        drawerScrim.setAlpha(0f);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        drawer = new LinearLayout(context);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(DRAWER_BG);
        drawer.setPadding(dp(10), dp(12), dp(10), dp(10));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(286), -1, Gravity.LEFT);
        drawer.setVisibility(GONE);
        addView(drawer, dlp);

        drawerTitle = label("Termux sessions", 14, TEXT, true);
        drawerTitle.setGravity(Gravity.CENTER_VERTICAL);
        drawer.addView(drawerTitle, new LinearLayout.LayoutParams(-1, dp(44)));
        drawer.addView(drawerAction("＋  New session", v -> { newSession(); closeDrawer(); }), new LinearLayout.LayoutParams(-1, dp(42)));

        drawerDivider = new View(context); drawerDivider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, 1); lineLp.setMargins(0, dp(7), 0, dp(7));
        drawer.addView(drawerDivider, lineLp);

        ScrollView scroll = new ScrollView(context);
        drawerSessions = new LinearLayout(context);
        drawerSessions.setOrientation(LinearLayout.VERTICAL);
        UiMotion.enableLayoutChanges(drawerSessions);
        scroll.addView(drawerSessions, new ScrollView.LayoutParams(-1, -2));
        drawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        drawer.addView(drawerAction("⌨  Toggle keyboard", v -> { toggleKeyboard(); closeDrawer(); }), new LinearLayout.LayoutParams(-1, dp(40)));
        drawer.addView(drawerAction("↻  Reload properties", v -> { reloadProperties(); closeDrawer(); }), new LinearLayout.LayoutParams(-1, dp(40)));

        nativeLoadError = preloadNativePty();
        reloadProperties();
        UiMotion.bindInteractive(content);
        if (runtime.isInstalled()) newSession(); else showRuntimeMessage();
    }

    public void onRuntimeReady() { if (sessions.isEmpty()) newSession(); }
    public void setNextSessionWorkingDirectory(String path) { if(path!=null && new File(path).isDirectory()) nextSessionWorkingDirectory=path; }
    public void setKeyboardOffset(int offset,boolean animate){
        int target=Math.max(0,offset);
        if(terminalHost.getPaddingBottom()!=target){
            terminalHost.setClipToPadding(target>0);
            terminalHost.setPadding(0,0,0,target);
        }
        if(animate){
            extraKeysHost.animate().cancel();
            extraKeysHost.animate().translationY(-target).setDuration(180L).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }else if(extraKeysHost.getTranslationY()!=-target)extraKeysHost.setTranslationY(-target);
    }

    /** Recolors both Termux chrome and live emulator palettes without restarting PTYs or losing scrollback. */
    public void applyTheme(boolean neon) { applyTheme(neon,false); }

    public void applyTheme(boolean neon,boolean light) {
        if (neon == neonTheme && light == lightTheme) { applyTerminalPaletteToSessions(); return; }
        int oldText=TEXT,oldMuted=MUTED,oldAccent=ACCENT,oldError=ERROR;
        applyPaletteValues(neon,light);
        setBackgroundColor(BG);content.setBackgroundColor(BG);terminalHost.setBackgroundColor(BG);
        toolbar.setBackgroundColor(BAR);extraKeysHost.setBackgroundColor(BAR);drawer.setBackgroundColor(DRAWER_BG);
        drawerDivider.setBackgroundColor(DIVIDER);
        recolorTextTree(this,oldText,oldMuted,oldAccent,oldError);
        reloadProperties();
        applyTerminalPaletteToSessions();
        refreshDrawerSessions();
        if(terminalView!=null){terminalView.onScreenUpdated();terminalView.invalidate();}
        UiMotion.themeChanged(content);
    }

    private void applyPaletteValues(boolean neon,boolean light) {
        neonTheme=neon;lightTheme=light;
        if(light){
            BG=Color.rgb(246,248,252);BAR=Color.WHITE;DRAWER_BG=Color.rgb(238,242,248);
            KEY_BG=Color.rgb(231,235,243);DIVIDER=Color.rgb(216,222,232);SELECTED_BG=Color.rgb(225,230,245);
            TEXT=Color.rgb(29,36,51);MUTED=Color.rgb(99,112,132);ACCENT=Color.rgb(83,91,214);ERROR=Color.rgb(194,65,75);
        }else if(neon){
            BG=Color.rgb(4,9,20);BAR=Color.rgb(12,20,42);DRAWER_BG=Color.rgb(13,23,43);
            KEY_BG=Color.rgb(26,35,64);DIVIDER=Color.rgb(51,62,91);SELECTED_BG=Color.rgb(42,43,83);
            TEXT=Color.rgb(244,243,255);MUTED=Color.rgb(142,149,179);ACCENT=Color.rgb(142,78,255);ERROR=Color.rgb(255,111,132);
        }else{
            BG=Color.rgb(0,0,0);BAR=Color.rgb(18,18,18);DRAWER_BG=Color.rgb(28,28,28);
            KEY_BG=Color.rgb(34,34,34);DIVIDER=Color.rgb(58,58,58);SELECTED_BG=Color.rgb(44,44,44);
            TEXT=Color.rgb(238,238,238);MUTED=Color.rgb(158,158,158);ACCENT=Color.rgb(217,119,87);ERROR=Color.rgb(214,120,111);
        }
    }

    private void recolorTextTree(View view,int oldText,int oldMuted,int oldAccent,int oldError){
        if(view instanceof TextView){TextView t=(TextView)view;int color=t.getCurrentTextColor();
            if(color==oldText)t.setTextColor(TEXT);else if(color==oldMuted)t.setTextColor(MUTED);
            else if(color==oldAccent)t.setTextColor(ACCENT);else if(color==oldError)t.setTextColor(ERROR);
        }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)recolorTextTree(g.getChildAt(i),oldText,oldMuted,oldAccent,oldError);}
    }

    public void closeAll() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        for (TerminalSession s : new ArrayList<>(sessions)) try { s.finishIfRunning(); } catch (Throwable ignored) {}
        sessions.clear(); selected = -1;
    }

    private void showRuntimeMessage() {
        terminalHost.removeAllViews();
        TextView t = label("正在准备内置 Termux 运行环境…\n首次启动只需本地解压，无需联网下载。", 13, MUTED, false);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(24), dp(24), dp(24), dp(24));
        terminalHost.addView(t, new FrameLayout.LayoutParams(-1, -1));
        UiMotion.pageIn(t);
    }

    private void newSession() {
        if (!runtime.isInstalled()) { showRuntimeMessage(); return; }
        nativeLoadError = preloadNativePty();
        if (nativeLoadError != null) { showTerminalFailure(nativeLoadError); return; }
        String shell = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(shell).isFile()) { showRuntimeMessage(); return; }
        String cwd = new File(nextSessionWorkingDirectory).isDirectory() ? nextSessionWorkingDirectory : TermuxConstants.TERMUX_HOME_DIR_PATH;
        String[] args = new String[]{shell, "-l"};
        TerminalSession session = new TerminalSession(shell, cwd, args, environmentArray(), 5000, this);
        session.mSessionName = "bash " + (sessions.size() + 1);
        sessions.add(session);
        applyTerminalPalette(session);
        selectSession(sessions.size() - 1);
        refreshDrawerSessions();
    }

    private Throwable preloadNativePty() {
        try {
            if (JNI.isLoaded()) return null;
            String dir = getContext().getApplicationInfo().nativeLibraryDir;
            File library = new File(dir, "libtermux.so");
            if (!library.isFile()) throw new UnsatisfiedLinkError("libtermux.so is missing from nativeLibraryDir: " + library);
            JNI.load(library.getAbsolutePath());
            return null;
        } catch (Throwable t) {
            Log.e("IQGETerminal", "Could not load libtermux.so", t);
            return t;
        }
    }

    private String[] environmentArray() {
        SandboxTermuxBridge.ensureCliInstalled(getContext());
        Map<String,String> e = new LinkedHashMap<>();
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        e.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        e.put("PREFIX", prefix);
        e.put("TMPDIR", prefix + "/tmp");
        e.put("PATH", prefix + "/bin");
        e.put("SHELL", prefix + "/bin/bash");
        e.put("TERM", "xterm-256color");
        e.put("COLORTERM", "truecolor");
        e.put("LANG", "en_US.UTF-8");
        e.put("TERMUX_VERSION", "0.118.3");
        e.put("TERMUX_APP__PACKAGE_NAME", "com.iqge");
        e.put("TERMUX_APP__PACKAGE_MANAGER", "apt");
        e.put("TERMUX_APP__PACKAGE_VARIANT", "apt-android-7");
        e.put("TERMUX_APP__FILES_DIR", "/data/user/0/com.iqge/files");
        e.put("TERMUX_APP__DATA_DIR", "/data/user/0/com.iqge");
        e.put("TERMUX_APP__LEGACY_DATA_DIR", "/data/user/0/com.iqge");
        e.put("TERMUX_APP__PID", Integer.toString(android.os.Process.myPid()));
        e.put("TERMUX_APP__UID", Integer.toString(android.os.Process.myUid()));
        e.put("TERMUX_APP__TARGET_SDK", "28");
        e.put("TERMUX_MAIN_PACKAGE_FORMAT", "debian");
        e.put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
        e.put("TERMUX_APK_RELEASE", "IQGE");
        e.put("TERMUX__HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        e.put("TERMUX__PREFIX", prefix);
        e.put("TERMUX__ROOTFS_DIR", "/data/user/0/com.iqge/files");
        e.put("TERMUX__ROOTFS", "/data/user/0/com.iqge/files");
        e.put("IQ_CODE_ANDROID", "1");
        e.put("IQGE_TERMINAL_SESSION", "1");
        e.put("IQGE_SANDBOX_BRIDGE_DIR", SandboxTermuxBridge.bridgeDir(getContext()));
        e.put("IQGE_APK_PATH", getContext().getApplicationInfo().sourceDir);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String,String> x : e.entrySet()) out.add(x.getKey() + "=" + x.getValue());
        return out.toArray(new String[0]);
    }

    private void selectSession(int index) {
        if (index < 0 || index >= sessions.size()) return;
        selected = index;
        terminalHost.removeAllViews();

        // Important: do not attach the PTY before TerminalView has completed its first layout.
        // attachSession() immediately calculates rows/columns and loads libtermux.so. In the old
        // build this happened while the pane was still being inserted into another FrameLayout,
        // so any JNI/runtime error escaped from onSizeChanged and killed the whole Activity.
        final TerminalView view = new TerminalView(getContext(), null);
        view.setTerminalViewClient(this);
        view.setTextSize(spPx(terminalTextSp));
        view.setFocusable(true); view.setFocusableInTouchMode(true);
        terminalView = view;
        terminalHost.addView(view, new FrameLayout.LayoutParams(-1, -1));
        UiMotion.pageIn(view);
        updateTitle();
        refreshDrawerSessions();

        view.post(() -> {
            if (terminalView != view || selected != index || index >= sessions.size()) return;
            try {
                TerminalSession attached=sessions.get(index);
                view.attachSession(attached);
                applyTerminalPalette(attached);
                view.onScreenUpdated();
                view.requestFocus();
                view.postDelayed(this::showKeyboard, 180);
            } catch (Throwable error) {
                Log.e("IQGETerminal", "Failed to attach native Termux PTY", error);
                if (terminalView == view) showTerminalFailure(error);
            }
        });
    }

    private void showTerminalFailure(Throwable error) {
        terminalHost.removeAllViews();
        terminalView = null;
        LinearLayout box = new LinearLayout(getContext()); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
        box.setPadding(dp(22),dp(22),dp(22),dp(22)); box.setBackgroundColor(BG);
        TextView h=label("Terminal failed to start",15,ERROR,true);h.setGravity(Gravity.CENTER);box.addView(h,new LinearLayout.LayoutParams(-1,dp(36)));
        String detail=terminalErrorDetail(error);
        TextView m=label(detail+"\n\nThe app stayed open so you can repair the runtime instead of crashing.",11,MUTED,false);
        m.setTypeface(Typeface.MONOSPACE);m.setTextIsSelectable(true);m.setGravity(Gravity.CENTER);box.addView(m,new LinearLayout.LayoutParams(-1,-2));
        TextView retry=drawerAction("↻  Retry terminal",v->selectSession(Math.max(0,selected)));retry.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(180),dp(42));rp.setMargins(0,dp(16),0,0);box.addView(retry,rp);
        terminalHost.addView(box,new FrameLayout.LayoutParams(-1,-1));
        UiMotion.bindInteractive(box);
        UiMotion.pageIn(box);
    }

    private String terminalErrorDetail(Throwable error) {
        if (error == null) return "Unknown PTY error";
        StringBuilder b = new StringBuilder();
        Throwable t = error; int depth = 0;
        while (t != null && depth++ < 5) {
            if (b.length() > 0) b.append("\ncaused by: ");
            b.append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage()));
            t = t.getCause();
        }
        Throwable load = JNI.getLoadError();
        if (load != null && load != error) b.append("\nloader: ").append(load.getClass().getSimpleName()).append(": ").append(String.valueOf(load.getMessage()));
        return b.toString();
    }

    private void updateTitle() {
        TerminalSession s = current();
        String t = s == null ? null : s.getTitle();
        if (t == null || t.trim().isEmpty()) t = s == null ? "Terminal" : s.mSessionName;
        String next=t == null || t.trim().isEmpty() ? "Terminal" : t;
        if(!String.valueOf(title.getText()).equals(next)){title.setText(next);UiMotion.contentUpdated(title,false);}
    }

    private void refreshDrawerSessions() {
        if (drawerSessions == null) return;
        drawerSessions.removeAllViews();
        for (int i = 0; i < sessions.size(); i++) {
            final int idx = i;
            TerminalSession s = sessions.get(i);
            String n = s.mSessionName == null ? "session " + (i + 1) : s.mSessionName;
            String sub = s.isRunning() ? "running" : "finished";
            LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(5), dp(4), dp(5));
            if (i == selected) row.setBackgroundColor(SELECTED_BG);
            TextView text = label((i == selected ? "●  " : "○  ") + n + "\n    " + sub, 12, i == selected ? TEXT : MUTED, i == selected);
            row.addView(text, new LinearLayout.LayoutParams(0, dp(52), 1));
            TextView close = label("×", 18, MUTED, false); close.setGravity(Gravity.CENTER); close.setOnClickListener(v -> closeSession(idx));
            row.addView(close, new LinearLayout.LayoutParams(dp(38), dp(42)));
            row.setOnClickListener(v -> { selectSession(idx); closeDrawer(); });
            row.setOnLongClickListener(v -> { selected = idx; renameSession(); return true; });
            drawerSessions.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
        }
        UiMotion.bindInteractive(drawerSessions);
    }

    private void closeSession(int index) {
        if (index < 0 || index >= sessions.size()) return;
        try { sessions.get(index).finishIfRunning(); } catch (Throwable ignored) {}
        sessions.remove(index);
        if (sessions.isEmpty()) { selected = -1; terminalHost.removeAllViews(); newSession(); }
        else selectSession(Math.min(index, sessions.size() - 1));
        refreshDrawerSessions();
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        UiMotion.drawerIn(drawerScrim,drawer,286f);
        refreshDrawerSessions();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        UiMotion.drawerOut(drawerScrim,drawer,286f,null);
    }

    private void toggleDrawer() { if (drawerOpen) closeDrawer(); else openDrawer(); }

    private void reloadProperties() {
        backMapsEscape = false;
        String matrix = readTermuxProperty("extra-keys");
        String back = readTermuxProperty("back-key");
        if (back != null) backMapsEscape = "escape".equalsIgnoreCase(back.trim());
        buildExtraKeys(matrix == null || matrix.trim().isEmpty() ? DEFAULT_EXTRA_KEYS : matrix);
    }

    private String readTermuxProperty(String wanted) {
        File f = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/termux.properties");
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line; StringBuilder pending = new StringBuilder();
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue;
                if (pending.length() > 0) pending.append(trimmed); else pending.append(line);
                if (trimmed.endsWith("\\")) { pending.setLength(pending.length() - 1); continue; }
                String full = pending.toString(); pending.setLength(0);
                int eq = full.indexOf('='); if (eq < 0) continue;
                if (wanted.equals(full.substring(0, eq).trim())) return full.substring(eq + 1).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void buildExtraKeys(String source) {
        if (extraKeysHost == null) return;
        extraKeysHost.removeAllViews();
        try {
            String normalized = source.replace('\'', '"');
            Pattern p = Pattern.compile("([\\{,]\\s*)(key|popup|macro|display)\\s*:");
            Matcher m = p.matcher(normalized); normalized = m.replaceAll("$1\"$2\":");
            JSONArray matrix = new JSONArray(normalized);
            for (int i = 0; i < matrix.length(); i++) {
                JSONArray row = matrix.getJSONArray(i);
                LinearLayout r = extraKeyRow();
                for (int j = 0; j < row.length(); j++) {
                    Object item = row.get(j); String key; String display;
                    if (item instanceof JSONObject) {
                        JSONObject o = (JSONObject)item;
                        key = o.optString("key", o.optString("macro", ""));
                        display = o.optString("display", displayName(key));
                    } else { key = String.valueOf(item); display = displayName(key); }
                    final String action = key;
                    TextView b = key(display);
                    b.setOnClickListener(v -> handleExtraKey(action, b));
                    r.addView(b, new LinearLayout.LayoutParams(0, -1, 1));
                }
                extraKeysHost.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            if (matrix.length() == 0) buildDefaultExtraKeys();
        } catch (Exception e) { buildDefaultExtraKeys(); }
        UiMotion.bindInteractive(extraKeysHost);
        UiMotion.staggerChildren(extraKeysHost,3);
    }

    private void buildDefaultExtraKeys() {
        extraKeysHost.removeAllViews();
        String[][] rows = {{"ESC","/","-","HOME","UP","END","PGUP"},{"TAB","CTRL","ALT","LEFT","DOWN","RIGHT","PGDN"}};
        for (String[] row : rows) {
            LinearLayout r = extraKeyRow();
            for (String k : row) { final String action = k; TextView b = key(displayName(k)); b.setOnClickListener(v -> handleExtraKey(action, b)); r.addView(b, new LinearLayout.LayoutParams(0, -1, 1)); }
            extraKeysHost.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private LinearLayout extraKeyRow() {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(3), dp(2), dp(3), dp(2)); row.setBackgroundColor(BAR); return row;
    }

    private String displayName(String key) {
        if (key == null) return "";
        switch (key.toUpperCase()) {
            case "LEFT": return "←"; case "RIGHT": return "→"; case "UP": return "↑"; case "DOWN": return "↓";
            default: return key;
        }
    }

    private void handleExtraKey(String raw, TextView source) {
        if (raw == null) return;
        String k = raw.trim(); String upper = k.toUpperCase();
        if (upper.contains(" ")) { for (String token : k.split("\\s+")) handleExtraKey(token, source); return; }
        switch (upper) {
            case "CTRL": ctrl = !ctrl; source.setTextColor(ctrl ? ACCENT : TEXT); UiMotion.contentUpdated(source,true); return;
            case "ALT": alt = !alt; source.setTextColor(alt ? ACCENT : TEXT); UiMotion.contentUpdated(source,true); return;
            case "SHIFT": shift = !shift; source.setTextColor(shift ? ACCENT : TEXT); UiMotion.contentUpdated(source,true); return;
            case "FN": fn = !fn; source.setTextColor(fn ? ACCENT : TEXT); UiMotion.contentUpdated(source,true); return;
            case "KEYBOARD": toggleKeyboard(); return;
            case "DRAWER": openDrawer(); return;
            case "ESC": write("\u001b"); return;
            case "TAB": write("\t"); return;
            case "ENTER": write("\r"); return;
            case "BKSP": case "BACKSPACE": write("\u007f"); return;
            case "HOME": write("\u001b[H"); return;
            case "END": write("\u001b[F"); return;
            case "PGUP": write("\u001b[5~"); return;
            case "PGDN": write("\u001b[6~"); return;
            case "LEFT": write("\u001b[D"); return;
            case "RIGHT": write("\u001b[C"); return;
            case "UP": write("\u001b[A"); return;
            case "DOWN": write("\u001b[B"); return;
            default: write(k);
        }
    }

    private TextView key(String s) { TextView t = label(s, 10, TEXT, true); t.setGravity(Gravity.CENTER); t.setBackgroundColor(KEY_BG); t.setPadding(dp(2), 0, dp(2), 0); return t; }

    private void write(String s) {
        TerminalSession x = current(); if (x == null) return;
        byte[] b = s.getBytes(StandardCharsets.UTF_8); x.write(b, 0, b.length);
        ctrl = alt = shift = fn = false;
    }

    private void applyTerminalPaletteToSessions(){for(TerminalSession session:sessions)applyTerminalPalette(session);}

    private void applyTerminalPalette(TerminalSession session){
        if(session==null||session.getEmulator()==null)return;
        int[] colors=session.getEmulator().mColors.mCurrentColors;
        if(lightTheme){
            colors[0]=BG;colors[1]=Color.rgb(185,28,28);colors[2]=Color.rgb(22,115,76);colors[3]=Color.rgb(161,98,7);
            colors[4]=Color.rgb(29,78,216);colors[5]=Color.rgb(126,34,206);colors[6]=Color.rgb(8,126,153);colors[7]=TEXT;
            colors[8]=MUTED;colors[9]=Color.rgb(180,35,46);colors[10]=Color.rgb(22,115,76);colors[11]=Color.rgb(161,98,7);
            colors[12]=Color.rgb(29,78,216);colors[13]=Color.rgb(126,34,206);colors[14]=Color.rgb(8,126,153);colors[15]=TEXT;
            colors[TextStyle.COLOR_INDEX_FOREGROUND]=TEXT;colors[TextStyle.COLOR_INDEX_BACKGROUND]=BG;colors[TextStyle.COLOR_INDEX_CURSOR]=ACCENT;return;
        }
        // Keep ANSI meaning intact while harmonising its core dark/bright colors with the selected UI.
        colors[0]=BG;colors[1]=neonTheme?Color.rgb(229,88,112):Color.rgb(205,0,0);
        colors[2]=neonTheme?Color.rgb(28,190,145):Color.rgb(0,205,0);
        colors[3]=neonTheme?Color.rgb(232,185,92):Color.rgb(205,205,0);
        colors[4]=neonTheme?Color.rgb(101,153,255):Color.rgb(100,149,237);
        colors[5]=neonTheme?Color.rgb(190,126,255):Color.rgb(205,0,205);
        colors[6]=neonTheme?Color.rgb(55,205,216):Color.rgb(0,205,205);colors[7]=TEXT;
        colors[8]=MUTED;colors[9]=ERROR;colors[10]=neonTheme?Color.rgb(72,226,181):Color.rgb(0,255,0);
        colors[11]=neonTheme?Color.rgb(255,214,119):Color.rgb(255,255,0);
        colors[12]=neonTheme?Color.rgb(122,151,255):Color.rgb(92,92,255);
        colors[13]=neonTheme?Color.rgb(210,154,255):Color.rgb(255,0,255);
        colors[14]=neonTheme?Color.rgb(83,226,236):Color.rgb(0,255,255);colors[15]=Color.WHITE;
        colors[TextStyle.COLOR_INDEX_FOREGROUND]=TEXT;
        colors[TextStyle.COLOR_INDEX_BACKGROUND]=BG;
        colors[TextStyle.COLOR_INDEX_CURSOR]=neonTheme?Color.rgb(164,132,255):Color.WHITE;
    }

    public void requestKeyboard() { if (terminalView != null) terminalView.postDelayed(this::showKeyboard, 80); }
    private void showKeyboard() { if (terminalView == null) return; terminalView.requestFocus(); InputMethodManager imm = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE); if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT); }
    private void toggleKeyboard() { if (terminalView == null) return; terminalView.requestFocus(); InputMethodManager imm = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE); if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0); }
    private void changeFont(int delta) { terminalTextSp = Math.max(8f, Math.min(32f, terminalTextSp + delta)); if (terminalView != null) terminalView.setTextSize(spPx(terminalTextSp)); }

    private void showQuickActions() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(getContext());
        String[] items = {"Paste", "Copy selection", "Reset terminal", "New session", "Rename session", "Close session", "Kill shell", "Font smaller", "Font larger", "Reload termux.properties", "Toggle wake lock"};
        android.app.AlertDialog dialog=b.setTitle("Terminal").setItems(items, (d,w) -> { TerminalSession s = current(); switch (w) {
            case 0: onPasteTextFromClipboard(s); break; case 1: copySelection(); break; case 2: if (s != null) s.reset(); break;
            case 3: newSession(); break; case 4: renameSession(); break; case 5: closeSession(selected); break; case 6: if (s != null) s.finishIfRunning(); break;
            case 7: changeFont(-1); break; case 8: changeFont(1); break; case 9: reloadProperties(); break; case 10: toggleWakeLock(); break;
        }}).create();dialog.show();if(dialog.getWindow()!=null){UiMotion.bindInteractive(dialog.getWindow().getDecorView());UiMotion.dialogIn(dialog.getWindow().getDecorView());}
    }

    private void renameSession() { TerminalSession s = current(); if (s == null) return; final android.widget.EditText e = new android.widget.EditText(getContext()); e.setSingleLine(true); e.setText(s.mSessionName); android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(getContext()).setTitle("Rename session").setView(e).setNegativeButton("Cancel", null).setPositiveButton("Rename", (d,w) -> { String n = e.getText().toString().trim(); if (!n.isEmpty()) s.mSessionName = n; updateTitle(); refreshDrawerSessions(); }).create();dialog.show();if(dialog.getWindow()!=null){UiMotion.bindInteractive(dialog.getWindow().getDecorView());UiMotion.dialogIn(dialog.getWindow().getDecorView());} }
    private void toggleWakeLock() { try { if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); toast("Wake lock released"); } else { android.os.PowerManager pm = (android.os.PowerManager)getContext().getSystemService(Context.POWER_SERVICE); wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "iqge:terminal"); wakeLock.acquire(); toast("Wake lock acquired"); } } catch (Throwable e) { toast("Wake lock: " + e.getMessage()); } }
    private void copySelection() { if (terminalView == null) return; String s = terminalView.getSelectedText(); if (s == null || s.isEmpty()) return; ClipboardManager cm = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("terminal", s)); toast("Copied"); }
    private TerminalSession current() { return selected >= 0 && selected < sessions.size() ? sessions.get(selected) : null; }

    // TerminalSessionClient
    @Override public void onTextChanged(@NonNull TerminalSession s) { if (s == current() && terminalView != null) terminalView.onScreenUpdated(); }
    @Override public void onTitleChanged(@NonNull TerminalSession s) { if (s == current()) updateTitle(); if(drawerOpen)refreshDrawerSessions(); }
    @Override public void onSessionFinished(@NonNull TerminalSession s) { if(drawerOpen)refreshDrawerSessions(); }
    @Override public void onCopyTextToClipboard(@NonNull TerminalSession s, String text) { ClipboardManager cm = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("terminal", text)); }
    @Override public void onPasteTextFromClipboard(@Nullable TerminalSession s) { ClipboardManager cm = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE); if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) { CharSequence x = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext()); if (x != null && s != null) { byte[] b = x.toString().getBytes(StandardCharsets.UTF_8); s.write(b, 0, b.length); } } }
    @Override public void onBell(@NonNull TerminalSession s) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); }
    @Override public void onColorsChanged(@NonNull TerminalSession s) { if (terminalView != null) terminalView.invalidate(); }
    @Override public void onTerminalCursorStateChange(boolean state) { if (terminalView != null) terminalView.invalidate(); }
    @Override public void setTerminalShellPid(@NonNull TerminalSession s, int pid) {}
    @Override public Integer getTerminalCursorStyle() { return null; }

    // TerminalViewClient
    @Override public float onScale(float scale) { if (scale > 1.04f) changeFont(1); else if (scale < 0.96f) changeFont(-1); return 1f; }
    @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return backMapsEscape; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return terminalView != null && terminalView.hasFocus(); }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) { if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { ctrl = true; return true; } if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { fn = true; return true; } return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { ctrl = false; return true; } if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { fn = false; return true; } return false; }
    @Override public boolean onLongPress(MotionEvent e) { return false; }
    @Override public boolean readControlKey() { boolean v = ctrl; ctrl = false; return v; }
    @Override public boolean readAltKey() { boolean v = alt; alt = false; return v; }
    @Override public boolean readShiftKey() { boolean v = shift; shift = false; return v; }
    @Override public boolean readFnKey() { boolean v = fn; fn = false; return v; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) { return false; }
    @Override public void onEmulatorSet() {}

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "terminal", e); }

    private TextView drawerAction(String s, OnClickListener l) { TextView t = label(s, 12, TEXT, false); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(8), 0, dp(8), 0); t.setOnClickListener(l); return t; }
    private TextView action(String s, OnClickListener l) { TextView t = label(s, 17, TEXT, false); t.setGravity(Gravity.CENTER); t.setOnClickListener(l); return t; }
    private TextView label(String s, float sp, int color, boolean bold) { TextView t = new TextView(getContext()); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private int spPx(float sp) { return (int)(sp * getResources().getDisplayMetrics().scaledDensity + 0.5f); }
    private void toast(String s) { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }
}
