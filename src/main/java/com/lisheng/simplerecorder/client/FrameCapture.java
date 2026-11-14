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
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Asynchronous frame readback on the 26.3 GPU abstraction.
 *
 * <p>Minecraft 26.3 renders through {@code com.mojang.renderpearl} and can run
 * on either the OpenGL or the Vulkan backend. There is no portable
 * {@code glReadPixels} in that world — raw GL only exists on the OpenGL
 * backend — so this class does exactly what vanilla's screenshot code does:
 * {@code CommandEncoder.copyTextureToBuffer} into a CPU-visible
 * {@code GpuBuffer}, then map that buffer once the copy has completed. Both
 * backends implement those two calls, so one code path covers OpenGL and
 * Vulkan.</p>
 *
 * <p>A small ring of buffers keeps the render thread ahead of the GPU: frame N
 * issues a copy, frame N+1 maps the finished buffer while frame N+1's copy runs.
 * The expensive part (flip + downscale + RGB, or the YUV conversion done by the
 * encoder) happens on a worker thread, never on the render thread.</p>
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

    private int width;
    private int height;
    private int bytesPerPixel;
    private boolean allocated;
    private boolean closed;
    private boolean sizeChanged;
    private boolean errorReported;

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
     * Queues one readback of the render target. Must run on the render thread,
     * after the frame has been submitted and before the target is cleared.
     *
     * @return {@code false} when every slot is still busy or the target changed size
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

        Slot slot = freeSlot();
        if (slot == null) {
            return false;
        }

        try {
            CommandEncoder encoder = device.createCommandEncoder();
            // The completion callback runs once the GPU work is done; from then
            // on the buffer may be mapped on the render thread.
            encoder.copyTextureToBuffer(colorTexture, slot.buffer, 0L, slot::markReady, 0);
            encoder.submit();
        } catch (Throwable throwable) {
            reportError("GPU frame copy failed: " + throwable);
            return false;
        }

        slot.state = State.COPYING;
        slot.ready = false;
        slot.timestampNanos = captureNanos;
        return true;
    }

    /** Advances the ring: maps finished copies and delivers scaled frames. */
    public void poll() {
        if (closed) {
            return;
        }
        for (Slot slot : slots) {
            switch (slot.state) {
                case COPYING -> {
                    if (slot.ready) {
                        mapAndScale(slot);
                    }
                }
                case SCALING -> {
                    if (slot.scaled) {
                        finishScaling(slot);
                    }
                }
                default -> {
                }
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

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        poll();
        scaler.shutdown();
        try {
            if (!scaler.awaitTermination(5L, TimeUnit.SECONDS)) {
                // Leaking the buffers is safer than unmapping memory the worker
                // may still be reading.
                LOGGER.warn("Frame scaler did not stop; leaking its GPU buffers");
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
            slot.ready = false;
            slot.scaled = false;
            slot.state = State.FREE;
            if (slot.mapped != null) {
                slot.mapped.close();
                slot.mapped = null;
            }
            if (slot.buffer != null) {
                slot.buffer.close();
                slot.buffer = null;
            }
        }
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
        int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;
        for (Slot slot : slots) {
            if (slot.mapped != null) {
                slot.mapped.close();
                slot.mapped = null;
            }
            if (slot.buffer != null) {
                slot.buffer.close();
            }
            slot.buffer = device.createBuffer(() -> "SimpleRecorder frame", usage, size);
            slot.pixels = new byte[(int) size];
            slot.state = State.FREE;
            slot.ready = false;
            slot.scaled = false;
            slot.frame = null;
            slot.error = null;
        }
        allocated = true;
        LOGGER.info("Capture target {}x{} {} ({} bytes/pixel, {} backend)", width, height, format,
                bytesPerPixel, GpuCapabilities.backendName());
    }

    private Slot freeSlot() {
        for (Slot slot : slots) {
            if (slot.buffer != null && slot.state == State.FREE) {
                return slot;
            }
        }
        return null;
    }

    private void mapAndScale(Slot slot) {
        slot.state = State.SCALING;
        slot.scaled = false;
        slot.frame = null;
        slot.error = null;

        ByteBuffer view;
        try {
            GpuBufferSlice.MappedView mapped = slot.buffer.map(true, false);
            slot.mapped = mapped;
            view = mapped.data().order(ByteOrder.LITTLE_ENDIAN);
            // Copy out of the mapped memory now: the mapping is released on the
            // render thread as soon as the worker has its private copy.
            view.get(0, slot.pixels, 0, slot.pixels.length);
        } catch (Throwable throwable) {
            slot.error = throwable;
            slot.scaled = true;
            slot.ready = false;
            return;
        } finally {
            if (slot.mapped != null) {
                try {
                    slot.mapped.close();
                } catch (Throwable ignored) {
                    // Closing a mapped view should not fail; nothing to do if it does.
                }
                slot.mapped = null;
            }
        }
        slot.ready = false;

        byte[] pixels = slot.pixels;
        int frameWidth = width;
        int frameHeight = height;
        long timestamp = slot.timestampNanos;
        try {
            scaler.execute(() -> {
                int outputSize = FrameScaler.scaledWidth(frameWidth, scale)
                        * FrameScaler.scaledHeight(frameHeight, scale);
                int[] output = pool.acquire(outputSize);
                try {
                    slot.frame = FrameScaler.scaleRgbaBottomUp(ByteBuffer.wrap(pixels), frameWidth, frameHeight,
                            scale, output, timestamp);
                } catch (Throwable throwable) {
                    pool.release(output);
                    slot.error = throwable;
                } finally {
                    slot.scaled = true;
                }
            });
        } catch (RuntimeException exception) {
            // Executor already shutting down.
            slot.state = State.FREE;
        }
    }

    private void finishScaling(Slot slot) {
        ScaledFrame frame = slot.frame;
        Throwable error = slot.error;
        slot.frame = null;
        slot.error = null;
        slot.scaled = false;
        slot.state = State.FREE;

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
        byte[] pixels;
        long timestampNanos;
        volatile boolean ready;
        volatile boolean scaled;
        volatile ScaledFrame frame;
        volatile Throwable error;
        volatile State state = State.FREE;

        void markReady() {
            this.ready = true;
        }
    }
}
