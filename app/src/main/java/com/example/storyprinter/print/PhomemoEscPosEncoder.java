package com.example.storyprinter.print;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PhomemoEscPosEncoder {

    public static byte[] encodeImage(byte[] imageData, int width, int height) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            // Print header
            outputStream.write(new byte[]{0x1b, 0x40}); // ESC @
            outputStream.write(new byte[]{0x1b, 0x61, 0x01}); // ESC a 1 (centered)

            // Print image
            int bytesPerLine = (width + 7) / 8; // Calculate bytes per line

            for (int line = 0; line < height; line++) {
                outputStream.write(new byte[]{0x1d, 0x76, 0x30, 0x00}); // GS v 0
                outputStream.write(new byte[]{(byte) (bytesPerLine & 0xFF), (byte) ((bytesPerLine >> 8) & 0xFF)}); // Width
                outputStream.write(new byte[]{(byte) (height & 0xFF), (byte) ((height >> 8) & 0xFF)}); // Height

                // Write image data
                for (int x = 0; x < bytesPerLine; x++) {
                    outputStream.write(imageData[line * bytesPerLine + x]);
                }
            }

            // Print footer
            outputStream.write(new byte[]{0x1b, 0x64, 0x02}); // ESC d n (feed n lines)
            outputStream.write(new byte[]{0x1b, 0x64, 0x02}); // ESC d n (feed n lines again)
        } catch (IOException e) {
            Log.e("PhomemoEscPosEncoder", "Error encoding image", e);
        }

        return outputStream.toByteArray();
    }
}