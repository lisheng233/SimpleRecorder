package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.GpuYuvConverter;
import net.minecraft.client.shader.Framebuffer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.apache.logging.log4j.LogManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.apache.logging.log4j.Logger;

/**
 * Asynchronous OpenGL pixel-pack readback with a small PBO ring.
 *
 * <p>The render thread only queues {@code glReadPixels} into a PBO and creates a
 * fence. When the fence signals, the PBO is mapped read-only and handed to a
 * daemon scaling thread. The scaling thread reads the mapped memory, flips and
 * downscales it into a pooled {@link ScaledFrame}; the render thread unmaps the
 * PBO on a later frame. No {@code NativeImage}, no synchronous GPU stall, and
 * no per-frame heap allocation.</p>
 */
public final class PboCapture {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int PBO_COUNT = 3;

    private final double scale;
    private final FramePool pool;
    private final YuvPool yuvPool;
    private final Consumer<ScaledFrame> sink;
    private final ExecutorService scaler;
    private final Slot[] slots = new Slot[PBO_COUNT];
    private final boolean gpuRequested;
    private GpuYuvConverter converter;
    private boolean gpu;

    private int width;
    private int height;
    private int dstWidth;
    private int dstHeight;
    private int bufferSize;
    private boolean allocated;
    private boolean closed;
    private boolean sizeChanged;
    private boolean errorReported;

    public PboCapture(double scale, FramePool pool, YuvPool yuvPool,
                      Consumer<ScaledFrame> sink, boolean gpuRequested) {
        this.scale = scale;
        this.pool = pool;
        this.yuvPool = yuvPool;
        this.sink = sink;
        this.gpuRequested = gpuRequested;
        if (gpuRequested) {
            try {
                this.converter = new GpuYuvConverter();
                this.gpu = true;
            } catch (Throwable throwable) {
                LOGGER.warn("OpenGL YUV converter unavailable; using CPU conversion", throwable);
                this.gpu = false;
            }
        }
        this.scaler = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SimpleRecorder-PBO-Scaler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Queues one asynchronous readback. Must be called on the render thread.
     *
     * @return {@code false} when no PBO was free or the framebuffer changed size
     */
    public boolean capture(Framebuffer target, long captureNanos) {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("PboCapture must be called on the render thread");
        }
        if (closed || target == null || target.width <= 0 || target.height <= 0) {
            return false;
        }

        poll();

        if (!allocated || target.width != width || target.height != height) {
            if (allocated) {
                sizeChanged = true;
                return false;
            }
            allocate(target.width, target.height);
        }

        Slot slot = freeSlot();
        if (slot == null) {
            return false;
        }

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

        try {
            int readWidth;
            int readHeight;
            if (gpu) {
                converter.render(target.getColorTextureId(), width, height,
                        slot.targetFbo, dstWidth, dstHeight);
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, slot.targetFbo);
                readWidth = dstWidth;
                readHeight = dstHeight;
            } else {
                if (previousFramebuffer != target.frameBufferId) {
                    GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
                }
                readWidth = width;
                readHeight = height;
            }
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
            GL11.glReadPixels(0, 0, readWidth, readHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            slot.width = readWidth;
            slot.height = readHeight;
            slot.gpuFrame = gpu;
            slot.timestampNanos = captureNanos;
            slot.state = State.IN_FLIGHT;
            slot.scaled = false;
            slot.frame = null;
            slot.error = null;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        }

        int error = GL11.glGetError();
        if (error != 0) {
            reportError("glReadPixels failed with OpenGL error " + error);
            if (slot.fence != 0L) {
                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
            }
            slot.state = State.FREE;
            return false;
        }
        return true;
    }

