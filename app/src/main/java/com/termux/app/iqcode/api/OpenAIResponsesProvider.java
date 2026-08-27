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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Java-native OpenAI Responses / private Codex Responses compatibility transport.
 *
 * This intentionally mirrors the important invariants from the supplied
 * TypeScript compatibility layer instead of launching that TypeScript at runtime:
 * - standard Responses uses /v1/responses; Codex uses /responses
 * - Codex always sends store=false
 * - max/ultra reasoning effort is exact and single-shot (no hidden downgrade)
 * - terminal snapshots are reconciled against deltas so text/tool arguments do
 *   not get emitted twice
 * - an exact final_answer replay of the immediately preceding Codex commentary
 *   is suppressed, matching the modified source's visible de-duplication rule
 */
public final class OpenAIResponsesProvider implements ModelProvider {

    private final HttpRequestTracker requests = new HttpRequestTracker();

    @Override public void cancelRequest(Thread worker) {
        requests.cancel(worker);
    }

    private static final String CODEX_TRANSLATOR_VERSION = "2.1.87";

    private static final class TextState {
        final String key;
        String phase;
        final StringBuilder received = new StringBuilder();
        final StringBuilder emitted = new StringBuilder();
        String duplicateCandidate;
        boolean exactDuplicateSuppressed;

        TextState(String key) { this.key = key; }
    }

    private static final class CallState {
        final String key;
        String callId;
        String name;
        final StringBuilder arguments = new StringBuilder();

        CallState(String key) { this.key = key; }
    }

    private static final class StreamState {
        final Map<String, JSONObject> items = new LinkedHashMap<>();
        final Map<Integer, String> outputKeys = new LinkedHashMap<>();
        final Map<String, String> itemKeys = new LinkedHashMap<>();
        final Map<String, String> callKeys = new LinkedHashMap<>();
        final Map<String, TextState> texts = new LinkedHashMap<>();
        final Map<String, CallState> calls = new LinkedHashMap<>();
        final StringBuilder thinking = new StringBuilder();
        boolean terminalEventSeen;
        String lastCommentary;
        int generatedKey;
    }

