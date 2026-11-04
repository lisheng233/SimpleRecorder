package com.lisheng.simplerecorder.gif;

import java.nio.file.Path;

/**
 * Result of a finished GIF export.
 */
public final class GifResult {
    private final Path path;
    private final int frameCount;
    private final long bytes;

    public GifResult(Path path, int frameCount, long bytes) {
        this.path = path;
        this.frameCount = frameCount;
        this.bytes = bytes;
    }

    public static GifResult empty(Path path) {
        return new GifResult(path, 0, 0L);
    }

    public Path path() {
        return path;
    }

    public int frameCount() {
        return frameCount;
    }

    public long bytes() {
        return bytes;
    }
}
