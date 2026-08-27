package com.termux.app.iqcode.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ToolSchemas {
    private ToolSchemas() {}

    static JSONObject object(JSONObject properties, String... required) {
        try {
            JSONObject schema = new JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false);
            JSONArray req = new JSONArray();
            if (required != null) for (String r : required) req.put(r);
            if (req.length() > 0) schema.put("required", req);
            return schema;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject string(String description) {
        try { return new JSONObject().put("type", "string").put("description", description); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    static JSONObject integer(String description, int minimum) {
        try { return new JSONObject().put("type", "integer").put("minimum", minimum).put("description", description); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    static JSONObject stringArray(String description) {
        try { return new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")).put("description", description); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    static JSONObject enumString(String description, String... values) {
        try {
            JSONArray a = new JSONArray();
            for (String value : values) a.put(value);
            return new JSONObject().put("type", "string").put("enum", a).put("description", description);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    static JSONObject freeObject(String description) {
        try { return new JSONObject().put("type", "object").put("description", description); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    static JSONObject bool(String description) {
        try { return new JSONObject().put("type", "boolean").put("description", description); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
