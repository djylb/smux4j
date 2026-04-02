package io.github.djylb.smux4j.internal;

import io.github.djylb.smux4j.frame.Frame;

import java.util.concurrent.CompletableFuture;

/**
 * Write request consumed by the session send loop.
 */
public final class WriteRequest {
    private final WriteClass writeClass;
    private final Frame frame;
    private final long sequence;
    private final CompletableFuture<WriteResult> resultFuture;

    public WriteRequest(WriteClass writeClass, Frame frame, long sequence, CompletableFuture<WriteResult> resultFuture) {
        this.writeClass = writeClass;
        this.frame = frame;
        this.sequence = sequence;
        this.resultFuture = resultFuture;
    }

    public WriteClass getWriteClass() {
        return writeClass;
    }

    public Frame getFrame() {
        return frame;
    }

    public long getSequence() {
        return sequence;
    }

    public CompletableFuture<WriteResult> getResultFuture() {
        return resultFuture;
    }
}
