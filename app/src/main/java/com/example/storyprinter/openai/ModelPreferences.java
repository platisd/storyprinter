package com.example.storyprinter.openai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Stores and resolves the user's preferred AI models.
 * Uses the same SharedPreferences file as {@link OpenAiKeyStore}.
 */
public final class ModelPreferences {

    private static final String PREFS_NAME = "openai_settings";

    private static final String KEY_TEXT_MODEL = "text_model";
    private static final String KEY_IMAGE_ORCHESTRATION_MODEL = "image_orchestration_model";
    private static final String KEY_IMAGE_TOOL_MODEL = "image_tool_model";

    public static final String DEFAULT_TEXT_MODEL = "gpt-4.1-mini";
    public static final String DEFAULT_IMAGE_ORCHESTRATION_MODEL = "gpt-4.1-mini";
    public static final String DEFAULT_IMAGE_TOOL_MODEL = "gpt-image-1-mini";

    private ModelPreferences() {
        // no instances
    }

    @NonNull
    public static String getTextModel(@NonNull Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TEXT_MODEL, DEFAULT_TEXT_MODEL);
        return v == null || v.trim().isEmpty() ? DEFAULT_TEXT_MODEL : v.trim();
    }

    public static void setTextModel(@NonNull Context context, @NonNull String model) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TEXT_MODEL, model.trim())
                .apply();
    }

    @NonNull
    public static String getImageOrchestrationModel(@NonNull Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_IMAGE_ORCHESTRATION_MODEL, DEFAULT_IMAGE_ORCHESTRATION_MODEL);
        return v == null || v.trim().isEmpty() ? DEFAULT_IMAGE_ORCHESTRATION_MODEL : v.trim();
    }

    public static void setImageOrchestrationModel(@NonNull Context context, @NonNull String model) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_IMAGE_ORCHESTRATION_MODEL, model.trim())
                .apply();
    }

    @NonNull
    public static String getImageToolModel(@NonNull Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_IMAGE_TOOL_MODEL, DEFAULT_IMAGE_TOOL_MODEL);
        return v == null || v.trim().isEmpty() ? DEFAULT_IMAGE_TOOL_MODEL : v.trim();
    }

    public static void setImageToolModel(@NonNull Context context, @NonNull String model) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_IMAGE_TOOL_MODEL, model.trim())
                .apply();
    }
}
