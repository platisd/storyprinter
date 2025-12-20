package com.example.storyprinter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private static final String IMAGE_MODEL = "gpt-5";

    private EditText etSeed;
    private Button btnStart;
    private Button btnNext;

    // Container that will hold full page blocks (title + text + image)
    private LinearLayout pagesContainer;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private OpenAiClient openAi;
    private OpenAiClient openAiImages;
    private final List<ChatMessage> conversation = new ArrayList<>();

    private int pageIndex = 0;

    // Responses API conversation chaining.
    private String previousTextResponseId = null;
    private String previousImageResponseId = null;

    private String seedPrompt = null;

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
        pagesContainer = findViewById(R.id.pagesContainer);

        // Text calls: default timeouts.
        OkHttpClient textHttp = new OkHttpClient();

        // Image calls can take longer: bump call/read/write timeouts.
        OkHttpClient imageHttp = new OkHttpClient.Builder()
                .callTimeout(java.time.Duration.ofSeconds(120))
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .readTimeout(java.time.Duration.ofSeconds(120))
                .writeTimeout(java.time.Duration.ofSeconds(120))
                .build();

        openAi = new OpenAiClient(textHttp, OPENAI_API_KEY);
        openAiImages = new OpenAiClient(imageHttp, OPENAI_API_KEY);

        btnStart.setOnClickListener(v -> startStory());
        btnNext.setOnClickListener(v -> nextPage());

        if (savedInstanceState != null) {
            // Minimal state restore for rotation.
            etSeed.setText(savedInstanceState.getString("seed", ""));
            pageIndex = savedInstanceState.getInt("pageIndex", 0);
            boolean started = savedInstanceState.getBoolean("started", false);
            btnNext.setEnabled(started);
            previousTextResponseId = savedInstanceState.getString("previousTextResponseId", null);
            previousImageResponseId = savedInstanceState.getString("previousImageResponseId", null);
            seedPrompt = savedInstanceState.getString("seedPrompt", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("seed", etSeed.getText() != null ? etSeed.getText().toString() : "");
        outState.putInt("pageIndex", pageIndex);
        outState.putBoolean("started", btnNext.isEnabled());
        outState.putString("previousTextResponseId", previousTextResponseId);
        outState.putString("previousImageResponseId", previousImageResponseId);
        outState.putString("seedPrompt", seedPrompt);
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

        seedPrompt = seed;

        conversation.clear();
        pageIndex = 0;
        previousTextResponseId = null;
        previousImageResponseId = null;

        // Starting a new story should reset pages (including images).
        pagesContainer.removeAllViews();

        btnNext.setEnabled(false);
        btnStart.setEnabled(false);

        queryAndAppendAssistantMessage();
    }

    private void nextPage() {
        conversation.add(new ChatMessage("user", "Generate the image description for Page " + (pageIndex + 1) + "."));
        btnNext.setEnabled(false);
        btnStart.setEnabled(false);
        queryAndAppendAssistantMessage();
    }

    private void queryAndAppendAssistantMessage() {
        // No global spinner; per-page spinner will be shown under the new page block.

        io.execute(() -> {
            final int pageNumberToRender = pageIndex + 1;
            final LinearLayout[] pageBlockHolder = new LinearLayout[1];
            main.post(() -> {
                LinearLayout pageBlock = createPageBlock(pageNumberToRender);
                pagesContainer.addView(pageBlock);
                setPageImageLoading(pageBlock, true);
                pageBlockHolder[0] = pageBlock;
            });

            try {
                String input;
                if (previousTextResponseId == null) {
                    input = "You create page-by-page prompts for a children's picture book. " +
                            "Return ONLY a simple, vivid image description for one page (no title, no extra commentary). " +
                            "Keep it child-friendly, concrete, and easy to illustrate. 1 short paragraph max.\n\n" +
                            "Story seed: " + (seedPrompt != null ? seedPrompt : "") + "\n" +
                            "Generate the image description for Page 1.";
                } else {
                    input = "Generate the image description for Page " + pageNumberToRender + ".";
                }

                OpenAiClient.ResponseResult result = openAi.createResponse(
                        MODEL,
                        TEMPERATURE,
                        input,
                        previousTextResponseId
                );

                if (result.responseId != null && !result.responseId.trim().isEmpty()) {
                    previousTextResponseId = result.responseId;
                }

                String assistant = result.outputText;
                conversation.add(new ChatMessage("assistant", assistant));

                main.post(() -> {
                    pageIndex = pageNumberToRender;
                    LinearLayout pageBlock = pageBlockHolder[0];
                    if (pageBlock != null) {
                        setPageText(pageBlock, assistant);
                    }
                });

                OpenAiClient.ImageResult imageResult = openAiImages.generateImage(
                        IMAGE_MODEL,
                        assistant,
                        previousImageResponseId
                );

                if (imageResult.responseId != null && !imageResult.responseId.trim().isEmpty()) {
                    previousImageResponseId = imageResult.responseId;
                }

                Bitmap bitmap = decodeBase64ToBitmap(imageResult.imageBase64);

                main.post(() -> {
                    LinearLayout pageBlock = pageBlockHolder[0];
                    if (pageBlock != null) {
                        setPageImageLoading(pageBlock, false);
                        if (bitmap != null) {
                            setPageImage(pageBlock, bitmap, pageNumberToRender);
                        } else {
                            setPageError(pageBlock, "No image returned.");
                        }
                    }

                    btnNext.setEnabled(true);
                    btnStart.setEnabled(true);
                });
            } catch (IOException e) {
                main.post(() -> {
                    LinearLayout pageBlock = pageBlockHolder[0];
                    if (pageBlock != null) {
                        setPageImageLoading(pageBlock, false);
                        setPageError(pageBlock, (e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                    btnStart.setEnabled(true);
                    btnNext.setEnabled(pageIndex > 0);
                });
            }
        });
    }

    private LinearLayout createPageBlock(int pageNumber) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams blockLp = (LinearLayout.LayoutParams) block.getLayoutParams();
        blockLp.topMargin = dpToPx(16);
        block.setLayoutParams(blockLp);

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        title.setText("Page " + pageNumber);
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTag("pageTitle");

        TextView text = new TextView(this);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
        textLp.topMargin = dpToPx(6);
        text.setLayoutParams(textLp);
        text.setText("");
        text.setTag("pageText");

        TextView error = new TextView(this);
        error.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams errLp = (LinearLayout.LayoutParams) error.getLayoutParams();
        errLp.topMargin = dpToPx(8);
        error.setLayoutParams(errLp);
        error.setVisibility(View.GONE);
        error.setTag("pageError");

        ProgressBar imageProgress = new ProgressBar(this);
        imageProgress.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pbLp.topMargin = dpToPx(10);
        pbLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        imageProgress.setLayoutParams(pbLp);
        imageProgress.setTag("imageProgress");
        imageProgress.setVisibility(View.GONE);

        ImageView image = new ImageView(this);
        image.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams imgLp = (LinearLayout.LayoutParams) image.getLayoutParams();
        imgLp.topMargin = dpToPx(10);
        image.setLayoutParams(imgLp);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        image.setTag("pageImage");

        block.addView(title);
        block.addView(text);
        block.addView(error);
        block.addView(imageProgress);
        block.addView(image);
        return block;
    }

    private void setPageText(LinearLayout pageBlock, String text) {
        if (pageBlock == null) return;
        View t = pageBlock.findViewWithTag("pageText");
        if (t instanceof TextView) {
            ((TextView) t).setText(text != null ? text : "");
        }
    }

    private void setPageError(LinearLayout pageBlock, String errorText) {
        if (pageBlock == null) return;
        View e = pageBlock.findViewWithTag("pageError");
        if (e instanceof TextView) {
            TextView tv = (TextView) e;
            tv.setText(errorText != null ? errorText : "Unknown error");
            tv.setVisibility(View.VISIBLE);
        }
    }

    private void setPageImageLoading(LinearLayout pageBlock, boolean loading) {
        if (pageBlock == null) return;
        View pb = pageBlock.findViewWithTag("imageProgress");
        if (pb != null) {
            pb.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    private void setPageImage(LinearLayout pageBlock, Bitmap bitmap, int pageNumber) {
        if (pageBlock == null || bitmap == null) return;
        View v = pageBlock.findViewWithTag("pageImage");
        if (!(v instanceof ImageView)) return;

        ImageView iv = (ImageView) v;
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription("Generated story image for page " + pageNumber);
        iv.setImageBitmap(bitmap);
        iv.setVisibility(View.VISIBLE);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }


    private static Bitmap decodeBase64ToBitmap(String base64) {
        if (base64 == null || base64.trim().isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
