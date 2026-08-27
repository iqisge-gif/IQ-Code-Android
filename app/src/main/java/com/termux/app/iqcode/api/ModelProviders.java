package com.termux.app.iqcode.api;

import com.termux.app.iqcode.model.SessionConfig;

/** Selects a Java-native wire provider. No CLI subprocess is involved. */
public final class ModelProviders {
    private ModelProviders() {}

    public static ModelProvider forConfig(SessionConfig config) {
        String protocol = config == null || config.protocol == null ? "anthropic" : config.protocol;
        if ("openai-responses".equals(protocol) || "codex-responses".equals(protocol)) {
            return new OpenAIResponsesProvider();
        }
        if ("openai-compatible".equals(protocol) || "openai-chat".equals(protocol)) {
            return new OpenAIChatCompletionsProvider();
        }
        if ("anthropic".equals(protocol)) return new AnthropicMessagesProvider();
        throw new IllegalArgumentException("Protocol is not Java-ported yet: " + protocol);
    }
}
