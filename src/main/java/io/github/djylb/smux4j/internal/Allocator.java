package io.github.djylb.smux4j.internal;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Slab-style allocator for frame payload buffers.
 */
public final class Allocator {
    public static final Allocator DEFAULT = new Allocator();

    private final ConcurrentLinkedQueue<byte[]>[] buffers;

    @SuppressWarnings("unchecked")
    public Allocator() {
        this.buffers = new ConcurrentLinkedQueue[17];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = new ConcurrentLinkedQueue<byte[]>();
        }
    }

    public byte[] acquire(int size) {
        if (size <= 0 || size > 65_536) {
            return null;
        }

        int bits = mostSignificantBit(size);
        int bucket = size == (1 << bits) ? bits : bits + 1;
        byte[] buffer = buffers[bucket].poll();
        if (buffer == null) {
            buffer = new byte[1 << bucket];
        }
        return buffer;
    }

    public void release(byte[] buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("allocator release() incorrect buffer size");
        }

        int capacity = buffer.length;
        if (capacity == 0 || capacity > 65_536) {
            throw new IllegalArgumentException("allocator release() incorrect buffer size");
        }

        int bits = mostSignificantBit(capacity);
        if (capacity != (1 << bits)) {
            throw new IllegalArgumentException("allocator release() incorrect buffer size");
        }

        buffers[bits].offer(buffer);
    }

    public static int mostSignificantBit(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(size);
    }
}
