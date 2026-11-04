package com.lisheng.simplerecorder.avif;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RgbToYuvConverterTest {
    @Test
    void convertsRedToLimitedRangeBt601Yuv() {
        byte[] y = new byte[1];
        byte[] u = new byte[1];
        byte[] v = new byte[1];
        try (RgbToYuvConverter converter = new RgbToYuvConverter(4)) {
            converter.convert(new int[]{0xFFFF0000}, 1, 1, false, y, u, v);
        }
        assertEquals(82, y[0] & 0xFF);
        assertEquals(90, u[0] & 0xFF);
        assertEquals(240, v[0] & 0xFF);
    }

    @Test
    void parallelBandsDoNotOverlap() {
        int width = 64;
        int height = 64;
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, 0xFF336699);
        byte[] y = new byte[width * height];
        byte[] u = new byte[width * height];
        byte[] v = new byte[width * height];
        try (RgbToYuvConverter converter = new RgbToYuvConverter(4)) {
            converter.convert(pixels, width, height, false, y, u, v);
        }
        for (int i = 0; i < y.length; i++) {
            assertEquals(y[0], y[i]);
            assertEquals(u[0], u[i]);
            assertEquals(v[0], v[i]);
        }
    }
}
