package io.github.djylb.smux4j.internal;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Small blocking I/O helpers used by the protocol loops.
 */
public final class IoSupport {
    private IoSupport() {
    }

    public static void readFully(InputStream input, byte[] target, int offset, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int read = input.read(target, offset + totalRead, length - totalRead);
            if (read < 0) {
                throw new EOFException();
            }
            totalRead += read;
        }
    }

    public static void writeFully(OutputStream output, byte[] source, int offset, int length) throws IOException {
        output.write(source, offset, length);
    }
}
