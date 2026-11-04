package com.lisheng.simplerecorder.gif;

import com.lisheng.simplerecorder.client.FramePool;
import com.lisheng.simplerecorder.client.ScaledFrame;
import com.lisheng.simplerecorder.encode.RecordingEncoder;
import com.lisheng.simplerecorder.encode.RecordingResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background GIF encoder.
 *
 * <p>The render thread only copies pixels into this queue; all ImageIO and file
 * work happens on a worker thread. The queue is bounded so a slow disk or CPU
 * cannot grow memory without limit: when it is full the newest capture is
 * dropped and the controller reports it.</p>
 */
public final class GifEncoder implements RecordingEncoder {
    private static final int QUEUE_CAPACITY = 12;

    private final Path path;
    private final int fps;
    private final boolean loop;
    private final FramePool pool;
    private final BlockingQueue<ScaledFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final CompletableFuture<RecordingResult> completion = new CompletableFuture<>();
    private final Thread worker;

    private volatile boolean accepting = true;
    private volatile boolean finishRequested;

    public GifEncoder(Path path, int fps, boolean loop, FramePool pool) {
        this.path = path;
        this.fps = fps;
        this.loop = loop;
        this.pool = pool;
        this.worker = new Thread(this::run, "SimpleRecorder-GIF-Encoder");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Queues a frame without blocking the render thread.
     *
     * @return {@code false} if the frame was dropped because the queue is full or
     *         the encoder is already shutting down
     */
    @Override
    public boolean offer(ScaledFrame frame) {
        if (!accepting) {
            return false;
        }
        return queue.offer(frame);
    }

    /**
     * Stops accepting frames and asks the worker to finish the file. Safe to call
     * more than once.
     */
    @Override
    public CompletableFuture<RecordingResult> finish() {
        accepting = false;
        finishRequested = true;
        return completion;
    }

    private void run() {
        GifSequenceWriter writer = null;
        try {
            while (true) {
                ScaledFrame frame = queue.poll(50L, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    try {
                        if (writer == null) {
                            writer = new GifSequenceWriter(path, fps, loop);
                        }
                        writer.addFrame(frame.pixels(), frame.width(), frame.height(), frame.captureNanos());
                    } finally {
                        pool.release(frame.pixels());
                    }
                    continue;
                }
                if (finishRequested && queue.isEmpty()) {
                    break;
                }
            }

            if (writer == null) {
                completion.complete(new RecordingResult(path.toAbsolutePath(), 0, 0L));
            } else {
                GifResult result = writer.finish();
                completion.complete(new RecordingResult(result.path(), result.frameCount(), result.bytes()));
            }
        } catch (Throwable throwable) {
            if (writer != null) {
                writer.abort();
            } else {
                deleteQuietly(path);
            }
            completion.completeExceptionally(throwable);
        } finally {
            ScaledFrame frame;
            while ((frame = queue.poll()) != null) {
                pool.release(frame.pixels());
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
