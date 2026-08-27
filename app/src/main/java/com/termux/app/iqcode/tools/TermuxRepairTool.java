package com.termux.app.iqcode.tools;
import com.termux.app.iqcode.model.SessionConfig;import com.termux.app.iqcode.model.ToolExecutionResult;import com.termux.app.iqcode.termux.TermuxShellExecutor;import org.json.JSONObject;
/** Explicitly permission-gated package-manager repair for interrupted/partially configured Termux installs. */
public final class TermuxRepairTool implements IQTool{
 private final TermuxShellExecutor shell; public TermuxRepairTool(TermuxShellExecutor s){shell=s;}
 public String name(){return "TermuxRepair";} public String description(){return "Repair the embedded Termux apt/dpkg state after an interrupted or incompatible package installation. Patches cached deb paths, runs dpkg --configure -a, then apt-get -f install -y.";}
 public JSONObject inputSchema(){return ToolSchemas.object(new JSONObject());} public PermissionKind permissionKind(){return PermissionKind.SHELL;}
 public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{String cmd="set -o pipefail; if [ -x \"$PREFIX/bin/iq-patch-deb\" ]; then find \"$PREFIX/var/cache/apt/archives\" -maxdepth 1 -type f -name '*.deb' -exec \"$PREFIX/bin/iq-patch-deb\" {} + 2>/dev/null || true; fi; dpkg --configure -a; apt-get -f install -y; dpkg --audit";TermuxShellExecutor.Result r=shell.execute(cmd,c.projectDirectory,15*60*1000);return ToolExecutionResult.command(r.timedOut?124:r.exitCode,r.combined());}
}
