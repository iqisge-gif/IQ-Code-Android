package com.termux.app.iqcode.api;

import com.termux.app.iqcode.model.AssistantTurn;
import com.termux.app.iqcode.model.SessionConfig;

import org.json.JSONArray;
import org.json.JSONObject;

public interface ModelProvider {
    final class StreamFailure extends java.io.IOException {
        public final String code;
        public StreamFailure(String code, String message) { super(message); this.code = code == null ? "" : code; }
        public StreamFailure(String code, String message, Throwable cause) { super(message, cause); this.code = code == null ? "" : code; }
    }

    interface StreamListener {
        void onTextDelta(String text);
        void onThinkingDelta(String thinking);
        void onToolInputDelta(String toolUseId, String toolName, String partialJson);
        void onUsage(long inputTokens, long outputTokens);
    }

    AssistantTurn createMessage(
        SessionConfig config,
        String systemPrompt,
        JSONArray messages,
        JSONArray tools,
        StreamListener listener
    ) throws Exception;

    /** Abort the active request owned by the supplied worker thread, if this transport can. */
    default void cancelRequest(Thread worker) { }
}
