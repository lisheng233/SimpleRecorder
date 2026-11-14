package com.lisheng.simplerecorder.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Converts Minecraft's native RGBA pixels to a top-down ARGB image and scales it
 * down with a box filter.
 *
 * <p>{@code NativeImage.getPixelsRGBA()} returns ABGR packed integers on
 * little-endian systems (red in the lowest byte), which is why this class does
 * not simply pass the array to {@link java.awt.image.BufferedImage}.</p>
 */
public final class FrameScaler {
    private FrameScaler() {
    }

    public static ScaledFrame scale(int[] nativeRgba, int srcWidth, int srcHeight, double scale) {
        return scale(nativeRgba, srcWidth, srcHeight, scale, null);
    }

    /**
     * @param reusableOutput optional array of exactly {@code scaledWidth *
     *                       scaledHeight} elements; may be {@code null}
     */
    public static ScaledFrame scale(int[] nativeRgba, int srcWidth, int srcHeight,
                                    double scale, int[] reusableOutput) {
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IllegalArgumentException("Invalid source size " + srcWidth + "x" + srcHeight);
        }
        if (nativeRgba.length != srcWidth * srcHeight) {
            throw new IllegalArgumentException("Pixel array does not match source size");
        }
        if (scale <= 0.0D) {
            throw new IllegalArgumentException("scale must be > 0");
        }

        long captureNanos = System.nanoTime();
        int dstWidth = scaledWidth(srcWidth, scale);
        int dstHeight = scaledHeight(srcHeight, scale);
        int[] output = reusableOutput != null && reusableOutput.length == dstWidth * dstHeight
                ? reusableOutput : new int[dstWidth * dstHeight];

        if (dstWidth == srcWidth && dstHeight == srcHeight) {
            for (int i = 0; i < output.length; i++) {
                output[i] = toArgb(nativeRgba[i]);
            }
            return new ScaledFrame(output, dstWidth, dstHeight, captureNanos);
        }

        // The default 50% case is by far the most common. A 2x2 box filter is
        // both faster and sharper than bilinear sampling for pixel art.
        if (Math.abs(scale - 0.5D) < 1.0E-9D && (srcWidth & 1) == 0 && (srcHeight & 1) == 0) {
            scaleHalf(nativeRgba, srcWidth, dstWidth, dstHeight, output);
            return new ScaledFrame(output, dstWidth, dstHeight, captureNanos);
        }

