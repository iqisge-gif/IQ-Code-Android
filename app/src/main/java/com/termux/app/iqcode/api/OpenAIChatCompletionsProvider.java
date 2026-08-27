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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generic OpenAI-compatible /v1/chat/completions transport.
 *
 * Designed for third-party gateways and OpenAI-compatible local/remote services:
 * - OpenAI Chat Completions message format
 * - streaming SSE text deltas
 * - streaming tool_calls/function arguments
 * - tool result replay
 * - image_url content parts (including data URLs)
 * - common reasoning_content/reasoning fields used by compatible gateways
 *
 * The engine itself keeps one normalized IQ/Anthropic-shaped history. This class
 * translates that normalized history at the transport boundary only.
 */
public final class OpenAIChatCompletionsProvider implements ModelProvider {

    private final HttpRequestTracker requests = new HttpRequestTracker();

    @Override public void cancelRequest(Thread worker) {
        requests.cancel(worker);
    }

    private static final class ToolState {
        final int index;
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();

        ToolState(int index) { this.index = index; }
    }

    private static final class StreamState {
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final Map<Integer, ToolState> tools = new LinkedHashMap<>();
        boolean terminalEventSeen;
    }

    @Override
    public AssistantTurn createMessage(SessionConfig config, String systemPrompt, JSONArray messages,
                                       JSONArray tools, StreamListener listener) throws Exception {
        JSONObject body = createRequest(config, systemPrompt, messages, tools);
        String endpoint = chatEndpoint(ApiUrlPolicy.requireBaseUrl(config));

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("content-type", "application/json");
        conn.setRequestProperty("accept", "text/event-stream, application/json");
        if (config.apiKey != null && !config.apiKey.trim().isEmpty()) {
            conn.setRequestProperty("authorization", "Bearer " + config.apiKey.trim());
        }
        conn.setRequestProperty("user-agent", "IQCodeAndroid-JavaNative/0.18");

        HttpRequestTracker.Scope request = requests.begin(conn);
        AssistantTurn turn = new AssistantTurn();
        StreamState state = new StreamState();
        try {
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(body.toString());
            }

            int status = conn.getResponseCode();
            request.markResponseStarted();
            if (status < 200 || status >= 300) {
                String error = readAll(conn.getErrorStream());
                throw new IllegalStateException("OpenAI 兼容 API HTTP " + status + ": " + truncate(error, 12000));
            }

            String contentType = conn.getContentType();
            boolean streaming = contentType == null || !contentType.toLowerCase().contains("json")
                || contentType.toLowerCase().contains("event-stream");
            if (!streaming) {
                parseNonStreaming(new JSONObject(readAll(conn.getInputStream())), state, turn, listener);
            } else {
                readSse(conn.getInputStream(), state, turn, listener);
                if (!state.terminalEventSeen) {
                    throw new IllegalStateException("stream_read_error: model stream ended before [DONE]");
                }
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            throw new ModelProvider.StreamFailure(request.failureCode(e), request.failureMessage(e), e);
        } finally {
            request.close();
        }

        finalizeTurn(state, turn);
        if (turn.stopReason == null || turn.stopReason.isEmpty()) {
            turn.stopReason = turn.toolCalls.isEmpty() ? "end_turn" : "tool_use";
        }
        return turn;
    }

    private static JSONObject createRequest(SessionConfig config, String systemPrompt, JSONArray messages,
                                            JSONArray tools) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("messages", mapMessages(systemPrompt, messages));
        body.put("stream", true);

        JSONArray mappedTools = mapTools(tools);
        if (mappedTools.length() > 0) {
            body.put("tools", mappedTools);
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", true);
        }

        // Chat Completions compatibility intentionally uses the existing Chat
        // effort mapping. max/ultra collapse to xhigh here; Responses/Codex keep
        // their strict max/ultra wire semantics in OpenAIResponsesProvider.
        String effort = ReasoningMapper.openAIChatEffort(config.effort);
        if (effort != null) body.put("reasoning_effort", effort);

