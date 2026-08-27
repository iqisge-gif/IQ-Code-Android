package com.termux.app.iqcode.core;

import com.termux.app.iqcode.model.AssistantTurn;
import com.termux.app.iqcode.model.SessionConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Semantic context-compaction helpers modelled after Claude Code's compaction pipeline.
 *
 * <p>The important invariants live here instead of in the UI:</p>
 * <ul>
 *   <li>automatic compaction reserves summary output plus a safety buffer;</li>
 *   <li>recent context is selected at API-round boundaries, not by an arbitrary message count;</li>
 *   <li>large/old tool results are reduced only in the summarizer request;</li>
 *   <li>the summary is produced by the configured model with tools disabled;</li>
 *   <li>draft analysis is stripped before the compacted context is persisted.</li>
 * </ul>
 */
final class ContextCompactor {
    static final int SUMMARY_OUTPUT_RESERVE_TOKENS = 20_000;
    static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    static final int MAX_PROMPT_TOO_LONG_RETRIES = 2;
    static final int MAX_CONSECUTIVE_AUTO_FAILURES = 3;

    static final class Plan {
        final JSONArray original;
        final JSONArray prefix;
        final JSONArray recent;
        final int removedMessages;
        final int retainedMessages;
        final int roughBeforeTokens;
        final String originalJson;

        Plan(JSONArray original, JSONArray prefix, JSONArray recent, int roughBeforeTokens) {
            this.original = original;
            this.prefix = prefix;
            this.recent = recent;
            this.removedMessages = prefix.length();
            this.retainedMessages = recent.length();
            this.roughBeforeTokens = roughBeforeTokens;
            this.originalJson = original.toString();
        }
    }

    private ContextCompactor() {}

    static int effectiveContextWindow(SessionConfig config) {
        int window = config == null ? 128_000 : Math.max(16_000, config.contextWindowTokens);
        int configuredMax = config == null ? 32_768 : Math.max(1, config.maxTokens);
        int reserve = Math.min(configuredMax, SUMMARY_OUTPUT_RESERVE_TOKENS);
        return Math.max(8_000, window - reserve);
    }

    /** Claude-style hard limit, optionally lowered by IQ Code's user percentage cap. */
    static int autoCompactThreshold(SessionConfig config) {
        int window = config == null ? 128_000 : Math.max(16_000, config.contextWindowTokens);
        int effective = effectiveContextWindow(config);
        int safe = Math.max(4_000, effective - AUTO_COMPACT_BUFFER_TOKENS);
        double ratio = config == null ? 0.95 : Math.max(0.50, Math.min(1.0, config.autoCompactRatio));
        int userCap = (int)Math.floor(window * ratio);
        return Math.max(4_000, Math.min(safe, userCap));
    }

    static int suggestedCut(JSONArray source, SessionConfig config, boolean manual) {
        if (source == null || source.length() == 0) return 0;
        List<Integer> starts = apiRoundStarts(source);
        int n = source.length();
        int[] cumulativeTokens = cumulativeRoughTokens(source);
        int targetRecent = clamp(effectiveContextWindow(config) / 5, 8_000, 32_000);
        int keepStart = starts.get(starts.size() - 1);
        int keptTokens = cumulativeTokens[n] - cumulativeTokens[keepStart];
        for (int group = starts.size() - 2; group >= 0; group--) {
            int start = starts.get(group);
            int end = starts.get(group + 1);
            int groupTokens = cumulativeTokens[end] - cumulativeTokens[start];
            if (keptTokens + groupTokens > targetRecent) break;
            keepStart = start;
            keptTokens += groupTokens;
        }

        if (keepStart > 0) return keepStart;

        // A manual /compact should still be useful on short-message-count sessions with a
        // very large prompt/tool result. Keep the final API round when possible; otherwise
        // summarize the sole round in full. Automatic compaction reaches this branch only
        // for a single huge API round, where full replacement is the only safe reduction.
        int total = cumulativeTokens[n];
        if (starts.size() > 1 && (manual || total >= autoCompactThreshold(config))) {
            // Manual full compaction still leaves a small verbatim working set. Two API
            // rounds preserve the latest request/tool hand-off without falling back to the
            // old arbitrary "last 12 messages" rule.
            int keepGroup = manual ? Math.max(1, starts.size() - 2) : starts.size() - 1;
            return starts.get(keepGroup);
        }
        if (total >= (manual ? 1_500 : autoCompactThreshold(config))) return n;
        return 0;
    }

