import java.nio.file.*;
public final class SandboxFileProviderResourceRegressionTest {
  private static void req(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args.length == 0 ? "." : args[0]);
    String manifest = Files.readString(root.resolve("Bcore/src/main/AndroidManifest.xml"));
    String fp = Files.readString(root.resolve("Bcore/src/main/java/top/niunaijun/blackbox/fake/provider/FileProvider.java"));
    String core = Files.readString(root.resolve("Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java"));
    int p = manifest.indexOf("android:name=\".fake.provider.FileProvider\"");
    req(p >= 0, "BlackBox FileProvider missing");
    String block = manifest.substring(p, Math.min(manifest.length(), p + 500));
    req(!block.contains("android:process=\":iqsandbox\""), "FileProvider must not bootstrap the IQSandbox controller process");
    req(block.contains("android:resource=\"@xml/filepath\""), "FILE_PROVIDER_PATHS must reference @xml/filepath");
    req(fp.contains("addFallbackRoots(context, strat)"), "FileProvider fallback roots missing");
    req(fp.contains("catch (Throwable ignored)"), "FileProvider must survive resource/PackageManager races");
    req(!fp.contains("Missing \" + META_DATA_FILE_PROVIDER_PATHS + \" meta-data"), "FileProvider still throws on missing metadata");
    req(core.contains("BlackBoxCore.getHostPkg() + \":black\""), "server process detection must not depend on generated R$string");
    req(!core.contains("getString(R.string.black_box_service_name)"), "generated R$string runtime dependency remains");
    System.out.println("SandboxFileProviderResourceRegressionTest PASS");
  }
}
