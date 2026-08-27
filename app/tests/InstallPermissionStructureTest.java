import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class InstallPermissionStructureTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String manifest = read(root, "AndroidManifest.xml");
        String provider = read(root, "src/com/iqge/IqFileProvider.java");
        String bridge = read(root, "src/com/termux/app/iqcode/tools/AndroidIntentBridge.java");
        String activity = read(root, "src/com/iqge/MainActivity.java");
        String build = read(root, "build-integrated.sh");
        String gradle = read(root, "build.gradle");

        require(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"),
            "manifest must request Android unknown-source install capability");
        require(manifest.contains("com.iqge.IqFileProvider")
                && manifest.contains("android:grantUriPermissions=\"true\"")
                && manifest.contains("android:exported=\"false\""),
            "private grantable FileProvider must be registered");
        require(manifest.contains("versionCode=\"2280\"") && manifest.contains("versionName=\"0.21.18\""),
            "release version must be 0.21.18");
        require(provider.contains("ParcelFileDescriptor.MODE_READ_ONLY")
                && provider.contains("throw new UnsupportedOperationException(\"read-only provider\")")
                && provider.contains("if (!under(candidate, canonicalRoot))"),
            "provider must remain read-only and reject traversal");
        require(bridge.contains("canRequestPackageInstalls()")
                && bridge.contains("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES")
                && bridge.contains("PENDING_APK_INSTALL")
                && bridge.contains("restorePendingApkIntent(context)")
                && bridge.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION")
                && bridge.contains("ClipData.newRawUri"),
            "APK installer must request permission, retain pending work and grant URI access");
        require(activity.contains("AndroidIntentBridge.resumePendingApkInstall(this)"),
            "activity must resume installation after returning from system settings");
        require(build.contains("./gradlew") && build.contains("assembleRelease")
                && build.contains("android.aapt2FromMavenOverride")
                && gradle.contains("implementation project(':Bcore')")
                && gradle.contains("signing/iqge-local-dev.jks"),
            "integrated build must use Gradle manifest/resource merging, Bcore and the stable IQGE signing key");
        require(activity.contains("ScrollView detailsScroll")
                && activity.contains("details.addView(inputText, ip)")
                && activity.contains("panel.addView(detailsScroll, new LinearLayout.LayoutParams(-1, 0, 1))")
                && activity.contains("int height=Math.min(dp(720)"),
            "permission details must scroll while action buttons stay in a fixed footer");
        System.out.println("InstallPermissionStructureTest PASS");
    }
}
