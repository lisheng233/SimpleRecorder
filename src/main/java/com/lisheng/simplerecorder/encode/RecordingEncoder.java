package com.lisheng.simplerecorder.encode;

import com.lisheng.simplerecorder.client.ScaledFrame;
import java.util.concurrent.CompletableFuture;

/**
 * Streaming encoder used by the recording controller. Implementations own a
 * background worker; the render thread only calls {@link #offer(ScaledFrame)}.
 */
public interface RecordingEncoder {
    /**
     * Queues a frame without blocking. Returns {@code false} when the frame was
     * dropped because the queue is full or the encoder is finishing.
     */
    boolean offer(ScaledFrame frame);

    /**
     * Stops accepting frames and finishes the output file. Safe to call more than
     * once.
     */
    CompletableFuture<RecordingResult> finish();
}
