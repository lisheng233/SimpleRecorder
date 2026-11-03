package com.lisheng.simplerecorder.avif;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lisheng.simplerecorder.client.FramePool;
import com.lisheng.simplerecorder.client.ScaledFrame;
import com.lisheng.simplerecorder.encode.RecordingResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AvifEncoderTest {
    @TempDir
    Path tempDir;

    @Test
    void encodesAnAnimatedAvifWithALocalEncoder() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        Assumptions.assumeTrue(os.contains("win") && (arch.contains("amd64") || arch.contains("x86_64")));
        Assumptions.assumeTrue(AvifSupport.command() != null, () -> "avifenc unavailable: " + AvifSupport.error());

        Path output = tempDir.resolve("test.avif");
        AvifEncoder encoder = new AvifEncoder(output, 30, true, 60, 10, new FramePool());
        encoder.offer(new ScaledFrame(solid(64, 64, 0xFFFF0000), 64, 64, 0L));
        encoder.offer(new ScaledFrame(solid(64, 64, 0xFF00FF00), 64, 64, 33_333_333L));

        RecordingResult result = encoder.finish().get(60, TimeUnit.SECONDS);
        assertTrue(result.frameCount() >= 2);
        assertTrue(result.bytes() > 0);

        String ascii = new String(Files.readAllBytes(output), StandardCharsets.ISO_8859_1);
        assertTrue(ascii.contains("ftyp") && (ascii.contains("avif") || ascii.contains("avis")),
                "output is not an AVIF file");
    }

    private static int[] solid(int width, int height, int color) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }
}
