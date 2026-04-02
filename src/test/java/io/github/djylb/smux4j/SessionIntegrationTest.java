package io.github.djylb.smux4j;

import io.github.djylb.smux4j.exception.SmuxTimeoutException;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionIntegrationTest {
    @Test
    public void transfersPayloadOverSingleStreamV1() throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(Config.VERSION_1)
                .build()
                .validate();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            Stream clientStream = clientSession.openStream();
            Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
            clientStream.write(payload);

            byte[] received = new byte[payload.length];
            int read = serverStream.read(received);
            assertEquals(payload.length, read);
            assertArrayEquals(payload, received);

            byte[] reply = "pong".getBytes(StandardCharsets.UTF_8);
            serverStream.write(reply);

            byte[] response = new byte[reply.length];
            assertEquals(reply.length, clientStream.read(response));
            assertArrayEquals(reply, response);

            clientStream.close();
            serverStream.close();
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void closeWritePropagatesEofInV2() throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(Config.VERSION_2)
                .build()
                .validate();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            Stream clientStream = clientSession.openStream();
            Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
            clientStream.write(payload);
            clientStream.closeWrite();

            byte[] received = new byte[payload.length];
            assertEquals(payload.length, serverStream.read(received));
            assertArrayEquals(payload, received);

            serverStream.setReadDeadline(Instant.now().plusSeconds(5));
            assertEquals(-1, serverStream.read(new byte[1]));

            serverStream.close();
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void closeWriteAllowsReadingResponseInV1() throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(Config.VERSION_1)
                .build()
                .validate();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            Stream clientStream = clientSession.openStream();
            Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            byte[] request = "hello from client".getBytes(StandardCharsets.UTF_8);
            clientStream.write(request);
            clientStream.closeWrite();

            byte[] requestBuffer = new byte[request.length];
            assertEquals(request.length, serverStream.read(requestBuffer));
            assertArrayEquals(request, requestBuffer);
            assertEquals(-1, serverStream.read(new byte[1]));

            byte[] response = "response from server".getBytes(StandardCharsets.UTF_8);
            serverStream.write(response);

            byte[] responseBuffer = new byte[response.length];
            assertEquals(response.length, clientStream.read(responseBuffer));
            assertArrayEquals(response, responseBuffer);

            try {
                clientStream.write(new byte[] {1});
                fail("write after closeWrite should fail");
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
    public void sessionClosePreventsFurtherOpens() throws Exception {
        SocketPair pair = SocketPair.open();
        Session session = null;
        Session peer = null;
        try {
            session = Smux.client(pair.client, null);
            peer = Smux.server(pair.server, null);
            session.close();
            try {
                session.openStream();
                fail("open after close should fail");
            } catch (ClosedChannelException expected) {
                // expected
            }
            assertEquals(0, session.numStreams());
        } finally {
            closeQuietly(session);
            closeQuietly(peer);
            pair.close();
        }
    }

    @Test
    public void doubleCloseThrowsClosedChannel() throws Exception {
        SocketPair pair = SocketPair.open();
        Session session = null;
        Session peer = null;
        try {
            session = Smux.client(pair.client, null);
            peer = Smux.server(pair.server, null);

            Stream stream = session.openStream();
            peer.acceptStream(Duration.ofSeconds(5));

            stream.close();
            try {
                stream.close();
                fail("double stream close should fail");
            } catch (ClosedChannelException expected) {
                // expected
            }

            session.close();
            try {
                session.close();
                fail("double session close should fail");
            } catch (ClosedChannelException expected) {
                // expected
            }
        } finally {
            closeQuietly(session);
            closeQuietly(peer);
            pair.close();
        }
    }

    @Test
    public void readDeadlineTimesOut() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            Stream clientStream = clientSession.openStream();
            Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            serverStream.setReadDeadline(Instant.now().minusMillis(1));
            try {
                serverStream.read(new byte[1]);
                fail("read with past deadline should time out");
            } catch (SmuxTimeoutException expected) {
                assertTrue(expected.getMessage().contains("timeout"));
            }

            clientStream.close();
            serverStream.close();
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void closeWriteTwiceThrowsClosedChannel() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            Stream clientStream = clientSession.openStream();
            serverSession.acceptStream(Duration.ofSeconds(5));

            clientStream.closeWrite();
            try {
                clientStream.closeWrite();
                fail("second closeWrite should fail");
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
    public void transferToEchoesPayloadInV1() throws Exception {
        transferToEchoesPayload(Config.VERSION_1);
    }

    @Test
    public void transferToEchoesPayloadInV2() throws Exception {
        transferToEchoesPayload(Config.VERSION_2);
    }

    @Test
    public void writeToAliasEchoesPayloadInV1() throws Exception {
        SocketPair pair = SocketPair.open();
        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, null);
            serverSession = Smux.server(pair.server, null);

            final Stream clientStream = clientSession.openStream();
            final Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));
            final byte[] payload = randomBytes(64 * 1024, 123L);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

            Thread echoWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[8 * 1024];
                    int remaining = payload.length;
                    try {
                        while (remaining > 0) {
                            int read = serverStream.read(buffer, 0, Math.min(buffer.length, remaining));
                            if (read < 0) {
                                fail("unexpected EOF before all payload was echoed");
                            }
                            serverStream.write(buffer, 0, read);
                            remaining -= read;
                        }
                        serverStream.closeWrite();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "writeTo-alias-echo");
            echoWorker.start();

            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        clientStream.write(payload);
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "writeTo-alias-writer");
            writer.start();

            ByteArrayOutputStream received = new ByteArrayOutputStream(payload.length);
            long copied = clientStream.writeTo(received);

            writer.join(5000L);
            echoWorker.join(5000L);
            if (writer.isAlive() || echoWorker.isAlive()) {
                fail("writeTo alias workers did not finish");
            }
            if (failure.get() != null) {
                throwAsException(failure.get());
            }
            assertEquals(payload.length, copied);
            assertArrayEquals(payload, received.toByteArray());
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void halfCloseBidirectionalInV1() throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(Config.VERSION_1)
                .build()
                .validate();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            final Stream clientStream = clientSession.openStream();
            final Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));
            final CountDownLatch clientWriteDone = new CountDownLatch(1);
            final CountDownLatch serverWriteDone = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

            Thread clientWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        clientStream.write("client data".getBytes(StandardCharsets.UTF_8));
                        clientWriteDone.countDown();
                        assertTrue(serverWriteDone.await(5, TimeUnit.SECONDS));

                        byte[] buffer = new byte[64];
                        int read = clientStream.read(buffer);
                        assertEquals("server data", new String(buffer, 0, read, StandardCharsets.UTF_8));
                        clientStream.closeWrite();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "client-half-close");

            Thread serverWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        serverStream.write("server data".getBytes(StandardCharsets.UTF_8));
                        serverWriteDone.countDown();
                        assertTrue(clientWriteDone.await(5, TimeUnit.SECONDS));

                        byte[] buffer = new byte[64];
                        int read = serverStream.read(buffer);
                        assertEquals("client data", new String(buffer, 0, read, StandardCharsets.UTF_8));
                        serverStream.closeWrite();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "server-half-close");

            clientWorker.start();
            serverWorker.start();
            clientWorker.join(5000L);
            serverWorker.join(5000L);

            if (clientWorker.isAlive() || serverWorker.isAlive()) {
                fail("half-close workers did not finish");
            }
            if (failure.get() != null) {
                throwAsException(failure.get());
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void halfCloseAutoCleansUpStreamsInV1() throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(Config.VERSION_1)
                .build()
                .validate();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            Stream clientStream = clientSession.openStream();
            Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            clientStream.closeWrite();
            serverStream.closeWrite();

            waitForNoStreams(clientSession, serverSession, Duration.ofSeconds(5));
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void randomLengthRandomDataTransferInV1() throws Exception {
        randomLengthRandomDataTransfer(Config.VERSION_1);
    }

    @Test
    public void randomLengthRandomDataTransferInV2() throws Exception {
        randomLengthRandomDataTransfer(Config.VERSION_2);
    }

    @Test
    public void supportsParallelStreamsInV1() throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(Config.VERSION_1)
                .build()
                .validate();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            final int streamCount = 4;
            final CountDownLatch done = new CountDownLatch(streamCount * 2);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

            for (int i = 0; i < streamCount; i++) {
                final int streamIndex = i;
                final Stream clientStream = clientSession.openStream();
                final Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

                Thread serverWorker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            byte[] buffer = new byte[256];
                            for (int round = 0; round < 20; round++) {
                                int read = serverStream.read(buffer);
                                serverStream.write(buffer, 0, read);
                            }
                            serverStream.close();
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                        } finally {
                            done.countDown();
                        }
                    }
                }, "server-parallel-" + i);
                serverWorker.start();

                Thread clientWorker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int round = 0; round < 20; round++) {
                                byte[] payload = ("stream-" + streamIndex + "-round-" + round).getBytes(StandardCharsets.UTF_8);
                                clientStream.write(payload);
                                byte[] echo = new byte[payload.length];
                                assertEquals(payload.length, clientStream.read(echo));
                                assertArrayEquals(payload, echo);
                            }
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                        } finally {
                            done.countDown();
                        }
                    }
                }, "client-parallel-" + i);
                clientWorker.start();
            }

            assertTrue("parallel stream workers timed out", done.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throwAsException(failure.get());
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    @Test
    public void supportsByteBufferReadAndWriteInV1() throws Exception {
        byteBufferRoundTrip(Config.VERSION_1);
    }

    @Test
    public void supportsByteBufferReadAndWriteInV2() throws Exception {
        byteBufferRoundTrip(Config.VERSION_2);
    }

    private static void transferToEchoesPayload(int version) throws Exception {
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
            final byte[] payload = randomBytes(256 * 1024, 42L + version);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

            Thread echoWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[32 * 1024];
                    int remaining = payload.length;
                    try {
                        while (remaining > 0) {
                            int read = serverStream.read(buffer, 0, Math.min(buffer.length, remaining));
                            if (read < 0) {
                                fail("unexpected EOF before all payload was echoed");
                            }
                            serverStream.write(buffer, 0, read);
                            remaining -= read;
                        }
                        serverStream.close();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "echo-worker-v" + version);
            echoWorker.start();

            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        clientStream.write(payload);
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "transfer-writer-v" + version);
            writer.start();

            ByteArrayOutputStream received = new ByteArrayOutputStream(payload.length);
            long copied = clientStream.transferTo(received);

            writer.join(5000L);
            echoWorker.join(5000L);
            if (writer.isAlive() || echoWorker.isAlive()) {
                fail("transfer workers did not finish");
            }
            if (failure.get() != null) {
                throwAsException(failure.get());
            }

            assertEquals(payload.length, copied);
            assertArrayEquals(payload, received.toByteArray());
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    private static void randomLengthRandomDataTransfer(int version) throws Exception {
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
            final int totalBytes = 512 * 1024;
            final int maxChunk = 16 * 1024;
            final long seed = 7_654_321L + version;
            final byte[] payload = randomBytes(totalBytes, seed);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

            Thread echoWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[maxChunk];
                    int echoed = 0;
                    try {
                        while (echoed < totalBytes) {
                            int read = serverStream.read(buffer, 0, Math.min(buffer.length, totalBytes - echoed));
                            if (read < 0) {
                                fail("unexpected EOF before echo completed");
                            }
                            serverStream.write(buffer, 0, read);
                            echoed += read;
                        }
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "random-echo-v" + version);
            echoWorker.start();

            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    Random lengthRandom = new Random(seed + 1L);
                    int sent = 0;
                    try {
                        while (sent < totalBytes) {
                            int chunk = Math.min(lengthRandom.nextInt(maxChunk) + 1, totalBytes - sent);
                            clientStream.write(payload, sent, chunk);
                            sent += chunk;
                        }
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }
            }, "random-writer-v" + version);
            writer.start();

            Random readLengthRandom = new Random(seed + 2L);
            byte[] received = new byte[maxChunk];
            int totalRead = 0;
            while (totalRead < totalBytes) {
                int requested = Math.min(readLengthRandom.nextInt(maxChunk) + 1, totalBytes - totalRead);
                int read = clientStream.read(received, 0, requested);
                assertTrue("expected positive read before end of echo", read > 0);
                for (int i = 0; i < read; i++) {
                    assertEquals("data mismatch at byte " + (totalRead + i), payload[totalRead + i], received[i]);
                }
                totalRead += read;
            }

            writer.join(5000L);
            echoWorker.join(5000L);
            if (writer.isAlive() || echoWorker.isAlive()) {
                fail("random transfer workers did not finish");
            }
            if (failure.get() != null) {
                throwAsException(failure.get());
            }
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    private static void byteBufferRoundTrip(int version) throws Exception {
        SocketPair pair = SocketPair.open();
        Config config = Config.builder()
                .version(version)
                .buildValidated();

        Session clientSession = null;
        Session serverSession = null;
        try {
            clientSession = Smux.client(pair.client, config);
            serverSession = Smux.server(pair.server, config);

            Stream clientStream = clientSession.openStream();
            Stream serverStream = serverSession.acceptStream(Duration.ofSeconds(5));

            byte[] request = "byte-buffer-request".getBytes(StandardCharsets.UTF_8);
            ByteBuffer requestBuffer = ByteBuffer.allocateDirect(request.length);
            requestBuffer.put(request);
            requestBuffer.flip();
            assertEquals(request.length, clientStream.write(requestBuffer));
            assertEquals(0, requestBuffer.remaining());

            ByteBuffer serverReadBuffer = ByteBuffer.allocateDirect(request.length);
            assertEquals(request.length, serverStream.read(serverReadBuffer));
            serverReadBuffer.flip();
            byte[] received = new byte[request.length];
            serverReadBuffer.get(received);
            assertArrayEquals(request, received);

            ByteBuffer responseBuffer = ByteBuffer.wrap("byte-buffer-response".getBytes(StandardCharsets.UTF_8));
            byte[] responseBytes = new byte[responseBuffer.remaining()];
            responseBuffer.mark();
            responseBuffer.get(responseBytes);
            responseBuffer.reset();
            assertEquals(responseBytes.length, serverStream.write(responseBuffer));
            assertEquals(0, responseBuffer.remaining());

            ByteBuffer clientReadBuffer = ByteBuffer.allocate(responseBytes.length);
            assertEquals(responseBytes.length, clientStream.read(clientReadBuffer));
            clientReadBuffer.flip();
            byte[] response = new byte[responseBytes.length];
            clientReadBuffer.get(response);
            assertArrayEquals(responseBytes, response);
        } finally {
            closeQuietly(clientSession);
            closeQuietly(serverSession);
            pair.close();
        }
    }

    private static void waitForNoStreams(Session clientSession, Session serverSession, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (clientSession.numStreams() == 0 && serverSession.numStreams() == 0) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("streams not cleaned up: client=" + clientSession.numStreams() + ", server=" + serverSession.numStreams());
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] payload = new byte[size];
        new Random(seed).nextBytes(payload);
        return payload;
    }

    private static void throwAsException(Throwable throwable) throws Exception {
        if (throwable instanceof Exception) {
            throw (Exception) throwable;
        }
        if (throwable instanceof AssertionError) {
            throw (AssertionError) throwable;
        }
        throw new RuntimeException(throwable);
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
}