    static Plan createPlan(JSONArray source, int safeCut) throws Exception {
        JSONArray original = cloneArray(source);
        int cut = Math.max(0, Math.min(safeCut, original.length()));
        JSONArray prefix = slice(original, 0, cut);
        JSONArray recent = slice(original, cut, original.length());
        return new Plan(original, prefix, recent, roughTokens(original));
    }

    static JSONArray buildSummaryMessages(Plan plan, String customInstructions, int attempt) throws Exception {
        JSONArray out = reduceForSummary(plan.prefix, Math.max(0, attempt));
        String prompt = compactPrompt(customInstructions, plan.recent.length() > 0);
        out.put(message("user", new JSONArray().put(textBlock(prompt))));
        return out;
    }

    static JSONArray buildCompactedMessages(Plan plan, String summary) throws Exception {
        JSONArray out = new JSONArray();
        StringBuilder context = new StringBuilder();
        context.append("<context_summary>\n")
            .append("This session continues from an earlier conversation. The model-generated summary below covers the compacted prefix.\n\n")
            .append(summary.trim())
            .append("\n</context_summary>\n\n")
            .append("<continuation_instruction>Continue seamlessly from this context. Do not acknowledge or recap the summary; resume the current task directly.")
            .append(plan.recent.length() > 0 ? " Recent messages follow verbatim." : "")
            .append("</continuation_instruction>");
        out.put(message("user", new JSONArray().put(textBlock(context.toString()))));
        for (int i = 0; i < plan.recent.length(); i++) out.put(plan.recent.getJSONObject(i));
        return out;
    }

    static String extractSummary(AssistantTurn turn) {
        if (turn == null) throw new IllegalStateException("压缩失败：模型没有返回结果");
        if ("max_tokens".equals(turn.stopReason)) {
            throw new IllegalStateException("压缩中断：摘要达到输出上限，请调高最大输出 token 后重试");
        }
        if (!turn.toolCalls.isEmpty()) {
            throw new IllegalStateException("压缩失败：摘要模型错误地尝试调用工具");
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < turn.content.length(); i++) {
            JSONObject block = turn.content.optJSONObject(i);
            if (block == null || !"text".equals(block.optString("type", ""))) continue;
            String value = block.optString("text", "");
            if (!value.isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(value);
            }
        }
        String formatted = formatSummary(text.toString());
        if (formatted.isEmpty()) throw new IllegalStateException("压缩失败：模型响应中没有有效摘要文本");
        String lower = formatted.toLowerCase(Locale.US);
        if (lower.startsWith("api error") || lower.startsWith("error:")) {
            throw new IllegalStateException("压缩失败：" + formatted);
        }
        return formatted;
    }

    static String formatSummary(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("(?is)<analysis>.*?</analysis>", "").trim();
        java.util.regex.Matcher match = java.util.regex.Pattern
            .compile("(?is)<summary>(.*?)</summary>").matcher(value);
        if (match.find()) value = match.group(1).trim();
        value = value.replaceAll("(?is)</?summary>", "").trim();
        value = value.replaceAll("\\n[ \\t]*\\n(?:[ \\t]*\\n)+", "\n\n");
        return value;
    }

