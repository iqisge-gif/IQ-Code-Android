import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ReasoningPreviewStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String main=Files.readString(root.resolve("src/com/iqge/MainActivity.java"),StandardCharsets.UTF_8);
        String view=Files.readString(root.resolve("src/com/iqge/AgentProgressView.java"),StandardCharsets.UTF_8);
        String script=Files.readString(root.resolve("test-source-no-build.sh"),StandardCharsets.UTF_8);
        require(main.contains("onThinkingDelta(String thinking){ handleThinkingDelta")&&main.contains("liveThinking"),"MainActivity must consume streamed reasoning");
        require(main.contains("length()>1200")&&main.contains("appendCollapsedWhitespace(rt.liveThinking,delta)")&&!main.contains("delta.replaceAll"),"reasoning preview must be normalized and bounded without regex work per delta");
        require(main.contains("clearThinkingPreview(rt)")&&main.contains("postRuntimeUi(rt,()->{if(agentProgressView!=null)agentProgressView.setReasoningPreview(\"\");})"),"reasoning preview must clear on the UI thread");
        require(view.contains("setReasoningPreview")&&view.contains("setMaxLines(2)")&&view.contains("TruncateAt.END"),"progress bar must render a compact bounded reasoning preview");
        require(script.contains("ReasoningPreviewStructureTest.java")&&script.contains("ReasoningPreviewStructureTest \"$PROJECT_ROOT\""),"canonical source suite must run reasoning preview regression");
        System.out.println("ReasoningPreviewStructureTest PASS");
    }
}
