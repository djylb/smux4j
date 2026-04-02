package io.github.djylb.smux4j.exception;

public final class GoAwayException extends SmuxException {
    public GoAwayException() {
        super("stream id overflows, should start a new connection");
    }
}
