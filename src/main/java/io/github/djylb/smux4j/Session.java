package io.github.djylb.smux4j;

import io.github.djylb.smux4j.exception.GoAwayException;
import io.github.djylb.smux4j.exception.InvalidProtocolException;
import io.github.djylb.smux4j.frame.Command;
import io.github.djylb.smux4j.frame.Frame;
import io.github.djylb.smux4j.frame.RawHeader;
import io.github.djylb.smux4j.frame.UpdateHeader;
import io.github.djylb.smux4j.internal.Allocator;
import io.github.djylb.smux4j.internal.DeadlineSupport;
import io.github.djylb.smux4j.internal.IoSupport;
import io.github.djylb.smux4j.internal.ShaperQueue;
import io.github.djylb.smux4j.internal.WriteClass;
import io.github.djylb.smux4j.internal.WriteRequest;
import io.github.djylb.smux4j.internal.WriteResult;
import io.github.djylb.smux4j.util.LittleEndian;

import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Logical multiplexing session translated from the original smux implementation.
 */
public final class Session implements Flushable, Channel {
    static final Duration OPEN_CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private static final int DEFAULT_ACCEPT_BACKLOG = 1024;
    private static final int MAX_SHAPER_SIZE = 1024;
    private static final long LOOP_WAIT_SLICE_MILLIS = 100L;
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private final long sessionNumber;
    private final SmuxConnection connection;
    private final InputStream input;
    private final OutputStream output;
    private final Config config;
    private final boolean client;
    private final AtomicBoolean goAway = new AtomicBoolean(false);
    private final Object nextStreamIdLock = new Object();
    private final AtomicInteger bucket;
    private final Object bucketMonitor = new Object();
    private final Object streamMonitor = new Object();
    private final Map<Long, Stream> streams = new HashMap<Long, Stream>();
    private final BlockingQueue<Stream> accepts = new LinkedBlockingQueue<Stream>(DEFAULT_ACCEPT_BACKLOG);
    private final BlockingQueue<WriteRequest> outgoing = new ArrayBlockingQueue<WriteRequest>(MAX_SHAPER_SIZE);
    private final ShaperQueue shaperQueue = new ShaperQueue();
    private final AtomicReference<IOException> socketReadError = new AtomicReference<IOException>();
    private final AtomicReference<IOException> socketWriteError = new AtomicReference<IOException>();
    private final AtomicReference<IOException> protocolError = new AtomicReference<IOException>();
    private final AtomicBoolean sessionIsActive = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong requestSequence = new AtomicLong();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<Void>();

    private final long keepAliveIntervalMillis;
    private final long keepAliveTimeoutMillis;

    private volatile long nextStreamId;
    private volatile Instant acceptDeadline;

    private Session(SmuxConnection connection, Config config, boolean client) throws IOException {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.input = connection.getInputStream();
        this.output = connection.getOutputStream();
        this.config = normalizeConfig(config);
        this.client = client;
        this.sessionNumber = SESSION_SEQUENCE.incrementAndGet();
        this.nextStreamId = client ? 1L : 0L;
        this.bucket = new AtomicInteger(this.config.getMaxReceiveBuffer());
        this.keepAliveIntervalMillis = this.config.getKeepAliveIntervalMillis();
        this.keepAliveTimeoutMillis = this.config.getKeepAliveTimeoutMillis();

        Thread recvThread = newThread("smux4j-recv-" + sessionNumber, new Runnable() {
            @Override
            public void run() {
                recvLoop();
            }
        });
        Thread sendThread = newThread("smux4j-send-" + sessionNumber, new Runnable() {
            @Override
            public void run() {
                sendLoop();
            }
        });
        Thread keepAliveThread = this.config.isKeepAliveDisabled() ? null : newThread("smux4j-keepalive-" + sessionNumber, new Runnable() {
            @Override
            public void run() {
                keepAliveLoop();
            }
        });

        recvThread.start();
        sendThread.start();
        if (keepAliveThread != null) {
            keepAliveThread.start();
        }
    }

