package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public final class GlobTool implements IQTool {
    private static final int MAX_RESULTS = 3000;

    @Override public String name() { return "Glob"; }
    @Override public String description() { return "Find files by glob pattern recursively, such as **/*.java or app/src/**/*.xml. Results are sorted by newest modification time first."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.READ; }

    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("pattern", ToolSchemas.string("Glob pattern, for example **/*.java."));
            p.put("path", ToolSchemas.string("Directory to search. Defaults to the active project."));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "pattern");
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        File root = PathPolicy.resolve(config.projectDirectory, input.optString("path", config.projectDirectory));
        if (!root.isDirectory()) return ToolExecutionResult.error("Search path is not a directory: " + root);
        Pattern regex = Pattern.compile(globToRegex(input.optString("pattern", "**/*")));
        List<File> matches = new ArrayList<>();
        walk(root, root, regex, matches);
        Collections.sort(matches, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        StringBuilder out = new StringBuilder();
        for (File file : matches) out.append(file.getAbsolutePath()).append('\n');
        if (matches.isEmpty()) out.append("(no matches)\n");
        if (matches.size() >= MAX_RESULTS) out.append("…result limit reached…\n");
        return ToolExecutionResult.ok(out.toString());
    }

    private static void walk(File root, File current, Pattern pattern, List<File> out) {
        if (out.size() >= MAX_RESULTS) return;
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= MAX_RESULTS) return;
            String name = child.getName();
            if (child.isDirectory()) {
                if (".git".equals(name) || ".gradle".equals(name)) continue;
                walk(root, child, pattern, out);
            } else {
                String relative = root.toURI().relativize(child.toURI()).getPath();
                if (pattern.matcher(relative).matches()) out.add(child);
            }
        }
    }

    static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doublestar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doublestar) {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        out.append("(?:.*/)?");
                    } else out.append(".*");
                } else out.append("[^/]*");
            } else if (c == '?') out.append("[^/]");
            else if (c == '[') {
                int end = glob.indexOf(']', i + 1);
                if (end > i) { out.append(glob, i, end + 1); i = end; }
                else out.append("\\[");
            } else {
                if ("\\.(){}+$^|".indexOf(c) >= 0) out.append('\\');
                out.append(c);
            }
        }
        return out.append('$').toString();
    }
}
