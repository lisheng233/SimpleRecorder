package com.lisheng.simplerecorder.encode;

import java.nio.file.Path;

/**
 * A finished recording.
 *
 * @param path       absolute output path
 * @param frameCount number of frames written
 * @param bytes      file size
 */
public record RecordingResult(Path path, int frameCount, long bytes) {
}
