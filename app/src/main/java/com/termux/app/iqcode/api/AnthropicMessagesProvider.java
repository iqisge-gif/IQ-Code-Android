package com.termux.app.iqcode.api;

import com.termux.app.iqcode.api.compat.ReasoningMapper;
import com.termux.app.iqcode.model.AssistantTurn;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolCall;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java/Android implementation of the IQ Messages streaming transport.
 * No Bun/Node/TypeScript runtime is involved: SSE is decoded directly here.
 */
public final class AnthropicMessagesProvider implements ModelProvider {

    private final HttpRequestTracker requests = new HttpRequestTracker();

    @Override public void cancelRequest(Thread worker) {
        requests.cancel(worker);
    }

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final class BlockState {
        final int index;
        String type;
        String id;
        String name;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
        final StringBuilder inputJson = new StringBuilder();
        JSONObject startBlock;

        BlockState(int index) {
            this.index = index;
        }
    }

    @Override
    public AssistantTurn createMessage(SessionConfig config, String systemPrompt, JSONArray messages,
                                       JSONArray tools, StreamListener listener) throws Exception {
        String baseUrl = ApiUrlPolicy.requireBaseUrl(config);
        if (config.apiKey == null || config.apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Anthropic API key is not configured");
        }

        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("max_tokens", config.maxTokens);
        body.put("stream", true);
        body.put("system", systemPrompt);
        body.put("messages", messages);
        body.put("tools", tools);

        String effort = ReasoningMapper.anthropicEffort(config.effort);
        if (effort != null) {
            body.put("output_config", new JSONObject().put("effort", effort));
        }

        String endpoint = stripTrailingSlash(baseUrl) + "/v1/messages";
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("content-type", "application/json");
        conn.setRequestProperty("accept", "text/event-stream");
        conn.setRequestProperty("x-api-key", config.apiKey);
        conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
        conn.setRequestProperty("user-agent", "IQCodeAndroid-JavaNative/0.15");

        HttpRequestTracker.Scope request = requests.begin(conn);
        AssistantTurn turn = new AssistantTurn();
        Map<Integer, BlockState> blocks = new TreeMap<>();
        String eventName = null;
        boolean terminalEventSeen = false;

        try {
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(body.toString());
            }

            int status = conn.getResponseCode();
            request.markResponseStarted();
            if (status < 200 || status >= 300) {
                String error = readAll(conn.getErrorStream());
                throw new IllegalStateException("IQ API HTTP " + status + ": " + truncate(error, 12000));
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("IQ request interrupted");
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) continue;

                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                JSONObject event = new JSONObject(payload);
                String type = event.optString("type", eventName == null ? "" : eventName);

                if ("error".equals(type)) {
                    JSONObject error = event.optJSONObject("error");
                    throw new IllegalStateException(error == null ? payload : error.optString("message", payload));
                }

                if ("message_stop".equals(type)) {
                    terminalEventSeen = true;
                    break;
                }

                if ("message_start".equals(type)) {
                    JSONObject message = event.optJSONObject("message");
                    JSONObject usage = message == null ? null : message.optJSONObject("usage");
                    if (usage != null) {
                        turn.inputTokens = totalInputTokens(usage, turn.inputTokens);
                        if (listener != null) listener.onUsage(turn.inputTokens, turn.outputTokens);
                    }
                    continue;
                }

                if ("content_block_start".equals(type)) {
                    int index = event.optInt("index", -1);
                    JSONObject start = event.optJSONObject("content_block");
                    if (index >= 0 && start != null) {
                        BlockState state = new BlockState(index);
                        state.startBlock = start;
                        state.type = start.optString("type", "");
                        state.id = start.optString("id", null);
                        state.name = start.optString("name", null);
                        String initialText = start.optString("text", "");
                        if (!initialText.isEmpty()) state.text.append(initialText);
                        String initialThinking = start.optString("thinking", "");
                        if (!initialThinking.isEmpty()) state.thinking.append(initialThinking);
                        String initialSignature = start.optString("signature", "");
                        if (!initialSignature.isEmpty()) state.signature.append(initialSignature);
                        blocks.put(index, state);
                    }
                    continue;
                }

                if ("content_block_delta".equals(type)) {
                    int index = event.optInt("index", -1);
                    BlockState state = blocks.get(index);
                    JSONObject delta = event.optJSONObject("delta");
                    if (state == null || delta == null) continue;
                    String deltaType = delta.optString("type", "");
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.optString("text", "");
                        state.text.append(text);
                        if (listener != null && !text.isEmpty()) listener.onTextDelta(text);
                    } else if ("thinking_delta".equals(deltaType)) {
                        String thinking = delta.optString("thinking", "");
                        state.thinking.append(thinking);
                        if (listener != null && !thinking.isEmpty()) listener.onThinkingDelta(thinking);
                    } else if ("signature_delta".equals(deltaType)) {
                        state.signature.append(delta.optString("signature", ""));
                    } else if ("input_json_delta".equals(deltaType)) {
                        String partial = delta.optString("partial_json", "");
                        state.inputJson.append(partial);
                        if (listener != null) listener.onToolInputDelta(state.id, state.name, partial);
                    }
                    continue;
                }

