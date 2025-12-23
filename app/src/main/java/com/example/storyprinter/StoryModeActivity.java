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

import com.example.storyprinter.openai.OpenAiClient;
import com.example.storyprinter.story.StorySession;
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

    // Loaded from BuildConfig.OPENAI_API_KEY (configured via local.properties, not version-controlled).
    private static final String OPENAI_API_KEY = com.example.storyprinter.BuildConfig.OPENAI_API_KEY;

    private static final String MODEL = "gpt-4.1-mini";
    private static final double TEMPERATURE = 1.1; // relatively high for imagination
    private static final String IMAGE_MODEL = "gpt-5";

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

        tilSeed = findViewById(R.id.tilSeed);
        etSeed = findViewById(R.id.etSeed);
        seedEndIconProgress = findViewById(R.id.seedEndIconProgress);

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

        openAi = new OpenAiClient(textHttp, OPENAI_API_KEY);
        openAiImages = new OpenAiClient(imageHttp, OPENAI_API_KEY);

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

        // Restore UI from the in-memory session so navigating away/back doesn't lose pages.
        seedPrompt = session.getSeedPrompt();
        if (seedPrompt != null && !seedPrompt.trim().isEmpty()) {
            etSeed.setText(seedPrompt);
        }
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
        io.shutdownNow();
    }

    private void clearStory() {
        // If a request is in-flight, shutting down the executor would be too destructive; just clear UI + session.
        session.clear();
        seedPrompt = null;

        pagesContainer.removeAllViews();
        btnNext.setEnabled(false);
        setComposeLoading(false);

        // Reset the input hint back to a seed.
        if (tilSeed != null) {
            tilSeed.setHint("Seed prompt (e.g., 'A brave bunny in space')");
            tilSeed.setEndIconDrawable(R.drawable.ic_send_24);
        } else if (etSeed != null) {
            etSeed.setHint("Seed prompt (e.g., 'A brave bunny in space')");
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
            tilSeed.setHint(hasPages
                    ? "Add a new instruction to steer the story (e.g., 'Introduce a robot friend')"
                    : "Seed prompt (e.g., 'A brave bunny in space')");

            // Swap end icon: send before story starts, slick update icon after.
            tilSeed.setEndIconDrawable(hasPages ? R.drawable.ic_update_24 : R.drawable.ic_send_24);
        } else if (etSeed != null) {
            etSeed.setHint(hasPages
                    ? "Add a new instruction to steer the story (e.g., 'Introduce a robot friend')"
                    : "Seed prompt (e.g., 'A brave bunny in space')");
        }
    }

    private void startStory() {
        String seed = etSeed.getText() != null ? etSeed.getText().toString().trim() : "";
        if (TextUtils.isEmpty(seed)) {
            etSeed.setError("Please enter a seed prompt");
            return;
        }

        seedPrompt = seed;
        session.clear();
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
            etSeed.setError("Please enter an update instruction");
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
                            "Keep it child-friendly, concrete, and easy to illustrate. 1 short paragraph max.\n\n" +
                            "Story seed: " + (seedPrompt != null ? seedPrompt : "") + "\n" +
                            "Generate the image description for Page 1.";
                } else if (steerInstructionOrNull != null && !steerInstructionOrNull.trim().isEmpty()) {
                    // Mid-story steering: include the new instruction as the next user input.
                    input = "For Page " + pageNumberToRender + ", continue the story.\n" +
                            "New instruction (apply from this page onward while keeping earlier details consistent): " +
                            steerInstructionOrNull.trim() + "\n\n" +
                            "Return ONLY the image description for this page.";
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

                // Image generation: chain only to previous IMAGE response id.
                OpenAiClient.ImageResult imageResult = openAiImages.generateImage(
                        IMAGE_MODEL,
                        assistant,
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

        // Image area: FrameLayout so we can overlay actions on top of the image.
        android.widget.FrameLayout imageArea = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams areaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        areaLp.topMargin = dpToPx(10);
        imageArea.setLayoutParams(areaLp);
        imageArea.setTag("imageArea");

        ImageView image = new ImageView(this);
        image.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        image.setTag("pageImage");

        // Overlay: initially hidden.
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.setVisibility(View.GONE);
        overlay.setTag("imageOverlay");

        // Semi-transparent gray scrim.
        View scrim = new View(this);
        scrim.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scrim.setBackgroundColor(Color.parseColor("#80000000")); // 50% black
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

        Button btnSave = new Button(this);
        btnSave.setText("Save image to phone");
        btnSave.setTag("btnSaveImage");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnSave.setLayoutParams(saveLp);

        Button btnPrint = new Button(this);
        btnPrint.setText("Print");
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
            showImageOverlay(block, true);
            return true;
        });
        scrim.setOnClickListener(v -> showImageOverlay(block, false));

        btnSave.setOnClickListener(v -> {
            Bitmap bmp = getBitmapFromPage(block);
            if (bmp == null) {
                Toast.makeText(this, "No image to save yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            requestSaveImageToPhone(bmp, pageNumber);
            showImageOverlay(block, false);
        });

        btnPrint.setOnClickListener(v -> {
            Bitmap bmp = getBitmapFromPage(block);
            if (bmp == null) {
                Toast.makeText(this, "No image to print yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            openManualModeWithImage(bmp, pageNumber);
            showImageOverlay(block, false);
        });

        block.addView(title);
        block.addView(text);
        block.addView(error);
        block.addView(imageProgress);
        block.addView(imageArea);
        return block;
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
        // On Android 10+ (API 29+), saving to MediaStore doesn't require storage permissions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingImageAction = new PendingImageAction(bitmap, pageNumber);
                writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                return;
            }
        }
        saveBitmapToGallery(bitmap, pageNumber);
    }

    private void saveBitmapToGallery(Bitmap bitmap, int pageNumber) {
        if (bitmap == null) return;

        io.execute(() -> {
            Uri uri = null;
            OutputStream os = null;
            try {
                String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "story_page_" + pageNumber + "_" + time + ".png";

                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StoryPrinter");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }

                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Failed to create MediaStore record");

                os = resolver.openOutputStream(uri);
                if (os == null) throw new IOException("Failed to open output stream");

                boolean ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                if (!ok) throw new IOException("Failed to encode PNG");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                }

                Uri finalUri = uri;
                main.post(() -> Toast.makeText(this, "Saved to Photos: " + finalUri, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Uri toDelete = uri;
                if (toDelete != null) {
                    try {
                        getContentResolver().delete(toDelete, null, null);
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
        io.execute(() -> {
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
}
