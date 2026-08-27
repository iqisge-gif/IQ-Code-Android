import java.nio.file.*;
public final class SandboxMainProcessRoleStructureTest {
  private static void require(boolean ok,String m){if(!ok)throw new AssertionError(m);}
  public static void main(String[] a)throws Exception{
    String core=Files.readString(Path.of("Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java"));
    String provider=Files.readString(Path.of("src/com/iqge/sandbox/SandboxControlProvider.java"));
    String role=Files.readString(Path.of("src/com/iqge/sandbox/SandboxProcessRole.java"));
    require(core.contains("getHostPkg() + \":iqsandbox\""),":iqsandbox must be an explicit BlackBox main process");
    require(core.contains("processName.equals(sandboxMainProcess)"),"BlackBox process classifier must accept sandbox main");
    require(!provider.substring(provider.indexOf("onCreate()"), provider.indexOf("@Override public Bundle call")).contains("IQSandboxEngine.create()"),"provider must not run doCreate before Application.onCreate");
    require(role.contains("proc.equals(pkg + \":iqsandbox\")")&&role.contains("proc.equals(pkg + \":black\")"),"attach role must be allowlisted");
    require(role.contains("index >= 0 && index < 50"),"only p0..p49 should be guest proxy processes");
    System.out.println("SandboxMainProcessRoleStructureTest PASS");
  }
}
