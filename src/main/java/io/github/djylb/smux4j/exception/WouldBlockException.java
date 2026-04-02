package io.github.djylb.smux4j.exception;

public final class WouldBlockException extends SmuxException {
    public WouldBlockException() {
        super("operation would block on IO");
    }
}
