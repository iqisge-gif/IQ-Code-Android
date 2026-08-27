package com.termux.app.iqcode.model;

import org.json.JSONObject;

public final class ToolCall {
    public final String id;
    public final String name;
    public final JSONObject input;

    public ToolCall(String id, String name, JSONObject input) {
        this.id = id;
        this.name = name;
        this.input = input == null ? new JSONObject() : input;
    }
}
