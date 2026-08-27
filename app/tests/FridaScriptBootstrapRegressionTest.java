import java.nio.file.*;

public final class FridaScriptBootstrapRegressionTest {
    private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String bridge=Files.readString(root.resolve("src/com/iqge/sandbox/SandboxFridaBridge.java"));
        require(bridge.contains("rpc.exports={"),"Gadget Script mode must export rpc.exports");
        require(bridge.contains("init(){let I=\\\""),"Gadget bridge must initialize through rpc.exports.init");
        require(bridge.contains("File.writeAllText(READY,'1')"),"ready marker must be written by init after bridge setup");
        require(bridge.contains("setInterval(tick,30);}};"),"init must leave the mailbox timer alive and close the export object");
        require(!bridge.contains("frida:'  "),"truncated/unterminated v0.21.15 DEX tail must never return");
        System.out.println("FridaScriptBootstrapRegressionTest PASS");
    }
}
