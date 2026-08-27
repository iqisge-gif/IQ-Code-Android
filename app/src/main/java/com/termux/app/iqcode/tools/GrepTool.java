package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GrepTool implements IQTool {
    private static final int MAX_RESULTS = 2000;
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;

    @Override public String name() { return "Grep"; }
    @Override public String description() { return "Search file contents recursively with a Java regular expression. Returns file:line:text matches. Use glob to restrict filenames."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.READ; }

    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("pattern", ToolSchemas.string("Java-compatible regular expression."));
            p.put("path", ToolSchemas.string("File or directory to search. Defaults to the active project."));
            p.put("glob", ToolSchemas.string("Optional filename glob such as **/*.java."));
            p.put("case_insensitive", ToolSchemas.bool("Case-insensitive matching."));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "pattern");
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        int flags = input.optBoolean("case_insensitive", false) ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        final Pattern contentPattern;
        try { contentPattern = Pattern.compile(input.optString("pattern", ""), flags); }
        catch (PatternSyntaxException e) { return ToolExecutionResult.error("Invalid regex: " + e.getMessage()); }
        String glob = input.optString("glob", "");
        Pattern filePattern = glob.isEmpty() ? null : Pattern.compile(GlobTool.globToRegex(glob));
        File root = PathPolicy.resolve(config.projectDirectory, input.optString("path", config.projectDirectory));
        StringBuilder out = new StringBuilder();
        Counter counter = new Counter();
        if (root.isFile()) scanFile(root.getParentFile(), root, contentPattern, filePattern, out, counter);
        else if (root.isDirectory()) walk(root, root, contentPattern, filePattern, out, counter);
        else return ToolExecutionResult.error("Search path does not exist: " + root);
        if (counter.value == 0) out.append("(no matches)\n");
        if (counter.value >= MAX_RESULTS) out.append("…result limit reached…\n");
        return ToolExecutionResult.ok(out.toString());
    }

    private static void walk(File root, File current, Pattern contentPattern, Pattern filePattern, StringBuilder out, Counter counter) {
        if (counter.value >= MAX_RESULTS) return;
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (counter.value >= MAX_RESULTS) return;
            if (child.isDirectory()) {
                String name = child.getName();
                if (".git".equals(name) || ".gradle".equals(name)) continue;
                walk(root, child, contentPattern, filePattern, out, counter);
            } else scanFile(root, child, contentPattern, filePattern, out, counter);
        }
    }

    private static void scanFile(File root, File file, Pattern contentPattern, Pattern filePattern, StringBuilder out, Counter counter) {
        if (counter.value >= MAX_RESULTS || file.length() > MAX_FILE_BYTES) return;
        String relative = root == null ? file.getName() : root.toURI().relativize(file.toURI()).getPath();
        if (filePattern != null && !filePattern.matcher(relative).matches()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null && counter.value < MAX_RESULTS) {
                lineNo++;
                if (contentPattern.matcher(line).find()) {
                    if (line.length() > 3000) line = line.substring(0, 3000) + " …[line truncated]";
                    out.append(file.getAbsolutePath()).append(':').append(lineNo).append(':').append(line).append('\n');
                    counter.value++;
                }
            }
        } catch (Exception ignored) { }
    }

    private static final class Counter { int value; }
}
