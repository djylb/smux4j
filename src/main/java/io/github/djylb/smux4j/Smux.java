package io.github.djylb.smux4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * Entry points mirroring the original package-level smux helpers.
 */
public final class Smux {
    private Smux() {
    }

    public static Config defaultConfig() {
        return Config.defaultConfig();
    }

    public static void verifyConfig(Config config) {
        Objects.requireNonNull(config, "config").validate();
    }

    public static Session client(SmuxConnection connection) throws IOException {
        return client(connection, null);
    }

    public static Session client(SmuxConnection connection, Config config) throws IOException {
        return Session.client(connection, config);
    }

    public static Session client(InputStream input, OutputStream output) throws IOException {
        return client(input, output, null);
    }

    public static Session client(InputStream input, OutputStream output, Config config) throws IOException {
        return client(SmuxConnections.of(input, output), config);
    }

    public static Session client(ByteChannel channel) throws IOException {
        return client(SmuxConnections.of(channel), null);
    }

    public static Session client(ByteChannel channel, Config config) throws IOException {
        return client(SmuxConnections.of(channel), config);
    }

    public static Session client(ReadableByteChannel input, WritableByteChannel output) throws IOException {
        return client(input, output, null);
    }

    public static Session client(ReadableByteChannel input, WritableByteChannel output, Config config) throws IOException {
        return client(SmuxConnections.of(input, output), config);
    }

    public static Session client(Socket socket) throws IOException {
        return client(socket, null);
    }

    public static Session client(Socket socket, Config config) throws IOException {
        return client(SmuxConnections.of(socket), config);
    }

    public static Session server(SmuxConnection connection) throws IOException {
        return server(connection, null);
    }

    public static Session server(SmuxConnection connection, Config config) throws IOException {
        return Session.server(connection, config);
    }

    public static Session server(InputStream input, OutputStream output) throws IOException {
        return server(input, output, null);
    }

    public static Session server(InputStream input, OutputStream output, Config config) throws IOException {
        return server(SmuxConnections.of(input, output), config);
    }

    public static Session server(ByteChannel channel) throws IOException {
        return server(SmuxConnections.of(channel), null);
    }

    public static Session server(ByteChannel channel, Config config) throws IOException {
        return server(SmuxConnections.of(channel), config);
    }

    public static Session server(ReadableByteChannel input, WritableByteChannel output) throws IOException {
        return server(input, output, null);
    }

    public static Session server(ReadableByteChannel input, WritableByteChannel output, Config config) throws IOException {
        return server(SmuxConnections.of(input, output), config);
    }

    public static Session server(Socket socket) throws IOException {
        return server(socket, null);
    }

    public static Session server(Socket socket, Config config) throws IOException {
        return server(SmuxConnections.of(socket), config);
    }
}
