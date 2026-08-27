import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ModelCatalogStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String file)throws Exception{return new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String resolver=read(root,"src/com/termux/app/iqcode/api/ApiEndpointResolver.java");
        String client=read(root,"src/com/termux/app/iqcode/api/ModelCatalogClient.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        require(resolver.contains("modelCatalogEndpoint")&&resolver.contains("appendV1(base, \"models\")")&&resolver.contains("return \"\""),"catalog resolution must stay on configured standard endpoints and fail closed for unsupported protocols");
        require(client.contains("setInstanceFollowRedirects(false)")&&client.contains("MAX_RESPONSE_CHARS")&&client.contains("MAX_MODELS")&&client.contains("CancellationSignal"),"catalog fetches must be bounded, cancellable, and reject redirects");
        require(client.contains("x-api-key")&&client.contains("anthropic-version")&&client.contains("Authorization")&&client.contains("Bearer "),"catalog authentication must follow protocol rules");
        require(ui.contains("composerModelChip.setOnClickListener(v -> showModelPicker())")&&ui.contains("modelCatalogClient.fetch")&&ui.contains("正在从当前 API 获取模型")&&ui.contains("手动输入模型名")&&ui.contains("管理 API"),"model chip must open direct discovery with safe manual fallback");
        require(ui.contains("modelCatalogGeneration.incrementAndGet()")&&ui.contains("modelCatalogFuture.cancel(true)"),"stale model catalog requests must be cancelled and generation guarded");
        System.out.println("ModelCatalogStructureTest PASS");
    }
}
