package io.github.djylb.smux4j.internal;

import io.github.djylb.smux4j.exception.SmuxTimeoutException;

import java.time.Instant;

/**
 * Helpers for converting optional deadlines into timed waits.
 */
public final class DeadlineSupport {
    private DeadlineSupport() {
    }

    public static long remainingMillis(Instant deadline) throws SmuxTimeoutException {
        if (deadline == null) {
            return Long.MAX_VALUE;
        }

        long remaining = deadline.toEpochMilli() - System.currentTimeMillis();
        if (remaining <= 0L) {
            throw new SmuxTimeoutException();
        }
        return remaining;
    }

    public static long waitSliceMillis(Instant deadline, long maxSliceMillis) throws SmuxTimeoutException {
        long remaining = remainingMillis(deadline);
        if (remaining == Long.MAX_VALUE) {
            return maxSliceMillis;
        }
        return Math.min(remaining, Math.max(1L, maxSliceMillis));
    }
}
