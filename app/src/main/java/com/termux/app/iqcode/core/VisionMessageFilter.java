package com.termux.app.iqcode.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** Removes image capability requirements from a detached provider-facing message snapshot. */
final class VisionMessageFilter {
    private VisionMessageFilter() {}

    static JSONArray apply(JSONArray messages, boolean visionEnabled) throws Exception {
        if (messages == null || visionEnabled) return messages;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray content = message.optJSONArray("content");
            if (content == null) continue;
            JSONArray filtered = new JSONArray();
            for (int j = 0; j < content.length(); j++) {
                Object raw = content.opt(j);
                if (!(raw instanceof JSONObject)) {
                    if (raw != null) filtered.put(raw);
                    continue;
                }
                JSONObject block = (JSONObject) raw;
                if (!"image".equals(block.optString("type", ""))) {
                    filtered.put(block);
                    continue;
                }
                String name = block.optString("name", "图片").trim();
                if (name.isEmpty()) name = "图片";
                filtered.put(new JSONObject()
                    .put("type", "text")
                    .put("text", "[图片未发送：API 设置中的视觉输入已关闭；文件：" + name + "]"));
            }
            message.put("content", filtered);
        }
        return messages;
    }
}
