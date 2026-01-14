package com.example.storyprinter.openai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.example.storyprinter.BuildConfig;

/**
 * Stores and resolves the OpenAI API key.
 *
 * Resolution order:
 * 1) User-provided key stored in app-private storage (persists across reboots/force stop)
 * 2) BuildConfig.OPENAI_API_KEY (optional; typically injected from local.properties)
 */
public final class OpenAiKeyStore {

    private static final String PREFS_NAME = "openai_settings";
    private static final String KEY_USER_API_KEY = "user_openai_api_key";

    private OpenAiKeyStore() {
        // no instances
    }

    @NonNull
    public static String getEffectiveApiKey(@NonNull Context context) {
        String userKey = getUserApiKey(context);
        if (!userKey.isEmpty()) return userKey;

        String buildKey = BuildConfig.OPENAI_API_KEY;
        return buildKey != null ? buildKey.trim() : "";
    }

    @NonNull
    public static String getUserApiKey(@NonNull Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_USER_API_KEY, "");
        return v == null ? "" : v.trim();
    }

    public static boolean hasUserApiKey(@NonNull Context context) {
        return !getUserApiKey(context).isEmpty();
    }

    public static void setUserApiKey(@NonNull Context context, @NonNull String apiKey) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_API_KEY, apiKey.trim())
                .apply();
    }

    public static void clearUserApiKey(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_USER_API_KEY)
                .apply();
    }
}
