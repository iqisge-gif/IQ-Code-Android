package com.iqge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Groups adjacent read/search-style tool calls for a compact chat activity projection. */
public final class ToolActivityGrouper {
    private ToolActivityGrouper() { }

    public static final class Entry {
        public final String toolId;
        public final String name;
        public final int readRequests;
        public final String hint;
        public final boolean boundaryBefore;

        public Entry(String toolId, String name, int readRequests, String hint, boolean boundaryBefore) {
            this.toolId = toolId == null ? "" : toolId;
            this.name = name == null ? "" : name;
            this.readRequests = Math.max(0, readRequests);
            this.hint = hint == null ? "" : hint;
            this.boundaryBefore = boundaryBefore;
        }
    }

    public static final class GroupPlan {
        public final List<String> toolIds;
        public final int searchPatterns;
        public final int readRequests;
        public final int locations;
        public final String latestHint;

        private GroupPlan(List<String> toolIds, int searchPatterns, int readRequests, int locations, String latestHint) {
            this.toolIds = Collections.unmodifiableList(new ArrayList<>(toolIds));
            this.searchPatterns = searchPatterns;
            this.readRequests = readRequests;
            this.locations = locations;
            this.latestHint = latestHint == null ? "" : latestHint;
        }

        public boolean contains(String toolId) { return toolIds.contains(toolId); }
    }

    public static List<GroupPlan> group(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        ArrayList<GroupPlan> groups = new ArrayList<>();
        ArrayList<Entry> run = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (Entry entry : entries) {
            boolean usable = entry != null && isCandidate(entry.name) && !entry.toolId.isEmpty() && seenIds.add(entry.toolId);
            if (entry != null && entry.boundaryBefore) flush(run, groups);
            if (!usable) {
                flush(run, groups);
                continue;
            }
            run.add(entry);
        }
        flush(run, groups);
        return groups;
    }

    private static boolean isCandidate(String name) {
        return "Read".equals(name) || "ReadMany".equals(name) || "Grep".equals(name) || "Glob".equals(name)
            || "LS".equals(name) || "Tree".equals(name) || "Stat".equals(name);
    }

    private static void flush(List<Entry> run, List<GroupPlan> groups) {
        if (run.size() < 2) {
            run.clear();
            return;
        }
        ArrayList<String> ids = new ArrayList<>();
        int searches = 0;
        int reads = 0;
        int locations = 0;
        String latestHint = "";
        for (Entry entry : run) {
            ids.add(entry.toolId);
            if ("Grep".equals(entry.name) || "Glob".equals(entry.name)) searches++;
            else if ("Read".equals(entry.name)) reads++;
            else if ("ReadMany".equals(entry.name)) reads += Math.max(1, entry.readRequests);
            else locations++;
            if (!entry.hint.trim().isEmpty()) latestHint = entry.hint;
        }
        groups.add(new GroupPlan(ids, searches, reads, locations, latestHint));
        run.clear();
    }
}