    static Session client(SmuxConnection connection, Config config) throws IOException {
        return new Session(connection, config, true);
    }

    static Session client(SmuxConnection connection) throws IOException {
        return client(connection, null);
    }

    static Session server(SmuxConnection connection, Config config) throws IOException {
        return new Session(connection, config, false);
    }

    static Session server(SmuxConnection connection) throws IOException {
        return server(connection, null);
    }

    public Config getConfig() {
        return config.copy();
    }

    public SmuxConnection getConnection() {
        return connection;
    }

    public boolean isClient() {
        return client;
    }

    public Stream openStream() throws IOException {
        ensureSessionOpen();

        long streamId;
        synchronized (nextStreamIdLock) {
            if (goAway.get()) {
                throw new GoAwayException();
            }
            if (nextStreamId > 0xffff_ffffL - 2L) {
                goAway.set(true);
                throw new GoAwayException();
            }
            nextStreamId += 2L;
            streamId = nextStreamId;
        }

        Stream stream = new Stream(streamId, config.getMaxFrameSize(), this);
        writeControlFrame(Frame.create(config.getVersion(), Command.SYN, streamId));

        synchronized (streamMonitor) {
            IOException readError = socketReadError.get();
            if (readError != null) {
                throw readError;
            }
            IOException writeError = socketWriteError.get();
            if (writeError != null) {
                throw writeError;
            }
            if (closed.get()) {
                throw new ClosedChannelException();
            }
            streams.put(streamId, stream);
        }

        return stream;
    }

    public Stream open() throws IOException {
        return openStream();
    }

    public Stream acceptStream() throws IOException {
        return acceptStreamInternal(acceptDeadline);
    }

    public Stream accept() throws IOException {
        return acceptStream();
    }

    public Stream acceptStream(Duration timeout) throws IOException {
        Objects.requireNonNull(timeout, "timeout");
        return acceptStreamInternal(Instant.now().plus(timeout));
    }

    public Stream accept(Duration timeout) throws IOException {
        return acceptStream(timeout);
    }

    public int numStreams() {
        if (closed.get()) {
            return 0;
        }
        synchronized (streamMonitor) {
            return streams.size();
        }
    }

    public int getStreamCount() {
        return numStreams();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isOpen() {
        return !isClosed();
    }

    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    public CompletableFuture<Void> getCloseFuture() {
        return closeFuture();
    }

    public void setDeadline(Instant deadline) {
        this.acceptDeadline = deadline;
    }

    public Instant getDeadline() {
        return acceptDeadline;
    }

    public SocketAddress getLocalAddress() {
        return connection.getLocalAddress();
    }

    public SocketAddress getRemoteAddress() {
        return connection.getRemoteAddress();
    }

    @Override
    public void flush() throws IOException {
        ensureSessionOpen();
        connection.flush();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            throw new ClosedChannelException();
        }

        notifyBucketAvailable();
        signalAllStreams();

        List<Stream> snapshot;
        synchronized (streamMonitor) {
            snapshot = new ArrayList<Stream>(streams.values());
        }
        for (Stream stream : snapshot) {
            stream.sessionClose();
        }

        IOException closeError = null;
        try {
            connection.close();
        } catch (IOException exception) {
            closeError = exception;
        } finally {
            closeFuture.complete(null);
        }

        if (closeError != null) {
            throw closeError;
        }
    }

    void streamClosed(long streamId) {
        Stream stream;
        synchronized (streamMonitor) {
            stream = streams.remove(streamId);
        }
        if (stream == null) {
            return;
        }

        int recycledBytes = stream.recycleTokens();
        if (recycledBytes > 0 && bucket.addAndGet(recycledBytes) > 0) {
            notifyBucketAvailable();
        }
    }

    void returnTokens(int tokenCount) {
        if (tokenCount > 0 && bucket.addAndGet(tokenCount) > 0) {
            notifyBucketAvailable();
        }
    }

    IOException currentReadError() {
        return socketReadError.get();
    }

    IOException currentWriteError() {
        return socketWriteError.get();
    }

