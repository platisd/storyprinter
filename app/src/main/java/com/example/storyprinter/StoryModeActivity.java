package com.example.storyprinter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.example.storyprinter.openai.ModelPreferences;
import com.example.storyprinter.openai.OpenAiClient;
import com.example.storyprinter.openai.OpenAiKeyStore;
import com.example.storyprinter.story.StorySession;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class StoryModeActivity extends AppCompatActivity {

    private static final double TEMPERATURE = 1.1; // relatively high for imagination

    private String textModel;
    private String imageModel;
    private String imageToolModel;

    private volatile boolean isLoading = false;

    private EditText etSeed;
    private TextInputLayout tilSeed;
    private View seedEndIconProgress;

    private Button btnNext;
    private Button btnClear;
    private TextView tvOutput;
    private ProgressBar progress;

    // Container that will hold full page blocks (title + text + image)
    private LinearLayout pagesContainer;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService diskIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private OpenAiClient openAi;
    private OpenAiClient openAiImages;

    private String seedPrompt = null;

    private final StorySession session = StorySession.get();

    // Permission + deferred action
    private ActivityResultLauncher<String> writeStoragePermissionLauncher;
    private PendingImageAction pendingImageAction = null;

    private static final class PendingImageAction {
        final Bitmap bitmap;
        final int pageNumber;

        PendingImageAction(Bitmap bitmap, int pageNumber) {
            this.bitmap = bitmap;
            this.pageNumber = pageNumber;
        }
    }

    private static final String TEMP_PRINT_DIR = "print_temp";

    private NestedScrollView storyScroll;
    private ExtendedFloatingActionButton fabBackToTop;
    private ExtendedFloatingActionButton fabGoToBottom;

    private Chip chipSwipeNext;


    // Bottom swipe-to-next (press + drag) state
    private boolean bottomSwipeTracking = false;
    private boolean bottomSwipeArmed = false;
    private float bottomSwipeStartY = 0f;

    private View cardReferenceImage;
    private ImageView ivReferenceThumb;
    private View btnRemoveReference;

    private final Runnable hideReferenceRemoveRunnable = () -> {
        if (btnRemoveReference != null) {
            btnRemoveReference.setVisibility(View.GONE);
        }
    };

    private ActivityResultLauncher<String> pickReferenceImageLauncher;

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

        // Material 3 top app bar.
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbarStory);
        if (toolbar != null) {
            // Keep it simple: use the default Material toolbar back arrow.
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        tilSeed = findViewById(R.id.tilSeed);
        etSeed = findViewById(R.id.etSeed);
        seedEndIconProgress = findViewById(R.id.seedEndIconProgress);

        cardReferenceImage = findViewById(R.id.cardReferenceImage);
        ivReferenceThumb = findViewById(R.id.ivReferenceThumb);
        btnRemoveReference = findViewById(R.id.btnRemoveReference);

        storyScroll = findViewById(R.id.storyScroll);
        fabBackToTop = findViewById(R.id.fabBackToTop);
        fabGoToBottom = findViewById(R.id.fabGoToBottom);

        chipSwipeNext = findViewById(R.id.chipSwipeNext);
        if (chipSwipeNext != null) {
            chipSwipeNext.setVisibility(View.GONE);
            chipSwipeNext.setEnabled(false);
        }

        if (storyScroll != null) {
            if (fabBackToTop != null) {
                fabBackToTop.setOnClickListener(v -> {
                    storyScroll.smoothScrollTo(0, 0);
                    // Smooth scroll is async; refresh state right after and after the next layout.
                    updateScrollFabEnabledState();
                    storyScroll.post(this::updateScrollFabEnabledState);
                });
                fabBackToTop.setVisibility(View.VISIBLE);
            }

            if (fabGoToBottom != null) {
                fabGoToBottom.setOnClickListener(v -> {
                    View child = storyScroll.getChildAt(0);
                    if (child != null) {
                        storyScroll.smoothScrollTo(0, child.getBottom());
                    }
                    updateScrollFabEnabledState();
                    storyScroll.post(this::updateScrollFabEnabledState);
                });
                fabGoToBottom.setVisibility(View.VISIBLE);
            }

            // Enabled/disabled state is driven by scroll position.
            storyScroll.setOnScrollChangeListener(new NestedScrollView.OnScrollChangeListener() {
                @Override
                public void onScrollChange(NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                    updateScrollFabEnabledState();
                }
            });

            // Initialize state after the first layout pass (and whenever layout changes).
            storyScroll.getViewTreeObserver().addOnGlobalLayoutListener(this::updateScrollFabEnabledState);

            // Replace fling-only trigger with a press-and-drag affordance:
            // - only when at bottom
            // - show a snackbar while finger is down to indicate the pending action
            // - allow cancel by dragging back down
            storyScroll.setOnTouchListener((v, event) -> {
                handleBottomSwipeToNextGesture(event);

                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    // Accessibility / lint: ensure click is properly reported.
                    v.performClick();
                }

                // Never consume; NestedScrollView must keep handling scrolling.
                return false;
            });
        }

        btnNext = findViewById(R.id.btnNext);
        btnClear = findViewById(R.id.btnClear);

        // tvOutput/progress were removed from the layout; keep these as null.
        tvOutput = null;
        progress = null;
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

        String apiKey = OpenAiKeyStore.getEffectiveApiKey(this);
        openAi = new OpenAiClient(textHttp, apiKey);
        openAiImages = new OpenAiClient(imageHttp, apiKey);

        textModel = ModelPreferences.getTextModel(this);
        imageModel = ModelPreferences.getImageOrchestrationModel(this);
        imageToolModel = ModelPreferences.getImageToolModel(this);

        // If the key is missing, prevent actions that will fail anyway.
        if (apiKey.trim().isEmpty()) {
            if (tilSeed != null) {
                tilSeed.setError("OpenAI API key missing. Set it in Settings.");
            }
            if (btnNext != null) btnNext.setEnabled(false);
        }

        // Send/Update (end icon) inside the text box.
        if (tilSeed != null) {
            tilSeed.setEndIconOnClickListener(v -> {
                if (isLoading) return;
                onSendOrUpdate();
            });
        }

        // Pressing Enter/Send on the keyboard triggers the same action.
        if (etSeed != null) {
            etSeed.setOnEditorActionListener((v, actionId, event) -> {
                boolean isSendAction = actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);

                if (isSendAction) {
                    if (!isLoading) {
                        onSendOrUpdate();
                    }
                    return true;
                }
                return false;
            });
        }

        btnNext.setOnClickListener(v -> {
            if (isLoading) return;
            nextPage();
        });
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                if (isLoading) return;
                clearStory();
            });
        }

        // Image picker for optional reference image.
        pickReferenceImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    io.execute(() -> {
                        try {
                            ReferenceImage ref = loadReferenceImageBase64(uri);
                            session.setReferenceImage(ref.base64Jpeg, ref.thumbnail);
                            main.post(() -> renderReferenceImageFromSession());
                        } catch (Exception e) {
                            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                            main.post(() -> Toast.makeText(this, "Couldn't load image: " + msg, Toast.LENGTH_LONG).show());
                        }
                    });
                }
        );

        if (tilSeed != null) {
            tilSeed.setStartIconOnClickListener(v -> {
                if (isLoading) return;
                if (pickReferenceImageLauncher != null) {
                    pickReferenceImageLauncher.launch("image/*");
                }
            });
        }

        if (cardReferenceImage != null && btnRemoveReference != null) {
            // Single tap toggles the remove icon (X) on/off.
            cardReferenceImage.setOnClickListener(v -> {
                boolean isVisible = btnRemoveReference.getVisibility() == View.VISIBLE;
                if (isVisible) {
                    main.removeCallbacks(hideReferenceRemoveRunnable);
                    btnRemoveReference.setVisibility(View.GONE);
                } else {
                    btnRemoveReference.setVisibility(View.VISIBLE);
                    // Auto-hide after a moment to keep UI calm if the user forgets.
                    main.removeCallbacks(hideReferenceRemoveRunnable);
                    main.postDelayed(hideReferenceRemoveRunnable, 10000L);
                }
            });

            btnRemoveReference.setOnClickListener(v -> {
                main.removeCallbacks(hideReferenceRemoveRunnable);
                session.clearReferenceImage();
                renderReferenceImageFromSession();
            });
        }

        // Restore UI from the in-memory session so navigating away/back doesn't lose pages.
        seedPrompt = session.getSeedPrompt();
        if (seedPrompt != null && !seedPrompt.trim().isEmpty()) {
            etSeed.setText(seedPrompt);
        }
        renderReferenceImageFromSession();
        renderPagesFromSession();
        updateButtonsForIdleState();
        setComposeLoading(false);

        if (savedInstanceState != null) {
            etSeed.setText(savedInstanceState.getString("seed", etSeed.getText() != null ? etSeed.getText().toString() : ""));
        }

        writeStoragePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (pendingImageAction == null) return;
                    PendingImageAction action = pendingImageAction;
                    pendingImageAction = null;

                    if (granted) {
                        saveBitmapToGallery(action.bitmap, action.pageNumber);
                    } else {
                        Toast.makeText(this, "Permission denied. Can't save image.", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void onSendOrUpdate() {
        // First interaction starts the story, later interactions steer (Update).
        if (session.snapshotPages().isEmpty()) {
            startStory();
        } else {
            updateStory();
        }
    }

    private void setComposeLoading(boolean loading) {
        isLoading = loading;

        if (tilSeed != null) {
            tilSeed.setEndIconVisible(!loading);
            tilSeed.setEnabled(!loading);
        }
        if (etSeed != null) {
            etSeed.setEnabled(!loading);
        }
        if (seedEndIconProgress != null) {
            seedEndIconProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }

        if (btnNext != null) btnNext.setEnabled(!loading && !session.snapshotPages().isEmpty());
        if (btnClear != null) btnClear.setEnabled(!loading);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("seed", etSeed.getText() != null ? etSeed.getText().toString() : "");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Don't shut down io here (story generation should survive navigation).
        // Also don't aggressively shut down diskIo here for the same reason.
    }

    private void clearStory() {
        // If a request is in-flight, shutting down the executor would be too destructive; just clear UI + session.
        session.clear();
        seedPrompt = null;

        // Clear the textbox as well.
        if (etSeed != null && etSeed.getText() != null) {
            etSeed.getText().clear();
            etSeed.setError(null);
        }

        // Clear reference image UI.
        renderReferenceImageFromSession();

        pagesContainer.removeAllViews();
        btnNext.setEnabled(false);
        setComposeLoading(false);

        // Reset the input hint back to a seed.
        if (tilSeed != null) {
            tilSeed.setHint(getString(R.string.story_seed_hint));
            tilSeed.setEndIconDrawable(R.drawable.ic_arrow_forward_24);
        } else if (etSeed != null) {
            etSeed.setHint(getString(R.string.story_seed_hint));
        }
    }

    private void renderPagesFromSession() {
        pagesContainer.removeAllViews();
        for (StorySession.Page p : session.snapshotPages()) {
            LinearLayout block = createPageBlock(p.pageNumber);
            pagesContainer.addView(block);
            setPageText(block, p.text);
            if (p.image != null) {
                setPageImageLoading(block, false);
                setPageImage(block, p.image, p.pageNumber);
            }
        }
    }

    private void updateButtonsForIdleState() {
        boolean hasPages = !session.snapshotPages().isEmpty();
        btnNext.setEnabled(hasPages);

        if (tilSeed != null) {
            tilSeed.setHint(getString(hasPages ? R.string.story_update_hint : R.string.story_seed_hint));

            // Swap end icon: arrow before story starts, slick update icon after.
            tilSeed.setEndIconDrawable(hasPages ? R.drawable.ic_update_24 : R.drawable.ic_arrow_forward_24);
        } else if (etSeed != null) {
            etSeed.setHint(getString(hasPages ? R.string.story_update_hint : R.string.story_seed_hint));
        }
    }

    private void startStory() {
        String seed = etSeed.getText() != null ? etSeed.getText().toString().trim() : "";
        if (TextUtils.isEmpty(seed)) {
            etSeed.setError(getString(R.string.story_error_seed_required));
            return;
        }

        seedPrompt = seed;
        session.clear(false);
        session.setSeedPrompt(seed);

        // Starting a new story should reset pages (including images).
        pagesContainer.removeAllViews();

        // Hide global status views (no-op; kept for safety if reintroduced later).
        if (tvOutput != null) tvOutput.setVisibility(View.GONE);
        if (progress != null) progress.setVisibility(View.GONE);

        btnNext.setEnabled(false);
        setComposeLoading(true);

        queryAndAppendAssistantMessage(null);
    }

    private void updateStory() {
        String steer = etSeed.getText() != null ? etSeed.getText().toString().trim() : "";
        if (TextUtils.isEmpty(steer)) {
            etSeed.setError(getString(R.string.story_error_update_required));
            return;
        }

        btnNext.setEnabled(false);
        setComposeLoading(true);
        queryAndAppendAssistantMessage(steer);
    }

    private void nextPage() {
        btnNext.setEnabled(false);
        setComposeLoading(true);
        queryAndAppendAssistantMessage(null);
    }

    private void queryAndAppendAssistantMessage(String steerInstructionOrNull) {
        io.execute(() -> {
            final int pageNumberToRender = session.getNextPageNumber();

            final LinearLayout[] pageBlockHolder = new LinearLayout[1];
            final StorySession.Page[] sessionPageHolder = new StorySession.Page[1];

            final boolean isSeedOrUpdate = (session.getPreviousTextResponseId() == null)
                    || (steerInstructionOrNull != null && !steerInstructionOrNull.trim().isEmpty());

            main.post(() -> {
                LinearLayout pageBlock = createPageBlock(pageNumberToRender);
                pagesContainer.addView(pageBlock);
                setPageImageLoading(pageBlock, true);
                pageBlockHolder[0] = pageBlock;
                sessionPageHolder[0] = session.addNewPage(pageNumberToRender);
            });

            try {
                String previousTextResponseId = session.getPreviousTextResponseId();
                String input;

                if (previousTextResponseId == null) {
                    // First page uses the seed.
                    input = "You create page-by-page prompts for a children's picture book. " +
                            "Return ONLY a simple, vivid image description for one page (no title, no extra commentary). " +
                            "Keep it child-friendly, concrete, and easy to illustrate. 1 short paragraph max.\n" +
                            "Story seed: " + (seedPrompt != null ? seedPrompt : "") + "\n" +
                            "If available, consider the reference image when generating the description.\n\n" +
                            "If available, use the reference image for style and character consistency, but still follow the written prompt.\n\n" +
                            "If there are any style or character details in the story seed, apply them consistently throughout the story.\n" +
                            "Style or character details in the seed should take precedence over any reference image if they conflict.\n\n" +
                            "Generate the image description for Page 1.";
                } else if (steerInstructionOrNull != null && !steerInstructionOrNull.trim().isEmpty()) {
                    // Mid-story steering: include the new instruction as the next user input.
                    input = "For Page " + pageNumberToRender + ", continue the story.\n" +
                            "New instruction (apply from this page onward while keeping earlier details consistent): " +
                            steerInstructionOrNull.trim() + "\n\n" +
                            "If available, use the reference image for style and character consistency, but still follow the written prompt.\n\n" +
                            "If there are any style or character details in the new instruction, apply them consistently from this page onward.\n" +
                            "Style or character details in the new instruction should take precedence over any reference image if they conflict.\n\n" +
                            "Style or character details from earlier pages and instructions should be kept consistent unless overridden by this new instruction.\n\n" +
                            "Return ONLY the image description for this page.";
                } else {
                    input = "Generate the image description for Page " + pageNumberToRender + ".\n" +
                            "Advance the story meaningfully: introduce a new scene, action, or setting. " +
                            "Each page should feel like a distinct moment — avoid repeating the same composition or pose.\n" +
                            "Keep characters and art style consistent with earlier pages, " +
                            "but change the environment, activity, or mood.";
                }

                // If we're applying a user-provided prompt (seed/update), attach the optional reference image.
                String referenceBase64 = isSeedOrUpdate ? session.getReferenceImageBase64() : null;

                OpenAiClient.ResponseResult result = openAi.createResponse(
                        textModel,
                        TEMPERATURE,
                        input,
                        referenceBase64,
                        previousTextResponseId
                );

                if (result.responseId != null && !result.responseId.trim().isEmpty()) {
                    session.setPreviousTextResponseId(result.responseId);
                }

                String assistant = result.outputText;

                StorySession.Page p = sessionPageHolder[0];
                if (p != null) {
                    p.text = assistant;
                }

                main.post(() -> {
                    LinearLayout pageBlock = pageBlockHolder[0];
                    if (pageBlock != null) {
                        setPageText(pageBlock, assistant);
                    }
                });

                // Image generation:
                // - chain only to previous IMAGE response id
                // - include user-provided reference image ONLY when the user provided a prompt (seed/update)
                referenceBase64 = isSeedOrUpdate ? session.getReferenceImageBase64() : null;

                OpenAiClient.ImageResult imageResult = openAiImages.generateImage(
                        imageModel,
                        imageToolModel,
                        assistant,
                        referenceBase64,
                        session.getPreviousImageResponseId()
                );

                if (imageResult.responseId != null && !imageResult.responseId.trim().isEmpty()) {
                    session.setPreviousImageResponseId(imageResult.responseId);
                }

                Bitmap bitmap = decodeBase64ToBitmap(imageResult.imageBase64);
                if (p != null) {
                    p.image = bitmap;
                }

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

                    setComposeLoading(false);
                    updateButtonsForIdleState();

                    // Content height changes after adding a page; refresh FAB enabled/disabled state.
                    if (storyScroll != null) {
                        storyScroll.post(this::updateScrollFabEnabledState);
                    }
                });
            } catch (IOException e) {
                main.post(() -> {
                    LinearLayout pageBlock = pageBlockHolder[0];
                    if (pageBlock != null) {
                        setPageImageLoading(pageBlock, false);
                        setPageError(pageBlock, (e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                    setComposeLoading(false);
                    updateButtonsForIdleState();

                    if (storyScroll != null) {
                        storyScroll.post(this::updateScrollFabEnabledState);
                    }
                });
            }
        });
    }

    private void renderReferenceImageFromSession() {
        if (cardReferenceImage == null || ivReferenceThumb == null || btnRemoveReference == null) return;

        // Keep the remove icon hidden by default.
        main.removeCallbacks(hideReferenceRemoveRunnable);

        Bitmap thumb = session.getReferenceImageThumbnail();
        boolean has = (thumb != null) && (session.getReferenceImageBase64() != null) && (!session.getReferenceImageBase64().trim().isEmpty());

        if (!has) {
            cardReferenceImage.setVisibility(View.GONE);
            ivReferenceThumb.setImageDrawable(null);
            btnRemoveReference.setVisibility(View.GONE);
            return;
        }

        cardReferenceImage.setVisibility(View.VISIBLE);
        ivReferenceThumb.setImageBitmap(thumb);
        // Hide X by default; user taps to toggle.
        btnRemoveReference.setVisibility(View.GONE);
    }

    private static final class ReferenceImage {
        final String base64Jpeg;
        final Bitmap thumbnail;

        ReferenceImage(String base64Jpeg, Bitmap thumbnail) {
            this.base64Jpeg = base64Jpeg;
            this.thumbnail = thumbnail;
        }
    }

    private ReferenceImage loadReferenceImageBase64(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();

        // Decode with sampling to avoid large allocations.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (java.io.InputStream is = resolver.openInputStream(uri)) {
            if (is == null) throw new IOException("Couldn't open image");
            BitmapFactory.decodeStream(is, null, opts);
        }

        int srcW = opts.outWidth;
        int srcH = opts.outHeight;
        if (srcW <= 0 || srcH <= 0) {
            throw new IOException("Unsupported image");
        }

        // Target: keep it reasonable; reference image is guidance, not full-res.
        int maxDim = 1024;
        int sample = 1;
        while ((srcW / sample) > maxDim || (srcH / sample) > maxDim) {
            sample *= 2;
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sample;
        Bitmap decoded;
        try (java.io.InputStream is2 = resolver.openInputStream(uri)) {
            if (is2 == null) throw new IOException("Couldn't open image");
            decoded = BitmapFactory.decodeStream(is2, null, decodeOpts);
        }
        if (decoded == null) throw new IOException("Couldn't decode image");

        // Encode to JPEG for the data-url (we always send data:image/jpeg;base64,...)
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        decoded.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

        // Thumbnail for UI.
        Bitmap thumb = Bitmap.createScaledBitmap(decoded, 120, (int) (120f * decoded.getHeight() / Math.max(1, decoded.getWidth())), true);

        return new ReferenceImage(base64, thumb);
    }

    private LinearLayout createPageBlock(int pageNumber) {
        // Outer container so we can keep current method signature (LinearLayout) while using a CardView inside.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams outerLp = (LinearLayout.LayoutParams) outer.getLayoutParams();
        outerLp.topMargin = dpToPx(16);
        outer.setLayoutParams(outerLp);

        // Material 3 card section.
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        // Match MainActivity card styling.
        card.setCardElevation(0f);
        card.setStrokeWidth(dpToPx(1));
        card.setStrokeColor(com.google.android.material.color.MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOutlineVariant,
                Color.LTGRAY
        ));

        // Inner content.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        int pad = dpToPx(16);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        title.setText(getString(R.string.story_page_title, pageNumber));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTag("pageTitle");

        TextView text = new TextView(this);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
        textLp.topMargin = dpToPx(8);
        text.setLayoutParams(textLp);
        text.setText("");
        text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        text.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurface,
                Color.DKGRAY
        ));
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
        error.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        error.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this,
                android.R.attr.colorError,
                Color.RED
        ));
        error.setTag("pageError");

        ProgressBar imageProgress = new ProgressBar(this);
        imageProgress.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pbLp.topMargin = dpToPx(12);
        pbLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        imageProgress.setLayoutParams(pbLp);
        imageProgress.setTag("imageProgress");
        imageProgress.setVisibility(View.GONE);

        // Image area: FrameLayout so we can overlay actions on top of the image.
        android.widget.FrameLayout imageArea = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams areaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        areaLp.topMargin = dpToPx(12);
        areaLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        imageArea.setLayoutParams(areaLp);
        imageArea.setTag("imageArea");

        ImageView image = new ImageView(this);
        image.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
        ));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        image.setTag("pageImage");

        // Overlay: initially hidden.
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER
        ));
        overlay.setVisibility(View.GONE);
        overlay.setTag("imageOverlay");

        // Semi-transparent scrim.
        View scrim = new View(this);
        scrim.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER
        ));
        scrim.setBackgroundColor(Color.parseColor("#80000000"));
        scrim.setTag("overlayScrim");

        // Actions container.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.FrameLayout.LayoutParams actionsLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionsLp.gravity = android.view.Gravity.CENTER;
        actions.setLayoutParams(actionsLp);
        actions.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        actions.setTag("overlayActions");

        // Use Material buttons for a consistent M3 look.
        com.google.android.material.button.MaterialButton btnSave = new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnSave.setText(R.string.story_action_save_image);
        btnSave.setTag("btnSaveImage");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnSave.setLayoutParams(saveLp);

        com.google.android.material.button.MaterialButton btnPrint = new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonStyle);
        btnPrint.setText(R.string.story_action_print);
        btnPrint.setEnabled(true);
        btnPrint.setTag("btnPrintImage");
        LinearLayout.LayoutParams printLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        printLp.leftMargin = dpToPx(12);
        btnPrint.setLayoutParams(printLp);

        actions.addView(btnSave);
        actions.addView(btnPrint);

        overlay.addView(scrim);
        overlay.addView(actions);

        imageArea.addView(image);
        imageArea.addView(overlay);

        // Toggle overlay on long-press. Hide on tap outside.
        image.setOnLongClickListener(v -> {
            if (image.getDrawable() == null) return false;
            showImageOverlay(outer, true);
            return true;
        });
        scrim.setOnClickListener(v -> showImageOverlay(outer, false));

        btnSave.setOnClickListener(v -> {
            Bitmap bmp = getBitmapFromPage(outer);
            if (bmp == null) {
                Toast.makeText(this, "No image to save yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            requestSaveImageToPhone(bmp, pageNumber);
            showImageOverlay(outer, false);
        });

        btnPrint.setOnClickListener(v -> {
            Bitmap bmp = getBitmapFromPage(outer);
            if (bmp == null) {
                Toast.makeText(this, "No image to print yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            openManualModeWithImage(bmp, pageNumber);
            showImageOverlay(outer, false);
        });

        content.addView(title);
        content.addView(text);
        content.addView(error);
        content.addView(imageProgress);
        content.addView(imageArea);

        card.addView(content);
        outer.addView(card);
        return outer;
    }

    private void showImageOverlay(LinearLayout pageBlock, boolean show) {
        if (pageBlock == null) return;
        View overlay = pageBlock.findViewWithTag("imageOverlay");
        if (overlay != null) {
            overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private Bitmap getBitmapFromPage(LinearLayout pageBlock) {
        if (pageBlock == null) return null;
        View v = pageBlock.findViewWithTag("pageImage");
        if (!(v instanceof ImageView)) return null;
        ImageView iv = (ImageView) v;
        if (!(iv.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)) return null;
        return ((android.graphics.drawable.BitmapDrawable) iv.getDrawable()).getBitmap();
    }

    private void requestSaveImageToPhone(Bitmap bitmap, int pageNumber) {
        // minSdk is 29, so MediaStore saving doesn't require legacy WRITE_EXTERNAL_STORAGE permission.
        saveBitmapToGallery(bitmap, pageNumber);
    }

    private void saveBitmapToGallery(Bitmap bitmap, int pageNumber) {
        if (bitmap == null) return;

        diskIo.execute(() -> {
            Uri uri = null;
            OutputStream os = null;
            try {
                String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "story_page_" + pageNumber + "_" + time + ".png";

                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StoryPrinter");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Failed to create MediaStore record");

                os = resolver.openOutputStream(uri);
                if (os == null) throw new IOException("Failed to open output stream");

                boolean ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                if (!ok) throw new IOException("Failed to encode PNG");

                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);

                Uri finalUri = uri;
                main.post(() -> Toast.makeText(this, "Saved to Photos: " + finalUri, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                if (uri != null) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                }
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> Toast.makeText(this, "Save failed: " + msg, Toast.LENGTH_LONG).show());
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void openManualModeWithImage(Bitmap bitmap, int pageNumber) {
        // Navigation should happen immediately; file writing can happen in the background.
        diskIo.execute(() -> {
            try {
                Uri uri = writeBitmapToCacheAndGetUri(bitmap, pageNumber);
                if (uri == null) throw new IOException("Failed to create image uri");

                main.post(() -> {
                    Intent i = new Intent(this, ManualModeActivity.class);
                    i.putExtra(ManualModeActivity.EXTRA_IMAGE_URI, uri.toString());
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> Toast.makeText(this, "Couldn't open print screen: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private Uri writeBitmapToCacheAndGetUri(Bitmap bitmap, int pageNumber) throws IOException {
        File dir = new File(getCacheDir(), TEMP_PRINT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create cache dir");
        }

        String fileName = "story_page_" + pageNumber + "_print.png";
        File file = new File(dir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            boolean ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            if (!ok) throw new IOException("Failed to encode PNG");
        }

        return FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
        );
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

        // Ensure overlay is hidden when a new image comes in.
        showImageOverlay(pageBlock, false);
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

    private void updateScrollFabEnabledState() {
        if (storyScroll == null) return;

        // Small tolerance because scroll positions can be off by a couple px due to rounding/insets.
        final int tol = dpToPx(2);

        int scrollY = storyScroll.getScrollY();

        View child = storyScroll.getChildAt(0);
        int range = 0;
        if (child != null) {
            range = Math.max(0, child.getHeight() - storyScroll.getHeight());
        }

        boolean atTop = scrollY <= tol;
        boolean atBottom = scrollY >= (range - tol);

        setFabEnabled(fabBackToTop, !atTop);
        setFabEnabled(fabGoToBottom, !atBottom);
    }

    private static void setFabEnabled(ExtendedFloatingActionButton fab, boolean enabled) {
        if (fab == null) return;

        fab.setEnabled(enabled);
        fab.setClickable(enabled);

        // Material 3: disabled state uses reduced emphasis (commonly 38% alpha).
        // Instead of dimming the entire view (which can look odd), we apply the alpha
        // to the icon tint and background tint so it reads as a real disabled color.
        final float disabledAlpha = 0.38f;

        int bg = com.google.android.material.color.MaterialColors.getColor(
                fab,
                com.google.android.material.R.attr.colorPrimaryContainer
        );
        int fg = com.google.android.material.color.MaterialColors.getColor(
                fab,
                com.google.android.material.R.attr.colorOnPrimaryContainer
        );

        int bgDisabled = com.google.android.material.color.MaterialColors.compositeARGBWithAlpha(
                bg,
                Math.round(255 * disabledAlpha)
        );
        int fgDisabled = com.google.android.material.color.MaterialColors.compositeARGBWithAlpha(
                fg,
                Math.round(255 * disabledAlpha)
        );

        int[][] states = new int[][]{
                new int[]{android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_enabled}
        };

        android.content.res.ColorStateList bgTint = new android.content.res.ColorStateList(
                states,
                new int[]{bg, bgDisabled}
        );
        android.content.res.ColorStateList iconTint = new android.content.res.ColorStateList(
                states,
                new int[]{fg, fgDisabled}
        );

        fab.setBackgroundTintList(bgTint);
        fab.setIconTint(iconTint);

        // Keep full alpha; the tint handles disabled emphasis.
        fab.setAlpha(1f);
    }

    private boolean isAtBottom() {
        if (storyScroll == null) return false;
        View child = storyScroll.getChildAt(0);
        if (child == null) return true;

        // Small tolerance because scroll positions can be off by a couple px due to rounding/insets.
        final int tol = dpToPx(2);
        int range = Math.max(0, child.getHeight() - storyScroll.getHeight());
        return storyScroll.getScrollY() >= (range - tol);
    }

    private void handleBottomSwipeToNextGesture(MotionEvent event) {
        if (event == null) return;
        if (storyScroll == null) return;

        // Only relevant when a story exists and Next is available.
        if (btnNext == null || !btnNext.isEnabled()) {
            resetBottomSwipeToNext();
            return;
        }

        if (isLoading) {
            resetBottomSwipeToNext();
            return;
        }

        final int armDistancePx = dpToPx(56);
        final int cancelHysteresisPx = dpToPx(24);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // Start tracking only if we're currently at the bottom.
                if (!isAtBottom()) {
                    resetBottomSwipeToNext();
                    return;
                }
                bottomSwipeTracking = true;
                bottomSwipeArmed = false;
                bottomSwipeStartY = event.getY();
                updateSwipeNextIndicator(false, true);
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!bottomSwipeTracking) return;

                // If user moved away from the bottom due to scroll bounce/content changes, stop tracking.
                if (!isAtBottom()) {
                    resetBottomSwipeToNext();
                    return;
                }

                float dy = event.getY() - bottomSwipeStartY;
                float upDistance = -dy;

                if (!bottomSwipeArmed) {
                    if (upDistance >= armDistancePx) {
                        bottomSwipeArmed = true;
                        updateSwipeNextIndicator(true, true);
                    }
                } else {
                    // Allow cancel by moving back down without lifting.
                    if (upDistance <= (armDistancePx - cancelHysteresisPx)) {
                        bottomSwipeArmed = false;
                        updateSwipeNextIndicator(false, true);
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean shouldTrigger = bottomSwipeTracking && bottomSwipeArmed && !isLoading
                        && btnNext != null && btnNext.isEnabled() && isAtBottom();

                resetBottomSwipeToNext();

                if (shouldTrigger) {
                    nextPage();
                }
                break;
            }
        }
    }

    private void updateSwipeNextIndicator(boolean armed, boolean show) {
        if (chipSwipeNext == null) return;

        // Text is intentionally short and unobtrusive.
        chipSwipeNext.setText(armed ? "Release for next" : "Swipe up for next");

        // Extra clean: only show an icon when the action is armed.
        if (armed) {
            chipSwipeNext.setChipIcon(getDrawable(R.drawable.ic_auto_awesome_24));
            chipSwipeNext.setChipIconVisible(true);
        } else {
            chipSwipeNext.setChipIcon(null);
            chipSwipeNext.setChipIconVisible(false);
        }

        if (!show) {
            if (chipSwipeNext.getVisibility() == View.VISIBLE) {
                chipSwipeNext.animate()
                        .alpha(0f)
                        .translationY(dpToPx(12))
                        .setDuration(120)
                        .withEndAction(() -> {
                            chipSwipeNext.setVisibility(View.GONE);
                            chipSwipeNext.setAlpha(1f);
                            chipSwipeNext.setTranslationY(0f);
                        })
                        .start();
            }
            return;
        }

        if (chipSwipeNext.getVisibility() != View.VISIBLE) {
            chipSwipeNext.setAlpha(0f);
            chipSwipeNext.setTranslationY(dpToPx(12));
            chipSwipeNext.setVisibility(View.VISIBLE);
            chipSwipeNext.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(150)
                    .start();
        }
    }

    private void resetBottomSwipeToNext() {
        bottomSwipeTracking = false;
        bottomSwipeArmed = false;
        bottomSwipeStartY = 0f;

        updateSwipeNextIndicator(false, false);
    }
}
