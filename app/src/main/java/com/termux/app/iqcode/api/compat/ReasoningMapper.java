package com.termux.app.iqcode.api.compat;

import java.util.Locale;

/**
 * Java port of src/services/api/compat/reasoning.ts from the supplied IQ Code tree.
 *
 * Important wire invariant for Responses/Codex: max and ultra are never silently
 * downgraded. xxhigh is the one intentional local alias and is sent as xhigh.
 */
public final class ReasoningMapper {
    private ReasoningMapper() {}

    public static String normalizeRequestedEffort(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String numericEffort(long value) {
        if (value <= 0) return null;
        if (value <= 2048) return "minimal";
        if (value <= 4096) return "low";
        if (value <= 8192) return "medium";
        if (value <= 16384) return "high";
        return "xhigh";
    }

    private static Long parseNumeric(String value) {
        if (value == null || value.isEmpty()) return null;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return null;
        }
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    /** OpenAI Chat-compatible effort. ultra/max/xxhigh collapse to xhigh. */
    public static String openAIChatEffort(String value) {
        String v = normalizeRequestedEffort(value);
        Long numeric = parseNumeric(v);
        if (numeric != null) return numericEffort(numeric);
        if ("minimal".equals(v) || "low".equals(v) || "medium".equals(v) || "high".equals(v)) return v;
        if ("xhigh".equals(v) || "xxhigh".equals(v) || "max".equals(v) || "ultra".equals(v)) return "xhigh";
        return null;
    }

    /**
     * OpenAI Responses configured effort before the final wire alias.
     * Unlike OpenAI Chat, arbitrary non-empty future values are preserved.
     */
    public static String openAIResponsesConfiguredEffort(String value) {
        String v = normalizeRequestedEffort(value);
        Long numeric = parseNumeric(v);
        if (numeric != null) return numeric <= 0 ? "none" : numericEffort(numeric);
        if (v == null || "auto".equals(v) || "adaptive".equals(v)) return null;
        return v;
    }

    /**
     * Exact, single-shot Responses wire effort. max stays max, ultra stays ultra;
     * xxhigh alone aliases to xhigh. There are no fallback candidates.
     */
    public static String openAIResponsesWireEffort(String value) {
        String configured = openAIResponsesConfiguredEffort(value);
        if (configured == null) return null;
        return "xxhigh".equals(configured) ? "xhigh" : configured;
    }

    /** Anthropic effort mapping from the supplied compatibility layer. */
    public static String anthropicEffort(String value) {
        String v = normalizeRequestedEffort(value);
        Long numeric = parseNumeric(v);
        if (numeric != null) {
            String mapped = numericEffort(numeric);
            return "minimal".equals(mapped) ? "low" : mapped;
        }
        if ("minimal".equals(v)) return "low";
        if ("low".equals(v) || "medium".equals(v) || "high".equals(v) || "xhigh".equals(v)) return v;
        if ("xxhigh".equals(v)) return "xhigh";
        if ("max".equals(v) || "ultra".equals(v)) return "max";
        return null;
    }

    /** Gemini thinking level mapping from the supplied compatibility layer. */
    public static String geminiThinkingLevel(String value) {
        String v = normalizeRequestedEffort(value);
        Long numeric = parseNumeric(v);
        if (numeric != null) {
            String mapped = numericEffort(numeric);
            return "xhigh".equals(mapped) ? "high" : mapped;
        }
        if ("minimal".equals(v) || "low".equals(v) || "medium".equals(v) || "high".equals(v)) return v;
        if ("xhigh".equals(v) || "xxhigh".equals(v) || "max".equals(v) || "ultra".equals(v)) return "high";
        return null;
    }

    public static Integer defaultBudgetTokens(String value) {
        String v = normalizeRequestedEffort(value);
        Long numeric = parseNumeric(v);
        if (numeric != null) {
            if (numeric > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return Math.max(0, numeric.intValue());
        }
        if ("minimal".equals(v)) return 1024;
        if ("low".equals(v)) return 4096;
        if ("medium".equals(v)) return 8192;
        if ("high".equals(v)) return 12288;
        if ("xhigh".equals(v)) return 16384;
        if ("xxhigh".equals(v) || "max".equals(v) || "ultra".equals(v)) return 32768;
        return null;
    }
}
