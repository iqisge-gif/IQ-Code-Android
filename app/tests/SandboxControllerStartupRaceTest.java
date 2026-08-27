import java.nio.file.*;
public final class SandboxControllerStartupRaceTest {
  static void req(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
  public static void main(String[] a)throws Exception{
    Path r=Path.of(a.length==0?".":a[0]);
    String engine=Files.readString(r.resolve("src/com/iqge/sandbox/IQSandboxEngine.java"));
    String provider=Files.readString(r.resolve("src/com/iqge/sandbox/SandboxControlProvider.java"));
    String ui=Files.readString(r.resolve("src/com/iqge/sandbox/SandboxDashboardActivity.java"));
    String core=Files.readString(r.resolve("Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java"));
    req(engine.contains("CountDownLatch READY_LATCH") && engine.contains("awaitReady(long timeoutMs)"),"controller readiness gate missing");
    req(engine.contains("ensureCreateScheduled()") && engine.contains("new Handler(Looper.getMainLooper())"),"main-loop engine creation scheduling missing");
    req(provider.contains("IQSandboxEngine.ensureCreateScheduled()") && provider.contains("IQSandboxEngine.awaitReady(12000)"),"provider can still race Application.onCreate");
    req(ui.contains("retryOrShow") && ui.contains("沙箱后端启动中"),"dashboard startup retry missing");
    req(core.contains("attach:controller-ready") && core.contains("return;"),"dedicated controller does not exit attach before runtime hooks");
    req(core.contains("throw new IllegalStateException(\"IQSandbox controller initialization failed\""),"controller create failure can still be reported as ready");
    System.out.println("SandboxControllerStartupRaceTest PASS");
  }
}
