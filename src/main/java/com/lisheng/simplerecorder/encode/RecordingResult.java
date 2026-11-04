package com.lisheng.simplerecorder.encode;

import java.nio.file.Path;

/**
 * A finished recording.
 */
public final class RecordingResult {
    private final Path path;
    private final int frameCount;
    private final long bytes;

    public RecordingResult(Path path, int frameCount, long bytes) {
        this.path = path;
        this.frameCount = frameCount;
        this.bytes = bytes;
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
