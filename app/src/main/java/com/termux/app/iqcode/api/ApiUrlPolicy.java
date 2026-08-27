package com.termux.app.iqcode.api;

import com.termux.app.iqcode.model.SessionConfig;

import java.util.Locale;

/** Validates user-owned API endpoint configuration without choosing or rewriting a host. */
final class ApiUrlPolicy {
    private ApiUrlPolicy() {}

    static String requireBaseUrl(SessionConfig config) {
        String base = config == null || config.baseUrl == null ? "" : config.baseUrl.trim();
        if (base.isEmpty()) {
            throw new IllegalStateException("API 地址未配置，请在设置中填写 Base URL");
        }
        String lower = base.toLowerCase(Locale.US);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new IllegalStateException("Base URL 必须以 https:// 或 http:// 开头");
        }
        return base;
    }
}
