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
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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
    private Bitmap currentBitmap;

    private static final int PRINTER_MAX_WIDTH_PX = 384; // Typical 58mm thermal printer width

    // Single-launcher for multiple permissions (we centralize)
    private ActivityResultLauncher<String[]> permissionsLauncher;
    private ActivityResultLauncher<String> singlePermissionLauncher;
    private ActivityResultLauncher<String> imagePickerLauncher;

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
            String deviceName = d.getName() == null ? "(unnamed)" : d.getName();
            String label = deviceName + " (" + d.getAddress() + ")";
            names.add(label);
            deviceMap.put(label, d);
        }
        if (names.isEmpty()) {
            names.add("No paired devices");
        }
        ArrayAdapter<String> adapterSpinner = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapterSpinner.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapterSpinner);
        updateStatus("Loaded " + deviceMap.size() + " devices");
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
            currentBitmap = bitmap;
            imagePreview.setImageBitmap(currentBitmap);
            updateStatus("Image selected: " + getDisplayName(uri) + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
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
        if (currentBitmap == null) { Toast.makeText(this, "Select an image first", Toast.LENGTH_SHORT).show(); return; }
        if (!connectionManager.isConnected()) { Toast.makeText(this, "Connect to a device first", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            singlePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return; }
        updateStatus("Preparing image...");
        btnPrint.setEnabled(false);
        new Thread(() -> {
            Bitmap processed = prepareBitmapForPrinter(currentBitmap);
            byte[] raster = ImageRasterizer.rasterizeImage(processed);
            byte[] escpos = PhomemoEscPosEncoder.encodeImage(raster, processed.getWidth(), processed.getHeight());
            connectionManager.sendImage(escpos);
            runOnUiThread(() -> {
                btnPrint.setEnabled(true);
                updateStatus("Image sent");
            });
        }).start();
    }

    private Bitmap prepareBitmapForPrinter(Bitmap original) {
        // Scale to max width keeping aspect ratio
        int width = original.getWidth();
        int height = original.getHeight();
        if (width > PRINTER_MAX_WIDTH_PX) {
            float ratio = (float) PRINTER_MAX_WIDTH_PX / width;
            width = PRINTER_MAX_WIDTH_PX;
            height = Math.round(height * ratio);
            original = Bitmap.createScaledBitmap(original, width, height, true);
        }
        // Ensure width multiple of 8
        int paddedWidth = (width + 7) / 8 * 8;
        if (paddedWidth != width) {
            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < paddedWidth; x++) {
                    int color = (x < width) ? original.getPixel(x, y) : Color.WHITE;
                    padded.setPixel(x, y, color);
                }
            }
            original = padded;
            width = paddedWidth;
        }
        // Convert to monochrome (simple threshold)
        Bitmap bw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = original.getPixel(x, y);
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);
                int gray = (r + g + b) / 3;
                int out = gray < 128 ? Color.BLACK : Color.WHITE;
                bw.setPixel(x, y, out);
            }
        }
        return bw;
    }

    private void updateStatus(String msg) {
        txtStatus.setText("Status: " + msg);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connectionManager != null) {
            connectionManager.closeConnection();
        }
    }
}
