package com.termux.app.iqcode.model;

import org.json.JSONArray;

public final class ToolExecutionResult {
    public final boolean isError;
    public final String content;
    public final int exitCode;
    /** Optional unified diff used by the Android transcript; never sent as tool output. */
    public final String diff;
    public final int addedLines;
    public final int deletedLines;
    private final JSONArray additionalContent;

    private ToolExecutionResult(boolean isError, String content, int exitCode,
                                String diff, int addedLines, int deletedLines, JSONArray additionalContent) {
        this.isError = isError;
        this.content = content == null ? "" : content;
        this.exitCode = exitCode;
        this.diff = diff == null ? "" : diff;
        this.addedLines = Math.max(0, addedLines);
        this.deletedLines = Math.max(0, deletedLines);
        this.additionalContent = copy(additionalContent);
    }

    public static ToolExecutionResult ok(String content) {
        return new ToolExecutionResult(false, content, 0, "", 0, 0, null);
    }

    public static ToolExecutionResult okWithAdditionalContent(String content, JSONArray additionalContent) {
        return new ToolExecutionResult(false, content, 0, "", 0, 0, additionalContent);
    }

    public static ToolExecutionResult okWithDiff(String content, String diff, int addedLines, int deletedLines) {
        return new ToolExecutionResult(false, content, 0, diff, addedLines, deletedLines, null);
    }

    public static ToolExecutionResult command(int exitCode, String content) {
        return new ToolExecutionResult(exitCode != 0, content, exitCode, "", 0, 0, null);
    }

    public static ToolExecutionResult error(String content) {
        return new ToolExecutionResult(true, content, -1, "", 0, 0, null);
    }

    public JSONArray additionalContent() {
        return additionalContent;
    }

    private static JSONArray copy(JSONArray content) {
        if (content == null || content.length() == 0) return new JSONArray();
        try { return new JSONArray(content.toString()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid additional tool content", e); }
    }
}
