package io.github.djylb.smux4j.exception;

import java.io.IOException;

/**
 * Base checked exception for smux-specific failures.
 */
public class SmuxException extends IOException {
    public SmuxException(String message) {
        super(message);
    }
}
