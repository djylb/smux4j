package io.github.djylb.smux4j;

import io.github.djylb.smux4j.exception.InvalidProtocolException;
import io.github.djylb.smux4j.exception.SmuxTimeoutException;
import io.github.djylb.smux4j.frame.Command;
import io.github.djylb.smux4j.frame.Frame;
import io.github.djylb.smux4j.util.LittleEndian;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionCompatibilityTest {
    @Test
    public void tinyReadBufferPreservesEchoedPayload() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            final Stream clientStream = clientSession.openStream();
            final Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            Thread echoWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[64];
                    try {
                        while (true) {
                            int read = serverStream.read(buffer);
                            if (read < 0) {
                                return;
                            }
                            serverStream.write(buffer, 0, read);
                        }
                    } catch (IOException ignored) {
                        // Session shutdown ends the loop.
                    }
                }
            }, "compat-echo");
            echoWorker.setDaemon(true);
            echoWorker.start();

            byte[] tinyBuffer = new byte[6];
            StringBuilder sent = new StringBuilder();
            StringBuilder received = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                String message = "hello" + i;
                byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                sent.append(message);
                clientStream.write(payload);

                int readTotal = 0;
                while (readTotal < payload.length) {
                    int read = clientStream.read(tinyBuffer);
                    assertTrue(read > 0);
                    readTotal += read;
                    received.append(new String(tinyBuffer, 0, read, StandardCharsets.UTF_8));
                }
            }

            assertEquals(sent.toString(), received.toString());
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void acceptDeadlineTimesOut() throws Exception {
        SocketPair pair = SocketPair.open();
        Session serverSession = null;
        Session clientSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            serverSession.setDeadline(Instant.now().minusMillis(1));
            try {
                serverSession.acceptStream();
                fail("accept with past deadline should time out");
            } catch (SmuxTimeoutException expected) {
                assertTrue(expected.getMessage().contains("timeout"));
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void keepAliveTimeoutClosesInactiveSession() throws Exception {
        ServerSocket listener = new ServerSocket(0);
        Socket client = null;
        try {
            Thread acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket server = listener.accept();
                        server.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "keepalive-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            client = new Socket("127.0.0.1", listener.getLocalPort());
            Config config = Config.builder()
                    .keepAliveInterval(Duration.ofSeconds(1))
                    .keepAliveTimeout(Duration.ofSeconds(2))
                    .build()
                    .validate();

            Session session = Smux.client(client, config);
            waitUntilClosed(session, Duration.ofSeconds(4));
        } finally {
            if (client != null) {
                client.close();
            }
            listener.close();
        }
    }

    @Test
    public void keepAliveTimeoutClosesWhenWritesBlock() throws Exception {
        SocketPair pair = SocketPair.open();
        Session session = null;
        try {
            Config config = Config.builder()
                    .keepAliveInterval(Duration.ofSeconds(1))
                    .keepAliveTimeout(Duration.ofSeconds(2))
                    .build()
                    .validate();

            SmuxConnection connection = new BlockingWriteSocketConnection(pair.client);
            session = Smux.client(connection, config);
            waitUntilClosed(session, Duration.ofSeconds(4));
        } finally {
            closeQuietly(session);
            pair.close();
        }
    }

    @Test
    public void surfacesProtocolErrorOnInvalidFrame() throws Exception {
        SocketPair pair = SocketPair.open();
        Session serverSession = null;
        Session clientSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            byte[] frame = new byte[Frame.HEADER_SIZE];
            frame[0] = (byte) Config.VERSION_1;
            frame[1] = (byte) Command.UPD.getCode();
            LittleEndian.writeUnsignedShort(frame, 2, 0);
            LittleEndian.writeUnsignedInt(frame, 4, 1L);
            pair.client.getOutputStream().write(frame);
            pair.client.getOutputStream().flush();

            try {
                serverSession.acceptStream(Duration.ofSeconds(5));
                fail("invalid frame should trip protocol error");
            } catch (InvalidProtocolException expected) {
                assertTrue(expected.getMessage().contains("invalid protocol"));
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void hidesAddressesWhenConnectionDoesNotExposeThem() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(new HiddenSocketConnection(pair.server), null);

            Stream clientStream = clientSession.openStream();
            Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            assertNull(serverSession.getLocalAddress());
            assertNull(serverSession.getRemoteAddress());
            assertNull(serverStream.getLocalAddress());
            assertNull(serverStream.getRemoteAddress());

            clientStream.close();
            serverStream.close();
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void closeFutureCompletesAfterClose() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            CompletableFuture<Void> closeFuture = clientSession.closeFuture();
            assertNotNull(closeFuture);
            assertFalse(closeFuture.isDone());
            assertSame(closeFuture, clientSession.getCloseFuture());

            clientSession.close();

            assertTrue(closeFuture.isDone());
            closeFuture.get();
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void streamCloseFutureCompletesAfterClose() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            Stream stream = clientSession.openStream();
            serverSession.acceptStream(Duration.ofSeconds(5));

            CompletableFuture<Void> closeFuture = stream.closeFuture();
            assertNotNull(closeFuture);
            assertFalse(closeFuture.isDone());
            assertSame(closeFuture, stream.getCloseFuture());

            stream.close();

            assertTrue(closeFuture.isDone());
            closeFuture.get();
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void openAndAcceptAliasesWork() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            assertNotNull(clientSession.open());
            assertNotNull(serverSession.accept(Duration.ofSeconds(5)));
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void streamIdIsNonZero() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            clientSession.openStream();
            Stream stream = serverSession.acceptStream(Duration.ofSeconds(5));
            assertTrue(stream.getId() != 0L);
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void writeFrameInternalFailsAfterSessionClose() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);
            clientSession.close();

            try {
                clientSession.writeFrameInternal(
                        Frame.create(Config.VERSION_1, Command.NOP, 0L),
                        Instant.now().plusSeconds(1),
                        io.github.djylb.smux4j.internal.WriteClass.DATA
                );
                fail("writeFrameInternal on closed session should fail");
            } catch (ClosedChannelException expected) {
                // expected
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void writeFrameInternalTimesOutOnPastDeadline() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);
            try {
                clientSession.writeFrameInternal(
                        Frame.create(Config.VERSION_1, Command.NOP, 0L),
                        Instant.now().minusSeconds(1),
                        io.github.djylb.smux4j.internal.WriteClass.DATA
                );
                fail("writeFrameInternal with past deadline should time out");
            } catch (SmuxTimeoutException expected) {
                assertTrue(expected.getMessage().contains("timeout"));
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void readAfterSessionCloseFails() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);
            Stream stream = clientSession.openStream();
            serverSession.acceptStream(Duration.ofSeconds(5));

            clientSession.close();

            try {
                stream.read(new byte[8]);
                fail("read after session close should fail");
            } catch (ClosedChannelException expected) {
                // expected
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void writeAfterConnectionCloseFails() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);
            Stream stream = clientSession.openStream();
            serverSession.acceptStream(Duration.ofSeconds(5));

            clientSession.getConnection().close();

            try {
                stream.write("write after connection close".getBytes(StandardCharsets.UTF_8));
                fail("write after connection close should fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void writeAfterStreamCloseFails() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);
            Stream stream = clientSession.openStream();
            serverSession.acceptStream(Duration.ofSeconds(5));

            stream.close();
            try {
                stream.write("write after close".getBytes(StandardCharsets.UTF_8));
                fail("write after close should fail");
            } catch (ClosedChannelException expected) {
                // expected
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void sendWithoutRecvStillDeliversData() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            final Stream clientStream = clientSession.openStream();
            final Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            Thread echoWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[1024];
                    try {
                        while (true) {
                            int read = serverStream.read(buffer);
                            if (read < 0) {
                                return;
                            }
                            serverStream.write(buffer, 0, read);
                        }
                    } catch (IOException ignored) {
                        // Session shutdown ends the loop.
                    }
                }
            }, "send-without-recv-echo");
            echoWorker.setDaemon(true);
            echoWorker.start();

            for (int i = 0; i < 100; i++) {
                clientStream.write(("hello" + i).getBytes(StandardCharsets.UTF_8));
            }

            byte[] oneByte = new byte[1];
            assertEquals(1, clientStream.read(oneByte));
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void serverCanInitiateStreamAndClientCanAccept() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            Stream serverStream = serverSession.openStream();
            Stream clientStream = clientSession.acceptStream(Duration.ofSeconds(5));

            byte[] payload = "server hello".getBytes(StandardCharsets.UTF_8);
            serverStream.write(payload);
            byte[] received = new byte[payload.length];
            assertEquals(payload.length, clientStream.read(received));
            assertEquals("server hello", new String(received, StandardCharsets.UTF_8));
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void concurrentStreamCloseDoesNotHangSessionClose() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            List<Stream> streams = new ArrayList<Stream>();
            for (int i = 0; i < 50; i++) {
                streams.add(clientSession.openStream());
                serverSession.acceptStream(Duration.ofSeconds(5));
            }

            final CountDownLatch done = new CountDownLatch(streams.size());
            for (final Stream stream : streams) {
                Thread closer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            stream.close();
                        } catch (IOException ignored) {
                            // Session close races are allowed here.
                        } finally {
                            done.countDown();
                        }
                    }
                }, "concurrent-close-" + stream.getId());
                closer.setDaemon(true);
                closer.start();
            }

            clientSession.close();
            assertTrue("concurrent stream close should finish", done.await(5, TimeUnit.SECONDS));
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void numStreamsDropsToZeroAfterSessionClose() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            clientSession.openStream();
            serverSession.acceptStream(Duration.ofSeconds(5));
            assertEquals(1, clientSession.numStreams());

            clientSession.close();
            assertEquals(0, clientSession.numStreams());
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void duplicateSynDoesNotCreateDuplicateAcceptedStreams() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            byte[] syn = new byte[Frame.HEADER_SIZE];
            syn[0] = (byte) Config.VERSION_1;
            syn[1] = (byte) Command.SYN.getCode();
            LittleEndian.writeUnsignedShort(syn, 2, 0);
            LittleEndian.writeUnsignedInt(syn, 4, 1000L);

            pair.client.getOutputStream().write(syn);
            pair.client.getOutputStream().write(syn);
            pair.client.getOutputStream().flush();

            Stream first = serverSession.acceptStream(Duration.ofSeconds(5));
            assertEquals(1000L, first.getId());

            serverSession.setDeadline(Instant.now().plusMillis(200));
            try {
                serverSession.acceptStream();
                fail("duplicate syn should not produce a second accepted stream");
            } catch (SmuxTimeoutException expected) {
                assertTrue(expected.getMessage().contains("timeout"));
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void mediumStressTransferCrossesDefaultReceiveBucketInV1() throws Exception {
        mediumStressTransfer(Config.VERSION_1);
    }

    @Test
    public void mediumStressTransferCrossesDefaultReceiveBucketInV2() throws Exception {
        mediumStressTransfer(Config.VERSION_2);
    }

    private static void waitUntilClosed(Session session, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (session.isClosed()) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("session should have been closed by keepalive timeout");
    }

    private static void mediumStressTransfer(int version) throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(version)
                .build()
                .validate();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            final Stream clientStream = clientSession.openStream();
            final Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));
            final byte[] payload = new byte[(4 * 1024 * 1024) + (512 * 1024)];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i * 31);
            }

            final CompletableFuture<Throwable> echoFailure = new CompletableFuture<Throwable>();
            Thread echoWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[32 * 1024];
                    int echoed = 0;
                    try {
                        while (echoed < payload.length) {
                            int read = serverStream.read(buffer, 0, Math.min(buffer.length, payload.length - echoed));
                            if (read < 0) {
                                throw new AssertionError("unexpected eof during stress transfer");
                            }
                            serverStream.write(buffer, 0, read);
                            echoed += read;
                        }
                        echoFailure.complete(null);
                    } catch (Throwable throwable) {
                        echoFailure.complete(throwable);
                    }
                }
            }, "medium-stress-echo-v" + version);
            echoWorker.setDaemon(true);
            echoWorker.start();

            final CompletableFuture<Throwable> writerFailure = new CompletableFuture<Throwable>();
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        clientStream.write(payload);
                        writerFailure.complete(null);
                    } catch (Throwable throwable) {
                        writerFailure.complete(throwable);
                    }
                }
            }, "medium-stress-writer-v" + version);
            writer.setDaemon(true);
            writer.start();

            byte[] received = new byte[payload.length];
            int totalRead = 0;
            while (totalRead < received.length) {
                int read = clientStream.read(received, totalRead, received.length - totalRead);
                assertTrue("stress transfer should keep producing bytes", read > 0);
                totalRead += read;
            }

            Throwable writeFailure = writerFailure.get(5, TimeUnit.SECONDS);
            if (writeFailure != null) {
                if (writeFailure instanceof Exception) {
                    throw (Exception) writeFailure;
                }
                if (writeFailure instanceof AssertionError) {
                    throw (AssertionError) writeFailure;
                }
                throw new RuntimeException(writeFailure);
            }

            Throwable failure = echoFailure.get(5, TimeUnit.SECONDS);
            if (failure != null) {
                if (failure instanceof Exception) {
                    throw (Exception) failure;
                }
                if (failure instanceof AssertionError) {
                    throw (AssertionError) failure;
                }
                throw new RuntimeException(failure);
            }

            assertEquals(payload.length, totalRead);
            for (int i = 0; i < payload.length; i++) {
                if (payload[i] != received[i]) {
                    fail("stress transfer mismatch at byte " + i);
                }
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
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

    private static final class SocketPair implements AutoCloseable {
        private final Socket client;
        private final Socket server;

        private SocketPair(Socket client, Socket server) {
            this.client = client;
            this.server = server;
        }

        private static SocketPair open() throws IOException {
            ServerSocket listener = new ServerSocket(0);
            try {
                Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                Socket server = listener.accept();
                return new SocketPair(client, server);
            } finally {
                listener.close();
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                client.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                server.close();
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

    private static final class HiddenSocketConnection implements SmuxConnection {
        private final Socket socket;

        private HiddenSocketConnection(Socket socket) {
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
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class BlockingWriteSocketConnection implements SmuxConnection {
        private final Socket socket;
        private final OutputStream output;

        private BlockingWriteSocketConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.output = new BlockingOutputStream(socket.getOutputStream());
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final OutputStream delegate;

        private BlockingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            blockThenWrite(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            blockThenWrite(b, off, len);
        }

        private void blockThenWrite(byte[] buffer, int offset, int length) throws IOException {
            try {
                Thread.sleep(Duration.ofDays(1).toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("write interrupted", exception);
            }
            delegate.write(buffer, offset, length);
        }
    }
}
