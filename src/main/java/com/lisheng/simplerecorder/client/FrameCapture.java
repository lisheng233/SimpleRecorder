package com.lisheng.simplerecorder.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Non-blocking frame readback for Minecraft 26.3.
 *
 * <p>26.3 renders through {@code com.mojang.renderpearl} and may run on the
 * OpenGL or the Vulkan backend, so raw {@code glReadPixels} is off the table.
 * The copy is issued with {@code CommandEncoder.copyTextureToBuffer} and the
 * result is read straight out of a <em>persistently mapped</em> buffer:</p>
 *
 * <ul>
 *   <li>each slot maps its buffer once at allocation, so no {@code map()} call
 *       (a potential driver stall) happens per frame;</li>
 *   <li>the render thread only records the copy and submits it, it never waits
 *       for the GPU and never touches the pixel data;</li>
 *   <li>when the copy's completion callback fires, the buffer is handed to a
 *       worker thread which scales it straight out of the mapped memory into a
 *       pooled pixel array (no intermediate copy, no heap churn);</li>
 *   <li>if all slots are busy the frame is simply dropped — capture never blocks
 *       the render thread.</li>
 * </ul>
 */
public final class FrameCapture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SLOT_COUNT = 3;

    private final double scale;
    private final FramePool pool;
    private final YuvPool yuvPool;
    private final Consumer<ScaledFrame> sink;
    private final ExecutorService scaler;

    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final AtomicInteger busy = new AtomicInteger();

    private int width;
    private int height;
    private int bytesPerPixel;
    private boolean allocated;
    private boolean closed;
    private boolean sizeChanged;
    private boolean errorReported;

    // Diagnostics: microseconds spent on the render thread.
    private long captureNanos;
    private long encoderNanos;
    private long recordNanos;
    private long submitNanos;
    private long captureCalls;
    private long framesCopied;
    private long framesSkipped;

    public FrameCapture(double scale, FramePool pool, YuvPool yuvPool, Consumer<ScaledFrame> sink) {
        this.scale = scale;
        this.pool = pool;
        this.yuvPool = yuvPool;
        this.sink = sink;
        this.scaler = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SimpleRecorder-FrameScaler");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Slot();
        }
    }

    /**
     * Records one readback of the render target. Must run on the render thread.
     *
     * @return {@code false} when the frame was skipped (all slots busy, target
     *         resized, or the device rejected the copy)
     */
    public boolean capture(RenderTarget target, long captureNanos) {
        if (closed || target == null || target.width <= 0 || target.height <= 0) {
            return false;
        }
        GpuTexture colorTexture = target.getColorTexture();
        if (colorTexture == null) {
            return false;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return false;
        }

        poll();

        if (!allocated || target.width != width || target.height != height) {
            if (allocated) {
                sizeChanged = true;
                return false;
            }
            allocate(device, target, colorTexture);
        }

        if (busy.get() >= SLOT_COUNT) {
            framesSkipped++;
            return false;
        }

        Slot slot = freeSlot();
        if (slot == null) {
            framesSkipped++;
            return false;
        }

        long start = System.nanoTime();
        try {
            slot.state = State.COPYING;
            slot.ready = false;
            slot.timestampNanos = captureNanos;
            slot.pixelsRef = null;
            long t0 = System.nanoTime();
            CommandEncoder encoder = device.createCommandEncoder();
            long t1 = System.nanoTime();
            encoder.copyTextureToBuffer(colorTexture, slot.buffer, 0L, slot::markReady, 0);
            long t2 = System.nanoTime();
            encoder.submit();
            long t3 = System.nanoTime();
            encoderNanos += t1 - t0;
            recordNanos += t2 - t1;
            submitNanos += t3 - t2;
            busy.incrementAndGet();
            framesCopied++;
        } catch (Throwable throwable) {
            slot.state = State.FREE;
            reportError("GPU frame copy failed: " + throwable);
            return false;
        } finally {
            this.captureNanos += System.nanoTime() - start;
            this.captureCalls++;
        }
        return true;
    }

    /** Advances the ring. Only cheap bookkeeping happens on the render thread. */
    public void poll() {
        if (closed) {
            return;
        }
        for (Slot slot : slots) {
            if (slot.state == State.COPYING && slot.ready) {
                dispatch(slot);
            } else if (slot.state == State.SCALING && slot.done) {
                finishScaling(slot);
            }
        }
    }

    public boolean hasSizeChanged() {
        return sizeChanged;
    }

    public boolean hasError() {
        return errorReported;
    }

    public int frameWidth() {
        return width;
    }

    public int frameHeight() {
        return height;
    }

    /** Render-thread microseconds per capture call and per copied frame. */
    public String stats() {
        if (captureCalls == 0L) {
            return "no capture calls";
        }
        double scale = 1.0D / ((double) captureCalls * 1000.0D);
        return String.format(java.util.Locale.ROOT,
                "render-thread capture avg %.0f us/call (encoder %.0f, record %.0f, submit %.0f) over %d calls, %d frames copied, %d skipped",
                captureNanos * scale, encoderNanos * scale, recordNanos * scale, submitNanos * scale,
                captureCalls, framesCopied, framesSkipped);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scaler.shutdown();
        try {
            if (!scaler.awaitTermination(5L, TimeUnit.SECONDS)) {
                LOGGER.warn("Frame scaler did not stop; leaking its GPU buffers instead of unmapping in use");
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        for (Slot slot : slots) {
            if (slot.frame != null) {
                releaseFrame(slot.frame);
                slot.frame = null;
            }
            slot.state = State.FREE;
            slot.ready = false;
            if (slot.mapped != null) {
                try {
                    slot.mapped.close();
                } catch (Throwable ignored) {
                    // Nothing sensible to do while shutting down.
                }
                slot.mapped = null;
            }
            if (slot.buffer != null) {
                try {
                    slot.buffer.close();
                } catch (Throwable ignored) {
                    // Nothing sensible to do while shutting down.
                }
                slot.buffer = null;
            }
        }
        busy.set(0);
        allocated = false;
    }

    private void allocate(GpuDevice device, RenderTarget target, GpuTexture texture) {
        GpuFormat format = texture.getFormat();
        bytesPerPixel = format.blockSize();
        width = target.width;
        height = target.height;
        long size = (long) width * (long) height * (long) bytesPerPixel;
        if (size <= 0L) {
            throw new IllegalStateException("Main render target has an empty colour texture");
        }
        int usage = GpuBuffer.USAGE_COPY_DST
                | GpuBuffer.USAGE_MAP_READ
                | GpuBuffer.USAGE_HINT_CLIENT_STORAGE;
        for (Slot slot : slots) {
            if (slot.mapped != null) {
                slot.mapped.close();
                slot.mapped = null;
            }
            if (slot.buffer != null) {
                slot.buffer.close();
            }
            slot.buffer = device.createBuffer(() -> "SimpleRecorder frame", usage, size);
            // Mapped once for the lifetime of the slot: no per-frame map/unmap,
            // which is where a driver would otherwise stall the render thread.
            slot.mapped = slot.buffer.map(true, false);
            slot.data = slot.mapped.data().order(ByteOrder.LITTLE_ENDIAN);
            slot.state = State.FREE;
            slot.ready = false;
            slot.frame = null;
            slot.error = null;
            slot.pixelsRef = null;
        }
        allocated = true;
        LOGGER.info("Capture target {}x{} {} ({} bytes/pixel, {} backend, {} MB per slot)", width, height, format,
                bytesPerPixel, GpuCapabilities.backendName(), (size / (1024 * 1024)));
    }

    private Slot freeSlot() {
        for (Slot slot : slots) {
            if (slot.buffer != null && slot.state == State.FREE) {
                return slot;
            }
        }
        return null;
    }

    private void dispatch(Slot slot) {
        slot.state = State.SCALING;
        slot.frame = null;
        slot.error = null;
        int frameWidth = width;
        int frameHeight = height;
        long timestamp = slot.timestampNanos;
        ByteBuffer data = slot.data;
        try {
            scaler.execute(() -> {
                int outputSize = FrameScaler.scaledWidth(frameWidth, scale)
                        * FrameScaler.scaledHeight(frameHeight, scale);
                int[] output = pool.acquire(outputSize);
                try {
                    slot.pixelsRef = output;
                    slot.frame = FrameScaler.scaleRgbaBottomUp(data, frameWidth, frameHeight, scale, output, timestamp);
                } catch (Throwable throwable) {
                    pool.release(output);
                    slot.frame = null;
                    slot.error = throwable;
                } finally {
                    slot.done = true;
                }
            });
        } catch (RuntimeException exception) {
            slot.state = State.FREE;
            busy.decrementAndGet();
        }
    }

    private void finishScaling(Slot slot) {
        ScaledFrame frame = slot.frame;
        Throwable error = slot.error;
        slot.frame = null;
        slot.error = null;
        slot.done = false;
        slot.pixelsRef = null;
        slot.state = State.FREE;
        busy.decrementAndGet();

        if (error != null) {
            reportError("Frame scaling failed: " + error);
            return;
        }
        if (frame != null) {
            try {
                sink.accept(frame);
            } catch (Throwable throwable) {
                releaseFrame(frame);
                LOGGER.error("Could not deliver a captured frame", throwable);
            }
        }
    }

    private void releaseFrame(ScaledFrame frame) {
        if (frame.pixels() != null) {
            pool.release(frame.pixels());
        }
        if (frame.yuv() != null) {
            yuvPool.release(frame.yuv());
        }
    }

    private void reportError(String message) {
        if (!errorReported) {
            errorReported = true;
            LOGGER.error("Frame capture failed: {}", message);
        }
    }

    private enum State {
        FREE,
        COPYING,
        SCALING
    }

    private static final class Slot {
        GpuBuffer buffer;
        GpuBufferSlice.MappedView mapped;
        ByteBuffer data;
        int[] pixelsRef;
        long timestampNanos;
        volatile boolean ready;
        volatile boolean done;
        volatile ScaledFrame frame;
        volatile Throwable error;
        volatile State state = State.FREE;

        void markReady() {
            this.ready = true;
        }
    }
}
