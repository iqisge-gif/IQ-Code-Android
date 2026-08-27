import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ThemeStructureTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String ui = read(root, "src/com/iqge/MainActivity.java");
        String markdown = read(root, "src/com/iqge/MarkdownRenderer.java");
        String terminal = read(root, "src/com/iqge/TermuxTerminalPane.java");
        String motion = read(root, "src/com/iqge/UiMotion.java");
        String manifest = read(root, "AndroidManifest.xml");

        require(ui.contains("THEME_CLASSIC") && ui.contains("THEME_NEON")
                && ui.contains("applyUiPalette") && ui.contains("neonTheme"),
            "classic and neon runtime palettes must both exist");
        require(ui.contains("applyStoredCustomPalette()")
                && ui.contains("putInt(PALETTE_BG_KEY,background)")
                && ui.contains("putInt(PALETTE_ACCENT_KEY,accent)"),
            "custom palette colors must persist across application restarts");
        require(ui.contains("paletteInput(BG)")
                && ui.contains("paletteInput(SURFACE)")
                && ui.contains("paletteInput(TEXT)")
                && ui.contains("颜色必须使用 #RRGGBB 格式"),
            "settings must expose validated custom palette controls");
        require(ui.contains("area.addView(buildWorkspaceTabs(true), lp(-1, dp(66)))")
                && ui.contains("⌂\\n对话") && ui.contains("⇄\\n变更"),
            "mobile layout must expose chat and changes navigation");
        require(ui.contains("Color.rgb(91,103,255)")
                && ui.contains("Color.rgb(142,78,255)")
                && ui.contains("Color.rgb(28, 190, 145)")
                && ui.contains("accentGradient") && ui.contains("elevatedCard"),
            "neon theme must retain violet gradient cards and turquoise status color");
        require(ui.contains("String draft = prompt == null")
                && ui.contains("prompt.setText(draft)")
                && ui.contains("rebuildWorkspaceForTheme"),
            "live theme switching must preserve the current input draft");
        require(markdown.contains("private static final Palette CLASSIC")
                && markdown.contains("private static final Palette NEON")
                && markdown.contains("private static final Palette DAY")
                && markdown.contains("highlight(String code,String lang,boolean neonTheme)")
                && markdown.contains("highlight(String code,String lang,boolean neonTheme,boolean lightTheme)")
                && markdown.contains("BackgroundColorSpan(palette.addBg)")
                && markdown.contains("BackgroundColorSpan(palette.deleteBg)"),
            "Markdown, editor syntax and green/red diffs must use explicit day/night palettes");
        require(ui.contains("MarkdownRenderer.render(this, item.body.toString(), 14f, neonTheme, lightTheme)")
                && ui.contains("new android.text.SpannableStringBuilder(visible)")
                && ui.contains("new ForegroundColorSpan(ACCENT)")
                && ui.contains("MarkdownRenderer.highlight(source,languageForFile(file),neonTheme,lightTheme)"),
            "final chat, lightweight streaming cursor and the file editor must respect the active theme");
        require(terminal.contains("public void applyTheme(boolean neon)")
                && terminal.contains("applyTerminalPaletteToSessions()")
                && terminal.contains("TextStyle.COLOR_INDEX_BACKGROUND")
                && terminal.contains("without restarting PTYs"),
            "live Termux sessions must recolor without losing PTY state or scrollback");
        require(ui.contains("saveAndApplyCustomPalette")
                && ui.contains("mixColor")
                && motion.contains("public static void themeChanged(View view)"),
            "settings must derive and apply a complete runtime palette from custom base colors");
        require(manifest.contains("versionCode=\"2280\"") && manifest.contains("versionName=\"0.21.18\""),
            "release version must be 0.21.18");
        System.out.println("ThemeStructureTest PASS");
    }
}
