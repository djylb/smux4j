package io.github.djylb.smux4j.exception;

import java.net.SocketTimeoutException;

public final class SmuxTimeoutException extends SocketTimeoutException {
    public SmuxTimeoutException() {
        super("timeout");
    }
}
