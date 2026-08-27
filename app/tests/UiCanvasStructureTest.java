import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class UiCanvasStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String store=Files.readString(root.resolve("src/com/iqge/UiCanvasStore.java"),StandardCharsets.UTF_8);
        String tool=Files.readString(root.resolve("src/com/termux/app/iqcode/tools/UiCanvasTool.java"),StandardCharsets.UTF_8);
        String registry=Files.readString(root.resolve("src/com/termux/app/iqcode/tools/ToolRegistry.java"),StandardCharsets.UTF_8);
        String main=Files.readString(root.resolve("src/com/iqge/MainActivity.java"),StandardCharsets.UTF_8);
        String script=Files.readString(root.resolve("test-source-no-build.sh"),StandardCharsets.UTF_8);
        require(store.contains("VERSION=2")&&store.contains("migrate")&&store.contains("SharedPreferences")&&store.contains("undo")&&store.contains("redo"),"canvas store must be versioned, persistent, and reversible");
        require(tool.contains("ui_canvas")&&tool.contains("patch")&&tool.contains("preview")&&tool.contains("reset")&&tool.contains("okWithAdditionalContent"),"Agent must have bounded runtime canvas operations");
        require(registry.contains("new UiCanvasTool(context)"),"UI canvas tool must be registered");
        require(main.contains("applyUiCanvasPalette")&&main.contains("UiCanvasStore.load"),"MainActivity must consume saved canvas palette");
        require(script.contains("UiCanvasStructureTest.java")&&script.contains("UiCanvasStructureTest \"$PROJECT_ROOT\""),"canonical source suite must run canvas regression");
        System.out.println("UiCanvasStructureTest PASS");
    }
}
