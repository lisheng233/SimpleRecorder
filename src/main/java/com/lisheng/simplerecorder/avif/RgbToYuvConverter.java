package com.lisheng.simplerecorder.avif;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Multi-threaded RGB to limited-range BT.601 YUV conversion used when the
 * OpenGL converter is unavailable or explicitly disabled.
 *
 * <p>Rows are split into bands across a small daemon pool. Auto mode uses half
 * of the available processors, clamped to 1-8: a single-core machine still
 * works, and a 96-core machine does not starve the game.</p>
 */
public final class RgbToYuvConverter implements AutoCloseable {
    private final int threads;
    private final ExecutorService executor;

    public RgbToYuvConverter(int configuredThreads) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int auto = Math.max(1, Math.min(8, cores / 2));
        this.threads = configuredThreads <= 0 ? auto : Math.max(1, Math.min(cores, configuredThreads));
        this.executor = threads <= 1 ? null : Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "SimpleRecorder-YUV");
            thread.setDaemon(true);
            return thread;
        });
    }

    public int threadCount() {
        return threads;
    }

    public void convert(int[] pixels, int width, int height, boolean use420,
                        byte[] yPlane, byte[] uPlane, byte[] vPlane) {
        if (threads <= 1 || executor == null) {
            convertRange(pixels, width, height, use420, yPlane, uPlane, vPlane, 0, height);
            return;
        }

        List<Callable<Void>> tasks = new ArrayList<>();
        int bands = Math.min(threads, height);
        for (int band = 0; band < bands; band++) {
            int rowStart = band * height / bands;
            int rowEnd = (band + 1) * height / bands;
            tasks.add(() -> {
                convertRange(pixels, width, height, use420, yPlane, uPlane, vPlane, rowStart, rowEnd);
                return null;
            });
        }
        try {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("YUV conversion interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("YUV conversion failed", exception.getCause());
        }
    }

    private static void convertRange(int[] pixels, int width, int height, boolean use420,
                                     byte[] yPlane, byte[] uPlane, byte[] vPlane,
                                     int rowStart, int rowEnd) {
        if (use420) {
            int chromaWidth = (width + 1) / 2;
            int chromaHeight = (height + 1) / 2;
            for (int py = rowStart; py < rowEnd; py++) {
                int row = py * width;
                int chromaRow = (py / 2) * chromaWidth;
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[row + x];
                    yPlane[row + x] = (byte) luma((pixel >>> 16) & 0xFF, (pixel >>> 8) & 0xFF, pixel & 0xFF);
                }
                if ((py & 1) == 0) {
                    for (int cx = 0; cx < chromaWidth; cx++) {
                        int r = 0;
                        int g = 0;
                        int b = 0;
                        int count = 0;
                        for (int dy = 0; dy < 2; dy++) {
                            int sy = py + dy;
                            if (sy >= height) {
                                continue;
                            }
                            for (int dx = 0; dx < 2; dx++) {
                                int sx = cx * 2 + dx;
                                if (sx >= width) {
                                    continue;
                                }
                                int pixel = pixels[sy * width + sx];
                                r += (pixel >>> 16) & 0xFF;
                                g += (pixel >>> 8) & 0xFF;
                                b += pixel & 0xFF;
                                count++;
                            }
                        }
                        uPlane[chromaRow + cx] = (byte) chromaU(r / count, g / count, b / count);
                        vPlane[chromaRow + cx] = (byte) chromaV(r / count, g / count, b / count);
                    }
                }
            }
        } else {
            for (int py = rowStart; py < rowEnd; py++) {
                int row = py * width;
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[row + x];
                    int r = (pixel >>> 16) & 0xFF;
                    int g = (pixel >>> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    yPlane[row + x] = (byte) luma(r, g, b);
                    uPlane[row + x] = (byte) chromaU(r, g, b);
                    vPlane[row + x] = (byte) chromaV(r, g, b);
                }
            }
        }
    }

    private static int luma(int r, int g, int b) {
        return clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
    }

    private static int chromaU(int r, int g, int b) {
        return clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
    }

    private static int chromaV(int r, int g, int b) {
        return clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
