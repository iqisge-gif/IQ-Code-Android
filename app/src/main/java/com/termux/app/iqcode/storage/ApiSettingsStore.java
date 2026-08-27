package com.termux.app.iqcode.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.iqcode.model.ApiProfile;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.security.AndroidSecretStore;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Persists global preferences plus secret-free API Profile metadata. */
public final class ApiSettingsStore {
    private static final String PREFS = "iq_code_android_settings";
    private static final int COMPACTION_LOGIC_VERSION = 2;
    private static final int API_ENDPOINT_CONFIG_VERSION = 1;
    private static final String PROFILES = "api_profiles_v1";
    private static final String ACTIVE_PROFILE = "active_api_profile_id";
    private static final String MIGRATED_PROFILE = "legacy_api_profile_id";
    private static final String ROLE_CARDS = "role_cards_v1";
    private static final String ACTIVE_ROLE_CARD = "active_role_card_id";
    public static final String OFFICIAL_PROFILE_ID = "iqcode-official";
    public static final String OFFICIAL_BASE_URL = "https://api.ginka.cloud/";
    public static final String OFFICIAL_SIGNUP_URL = "https://api.ginka.cloud/sign-up?aff=caHN";
    public static final String OFFICIAL_DEFAULT_MODEL = "gpt-5.6-sol";
    private final SharedPreferences prefs;
    private final AndroidSecretStore secrets;

