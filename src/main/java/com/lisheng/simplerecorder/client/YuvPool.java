package com.lisheng.simplerecorder.client;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Small pool for packed YUV byte arrays produced by the GPU capture path.
 */
public final class YuvPool {
    private final Deque<byte[]> free = new ArrayDeque<>();
    private int pooled;

    public synchronized byte[] acquire(int size) {
        byte[] buffer = free.pollFirst();
        if (buffer == null || buffer.length != size) {
            return new byte[size];
        }
        pooled--;
        return buffer;
    }

    public synchronized void release(byte[] buffer) {
        if (buffer == null || pooled >= 4) {
            return;
        }
        free.addLast(buffer);
        pooled++;
    }

    public synchronized void clear() {
        free.clear();
        pooled = 0;
    }
}