        scaleBox(nativeRgba, srcWidth, srcHeight, dstWidth, dstHeight, output);
        return new ScaledFrame(output, dstWidth, dstHeight, captureNanos);
    }

    /**
     * Scales a bottom-up RGBA byte buffer, as produced by an OpenGL pixel pack
     * buffer, into a top-down ARGB frame. No intermediate copy or NativeImage is
     * created.
     *
     * @param rgba          mapped PBO memory, at least {@code srcWidth *
     *                      srcHeight * 4} bytes
     * @param reusableOutput optional array of exactly the destination size
     * @param captureNanos  timestamp of the frame request, not of the readback
     */
    public static ScaledFrame scaleRgbaBottomUp(ByteBuffer rgba, int srcWidth, int srcHeight,
                                                double scale, int[] reusableOutput, long captureNanos) {
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IllegalArgumentException("Invalid source size " + srcWidth + "x" + srcHeight);
        }
        if (rgba.capacity() < srcWidth * srcHeight * 4) {
            throw new IllegalArgumentException("Pixel buffer is too small");
        }
        if (scale <= 0.0D) {
            throw new IllegalArgumentException("scale must be > 0");
        }

        IntBuffer source = rgba.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int dstWidth = scaledWidth(srcWidth, scale);
        int dstHeight = scaledHeight(srcHeight, scale);
        int[] output = reusableOutput != null && reusableOutput.length == dstWidth * dstHeight
                ? reusableOutput : new int[dstWidth * dstHeight];

        if (dstWidth == srcWidth && dstHeight == srcHeight) {
            for (int dy = 0; dy < dstHeight; dy++) {
                int sourceRow = (srcHeight - 1 - dy) * srcWidth;
                int outRow = dy * dstWidth;
                for (int dx = 0; dx < dstWidth; dx++) {
                    output[outRow + dx] = toArgb(source.get(sourceRow + dx));
                }
            }
            return new ScaledFrame(output, dstWidth, dstHeight, captureNanos);
        }

        if (Math.abs(scale - 0.5D) < 1.0E-9D && (srcWidth & 1) == 0 && (srcHeight & 1) == 0) {
            scaleHalfBottomUp(source, srcWidth, srcHeight, dstWidth, dstHeight, output);
            return new ScaledFrame(output, dstWidth, dstHeight, captureNanos);
        }

        scaleBoxBottomUp(source, srcWidth, srcHeight, dstWidth, dstHeight, output);
        return new ScaledFrame(output, dstWidth, dstHeight, captureNanos);
    }

    private static void scaleHalfBottomUp(IntBuffer source, int srcWidth, int srcHeight,
                                          int dstWidth, int dstHeight, int[] output) {
        for (int dy = 0; dy < dstHeight; dy++) {
            int topRow = (srcHeight - 1 - dy * 2) * srcWidth;
            int bottomRow = topRow - srcWidth;
            int outRow = dy * dstWidth;
            int sx = 0;
            for (int dx = 0; dx < dstWidth; dx++) {
                int p00 = source.get(topRow + sx);
                int p01 = source.get(topRow + sx + 1);
                int p10 = source.get(bottomRow + sx);
                int p11 = source.get(bottomRow + sx + 1);
                sx += 2;

                int r = ((p00 & 0xFF) + (p01 & 0xFF) + (p10 & 0xFF) + (p11 & 0xFF) + 2) >>> 2;
                int g = (((p00 >>> 8) & 0xFF) + ((p01 >>> 8) & 0xFF)
                        + ((p10 >>> 8) & 0xFF) + ((p11 >>> 8) & 0xFF) + 2) >>> 2;
                int b = (((p00 >>> 16) & 0xFF) + ((p01 >>> 16) & 0xFF)
                        + ((p10 >>> 16) & 0xFF) + ((p11 >>> 16) & 0xFF) + 2) >>> 2;
                output[outRow + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void scaleBoxBottomUp(IntBuffer source, int srcWidth, int srcHeight,
                                         int dstWidth, int dstHeight, int[] output) {
        double xRatio = srcWidth / (double) dstWidth;
        double yRatio = srcHeight / (double) dstHeight;

        for (int dy = 0; dy < dstHeight; dy++) {
            int y0 = (int) Math.floor(dy * yRatio);
            int y1 = Math.min(srcHeight, (int) Math.ceil((dy + 1) * yRatio));
            if (y1 <= y0) {
                y1 = Math.min(srcHeight, y0 + 1);
            }
            int outRow = dy * dstWidth;

            for (int dx = 0; dx < dstWidth; dx++) {
                int x0 = (int) Math.floor(dx * xRatio);
                int x1 = Math.min(srcWidth, (int) Math.ceil((dx + 1) * xRatio));
                if (x1 <= x0) {
                    x1 = Math.min(srcWidth, x0 + 1);
                }

                int r = 0;
                int g = 0;
                int b = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = (srcHeight - 1 - sy) * srcWidth;
                    for (int sx = x0; sx < x1; sx++) {
                        int pixel = source.get(row + sx);
                        r += pixel & 0xFF;
                        g += (pixel >>> 8) & 0xFF;
                        b += (pixel >>> 16) & 0xFF;
                    }
                }

                int count = (x1 - x0) * (y1 - y0);
                output[outRow + dx] = 0xFF000000
                        | ((r / count) << 16)
                        | ((g / count) << 8)
                        | (b / count);
            }
        }
    }

    public static int scaledWidth(int srcWidth, double scale) {
        return Math.max(1, (int) Math.round(srcWidth * scale));
    }

    public static int scaledHeight(int srcHeight, double scale) {
        return Math.max(1, (int) Math.round(srcHeight * scale));
    }

    private static void scaleHalf(int[] source, int srcWidth, int dstWidth, int dstHeight, int[] output) {
        for (int dy = 0; dy < dstHeight; dy++) {
            int row0 = (dy * 2) * srcWidth;
            int row1 = row0 + srcWidth;
            int outRow = dy * dstWidth;
            int sx = 0;
            for (int dx = 0; dx < dstWidth; dx++) {
                int p00 = source[row0 + sx];
                int p01 = source[row0 + sx + 1];
                int p10 = source[row1 + sx];
                int p11 = source[row1 + sx + 1];
                sx += 2;

                int r = ((p00 & 0xFF) + (p01 & 0xFF) + (p10 & 0xFF) + (p11 & 0xFF) + 2) >>> 2;
                int g = (((p00 >>> 8) & 0xFF) + ((p01 >>> 8) & 0xFF)
                        + ((p10 >>> 8) & 0xFF) + ((p11 >>> 8) & 0xFF) + 2) >>> 2;
                int b = (((p00 >>> 16) & 0xFF) + ((p01 >>> 16) & 0xFF)
                        + ((p10 >>> 16) & 0xFF) + ((p11 >>> 16) & 0xFF) + 2) >>> 2;
                output[outRow + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void scaleBox(int[] source, int srcWidth, int srcHeight,
                                 int dstWidth, int dstHeight, int[] output) {
        double xRatio = srcWidth / (double) dstWidth;
        double yRatio = srcHeight / (double) dstHeight;

        for (int dy = 0; dy < dstHeight; dy++) {
            int y0 = (int) Math.floor(dy * yRatio);
            int y1 = Math.min(srcHeight, (int) Math.ceil((dy + 1) * yRatio));
            if (y1 <= y0) {
                y1 = Math.min(srcHeight, y0 + 1);
            }
            int outRow = dy * dstWidth;

            for (int dx = 0; dx < dstWidth; dx++) {
                int x0 = (int) Math.floor(dx * xRatio);
                int x1 = Math.min(srcWidth, (int) Math.ceil((dx + 1) * xRatio));
                if (x1 <= x0) {
                    x1 = Math.min(srcWidth, x0 + 1);
                }

                int r = 0;
                int g = 0;
                int b = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = sy * srcWidth;
                    for (int sx = x0; sx < x1; sx++) {
                        int pixel = source[row + sx];
                        r += pixel & 0xFF;
                        g += (pixel >>> 8) & 0xFF;
                        b += (pixel >>> 16) & 0xFF;
                    }
                }

                int count = (x1 - x0) * (y1 - y0);
                output[outRow + dx] = 0xFF000000
                        | ((r / count) << 16)
                        | ((g / count) << 8)
                        | (b / count);
            }
        }
    }

    private static int toArgb(int nativeRgba) {
        int r = nativeRgba & 0xFF;
        int g = (nativeRgba >>> 8) & 0xFF;
        int b = (nativeRgba >>> 16) & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
