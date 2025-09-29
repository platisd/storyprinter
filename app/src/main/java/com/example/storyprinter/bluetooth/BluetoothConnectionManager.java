package com.example.storyprinter.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothConnectionManager {
    private static final String TAG = "BluetoothConnectionManager";
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;

    // UUID for the SPP (Serial Port Profile)
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public BluetoothConnectionManager(Context context) {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public void pairDevice(BluetoothDevice device) {
        // Permission guard for API 31+; caller should have checked but we fail safe here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device == null) {
                Log.e(TAG, "Device is null");
                return;
            }
            // We cannot directly check permission here without a Context reference to checkSelfPermission unless we kept one.
            // bluetoothAdapter.getRemoteDevice still throws SecurityException when lacking BLUETOOTH_CONNECT.
            try {
                device.getName(); // Touch device to trigger potential SecurityException early.
            } catch (SecurityException se) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", se);
                return;
            }
        }
        try {
            if (bluetoothSocket != null && bluetoothSocket.isConnected()) {
                Log.d(TAG, "Already connected. Closing previous connection.");
                closeConnection();
            }
            bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
            bluetoothSocket.connect();
            outputStream = bluetoothSocket.getOutputStream();
            Log.d(TAG, "Connected to device: " + device.getName());
        } catch (IOException e) {
            Log.e(TAG, "Could not connect to device", e);
            closeConnection();
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied to connect to device", e);
            closeConnection();
        }
    }

    public boolean isConnected() {
        return bluetoothSocket != null && bluetoothSocket.isConnected();
    }

    public void sendImage(byte[] imageData) {
        if (outputStream != null) {
            try {
                outputStream.write(imageData);
                outputStream.flush();
                Log.d(TAG, "Image data sent");
            } catch (IOException e) {
                Log.e(TAG, "Error sending image data", e);
            }
        } else {
            Log.e(TAG, "Output stream is null. Cannot send image data.");
        }
    }

    public void closeConnection() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing connection", e);
        } finally {
            outputStream = null;
            bluetoothSocket = null;
        }
    }
}