package com.lisheng.simplerecorder.gif;

import java.nio.file.Path;

/**
 * Result of a finished GIF export.
 *
 * @param path       absolute output path
 * @param frameCount number of GIF frames actually written (identical frames are merged)
 * @param bytes      file size
 */
public record GifResult(Path path, int frameCount, long bytes) {
    public static GifResult empty(Path path) {
        return new GifResult(path, 0, 0L);
    }
}
