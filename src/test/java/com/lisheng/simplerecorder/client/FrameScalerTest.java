package com.lisheng.simplerecorder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class FrameScalerTest {
    @Test
    void convertsNativeRgbaToArgbAtFullSize() {
        int[] source = {pack(10, 20, 30, 255)};
        ScaledFrame frame = FrameScaler.scale(source, 1, 1, 1.0D);

        assertEquals(1, frame.width());
        assertEquals(1, frame.height());
        assertEquals(0xFF0A141E, frame.pixels()[0]);
    }

    @Test
    void averagesTopAndBottomRowsInHalfSizeMode() {
        // 4x4: top half red, bottom half blue. The 50% fast path should keep the
        // top row red and the bottom row blue, not flip them.
        int[] source = new int[4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                source[y * 4 + x] = y < 2
                        ? pack(255, 0, 0, 255)
                        : pack(0, 0, 255, 255);
            }
        }

        ScaledFrame frame = FrameScaler.scale(source, 4, 4, 0.5D);

        assertEquals(2, frame.width());
        assertEquals(2, frame.height());
        assertEquals(0xFFFF0000, frame.pixels()[0]);
        assertEquals(0xFFFF0000, frame.pixels()[1]);
        assertEquals(0xFF0000FF, frame.pixels()[2]);
        assertEquals(0xFF0000FF, frame.pixels()[3]);
    }

    @Test
    void averagesA2x2Block() {
        int[] source = {
                pack(0, 0, 0, 255), pack(100, 100, 100, 255),
                pack(200, 200, 200, 255), pack(255, 255, 255, 255)
        };
        ScaledFrame frame = FrameScaler.scale(source, 2, 2, 0.5D);

        assertEquals(1, frame.width());
        assertEquals(1, frame.height());
        // (0 + 100 + 200 + 255 + 2) / 4 = 139 (0x8B)
        assertEquals(0xFF8B8B8B, frame.pixels()[0]);
    }

    @Test
    void handlesOddDimensionsWithGeneralBoxFilter() {
        int[] source = new int[3 * 3];
        java.util.Arrays.fill(source, pack(20, 40, 60, 255));

        ScaledFrame frame = FrameScaler.scale(source, 3, 3, 0.5D);

        assertEquals(2, frame.width());
        assertEquals(2, frame.height());
        assertEquals(0xFF14283C, frame.pixels()[0]);
    }

    @Test
    void flipsBottomUpPboRows() {
        // OpenGL returns the bottom row first. Source top half is red, bottom half
        // is blue, so the PBO buffer starts with blue rows.
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 4 * 4);
        for (int row = 0; row < 4; row++) {
            int r = row < 2 ? 0 : 255;
            int b = row < 2 ? 255 : 0;
            for (int x = 0; x < 4; x++) {
                buffer.put((byte) r).put((byte) 0).put((byte) b).put((byte) 255);
            }
        }
        buffer.flip();

        ScaledFrame frame = FrameScaler.scaleRgbaBottomUp(buffer, 4, 4, 0.5D, null, 1L);

        assertEquals(2, frame.width());
        assertEquals(2, frame.height());
        assertEquals(0xFFFF0000, frame.pixels()[0]);
        assertEquals(0xFFFF0000, frame.pixels()[1]);
        assertEquals(0xFF0000FF, frame.pixels()[2]);
        assertEquals(0xFF0000FF, frame.pixels()[3]);
    }

    /**
     * NativeImage stores the bytes as RGBA; on a little-endian machine the packed
     * int is AABBGGRR.
     */
    private static int pack(int r, int g, int b, int a) {
        return r | (g << 8) | (b << 16) | (a << 24);
    }
}
