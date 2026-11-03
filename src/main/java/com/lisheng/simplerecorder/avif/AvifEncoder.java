package com.lisheng.simplerecorder.avif;

import com.lisheng.simplerecorder.client.FramePool;
import com.lisheng.simplerecorder.client.ScaledFrame;
import com.lisheng.simplerecorder.client.YuvPool;
import com.lisheng.simplerecorder.encode.RecordingEncoder;
import com.lisheng.simplerecorder.encode.RecordingResult;
import com.mojang.logging.LogUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Streaming animated AVIF encoder.
 *
 * <p>Frames are converted to Y4M and streamed into {@code avifenc --stdin}.
 * The encoder process gets a fixed 30 FPS timebase; if capture frames were
 * dropped, the previous frame is repeated to keep the wall-clock duration
 * correct.</p>
 */
public final class AvifEncoder implements RecordingEncoder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int QUEUE_CAPACITY = 16;
    private static final int MAX_DUPLICATE_FRAMES = 60;
    private static final byte[] FRAME_HEADER = "FRAME\n".getBytes(StandardCharsets.US_ASCII);

    private final Path path;
    private final int fps;
    private final boolean loop;
    private final int quality;
    private final int speed;
    private final FramePool pool;
    private final YuvPool yuvPool;
    private final RgbToYuvConverter converter;
    private final BlockingQueue<ScaledFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final CompletableFuture<RecordingResult> completion = new CompletableFuture<>();
    private final Thread worker;

    private volatile boolean accepting = true;
    private volatile boolean finishRequested;

    private byte[] yPlane = new byte[0];
    private byte[] uPlane = new byte[0];
    private byte[] vPlane = new byte[0];
    private boolean use420;

    private ScaledFrame previousFrame;
    private long firstTimestampNanos;
    private int frameSlot;
    private int framesWritten;
    private volatile String processLog = "";

    public AvifEncoder(Path path, int fps, boolean loop, int quality, int speed, FramePool pool) {
        this(path, fps, loop, quality, speed, pool, new YuvPool(), 0);
    }

    public AvifEncoder(Path path, int fps, boolean loop, int quality, int speed,
                       FramePool pool, YuvPool yuvPool, int cpuThreads) {
        this.path = path;
        this.fps = fps;
        this.loop = loop;
        this.quality = quality;
        this.speed = speed;
        this.pool = pool;
        this.yuvPool = yuvPool;
        this.converter = new RgbToYuvConverter(cpuThreads);
        this.worker = new Thread(this::run, "SimpleRecorder-AVIF-Encoder");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public boolean offer(ScaledFrame frame) {
        if (!accepting) {
            return false;
        }
        return queue.offer(frame);
    }

    @Override
    public CompletableFuture<RecordingResult> finish() {
        accepting = false;
        finishRequested = true;
        return completion;
    }

    private void run() {
        Process process = null;
        OutputStream stdin = null;
        Thread logReader = null;
        try {
            ScaledFrame first = nextFrame();
            if (first == null) {
                completion.complete(new RecordingResult(path.toAbsolutePath(), 0, 0L));
                return;
            }

            Files.createDirectories(path.toAbsolutePath().getParent());
            process = startProcess(first.width(), first.height());
            logReader = drainLog(process);
            stdin = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
            writeHeader(stdin, first);

            writeFrame(stdin, first);
            framesWritten++;
            previousFrame = first;
            firstTimestampNanos = first.captureNanos();
            frameSlot = 1;

            while (true) {
                ScaledFrame frame = nextFrame();
                if (frame == null) {
                    break;
                }
                if (frame.width() != first.width() || frame.height() != first.height()) {
                    releaseFrame(frame);
                    throw new IOException("AVIF frame size changed");
                }

                writeDuplicateGap(stdin, frame);
                writeFrame(stdin, frame);
                framesWritten++;
                frameSlot++;

                ScaledFrame old = previousFrame;
                previousFrame = frame;
                releaseFrame(old);
            }

            stdin.close();
            stdin = null;
            int exitCode = waitFor(process);
            if (logReader != null) {
                logReader.join(1000L);
            }
            if (exitCode != 0) {
                throw new IOException("avifenc exited with " + exitCode + ": " + tail(processLog));
            }

            long bytes = Files.exists(path) ? Files.size(path) : 0L;
            if (bytes <= 0L) {
                throw new IOException("avifenc produced no output: " + tail(processLog));
            }
            completion.complete(new RecordingResult(path.toAbsolutePath(), framesWritten, bytes));
        } catch (Throwable throwable) {
            if (process != null) {
                process.destroyForcibly();
            }
            deleteQuietly(path);
            completion.completeExceptionally(throwable);
        } finally {
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            }
            if (logReader != null) {
                logReader.interrupt();
            }
            if (previousFrame != null) {
                releaseFrame(previousFrame);
                previousFrame = null;
            }
            ScaledFrame frame;
            while ((frame = queue.poll()) != null) {
                releaseFrame(frame);
            }
            converter.close();
        }
    }

    private ScaledFrame nextFrame() throws InterruptedException {
        while (true) {
            ScaledFrame frame = queue.poll(50L, TimeUnit.MILLISECONDS);
            if (frame != null) {
                return frame;
            }
            if (finishRequested && queue.isEmpty()) {
                return null;
            }
        }
    }

    private Process startProcess(int width, int height) throws IOException {
        String encoder = AvifSupport.command();
        if (encoder == null) {
            throw new IOException("AVIF encoder unavailable: " + AvifSupport.error());
        }

        int jobs = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        List<String> command = new ArrayList<>();
        command.add(encoder);
        command.add("--stdin");
        command.add("--fps");
        command.add(Integer.toString(fps));
        command.add("-q");
        command.add(Integer.toString(quality));
        command.add("-s");
        command.add(Integer.toString(speed));
        command.add("-j");
        command.add(Integer.toString(jobs));
        command.add("--repetition-count");
        command.add(loop ? "infinite" : "0");
        command.add("--");
        command.add(path.toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        LOGGER.info("Starting AVIF encoder: {} ({}x{}, {} FPS, q={}, s={})",
                encoder, width, height, fps, quality, speed);
        return builder.start();
    }

    private Thread drainLog(Process process) {
        Thread thread = new Thread(() -> {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (captured.size() < 16 * 1024) {
                        captured.write(buffer, 0, Math.min(read, 16 * 1024 - captured.size()));
                    }
                }
            } catch (IOException ignored) {
                // The process closing its output is expected.
            }
            processLog = captured.toString(StandardCharsets.UTF_8);
        }, "SimpleRecorder-AVIF-Log");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private int waitFor(Process process) throws InterruptedException {
        if (!process.waitFor(10L, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("avifenc timed out");
        }
        return process.exitValue();
    }

    private void writeHeader(OutputStream output, ScaledFrame first) throws IOException {
        use420 = !first.hasYuv() && (first.width() & 1) == 0 && (first.height() & 1) == 0;
        String chroma = use420 ? "420mpeg2" : "444";
        String header = "YUV4MPEG2 W" + first.width() + " H" + first.height()
                + " F" + fps + ":1 Ip A1:1 C" + chroma + "\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeDuplicateGap(OutputStream output, ScaledFrame next) throws IOException {
        long intervalNanos = 1_000_000_000L / fps;
        int targetSlot = (int) Math.round(
                (next.captureNanos() - firstTimestampNanos) / (double) intervalNanos);
        int missing = Math.max(0, targetSlot - frameSlot);
        int duplicates = Math.min(MAX_DUPLICATE_FRAMES, missing);
        if (missing > MAX_DUPLICATE_FRAMES) {
            LOGGER.warn("AVIF capture stalled for {} frames; capping duplicates at {}",
                    missing, MAX_DUPLICATE_FRAMES);
        }
        for (int i = 0; i < duplicates; i++) {
            writeFrame(output, previousFrame);
            framesWritten++;
            frameSlot++;
        }
    }

    private void writeFrame(OutputStream output, ScaledFrame frame) throws IOException {
        int width = frame.width();
        int height = frame.height();
        int ySize = width * height;

        if (frame.hasYuv()) {
            output.write(FRAME_HEADER);
            output.write(frame.yuv(), 0, ySize * 3);
            return;
        }

        int[] pixels = frame.pixels();
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        int chromaSize = chromaWidth * chromaHeight;

        if (yPlane.length < ySize) {
            yPlane = new byte[ySize];
        }
        if (uPlane.length < (use420 ? chromaSize : ySize)) {
            uPlane = new byte[use420 ? chromaSize : ySize];
        }
        if (vPlane.length < (use420 ? chromaSize : ySize)) {
            vPlane = new byte[use420 ? chromaSize : ySize];
        }

        converter.convert(pixels, width, height, use420, yPlane, uPlane, vPlane);

        output.write(FRAME_HEADER);
        output.write(yPlane, 0, ySize);
        if (use420) {
            output.write(uPlane, 0, chromaSize);
            output.write(vPlane, 0, chromaSize);
        } else {
            output.write(uPlane, 0, ySize);
            output.write(vPlane, 0, ySize);
        }
    }

    private void releaseFrame(ScaledFrame frame) {
        if (frame == null) {
            return;
        }
        if (frame.pixels() != null) {
            pool.release(frame.pixels());
        }
        if (frame.yuv() != null) {
            yuvPool.release(frame.yuv());
        }
    }

    private static String tail(String text) {
        if (text == null || text.isEmpty()) {
            return "(no output)";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(trimmed.length() - 500);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
