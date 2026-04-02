package io.github.djylb.smux4j.frame;

import io.github.djylb.smux4j.util.LittleEndian;

import java.util.Arrays;

/**
 * Header view matching the original raw smux frame header layout.
 */
public final class RawHeader {
    private final byte[] bytes;

    public RawHeader(byte[] bytes) {
        if (bytes == null || bytes.length != Frame.HEADER_SIZE) {
            throw new IllegalArgumentException("raw header must be exactly " + Frame.HEADER_SIZE + " bytes");
        }
        this.bytes = bytes.clone();
    }

    public int getVersion() {
        return bytes[0] & 0xff;
    }

    public Command getCommand() {
        return Command.fromCode(bytes[1] & 0xff);
    }

    public int getLength() {
        return LittleEndian.readUnsignedShort(bytes, 2);
    }

    public long getStreamId() {
        return LittleEndian.readUnsignedInt(bytes, 4);
    }

    public byte[] toByteArray() {
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }

    public static RawHeader fromFrame(Frame frame) {
        byte[] header = new byte[Frame.HEADER_SIZE];
        header[0] = (byte) frame.getVersion();
        header[1] = (byte) frame.getCommand().getCode();
        LittleEndian.writeUnsignedShort(header, 2, frame.getDataLength());
        LittleEndian.writeUnsignedInt(header, 4, frame.getStreamId());
        return new RawHeader(header);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RawHeader)) {
            return false;
        }
        RawHeader that = (RawHeader) other;
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "Version:" + getVersion()
                + " Cmd:" + getCommand().getCode()
                + " StreamID:" + getStreamId()
                + " Length:" + getLength();
    }
}