                if ("message_delta".equals(type)) {
                    JSONObject delta = event.optJSONObject("delta");
                    if (delta != null && delta.has("stop_reason") && !delta.isNull("stop_reason")) {
                        turn.stopReason = delta.optString("stop_reason", null);
                    }
                    JSONObject usage = event.optJSONObject("usage");
                    if (usage != null) {
                        if (usage.has("input_tokens") || usage.has("cache_creation_input_tokens") || usage.has("cache_read_input_tokens")) {
                            turn.inputTokens = totalInputTokens(usage, turn.inputTokens);
                        }
                        if (usage.has("output_tokens")) turn.outputTokens = usage.optLong("output_tokens", turn.outputTokens);
                        if (listener != null) listener.onUsage(turn.inputTokens, turn.outputTokens);
                    }
                }
            }
        }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            throw new ModelProvider.StreamFailure(request.failureCode(e), request.failureMessage(e), e);
        } finally {
            request.close();
        }

        if (!terminalEventSeen) {
            throw new IllegalStateException("stream_read_error: model stream ended before message_stop");
        }

        for (BlockState state : blocks.values()) {
            JSONObject normalized = normalizeBlock(state);
            if (normalized != null) turn.content.put(normalized);
            if ("tool_use".equals(state.type)) {
                JSONObject input = parseObjectOrEmpty(state.inputJson.toString());
                turn.toolCalls.add(new ToolCall(state.id, state.name, input));
            }
        }
        return turn;
    }

    /** Anthropic reports cache creation/read beside input_tokens; all three occupy context. */
    private static long totalInputTokens(JSONObject usage, long fallback) {
        if (usage == null) return fallback;
        long fresh = usage.optLong("input_tokens", 0L);
        long created = usage.optLong("cache_creation_input_tokens", 0L);
        long cached = usage.optLong("cache_read_input_tokens", 0L);
        long total = fresh + created + cached;
        return total > 0 ? total : fallback;
    }

    private static JSONObject normalizeBlock(BlockState state) throws JSONException {
        if ("text".equals(state.type)) {
            return new JSONObject().put("type", "text").put("text", state.text.toString());
        }
        if ("thinking".equals(state.type)) {
            JSONObject block = new JSONObject().put("type", "thinking").put("thinking", state.thinking.toString());
            if (state.signature.length() > 0) block.put("signature", state.signature.toString());
            return block;
        }
        if ("tool_use".equals(state.type)) {
            return new JSONObject()
                .put("type", "tool_use")
                .put("id", state.id)
                .put("name", state.name)
                .put("input", parseObjectOrEmpty(state.inputJson.toString()));
        }
        // Preserve complete content blocks that arrive fully in content_block_start.
        return state.startBlock == null ? null : new JSONObject(state.startBlock.toString());
    }

    private static JSONObject parseObjectOrEmpty(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            // Return the raw input so the agent can see the malformed payload in the tool result.
            try {
                return new JSONObject().put("_raw_invalid_json", raw);
            } catch (JSONException impossible) {
                return new JSONObject();
            }
        }
    }

    private static String stripTrailingSlash(String url) {
        String out = url.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "\n…truncated…";
    }
}
