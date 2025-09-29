package com.example.storyprinter.print;

import android.graphics.Bitmap;

public class ImageRasterizer {

    public static byte[] rasterizeImage(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int rasterWidth = (width + 7) / 8; // Each byte represents 8 pixels
        byte[] rasterData = new byte[rasterWidth * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                int value = (pixel == 0xFF000000) ? 0 : 1; // Black pixel is 0, white pixel is 1
                int byteIndex = (y * rasterWidth) + (x / 8);
                int bitIndex = 7 - (x % 8);
                rasterData[byteIndex] |= (value << bitIndex);
            }
        }

        return rasterData;
    }
}