package com.example.storyprinter.print;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PhomemoEscPosEncoder {

    public static byte[] encodeImage(byte[] imageData, int width, int height) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            // Initialize printer & center
            outputStream.write(new byte[]{0x1b, 0x40}); // ESC @ reset
            outputStream.write(new byte[]{0x1b, 0x61, 0x01}); // ESC a 1 center

            int bytesPerLine = (width + 7) / 8; // width already padded to multiple of 8

            // Single raster command for whole image: GS v 0 m xL xH yL yH d1..dn
            outputStream.write(new byte[]{0x1d, 0x76, 0x30, 0x00}); // m = 0 normal density
            byte xL = (byte) (bytesPerLine & 0xFF);
            byte xH = (byte) ((bytesPerLine >> 8) & 0xFF);
            byte yL = (byte) (height & 0xFF);
            byte yH = (byte) ((height >> 8) & 0xFF);
            outputStream.write(new byte[]{xL, xH, yL, yH});
            // Write all raster bytes
            outputStream.write(imageData, 0, bytesPerLine * height);

            // Feed a few lines
            outputStream.write(new byte[]{0x1b, 0x64, 0x02}); // ESC d 2
        } catch (IOException e) {
            Log.e("PhomemoEscPosEncoder", "Error encoding image", e);
        }

        return outputStream.toByteArray();
    }
}