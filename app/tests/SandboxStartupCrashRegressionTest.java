import java.nio.file.*;
public class SandboxStartupCrashRegressionTest {
  static String read(Path p) throws Exception { return Files.readString(p); }
  static void req(boolean c,String m){if(!c)throw new AssertionError(m);}
  public static void main(String[] args)throws Exception{
    Path r=Paths.get(args.length==0?".":args[0]);
    String core=read(r.resolve("Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java"));
    String fp=read(r.resolve("Bcore/src/main/java/top/niunaijun/blackbox/fake/provider/FileProvider.java"));
    req(!core.contains("BRUserHandle.get().myUserId()"),"early BRUserHandle reflection dependency remains");
    req(core.contains("resolveHostUserId()")&&core.contains("Process.myUid()"),"direct host userId fallback missing");
    req(fp.contains("addFallbackRoots(context, strat)"),"FileProvider fallback roots missing");
    req(!fp.contains("Missing \" + META_DATA_FILE_PROVIDER_PATHS + \" meta-data"),"FileProvider still hard-crashes on missing metadata");
    System.out.println("SandboxStartupCrashRegressionTest PASS");
  }
}
