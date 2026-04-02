package io.github.djylb.smux4j.internal;

import java.io.IOException;

/**
 * Write result produced by the session send loop.
 */
public final class WriteResult {
    private final int bytesWritten;
    private final IOException error;

    public WriteResult(int bytesWritten, IOException error) {
        this.bytesWritten = bytesWritten;
        this.error = error;
    }

    public int getBytesWritten() {
        return bytesWritten;
    }

    public IOException getError() {
        return error;
    }
}
