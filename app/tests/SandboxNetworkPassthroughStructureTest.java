import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SandboxNetworkPassthroughStructureTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String file) throws Exception {
        return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String engine = read(root, "src/com/iqge/sandbox/IQSandboxEngine.java");
        String connectivity = read(root,
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IConnectivityManagerProxy.java");
        String core = read(root, "Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java");
        String dns = read(root,
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IDnsResolverProxy.java");
        String hooks = read(root,
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");
        String script = read(root, "test-source-no-build.sh");

        require(engine.contains("isUseVpnNetwork(){return false;"),
                "IQ Sandbox must keep the incomplete VPN transport disabled");
        require(engine.contains("网络使用宿主机直连") && engine.contains("VPN 网络模式已禁用"),
                "sandbox startup must diagnose host-network passthrough");
        require(connectivity.contains("class PassthroughHook")
                        && connectivity.contains("return method.invoke(who, args);"),
                "the unused connectivity compatibility proxy must not fabricate network state");
        require(!hooks.contains("addInjector(new IConnectivityManagerProxy())"),
                "Guests must use the host ConnectivityManager directly instead of a binder proxy");
        require(!connectivity.contains("8.8.8.8") && !connectivity.contains("8.8.4.4")
                        && !connectivity.contains("new Network(")
                        && !connectivity.contains("setDetailedState")
                        && !connectivity.contains("NET_CAPABILITY_VALIDATED"),
                "connectivity hooks must not fabricate DNS, Network, or validation state");
        require(core.contains("isVpnNetworkSupported()")
                        && core.contains("unsupported; keeping host network"),
                "accidental VPN activation must be rejected instead of black-holing traffic");
        require(dns.contains("do not replace it")
                        && !dns.contains("8.8.8.8") && !dns.contains("8.8.4.4")
                        && !dns.contains("replaceSystemService(DNS_RESOLVER_SERVICE)"),
                "the sandbox must leave Android DNS resolution on the real resolver");
        require(!hooks.contains("addInjector(new IDnsResolverProxy())"),
                "the broken DNS resolver injector must not be installed");
        require(script.contains("SandboxNetworkPassthroughStructureTest.java")
                        && script.contains("SandboxNetworkPassthroughStructureTest \"$PROJECT_ROOT\""),
                "the canonical source suite must run the network passthrough regression");

        System.out.println("SandboxNetworkPassthroughStructureTest PASS");
    }
}
