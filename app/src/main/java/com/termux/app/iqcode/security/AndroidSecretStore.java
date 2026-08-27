package com.termux.app.iqcode.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small Android Keystore wrapper for API tokens. */
public final class AndroidSecretStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "iq_code_android_api_key_v1";
    private static final String PREFS = "iq_code_android_secrets";
    private static final String VALUE = "api_key_ciphertext";
    private static final String IV = "api_key_iv";

    private final Context context;

    public AndroidSecretStore(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void setApiKey(String value) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(VALUE).remove(IV).apply();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        prefs.edit()
            .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .apply();
    }

    public synchronized String getApiKey() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String encoded = prefs.getString(VALUE, null);
            String ivEncoded = prefs.getString(IV, null);
            if (encoded == null || ivEncoded == null) return "";
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(ivEncoded, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized void setApiKey(String profileId, int credentialRevision, String value) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String slot = profileSlot(profileId, credentialRevision);
        String valueKey = "profile_" + slot + "_ciphertext";
        String ivKey = "profile_" + slot + "_iv";
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(valueKey).remove(ivKey).apply();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        prefs.edit()
            .putString(valueKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .apply();
    }

    public synchronized String getApiKey(String profileId, int credentialRevision) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String slot = profileSlot(profileId, credentialRevision);
            String encoded = prefs.getString("profile_" + slot + "_ciphertext", null);
            String ivEncoded = prefs.getString("profile_" + slot + "_iv", null);
            if (encoded == null || ivEncoded == null) return "";
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(ivEncoded, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized void removeApiKey(String profileId, int credentialRevision) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String slot = profileSlot(profileId, credentialRevision);
        prefs.edit().remove("profile_" + slot + "_ciphertext").remove("profile_" + slot + "_iv").apply();
    }

    private static String profileSlot(String profileId, int credentialRevision) {
        String raw = (profileId == null ? "" : profileId) + "@" + Math.max(1, credentialRevision);
        return Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
