import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class AgentProgressParticleSphereStructureTest {
    private static void require(boolean value, String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String source=Files.readString(root.resolve("src/com/iqge/AgentProgressView.java"),StandardCharsets.UTF_8);
        String script=Files.readString(root.resolve("test-source-no-build.sh"),StandardCharsets.UTF_8);
        require(source.contains("class SphereView")&&source.contains("Canvas")&&source.contains("drawPoint"),"progress view must draw a particle sphere");
        require(source.contains("System.nanoTime()")&&source.contains("postOnAnimation")&&source.contains("setAnimating"),"sphere rotation must be timestamp/frame based and lifecycle gated");
        require(source.contains("count=128")&&source.contains("Math.PI*2.0*i")&&source.contains("points[i]*cos-points[i+1]*sin")&&!source.contains("Math.atan2"),"indicator must rotate deterministic points without per-particle trigonometry");
        require(source.contains("postDelayed(this,33L)")&&!source.contains("handler.postDelayed(this,70)"),"progress animation must be capped near 30fps and must not relayout status text on a timer");
        require(source.contains("sphere.setAnimating(false)")&&source.contains("onDetachedFromWindow"),"animation callbacks must stop on clear/detach");
        require(source.contains("GradientDrawable")&&source.contains("setCornerRadius(dp(16))")&&source.contains("setText(verb)"),"progress activity must use one compact surface without a duplicate square marker");
        require(source.contains("Typeface.DEFAULT")&&!source.contains("status.setTypeface(Typeface.MONOSPACE")&&!source.contains("reasoning.setTypeface(Typeface.MONOSPACE"),"progress presentation text must match the normal chat typography");
        require(source.contains("setOutputBacklog")&&source.contains("setSpeed")&&source.contains("outputBacklog*.018f"),"ring speed must follow bounded pending output backlog");
        require(script.contains("AgentProgressParticleSphereStructureTest.java")&&script.contains("AgentProgressParticleSphereStructureTest \"$PROJECT_ROOT\""),"canonical source suite must run particle sphere regression");
        System.out.println("AgentProgressParticleSphereStructureTest PASS");
    }
}
