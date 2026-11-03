package com.lisheng.simplerecorder.gif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lisheng.simplerecorder.client.FramePool;
import com.lisheng.simplerecorder.client.ScaledFrame;
import com.lisheng.simplerecorder.encode.RecordingResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GifEncoderTest {
    @TempDir
    Path tempDir;

    @Test
    void writesGifThroughTheSharedEncoderInterface() throws Exception {
        Path output = tempDir.resolve("fallback.gif");
        GifEncoder encoder = new GifEncoder(output, 30, true, new FramePool());
        encoder.offer(new ScaledFrame(solid(16, 16, 0xFFFF0000), 16, 16, 0L));
        encoder.offer(new ScaledFrame(solid(16, 16, 0xFF0000FF), 16, 16, 33_333_333L));

        RecordingResult result = encoder.finish().get(30, TimeUnit.SECONDS);
        assertEquals(2, result.frameCount());
        assertTrue(Files.size(output) > 0);
        assertEquals(16, ImageIO.read(output.toFile()).getWidth());
    }

    private static int[] solid(int width, int height, int color) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }
}
