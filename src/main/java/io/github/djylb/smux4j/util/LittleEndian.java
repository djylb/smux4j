package io.github.djylb.smux4j.util;

/**
 * Small little-endian helpers used by frame parsing.
 */
public final class LittleEndian {
    private LittleEndian() {
    }

    public static int readUnsignedShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    public static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    public static long readUnsignedInt(byte[] data, int offset) {
        return ((long) data[offset] & 0xffL)
                | (((long) data[offset + 1] & 0xffL) << 8)
                | (((long) data[offset + 2] & 0xffL) << 16)
                | (((long) data[offset + 3] & 0xffL) << 24);
    }

    public static void writeUnsignedShort(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    public static void writeInt(byte[] data, int offset, int value) {
        writeUnsignedShort(data, offset, value);
        writeUnsignedShort(data, offset + 2, value >>> 16);
    }

    public static void writeUnsignedInt(byte[] data, int offset, long value) {
        writeInt(data, offset, (int) value);
    }
}
