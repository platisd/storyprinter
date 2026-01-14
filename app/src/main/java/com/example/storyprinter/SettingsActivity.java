package com.example.storyprinter;

import android.os.Bundle;
import android.text.InputType;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.storyprinter.openai.OpenAiKeyStore;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
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
    }
}

