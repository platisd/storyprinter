package com.example.storyprinter;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_menu);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_menu), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Intent storyIntent = new Intent(this, StoryModeActivity.class);
        Intent manualIntent = new Intent(this, ManualModeActivity.class);
        Intent settingsIntent = new Intent(this, SettingsActivity.class);

        // Backwards compatible: keep the existing IDs working.
        Button btnStory = findViewById(R.id.btnStoryMode);
        Button btnManual = findViewById(R.id.btnManualMode);
        btnStory.setOnClickListener(v -> startActivity(storyIntent));
        btnManual.setOnClickListener(v -> startActivity(manualIntent));

        // New UI (Material3 cards). If present, wire them too.
        View cardStory = findViewById(R.id.cardStory);
        if (cardStory != null) cardStory.setOnClickListener(v -> startActivity(storyIntent));

        View cardManual = findViewById(R.id.cardManual);
        if (cardManual != null) cardManual.setOnClickListener(v -> startActivity(manualIntent));

        View cardSettings = findViewById(R.id.cardSettings);
        if (cardSettings != null) cardSettings.setOnClickListener(v -> startActivity(settingsIntent));
    }
}
