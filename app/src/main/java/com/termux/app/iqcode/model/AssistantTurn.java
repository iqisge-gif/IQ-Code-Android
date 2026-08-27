package com.termux.app.iqcode.model;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Final normalized assistant turn assembled from Anthropic SSE events. */
public final class AssistantTurn {
    public final JSONArray content = new JSONArray();
    public final List<ToolCall> toolCalls = new ArrayList<>();
    public String stopReason;
    public long inputTokens;
    public long outputTokens;
}
