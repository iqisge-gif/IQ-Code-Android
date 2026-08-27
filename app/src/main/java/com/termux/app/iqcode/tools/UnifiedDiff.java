package com.termux.app.iqcode.tools;

import java.util.ArrayList;
import java.util.List;

/** Small dependency-free line diff for tool transcript previews; it is not used to apply edits. */
final class UnifiedDiff {
    static final class Result {
        final String text;
        final int additions;
        final int deletions;
        Result(String text, int additions, int deletions) {
            this.text = text == null ? "" : text;
            this.additions = Math.max(0, additions);
            this.deletions = Math.max(0, deletions);
        }
    }

    private static final class Op {
        final char kind;
        final String text;
        Op(char kind, String text) { this.kind = kind; this.text = text == null ? "" : text; }
    }

    private UnifiedDiff() {}

    static Result create(String path, String before, String after, boolean existed) {
        String[] oldLines = lines(before);
        String[] newLines = lines(after);
        boolean coarse = (long)oldLines.length * (long)newLines.length > 1_200_000L;
        List<Op> ops = diff(oldLines, newLines);
        int additions = 0, deletions = 0;
        if (coarse) {
            int prefix = commonPrefix(oldLines, newLines);
            int suffix = commonSuffix(oldLines, newLines, prefix);
            deletions = oldLines.length - prefix - suffix;
            additions = newLines.length - prefix - suffix;
        } else {
            for (Op op : ops) {
                if (op.kind == '+') additions++;
                else if (op.kind == '-') deletions++;
            }
        }
        if (additions == 0 && deletions == 0) return new Result("", 0, 0);

        String shownPath = cleanPath(path);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(existed ? "a/" + shownPath : "/dev/null").append('\n');
        out.append("+++ b/").append(shownPath).append('\n');
        appendHunks(out, ops);
        if (out.length() > 140_000) {
            out.setLength(140_000);
            out.append("\n@@ … diff display truncated … @@\n");
        }
        return new Result(out.toString(), additions, deletions);
    }

    static Result deleted(String path, String before) {
        String[] oldLines = lines(before);
        if (oldLines.length == 0) return new Result("", 0, 0);
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(cleanPath(path)).append('\n');
        out.append("+++ /dev/null\n");
        out.append("@@ -1,").append(oldLines.length).append(" +0,0 @@\n");
        int shown = Math.min(oldLines.length, 1200);
        for (int i = 0; i < shown && out.length() < 140_000; i++) out.append('-').append(oldLines[i]).append('\n');
        if (shown < oldLines.length) out.append("@@ … ").append(oldLines.length - shown).append(" deleted lines omitted … @@\n");
        return new Result(out.toString(), 0, oldLines.length);
    }

    static Result combine(List<Result> results) {
        StringBuilder text = new StringBuilder();
        int additions = 0, deletions = 0;
        if (results != null) for (Result result : results) {
            if (result == null) continue;
            additions += result.additions;
            deletions += result.deletions;
            if (!result.text.isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(result.text);
            }
        }
        return new Result(text.toString(), additions, deletions);
    }

    private static List<Op> diff(String[] a, String[] b) {
        long cells = (long)a.length * (long)b.length;
        if (cells > 1_200_000L) return coarseDiff(a, b);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? 1 + lcs[i + 1][j + 1]
                    : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Op> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                out.add(new Op(' ', a[i++])); j++;
            } else if (i < a.length && (j >= b.length || lcs[i + 1][j] >= lcs[i][j + 1])) {
                out.add(new Op('-', a[i++]));
            } else {
                out.add(new Op('+', b[j++]));
            }
        }
        return out;
    }

    private static List<Op> coarseDiff(String[] a, String[] b) {
        int prefix = commonPrefix(a, b);
        int suffix = commonSuffix(a, b, prefix);
        List<Op> out = new ArrayList<>();
        for (int i = Math.max(0, prefix - 3); i < prefix; i++) out.add(new Op(' ', a[i]));
        int oldChanged = a.length - prefix - suffix;
        int newChanged = b.length - prefix - suffix;
        appendLimited(out, '-', a, prefix, oldChanged);
        appendLimited(out, '+', b, prefix, newChanged);
        for (int i = a.length - suffix; i < Math.min(a.length, a.length - suffix + 3); i++) {
            if (i >= 0) out.add(new Op(' ', a[i]));
        }
        return out;
    }

    private static int commonPrefix(String[] a, String[] b) {
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) prefix++;
        return prefix;
    }

    private static int commonSuffix(String[] a, String[] b, int prefix) {
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix
            && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) suffix++;
        return suffix;
    }

    private static void appendLimited(List<Op> out, char kind, String[] lines, int start, int count) {
        int head = Math.min(count, 500);
        for (int i = 0; i < head; i++) out.add(new Op(kind, lines[start + i]));
        if (head < count) out.add(new Op(' ', "… " + (count - head) + " changed lines omitted from preview …"));
    }

    private static void appendHunks(StringBuilder out, List<Op> ops) {
        List<int[]> ranges = new ArrayList<>();
        int lastEnd = -1;
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind == ' ') continue;
            int start = Math.max(0, i - 3);
            int end = Math.min(ops.size(), i + 4);
            if (!ranges.isEmpty() && start <= lastEnd) {
                ranges.get(ranges.size() - 1)[1] = Math.max(lastEnd, end);
            } else {
                ranges.add(new int[]{start, end});
            }
            lastEnd = ranges.get(ranges.size() - 1)[1];
        }
        for (int[] range : ranges) {
            int oldStart = 1, newStart = 1;
            for (int i = 0; i < range[0]; i++) {
                char kind = ops.get(i).kind;
                if (kind != '+') oldStart++;
                if (kind != '-') newStart++;
            }
            int oldCount = 0, newCount = 0;
            for (int i = range[0]; i < range[1]; i++) {
                char kind = ops.get(i).kind;
                if (kind != '+') oldCount++;
                if (kind != '-') newCount++;
            }
            out.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            for (int i = range[0]; i < range[1]; i++) {
                Op op = ops.get(i);
                out.append(op.kind).append(op.text).append('\n');
            }
        }
    }

    private static String[] lines(String value) {
        if (value == null || value.isEmpty()) return new String[0];
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        String[] raw = normalized.split("\n", -1);
        if (raw.length > 0 && raw[raw.length - 1].isEmpty()) {
            String[] trimmed = new String[raw.length - 1];
            System.arraycopy(raw, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }

    private static String cleanPath(String path) {
        String value = path == null || path.trim().isEmpty() ? "file" : path.trim().replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        return value;
    }
}