    public ApiSettingsStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secrets = new AndroidSecretStore(app);
    }

    public synchronized SessionConfig load() {
        SessionConfig c = loadGlobal();
        String legacyKey = secrets.getApiKey();
        List<ApiProfile> profiles = loadProfilesInternal();
        if (profiles.isEmpty()) profiles = migrateLegacyProfile(c, legacyKey);
        profiles = normalizeProfiles(profiles);
        String activeId = prefs.getString(ACTIVE_PROFILE, "");
        ApiProfile active = findProfile(profiles, activeId);
        if (active == null) {
            active = profiles.get(0);
            prefs.edit().putString(ACTIVE_PROFILE, active.id).apply();
        }
        applyProfile(c, active, apiKeyFor(active));
        return c;
    }

    public synchronized List<ApiProfile> getProfiles() {
        SessionConfig legacy = loadGlobal();
        List<ApiProfile> profiles = loadProfilesInternal();
        if (profiles.isEmpty()) profiles = migrateLegacyProfile(legacy, secrets.getApiKey());
        profiles = normalizeProfiles(profiles);
        ArrayList<ApiProfile> copies = new ArrayList<>();
        for (ApiProfile profile : profiles) copies.add(profile.copy());
        return copies;
    }

    public synchronized ApiProfile getActiveProfile() {
        List<ApiProfile> profiles = getProfiles();
        ApiProfile active = findProfile(profiles, prefs.getString(ACTIVE_PROFILE, ""));
        return active == null ? profiles.get(0) : active;
    }

    public synchronized ApiProfile getProfile(String profileId) {
        ApiProfile profile=findProfile(getProfiles(),profileId);
        return profile==null?null:profile.copy();
    }

    public synchronized String getActiveProfileId() { return getActiveProfile().id; }

    public synchronized void selectProfile(String profileId) {
        if (findProfile(getProfiles(), profileId) == null) throw new IllegalArgumentException("Unknown API profile");
        prefs.edit().putString(ACTIVE_PROFILE, profileId).apply();
    }

    public synchronized SessionConfig resolveProfile(String profileId, SessionConfig base) {
        ApiProfile profile = findProfile(getProfiles(), profileId);
        if (profile == null) return null;
        SessionConfig resolved = base == null ? loadGlobal() : base.copy();
        applyProfile(resolved, profile, apiKeyFor(profile));
        return resolved;
    }

    public synchronized ApiProfile saveProfile(ApiProfile draft, String apiKey, boolean replaceKey) throws Exception {
        if (draft == null) throw new IllegalArgumentException("Profile is required");
        List<ApiProfile> profiles = getProfiles();
        ApiProfile existing = findProfile(profiles, draft.id);
        ApiProfile saved = draft.copy();
        if (saved.id == null || saved.id.trim().isEmpty()) saved.id = new ApiProfile().id;
        saved.name = nonEmpty(saved.name, "API 配置");
        saved.protocol = nonEmpty(saved.protocol, "openai-responses");
        saved.baseUrl = cleanBaseUrl(saved.baseUrl);
        saved.defaultModel = nonEmpty(saved.defaultModel, "gpt-5.6-terra");
        if (existing == null) {
            saved.revision = 1;
            saved.credentialRevision = 1;
            profiles.add(saved);
        } else {
            saved.revision = existing.revision;
            saved.credentialRevision = existing.credentialRevision;
            if (!sameProfileEndpoint(existing, saved)) saved.revision++;
            for (int i = 0; i < profiles.size(); i++) if (existing.id.equals(profiles.get(i).id)) { profiles.set(i, saved); break; }
        }
        if (OFFICIAL_PROFILE_ID.equals(saved.id)) {
            saved.name="IQ Code 官方 API";
            saved.baseUrl=OFFICIAL_BASE_URL;
            saved.defaultModel=OFFICIAL_DEFAULT_MODEL;
        }
        if (replaceKey) {
            if (existing != null) saved.credentialRevision = existing.credentialRevision + 1;
            secrets.setApiKey(saved.id, saved.credentialRevision, apiKey == null ? "" : apiKey);
        }
        saveProfilesInternal(profiles, prefs.getString(ACTIVE_PROFILE, saved.id).isEmpty() ? saved.id : prefs.getString(ACTIVE_PROFILE, saved.id));
        return saved.copy();
    }

    public synchronized void deleteProfile(String profileId) {
        if (OFFICIAL_PROFILE_ID.equals(profileId)) throw new IllegalStateException("官方 API 配置不能删除");
        List<ApiProfile> profiles = getProfiles();
        if (profiles.size() <= 1) throw new IllegalStateException("At least one API profile is required");
        ApiProfile profile = findProfile(profiles, profileId);
        if (profile == null) return;
        profiles.remove(profile);
        secrets.removeApiKey(profile.id, profile.credentialRevision);
        String active = prefs.getString(ACTIVE_PROFILE, "");
        if (profile.id.equals(active)) active = profiles.get(0).id;
        saveProfilesInternal(profiles, active);
    }

    public synchronized File getLastSessionFile() {
        String path = prefs.getString("last_session_path", "");
        if (path == null || path.trim().isEmpty()) return null;
        File f = new File(path);
        return f.isFile() ? f : null;
    }

    public synchronized void setLastSessionFile(File file) {
        prefs.edit().putString("last_session_path", file == null ? "" : file.getAbsolutePath()).apply();
    }

    /** Saves global settings and updates the selected Profile without storing a secret in preferences. */
    public synchronized void save(SessionConfig c) throws Exception {
        c.customSystemPrompt = sanitizeCustomSystemPrompt(c.customSystemPrompt);
        List<ApiProfile> profiles = getProfiles();
        ApiProfile profile = findProfile(profiles, c.profileId);
        if (profile == null) profile = findProfile(profiles, prefs.getString(ACTIVE_PROFILE, ""));
        if (profile == null) profile = profiles.get(0);
        ApiProfile updated = profile.copy();
        updated.protocol = nonEmpty(c.protocol, updated.protocol);
        updated.baseUrl = cleanBaseUrl(c.baseUrl);
        updated.defaultModel = nonEmpty(c.model, updated.defaultModel);
        if (OFFICIAL_PROFILE_ID.equals(updated.id)) {
            updated.name="IQ Code 官方 API";
            updated.baseUrl=OFFICIAL_BASE_URL;
            updated.defaultModel=OFFICIAL_DEFAULT_MODEL;
        }
        if (!sameProfileEndpoint(profile, updated)) updated.revision++;
        String oldKey = apiKeyFor(profile);
        String newKey = c.apiKey == null ? "" : c.apiKey;
        if (!newKey.equals(oldKey)) {
            updated.credentialRevision = profile.credentialRevision + 1;
            secrets.setApiKey(updated.id, updated.credentialRevision, newKey);
        }
        for (int i = 0; i < profiles.size(); i++) if (profile.id.equals(profiles.get(i).id)) { profiles.set(i, updated); break; }
        saveProfilesInternal(profiles, updated.id);
        applyProfile(c, updated, apiKeyFor(updated));
        saveGlobal(c);
    }

    private SessionConfig loadGlobal() {
        SessionConfig c = new SessionConfig();
        c.protocol = prefs.getString("protocol", c.protocol);
        String savedBaseUrl = cleanBaseUrl(prefs.getString("base_url", c.baseUrl));
        int endpointConfigVersion = prefs.getInt("api_endpoint_config_version", 0);
        if (endpointConfigVersion < API_ENDPOINT_CONFIG_VERSION && isLegacyBundledBaseUrl(savedBaseUrl)) savedBaseUrl = "";
        c.baseUrl = savedBaseUrl;
        if (endpointConfigVersion < API_ENDPOINT_CONFIG_VERSION) prefs.edit().putString("base_url", c.baseUrl).putInt("api_endpoint_config_version", API_ENDPOINT_CONFIG_VERSION).apply();
        c.model = prefs.getString("model", c.model);
        c.visionEnabled = prefs.getBoolean("vision_enabled", c.visionEnabled);
        c.effort = prefs.getString("effort", c.effort);
        c.customSystemPrompt = sanitizeCustomSystemPrompt(prefs.getString("custom_system_prompt", ""));
        c.roleCard = sanitizeCustomSystemPrompt(prefs.getString("role_card", ""));
        c.reasoningSummary = prefs.getString("reasoning_summary", c.reasoningSummary);
        c.preserveReasoningState = prefs.getBoolean("preserve_reasoning_state", c.preserveReasoningState);
        c.toolMode = prefs.getString("tool_mode", c.toolMode);
        c.permissionMode = prefs.getString("permission_mode", c.permissionMode);
        c.sandboxAgentFullAccess = prefs.getBoolean("sandbox_agent_full_access", c.sandboxAgentFullAccess);
        c.rootExecutionEnabled = prefs.getBoolean("root_execution_enabled", c.rootExecutionEnabled);
        c.forcedKeepAliveEnabled = prefs.getBoolean("forced_keep_alive_enabled", c.forcedKeepAliveEnabled);
        c.projectDirectory = prefs.getString("project_directory", TermuxConstants.TERMUX_HOME_DIR_PATH);
        c.maxTokens = prefs.getInt("max_tokens", c.maxTokens);
        c.contextWindowTokens = prefs.getInt("context_window_tokens", c.contextWindowTokens);
        c.autoCompact = prefs.getBoolean("auto_compact", c.autoCompact);
        c.autoCompactRatio = Double.longBitsToDouble(prefs.getLong("auto_compact_ratio", Double.doubleToRawLongBits(c.autoCompactRatio)));
        int compactVersion = prefs.getInt("context_compaction_logic_version", 0);
        if (compactVersion < COMPACTION_LOGIC_VERSION && Math.abs(c.autoCompactRatio - 0.78d) < 0.000001d) c.autoCompactRatio = 1.0d;
        if (compactVersion < COMPACTION_LOGIC_VERSION) prefs.edit().putInt("context_compaction_logic_version", COMPACTION_LOGIC_VERSION).putLong("auto_compact_ratio", Double.doubleToRawLongBits(c.autoCompactRatio)).apply();
        c.webSearchEnabled = prefs.getBoolean("web_search_enabled", c.webSearchEnabled);
        c.webSearchProvider = prefs.getString("web_search_provider", c.webSearchProvider);
        c.webSearchMaxResults = prefs.getInt("web_search_max_results", c.webSearchMaxResults);
        c.webFetchMaxChars = prefs.getInt("web_fetch_max_chars", c.webFetchMaxChars);
        c.webTimeoutMs = prefs.getInt("web_timeout_ms", c.webTimeoutMs);
        return c;
    }

    private void saveGlobal(SessionConfig c) {
        prefs.edit()
            .putString("protocol", c.protocol)
            .putString("base_url", cleanBaseUrl(c.baseUrl))
            .putInt("api_endpoint_config_version", API_ENDPOINT_CONFIG_VERSION)
            .putString("model", c.model)
            .putBoolean("vision_enabled", c.visionEnabled)
            .putString("effort", c.effort)
            .putString("custom_system_prompt", sanitizeCustomSystemPrompt(c.customSystemPrompt))
            .putString("role_card", sanitizeCustomSystemPrompt(c.roleCard))
            .putString("reasoning_summary", c.reasoningSummary)
            .putBoolean("preserve_reasoning_state", c.preserveReasoningState)
            .putString("tool_mode", c.toolMode)
            .putString("permission_mode", c.permissionMode)
            .putBoolean("sandbox_agent_full_access", c.sandboxAgentFullAccess)
            .putBoolean("root_execution_enabled", c.rootExecutionEnabled)
            .putBoolean("forced_keep_alive_enabled", c.forcedKeepAliveEnabled)
            .putString("project_directory", c.projectDirectory)
            .putInt("max_tokens", c.maxTokens)
            .putInt("context_window_tokens", c.contextWindowTokens)
            .putBoolean("auto_compact", c.autoCompact)
            .putLong("auto_compact_ratio", Double.doubleToRawLongBits(c.autoCompactRatio))
            .putInt("context_compaction_logic_version", COMPACTION_LOGIC_VERSION)
            .putBoolean("web_search_enabled", c.webSearchEnabled)
            .putString("web_search_provider", c.webSearchProvider)
            .putInt("web_search_max_results", c.webSearchMaxResults)
            .putInt("web_fetch_max_chars", c.webFetchMaxChars)
            .putInt("web_timeout_ms", c.webTimeoutMs)
            .apply();
    }

    private List<ApiProfile> migrateLegacyProfile(SessionConfig legacy, String legacyKey) {
        ArrayList<ApiProfile> profiles = new ArrayList<>();
        profiles.add(officialProfile());
        String legacyUrl=cleanBaseUrl(legacy.baseUrl);
        if(!legacyUrl.isEmpty()||(legacyKey!=null&&!legacyKey.isEmpty())) {
            ApiProfile profile = new ApiProfile();
            profile.name = "原 API 配置";
            profile.protocol = nonEmpty(legacy.protocol, profile.protocol);
            profile.baseUrl = legacyUrl;
            profile.defaultModel = nonEmpty(legacy.model, profile.defaultModel);
            try { if (!legacyKey.isEmpty()) secrets.setApiKey(profile.id, profile.credentialRevision, legacyKey); } catch (Exception ignored) { }
            profiles.add(profile);
            prefs.edit().putString(MIGRATED_PROFILE, profile.id).apply();
        }
        saveProfilesInternal(profiles, OFFICIAL_PROFILE_ID);
        return profiles;
    }

    private ApiProfile officialProfile() {
        ApiProfile profile=new ApiProfile();
        profile.id=OFFICIAL_PROFILE_ID;profile.name="IQ Code 官方 API";
        profile.protocol="openai-responses";profile.baseUrl=OFFICIAL_BASE_URL;profile.defaultModel=OFFICIAL_DEFAULT_MODEL;
        return profile;
    }

    private List<ApiProfile> normalizeProfiles(List<ApiProfile> source) {
        ArrayList<ApiProfile> normalized=new ArrayList<>();
        ApiProfile official=findProfile(source,OFFICIAL_PROFILE_ID);
        if(official==null)official=officialProfile();else{
            official=official.copy();official.name="IQ Code 官方 API";
            official.protocol=nonEmpty(official.protocol,"openai-responses");official.baseUrl=OFFICIAL_BASE_URL;official.defaultModel=OFFICIAL_DEFAULT_MODEL;
        }
        normalized.add(official);
        for(ApiProfile profile:source)if(!OFFICIAL_PROFILE_ID.equals(profile.id))normalized.add(profile);
        String active=prefs.getString(ACTIVE_PROFILE,OFFICIAL_PROFILE_ID);
        if(findProfile(normalized,active)==null)active=OFFICIAL_PROFILE_ID;
        saveProfilesInternal(normalized,active);
        return normalized;
    }

    private void applyProfile(SessionConfig c, ApiProfile profile, String apiKey) {
        c.profileId = profile.id;
        c.profileRevision = profile.revision;
        c.credentialRevision = profile.credentialRevision;
        c.protocol = profile.protocol;
        c.baseUrl = profile.baseUrl;
        c.model = profile.defaultModel;
        c.apiKey = apiKey;
    }

    private String apiKeyFor(ApiProfile profile) {
        String key = secrets.getApiKey(profile.id, profile.credentialRevision);
        if (!key.isEmpty()) return key;
        return profile.id.equals(prefs.getString(MIGRATED_PROFILE, "")) ? secrets.getApiKey() : "";
    }

    private List<ApiProfile> loadProfilesInternal() {
        ArrayList<ApiProfile> profiles = new ArrayList<>();
        String raw = prefs.getString(PROFILES, "");
        if (raw == null || raw.trim().isEmpty()) return profiles;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                ApiProfile profile = ApiProfile.fromJson(json);
                if (findProfile(profiles, profile.id) == null) profiles.add(profile);
            }
        } catch (Exception ignored) { }
        return profiles;
    }

    private void saveProfilesInternal(List<ApiProfile> profiles, String activeId) {
        JSONArray array = new JSONArray();
        for (ApiProfile profile : profiles) array.put(profile.toJson());
        prefs.edit().putString(PROFILES, array.toString()).putString(ACTIVE_PROFILE, activeId).apply();
    }

    public synchronized JSONArray getRoleCards() {
        try { return new JSONArray(prefs.getString(ROLE_CARDS, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    public synchronized void saveRoleCards(JSONArray cards, String activeId) {
        prefs.edit().putString(ROLE_CARDS, cards == null ? "[]" : cards.toString()).putString(ACTIVE_ROLE_CARD, activeId == null ? "" : activeId).apply();
    }

    public synchronized String getActiveRoleCardId() { return prefs.getString(ACTIVE_ROLE_CARD, ""); }

    private static String sanitizeCustomSystemPrompt(String value) {
        String input=value==null?"":value.replace("\r\n","\n").replace('\r','\n');
        if(input.indexOf('\0')>=0)throw new IllegalArgumentException("自定义提示词不能包含 NUL 字符");
        StringBuilder clean=new StringBuilder();int codePoints=0;
        for(int i=0;i<input.length();){
            int cp=input.codePointAt(i);int width=Character.charCount(cp);
            if(Character.isISOControl(cp)&&cp!='\n'&&cp!='\t')throw new IllegalArgumentException("自定义提示词包含不支持的控制字符");
            clean.appendCodePoint(cp);i+=width;
        }
        return clean.toString().trim();
    }

    private static ApiProfile findProfile(List<ApiProfile> profiles, String id) {
        if (id == null) return null;
        for (ApiProfile profile : profiles) if (id.equals(profile.id)) return profile;
        return null;
    }

    private static boolean sameProfileEndpoint(ApiProfile a, ApiProfile b) {
        return a.protocol.equals(b.protocol) && a.baseUrl.equals(b.baseUrl) && a.defaultModel.equals(b.defaultModel);
    }

    private static String cleanBaseUrl(String value) { return value == null ? "" : value.trim(); }
    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }

    private static boolean isLegacyBundledBaseUrl(String value) {
        String lower = cleanBaseUrl(value).toLowerCase(java.util.Locale.US);
        return lower.equals("http://ctoken.top") || lower.startsWith("http://ctoken.top/")
            || lower.equals("https://ctoken.top") || lower.startsWith("https://ctoken.top/");
    }
}