    static boolean isPromptTooLong(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getMessage() != null) all.append(' ').append(t.getMessage());
        }
        String s = all.toString().toLowerCase(Locale.US);
        return s.contains("prompt_too_long") || s.contains("context_length_exceeded")
            || s.contains("maximum context length") || s.contains("context window")
            || s.contains("too many tokens") || s.contains("request too large")
            || s.contains("http 413") || s.contains("http 400") && s.contains("token");
    }

    static int roughTokens(JSONArray messages) {
        return roughTokens(messages, 0, messages == null ? 0 : messages.length());
    }

    static int roughTokens(JSONArray messages, int from, int to) {
        if (messages == null) return 0;
        long tokens = 0;
        int start = Math.max(0, from), end = Math.min(messages.length(), Math.max(start, to));
        for (int i = start; i < end; i++) tokens += roughMessageTokens(messages.optJSONObject(i));
        return (int)Math.min(Integer.MAX_VALUE, Math.max(0L, tokens));
    }

    private static int[] cumulativeRoughTokens(JSONArray messages) {
        int length=messages==null?0:messages.length();
        int[] cumulative=new int[length+1];
        long total=0;
        for(int i=0;i<length;i++){
            total=Math.min(Integer.MAX_VALUE,total+roughMessageTokens(messages.optJSONObject(i)));
            cumulative[i+1]=(int)total;
        }
        return cumulative;
    }

    private static int roughMessageTokens(JSONObject message) {
        if(message==null)return 0;
        long tokens=6;
        JSONArray content=message.optJSONArray("content");
        if(content==null)return 6+roughTextTokens(String.valueOf(message.opt("content")));
        for(int j=0;j<content.length();j++){
            JSONObject block=content.optJSONObject(j);if(block==null)continue;
            tokens+=3;String type=block.optString("type","");
            if("text".equals(type))tokens+=roughTextTokens(block.optString("text",""));
            else if("thinking".equals(type))tokens+=roughTextTokens(block.optString("thinking",""));
            else if("tool_use".equals(type)){tokens+=roughTextTokens(block.optString("name",""));tokens+=roughJsonTokens(String.valueOf(block.opt("input")));}
            else if("tool_result".equals(type))tokens+=roughJsonTokens(String.valueOf(block.opt("content")));
            else if("function_call".equals(type)){tokens+=roughTextTokens(block.optString("name",""));tokens+=roughJsonTokens(block.optString("arguments",""));}
            else if("image".equals(type))tokens+=1_200;
            else tokens+=roughTextTokens(block.toString());
        }
        return (int)Math.min(Integer.MAX_VALUE,tokens);
    }

    /** More realistic than chars/3 for Chinese while retaining the usual English chars/4 fallback. */
    static int roughTextTokens(String value) {
        if (value == null || value.isEmpty()) return 0;
        long ascii = 0, nonAscii = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp <= 0x7f) ascii++;
            else if (!Character.isWhitespace(cp)) nonAscii++;
        }
        return (int)Math.min(Integer.MAX_VALUE, (ascii + 3L) / 4L + nonAscii);
    }

    private static int roughJsonTokens(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) return 0;
        return Math.max(roughTextTokens(value), (value.length() + 1) / 2);
    }

    private static JSONArray reduceForSummary(JSONArray prefix, int attempt) throws Exception {
        int textLimit = attempt == 0 ? 30_000 : attempt == 1 ? 14_000 : 8_000;
        int toolLimit = attempt == 0 ? 8_000 : attempt == 1 ? 3_500 : 2_000;
        JSONArray selected = prefix;
        String overflowDigest = "";
        if (attempt >= 2 && prefix.length() > 1) {
            List<Integer> starts = apiRoundStarts(prefix);
            int groupsToDrop = Math.max(1, starts.size() / 5);
            groupsToDrop = Math.min(groupsToDrop, Math.max(0, starts.size() - 1));
            if (groupsToDrop > 0) {
                int dropEnd = starts.get(groupsToDrop);
                overflowDigest = localDigest(prefix, 0, dropEnd, 8_000);
                selected = slice(prefix, dropEnd, prefix.length());
            }
        }

        JSONArray out = new JSONArray();
        if (!overflowDigest.isEmpty()) {
            out.put(message("user", new JSONArray().put(textBlock(
                "<overflow_digest>Exact oldest rounds exceeded the provider request limit. This deterministic digest preserves their recoverable facts:\n"
                    + overflowDigest + "\n</overflow_digest>"))));
        }
        for (int i = 0; i < selected.length(); i++) {
            JSONObject sourceMessage = selected.optJSONObject(i);
            if (sourceMessage == null) continue;
            String role = sourceMessage.optString("role", "user");
            JSONArray sourceContent = sourceMessage.optJSONArray("content");
            JSONArray content = new JSONArray();
            if (sourceContent != null) {
                for (int j = 0; j < sourceContent.length(); j++) {
                    JSONObject block = sourceContent.optJSONObject(j);
                    if (block == null) continue;
                    String type = block.optString("type", "");
                    if ("thinking".equals(type)) continue;
                    if ("image".equals(type)) {
                        content.put(textBlock("[attached image omitted from compaction request]"));
                    } else if ("text".equals(type)) {
                        content.put(textBlock(headTail(block.optString("text", ""), textLimit)));
                    } else if ("tool_result".equals(type)) {
                        JSONObject copy = new JSONObject(block.toString());
                        copy.put("content", headTail(String.valueOf(block.opt("content")), toolLimit));
                        content.put(copy);
                    } else if ("tool_use".equals(type)) {
                        JSONObject copy = new JSONObject(block.toString());
                        String input = String.valueOf(block.opt("input"));
                        if (input.length() > toolLimit) {
                            copy.put("input", new JSONObject().put("_iq_compacted_input", headTail(input, toolLimit)));
                        }
                        content.put(copy);
                    } else {
                        content.put(textBlock("[" + type + " block: " + headTail(block.toString(), toolLimit) + "]"));
                    }
                }
            }
            if (content.length() == 0) content.put(textBlock("[non-text assistant state omitted]"));
            out.put(message(role, content));
        }
        return out;
    }

    private static String compactPrompt(String customInstructions, boolean recentPreserved) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("CRITICAL: Respond with TEXT ONLY. Do not call any tool. Tool calls will be rejected.\n")
            .append("Your entire response must contain an <analysis> drafting block followed by one <summary> block.\n\n")
            .append("Create a detailed, technically accurate continuity summary of the conversation prefix above. ")
            .append(recentPreserved
                ? "Newer messages are preserved verbatim after this summary, so summarize only the prefix you saw.\n"
                : "The summary will replace the conversation shown above.\n")
            .append("In <analysis>, inspect the messages chronologically and verify requests, decisions, code, files, errors, fixes, user corrections, pending work, and exact current state.\n\n")
            .append("In <summary>, use these sections:\n")
            .append("1. Primary Request and Intent\n")
            .append("2. Key Technical Concepts and Decisions\n")
            .append("3. Files and Code Sections (paths, functions, edits, and essential snippets)\n")
            .append("4. Errors, Failed Attempts, and Fixes\n")
            .append("5. Problem Solving and Verified Results\n")
            .append("6. All User Messages (exclude tool results)\n")
            .append("7. Pending Tasks\n")
            .append("8. Current Work and Exact State\n")
            .append("9. Direct Next Step, only if it follows the latest request\n\n")
            .append("Preserve concrete commands, file paths, identifiers, constraints, test results, and unresolved blockers. Do not invent completion. Match the conversation's language where practical.");
        String custom = customInstructions == null ? "" : customInstructions.trim();
        if (!custom.isEmpty()) {
            prompt.append("\n\n<additional_compact_instructions>\n")
                .append(headTail(custom, 6_000))
                .append("\n</additional_compact_instructions>");
        }
        prompt.append("\n\nREMINDER: no tools. Return only <analysis>...</analysis><summary>...</summary>.");
        return prompt.toString();
    }

    private static List<Integer> apiRoundStarts(JSONArray messages) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 1; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (!isUserRequest(message)) continue;
            if (starts.get(starts.size() - 1) != i) starts.add(i);
        }
        return starts;
    }

    private static boolean isUserRequest(JSONObject message) {
        if (message == null || !"user".equals(message.optString("role", ""))) return false;
        JSONArray content = message.optJSONArray("content");
        if (content == null) return false;
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type", "");
            if ("text".equals(type) || "image".equals(type)) return true;
        }
        return false;
    }

    private static String localDigest(JSONArray messages, int from, int to, int maxChars) {
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(messages.length(), to); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String part = digestContent(message.optJSONArray("content"), 1_200);
            if (part.isEmpty()) continue;
            out.append(message.optString("role", "message")).append(": ").append(part).append('\n');
            if (out.length() >= maxChars) break;
        }
        return headTail(out.toString(), maxChars);
    }

    private static String digestContent(JSONArray content, int maxChars) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type", "");
            String value = "";
            if ("text".equals(type)) value = block.optString("text", "");
            else if ("tool_use".equals(type)) value = "tool " + block.optString("name", "") + " " + String.valueOf(block.opt("input"));
            else if ("tool_result".equals(type)) value = "tool result " + String.valueOf(block.opt("content"));
            else if ("image".equals(type)) value = "[attached image]";
            if (value.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(" | ");
            out.append(value.trim());
            if (out.length() >= maxChars) break;
        }
        return headTail(out.toString().replace('\n', ' ').replaceAll("\\s+", " "), maxChars);
    }

    private static String headTail(String value, int maxChars) {
        String s = value == null ? "" : value;
        if (maxChars <= 0 || s.length() <= maxChars) return s;
        int marker = 72;
        int usable = Math.max(16, maxChars - marker);
        int head = (usable * 2) / 3;
        int tail = usable - head;
        return s.substring(0, head) + "\n[… " + (s.length() - head - tail)
            + " chars omitted for compaction request …]\n" + s.substring(s.length() - tail);
    }

    private static JSONObject textBlock(String text) throws Exception {
        return new JSONObject().put("type", "text").put("text", text == null ? "" : text);
    }

    private static JSONObject message(String role, JSONArray content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static JSONArray slice(JSONArray source, int from, int to) throws Exception {
        JSONArray out = new JSONArray();
        if (source == null) return out;
        for (int i = Math.max(0, from); i < Math.min(source.length(), to); i++) {
            out.put(source.getJSONObject(i));
        }
        return out;
    }

    private static JSONArray cloneArray(JSONArray source) throws Exception {
        return source == null ? new JSONArray() : new JSONArray(source.toString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
