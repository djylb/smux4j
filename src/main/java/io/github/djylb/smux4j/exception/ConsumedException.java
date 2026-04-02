package io.github.djylb.smux4j.exception;

public final class ConsumedException extends SmuxException {
    public ConsumedException() {
        super("peer consumed more than sent");
    }
}
