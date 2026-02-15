package com.example.storyprinter;

import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.storyprinter.openai.ModelPreferences;
import com.example.storyprinter.openai.OpenAiKeyStore;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class SettingsActivity extends AppCompatActivity {

    private TextInputLayout tilApiKey;
    private TextInputEditText etApiKey;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_settings), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbarSettings);
        toolbar.setNavigationOnClickListener(v -> finish());

        tilApiKey = findViewById(R.id.tilApiKey);
        etApiKey = findViewById(R.id.etApiKey);

        // Make the key entry behave like a password field (but still editable/copyable).
        if (etApiKey != null) {
            etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }

        MaterialButton btnSave = findViewById(R.id.btnSaveKey);
        MaterialButton btnClear = findViewById(R.id.btnClearKey);

        // Pre-fill with stored key if present (never auto-fill BuildConfig key).
        String stored = OpenAiKeyStore.getUserApiKey(this);
        if (!stored.isEmpty() && etApiKey != null) {
            etApiKey.setText(stored);
        }

        btnSave.setOnClickListener(v -> {
            String key = etApiKey != null && etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
            if (key.isEmpty()) {
                tilApiKey.setError(getString(R.string.settings_error_key_required));
                return;
            }
            tilApiKey.setError(null);
            OpenAiKeyStore.setUserApiKey(this, key);
            Snackbar.make(findViewById(R.id.main_settings), R.string.settings_key_saved, Snackbar.LENGTH_LONG).show();
        });

        btnClear.setOnClickListener(v -> {
            OpenAiKeyStore.clearUserApiKey(this);
            if (etApiKey != null) etApiKey.setText("");
            tilApiKey.setError(null);
            Snackbar.make(findViewById(R.id.main_settings), R.string.settings_key_cleared, Snackbar.LENGTH_LONG).show();
        });

        // Small hint if neither a stored key nor BuildConfig fallback exists.
        if (OpenAiKeyStore.getEffectiveApiKey(this).isEmpty()) {
            Snackbar.make(findViewById(R.id.main_settings), R.string.settings_warning_no_key, Snackbar.LENGTH_LONG).show();
        }

        setupModelDropdowns();
    }

    private void setupModelDropdowns() {
        String[] textModels = {"gpt-4.1-nano", "gpt-4.1-mini", "gpt-5-nano", "gpt-5-mini"};
        String[] imageRenderModels = {"gpt-image-1-mini", "gpt-image-1", "gpt-image-1.5"};

        // Text model dropdown
        AutoCompleteTextView actvText = findViewById(R.id.actvTextModel);
        if (actvText != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, textModels);
            actvText.setAdapter(adapter);
            actvText.setText(ModelPreferences.getTextModel(this), false);
            actvText.setOnItemClickListener((parent, view, position, id) ->
                    ModelPreferences.setTextModel(this, textModels[position]));
        }

        // Image orchestration model dropdown
        AutoCompleteTextView actvOrch = findViewById(R.id.actvImageOrchModel);
        if (actvOrch != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, textModels);
            actvOrch.setAdapter(adapter);
            actvOrch.setText(ModelPreferences.getImageOrchestrationModel(this), false);
            actvOrch.setOnItemClickListener((parent, view, position, id) ->
                    ModelPreferences.setImageOrchestrationModel(this, textModels[position]));
        }

        // Image rendering model dropdown
        AutoCompleteTextView actvRender = findViewById(R.id.actvImageRenderModel);
        if (actvRender != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, imageRenderModels);
            actvRender.setAdapter(adapter);
            actvRender.setText(ModelPreferences.getImageToolModel(this), false);
            actvRender.setOnItemClickListener((parent, view, position, id) ->
                    ModelPreferences.setImageToolModel(this, imageRenderModels[position]));
        }

        // Info buttons
        ImageButton btnInfoText = findViewById(R.id.btnInfoTextModel);
        if (btnInfoText != null) {
            btnInfoText.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_info_text_model_title)
                    .setMessage(R.string.settings_info_text_model_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }

        ImageButton btnInfoOrch = findViewById(R.id.btnInfoImageOrchModel);
        if (btnInfoOrch != null) {
            btnInfoOrch.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_info_image_orchestration_title)
                    .setMessage(R.string.settings_info_image_orchestration_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }

        ImageButton btnInfoRender = findViewById(R.id.btnInfoImageRenderModel);
        if (btnInfoRender != null) {
            btnInfoRender.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_info_image_rendering_title)
                    .setMessage(R.string.settings_info_image_rendering_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }
    }
}

