import java.nio.file.*;

public final class FridaGadgetConfigRegressionTest {
    private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String bridge=Files.readString(root.resolve("src/com/iqge/sandbox/SandboxFridaBridge.java"));
        require(bridge.contains("new File(dir, \"libiqfrida.config\")"),"disk-loaded Gadget must use libiqfrida.config");
        require(!bridge.contains("libiqfrida.config.so"),"APK-lib compatibility name must not be used for private disk load");
        require(bridge.contains("put(\"path\", script.getName())"),"Script path must be relative to Gadget directory");
        require(bridge.contains("System.load(gadget.getAbsolutePath())"),"Gadget must still be loaded by absolute library path");
        System.out.println("FridaGadgetConfigRegressionTest PASS");
    }
}
