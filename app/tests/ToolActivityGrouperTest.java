import com.iqge.ToolActivityGrouper;

import java.util.Arrays;
import java.util.List;

public final class ToolActivityGrouperTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static ToolActivityGrouper.Entry entry(String id, String name, int reads, String hint, boolean boundary) {
        return new ToolActivityGrouper.Entry(id, name, reads, hint, boundary);
    }

    public static void main(String[] args) {
        List<ToolActivityGrouper.GroupPlan> grouped = ToolActivityGrouper.group(Arrays.asList(
            entry("grep-1", "Grep", 0, "TODO · src", false),
            entry("read-1", "Read", 1, "AndroidManifest.xml", false),
            entry("read-many", "ReadMany", 3, "MainActivity.java · +2", false)
        ));
        require(grouped.size() == 1, "adjacent search/read tools must collapse into one group");
        ToolActivityGrouper.GroupPlan first = grouped.get(0);
        require(first.toolIds.equals(Arrays.asList("grep-1", "read-1", "read-many")), "group must preserve tool order");
        require(first.searchPatterns == 1 && first.readRequests == 4 && first.locations == 0, "group must count search and actual ReadMany paths");
        require("MainActivity.java · +2".equals(first.latestHint), "group must retain the latest useful detail");

        grouped = ToolActivityGrouper.group(Arrays.asList(
            entry("read-a", "Read", 1, "a", false),
            entry("grep-a", "Grep", 0, "a", false),
            entry("write", "Write", 0, "a", false),
            entry("read-b", "Read", 1, "b", false),
            entry("glob-b", "Glob", 0, "b", false)
        ));
        require(grouped.size() == 2, "writes and other non-candidate tools must break a collapsed run");
        require(grouped.get(0).toolIds.equals(Arrays.asList("read-a", "grep-a")), "tools before a write stay together");
        require(grouped.get(1).toolIds.equals(Arrays.asList("read-b", "glob-b")), "tools after a write begin a new group");

        grouped = ToolActivityGrouper.group(Arrays.asList(
            entry("read-c", "Read", 1, "c", false),
            entry("grep-c", "Grep", 0, "c", true),
            entry("read-d", "Read", 1, "d", false)
        ));
        require(grouped.size() == 1 && grouped.get(0).toolIds.equals(Arrays.asList("grep-c", "read-d")), "visible assistant content boundaries must split groups");

        grouped = ToolActivityGrouper.group(Arrays.asList(entry("only", "Read", 1, "only", false)));
        require(grouped.isEmpty(), "a single tool must keep its ordinary tool card");

        grouped = ToolActivityGrouper.group(Arrays.asList(
            entry("same", "Read", 1, "first", false),
            entry("same", "Grep", 0, "duplicate", false),
            entry("next", "Read", 1, "next", false)
        ));
        require(grouped.isEmpty(), "duplicate or unreliable IDs must conservatively avoid grouping");

        grouped = ToolActivityGrouper.group(Arrays.asList(
            entry("ls", "LS", 0, "src", false),
            entry("tree", "Tree", 0, "src/com", false),
            entry("stat", "Stat", 0, "AndroidManifest.xml", false)
        ));
        require(grouped.size() == 1 && grouped.get(0).locations == 3, "directory and metadata tools must count viewed locations");

        grouped = ToolActivityGrouper.group(Arrays.asList(
            entry("many", "ReadMany", 0, "first", false),
            entry("read", "Read", 1, "second", false)
        ));
        require(grouped.size() == 1 && grouped.get(0).readRequests == 2, "ReadMany without a path count must still count as one request");

        System.out.println("ToolActivityGrouperTest PASS");
    }
}