    /**
     * Advances the PBO state machine and delivers finished frames on the render
     * thread. Safe to call every frame, including when capture is rate-limited.
     */
    public void poll() {
        if (closed) {
            return;
        }
        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }
            if (slot.state == State.IN_FLIGHT) {
                pollFence(slot);
            } else if (slot.state == State.SCALING && slot.scaled) {
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

    /**
     * Stops the scaling thread and deletes all GL objects. Must be called on the
     * render thread after the last capture.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        poll();
        scaler.shutdown();
        boolean terminated;
        try {
            terminated = scaler.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminated = false;
        }
        if (!terminated) {
            LOGGER.warn("PBO scaler did not stop; leaking its pixel buffers to avoid a use-after-free");
            return;
        }

        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }
            if (slot.frame != null) {
                releaseFrame(slot.frame);
                slot.frame = null;
            }
            unmap(slot);
            if (slot.fence != 0L) {
                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
            }
            if (slot.pbo != 0) {
                GL15.glDeleteBuffers(slot.pbo);
                slot.pbo = 0;
            }
            if (slot.targetFbo != 0) {
                GL30.glDeleteFramebuffers(slot.targetFbo);
                slot.targetFbo = 0;
            }
            if (slot.targetTexture != 0) {
                GL11.glDeleteTextures(slot.targetTexture);
                slot.targetTexture = 0;
            }
            slot.state = State.FREE;
        }
        if (converter != null) {
            converter.close();
            converter = null;
        }
        gpu = false;
        allocated = false;
    }

    private void allocate(int newWidth, int newHeight) {
        width = newWidth;
        height = newHeight;
        dstWidth = FrameScaler.scaledWidth(width, scale);
        dstHeight = FrameScaler.scaledHeight(height, scale);
        bufferSize = (gpu ? dstWidth * dstHeight : width * height) * 4;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = new Slot();
            }
            Slot slot = slots[i];
            if (slot.pbo == 0) {
                slot.pbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) bufferSize, GL15.GL_STREAM_READ);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            }
            if (gpu && slot.targetFbo == 0) {
                createGpuTarget(slot);
            }
            slot.state = State.FREE;
            slot.fence = 0L;
            slot.mapped = null;
            slot.scaled = false;
            slot.frame = null;
            slot.error = null;
        }
        allocated = true;
    }

    private Slot freeSlot() {
        for (Slot slot : slots) {
            if (slot != null && slot.state == State.FREE) {
                return slot;
            }
        }
        return null;
    }

    private void pollFence(Slot slot) {
        if (slot.fence == 0L) {
            mapAndSubmit(slot);
            return;
        }
        int status = GL32.glClientWaitSync(slot.fence, 0, 0L);
        if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
            GL32.glDeleteSync(slot.fence);
            slot.fence = 0L;
            mapAndSubmit(slot);
        } else if (status == GL32.GL_WAIT_FAILED) {
            GL32.glDeleteSync(slot.fence);
            slot.fence = 0L;
            reportError("glClientWaitSync failed");
            slot.state = State.FREE;
        }
    }

    private void mapAndSubmit(Slot slot) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
        ByteBuffer mapped;
        try {
            mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
        if (mapped == null) {
            reportError("glMapBuffer returned null");
            slot.state = State.FREE;
            return;
        }

        slot.mapped = mapped.order(ByteOrder.LITTLE_ENDIAN);
        slot.state = State.SCALING;
        slot.scaled = false;
        slot.frame = null;
        slot.error = null;

        int frameWidth = slot.width;
        int frameHeight = slot.height;
        long timestamp = slot.timestampNanos;
        boolean gpuFrame = slot.gpuFrame;
        try {
            scaler.execute(() -> {
                if (gpuFrame) {
                    byte[] yuv = null;
                    try {
                        int pixels = frameWidth * frameHeight;
                        yuv = yuvPool.acquire(pixels * 3);
                        ByteBuffer source = slot.mapped;
                        for (int i = 0; i < pixels; i++) {
                            yuv[i] = source.get();
                            yuv[pixels + i] = source.get();
                            yuv[pixels * 2 + i] = source.get();
                            source.get();
                        }
                        slot.frame = ScaledFrame.yuv(yuv, frameWidth, frameHeight, timestamp);
                    } catch (Throwable throwable) {
                        if (yuv != null) {
                            yuvPool.release(yuv);
                        }
                        slot.error = throwable;
                    } finally {
                        slot.scaled = true;
                    }
                } else {
                    int outputSize = FrameScaler.scaledWidth(frameWidth, scale)
                            * FrameScaler.scaledHeight(frameHeight, scale);
                    int[] output = pool.acquire(outputSize);
                    try {
                        slot.frame = FrameScaler.scaleRgbaBottomUp(
                                slot.mapped, frameWidth, frameHeight, scale, output, timestamp);
                    } catch (Throwable throwable) {
                        pool.release(output);
                        slot.error = throwable;
                    } finally {
                        slot.scaled = true;
                    }
                }
            });
        } catch (RuntimeException exception) {
            // The executor is shutting down.
            unmap(slot);
            slot.state = State.FREE;
        }
    }

    private void finishScaling(Slot slot) {
        ScaledFrame frame = slot.frame;
        Throwable error = slot.error;
        unmap(slot);
        slot.state = State.FREE;
        slot.frame = null;
        slot.scaled = false;
        slot.error = null;

        if (error != null) {
            reportError("PBO scaling failed: " + error.getMessage());
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

    private void createGpuTarget(Slot slot) {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        slot.targetTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, slot.targetTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, dstWidth, dstHeight, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        slot.targetFbo = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, slot.targetFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, slot.targetTexture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("YUV framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private void unmap(Slot slot) {
        if (slot.mapped == null) {
            return;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
        try {
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            slot.mapped = null;
        }
    }

    private void reportError(String message) {
        if (!errorReported) {
            errorReported = true;
            LOGGER.error("Async PBO capture failed: {}", message);
        }
    }

    private enum State {
        FREE,
        IN_FLIGHT,
        SCALING
    }

    private static final class Slot {
        int pbo;
        int targetTexture;
        int targetFbo;
        boolean gpuFrame;
        long fence;
        int width;
        int height;
        long timestampNanos;
        ByteBuffer mapped;
        volatile boolean scaled;
        volatile ScaledFrame frame;
        volatile Throwable error;
        State state = State.FREE;
    }
}
