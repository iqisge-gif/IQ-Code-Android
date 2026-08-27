import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SandboxWebViewStructureTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String file) throws Exception {
        return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String webView = read(root,
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/WebViewProxy.java");
        String network = read(root,
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IConnectivityManagerProxy.java");
        String webViewService = read(root,
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IWebViewUpdateServiceProxy.java");
        String webViewFactory = read(root,
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/WebViewFactoryProxy.java");
        String script = read(root, "test-source-no-build.sh");

        require(webView.contains("return method.invoke(who, args);")
                        && webView.contains("settings.setCacheMode(WebSettings.LOAD_DEFAULT)"),
                "WebView must preserve the platform data/cache lifecycle");
        require(!webView.contains("System.setProperty(\"webview.data.dir\")")
                        && !webView.contains("System.setProperty(\"webview.cache.dir\")")
                        && !webView.contains("System.setProperty(\"webview.cookies.dir\")")
                        && !webView.contains("System.setProperty(\"webview.database.path\")")
                        && !webView.contains("webview_fallback")
                        && !webView.contains("setAppCacheEnabled")
                        && !webView.contains("setNetworkAvailable(true)")
                        && !webView.contains("BlackBox\""),
                "sandbox must not redirect or fake WebView storage/network identity");
        require(!network.contains("8.8.8.8") && !network.contains("new Network("),
                "WebView must use real host DNS and Network objects");
        require(webViewService.contains("return method.invoke(who, args);")
                        && !webViewService.contains("return new PackageInfo[0]")
                        && !webViewService.contains("return false for single-process mode"),
                "WebView provider discovery must not be replaced with fake empty/single-process state");
        require(webViewFactory.contains("return method.invoke(who, args);")
                        && !webViewFactory.contains("com.google.android.webview"),
                "WebView provider selection must follow the device provider");
        require(script.contains("SandboxWebViewStructureTest.java")
                        && script.contains("SandboxWebViewStructureTest \"$PROJECT_ROOT\""),
                "the canonical source suite must run the WebView regression");

        System.out.println("SandboxWebViewStructureTest PASS");
    }
}
