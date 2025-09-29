package com.example.storyprinter;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.storyprinter.bluetooth.BluetoothConnectionManager;
import com.example.storyprinter.print.ImageRasterizer;
import com.example.storyprinter.print.PhomemoEscPosEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private Spinner spinnerDevices;
    private Button btnConnect, btnSelectImage, btnPrint;
    private ImageView imagePreview;
    private TextView txtStatus;

    private final Map<String, BluetoothDevice> deviceMap = new HashMap<>();
    private BluetoothConnectionManager connectionManager;

    private Bitmap originalBitmap; // raw selected image
    private Bitmap processedBitmap; // dithered / printer-ready image shown in preview & sent

    private static final int PRINTER_MAX_WIDTH_PX = 384; // Typical 58mm thermal printer width
    private static final String TARGET_DEVICE_NAME = "T02"; // Filter target

    // Preview scaling configuration
    private static final int PREVIEW_MAX_SCALE = 4; // Max integer multiple upscale
    private static final int PREVIEW_MIN_TARGET_WIDTH = 800; // Try to reach at least this width if possible
    private static final boolean PREVIEW_USE_NEAREST_NEIGHBOR = true; // Preserve dither pattern

    // Single-launcher for multiple permissions (we centralize)
    private ActivityResultLauncher<String[]> permissionsLauncher;
    private ActivityResultLauncher<String> singlePermissionLauncher;
    private ActivityResultLauncher<String> imagePickerLauncher;

    // Simplified adjustable parameters
    // Remove boolean currentUseFSDither; introduce enum-like int for dither mode
    private static final int DITHER_FLOYD_STEINBERG = 0;
    private static final int DITHER_ORDERED_8x8 = 1;
    private static final int DITHER_NONE = 2;
    private int currentDitherMode = DITHER_FLOYD_STEINBERG;
    // Keep existing fields
    private float currentGamma = 1.0f;
    private int currentThreshold = 128;
    private boolean currentInvert = false;

    // UI control fields (adjust) - remove btnReprocess, add spinnerDitherMode
    private SeekBar seekGamma, seekThreshold;
    private TextView valueGamma, valueThreshold;
    private Switch switchInvert; // Only invert remains
    private Button btnReset;
    private Spinner spinnerDitherMode;

    // Preference / state persistence
    private static final String PREFS_NAME = "image_prefs";
    private static final String KEY_GAMMA = "gamma_progress"; // stored as int progress (10..150)
    private static final String KEY_THRESHOLD = "threshold"; // 0..255
    private static final String KEY_DITHER_MODE = "dither_mode"; // 0 FS,1 ORD,2 NONE
    private static final String KEY_INVERT = "invert";
    private static final String KEY_FSDITHER_LEGACY = "fs_dither"; // legacy boolean for migration

    private static final int DEFAULT_GAMMA_PROGRESS = 100; // => 1.00
    private static final int DEFAULT_THRESHOLD = 128;
    private static final int DEFAULT_DITHER_MODE = DITHER_FLOYD_STEINBERG;
    private static final boolean DEFAULT_INVERT = false;

    // Live reprocess debounce
    private static final long REPROCESS_DELAY_MS = 200L;
    private final android.os.Handler reprocessHandler = new android.os.Handler(Looper.getMainLooper());
    private final Runnable reprocessRunnable = this::processCurrentImageAsync; // will check for null image inside method
    private int processingGeneration = 0; // to discard stale results

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        connectionManager = new BluetoothConnectionManager(this);

        initPermissionLaunchers();
        initViews();
        loadPreferencesAndApply();
        initControls();
        setupListeners();
        ensurePermissionsThenLoadDevices();
    }

    private void initPermissionLaunchers() {
        singlePermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                // Retry devices load after single permission grant
                loadPairedDevices();
            } else {
                updateStatus("Permission denied");
            }
        });

        permissionsLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!Boolean.TRUE.equals(granted)) { allGranted = false; break; }
            }
            if (allGranted) {
                loadPairedDevices();
            } else {
                updateStatus("Missing required permissions");
            }
        });

        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                loadBitmapFromUri(uri);
            }
        });
    }

    private void ensurePermissionsThenLoadDevices() {
        String[] needed = computeRequiredPermissions();
        if (needed.length == 0) {
            loadPairedDevices();
        } else {
            permissionsLauncher.launch(needed);
        }
    }

    private String[] computeRequiredPermissions() {
        ArrayList<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // 31+
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                list.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // 33+
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                list.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else { // legacy external storage access still sometimes required <33
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        return list.toArray(new String[0]);
    }

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void initViews() {
        spinnerDevices = findViewById(R.id.spinnerDevices);
        btnConnect = findViewById(R.id.btnConnect);
        btnSelectImage = findViewById(R.id.btnSelectImage);
        btnPrint = findViewById(R.id.btnPrint);
        imagePreview = findViewById(R.id.imagePreview);
        txtStatus = findViewById(R.id.txtStatus);
        seekGamma = findViewById(R.id.seekGamma);
        seekThreshold = findViewById(R.id.seekThreshold);
        valueGamma = findViewById(R.id.valueGamma);
        valueThreshold = findViewById(R.id.valueThreshold);
        switchInvert = findViewById(R.id.switchInvert);
        btnReset = findViewById(R.id.btnReset);
        spinnerDitherMode = findViewById(R.id.spinnerDitherMode);

        // Remove hard-coded defaults; preferences loader will set them.
    }

    private void loadPreferencesAndApply() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int gammaProgress = sp.getInt(KEY_GAMMA, DEFAULT_GAMMA_PROGRESS);
        int threshold = sp.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        int ditherMode = sp.contains(KEY_DITHER_MODE)
                ? sp.getInt(KEY_DITHER_MODE, DITHER_FLOYD_STEINBERG)
                : (sp.getBoolean(KEY_FSDITHER_LEGACY, true) ? DITHER_FLOYD_STEINBERG : DITHER_NONE);
        boolean inv = sp.getBoolean(KEY_INVERT, DEFAULT_INVERT);

        // Clamp values just in case
        if (gammaProgress < 10) gammaProgress = 10; if (gammaProgress > 150) gammaProgress = 150;
        if (threshold < 0) threshold = 0; if (threshold > 255) threshold = 255;
        if (ditherMode < 0 || ditherMode > 2) ditherMode = DITHER_FLOYD_STEINBERG;

        // Apply to UI controls (they exist after initViews)
        seekGamma.setProgress(gammaProgress);
        seekThreshold.setProgress(threshold);
        switchInvert.setChecked(inv);
        spinnerDitherMode.setSelection(ditherMode, false);

        // Update internal variables & labels
        currentGamma = gammaProgress / 100f;
        currentThreshold = threshold;
        currentDitherMode = ditherMode;
        currentInvert = inv;
        valueGamma.setText(String.format(java.util.Locale.US, "%.2f", currentGamma));
        valueThreshold.setText(String.valueOf(currentThreshold));
    }

    private void savePreferences() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit()
            .putInt(KEY_GAMMA, Math.round(currentGamma * 100f))
            .putInt(KEY_THRESHOLD, currentThreshold)
            .putInt(KEY_DITHER_MODE, currentDitherMode)
            .putBoolean(KEY_INVERT, currentInvert)
            .apply();
    }

    private void initControls() {
        // Setup dither mode spinner adapter
        ArrayAdapter<String> ditherAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Floyd-Steinberg", "Ordered 8x8", "None"});
        ditherAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDitherMode.setAdapter(ditherAdapter);
        spinnerDitherMode.setSelection(currentDitherMode, false);

        seekGamma.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 10) progress = 10; if (progress > 150) progress = 150;
                currentGamma = progress / 100f;
                valueGamma.setText(String.format(java.util.Locale.US, "%.2f", currentGamma));
                savePreferences();
                scheduleLiveReprocess();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentThreshold = progress;
                valueThreshold.setText(String.valueOf(currentThreshold));
                savePreferences();
                scheduleLiveReprocess();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        spinnerDitherMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position != currentDitherMode) {
                    currentDitherMode = position;
                    savePreferences();
                    scheduleLiveReprocess();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        switchInvert.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentInvert = isChecked;
            savePreferences();
            scheduleLiveReprocess();
        });

        btnReset.setOnClickListener(v -> {
            seekGamma.setProgress(DEFAULT_GAMMA_PROGRESS);
            seekThreshold.setProgress(DEFAULT_THRESHOLD);
            spinnerDitherMode.setSelection(DITHER_FLOYD_STEINBERG);
            switchInvert.setChecked(DEFAULT_INVERT);
            // internal vars updated by listeners
            updateStatus("Settings reset");
        });
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> connectToSelectedDevice());
        btnSelectImage.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                singlePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                singlePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            } else {
                imagePickerLauncher.launch("image/*");
            }
        });
        btnPrint.setOnClickListener(v -> sendCurrentImage());
    }

    private void loadPairedDevices() {
        // Guard Bluetooth permission (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            updateStatus("Bluetooth permission needed");
            singlePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            updateStatus("Bluetooth not supported");
            return;
        }
        if (!adapter.isEnabled()) {
            updateStatus("Bluetooth disabled");
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException se) {
            updateStatus("Missing permission to read devices");
            return;
        }
        List<String> names = new ArrayList<>();
        deviceMap.clear();
        for (BluetoothDevice d : bonded) {
            String rawName = d.getName();
            if (rawName != null && rawName.equalsIgnoreCase(TARGET_DEVICE_NAME)) {
                String label = rawName + " (" + d.getAddress() + ")";
                names.add(label);
                deviceMap.put(label, d);
            }
        }
        if (names.isEmpty()) {
            names.add("No paired " + TARGET_DEVICE_NAME + " devices");
            btnConnect.setEnabled(false);
        } else {
            btnConnect.setEnabled(true);
        }
        ArrayAdapter<String> adapterSpinner = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapterSpinner.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapterSpinner);
        updateStatus("Found " + deviceMap.size() + " " + TARGET_DEVICE_NAME + " device(s)");
    }

    private void connectToSelectedDevice() {
        Object sel = spinnerDevices.getSelectedItem();
        if (sel == null) { Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show(); return; }
        BluetoothDevice device = deviceMap.get(sel.toString());
        if (device == null) { Toast.makeText(this, "Invalid device", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            singlePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        updateStatus("Connecting to " + device.getName() + "...");
        btnConnect.setEnabled(false);
        new Thread(() -> {
            connectionManager.pairDevice(device);
            runOnUiThread(() -> {
                btnConnect.setEnabled(true);
                if (connectionManager.isConnected()) {
                    updateStatus("Connected: " + device.getName());
                } else {
                    updateStatus("Failed to connect");
                }
            });
        }).start();
    }

    private void loadBitmapFromUri(@NonNull Uri uri) {
        // Media read permission check before attempting open (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
            updateStatus("Image permission needed");
            singlePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            singlePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            return;
        }
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) { Toast.makeText(this, "Cannot open image", Toast.LENGTH_SHORT).show(); return; }
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap == null) { Toast.makeText(this, "Decode failed", Toast.LENGTH_SHORT).show(); return; }
            originalBitmap = bitmap;
            processedBitmap = null;
            updateStatus("Image loaded");
            imagePreview.setImageDrawable(null);
            processingGeneration++; // invalidate prior processing
            scheduleLiveReprocess();
        } catch (IOException e) {
            Log.e("MainActivity", "Error reading image", e);
            Toast.makeText(this, "Error reading image", Toast.LENGTH_SHORT).show();
        }
    }

    private String getDisplayName(Uri uri) {
        String name = "image";
        ContentResolver cr = getContentResolver();
        try (android.database.Cursor cursor = cr.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    private void sendCurrentImage() {
        if (processedBitmap == null) { Toast.makeText(this, "Select an image first", Toast.LENGTH_SHORT).show(); return; }
        if (!connectionManager.isConnected()) { Toast.makeText(this, "Connect to a device first", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            singlePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return; }
        updateStatus("Sending image...");
        btnPrint.setEnabled(false);
        final Bitmap toSend = processedBitmap;
        new Thread(() -> {
            // Inversion already applied to processedBitmap if selected.
            byte[] raster = ImageRasterizer.rasterizeImage(toSend);
            byte[] escpos = PhomemoEscPosEncoder.encodeImage(raster, toSend.getWidth(), toSend.getHeight());
            connectionManager.sendImage(escpos);
            runOnUiThread(() -> {
                btnPrint.setEnabled(true);
                updateStatus("Image sent");
            });
        }).start();
    }

    private void processCurrentImageAsync() {
        if (originalBitmap == null) return; // guard
        btnPrint.setEnabled(false);
        final int generation = ++processingGeneration;
        updateStatus("Processing...");
        final Bitmap source = originalBitmap;
        new Thread(() -> {
            Bitmap processed = simpleProcessBitmap(source);
            Bitmap preview = buildPreviewBitmap(processed);
            runOnUiThread(() -> {
                if (generation != processingGeneration) {
                    // stale result; discard
                    return;
                }
                processedBitmap = processed;
                imagePreview.setImageBitmap(preview != null ? preview : processedBitmap);
                btnPrint.setEnabled(connectionManager.isConnected());
                updateStatus("Processed (" + processed.getWidth() + "x" + processed.getHeight() + ")");
            });
        }).start();
    }

    private Bitmap simpleProcessBitmap(Bitmap original) {
        // Scale to printer width if needed
        int width = original.getWidth();
        int height = original.getHeight();
        if (width > PRINTER_MAX_WIDTH_PX) {
            float ratio = (float) PRINTER_MAX_WIDTH_PX / width;
            width = PRINTER_MAX_WIDTH_PX;
            height = Math.round(height * ratio);
            original = Bitmap.createScaledBitmap(original, width, height, true);
        }
        // Pad to multiple of 8
        int paddedWidth = (width + 7) / 8 * 8;
        if (paddedWidth != width) {
            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < height; y++) for (int x = 0; x < paddedWidth; x++) padded.setPixel(x, y, (x < width) ? original.getPixel(x, y) : Color.WHITE);
            original = padded; width = paddedWidth;
        }

        // Build luminance array
        double[][] lum = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = original.getPixel(x, y);
                double L = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
                double normalized = L / 255.0;
                double adjusted = Math.pow(normalized, 1.0 / currentGamma);
                lum[y][x] = adjusted * 255.0;
            }
        }

        Bitmap bw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (currentDitherMode == DITHER_FLOYD_STEINBERG) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double oldPixel = lum[y][x];
                    double newPixel = oldPixel < currentThreshold ? 0 : 255;
                    double error = oldPixel - newPixel;
                    boolean isBlack = (newPixel == 0);
                    if (currentInvert) isBlack = !isBlack;
                    bw.setPixel(x, y, isBlack ? Color.BLACK : Color.WHITE);
                    if (x + 1 < width) lum[y][x + 1] += error * 7 / 16.0;
                    if (y + 1 < height) {
                        if (x > 0) lum[y + 1][x - 1] += error * 3 / 16.0;
                        lum[y + 1][x] += error * 5 / 16.0;
                        if (x + 1 < width) lum[y + 1][x + 1] += error * 1 / 16.0;
                    }
                }
            }
        } else if (currentDitherMode == DITHER_ORDERED_8x8) {
            final int[][] bayer8 = {
                    {0,32,8,40,2,34,10,42},
                    {48,16,56,24,50,18,58,26},
                    {12,44,4,36,14,46,6,38},
                    {60,28,52,20,62,30,54,22},
                    {3,35,11,43,1,33,9,41},
                    {51,19,59,27,49,17,57,25},
                    {15,47,7,39,13,45,5,37},
                    {63,31,55,23,61,29,53,21}
            }; // values 0..63
            // Precompute offset for global threshold influence (center around 128)
            double offset = 128 - currentThreshold; // positive means make image darker
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int m = bayer8[y & 7][x & 7];
                    double orderedThreshold = ( (m + 0.5) * 4 ); // 0..~255
                    double lumAdj = Math.max(0, Math.min(255, lum[y][x] + offset));
                    boolean isBlack = lumAdj < orderedThreshold;
                    if (currentInvert) isBlack = !isBlack;
                    bw.setPixel(x, y, isBlack ? Color.BLACK : Color.WHITE);
                }
            }
        } else { // DITHER_NONE
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean isBlack = lum[y][x] < currentThreshold;
                    if (currentInvert) isBlack = !isBlack;
                    bw.setPixel(x, y, isBlack ? Color.BLACK : Color.WHITE);
                }
            }
        }
        return bw;
    }

    // Reintroduce preview upscaling helper lost during refactor
    private Bitmap buildPreviewBitmap(Bitmap processed) {
        if (processed == null) return null;
        int w = processed.getWidth();
        int h = processed.getHeight();
        int scale = 1;
        while (scale < PREVIEW_MAX_SCALE && (w * (scale + 1)) <= PREVIEW_MIN_TARGET_WIDTH) {
            scale++;
        }
        if (scale <= 1) return null; // no upscale
        int newW = w * scale;
        int newH = h * scale;
        return Bitmap.createScaledBitmap(processed, newW, newH, !PREVIEW_USE_NEAREST_NEIGHBOR);
    }

    private void updateStatus(String msg) {
        txtStatus.setText("Status: " + msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelScheduledReprocess();
        if (connectionManager != null) connectionManager.closeConnection();
    }

    private void scheduleLiveReprocess() {
        if (originalBitmap == null) return;
        reprocessHandler.removeCallbacks(reprocessRunnable);
        reprocessHandler.postDelayed(reprocessRunnable, REPROCESS_DELAY_MS);
    }

    private void cancelScheduledReprocess() {
        reprocessHandler.removeCallbacks(reprocessRunnable);
    }
}
