package com.example.storyprinter;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.storyprinter.openai.ChatMessage;
import com.example.storyprinter.openai.OpenAiClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class StoryModeActivity extends AppCompatActivity {

    // Loaded from BuildConfig.OPENAI_API_KEY (configured via local.properties, not version-controlled).
    private static final String OPENAI_API_KEY = com.example.storyprinter.BuildConfig.OPENAI_API_KEY;

    private static final String MODEL = "gpt-4.1-mini";
    private static final double TEMPERATURE = 1.1; // relatively high for imagination

    private EditText etSeed;
    private Button btnStart;
    private Button btnNext;
    private TextView tvOutput;
    private ProgressBar progress;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private OpenAiClient openAi;
    private final List<ChatMessage> conversation = new ArrayList<>();

    private int pageIndex = 0;

    // Responses API conversation chaining.
    private String previousResponseId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_story_mode);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_story), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        etSeed = findViewById(R.id.etSeed);
        btnStart = findViewById(R.id.btnStart);
        btnNext = findViewById(R.id.btnNext);
        tvOutput = findViewById(R.id.tvOutput);
        progress = findViewById(R.id.progress);

        openAi = new OpenAiClient(new OkHttpClient(), OPENAI_API_KEY);

        btnStart.setOnClickListener(v -> startStory());
        btnNext.setOnClickListener(v -> nextPage());

        if (savedInstanceState != null) {
            // Minimal state restore for rotation: keep text, enable next based on whether we started.
            tvOutput.setText(savedInstanceState.getString("output", tvOutput.getText().toString()));
            etSeed.setText(savedInstanceState.getString("seed", ""));
            pageIndex = savedInstanceState.getInt("pageIndex", 0);
            boolean started = savedInstanceState.getBoolean("started", false);
            btnNext.setEnabled(started);
            previousResponseId = savedInstanceState.getString("previousResponseId", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("seed", etSeed.getText() != null ? etSeed.getText().toString() : "");
        outState.putString("output", tvOutput.getText() != null ? tvOutput.getText().toString() : "");
        outState.putInt("pageIndex", pageIndex);
        outState.putBoolean("started", btnNext.isEnabled());
        outState.putString("previousResponseId", previousResponseId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void startStory() {
        String seed = etSeed.getText() != null ? etSeed.getText().toString().trim() : "";
        if (TextUtils.isEmpty(seed)) {
            etSeed.setError("Please enter a seed prompt");
            return;
        }

        conversation.clear();
        pageIndex = 0;
        previousResponseId = null;

        // System instruction: generate ONLY an image description per page.
        conversation.add(new ChatMessage(
                "system",
                "You create page-by-page prompts for a children's picture book. " +
                        "Return ONLY a simple, vivid image description for one page (no title, no extra commentary). " +
                        "Keep it child-friendly, concrete, and easy to illustrate. 1 short paragraph max."
        ));

        // User seed.
        conversation.add(new ChatMessage(
                "user",
                "Story seed: " + seed + "\n" +
                        "Generate the image description for Page 1."
        ));

        tvOutput.setText("Generating Page 1…");
        btnNext.setEnabled(false);
        btnStart.setEnabled(false);

        queryAndAppendAssistantMessage(/*appendToOutput=*/false);
    }

    private void nextPage() {
        // Ask for the next page while keeping prior conversation.
        conversation.add(new ChatMessage("user", "Generate the image description for Page " + (pageIndex + 1) + "."));
        btnNext.setEnabled(false);
        btnStart.setEnabled(false);
        queryAndAppendAssistantMessage(/*appendToOutput=*/true);
    }

    private void queryAndAppendAssistantMessage(boolean appendToOutput) {
        setLoading(true);

        io.execute(() -> {
            try {
                // Turn the current conversation into a single prompt. The server will keep state
                // via previous_response_id chaining.
                String input = buildInputTranscript(conversation);

                OpenAiClient.ResponseResult result = openAi.createResponse(
                        MODEL,
                        TEMPERATURE,
                        input,
                        previousResponseId
                );

                // Update the chain id for the next turn.
                if (result.responseId != null && !result.responseId.trim().isEmpty()) {
                    previousResponseId = result.responseId;
                }

                String assistant = result.outputText;

                // Save assistant to conversation so our local transcript stays coherent too.
                conversation.add(new ChatMessage("assistant", assistant));

                main.post(() -> {
                    pageIndex += 1;

                    if (!appendToOutput) {
                        tvOutput.setText("Page " + pageIndex + "\n" + assistant);
                    } else {
                        String current = tvOutput.getText() != null ? tvOutput.getText().toString() : "";
                        tvOutput.setText(current + "\n\n" + "Page " + pageIndex + "\n" + assistant);
                    }

                    btnNext.setEnabled(true);
                    btnStart.setEnabled(true);
                    setLoading(false);
                });
            } catch (IOException e) {
                main.post(() -> {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    tvOutput.setText((tvOutput.getText() != null ? tvOutput.getText().toString() : "") +
                            "\n\n[Error]\n" + msg);
                    btnStart.setEnabled(true);
                    btnNext.setEnabled(pageIndex > 0);
                    setLoading(false);
                });
            }
        });
    }

    private static String buildInputTranscript(List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            // Keep it simple and readable for the model.
            sb.append(m.role).append(": ").append(m.content).append("\n");
        }
        return sb.toString();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
