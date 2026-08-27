package com.termux.app.iqcode.api;

import com.termux.app.iqcode.model.SessionConfig;

import java.util.Locale;

/** Builds endpoints only on the user-configured API host. */
public final class ApiEndpointResolver {
    private ApiEndpointResolver() { }

    public static String modelCatalogEndpoint(SessionConfig config) {
        String base = stripTrailingSlash(ApiUrlPolicy.requireBaseUrl(config));
        String protocol = config == null || config.protocol == null ? "" : config.protocol;
        if ("anthropic".equals(protocol)) return appendV1(base, "models");
        if ("openai-chat".equals(protocol) || "openai-responses".equals(protocol)) return appendV1(base, "models");
        return "";
    }

    public static String stripTrailingSlash(String base) {
        String out = base == null ? "" : base.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String appendV1(String base, String path) {
        String lower = base.toLowerCase(Locale.US);
        return lower.endsWith("/v1") ? base + "/" + path : base + "/v1/" + path;
    }
}
