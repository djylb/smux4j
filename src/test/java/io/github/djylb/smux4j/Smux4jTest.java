package io.github.djylb.smux4j;

import io.github.djylb.smux4j.frame.Frame;
import io.github.djylb.smux4j.frame.RawHeader;
import io.github.djylb.smux4j.frame.UpdateHeader;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class Smux4jTest {
    @Test
    public void usesExpectedPackageName() {
        assertEquals("io.github.djylb.smux4j", Smux.class.getPackage().getName());
    }

    @Test
    public void exposesDefaultConfig() {
        Config config = Smux.defaultConfig();

        assertNotNull(config);
        assertEquals(Config.VERSION_1, config.getVersion());
        assertEquals(Duration.ofSeconds(10), config.getKeepAliveInterval());
    }

    @Test
    public void exposesFrameConstants() {
        assertEquals(8, Frame.HEADER_SIZE);
    }

    @Test
    public void rawHeaderStringMatchesOriginalFormat() {
        RawHeader header = new RawHeader(new byte[] {1, 0, 100, 0, 1, 0, 0, 0});

        assertEquals("Version:1 Cmd:0 StreamID:1 Length:100", header.toString());
    }

    @Test
    public void frameSupportsValueSemanticsAcrossOffsets() {
        byte[] payload = new byte[] {9, 1, 2, 3, 8};
        Frame left = new Frame(1, io.github.djylb.smux4j.frame.Command.PSH, 7L, payload, 1, 3);
        Frame right = Frame.create(1, io.github.djylb.smux4j.frame.Command.PSH, 7L, new byte[] {1, 2, 3});

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertTrue(left.toString().contains("dataLength=3"));
    }

    @Test
    public void updateHeaderSupportsValueSemantics() {
        UpdateHeader left = UpdateHeader.of(12L, 34L);
        UpdateHeader right = new UpdateHeader(left.toByteArray());

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertTrue(left.toString().contains("consumed=12"));
    }

    @Test
    public void supportsBuilderStyleConfig() {
        Config config = Config.builder()
                .version(Config.VERSION_2)
                .keepAliveInterval(Duration.ofSeconds(5))
                .keepAliveTimeout(Duration.ofSeconds(15))
                .maxFrameSize(16_384)
                .build()
                .validate();

        assertEquals(Config.VERSION_2, config.getVersion());
        assertEquals(Duration.ofSeconds(5), config.getKeepAliveInterval());
        assertEquals(16_384, config.getMaxFrameSize());
    }

    @Test
    public void builderSupportsMillisecondConvenienceAndValidatedBuild() {
        Config config = Config.builder()
                .keepAliveIntervalMillis(2_500L)
                .keepAliveTimeoutMillis(7_500L)
                .buildValidated();

        assertEquals(Duration.ofMillis(2_500L), config.getKeepAliveInterval());
        assertEquals(Duration.ofMillis(7_500L), config.getKeepAliveTimeout());
    }

    @Test
    public void configSupportsValueSemantics() {
        Config left = Config.builder()
                .version(Config.VERSION_2)
                .keepAliveInterval(Duration.ofSeconds(3))
                .maxFrameSize(8_192)
                .build();
        Config same = left.copy();
        Config different = left.toBuilder()
                .maxFrameSize(4_096)
                .build();

        assertEquals(left, same);
        assertEquals(left.hashCode(), same.hashCode());
        assertNotEquals(left, different);
        assertTrue(left.toString().contains("maxFrameSize=8192"));
    }

    @Test
    public void createsSessionFromJavaStreams() throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Session session = null;
        try {
            session = Smux.client(input, output, null);

            assertNotNull(session);
            assertTrue(session.isClient());
            assertNotNull(session.getConnection());
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    public void exposesNoConfigConvenienceOverloads() throws IOException {
        ByteArrayInputStream clientInput = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        ByteArrayInputStream serverInput = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream serverOutput = new ByteArrayOutputStream();

        Session clientSession = null;
        Session serverSession = null;
        Session directSession = null;
        try {
            clientSession = Smux.client(clientInput, clientOutput);
            serverSession = Smux.server(serverInput, serverOutput);
            directSession = Smux.client(SmuxConnections.of(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));

            assertNotNull(clientSession);
            assertNotNull(serverSession);
            assertNotNull(directSession);
            assertTrue(clientSession.isClient());
            assertFalse(serverSession.isClient());
            assertTrue(clientSession.isOpen());
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            closeQuietly(directSession);
        }
    }

    @Test
    public void exposesNioChannelConvenienceOverloads() throws IOException {
        TrackingByteChannel duplexChannel = new TrackingByteChannel();
        TrackingReadableByteChannel readChannel = new TrackingReadableByteChannel();
        TrackingWritableByteChannel writeChannel = new TrackingWritableByteChannel();

        Session duplexSession = null;
        Session splitSession = null;
        try {
            duplexSession = Smux.client(duplexChannel);
            splitSession = Smux.server(readChannel, writeChannel);

            assertNotNull(duplexSession);
            assertNotNull(splitSession);
            assertTrue(duplexSession.isClient());
            assertFalse(splitSession.isClient());
        } finally {
            closeQuietly(duplexSession);
            closeQuietly(splitSession);
        }
    }

    @Test
    public void adaptsNioChannelsWithoutDoubleClose() throws IOException {
        TrackingByteChannel duplexChannel = new TrackingByteChannel();
        TrackingReadableByteChannel readChannel = new TrackingReadableByteChannel();
        TrackingWritableByteChannel writeChannel = new TrackingWritableByteChannel();

        SmuxConnection duplexConnection = SmuxConnections.of(duplexChannel);
        SmuxConnection splitConnection = SmuxConnections.of(readChannel, writeChannel);

        assertNotNull(duplexConnection.getInputStream());
        assertNotNull(duplexConnection.getOutputStream());
        assertNotNull(splitConnection.getInputStream());
        assertNotNull(splitConnection.getOutputStream());

        duplexConnection.close();
        splitConnection.close();

        assertEquals(1, duplexChannel.getCloseCount());
        assertEquals(1, readChannel.getCloseCount());
        assertEquals(1, writeChannel.getCloseCount());
    }

    @Test
    public void streamExposesInputAndOutputViews() throws IOException {
        Session session = null;
        try {
            session = Smux.client(
                    SmuxConnections.of(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()),
                    null
            );
            Stream stream = new Stream(1L, 1024, session);

            assertNotNull(stream.getInputStream());
            assertNotNull(stream.getOutputStream());
            assertSame(session, stream.getSession());
            assertTrue(stream instanceof ByteChannel);
            assertTrue(stream.isOpen());
            assertTrue(session.isOpen());
        } finally {
            closeQuietly(session);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static final class TrackingByteChannel implements ByteChannel {
        private boolean open = true;
        private int closeCount;

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public int write(ByteBuffer src) {
            int size = src.remaining();
            src.position(src.limit());
            return size;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                closeCount++;
            }
        }

        private int getCloseCount() {
            return closeCount;
        }
    }

    private static final class TrackingReadableByteChannel implements ReadableByteChannel {
        private boolean open = true;
        private int closeCount;

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                closeCount++;
            }
        }

        private int getCloseCount() {
            return closeCount;
        }
    }

    private static final class TrackingWritableByteChannel implements WritableByteChannel {
        private boolean open = true;
        private int closeCount;

        @Override
        public int write(ByteBuffer src) {
            int size = src.remaining();
            src.position(src.limit());
            return size;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                closeCount++;
            }
        }

        private int getCloseCount() {
            return closeCount;
        }
    }
}
