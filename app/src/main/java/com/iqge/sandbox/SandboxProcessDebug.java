package com.iqge.sandbox;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import top.niunaijun.blackbox.app.BActivityThread;

/**
 * In-process debugger for IQ Sandbox guest processes.
 *
 * This class intentionally operates only on /proc/self and code loaded into the current
 * virtual process. The main Agent selects a concrete sandbox PID through SandboxAgentBridge,
 * then that process performs the operation on itself. This keeps raw memory/debug operations
 * scoped to IQ Sandbox instead of turning IQ Code into a system-wide injector.
 */
public final class SandboxProcessDebug {
    private static final int MAX_MAP_CHARS = 512_000;
    private static final int MAX_MEMORY_BYTES = 65_536;
    private SandboxProcessDebug() {}

    public static JSONObject dispatch(Context context, String action, JSONObject p) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true)
            .put("pid", Process.myPid())
            .put("uid", Process.myUid())
            .put("package", safe(BActivityThread.getAppPackageName()))
            .put("process", safe(BActivityThread.getAppProcessName()));
        switch (action == null ? "" : action) {
            case "proc_info":
                out.put("info", processInfo(context));
                break;
            case "proc_maps":
                out.put("maps", readMaps(p.optString("filter", ""), Math.max(1, Math.min(6000, p.optInt("max_lines", 1600)))));
                break;
            case "proc_modules":
                out.put("modules", modules(p.optString("filter", ""), Math.max(1, Math.min(1200, p.optInt("max_modules", 400)))));
                break;
            case "proc_threads":
                out.put("threads", threads());
                break;
            case "proc_memory_read":
                out.put("memory", memoryRead(context, p.optString("address", ""), p.optInt("size", 64), p.optString("format", "hex")));
                break;
            case "proc_memory_write":
                out.put("memory", memoryWrite(context, p.optString("address", ""), p.optString("data", ""), p.optString("format", "hex")));
                break;
            case "proc_load_library":
                out.put("injection", loadLibrary(context, p.optString("path", "")));
                break;
            case "proc_thread_dump":
                out.put("thread_dump", threadDump());
                break;
            case "proc_gc":
                Runtime.getRuntime().gc();
                out.put("message", "GC 已请求");
                break;
            case "proc_frida_status":
                out.put("frida", SandboxFridaBridge.status(context));
                break;
            case "proc_frida_load":
                out.put("frida", SandboxFridaBridge.load(context));
                break;
            case "proc_frida_command":
                out.put("frida", SandboxFridaBridge.command(context, p.optString("op", ""), p.optJSONObject("frida_payload"), Math.max(1000, Math.min(120000, p.optInt("timeout_ms", 10000)))));
                break;
            case "proc_frida_events":
                out.put("events", SandboxFridaBridge.events(Math.max(1024, Math.min(1000000, p.optInt("max_chars", 200000)))));
                break;
            default:
                out.put("ok", false).put("error", "未知进程调试动作: " + action);
        }
        return out;
    }

    private static JSONObject processInfo(Context context) throws Exception {
        JSONObject o = new JSONObject();
        o.put("pid", Process.myPid())
            .put("uid", Process.myUid())
            .put("package", safe(BActivityThread.getAppPackageName()))
            .put("process", safe(BActivityThread.getAppProcessName()))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI)
            .put("cmdline", readSmall("/proc/self/cmdline", 8192).replace('\0', ' ').trim())
            .put("status", readSmall("/proc/self/status", 32_768));
        try { o.put("native_library_dir", context.getApplicationInfo().nativeLibraryDir); } catch (Throwable ignored) {}
        return o;
    }

    private static String readMaps(String filter, int maxLines) throws Exception {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line; int lines = 0;
            while ((line = br.readLine()) != null && lines < maxLines && out.length() < MAX_MAP_CHARS) {
                if (!needle.isEmpty() && !line.toLowerCase(Locale.US).contains(needle)) continue;
                out.append(line).append('\n'); lines++;
            }
        }
        return out.toString();
    }

    private static JSONArray modules(String filter, int maxModules) throws Exception {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.US);
        Map<String, Module> grouped = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                MapLine m = parseMapLine(line); if (m == null || m.path.isEmpty() || m.path.startsWith("[")) continue;
                if (!needle.isEmpty() && !m.path.toLowerCase(Locale.US).contains(needle)) continue;
                Module mod = grouped.get(m.path);
                if (mod == null) { mod = new Module(m.path); grouped.put(m.path, mod); }
                mod.observe(m);
            }
        }
        List<Module> list = new ArrayList<>(grouped.values());
        Collections.sort(list, Comparator.comparingLong(x -> x.base));
        JSONArray out = new JSONArray();
        for (Module m : list) {
            if (out.length() >= maxModules) break;
            out.put(new JSONObject()
                .put("path", m.path)
                .put("base", hex(m.base))
                .put("start", hex(m.start))
                .put("end", hex(m.end))
                .put("size", m.end - m.start)
                .put("perms", m.perms.toString()));
        }
        return out;
    }

    private static JSONArray threads() throws Exception {
        JSONArray out = new JSONArray();
        File task = new File("/proc/self/task"); File[] dirs = task.listFiles(); if (dirs == null) return out;
        List<File> list = new ArrayList<>(); Collections.addAll(list, dirs);
        Collections.sort(list, Comparator.comparingInt(f -> parseInt(f.getName(), Integer.MAX_VALUE)));
        for (File d : list) {
            if (!d.isDirectory()) continue;
            JSONObject t = new JSONObject().put("tid", parseInt(d.getName(), -1));
            try { t.put("name", readSmall(new File(d, "comm").getAbsolutePath(), 2048).trim()); } catch (Throwable ignored) {}
            try {
                String status = readSmall(new File(d, "status").getAbsolutePath(), 8192);
                for (String line : status.split("\\n")) if (line.startsWith("State:")) { t.put("state", line.substring(6).trim()); break; }
            } catch (Throwable ignored) {}
            out.put(t);
        }
        return out;
    }

    /**
     * v0.21.3: live memory I/O is routed through Frida inside the selected Guest process.
     * We intentionally do not touch /proc/self/mem and do not use Unsafe.copyMemory here:
     * both paths can be blocked on Android 15, and Unsafe can hard-crash the process on a
     * stale/racing address instead of returning a Java exception.
     */
    private static JSONObject memoryRead(Context context, String addressText, int size, String format) throws Exception {
        long address = parseAddress(addressText);
        int count = Math.max(1, Math.min(MAX_MEMORY_BYTES, size));
        MapLine mapping = findMapping(address, count, false);
        Context host = IQSandboxEngine.hostContext(); if (host == null) host = context;
        if (!FridaRuntimeManager.isInstalled(host))
            throw new IllegalStateException("安全内存通道未就绪：已禁用 /proc/self/mem 与 Unsafe 直读以避免 EACCES/闪退。先执行 Debug action=frida_install，再重试 memory_read/frida_read。");
        JSONObject payload = new JSONObject().put("address", hex(address)).put("size", count).put("volatile", true);
        JSONObject response = SandboxFridaBridge.command(context, "read", payload, 12000);
        JSONObject result = response.optJSONObject("result");
        if (result == null) throw new IllegalStateException("Frida read 未返回 result: " + response);
        String hexData = result.optString("hex", "");
        String f = normalizeFormat(format);
        String data = hexData;
        if ("base64".equals(f)) data = Base64.encodeToString(fromHex(hexData), Base64.NO_WRAP);
        return new JSONObject()
            .put("address", result.optString("address", hex(address)))
            .put("size", result.optInt("size", count))
            .put("mapping", mapping.raw)
            .put("backend", "frida_in_process")
            .put("volatile", true)
            .put("format", f)
            .put("data", data);
    }

    private static JSONObject memoryWrite(Context context, String addressText, String encoded, String format) throws Exception {
        long address = parseAddress(addressText);
        String f = normalizeFormat(format);
        byte[] data = decodeData(encoded, f);
        if (data.length == 0) throw new IllegalArgumentException("写入数据不能为空");
        if (data.length > MAX_MEMORY_BYTES) throw new IllegalArgumentException("单次最多写入 " + MAX_MEMORY_BYTES + " 字节");
        MapLine mapping = findMapping(address, data.length, true);
        Context host = IQSandboxEngine.hostContext(); if (host == null) host = context;
        if (!FridaRuntimeManager.isInstalled(host))
            throw new IllegalStateException("安全内存通道未就绪：已禁用 /proc/self/mem 与 Unsafe 直写以避免 EACCES/闪退。先执行 Debug action=frida_install，再重试 memory_write/frida_write。");
        JSONObject payload = new JSONObject().put("address", hex(address)).put("data", toHex(data)).put("volatile", true);
        JSONObject response = SandboxFridaBridge.command(context, "write", payload, 12000);
        JSONObject result = response.optJSONObject("result");
        if (result == null) throw new IllegalStateException("Frida write 未返回 result: " + response);
        return new JSONObject()
            .put("address", result.optString("address", hex(address)))
            .put("size", result.optInt("written", data.length))
            .put("mapping", mapping.raw)
            .put("backend", "frida_in_process")
            .put("volatile", true)
            .put("written", true);
    }

    private static JSONObject loadLibrary(Context context, String pathText) throws Exception {
        if (pathText == null || pathText.trim().isEmpty()) throw new IllegalArgumentException("load_library 需要绝对 .so 路径");
        File f = new File(pathText.trim()).getCanonicalFile();
        if (!f.isAbsolute() || !f.isFile() || !f.getName().endsWith(".so")) throw new IllegalArgumentException("不是有效 .so 文件: " + f);
        String canonical = f.getAbsolutePath();
        String appData = context.getApplicationInfo().dataDir;
        String files = context.getFilesDir().getAbsolutePath();
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        // Sandbox injection is deliberately limited to files owned by IQ Code / the virtual runtime.
        if (!(canonical.startsWith(appData + File.separator) || canonical.startsWith(files + File.separator) || canonical.startsWith(nativeDir + File.separator)
            || canonical.startsWith("/data/user/0/com.iqge/") || canonical.startsWith("/data/data/com.iqge/"))) {
            throw new SecurityException("沙箱调试注入只允许加载 IQ Code 私有目录中的 .so；先把调试库复制到 IQCode files/home 或 sandbox/debug-libs");
        }
        System.load(canonical);
        SandboxDebugLog.event("Guest 受控加载调试库: " + canonical + " pid=" + Process.myPid());
        return new JSONObject().put("loaded", true).put("path", canonical).put("pid", Process.myPid());
    }

    private static JSONArray threadDump() throws Exception {
        JSONArray out = new JSONArray();
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        List<Map.Entry<Thread, StackTraceElement[]>> entries = new ArrayList<>(all.entrySet());
        Collections.sort(entries, Comparator.comparing(e -> e.getKey().getName()));
        for (Map.Entry<Thread, StackTraceElement[]> e : entries) {
            Thread t = e.getKey(); JSONObject o = new JSONObject().put("id", t.getId()).put("name", t.getName()).put("state", String.valueOf(t.getState()));
            JSONArray stack = new JSONArray(); int n = 0;
            for (StackTraceElement ste : e.getValue()) { if (n++ >= 96) break; stack.put(ste.toString()); }
            o.put("stack", stack); out.put(o);
        }
        return out;
    }

    private static MapLine findMapping(long address, int length, boolean write) throws Exception {
        long end = address + Math.max(1, length); if (end < address) throw new IllegalArgumentException("地址范围溢出");
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                MapLine m = parseMapLine(line); if (m == null) continue;
                if (address >= m.start && end <= m.end) {
                    if (m.perms.length() < 2 || m.perms.charAt(0) != 'r') throw new SecurityException("目标内存页不可读: " + line);
                    if (write && m.perms.charAt(1) != 'w') throw new SecurityException("目标内存页不可写: " + line);
                    return m;
                }
            }
        }
        throw new IllegalArgumentException("地址不在当前 Guest 的有效映射中: " + hex(address));
    }

    private static MapLine parseMapLine(String line) {
        try {
            String[] parts = line.trim().split("\\s+", 6); if (parts.length < 5) return null;
            String[] range = parts[0].split("-", 2); if (range.length != 2) return null;
            long start = Long.parseUnsignedLong(range[0], 16), end = Long.parseUnsignedLong(range[1], 16);
            long offset = Long.parseUnsignedLong(parts[2], 16); String path = parts.length >= 6 ? parts[5].trim() : "";
            return new MapLine(start, end, offset, parts[1], path, line);
        } catch (Throwable ignored) { return null; }
    }

    private static byte[] decodeData(String encoded, String format) {
        String s = encoded == null ? "" : encoded.trim();
        if ("base64".equals(format)) return Base64.decode(s, Base64.DEFAULT);
        s = s.replace("0x", "").replaceAll("[^0-9A-Fa-f]", "");
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("hex 数据长度必须为偶数");
        byte[] out = new byte[s.length() / 2]; for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16); return out;
    }
    private static String normalizeFormat(String f) { return "base64".equalsIgnoreCase(f) ? "base64" : "hex"; }
    private static String toHex(byte[] data) { StringBuilder b = new StringBuilder(data.length * 2); for (byte x : data) b.append(String.format(Locale.US, "%02x", x & 0xff)); return b.toString(); }
    private static byte[] fromHex(String s) { String v=s==null?"":s.replaceAll("[^0-9A-Fa-f]",""); if((v.length()&1)!=0)throw new IllegalArgumentException("hex 长度必须为偶数"); byte[] out=new byte[v.length()/2]; for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(v.substring(i*2,i*2+2),16); return out; }
    private static long parseAddress(String text) { String s = text == null ? "" : text.trim().toLowerCase(Locale.US); if (s.startsWith("0x")) s = s.substring(2); if (s.isEmpty()) throw new IllegalArgumentException("需要 address，例如 0x7a12340000"); return Long.parseUnsignedLong(s, 16); }
    private static String hex(long v) { return "0x" + Long.toUnsignedString(v, 16); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (Throwable e) { return fallback; } }
    private static String readSmall(String path, int max) throws Exception { try (FileInputStream in = new FileInputStream(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1 && out.size() < max) out.write(b, 0, Math.min(n, max - out.size())); return new String(out.toByteArray(), StandardCharsets.UTF_8); } }


    private static final class MapLine {
        final long start, end, offset; final String perms, path, raw;
        MapLine(long start,long end,long offset,String perms,String path,String raw){this.start=start;this.end=end;this.offset=offset;this.perms=perms;this.path=path;this.raw=raw;}
    }
    private static final class Module {
        final String path; long base=Long.MAX_VALUE,start=Long.MAX_VALUE,end=0; final StringBuilder perms=new StringBuilder();
        Module(String path){this.path=path;}
        void observe(MapLine m){ long candidate=m.start-m.offset; if(Long.compareUnsigned(candidate,base)<0)base=candidate;if(Long.compareUnsigned(m.start,start)<0)start=m.start;if(Long.compareUnsigned(m.end,end)>0)end=m.end;if(perms.indexOf(m.perms)<0){if(perms.length()>0)perms.append(',');perms.append(m.perms);} }
    }
}
