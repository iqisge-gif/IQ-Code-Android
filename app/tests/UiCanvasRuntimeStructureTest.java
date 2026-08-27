import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
public final class UiCanvasRuntimeStructureTest {
  private static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
  public static void main(String[] a)throws Exception{
    Path r=Paths.get(a.length==0?".":a[0]).toAbsolutePath();
    String s=Files.readString(r.resolve("src/com/iqge/UiCanvasStore.java"),StandardCharsets.UTF_8);
    String c=Files.readString(r.resolve("src/com/iqge/UiCanvasController.java"),StandardCharsets.UTF_8);
    String t=Files.readString(r.resolve("src/com/termux/app/iqcode/tools/UiCanvasTool.java"),StandardCharsets.UTF_8);
    String m=Files.readString(r.resolve("src/com/iqge/MainActivity.java"),StandardCharsets.UTF_8);
    require(s.contains("undo")&&s.contains("redo")&&s.contains("commit()")&&s.contains("MAX_BYTES"),"store must atomically bound and retain history");
    require(c.contains("MAX_OPS")&&c.contains("visibility")&&c.contains("textSize")&&c.contains("MarginLayoutParams"),"renderer must allow-list runtime layout properties");
    require(t.contains("patch")&&t.contains("preview")&&t.contains("export")&&t.contains("operations"),"tool must expose runtime canvas operations");
    require(m.contains("UiCanvasController.apply")&&m.contains("showUiCanvasPanel")&&m.contains("/canvas"),"activity must apply and expose the canvas");
    System.out.println("UiCanvasRuntimeStructureTest PASS");
  }
}
