package io.github.djylb.smux4j.frame;

import io.github.djylb.smux4j.util.LittleEndian;

import java.util.Arrays;

/**
 * Header payload used by the v2 UPD command.
 */
public final class UpdateHeader {
    private final byte[] bytes;

    public UpdateHeader(byte[] bytes) {
        if (bytes == null || bytes.length != Frame.UPDATE_SIZE) {
            throw new IllegalArgumentException("update header must be exactly " + Frame.UPDATE_SIZE + " bytes");
        }
        this.bytes = bytes.clone();
    }

    public long getConsumed() {
        return LittleEndian.readUnsignedInt(bytes, 0);
    }

    public long getWindow() {
        return LittleEndian.readUnsignedInt(bytes, 4);
    }

    public static UpdateHeader of(long consumed, long window) {
        byte[] bytes = new byte[Frame.UPDATE_SIZE];
        LittleEndian.writeUnsignedInt(bytes, 0, consumed);
        LittleEndian.writeUnsignedInt(bytes, 4, window);
        return new UpdateHeader(bytes);
    }

    public byte[] toByteArray() {
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UpdateHeader)) {
            return false;
        }
        UpdateHeader that = (UpdateHeader) other;
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "UpdateHeader{consumed=" + getConsumed()
                + ", window=" + getWindow()
                + '}';
    }
}
