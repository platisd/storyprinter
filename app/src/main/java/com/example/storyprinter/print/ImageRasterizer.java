package com.example.storyprinter.print;

import android.graphics.Bitmap;

public class ImageRasterizer {

    // Existing API kept so callers remain unchanged
    public static byte[] rasterizeImage(Bitmap bitmap) {
        return rasterizeImage(bitmap, false);
    }

    /**
     * Rasterize a 1-bit bitmap for ESC/POS GS v 0 command.
     * Bit value 1 must represent a BLACK dot for most ESC/POS printers.
     * Previously this implementation inverted bits, causing white areas to print black.
     * @param bitmap Monochrome (BLACK / WHITE) bitmap expected.
     * @param invert If true, invert produced bits (rarely needed, but handy for some models).
     */
    public static byte[] rasterizeImage(Bitmap bitmap, boolean invert) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bytesPerLine = (width + 7) / 8; // Each byte = 8 pixels
        byte[] rasterData = new byte[bytesPerLine * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                // We treat pure BLACK (ARGB 0xFF000000) as printed (bit=1), otherwise 0.
                int bit = (pixel == 0xFF000000) ? 1 : 0; // Corrected: 1 now means black dot
                if (invert) bit ^= 1;
                int byteIndex = y * bytesPerLine + (x / 8);
                int bitIndex = 7 - (x % 8); // MSB first per byte
                rasterData[byteIndex] |= (bit << bitIndex);
            }
        }

        return rasterData;
    }
}