    @Override
    public AssistantTurn createMessage(SessionConfig config, String systemPrompt, JSONArray messages,
                                       JSONArray tools, StreamListener listener) throws Exception {
        String baseUrl = ApiUrlPolicy.requireBaseUrl(config);
        if (config.apiKey == null || config.apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key is not configured");
        }
        boolean codex = "codex-responses".equals(config.protocol);
        JSONObject body = createRequest(config, systemPrompt, messages, tools, codex);
        String endpoint = stripTrailingSlash(baseUrl) + (codex ? "/responses" : "/v1/responses");

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("content-type", "application/json");
        conn.setRequestProperty("accept", "text/event-stream");
        conn.setRequestProperty("authorization", "Bearer " + config.apiKey);
        conn.setRequestProperty("user-agent", codex
            ? "codex_cli_rs/" + CODEX_TRANSLATOR_VERSION + " (iq-code-translation)"
            : "IQCodeAndroid-JavaNative/0.15");
        if (codex) {
            String session = nonEmpty(config.sessionId) ? config.sessionId : UUID.randomUUID().toString();
            String thread = nonEmpty(config.threadId) ? config.threadId : session;
            conn.setRequestProperty("originator", "codex_cli_rs");
            conn.setRequestProperty("session-id", session);
            conn.setRequestProperty("thread-id", thread);
            conn.setRequestProperty("x-client-request-id", truncate(thread, 512));
        }

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
                // Deliberately no reasoning downgrade/retry here.
                throw new IllegalStateException("Responses API HTTP " + status + ": " + truncate(error, 12000));
            }

            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("json") && !contentType.toLowerCase().contains("event-stream")) {
                JSONObject response = new JSONObject(readAll(conn.getInputStream()));
                captureTerminalResponse(response, state, turn, listener);
            } else {
                readSse(conn.getInputStream(), state, turn, listener);
                if (!state.terminalEventSeen) {
                    throw new ModelProvider.StreamFailure("stream_read_error", "stream closed before response.completed/response.incomplete");
                }
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            if (e instanceof ModelProvider.StreamFailure) throw e;
            throw new ModelProvider.StreamFailure(request.failureCode(e), request.failureMessage(e), e);
        } finally {
            request.close();
        }
        finalizeTurn(state, turn);
        if (turn.stopReason == null) turn.stopReason = turn.toolCalls.isEmpty() ? "end_turn" : "tool_use";
        return turn;
    }

    private static JSONObject createRequest(SessionConfig config, String systemPrompt, JSONArray messages,
                                            JSONArray tools, boolean codex) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", config.model);
        if (systemPrompt != null && !systemPrompt.isEmpty()) body.put("instructions", systemPrompt);
        body.put("input", mapInput(messages));
        body.put("stream", true);
        body.put("store", false);

        JSONArray mappedTools = mapTools(tools, codex && "native".equals(config.toolMode));
        if (mappedTools.length() > 0) {
            body.put("tools", mappedTools);
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", true);
        }

        String configuredEffort = ReasoningMapper.openAIResponsesConfiguredEffort(config.effort);
        String effort = ReasoningMapper.openAIResponsesWireEffort(config.effort);
        boolean reasoningDisabled = "none".equals(configuredEffort) || "0".equals(ReasoningMapper.normalizeRequestedEffort(config.effort));
        if (effort != null && !reasoningDisabled) {
            JSONObject reasoning = new JSONObject().put("effort", effort);
            if (config.reasoningSummary != null && !config.reasoningSummary.isEmpty() && !"off".equals(config.reasoningSummary)) {
                reasoning.put("summary", config.reasoningSummary);
            }
            body.put("reasoning", reasoning);
        }
        if (!reasoningDisabled && config.preserveReasoningState) {
            body.put("include", new JSONArray().put("reasoning.encrypted_content"));
        }

        // Private Codex /responses rejects several standard-only fields.
        if (!codex) body.put("max_output_tokens", config.maxTokens);
        if (codex && nonEmpty(config.sessionId)) {
            body.put("prompt_cache_key", truncate(config.threadId == null || config.threadId.isEmpty() ? config.sessionId : config.threadId, 512));
        }
        return body;
    }

    private static JSONArray mapTools(JSONArray tools, boolean nativeBash) throws Exception {
        JSONArray out = new JSONArray();
        if (tools == null) return out;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            String name = tool.optString("name", "");
            if (name.isEmpty()) continue;
            if (nativeBash && "Bash".equals(name)) {
                out.put(new JSONObject().put("type", "shell"));
                continue;
            }
            JSONObject mapped = new JSONObject()
                .put("type", "function")
                .put("name", name)
                .put("description", tool.optString("description", ""))
                .put("parameters", tool.optJSONObject("input_schema") == null ? new JSONObject().put("type", "object") : tool.optJSONObject("input_schema"))
                .put("strict", false);
            out.put(mapped);
        }
        return out;
    }

    /** Maps the engine's normalized Anthropic-shaped history into Responses items. */
    private static JSONArray mapInput(JSONArray messages) throws Exception {
        JSONArray out = new JSONArray();
        if (messages == null) return out;
        Set<String> pairedCalls = pairedToolCallIds(messages);
        Set<String> emittedCalls = new LinkedHashSet<>();
        Set<String> emittedOutputs = new LinkedHashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "user");
            JSONArray blocks = message.optJSONArray("content");
            if (blocks == null) continue;

            if ("assistant".equals(role)) {
                JSONArray textParts = new JSONArray();
                for (int j = 0; j < blocks.length(); j++) {
                    JSONObject block = blocks.optJSONObject(j);
                    if (block == null) continue;
                    String type = block.optString("type", "");
                    if ("text".equals(type)) {
                        String text = block.optString("text", "");
                        if (!text.isEmpty()) textParts.put(new JSONObject().put("type", "output_text").put("text", text).put("annotations", new JSONArray()));
                    } else if ("tool_use".equals(type)) {
                        String callId = block.optString("id", "").trim();
                        String name = block.optString("name", "unknown_tool");
                        JSONObject input = block.optJSONObject("input");
                        String args = input == null ? "{}" : input.toString();

                        // Responses rejects a history that contains a function_call without a matching
                        // function_call_output. Old sessions can acquire exactly that shape when Android kills
                        // the process, a tool is cancelled, or an earlier build compacted the boundary badly.
                        // Only emit protocol-level calls that are known to have a later result. Preserve all
                        // incomplete/duplicate call information as ordinary assistant text instead.
                        if (callId.isEmpty() || emittedCalls.contains(callId) || !pairedCalls.contains(callId)) {
                            String prefix = callId.isEmpty() ? "Previous tool call" : "Incomplete previous tool call " + callId;
                            textParts.put(new JSONObject().put("type", "output_text")
                                .put("text", "[" + prefix + ": " + name + " " + args + "]")
                                .put("annotations", new JSONArray()));
                            continue;
                        }
                        if (textParts.length() > 0) {
                            out.put(new JSONObject().put("type", "message").put("role", "assistant").put("content", textParts));
                            textParts = new JSONArray();
                        }
                        out.put(new JSONObject()
                            .put("type", "function_call")
                            .put("call_id", callId)
                            .put("name", name)
                            .put("arguments", args));
                        emittedCalls.add(callId);
                    }
                    // Thinking blocks are intentionally not flattened into visible text.
                    // Encrypted reasoning replay support is tracked separately in the porting matrix.
                }
                if (textParts.length() > 0) out.put(new JSONObject().put("type", "message").put("role", "assistant").put("content", textParts));
                continue;
            }

            JSONArray userParts = new JSONArray();
            for (int j = 0; j < blocks.length(); j++) {
                JSONObject block = blocks.optJSONObject(j);
                if (block == null) continue;
                String type = block.optString("type", "");
                if ("tool_result".equals(type)) {
                    String callId = block.optString("tool_use_id", "").trim();
                    String output = toolResultText(block.opt("content"));
                    if (!callId.isEmpty() && pairedCalls.contains(callId) && emittedCalls.contains(callId) && !emittedOutputs.contains(callId)) {
                        if (userParts.length() > 0) {
                            out.put(new JSONObject().put("type", "message").put("role", "user").put("content", userParts));
                            userParts = new JSONArray();
                        }
                        out.put(new JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", callId)
                            .put("output", output));
                        emittedOutputs.add(callId);
                    } else {
                        // This is the exact failure mode produced by an old compaction boundary:
                        // function_call_output survived but its function_call did not. Sending that item
                        // makes Responses reject the whole request with "No tool call found ...".
                        // Keep the useful result as ordinary user context instead of emitting an orphan.
                        String label = callId.isEmpty() ? "unknown" : callId;
                        userParts.put(new JSONObject().put("type", "input_text")
                            .put("text", "[Previous tool result " + label + "]\n" + output));
                    }
                } else if ("text".equals(type)) {
                    String text = block.optString("text", "");
                    if (!text.isEmpty()) userParts.put(new JSONObject().put("type", "input_text").put("text", text));
                } else if ("image".equals(type)) {
                    JSONObject source = block.optJSONObject("source");
                    if (source != null && "base64".equals(source.optString("type"))) {
                        String media = source.optString("media_type", "image/jpeg");
                        String data = source.optString("data", "");
                        if (!data.isEmpty()) userParts.put(new JSONObject().put("type", "input_image").put("image_url", "data:" + media + ";base64," + data));
                    }
                }
            }
            if (userParts.length() > 0) out.put(new JSONObject().put("type", "message").put("role", "user").put("content", userParts));
        }
        return out;
    }


    /**
     * Return tool call ids that have a result later in the normalized history. A simple
     * all-ids pre-scan is not sufficient: a corrupt orphan result can appear before a
     * replayed call with the same id, which would still create a dangling Responses item.
     */
    private static Set<String> pairedToolCallIds(JSONArray messages) {
        Set<String> seenCalls = new LinkedHashSet<>();
        Set<String> paired = new LinkedHashSet<>();
        if (messages == null) return paired;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray blocks = message.optJSONArray("content");
            if (blocks == null) continue;
            for (int j = 0; j < blocks.length(); j++) {
                JSONObject block = blocks.optJSONObject(j);
                if (block == null) continue;
                String type = block.optString("type", "");
                if ("tool_use".equals(type)) {
                    String id = block.optString("id", "").trim();
                    if (!id.isEmpty()) seenCalls.add(id);
                } else if ("tool_result".equals(type)) {
                    String id = block.optString("tool_use_id", "").trim();
                    if (!id.isEmpty() && seenCalls.contains(id)) paired.add(id);
                }
            }
        }
        return paired;
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
                } else if (v != null) {
                    if (s.length() > 0) s.append('\n');
                    s.append(String.valueOf(v));
                }
            }
            return s.toString();
        }
        return String.valueOf(content);
    }

    private static void readSse(InputStream in, StreamState state, AssistantTurn turn, StreamListener listener) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Responses request interrupted");
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        processEvent(eventName, data.toString(), state, turn, listener);
                        data.setLength(0);
                        if (state.terminalEventSeen) break;
                    }
                    eventName = null;
                    continue;
                }
                if (line.startsWith("event:")) eventName = line.substring(6).trim();
                else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0) processEvent(eventName, data.toString(), state, turn, listener);
        }
    }

    private static void processEvent(String eventName, String payload, StreamState state,
                                     AssistantTurn turn, StreamListener listener) throws Exception {
        if (payload == null || payload.isEmpty() || "[DONE]".equals(payload)) return;
        JSONObject event = new JSONObject(payload);
        String type = event.optString("type", eventName == null ? "" : eventName);
        if ("error".equals(type) || "response.failed".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            JSONObject error = event.optJSONObject("error");
            if (error == null && response != null) error = response.optJSONObject("error");
            String code = event.optString("code", "");
            if (code.isEmpty() && error != null) code = error.optString("code", "");
            String message = error == null ? event.optString("message", payload) : error.optString("message", payload);
            throw new ModelProvider.StreamFailure(code, message);
        }

        JSONObject item = event.optJSONObject("item");
        if (item != null && ("response.output_item.added".equals(type) || "response.output_item.done".equals(type))) {
            captureItem(event, item, state, listener, "response.output_item.done".equals(type));
        }

        String key = resolveKey(event, item, state, type);
        if ("response.output_text.delta".equals(type) && event.has("delta")) {
            emitText(state, key, phaseFor(item, state.items.get(key)), event.optString("delta", ""), listener, false);
        } else if ("response.output_text.done".equals(type) && event.has("text")) {
            TextState ts = textState(state, key, phaseFor(item, state.items.get(key)));
            emitText(state, key, ts.phase, snapshotDelta(event.optString("text", ""), ts.received.toString()), listener, true);
        } else if ("response.reasoning_summary_text.delta".equals(type) && event.has("delta")) {
            String delta = event.optString("delta", "");
            state.thinking.append(delta);
            if (listener != null && !delta.isEmpty()) listener.onThinkingDelta(delta);
        } else if ("response.reasoning_summary_text.done".equals(type) && event.has("text")) {
            String delta = snapshotDelta(event.optString("text", ""), state.thinking.toString());
            state.thinking.append(delta);
            if (listener != null && !delta.isEmpty()) listener.onThinkingDelta(delta);
        } else if ("response.reasoning_text.delta".equals(type) && event.has("delta")) {
            String delta = event.optString("delta", "");
            state.thinking.append(delta);
            if (listener != null && !delta.isEmpty()) listener.onThinkingDelta(delta);
        } else if ("response.reasoning_text.done".equals(type) && event.has("text")) {
            String delta = snapshotDelta(event.optString("text", ""), state.thinking.toString());
            state.thinking.append(delta);
            if (listener != null && !delta.isEmpty()) listener.onThinkingDelta(delta);
        } else if ("response.function_call_arguments.delta".equals(type)) {
            CallState cs = callState(state, key, item != null ? item : state.items.get(key), event);
            String delta = event.optString("delta", "");
            cs.arguments.append(delta);
            if (listener != null && !delta.isEmpty()) listener.onToolInputDelta(cs.callId, cs.name, delta);
        } else if ("response.function_call_arguments.done".equals(type)) {
            CallState cs = callState(state, key, item != null ? item : state.items.get(key), event);
            String full = event.optString("arguments", "");
            String delta = snapshotDelta(full, cs.arguments.toString());
            cs.arguments.append(delta);
            if (listener != null && !delta.isEmpty()) listener.onToolInputDelta(cs.callId, cs.name, delta);
        }

        if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            state.terminalEventSeen = true;
            JSONObject response = event.optJSONObject("response");
            if (response != null) captureTerminalResponse(response, state, turn, listener);
            if ("response.incomplete".equals(type)) turn.stopReason = "max_tokens";
        }
    }

    private static void captureTerminalResponse(JSONObject response, StreamState state,
                                                AssistantTurn turn, StreamListener listener) throws Exception {
        JSONArray output = response.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                JSONObject fake = new JSONObject().put("output_index", i).put("item", item);
                captureItem(fake, item, state, listener, true);
            }
        } else {
            String outputText = response.optString("output_text", "");
            if (!outputText.isEmpty()) {
                String key = state.outputKeys.containsKey(0) ? state.outputKeys.get(0) : "message:0";
                TextState ts = textState(state, key, null);
                emitText(state, key, ts.phase, snapshotDelta(outputText, ts.received.toString()), listener, true);
            }
        }
        JSONObject usage = response.optJSONObject("usage");
        if (usage != null) {
            turn.inputTokens = usage.optLong("input_tokens", turn.inputTokens);
            turn.outputTokens = usage.optLong("output_tokens", turn.outputTokens);
            if (listener != null) listener.onUsage(turn.inputTokens, turn.outputTokens);
        }
        JSONObject incomplete = response.optJSONObject("incomplete_details");
        if (incomplete != null && "max_output_tokens".equals(incomplete.optString("reason"))) turn.stopReason = "max_tokens";
    }

    private static void captureItem(JSONObject event, JSONObject item, StreamState state,
                                    StreamListener listener, boolean terminal) throws Exception {
        String key = resolveKey(event, item, state, item.optString("type", "item"));
        JSONObject prior = state.items.get(key);
        state.items.put(key, merge(prior, item));
        String type = item.optString("type", "");
        if ("function_call".equals(type) || "custom_tool_call".equals(type)) {
            CallState cs = callState(state, key, item, event);
            String args = "function_call".equals(type) ? item.optString("arguments", "") : item.optString("input", "");
            if (!args.isEmpty()) {
                String delta = snapshotDelta(args, cs.arguments.toString());
                cs.arguments.append(delta);
                if (listener != null && !delta.isEmpty()) listener.onToolInputDelta(cs.callId, cs.name, delta);
            }
        } else if ("message".equals(type)) {
            JSONArray parts = item.optJSONArray("content");
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                if ("output_text".equals(part.optString("type"))) {
                    TextState ts = textState(state, key, item.optString("phase", null));
                    String delta = snapshotDelta(part.optString("text", ""), ts.received.toString());
                    emitText(state, key, ts.phase, delta, listener, terminal);
                }
            }
        }
    }

    private static String resolveKey(JSONObject event, JSONObject item, StreamState state, String prefix) {
        String itemId = item == null ? null : nonEmptyOrNull(item.optString("id", null));
        String callId = item == null ? null : nonEmptyOrNull(item.optString("call_id", null));
        if (callId == null) callId = nonEmptyOrNull(event.optString("call_id", null));
        int outputIndex = event.has("output_index") ? event.optInt("output_index", -1) : -1;

        if (callId != null && state.callKeys.containsKey(callId)) return state.callKeys.get(callId);
        if (itemId != null && state.itemKeys.containsKey(itemId)) return state.itemKeys.get(itemId);
        if (outputIndex >= 0 && state.outputKeys.containsKey(outputIndex)) {
            String existing = state.outputKeys.get(outputIndex);
            if (itemId != null) state.itemKeys.put(itemId, existing);
            if (callId != null) state.callKeys.put(callId, existing);
            return existing;
        }

        String key = itemId != null ? itemId : callId != null ? callId : outputIndex >= 0 ? prefix + ":" + outputIndex : prefix + ":generated:" + (++state.generatedKey);
        if (itemId != null) state.itemKeys.put(itemId, key);
        if (callId != null) state.callKeys.put(callId, key);
        if (outputIndex >= 0) state.outputKeys.put(outputIndex, key);
        return key;
    }

    private static TextState textState(StreamState state, String key, String phase) {
        TextState ts = state.texts.get(key);
        if (ts == null) {
            ts = new TextState(key);
            ts.phase = phase;
            if ("final_answer".equals(phase) && state.lastCommentary != null && !state.lastCommentary.isEmpty()) {
                ts.duplicateCandidate = state.lastCommentary;
            }
            state.texts.put(key, ts);
        } else if (ts.phase == null && phase != null) ts.phase = phase;
        return ts;
    }

    private static void emitText(StreamState state, String key, String phase, String delta,
                                 StreamListener listener, boolean done) {
        TextState ts = textState(state, key, phase);
        if (delta != null && !delta.isEmpty()) ts.received.append(delta);

        if (ts.duplicateCandidate != null && ts.emitted.length() == 0) {
            String candidate = ts.duplicateCandidate;
            String received = ts.received.toString();
            if (candidate.startsWith(received)) {
                if (done && candidate.equals(received)) ts.exactDuplicateSuppressed = true;
                return;
            }
            ts.duplicateCandidate = null;
            if (listener != null && !received.isEmpty()) listener.onTextDelta(received);
            ts.emitted.append(received);
        } else if (delta != null && !delta.isEmpty()) {
            if (listener != null) listener.onTextDelta(delta);
            ts.emitted.append(delta);
        }

        if (done && "commentary".equals(ts.phase) && ts.received.length() > 0) {
            state.lastCommentary = ts.received.toString();
        }
    }

    private static CallState callState(StreamState state, String key, JSONObject item, JSONObject event) {
        CallState cs = state.calls.get(key);
        if (cs == null) {
            cs = new CallState(key);
            state.calls.put(key, cs);
        }
        if (item != null) {
            if (cs.callId == null) cs.callId = nonEmptyOrNull(item.optString("call_id", null));
            if (cs.callId == null) cs.callId = nonEmptyOrNull(item.optString("id", null));
            if (cs.name == null) cs.name = nonEmptyOrNull(item.optString("name", null));
        }
        if (cs.callId == null) cs.callId = nonEmptyOrNull(event.optString("call_id", null));
        if (cs.callId == null) cs.callId = key;
        if (cs.name == null) cs.name = nonEmptyOrNull(event.optString("name", null));
        if (cs.name == null) cs.name = "unknown_tool";
        state.callKeys.put(cs.callId, key);
        return cs;
    }

    private static void finalizeTurn(StreamState state, AssistantTurn turn) throws Exception {
        for (TextState ts : state.texts.values()) {
            String text = ts.received.toString();
            if (text.isEmpty()) continue;
            if (ts.exactDuplicateSuppressed || (ts.duplicateCandidate != null && ts.duplicateCandidate.equals(text))) continue;
            turn.content.put(new JSONObject().put("type", "text").put("text", text));
        }
        if (state.thinking.length() > 0) {
            turn.content.put(new JSONObject().put("type", "thinking").put("thinking", state.thinking.toString()));
        }
        for (CallState cs : state.calls.values()) {
            if (cs.name == null || "unknown_tool".equals(cs.name)) continue;
            JSONObject input = parseObjectOrRaw(cs.arguments.toString());
            String id = cs.callId == null ? cs.key : cs.callId;
            turn.content.put(new JSONObject().put("type", "tool_use").put("id", id).put("name", cs.name).put("input", input));
            turn.toolCalls.add(new ToolCall(id, cs.name, input));
        }
        if (!turn.toolCalls.isEmpty() && turn.stopReason == null) turn.stopReason = "tool_use";
    }

    private static JSONObject parseObjectOrRaw(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (JSONException e) {
            try { return new JSONObject().put("_raw_invalid_json", raw); }
            catch (JSONException impossible) { return new JSONObject(); }
        }
    }

    private static String phaseFor(JSONObject a, JSONObject b) {
        String p = a == null ? null : nonEmptyOrNull(a.optString("phase", null));
        return p != null ? p : b == null ? null : nonEmptyOrNull(b.optString("phase", null));
    }

    private static JSONObject merge(JSONObject a, JSONObject b) throws JSONException {
        JSONObject out = a == null ? new JSONObject() : new JSONObject(a.toString());
        if (b != null) {
            java.util.Iterator<String> keys = b.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object value = b.opt(k);
                if (value != null) out.put(k, value);
            }
        }
        return out;
    }

    /** Same overlap behavior used by the supplied TypeScript translator. */
    static String snapshotDelta(String snapshot, String emitted) {
        if (snapshot == null || snapshot.isEmpty()) return "";
        if (emitted == null) emitted = "";
        if (snapshot.startsWith(emitted)) return snapshot.substring(emitted.length());
        if (emitted.endsWith(snapshot)) return "";
        int max = Math.min(snapshot.length(), emitted.length());
        for (int overlap = max; overlap > 0; overlap--) {
            if (emitted.endsWith(snapshot.substring(0, overlap))) return snapshot.substring(overlap);
        }
        return snapshot;
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean nonEmpty(String s) { return s != null && !s.trim().isEmpty(); }
    private static String nonEmptyOrNull(String s) { return nonEmpty(s) ? s : null; }
}
