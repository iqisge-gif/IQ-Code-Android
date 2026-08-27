package com.termux.app.iqcode.api;

import com.termux.app.iqcode.model.SessionConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, redirect-free model catalog fetcher for standard Anthropic/OpenAI endpoints. */
public final class ModelCatalogClient {
    public interface CancellationSignal { boolean isCancelled(); }

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_RESPONSE_CHARS = 512 * 1024;
    private static final int MAX_MODELS = 250;

    public List<ModelDescriptor> fetch(SessionConfig config, CancellationSignal cancellation) throws Exception {
        if (config == null || config.apiKey == null || config.apiKey.trim().isEmpty()) {
            throw new IllegalStateException("当前 API 配置没有可用密钥");
        }
        String endpoint = ApiEndpointResolver.modelCatalogEndpoint(config);
        if (endpoint.isEmpty()) throw new IllegalStateException("当前 API 协议未公开标准模型目录，请手动填写模型名");
        checkCancelled(cancellation);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if ("anthropic".equals(config.protocol)) {
            connection.setRequestProperty("x-api-key", config.apiKey);
            connection.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
        }
        try {
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) throw new IllegalStateException("模型目录请求被重定向，已拒绝");
            if (code < 200 || code >= 300) throw new IllegalStateException("模型目录请求失败（HTTP " + code + "）");
            String body = readBounded(connection.getInputStream(), cancellation);
            checkCancelled(cancellation);
            return parse(body);
        } finally {
            connection.disconnect();
        }
    }

    static List<ModelDescriptor> parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        if (data == null) throw new IllegalStateException("模型目录返回格式无效");
        Map<String, ModelDescriptor> unique = new LinkedHashMap<>();
        for (int i = 0; i < data.length() && unique.size() < MAX_MODELS; i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = safeId(item.optString("id", ""));
            if (id.isEmpty() || unique.containsKey(id)) continue;
            String name = safeDisplay(item.optString("display_name", item.optString("name", id)));
            unique.put(id, new ModelDescriptor(id, name));
        }
        ArrayList<ModelDescriptor> models = new ArrayList<>(unique.values());
        Collections.sort(models, Comparator.comparing(model -> model.id));
        return models;
    }

    private static String readBounded(InputStream stream, CancellationSignal cancellation) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                checkCancelled(cancellation);
                if (out.length() + count > MAX_RESPONSE_CHARS) throw new IllegalStateException("模型目录响应过大");
                out.append(buffer, 0, count);
            }
        }
        return out.toString();
    }

    private static String safeId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 160) return "";
        for (int i = 0; i < id.length(); i++) if (Character.isISOControl(id.charAt(i))) return "";
        return id;
    }

    private static String safeDisplay(String value) {
        String name = value == null ? "" : value.trim();
        if (name.length() > 160) name = name.substring(0, 160);
        return name;
    }

    private static void checkCancelled(CancellationSignal cancellation) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancellation != null && cancellation.isCancelled()) throw new InterruptedException("Cancelled");
    }
}
