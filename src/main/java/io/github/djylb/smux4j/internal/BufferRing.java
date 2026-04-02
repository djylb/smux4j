package io.github.djylb.smux4j.internal;

/**
 * Power-of-two ring buffer used for queued stream payload chunks.
 */
public final class BufferRing {
    private byte[][] ownerBuffers;
    private int[] offsets;
    private int[] lengths;
    private int head;
    private int tail;
    private int size;
    private int mask;

    public BufferRing(int capacity) {
        int normalizedCapacity = 1;
        while (normalizedCapacity < Math.max(1, capacity)) {
            normalizedCapacity <<= 1;
        }

        this.ownerBuffers = new byte[normalizedCapacity][];
        this.offsets = new int[normalizedCapacity];
        this.lengths = new int[normalizedCapacity];
        this.mask = normalizedCapacity - 1;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void push(byte[] ownerBuffer, int length) {
        if (ownerBuffer == null) {
            throw new IllegalArgumentException("ownerBuffer must not be null");
        }
        if (length < 0 || length > ownerBuffer.length) {
            throw new IllegalArgumentException("invalid buffer length");
        }

        if (size == ownerBuffers.length) {
            grow();
        }

        ownerBuffers[tail] = ownerBuffer;
        offsets[tail] = 0;
        lengths[tail] = length;
        tail = (tail + 1) & mask;
        size++;
    }

    public Entry pop() {
        if (size == 0) {
            return null;
        }

        Entry entry = new Entry(ownerBuffers[head], offsets[head], lengths[head]);
        ownerBuffers[head] = null;
        offsets[head] = 0;
        lengths[head] = 0;
        head = (head + 1) & mask;
        size--;
        if (size == 0) {
            tail = head;
        }
        return entry;
    }

    public ConsumeResult consumeFront(byte[] target, int targetOffset, int targetLength) {
        if (size == 0) {
            return new ConsumeResult(0, null);
        }

        int copied = Math.min(targetLength, lengths[head]);
        System.arraycopy(ownerBuffers[head], offsets[head], target, targetOffset, copied);
        offsets[head] += copied;
        lengths[head] -= copied;

        byte[] recycledBuffer = null;
        if (lengths[head] == 0) {
            recycledBuffer = ownerBuffers[head];
            ownerBuffers[head] = null;
            offsets[head] = 0;
            lengths[head] = 0;
            head = (head + 1) & mask;
            size--;
            if (size == 0) {
                tail = head;
            }
        }

        return new ConsumeResult(copied, recycledBuffer);
    }

    private void grow() {
        int newCapacity = ownerBuffers.length << 1;
        byte[][] newOwnerBuffers = new byte[newCapacity][];
        int[] newOffsets = new int[newCapacity];
        int[] newLengths = new int[newCapacity];

        for (int i = 0; i < size; i++) {
            int index = (head + i) & mask;
            newOwnerBuffers[i] = ownerBuffers[index];
            newOffsets[i] = offsets[index];
            newLengths[i] = lengths[index];
        }

        ownerBuffers = newOwnerBuffers;
        offsets = newOffsets;
        lengths = newLengths;
        head = 0;
        tail = size;
        mask = newCapacity - 1;
    }

    public static final class ConsumeResult {
        private final int bytesCopied;
        private final byte[] recycledBuffer;

        private ConsumeResult(int bytesCopied, byte[] recycledBuffer) {
            this.bytesCopied = bytesCopied;
            this.recycledBuffer = recycledBuffer;
        }

        public int getBytesCopied() {
            return bytesCopied;
        }

        public byte[] getRecycledBuffer() {
            return recycledBuffer;
        }
    }

    public static final class Entry {
        private final byte[] ownerBuffer;
        private final int offset;
        private final int length;

        private Entry(byte[] ownerBuffer, int offset, int length) {
            this.ownerBuffer = ownerBuffer;
            this.offset = offset;
            this.length = length;
        }

        public byte[] getOwnerBuffer() {
            return ownerBuffer;
        }

        public int getOffset() {
            return offset;
        }

        public int getLength() {
            return length;
        }
    }
}
