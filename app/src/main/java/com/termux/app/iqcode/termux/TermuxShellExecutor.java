package com.termux.app.iqcode.termux;

import android.content.Context;

import com.iqge.sandbox.SandboxTermuxBridge;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Executes IQ Bash-tool commands directly in the embedded Termux Bionic userspace. No proot. */
public final class TermuxShellExecutor {
    public static final int DEFAULT_TIMEOUT_MS = 120_000;
    public static final int MAX_CAPTURE_CHARS = 2_000_000;
    private static final long CONTROL_POLL_MS = 40L;
    private static final long CONTROL_EXIT_GRACE_MS = 250L;
    private static final long PID_FILE_GRACE_MS = 300L;
    private static final long THREAD_JOIN_MS = 1500L;
    private static final ReentrantLock PACKAGE_TRANSACTION = new ReentrantLock();

    /** Live stdout/stderr delivery for long-running agent commands. */
    public interface OutputListener {
        /** chunk is empty for a heartbeat; stderr marks stderr stream chunks. */
        void onOutput(String chunk, boolean stderr, long elapsedMs);
    }

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;
        Result(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode; this.stdout = stdout; this.stderr = stderr; this.timedOut = timedOut;
        }
        public String combined() {
            StringBuilder out = new StringBuilder();
            if (!stdout.isEmpty()) out.append(stdout);
            if (!stderr.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length()-1) != '\n') out.append('\n');
                out.append("[stderr]\n").append(stderr);
            }
            if (timedOut) out.append("\n[command timed out]");
            if (out.length() == 0) out.append("(no output)");
            return out.toString();
        }
    }

    @SuppressWarnings("unused") private final Context context;
    public TermuxShellExecutor(Context context) { this.context = context.getApplicationContext(); }

    public Result execute(String command, String cwd, int timeoutMs) throws Exception {
        return execute(command, cwd, timeoutMs, null);
    }

    public Result execute(String command, String cwd, int timeoutMs, OutputListener listener) throws Exception {
        return executeInternal(command,cwd,timeoutMs,listener,false);
    }

    /** Executes through Magisk/KernelSU's su. The caller must gate this behind explicit user opt-in. */
    public Result executeAsRoot(String command,String cwd,int timeoutMs,OutputListener listener)throws Exception{
        return executeInternal(command,cwd,timeoutMs,listener,true);
    }

    public Result executeAsRoot(String command,String cwd,int timeoutMs)throws Exception{
        return executeAsRoot(command,cwd,timeoutMs,null);
    }

    private Result executeInternal(String command,String cwd,int timeoutMs,OutputListener listener,boolean asRoot)throws Exception{
        if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash").isFile())
            throw new IllegalStateException("Embedded Termux runtime is not installed yet");
        if (cwd == null || cwd.isEmpty()) cwd = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File requestedWorking = new File(cwd);
        if (!asRoot && !requestedWorking.isDirectory()) throw new IllegalArgumentException("Working directory does not exist: " + cwd);
        // Java cannot chdir into a root-only directory before su starts. Root performs the requested cd.
        File working = asRoot ? new File(TermuxConstants.TERMUX_HOME_DIR_PATH) : requestedWorking;

        String rawCommand = command == null ? "" : command;
        String commandLower = rawCommand.toLowerCase(java.util.Locale.US);
        boolean packageCommand = !asRoot && isPackageCommand(commandLower);
        boolean packageLockHeld = false;
        if (packageCommand) {
            packageLockHeld = PACKAGE_TRANSACTION.tryLock();
            if (!packageLockHeld) {
                return new Result(75, "", "[IQGE] 已有 IQ Code 包管理任务正在运行。不会再启动第二个 apt/pkg/dpkg，请等待当前任务完成或先停止它。\n", false);
            }
            // v0.19.7 and older could leave apt/dpkg orphaned when an Agent task was cancelled:
            // Java destroyed only the parent bash, so the child kept the dpkg lock. Reap only
            // orphaned same-UID package-manager processes (PPid=1); an active terminal-owned apt
            // still has its terminal shell as parent and is never killed here.
            cleanupOrphanedPackageManagers(listener);
        }

        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String executionId = android.os.Process.myPid() + "-" + System.nanoTime() + "-" + UUID.randomUUID().toString();
        File pidFile = new File(prefix + "/tmp/iqge-" + (asRoot ? "root" : "exec") + "-" + executionId + ".pid");
        File controlFile = new File(prefix + "/tmp/iqge-complete-" + executionId + ".status");
        String controlToken = UUID.randomUUID().toString().replace("-", "");
        final long startedAt = System.currentTimeMillis();
        Process process = null;
        Capture stdout = null, stderr = null;
        Thread outThread = null, errThread = null, waiter = null, controlThread = null;
        ControlWatcher controlWatcher = null;
        BashCompletionCoordinator completion = new BashCompletionCoordinator();
        boolean timedOut = false;
        boolean interrupted = false;
        boolean terminationRequested = false;
        boolean isolateProcessGroup = false;
        try {
            File tempDirectory = new File(prefix + "/tmp");
            if (!tempDirectory.isDirectory() && !tempDirectory.mkdirs() && !tempDirectory.isDirectory())
                throw new IllegalStateException("Cannot create Termux temporary directory: " + tempDirectory);
            createPrivateControlFile(pidFile);
            createPrivateControlFile(controlFile);

            String shellPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            String commandShell = shellQuote(shellPath) + " -lc " + shellQuote(rawCommand);
            String wrapped = "umask 077; printf '%s\\n' $$ > " + shellQuote(pidFile.getAbsolutePath()) + "; " +
                commandShell + "; iqge_exit=$?; printf '%s:%s\\n' " + shellQuote(controlToken) + " \"$iqge_exit\" > " +
                shellQuote(controlFile.getAbsolutePath()) + "; exit \"$iqge_exit\"";
            File setsid = new File(prefix + "/bin/setsid");
            isolateProcessGroup = setsid.isFile() && setsid.canExecute();
            ProcessBuilder pb;
            if(asRoot){
                String launcher = "exec " + (isolateProcessGroup ? shellQuote(setsid.getAbsolutePath()) + " --wait " : "") +
                    shellQuote(shellPath) + " -lc " + shellQuote(wrapped);
                String rootWrapped="umask 077; cd "+shellQuote(requestedWorking.getAbsolutePath())+" || { echo 'Root cwd does not exist or is inaccessible' >&2; exit 72; }; "+
                    "export HOME="+shellQuote(TermuxConstants.TERMUX_HOME_DIR_PATH)+" PREFIX="+shellQuote(prefix)+" TMPDIR="+shellQuote(prefix+"/tmp")+
                    " PATH="+shellQuote(prefix+"/bin:/system/bin:/system/xbin")+" LANG=en_US.UTF-8 TERM=xterm-256color COLORTERM=truecolor "+
                    "SHELL="+shellQuote(prefix+"/bin/bash")+" IQ_CODE_ANDROID=1 IQGE_TOOL_EXECUTOR=1 IQGE_ROOT_EXECUTOR=1; "+launcher;
                pb=new ProcessBuilder(findSuBinary(),"-c",rootWrapped);
            }else if(isolateProcessGroup){
                pb = new ProcessBuilder(setsid.getAbsolutePath(), "--wait", shellPath, "-lc", wrapped);
            }else{
                pb = new ProcessBuilder(shellPath, "-lc", wrapped);
            }
            pb.directory(working);
            Map<String,String> env = pb.environment();
            env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            env.put("PREFIX", prefix);
            env.put("TMPDIR", prefix + "/tmp");
            // Match current Termux Android 7+ shell semantics: PREFIX/bin only and no LD_LIBRARY_PATH.
            // Termux ELF binaries use DT_RUNPATH; forcing LD_LIBRARY_PATH can break apt/dpkg/java subprocesses.
            env.put("PATH", prefix + "/bin");
            env.remove("LD_LIBRARY_PATH");
            env.put("LANG", "en_US.UTF-8");
            env.put("TERM", "xterm-256color");
            env.put("COLORTERM", "truecolor");
            env.put("PWD", requestedWorking.getAbsolutePath());
            env.put("SHELL", prefix + "/bin/bash");
            env.put("IQ_CODE_ANDROID", "1");
            env.put("IQGE_SANDBOX_BRIDGE_DIR", SandboxTermuxBridge.bridgeDir(context));
            env.put("IQGE_APK_PATH", context.getApplicationInfo().sourceDir);
            SandboxTermuxBridge.ensureCliInstalled(context);
            env.put("IQGE_TOOL_EXECUTOR", "1");
            env.put("TERMUX_VERSION", "0.118.3");
            env.put("TERMUX_APP__PACKAGE_NAME", "com.iqge");
            env.put("TERMUX_APP__PACKAGE_MANAGER", "apt");
            env.put("TERMUX_APP__PACKAGE_VARIANT", "apt-android-7");
            env.put("TERMUX_APP__FILES_DIR", "/data/user/0/com.iqge/files");
            env.put("TERMUX_APP__DATA_DIR", "/data/user/0/com.iqge");
            env.put("TERMUX_APP__LEGACY_DATA_DIR", "/data/user/0/com.iqge");
            env.put("TERMUX_APP__PID", Integer.toString(android.os.Process.myPid()));
            env.put("TERMUX_APP__UID", Integer.toString(android.os.Process.myUid()));
            env.put("TERMUX_APP__TARGET_SDK", "28");
            env.put("TERMUX_MAIN_PACKAGE_FORMAT", "debian");
            env.put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
            env.put("TERMUX_APK_RELEASE", "IQGE");
            env.put("TERMUX__HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            env.put("TERMUX__PREFIX", prefix);
            env.put("TERMUX__ROOTFS_DIR", "/data/user/0/com.iqge/files");
            env.put("TERMUX__ROOTFS", "/data/user/0/com.iqge/files");
            // Prefer deterministic plain logs from build tools. Commands remain ordinary Bash and can override these.
            env.put("CI", "true");
            if (packageCommand) {
                // Agent package installs must never stop on an invisible debconf prompt. The visible
                // interactive Terminal intentionally keeps normal interactive behavior.
                env.put("DEBIAN_FRONTEND", "noninteractive");
                env.put("APT_LISTCHANGES_FRONTEND", "none");
            }

            process = pb.start();
            final Process child = process;
            stdout = new Capture(child.getInputStream(), false, listener, startedAt);
            stderr = new Capture(child.getErrorStream(), true, listener, startedAt);
            outThread = new Thread(stdout, "iq-bash-stdout");
            errThread = new Thread(stderr, "iq-bash-stderr");
            outThread.start(); errThread.start();

            waiter = new Thread(() -> {
                try { completion.publishProcessExit(child.waitFor()); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "iq-bash-waiter");
            controlWatcher = new ControlWatcher(controlFile, controlToken, completion);
            controlThread = new Thread(controlWatcher, "iq-bash-control");
            waiter.start(); controlThread.start();

            boolean finished = false;
            long deadline = startedAt + Math.max(1, timeoutMs);
            try {
                while (!finished) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    finished = completion.await(Math.min(500L, remaining));
                    if (!finished && listener != null) {
                        try { listener.onOutput("", false, System.currentTimeMillis() - startedAt); } catch (Throwable ignored) { }
                    }
                }
            } catch (InterruptedException e) {
                interrupted = true;
                terminationRequested = true;
                terminateExecution(asRoot,isolateProcessGroup,readPidWithGrace(pidFile), child, listener, "任务已取消，正在停止 Bash 子进程…");
                throw e;
            }
            timedOut = !finished;
            if (timedOut) {
                terminationRequested = true;
                terminateExecution(asRoot,isolateProcessGroup,readPidWithGrace(pidFile), child, listener, "命令超时，正在停止 Bash 子进程…");
                completion.awaitProcessExit(THREAD_JOIN_MS);
            } else if (completion.source() == BashCompletionCoordinator.Source.CONTROL &&
                    !completion.awaitProcessExit(CONTROL_EXIT_GRACE_MS)) {
                terminationRequested = true;
                terminateExecution(asRoot,isolateProcessGroup,readPidWithGrace(pidFile), child, listener, "命令已完成，正在清理遗留 Bash 进程…");
                completion.awaitProcessExit(THREAD_JOIN_MS);
            }

            if (stdout != null) stdout.close();
            if (stderr != null) stderr.close();
            closeProcessStreams(child);
            if (controlWatcher != null) controlWatcher.stop();
            if (controlThread != null) controlThread.interrupt();
            joinThread(outThread, THREAD_JOIN_MS);
            joinThread(errThread, THREAD_JOIN_MS);
            joinThread(controlThread, CONTROL_EXIT_GRACE_MS);
            joinThread(waiter, THREAD_JOIN_MS);
            if (waiter != null && waiter.isAlive()) {
                waiter.interrupt();
                joinThread(waiter, CONTROL_EXIT_GRACE_MS);
            }
            if (listener != null) {
                try { listener.onOutput("", false, System.currentTimeMillis() - startedAt); } catch (Throwable ignored) { }
            }
            if (!timedOut && !completion.hasExitCode())
                throw new IllegalStateException("Bash ended without a trusted exit status");
            int resultExit = timedOut ? 124 : completion.exitCode();
            return new Result(resultExit, stdout == null ? "" : stdout.value(), stderr == null ? "" : stderr.value(), timedOut);
        } finally {
            // Any exception/cancellation path must not leave apt/dpkg alive behind the Bash parent.
            if (process != null && !completion.processExited() && !terminationRequested) {
                terminateExecution(asRoot,isolateProcessGroup,readPidWithGrace(pidFile), process, listener, interrupted ? null : "正在清理 Bash 子进程…");
            }
            closeProcessStreams(process);
            if (stdout != null) stdout.close();
            if (stderr != null) stderr.close();
            if (controlWatcher != null) controlWatcher.stop();
            if (controlThread != null) controlThread.interrupt();
            joinThread(outThread, CONTROL_EXIT_GRACE_MS);
            joinThread(errThread, CONTROL_EXIT_GRACE_MS);
            joinThread(controlThread, CONTROL_EXIT_GRACE_MS);
            joinThread(waiter, CONTROL_EXIT_GRACE_MS);
            try { pidFile.delete(); } catch (Throwable ignored) { }
            try { controlFile.delete(); } catch (Throwable ignored) { }
            if (packageLockHeld) PACKAGE_TRANSACTION.unlock();
        }
    }

    private static boolean isPackageCommand(String lc) {
        return lc.contains("pkg ") || lc.startsWith("pkg") || lc.contains("apt ") || lc.startsWith("apt ") ||
            lc.contains("apt-get ") || lc.startsWith("apt-get") || lc.contains("dpkg ") || lc.startsWith("dpkg");
    }

    private static String findSuBinary(){
        String[] candidates={"/system/bin/su","/system/xbin/su","/sbin/su","/su/bin/su",TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH+"/su"};
        for(String candidate:candidates){File f=new File(candidate);if(f.isFile()&&f.canExecute())return candidate;}
        throw new IllegalStateException("未找到 Magisk/KernelSU 的 su；设备可能没有 Root，或尚未授予 IQ Code Root 权限");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static int readPidWithGrace(File f) {
        long deadline = System.currentTimeMillis() + PID_FILE_GRACE_MS;
        int pid;
        do {
            pid = readPid(f);
            if (pid > 1) return pid;
            if (System.currentTimeMillis() >= deadline) return -1;
            try { Thread.sleep(20L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
        } while (true);
    }

    private static int readPid(File f) {
        if (f == null || !f.isFile()) return -1;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Throwable ignored) { return -1; }
    }

    private static void createPrivateControlFile(File file) throws Exception {
        if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot replace control file: " + file);
        try (FileOutputStream ignored = new FileOutputStream(file)) { }
        if (!file.setReadable(true, true) || !file.setWritable(true, true))
            throw new IllegalStateException("Cannot protect control file: " + file);
        file.setReadable(false, false); file.setWritable(false, false); file.setExecutable(false, false);
        if (!file.setReadable(true, true) || !file.setWritable(true, true))
            throw new IllegalStateException("Cannot protect control file: " + file);
    }

    private static void closeProcessStreams(Process process) {
        if (process == null) return;
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Throwable ignored) { }
    }

    private static void joinThread(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(timeoutMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class ControlWatcher implements Runnable {
        private final File controlFile;
        private final String token;
        private final BashCompletionCoordinator completion;
        private volatile boolean stopped;

        ControlWatcher(File controlFile, String token, BashCompletionCoordinator completion) {
            this.controlFile = controlFile;
            this.token = token;
            this.completion = completion;
        }

        void stop() { stopped = true; }

        @Override public void run() {
            while (!stopped && !completion.hasExitCode()) {
                if (controlFile.length() > 0L) {
                    String line = readControlLine(controlFile);
                    Integer code = BashCompletionCoordinator.parseControlLine(line, token);
                    if (code != null) {
                        completion.publishControl(code.intValue());
                        return;
                    }
                }
                try { Thread.sleep(CONTROL_POLL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private static String readControlLine(File controlFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(controlFile), StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (Throwable ignored) { return null; }
    }

    private static final class ProcInfo {
        int pid, ppid, uid; String name = "";
    }

    private static List<ProcInfo> listOwnProcesses() {
        List<ProcInfo> out = new ArrayList<>();
        File proc = new File("/proc"); File[] xs = proc.listFiles(); if (xs == null) return out;
        int myUid = android.os.Process.myUid();
        for (File x : xs) {
            String n = x.getName(); if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            ProcInfo pi = new ProcInfo();
            try { pi.pid = Integer.parseInt(n); } catch (Throwable ignored) { continue; }
            try (BufferedReader r = new BufferedReader(new FileReader(new File(x, "status")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("Name:")) pi.name = line.substring(5).trim();
                    else if (line.startsWith("PPid:")) pi.ppid = Integer.parseInt(line.substring(5).trim());
                    else if (line.startsWith("Uid:")) { String[] a=line.substring(4).trim().split("\\s+"); pi.uid=Integer.parseInt(a[0]); }
                }
            } catch (Throwable ignored) { continue; }
            if (pi.uid == myUid) out.add(pi);
        }
        return out;
    }

    private static boolean isPackageProcessName(String name) {
        if (name == null) return false; String n=name.toLowerCase(java.util.Locale.US);
        return n.equals("apt") || n.equals("apt-get") || n.equals("dpkg") || n.equals("dpkg-deb") || n.equals("pkg");
    }

    public static void cleanupOrphanedPackageManagers() { cleanupOrphanedPackageManagers(null); }

    private static void cleanupOrphanedPackageManagers(OutputListener listener) {
        for (ProcInfo p : listOwnProcesses()) {
            if (p.ppid != 1 || !isPackageProcessName(p.name)) continue;
            if (p.pid == android.os.Process.myPid()) continue;
            if (listener != null) {
                try { listener.onOutput("\n[IQGE] 检测到上次取消后遗留的 " + p.name + " (PID " + p.pid + ")，正在释放 dpkg 锁…\n", true, 0); } catch (Throwable ignored) { }
            }
            try { android.system.Os.kill(p.pid, android.system.OsConstants.SIGTERM); } catch (Throwable ignored) { }
            try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (new File("/proc/" + p.pid).exists()) try { android.system.Os.kill(p.pid, android.system.OsConstants.SIGKILL); } catch (Throwable ignored) { }
        }
    }

    private static void terminateProcessTree(int rootPid, Process fallback, OutputListener listener, String note) {
        if (note != null && listener != null) try { listener.onOutput("\n[IQGE] " + note + "\n", true, 0); } catch (Throwable ignored) { }
        if (rootPid <= 1) { try { fallback.destroy(); } catch (Throwable ignored) { } return; }
        List<ProcInfo> all = listOwnProcesses();
        Map<Integer,List<Integer>> children = new HashMap<>();
        for (ProcInfo p : all) children.computeIfAbsent(p.ppid, k -> new ArrayList<>()).add(p.pid);
        List<Integer> order = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        collectDescendants(rootPid, children, seen, order); order.add(rootPid);
        for (int pid : order) { if (pid == android.os.Process.myPid()) continue; try { android.system.Os.kill(pid, android.system.OsConstants.SIGTERM); } catch (Throwable ignored) { } }
        try { Thread.sleep(180); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (int pid : order) { if (pid == android.os.Process.myPid()) continue; if (new File("/proc/"+pid).exists()) try { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL); } catch (Throwable ignored) { } }
        try { fallback.destroy(); } catch (Throwable ignored) { }
    }

    private static void terminateExecution(boolean root,boolean processGroup,int pid,Process fallback,OutputListener listener,String note){
        if(note!=null&&listener!=null)try{listener.onOutput("\n[IQGE] "+note+"\n",true,0);}catch(Throwable ignored){}
        if(processGroup&&pid>1){
            if(root)terminateRootProcessGroup(pid);else terminateProcessGroup(pid);
        }
        if(!root){terminateProcessTree(pid,fallback,null,null);return;}
        if(pid>1)try{
            String kill="killtree(){ for child in $(cat /proc/$1/task/$1/children 2>/dev/null); do killtree $child; done; kill -TERM $1 2>/dev/null; }; killtree "+pid+"; sleep 0.2; kill -KILL "+pid+" 2>/dev/null || true";
            Process k=new ProcessBuilder(findSuBinary(),"-c",kill).start();Thread waiter=new Thread(()->{try{k.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}},"iq-root-kill-waiter");waiter.start();waiter.join(2000);k.destroy();
        }catch(Throwable ignored){}
        try{fallback.destroy();}catch(Throwable ignored){}
    }

    private static void terminateProcessGroup(int pid) {
        try { android.system.Os.kill(-pid, android.system.OsConstants.SIGTERM); } catch (Throwable ignored) { }
        try { Thread.sleep(180); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL); } catch (Throwable ignored) { }
    }

    private static void terminateRootProcessGroup(int pid) {
        try {
            String kill="kill -TERM -"+pid+" 2>/dev/null || true; sleep 0.2; kill -KILL -"+pid+" 2>/dev/null || true";
            Process command=new ProcessBuilder(findSuBinary(),"-c",kill).start();
            Thread waiter=new Thread(()->{try{command.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}},"iq-root-group-kill-waiter");
            waiter.start(); waiter.join(2000); command.destroy();
        } catch (Throwable ignored) { }
    }

    private static void collectDescendants(int pid, Map<Integer,List<Integer>> children, Set<Integer> seen, List<Integer> out) {
        if (!seen.add(pid)) return; List<Integer> cs=children.get(pid); if (cs==null) return;
        for (int c : cs) { collectDescendants(c, children, seen, out); out.add(c); }
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final boolean stderr;
        private final OutputListener listener;
        private final long startedAt;
        private final StringBuilder output = new StringBuilder();
        private volatile boolean closed;
        private boolean truncated;
        Capture(InputStream input, boolean stderr, OutputListener listener, long startedAt) {
            this.input = input; this.stderr = stderr; this.listener = listener; this.startedAt = startedAt;
        }
        @Override public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096]; int read;
                while ((read = reader.read(buffer)) >= 0) {
                    String chunk = new String(buffer, 0, read);
                    synchronized (output) {
                        int remaining = MAX_CAPTURE_CHARS - output.length();
                        if (remaining > 0) output.append(buffer, 0, Math.min(read, remaining));
                        if (read > remaining) truncated = true;
                    }
                    if (listener != null && !chunk.isEmpty()) {
                        try { listener.onOutput(chunk, stderr, System.currentTimeMillis() - startedAt); } catch (Throwable ignored) { }
                    }
                }
            } catch (Exception e) {
                if (closed) return;
                String msg = "\n[capture error: " + e.getMessage() + "]";
                synchronized (output) { output.append(msg); }
                if (listener != null) {
                    try { listener.onOutput(msg, stderr, System.currentTimeMillis() - startedAt); } catch (Throwable ignored) { }
                }
            }
        }
        void close() { closed = true; closeQuietly(input); }

        String value() {
            synchronized (output) {
                String value = output.toString();
                if (truncated) value += "\n…output truncated…";
                return value;
            }
        }
    }
}
