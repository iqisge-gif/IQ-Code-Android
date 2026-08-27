import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SandboxProcessIsolationStructureTest {
    private static void require(boolean ok,String m){if(!ok)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);
        String app=Files.readString(root.resolve("src/com/iqge/IQCodeApplication.java"),StandardCharsets.UTF_8);
        String manifest=Files.readString(root.resolve("AndroidManifest.xml"),StandardCharsets.UTF_8);
        String client=Files.readString(root.resolve("src/com/iqge/sandbox/SandboxHostClient.java"),StandardCharsets.UTF_8);
        require(app.contains("if (sandboxProcess) IQSandboxEngine.attach(base);"),"BlackBox attach must be sandbox-process gated");
        require(app.contains("SandboxProcessRole.isMainProcess(this)"),"main process branch missing");
        require(manifest.contains("SandboxControlProvider")&&manifest.contains("android:process=\":iqsandbox\""),"isolated sandbox control provider missing");
        require(manifest.contains("InitializationProvider\" tools:node=\"remove\""),"AndroidX startup removal missing");
        require(client.contains("ContentResolver")||client.contains("getContentResolver().call"),"sandbox IPC client missing");
        System.out.println("SandboxProcessIsolationStructureTest PASS");
    }
}