        if (config.maxTokens > 0) body.put("max_tokens", config.maxTokens);
        return body;
    }

    private static JSONArray mapTools(JSONArray tools) throws Exception {
        JSONArray out = new JSONArray();
        if (tools == null) return out;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            String name = tool.optString("name", "").trim();
            if (name.isEmpty()) continue;
            JSONObject fn = new JSONObject()
                .put("name", name)
                .put("description", tool.optString("description", ""))
                .put("parameters", tool.optJSONObject("input_schema") == null
                    ? new JSONObject().put("type", "object")
                    : tool.optJSONObject("input_schema"));
            out.put(new JSONObject().put("type", "function").put("function", fn));
        }
        return out;
    }

    /** Converts the normalized IQ message history into Chat Completions messages. */
    private static JSONArray mapMessages(String systemPrompt, JSONArray messages) throws Exception {
        JSONArray out = new JSONArray();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            out.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        if (messages == null) return out;

        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "user");
            JSONArray blocks = message.optJSONArray("content");
            if (blocks == null) continue;

            if ("assistant".equals(role)) {
                appendAssistant(out, blocks);
            } else {
                appendUserAndToolResults(out, blocks);
            }
        }
        return out;
    }

    private static void appendAssistant(JSONArray out, JSONArray blocks) throws Exception {
        StringBuilder text = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        for (int j = 0; j < blocks.length(); j++) {
            JSONObject block = blocks.optJSONObject(j);
            if (block == null) continue;
            String type = block.optString("type", "");
            if ("text".equals(type)) {
                String v = block.optString("text", "");
                if (!v.isEmpty()) text.append(v);
            } else if ("tool_use".equals(type)) {
                JSONObject fn = new JSONObject()
                    .put("name", block.optString("name", "unknown_tool"))
                    .put("arguments", block.optJSONObject("input") == null ? "{}" : block.optJSONObject("input").toString());
                toolCalls.put(new JSONObject()
                    .put("id", nonEmpty(block.optString("id", "")) ? block.optString("id") : "call_" + UUID.randomUUID())
                    .put("type", "function")
                    .put("function", fn));
            }
            // Thinking blocks are deliberately transport-internal and are not
            // flattened into visible assistant text on replay.
        }
        if (text.length() == 0 && toolCalls.length() == 0) return;
        JSONObject msg = new JSONObject().put("role", "assistant");
        if (text.length() > 0) msg.put("content", text.toString());
        else msg.put("content", JSONObject.NULL);
        if (toolCalls.length() > 0) msg.put("tool_calls", toolCalls);
        out.put(msg);
    }

    private static void appendUserAndToolResults(JSONArray out, JSONArray blocks) throws Exception {
        JSONArray userParts = new JSONArray();
        for (int j = 0; j < blocks.length(); j++) {
            JSONObject block = blocks.optJSONObject(j);
            if (block == null) continue;
            String type = block.optString("type", "");
            if ("tool_result".equals(type)) {
                flushUserParts(out, userParts);
                userParts = new JSONArray();
                out.put(new JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", block.optString("tool_use_id", ""))
                    .put("content", toolResultText(block.opt("content"))));
            } else if ("text".equals(type)) {
                String v = block.optString("text", "");
                if (!v.isEmpty()) userParts.put(new JSONObject().put("type", "text").put("text", v));
            } else if ("image".equals(type)) {
                JSONObject source = block.optJSONObject("source");
                if (source != null && "base64".equals(source.optString("type"))) {
                    String media = source.optString("media_type", "image/jpeg");
                    String data = source.optString("data", "");
                    if (!data.isEmpty()) {
                        userParts.put(new JSONObject().put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", "data:" + media + ";base64," + data)));
                    }
                }
            }
        }
        flushUserParts(out, userParts);
    }

    private static void flushUserParts(JSONArray out, JSONArray parts) throws Exception {
        if (parts == null || parts.length() == 0) return;
        // For maximum compatibility use a plain string when the message is just
        // one text part; use multimodal content arrays only when needed.
        if (parts.length() == 1) {
            JSONObject one = parts.optJSONObject(0);
            if (one != null && "text".equals(one.optString("type"))) {
                out.put(new JSONObject().put("role", "user").put("content", one.optString("text", "")));
                return;
            }
        }
        out.put(new JSONObject().put("role", "user").put("content", parts));
    }

    private static String toolResultText(Object content) {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject && "text".equals(((JSONObject) v).optString("type"))) {
                    if (s.length() > 0) s.append('\n');
                    s.append(((JSONObject) v).optString("text", ""));
                } else if (v != null && v != JSONObject.NULL) {
                    if (s.length() > 0) s.append('\n');
                    s.append(String.valueOf(v));
                }
            }
            return s.toString();
        }
        return String.valueOf(content);
    }

    private static void readSse(InputStream in, StreamState state, AssistantTurn turn,
                                StreamListener listener) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OpenAI compatible request interrupted");
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) {
                    state.terminalEventSeen = true;
                    break;
                }
                processChunk(new JSONObject(payload), state, turn, listener);
            }
        }
    }

    private static void processChunk(JSONObject chunk, StreamState state, AssistantTurn turn,
                                     StreamListener listener) throws Exception {
        JSONObject usage = chunk.optJSONObject("usage");
        if (usage != null) {
            turn.inputTokens = usage.optLong("prompt_tokens", turn.inputTokens);
            turn.outputTokens = usage.optLong("completion_tokens", turn.outputTokens);
            if (listener != null) listener.onUsage(turn.inputTokens, turn.outputTokens);
        }

        JSONObject error = chunk.optJSONObject("error");
        if (error != null) throw new IllegalStateException(error.optString("message", chunk.toString()));

        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return;
        String finish = choice.optString("finish_reason", "");
        if (!finish.isEmpty() && !"null".equals(finish)) turn.stopReason = mapFinishReason(finish);

        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) return;

        String text = contentText(delta.opt("content"));
        if (!text.isEmpty()) {
            state.text.append(text);
            if (listener != null) listener.onTextDelta(text);
        }

        String thinking = firstNonEmpty(
            stringValue(delta.opt("reasoning_content")),
            stringValue(delta.opt("reasoning")),
            stringValue(delta.opt("thinking"))
        );
        if (!thinking.isEmpty()) {
            state.thinking.append(thinking);
            if (listener != null) listener.onThinkingDelta(thinking);
        }

        JSONArray calls = delta.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) continue;
                int index = call.has("index") ? call.optInt("index", i) : i;
                ToolState ts = state.tools.get(index);
                if (ts == null) { ts = new ToolState(index); state.tools.put(index, ts); }
                String id = call.optString("id", "");
                if (!id.isEmpty()) ts.id = id;
                JSONObject fn = call.optJSONObject("function");
                if (fn != null) {
                    String name = fn.optString("name", "");
                    if (!name.isEmpty()) ts.name = name;
                    String args = fn.optString("arguments", "");
                    if (!args.isEmpty()) {
                        ts.arguments.append(args);
                        if (listener != null) listener.onToolInputDelta(ts.id, ts.name, args);
                    }
                }
            }
        }
    }

    private static void parseNonStreaming(JSONObject response, StreamState state, AssistantTurn turn,
                                          StreamListener listener) throws Exception {
        JSONObject usage = response.optJSONObject("usage");
        if (usage != null) {
            turn.inputTokens = usage.optLong("prompt_tokens", 0);
            turn.outputTokens = usage.optLong("completion_tokens", 0);
            if (listener != null) listener.onUsage(turn.inputTokens, turn.outputTokens);
        }
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return;
        turn.stopReason = mapFinishReason(choice.optString("finish_reason", "stop"));
        JSONObject message = choice.optJSONObject("message");
        if (message == null) return;

        String text = contentText(message.opt("content"));
        if (!text.isEmpty()) {
            state.text.append(text);
            if (listener != null) listener.onTextDelta(text);
        }
        String thinking = firstNonEmpty(
            stringValue(message.opt("reasoning_content")),
            stringValue(message.opt("reasoning")),
            stringValue(message.opt("thinking"))
        );
        if (!thinking.isEmpty()) {
            state.thinking.append(thinking);
            if (listener != null) listener.onThinkingDelta(thinking);
        }
        JSONArray calls = message.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) continue;
                ToolState ts = new ToolState(i);
                ts.id = call.optString("id", "");
                JSONObject fn = call.optJSONObject("function");
                if (fn != null) {
                    ts.name = fn.optString("name", "");
                    ts.arguments.append(fn.optString("arguments", ""));
                }
                state.tools.put(i, ts);
            }
        }
    }

    private static void finalizeTurn(StreamState state, AssistantTurn turn) throws Exception {
        if (state.thinking.length() > 0) {
            turn.content.put(new JSONObject().put("type", "thinking").put("thinking", state.thinking.toString()));
        }
        if (state.text.length() > 0) {
            turn.content.put(new JSONObject().put("type", "text").put("text", state.text.toString()));
        }
        for (ToolState ts : state.tools.values()) {
            String id = nonEmpty(ts.id) ? ts.id : "call_" + UUID.randomUUID();
            String name = nonEmpty(ts.name) ? ts.name : "unknown_tool";
            JSONObject input = parseObjectOrRaw(ts.arguments.toString());
            turn.content.put(new JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input));
            turn.toolCalls.add(new ToolCall(id, name, input));
        }
        if (!turn.toolCalls.isEmpty() && (turn.stopReason == null || "end_turn".equals(turn.stopReason))) {
            turn.stopReason = "tool_use";
        }
    }

    private static JSONObject parseObjectOrRaw(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (JSONException e) {
            try { return new JSONObject().put("_raw_invalid_json", raw); }
            catch (JSONException impossible) { return new JSONObject(); }
        }
    }

    private static String contentText(Object content) {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder out = new StringBuilder();
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                if ("text".equals(p.optString("type"))) out.append(p.optString("text", ""));
            }
            return out.toString();
        }
        return String.valueOf(content);
    }

    private static String stringValue(Object v) {
        return v instanceof String ? (String) v : "";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String mapFinishReason(String reason) {
        if (reason == null) return null;
        if ("tool_calls".equals(reason) || "function_call".equals(reason)) return "tool_use";
        if ("length".equals(reason)) return "max_tokens";
        if ("stop".equals(reason)) return "end_turn";
        return reason;
    }

    private static String chatEndpoint(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String lower = base.toLowerCase();
        if (lower.endsWith("/chat/completions")) return base;
        if (lower.endsWith("/v1")) return base + "/chat/completions";
        return base + "/v1/chat/completions";
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

    private static boolean nonEmpty(String value) { return value != null && !value.trim().isEmpty(); }
}
