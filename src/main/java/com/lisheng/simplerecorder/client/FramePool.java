package com.lisheng.simplerecorder.client;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Small pool for the large pixel arrays used by the capture/encode pipeline.
 * At 960x540 each array is about 2 MB, so recycling avoids a steady stream of
 * large allocations while recording.
 */
public final class FramePool {
    private static final int MAX_IDLE = 16;

    private final ConcurrentLinkedQueue<int[]> idle = new ConcurrentLinkedQueue<>();

    public int[] acquire(int length) {
        int[] pixels = idle.poll();
        if (pixels != null && pixels.length == length) {
            return pixels;
        }
        return new int[length];
    }

    public void release(int[] pixels) {
        if (pixels == null) {
            return;
        }
        if (idle.size() < MAX_IDLE) {
            idle.offer(pixels);
        }
    }
}
