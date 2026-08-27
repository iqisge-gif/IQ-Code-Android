package com.termux.app.iqcode.tools;
import com.termux.app.iqcode.model.SessionConfig;import com.termux.app.iqcode.model.ToolExecutionResult;import com.termux.app.iqcode.termux.TermuxShellExecutor;import org.json.JSONObject;
/** Read-only environment diagnostic that helps the agent recover from missing package-manager/toolchain state. */
public final class TermuxDoctorTool implements IQTool{
 private final TermuxShellExecutor shell; public TermuxDoctorTool(TermuxShellExecutor s){shell=s;}
 public String name(){return "TermuxDoctor";} public String description(){return "Inspect the embedded Termux runtime, PATH/PREFIX, package manager, dpkg state, architecture, disk space, and common compilers without modifying the environment.";}
 public JSONObject inputSchema(){return ToolSchemas.object(new JSONObject());} public PermissionKind permissionKind(){return PermissionKind.READ;}
 public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{String cmd="printf 'HOME=%s\\nPREFIX=%s\\nPATH=%s\\nTMPDIR=%s\\n' \"$HOME\" \"$PREFIX\" \"$PATH\" \"$TMPDIR\"; uname -a; getprop ro.product.cpu.abi 2>/dev/null || true; for x in bash pkg apt dpkg git python node bun java javac clang make cmake; do printf '%-8s ' \"$x\"; command -v \"$x\" || echo missing; done; printf '\\n-- dpkg audit --\\n'; dpkg --audit 2>&1 || true; printf '\\n-- apt sources --\\n'; grep -Rhv '^[[:space:]]*#' \"$PREFIX/etc/apt/sources.list\" \"$PREFIX/etc/apt/sources.list.d\"/*.list 2>/dev/null || true; printf '\\n-- disk --\\n'; df -h \"$HOME\" 2>/dev/null || true";TermuxShellExecutor.Result r=shell.execute(cmd,c.projectDirectory,30000);return ToolExecutionResult.command(r.exitCode,r.combined());}
}
