import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

public final class BundledRuntimeStructureTest {
    private static final long EXPECTED_SIZE = 32_176_084L;
    private static final String EXPECTED_SHA256 = "82aae307c462bc911b02588228714438122e4f7f4492c78d8ab9914e78d10216";

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder out = new StringBuilder();
        for (byte item : digest.digest()) out.append(String.format("%02x", item & 255));
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Path asset = root.resolve("assets/bootstrap-aarch64.zip");
        Path compatibilityJar = root.resolve("lib/iqcode-termux-compat.jar");
        String activity = read(root, "src/com/iqge/MainActivity.java");
        String terminal = read(root, "src/com/iqge/TermuxTerminalPane.java");
        String build = read(root, "build-integrated.sh");
        String gradle = read(root, "build.gradle");

        require(Files.isRegularFile(asset), "the ARM64 Termux bootstrap must be checked into APK assets");
        require(Files.size(asset) == EXPECTED_SIZE, "bundled bootstrap size changed unexpectedly");
        require(EXPECTED_SHA256.equals(sha256(asset)), "bundled bootstrap digest changed unexpectedly");
        try (ZipFile zip = new ZipFile(asset.toFile())) {
            require(zip.size() >= 3000 && zip.getEntry("SYMLINKS.txt") != null,
                "bundled bootstrap must be a complete Termux archive");
        }

        require(Files.isRegularFile(compatibilityJar), "binary Termux compatibility library must be present");
        try (ZipFile jar = new ZipFile(compatibilityJar.toFile())) {
            require(jar.getEntry("com/iqge/RuntimeInstaller.class") != null
                    && jar.getEntry("com/iqge/RuntimeInstaller$Progress.class") != null,
                "compatibility library must expose the RuntimeInstaller API required by the UI");
        }
        require(!Files.exists(root.resolve("src/com/iqge/RuntimeInstaller.java"))
                && gradle.contains("implementation files('lib/iqcode-termux-compat.jar')"),
            "Termux prefix conversion implementation must remain binary-only and wired into Gradle");

        require(build.contains("EXPECTED_SHA256") && build.contains(EXPECTED_SHA256)
                && build.contains("sha256sum")
                && gradle.contains("noCompress 'zip'"),
            "the integrated build must keep the bootstrap uncompressed and verify its pinned digest");
        require(activity.contains("正在初始化内置 Termux 环境")
                && activity.contains("Reading bundled Termux bootstrap")
                && terminal.contains("无需联网下载"),
            "first-launch UI must describe local initialization rather than a download");
        System.out.println("BundledRuntimeStructureTest PASS");
    }
}