    IOException currentProtocolError() {
        return protocolError.get();
    }

    int getProtocolVersion() {
        return config.getVersion();
    }

    int getMaxStreamBuffer() {
        return config.getMaxStreamBuffer();
    }

    int writeFrameInternal(Frame frame, Instant deadline, WriteClass writeClass) throws IOException {
        CompletableFuture<WriteResult> resultFuture = new CompletableFuture<WriteResult>();
        WriteRequest request = new WriteRequest(writeClass, frame, requestSequence.incrementAndGet(), resultFuture);

        enqueueWriteRequest(request, deadline);
        return awaitWriteResult(resultFuture, deadline);
    }

    private Stream acceptStreamInternal(Instant deadline) throws IOException {
        while (true) {
            IOException readError = socketReadError.get();
            if (readError != null) {
                throw readError;
            }
            IOException proto = protocolError.get();
            if (proto != null) {
                throw proto;
            }
            if (closed.get()) {
                throw new ClosedChannelException();
            }

            long waitMillis = DeadlineSupport.waitSliceMillis(deadline, LOOP_WAIT_SLICE_MILLIS);

            try {
                Stream stream = accepts.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (stream != null) {
                    return stream;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("accept interrupted");
                interrupted.initCause(exception);
                throw interrupted;
            }
        }
    }

    private void recvLoop() {
        byte[] headerBytes = new byte[Frame.HEADER_SIZE];
        byte[] updateBytes = new byte[Frame.UPDATE_SIZE];

        while (!closed.get()) {
            waitForBucket();
            if (closed.get()) {
                return;
            }

            try {
                IoSupport.readFully(input, headerBytes, 0, headerBytes.length);
            } catch (IOException exception) {
                notifyReadError(exception);
                return;
            }

            sessionIsActive.set(true);

            RawHeader header = new RawHeader(headerBytes);
            if (header.getVersion() != config.getVersion()) {
                notifyProtocolError(new InvalidProtocolException());
                return;
            }

            long streamId = header.getStreamId();
            Command command;
            try {
                command = header.getCommand();
            } catch (IllegalArgumentException exception) {
                notifyProtocolError(new InvalidProtocolException());
                return;
            }

            switch (command) {
                case NOP:
                    if (header.getLength() != 0) {
                        notifyProtocolError(new InvalidProtocolException());
                        return;
                    }
                    break;
                case SYN:
                    if (header.getLength() != 0) {
                        notifyProtocolError(new InvalidProtocolException());
                        return;
                    }
                    handleSyn(streamId);
                    break;
                case FIN:
                    if (header.getLength() != 0) {
                        notifyProtocolError(new InvalidProtocolException());
                        return;
                    }
                    handleFin(streamId);
                    break;
                case PSH:
                    if (header.getLength() > 0) {
                        handlePush(streamId, header.getLength());
                    }
                    break;
                case UPD:
                    if (config.getVersion() != Config.VERSION_2 || header.getLength() != Frame.UPDATE_SIZE) {
                        notifyProtocolError(new InvalidProtocolException());
                        return;
                    }
                    try {
                        IoSupport.readFully(input, updateBytes, 0, updateBytes.length);
                    } catch (IOException exception) {
                        notifyReadError(exception);
                        return;
                    }
                    handleUpdate(streamId, new UpdateHeader(updateBytes));
                    break;
                default:
                    notifyProtocolError(new InvalidProtocolException());
                    return;
            }
        }
    }

    private void handleSyn(long streamId) {
        Stream accepted = null;
        synchronized (streamMonitor) {
            if (!streams.containsKey(streamId)) {
                accepted = new Stream(streamId, config.getMaxFrameSize(), this);
                streams.put(streamId, accepted);
            }
        }

        if (accepted != null) {
            offerAcceptedStream(accepted);
        }
    }

    private void handleFin(long streamId) {
        Stream stream;
        synchronized (streamMonitor) {
            stream = streams.get(streamId);
        }
        if (stream != null) {
            stream.fin();
        }
    }

    private void handlePush(long streamId, int length) {
        byte[] ownerBuffer = Allocator.DEFAULT.acquire(length);
        if (ownerBuffer == null) {
            notifyProtocolError(new InvalidProtocolException());
            return;
        }

        try {
            IoSupport.readFully(input, ownerBuffer, 0, length);
        } catch (IOException exception) {
            try {
                Allocator.DEFAULT.release(ownerBuffer);
            } catch (IllegalArgumentException ignored) {
                // Keep the original socket read error.
            }
            notifyReadError(exception);
            return;
        }

        Stream stream;
        synchronized (streamMonitor) {
            stream = streams.get(streamId);
        }

        if (stream != null) {
            stream.pushBytes(ownerBuffer, length);
            bucket.addAndGet(-length);
            stream.wakeupReader();
        } else {
            Allocator.DEFAULT.release(ownerBuffer);
        }
    }

    private void handleUpdate(long streamId, UpdateHeader header) {
        Stream stream;
        synchronized (streamMonitor) {
            stream = streams.get(streamId);
        }
        if (stream != null) {
            stream.update((int) header.getConsumed(), (int) header.getWindow());
        }
    }

    private void sendLoop() {
        byte[] headerBytes = new byte[Frame.HEADER_SIZE];

        while (!closed.get()) {
            WriteRequest request;
            try {
                request = outgoing.poll(LOOP_WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }

            if (request == null) {
                continue;
            }

            shaperQueue.push(request);
            drainOutgoingQueue();

            while (!closed.get()) {
                WriteRequest next = shaperQueue.pop();
                if (next == null) {
                    break;
                }

                Frame frame = next.getFrame();
                try {
                    headerBytes[0] = (byte) frame.getVersion();
                    headerBytes[1] = (byte) frame.getCommand().getCode();
                    LittleEndian.writeUnsignedShort(headerBytes, 2, frame.getDataLength());
                    LittleEndian.writeUnsignedInt(headerBytes, 4, frame.getStreamId());

                    IoSupport.writeFully(output, headerBytes, 0, Frame.HEADER_SIZE);
                    if (frame.getDataLength() > 0) {
                        IoSupport.writeFully(output, frame.getDataArray(), frame.getDataOffset(), frame.getDataLength());
                    }
                    next.getResultFuture().complete(new WriteResult(frame.getDataLength(), null));
                } catch (IOException exception) {
                    next.getResultFuture().complete(new WriteResult(0, exception));
                    notifyWriteError(exception);
                    return;
                }

                drainOutgoingQueue();
            }
        }
    }

    private void keepAliveLoop() {
        long nextPingAt = System.currentTimeMillis() + keepAliveIntervalMillis;
        long nextTimeoutAt = System.currentTimeMillis() + keepAliveTimeoutMillis;

        while (!closed.get()) {
            long now = System.currentTimeMillis();
            long sleepMillis = Math.min(nextPingAt - now, nextTimeoutAt - now);
            if (sleepMillis > 0L) {
                if (awaitClose(Math.min(sleepMillis, LOOP_WAIT_SLICE_MILLIS))) {
                    return;
                }
                continue;
            }

            now = System.currentTimeMillis();
            if (now >= nextPingAt) {
                try {
                    writeFrameInternal(
                            Frame.create(config.getVersion(), Command.NOP, 0L),
                            Instant.ofEpochMilli(now + keepAliveIntervalMillis),
                            WriteClass.CONTROL
                    );
                } catch (IOException ignored) {
                    if (closed.get()) {
                        return;
                    }
                }
                notifyBucketAvailable();
                nextPingAt = now + keepAliveIntervalMillis;
            }

            if (now >= nextTimeoutAt) {
                if (!sessionIsActive.compareAndSet(true, false) && bucket.get() > 0) {
                    try {
                        close();
                    } catch (IOException ignored) {
                        // Closing on timeout is best effort.
                    }
                    return;
                }
                nextTimeoutAt = now + keepAliveTimeoutMillis;
            }
        }
    }

    private void waitForBucket() {
        while (bucket.get() <= 0 && !closed.get()) {
            synchronized (bucketMonitor) {
                if (bucket.get() > 0 || closed.get()) {
                    return;
                }
                try {
                    bucketMonitor.wait(LOOP_WAIT_SLICE_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void notifyBucketAvailable() {
        synchronized (bucketMonitor) {
            bucketMonitor.notifyAll();
        }
    }

    private void offerAcceptedStream(Stream stream) {
        while (!closed.get()) {
            try {
                if (accepts.offer(stream, LOOP_WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void enqueueWriteRequest(WriteRequest request, Instant deadline) throws IOException {
        while (true) {
            ensureWritable(deadline);
            long waitMillis = DeadlineSupport.waitSliceMillis(deadline, LOOP_WAIT_SLICE_MILLIS);
            try {
                if (outgoing.offer(request, waitMillis, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("write enqueue interrupted");
                interrupted.initCause(exception);
                throw interrupted;
            }
        }
    }

    private int awaitWriteResult(CompletableFuture<WriteResult> resultFuture, Instant deadline) throws IOException {
        while (true) {
            ensureWritable(deadline);
            long waitMillis = DeadlineSupport.waitSliceMillis(deadline, LOOP_WAIT_SLICE_MILLIS);
            try {
                WriteResult result = resultFuture.get(waitMillis, TimeUnit.MILLISECONDS);
                if (result.getError() != null) {
                    throw result.getError();
                }
                return result.getBytesWritten();
            } catch (TimeoutException ignored) {
                // Continue checking close / deadline / socket errors.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("write wait interrupted");
                interrupted.initCause(exception);
                throw interrupted;
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("write failed", cause);
            }
        }
    }

    private void ensureSessionOpen() throws IOException {
        if (closed.get()) {
            throw new ClosedChannelException();
        }
    }

    private void ensureWritable(Instant deadline) throws IOException {
        if (closed.get()) {
            throw new ClosedChannelException();
        }
        IOException writeError = socketWriteError.get();
        if (writeError != null) {
            throw writeError;
        }
        if (deadline != null) {
            DeadlineSupport.remainingMillis(deadline);
        }
    }

    private int writeControlFrame(Frame frame) throws IOException {
        return writeFrameInternal(frame, Instant.now().plus(OPEN_CLOSE_TIMEOUT), WriteClass.CONTROL);
    }

    private void drainOutgoingQueue() {
        while (shaperQueue.size() < MAX_SHAPER_SIZE) {
            WriteRequest request = outgoing.poll();
            if (request == null) {
                return;
            }
            shaperQueue.push(request);
        }
    }

    private boolean awaitClose(long waitMillis) {
        try {
            closeFuture.get(waitMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException ignored) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return true;
        } catch (ExecutionException ignored) {
            return true;
        }
    }

    private void notifyReadError(IOException error) {
        if (socketReadError.compareAndSet(null, error)) {
            signalAllStreams();
            notifyBucketAvailable();
        }
    }

    private void notifyWriteError(IOException error) {
        if (socketWriteError.compareAndSet(null, error)) {
            signalAllStreams();
            notifyBucketAvailable();
        }
    }

    private void notifyProtocolError(IOException error) {
        if (protocolError.compareAndSet(null, error)) {
            signalAllStreams();
            notifyBucketAvailable();
        }
    }

    private void signalAllStreams() {
        List<Stream> snapshot;
        synchronized (streamMonitor) {
            snapshot = new ArrayList<Stream>(streams.values());
        }
        for (Stream stream : snapshot) {
            stream.signalAll();
        }
    }

    private static Config normalizeConfig(Config config) {
        Config resolved = config == null ? Config.defaultConfig() : config.copy();
        return resolved.validate();
    }

    private static Thread newThread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", "Session{", "}")
                .add("sessionNumber=" + sessionNumber)
                .add("client=" + client)
                .add("version=" + config.getVersion())
                .add("closed=" + closed.get())
                .add("streamCount=" + numStreams())
                .toString();
    }
}
