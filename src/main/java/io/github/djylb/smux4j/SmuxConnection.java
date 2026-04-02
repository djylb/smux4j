package io.github.djylb.smux4j;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;

/**
 * Duplex byte-stream abstraction used by the library.
 */
public interface SmuxConnection extends Closeable, Flushable {
    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    default void flush() throws IOException {
        getOutputStream().flush();
    }

    default SocketAddress getLocalAddress() {
        return null;
    }

    default SocketAddress getRemoteAddress() {
        return null;
    }
}
