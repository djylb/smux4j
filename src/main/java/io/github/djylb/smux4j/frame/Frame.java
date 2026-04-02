package io.github.djylb.smux4j.frame;

import java.util.Objects;

/**
 * Protocol frame header and payload container.
 */
public final class Frame {
    public static final int HEADER_SIZE = 8;
    public static final int UPDATE_SIZE = 8;
    public static final int INITIAL_PEER_WINDOW = 262_144;

    private final int version;
    private final Command command;
    private final long streamId;
    private final byte[] data;
    private final int dataOffset;
    private final int dataLength;

    public Frame(int version, Command command, long streamId, byte[] data) {
        this(version, command, streamId, data, 0, data == null ? 0 : data.length);
    }

    public Frame(int version, Command command, long streamId, byte[] data, int dataOffset, int dataLength) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (streamId < 0 || streamId > 0xffff_ffffL) {
            throw new IllegalArgumentException("streamId must fit into uint32");
        }
        if (data == null) {
            data = new byte[0];
        }
        if (dataOffset < 0 || dataLength < 0 || dataOffset > data.length - dataLength) {
            throw new IndexOutOfBoundsException("offset=" + dataOffset + ", length=" + dataLength + ", size=" + data.length);
        }

        this.version = version;
        this.command = command;
        this.streamId = streamId;
        this.data = data;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
    }

    public int getVersion() {
        return version;
    }

    public Command getCommand() {
        return command;
    }

    public long getStreamId() {
        return streamId;
    }

    public byte[] getDataArray() {
        return data;
    }

    public int getDataOffset() {
        return dataOffset;
    }

    public int getDataLength() {
        return dataLength;
    }

    public static Frame create(int version, Command command, long streamId) {
        return new Frame(version, command, streamId, new byte[0]);
    }

    public static Frame create(int version, Command command, long streamId, byte[] data) {
        return new Frame(version, command, streamId, data);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Frame)) {
            return false;
        }
        Frame that = (Frame) other;
        if (version != that.version
                || streamId != that.streamId
                || dataLength != that.dataLength
                || !command.equals(that.command)) {
            return false;
        }
        for (int index = 0; index < dataLength; index++) {
            if (data[dataOffset + index] != that.data[that.dataOffset + index]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(version, command, streamId, dataLength);
        for (int index = 0; index < dataLength; index++) {
            result = 31 * result + data[dataOffset + index];
        }
        return result;
    }

    @Override
    public String toString() {
        return "Frame{version=" + version
                + ", command=" + command
                + ", streamId=" + streamId
                + ", dataOffset=" + dataOffset
                + ", dataLength=" + dataLength
                + '}';
    }
}
