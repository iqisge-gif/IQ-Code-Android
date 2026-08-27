package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.termux.TermuxShellExecutor;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class BashTool implements IQTool {
    private static final String OFFICIAL_MAIN_REPOSITORY = "https://packages.termux.dev/apt/termux-main";
    private static final String OFFICIAL_ROOT_REPOSITORY = "https://packages.termux.dev/apt/termux-root";
    private static final String OFFICIAL_X11_REPOSITORY = "https://packages.termux.dev/apt/termux-x11";
    private static final String[] MAIN_MIRROR_FALLBACKS = new String[] {
        OFFICIAL_MAIN_REPOSITORY
    };
    private final TermuxShellExecutor executor;
    private final AndroidIntentBridge androidIntent;

    public BashTool(TermuxShellExecutor executor) { this(executor, null); }
    public BashTool(TermuxShellExecutor executor, AndroidIntentBridge androidIntent) { this.executor = executor; this.androidIntent = androidIntent; }

    @Override public String name() { return "Bash"; }

    @Override public String description() {
        return "Run bash in embedded Termux for project commands, builds, tests, git, and packages.";
    }

    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("command", ToolSchemas.string("Shell command to execute with bash -lc."));
            p.put("cwd", ToolSchemas.string("Optional working directory. Relative paths are resolved against the active project."));
            p.put("timeout_ms", ToolSchemas.integer("Timeout in milliseconds.", 1000));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "command");
    }

    @Override public PermissionKind permissionKind() { return PermissionKind.SHELL; }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        return execute(config, input, null);
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input, ProgressListener progress) throws Exception {
        String command = input.optString("command", "");
        if (androidIntent != null) {
            ToolExecutionResult bridged = androidIntent.tryExecuteAmStart(command);
            if (bridged != null) return bridged;
        }
        String cwdRaw = input.optString("cwd", config.projectDirectory);
        String cwd = PathPolicy.resolve(config.projectDirectory, cwdRaw).getAbsolutePath();
        int timeout = input.optInt("timeout_ms", TermuxShellExecutor.DEFAULT_TIMEOUT_MS);
        // Large Termux packages (OpenJDK, LLVM, Chromium, etc.) can spend a long time
        // downloading a single archive while apt emits no new line-oriented output. Do not let the
        // agent-side watchdog kill a healthy package-manager process merely because the mirror is slow.
        timeout = Math.max(1000, Math.min(timeout, 2 * 60 * 60 * 1000));
        String lc = command.toLowerCase(Locale.US);
        boolean packageCommand = isPackageCommand(lc);
        boolean longBuildCommand = isLongRunningBuildCommand(lc);
        if (packageCommand && timeout < 60 * 60 * 1000) timeout = 60 * 60 * 1000;
        // Coding agents often suggest 10-120 second timeouts even for first Gradle/Maven/NDK
        // builds, where dependency resolution alone can legitimately take several minutes on Android.
        // A too-small timeout looked like the Agent simply "stopped halfway" (exit 124). Keep a
        // generous floor for known build/test commands; the user can still /cancel or send a steering
        // correction, both of which terminate the process tree immediately.
        if (longBuildCommand && timeout < 15 * 60 * 1000) timeout = 15 * 60 * 1000;
        if (packageCommand) ensureAptNetworkPolicy();

        TermuxShellExecutor.OutputListener live = progress == null ? null : (chunk, stderr, elapsedMs) -> progress.onProgress(chunk, stderr, elapsedMs);
        TermuxShellExecutor.Result result = executor.execute(command, cwd, timeout, live);
        String output = result.combined();

        // pkg/apt normally retries the same URI. A mirror can answer the lightweight availability
        // probe yet repeatedly drop a large archive (for example a 100+ MB OpenJDK .deb). Keep
        // retries short via 99iqge-network, then restore the official Termux repository and rerun
        // the original package command. IQGE deliberately does not rotate to third-party mirrors.
        // This happens only after an actual non-zero network failure, never merely because apt
        // printed an ordinary Ign line while still running.
        if (packageCommand && result.exitCode != 0 && !result.timedOut && looksLikeMirrorFailure(output)) {
            StringBuilder failoverLog = new StringBuilder();
            for (int i = 0; i < MAIN_MIRROR_FALLBACKS.length; i++) {
                String mirror = MAIN_MIRROR_FALLBACKS[i];
                String msg = "\n[IQGE] 下载失败，正在固定 Termux 官方源 " + (i + 1) + "/" + MAIN_MIRROR_FALLBACKS.length + ": " + mirror + "\n";
                failoverLog.append(msg);
                if (progress != null) progress.onProgress(msg, true, 0);

                TermuxShellExecutor.Result switched = executor.execute(buildMirrorSwitchCommand(mirror), cwd, 3 * 60 * 1000, live);
                failoverLog.append(switched.combined()).append("\n");
                if (switched.exitCode != 0 || switched.timedOut) continue;

                if (progress != null) progress.onProgress("\n[IQGE] Termux 官方源可用，重新执行原安装命令…\n", false, 0);
                TermuxShellExecutor.Result retry = executor.execute(command, cwd, timeout, live);
                failoverLog.append("[retry @ ").append(mirror).append("]\n").append(retry.combined()).append("\n");
                result = retry;
                output = failoverLog.toString();
                if (retry.exitCode == 0 || retry.timedOut || !looksLikeMirrorFailure(retry.combined())) break;
            }
        }

        // An interrupted install or an upstream .deb that still contains /data/data/com.termux can
        // leave dpkg in a half-configured state. Repair the cached archives and retry once. Do not
        // retry ordinary network/404/GPG failures: those should be surfaced to IQ/user directly.
        if (packageCommand && result.exitCode != 0 && !result.timedOut && looksLikePrefixOrDpkgStateFailure(output)) {
            String repair = "set +e; " +
                "if [ -x \"$PREFIX/bin/iq-patch-deb\" ]; then find \"$PREFIX/var/cache/apt/archives\" -maxdepth 1 -type f -name '*.deb' -exec \"$PREFIX/bin/iq-patch-deb\" {} + 2>/dev/null; fi; " +
                "dpkg --configure -a; " +
                "apt-get -f install -y";
            if (progress != null) progress.onProgress("\n[IQGE package compatibility repair]\n", true, 0);
            TermuxShellExecutor.Result repaired = executor.execute(repair, cwd, 15 * 60 * 1000, live);
            if (progress != null) progress.onProgress("\n[retry]\n", false, 0);
            TermuxShellExecutor.Result retry = executor.execute(command, cwd, timeout, live);
            StringBuilder combined = new StringBuilder();
            combined.append("[IQGE package compatibility repair]\n")
                .append(repaired.combined()).append("\n\n[retry]\n")
                .append(retry.combined());
            result = retry;
            output = combined.toString();
        }

        if (result.exitCode != 0 && packageCommand) {
            output += "\n\n[Termux package-manager context]\nHOME=" + com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH +
                "\nPREFIX=" + com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH +
                "\nExit=" + (result.timedOut ? 124 : result.exitCode) +
                "\nHint: the full stderr is visible in this failed Bash row. Use TermuxDoctor or TermuxRepair if dpkg is left half-configured.";
        }
        return ToolExecutionResult.command(result.timedOut ? 124 : result.exitCode, output);
    }


    private static void ensureAptNetworkPolicy() throws Exception {
        File dir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/apt.conf.d");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            throw new IllegalStateException("Cannot create apt config directory: " + dir);
        File cfg = new File(dir, "99iqge-network");
        String body =
            "Acquire::Retries \"1\";\n" +
            "Acquire::http::Timeout \"35\";\n" +
            "Acquire::https::Timeout \"35\";\n" +
            "Acquire::http::Pipeline-Depth \"0\";\n" +
            "DPkg::Lock::Timeout \"5\";\n";
        try (FileOutputStream out = new FileOutputStream(cfg, false)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildMirrorSwitchCommand(String mirror) {
        String q = shellQuote(mirror);
        String root = shellQuote(OFFICIAL_ROOT_REPOSITORY);
        String x11 = shellQuote(OFFICIAL_X11_REPOSITORY);
        return "set -e; " +
            "aptroot=\"$PREFIX/etc/apt\"; listdir=\"$aptroot/sources.list.d\"; termuxdir=\"$PREFIX/etc/termux\"; " +
            "mkdir -p \"$listdir\" \"$aptroot/iqge-backup\" \"$termuxdir\"; " +
            "if [ -f \"$aptroot/sources.list\" ] && [ ! -f \"$aptroot/iqge-backup/sources.list\" ]; then cp -p \"$aptroot/sources.list\" \"$aptroot/iqge-backup/sources.list\"; fi; " +
            "for f in \"$aptroot/sources.list\" \"$listdir\"/*.list; do " +
            "[ -f \"$f\" ] || continue; [ \"$f\" = \"$listdir/iqge-main.list\" ] && continue; " +
            "sed -i '/^[[:space:]]*deb[[:space:]].*termux-main/s/^/# IQGE fixed official main: /' \"$f\"; done; " +
            "for f in \"$listdir\"/*.sources; do [ -f \"$f\" ] || continue; " +
            "if grep -q 'termux-main' \"$f\"; then " +
            "sed -i 's|^[[:space:]]*URIs:.*termux-main.*|URIs: " + OFFICIAL_MAIN_REPOSITORY + "|' \"$f\"; " +
            "if grep -q '^[[:space:]]*Enabled:' \"$f\"; then sed -i 's/^[[:space:]]*Enabled:.*/Enabled: no/' \"$f\"; else printf '\\nEnabled: no\\n' >> \"$f\"; fi; fi; done; " +
            "printf 'deb %s stable main\\n' " + q + " > \"$listdir/iqge-main.list\"; " +
            "rm -rf \"$termuxdir/chosen_mirrors\"; " +
            "printf 'MAIN=\"%s\"\\nROOT=\"%s\"\\nX11=\"%s\"\\nWEIGHT=1\\n' " + q + " " + root + " " + x11 + " > \"$termuxdir/chosen_mirrors\"; " +
            "rm -f \"$PREFIX/var/cache/apt/archives/partial/\"* 2>/dev/null || true; " +
            "apt-get update -o Acquire::Retries=1 -o Acquire::http::Timeout=25 -o Acquire::https::Timeout=25";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean looksLikeMirrorFailure(String output) {
        String s = output == null ? "" : output.toLowerCase(Locale.US);
        return s.contains("failed to fetch") ||
            s.contains("could not connect") ||
            s.contains("connection timed out") ||
            s.contains("connection timeout") ||
            s.contains("connection reset") ||
            s.contains("network is unreachable") ||
            s.contains("temporary failure resolving") ||
            s.contains("tls handshake") ||
            s.contains("certificate verification failed") ||
            s.contains("hash sum mismatch") ||
            s.contains("unexpected size") ||
            s.contains("undetermined error") ||
            s.contains("server returned") ||
            s.contains(" 404 ") || s.contains(" 403 ");
    }

    private static boolean isLongRunningBuildCommand(String lc) {
        String c = lc == null ? "" : lc;
        return c.contains("./gradlew") || c.contains(" gradle ") || c.startsWith("gradle ")
            || c.contains("mvn ") || c.startsWith("mvn") || c.contains("mvnw")
            || c.contains("cmake --build") || c.contains("ndk-build") || c.contains(" ninja") || c.startsWith("ninja")
            || c.contains(" cargo build") || c.startsWith("cargo build") || c.contains(" cargo test") || c.startsWith("cargo test")
            || c.contains(" npm run build") || c.startsWith("npm run build") || c.contains(" npm test") || c.startsWith("npm test")
            || c.contains(" bun run build") || c.startsWith("bun run build") || c.contains(" bun test") || c.startsWith("bun test")
            || c.equals("make") || c.startsWith("make ") || c.contains(" && make") || c.contains("; make");
    }

    private static boolean isPackageCommand(String lc) {
        return lc.contains("pkg ") || lc.contains("apt ") || lc.contains("apt-get ") || lc.contains("dpkg ") || lc.contains("dpkg-");
    }

    private static boolean looksLikePrefixOrDpkgStateFailure(String output) {
        String s = output == null ? "" : output.toLowerCase(Locale.US);
        return s.contains("/data/data/com.termux") ||
            s.contains("unable to stat './data/data/com.termux") ||
            (s.contains("permission denied") && s.contains("dpkg")) ||
            s.contains("dpkg returned an error code") ||
            s.contains("sub-process") && s.contains("dpkg") ||
            s.contains("dpkg was interrupted") ||
            s.contains("dependency problems - leaving unconfigured") ||
            s.contains("you must manually run 'dpkg --configure -a'");
    }
}
