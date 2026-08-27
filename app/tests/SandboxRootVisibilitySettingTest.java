import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SandboxRootVisibilitySettingTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String path) throws Exception {
        return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String store = read(root, "src/com/iqge/sandbox/SandboxSettingsStore.java");
        String engine = read(root, "src/com/iqge/sandbox/IQSandboxEngine.java");
        String provider = read(root, "src/com/iqge/sandbox/SandboxControlProvider.java");
        String dashboard = read(root, "src/com/iqge/sandbox/SandboxDashboardActivity.java");
        String testScript = read(root, "test-source-no-build.sh");

        require(store.contains("AtomicFile") && store.contains("sandbox/settings.json"),
                "sandbox settings must use one atomic host file");
        require(store.contains("optBoolean(\"hide_root\", true)") && store.contains("catch (Exception ignored)"),
                "missing or invalid settings must keep root hidden");
        require(store.contains("settings.startWrite()") && store.contains("settings.finishWrite(out)")
                        && store.contains("settings.failWrite(out)"),
                "settings writes must retain AtomicFile rollback semantics");

        require(engine.contains("isHideRoot(){return SandboxSettingsStore.isRootHidden(app);}"),
                "BlackBox root hiding must read the persistent setting");
        require(!engine.contains("isHideRoot(){return true;}"),
                "BlackBox root hiding must not remain hard-coded");
        int stopGuests = engine.indexOf("for(ApplicationInfo ai:apps)BlackBoxCore.get().stopPackage");
        int verifyStopped = engine.indexOf("if(!stopped)throw new IllegalStateException");
        int writeSetting = engine.indexOf("SandboxSettingsStore.setRootHidden(c,hidden)");
        require(stopGuests >= 0 && verifyStopped > stopGuests && writeSetting > verifyStopped,
                "running Guests must be stopped and verified before a changed setting is persisted");
        int launch = engine.indexOf("public static boolean launch(String pkg)");
        int launchLock = engine.indexOf("synchronized(LIFECYCLE_LOCK)", launch);
        require(launch >= 0 && launchLock > launch,
                "Guest launch and setting changes must share lifecycle serialization");

        require(provider.contains("case \"set_hide_root\"")
                        && provider.contains("put(\"hide_root\", IQSandboxEngine.isRootHidden())"),
                "the private sandbox RPC must expose the effective setting");
        require(dashboard.contains("new Switch(this)") && dashboard.contains("隐藏 Root"),
                "the sandbox dashboard must expose a Root hiding switch");
        require(dashboard.contains("所有正在运行的 Guest 将停止")
                        && dashboard.contains("setRootSwitch(previous,false)")
                        && dashboard.contains("setRootSwitch(previous,true)"),
                "the switch must confirm restart semantics, disable in flight, and restore on failure");

        require(testScript.contains("SandboxRootVisibilitySettingTest.java")
                        && testScript.contains("SandboxRootVisibilitySettingTest \"$PROJECT_ROOT\""),
                "the canonical source suite must run this regression test");
        System.out.println("SandboxRootVisibilitySettingTest PASS");
    }
}
