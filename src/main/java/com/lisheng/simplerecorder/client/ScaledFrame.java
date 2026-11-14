package com.lisheng.simplerecorder.client;

/**
 * A downscaled frame ready for an encoder.
 *
 * <p>Either {@link #pixels()} is a top-down ARGB image (GIF, and the CPU AVIF
 * path) or {@link #yuv()} is a packed Y, U, V plane buffer from the OpenGL
 * converter (GPU AVIF path).</p>
 *
 * @param pixels       ARGB pixels, top row first; {@code null} for a YUV frame
 * @param captureNanos monotonic timestamp of the capture request
 * @param yuv          packed Y then U then V planes; {@code null} for ARGB
 */
public record ScaledFrame(int[] pixels, int width, int height, long captureNanos, byte[] yuv) {
    public ScaledFrame(int[] pixels, int width, int height, long captureNanos) {
        this(pixels, width, height, captureNanos, null);
    }

    public static ScaledFrame yuv(byte[] yuv, int width, int height, long captureNanos) {
        return new ScaledFrame(null, width, height, captureNanos, yuv);
    }

    public boolean hasYuv() {
        return yuv != null;
    }
}
