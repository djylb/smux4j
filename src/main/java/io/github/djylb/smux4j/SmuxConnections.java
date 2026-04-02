package io.github.djylb.smux4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * Small adapters for turning existing Java transports into {@link SmuxConnection}.
 */
public final class SmuxConnections {
    private SmuxConnections() {
    }

    public static SmuxConnection of(Socket socket) {
        Objects.requireNonNull(socket, "socket");
        return new SocketSmuxConnection(socket);
    }

    public static SmuxConnection of(InputStream input, OutputStream output) {
        return of(input, output, null, null);
    }

    public static SmuxConnection of(ByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return of(channel, channel);
    }

    public static SmuxConnection of(ReadableByteChannel input, WritableByteChannel output) {
        return new ChannelSmuxConnection(input, output);
    }

    public static SmuxConnection of(
            InputStream input,
            OutputStream output,
            SocketAddress localAddress,
            SocketAddress remoteAddress
    ) {
        return new BasicSmuxConnection(input, output, localAddress, remoteAddress);
    }

    private static final class BasicSmuxConnection implements SmuxConnection {
        private final InputStream input;
        private final OutputStream output;
        private final SocketAddress localAddress;
        private final SocketAddress remoteAddress;

        private BasicSmuxConnection(
                InputStream input,
                OutputStream output,
                SocketAddress localAddress,
                SocketAddress remoteAddress
        ) {
            this.input = Objects.requireNonNull(input, "input");
            this.output = Objects.requireNonNull(output, "output");
            this.localAddress = localAddress;
            this.remoteAddress = remoteAddress;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return localAddress;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                input.close();
            } catch (IOException exception) {
                failure = exception;
            }

            try {
                output.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
            }

            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ChannelSmuxConnection implements SmuxConnection {
        private final ReadableByteChannel inputChannel;
        private final WritableByteChannel outputChannel;
        private final InputStream input;
        private final OutputStream output;

        private ChannelSmuxConnection(ReadableByteChannel inputChannel, WritableByteChannel outputChannel) {
            this.inputChannel = Objects.requireNonNull(inputChannel, "inputChannel");
            this.outputChannel = Objects.requireNonNull(outputChannel, "outputChannel");
            this.input = Channels.newInputStream(inputChannel);
            this.output = Channels.newOutputStream(outputChannel);
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                inputChannel.close();
            } catch (IOException exception) {
                failure = exception;
            }

            if (outputChannel != inputChannel) {
                try {
                    outputChannel.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    }
                }
            }

            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class SocketSmuxConnection implements SmuxConnection {
        private final Socket socket;

        private SocketSmuxConnection(Socket socket) {
            this.socket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public SocketAddress getLocalAddress() {
            return socket.getLocalSocketAddress();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return socket.getRemoteSocketAddress();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
