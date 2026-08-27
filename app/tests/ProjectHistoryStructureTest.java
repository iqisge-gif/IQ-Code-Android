import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ProjectHistoryStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String relative)throws Exception{return new String(Files.readAllBytes(root.resolve(relative)),StandardCharsets.UTF_8);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String store=read(root,"src/com/termux/app/iqcode/storage/SessionStore.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        String manifest=read(root,"AndroidManifest.xml");
        require(store.contains(".iq/projects")&&store.contains("projectKey(String projectDirectory)")&&store.contains("project.json"),"project path must own a persistent history namespace and readable index");
        require(store.contains("MessageDigest.getInstance(\"SHA-256\")")&&store.contains("canonicalProject(String projectDirectory)"),"canonical path key must be stable and collision resistant");
        require(store.contains("migrateLegacySessions(File preferred)")&&ui.contains("SessionStore.migrateLegacySessions(lastSession)"),"legacy global sessions must migrate before a runtime opens them");
        require(ui.contains("SessionStore.listSessions(config.projectDirectory)")&&ui.contains("选择项目上下文历史")&&ui.contains("新建空白上下文"),"sidebar and resume picker must show only the active project's contexts");
        require(ui.contains("showProjectPathDialog()")&&ui.contains("switchProjectPath(String requestedProject")&&ui.contains("showProjectHistoryPicker(true)"),"manual project path changes must have a direct entry and open that project's context chooser");
        require(manifest.contains("versionCode=\"2280\"")&&manifest.contains("versionName=\"0.21.18\""),"release version must be 0.21.18");
        System.out.println("ProjectHistoryStructureTest PASS");
    }
}
