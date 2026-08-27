package com.termux.app.iqcode.model;

import org.json.JSONObject;

import java.util.UUID;

/** Persisted API metadata. Secrets are stored separately in AndroidSecretStore. */
public final class ApiProfile {
    public static final int SCHEMA_VERSION = 1;

    public String id;
    public String name;
    public String protocol;
    public String baseUrl;
    public String defaultModel;
    public int revision;
    public int credentialRevision;

    public ApiProfile() {
        id = UUID.randomUUID().toString();
        name = "API 配置";
        protocol = "openai-responses";
        baseUrl = "";
        defaultModel = "gpt-5.6-terra";
        revision = 1;
        credentialRevision = 1;
    }

    public ApiProfile copy() {
        ApiProfile copy = new ApiProfile();
        copy.id = id;
        copy.name = name;
        copy.protocol = protocol;
        copy.baseUrl = baseUrl;
        copy.defaultModel = defaultModel;
        copy.revision = revision;
        copy.credentialRevision = credentialRevision;
        return copy;
    }

    public JSONObject toJson() {
        try {
            return new JSONObject()
                .put("schema_version", SCHEMA_VERSION)
                .put("id", id)
                .put("name", name)
                .put("protocol", protocol)
                .put("base_url", baseUrl)
                .put("default_model", defaultModel)
                .put("revision", revision)
                .put("credential_revision", credentialRevision);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode API profile", e);
        }
    }

    public static ApiProfile fromJson(JSONObject json) {
        ApiProfile profile = new ApiProfile();
        profile.id = clean(json.optString("id", profile.id));
        profile.name = clean(json.optString("name", profile.name));
        profile.protocol = clean(json.optString("protocol", profile.protocol));
        profile.baseUrl = clean(json.optString("base_url", profile.baseUrl));
        profile.defaultModel = clean(json.optString("default_model", profile.defaultModel));
        profile.revision = Math.max(1, json.optInt("revision", profile.revision));
        profile.credentialRevision = Math.max(1, json.optInt("credential_revision", profile.credentialRevision));
        if (profile.id.isEmpty()) profile.id = UUID.randomUUID().toString();
        if (profile.name.isEmpty()) profile.name = "API 配置";
        if (profile.protocol.isEmpty()) profile.protocol = "openai-responses";
        return profile;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
