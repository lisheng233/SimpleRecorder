package com.lisheng.simplerecorder.client;

/**
 * A downscaled frame ready for an encoder.
 *
 * <p>Either {@link #pixels()} is a top-down ARGB image (GIF, and the CPU AVIF
 * path) or {@link #yuv()} is a packed Y, U, V plane buffer from the OpenGL
 * converter (GPU AVIF path).</p>
 */
public final class ScaledFrame {
    private final int[] pixels;
    private final int width;
    private final int height;
    private final long captureNanos;
    private final byte[] yuv;

    public ScaledFrame(int[] pixels, int width, int height, long captureNanos) {
        this(pixels, width, height, captureNanos, null);
    }

    public ScaledFrame(int[] pixels, int width, int height, long captureNanos, byte[] yuv) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.captureNanos = captureNanos;
        this.yuv = yuv;
    }

    public static ScaledFrame yuv(byte[] yuv, int width, int height, long captureNanos) {
        return new ScaledFrame(null, width, height, captureNanos, yuv);
    }

    public int[] pixels() {
        return pixels;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long captureNanos() {
        return captureNanos;
    }

    public byte[] yuv() {
        return yuv;
    }

    public boolean hasYuv() {
        return yuv != null;
    }
}
