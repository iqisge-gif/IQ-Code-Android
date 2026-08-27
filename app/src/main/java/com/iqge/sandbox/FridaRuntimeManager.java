package com.iqge.sandbox;

import android.content.Context;

import com.termux.app.iqcode.termux.TermuxShellExecutor;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Installs the official arm64 Frida Gadget into IQ Code private storage on demand.
 *
 * The binary is intentionally not fetched from an arbitrary mirror. The URL and SHA-256 are
 * pinned to an official frida/frida GitHub release. The embedded Termux curl+xz toolchain does
 * the transport/decompression and Java verifies the downloaded compressed asset before use.
 */
public final class FridaRuntimeManager {
    public static final String VERSION = "17.17.0";
    public static final String GADGET_URL = "https://github.com/frida/frida/releases/download/17.17.0/frida-gadget-17.17.0-android-arm64.so.xz";
    public static final String GADGET_XZ_SHA256 = "942b66a229da11f1dda38c2c3c126bf23df76c0febc0925b6a5decc04687e871";
    private FridaRuntimeManager() {}

    public static File root(Context c) { return new File(c.getFilesDir(), "sandbox/frida"); }
    public static File masterGadget(Context c) { return new File(root(c), "frida-" + VERSION + "/libiqfrida.so"); }
    private static File marker(Context c) { return new File(root(c), "frida-" + VERSION + "/installed.json"); }

    public static boolean isInstalled(Context c) {
        File f = masterGadget(c);
        return f.isFile() && f.length() > 1024 * 1024 && isElf(f);
    }

    public static JSONObject status(Context c) throws Exception {
        File f = masterGadget(c);
        return new JSONObject()
            .put("version", VERSION)
            .put("installed", isInstalled(c))
            .put("path", f.getAbsolutePath())
            .put("size", f.isFile() ? f.length() : 0)
            .put("source", GADGET_URL)
            .put("asset_sha256", GADGET_XZ_SHA256);
    }


    private static File autoAttachFile(Context c) { return new File(root(c), "auto-attach.json"); }

    public static synchronized boolean isAutoAttachEnabled(Context c, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            File f = autoAttachFile(c); if (!f.isFile()) return false;
            byte[] data = new byte[(int) Math.min(f.length(), 1024 * 1024)];
            int n; try (FileInputStream in = new FileInputStream(f)) { n = in.read(data); }
            if (n <= 0) return false;
            JSONObject o = new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8));
            // v0.21.2 wrote package:true without a format marker and could auto-load Gadget
            // before Guest Application.onCreate on the next launch. Treat those stale entries
            // as disabled so upgrading to v0.21.3 recovers automatically.
            if (o.optInt("__format", 0) != 2) return false;
            return o.optBoolean(packageName.trim(), false);
        } catch (Throwable ignored) { return false; }
    }

    public static synchronized JSONObject setAutoAttach(Context c, String packageName, boolean enabled) throws Exception {
        String pkg = packageName == null ? "" : packageName.trim();
        if (pkg.isEmpty()) throw new IllegalArgumentException("package 不能为空");
        File f = autoAttachFile(c); JSONObject o = new JSONObject();
        if (f.isFile()) {
            try {
                byte[] data = new byte[(int) Math.min(f.length(), 1024 * 1024)]; int n;
                try (FileInputStream in = new FileInputStream(f)) { n = in.read(data); }
                if (n > 0) o = new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8));
                // Do not carry v0.21.2's implicit auto-attach state forward.
                if (o.optInt("__format", 0) != 2) o = new JSONObject();
            } catch (Throwable ignored) { o = new JSONObject(); }
        }
        o.put("__format", 2);
        if (enabled) o.put(pkg, true); else o.remove(pkg);
        File parent = f.getParentFile(); if (parent != null && !parent.isDirectory()) parent.mkdirs();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString(2).getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) { try (FileOutputStream out = new FileOutputStream(f)) { out.write(o.toString(2).getBytes(StandardCharsets.UTF_8)); } tmp.delete(); }
        return new JSONObject().put("package", pkg).put("auto_attach", enabled).put("frida_installed", isInstalled(c));
    }

    public static JSONObject install(Context c, TermuxShellExecutor shell, String cwd) throws Exception {
        File dest = masterGadget(c);
        if (isInstalled(c)) return status(c).put("changed", false);
        File dir = dest.getParentFile();
        File cache = new File(root(c), "cache");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("无法创建 Frida 目录: " + dir);
        if (!cache.isDirectory() && !cache.mkdirs() && !cache.isDirectory()) throw new IllegalStateException("无法创建 Frida cache: " + cache);
        File xz = new File(cache, "frida-gadget-" + VERSION + "-android-arm64.so.xz");
        String curl = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/curl";
        String xzbin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/xz";
        if (!new File(curl).isFile() || !new File(xzbin).isFile())
            throw new IllegalStateException("内置 Termux runtime 尚未就绪，Frida 安装需要 curl 与 xz");

        String dl = q(curl) + " -L --fail --retry 2 --connect-timeout 20 -o " + q(xz.getAbsolutePath() + ".tmp") + " " + q(GADGET_URL)
            + " && mv " + q(xz.getAbsolutePath() + ".tmp") + " " + q(xz.getAbsolutePath());
        TermuxShellExecutor.Result r = shell.execute(dl, normalizeCwd(cwd), 240_000, null);
        if (r.exitCode != 0) throw new IllegalStateException("Frida Gadget 下载失败: " + r.combined());
        String actual = sha256(xz);
        if (!GADGET_XZ_SHA256.equalsIgnoreCase(actual)) {
            xz.delete();
            throw new SecurityException("Frida Gadget SHA-256 不匹配，拒绝安装。expected=" + GADGET_XZ_SHA256 + " actual=" + actual);
        }

        File tmp = new File(dir, "libiqfrida.so.tmp");
        String unpack = q(xzbin) + " -dc " + q(xz.getAbsolutePath()) + " > " + q(tmp.getAbsolutePath())
            + " && chmod 700 " + q(tmp.getAbsolutePath()) + " && mv " + q(tmp.getAbsolutePath()) + " " + q(dest.getAbsolutePath());
        r = shell.execute(unpack, normalizeCwd(cwd), 120_000, null);
        if (r.exitCode != 0) throw new IllegalStateException("Frida Gadget 解压失败: " + r.combined());
        if (!isInstalled(c)) throw new IllegalStateException("Frida Gadget 解压后不是有效 ELF");
        writeMarker(c, actual);
        SandboxDebugLog.event("Frida Gadget " + VERSION + " 已安装: " + dest);
        return status(c).put("changed", true);
    }

    private static String normalizeCwd(String cwd) {
        if (cwd != null && !cwd.trim().isEmpty() && new File(cwd).isDirectory()) return cwd;
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }
    private static boolean isElf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && (h[0] & 0xff) == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        } catch (Throwable e) { return false; }
    }
    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder(); for (byte x : md.digest()) s.append(String.format(Locale.US, "%02x", x & 0xff)); return s.toString();
    }
    private static void writeMarker(Context c, String compressedSha) {
        try {
            File m = marker(c); File p = m.getParentFile(); if (!p.isDirectory()) p.mkdirs();
            String text = new JSONObject().put("version", VERSION).put("compressed_sha256", compressedSha).put("installed_at", System.currentTimeMillis()).toString(2);
            try (FileOutputStream out = new FileOutputStream(m)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        } catch (Throwable ignored) {}
    }
    private static String q(String s) { return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'"; }
}
