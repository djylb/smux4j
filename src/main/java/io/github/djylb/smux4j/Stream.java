package io.github.djylb.smux4j;

import io.github.djylb.smux4j.exception.ConsumedException;
import io.github.djylb.smux4j.exception.SmuxTimeoutException;
import io.github.djylb.smux4j.frame.Command;
import io.github.djylb.smux4j.frame.Frame;
import io.github.djylb.smux4j.frame.UpdateHeader;
import io.github.djylb.smux4j.internal.Allocator;
import io.github.djylb.smux4j.internal.BufferRing;
import io.github.djylb.smux4j.internal.DeadlineSupport;
import io.github.djylb.smux4j.internal.WriteClass;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logical stream within a session.
 */
public final class Stream implements Closeable, Flushable, ByteChannel {
    private static final int WOULD_BLOCK = -2;
    private static final long WAIT_SLICE_MILLIS = 100L;

    private final long id;
    private final Session session;
    private final BufferRing bufferRing = new BufferRing(8);
    private final Object bufferLock = new Object();
    private final Object readerMonitor = new Object();
    private final Object writerMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean remoteFin = new AtomicBoolean(false);
    private final AtomicBoolean writeClosed = new AtomicBoolean(false);
    private final AtomicInteger numWritten = new AtomicInteger(0);
    private final AtomicInteger peerConsumed = new AtomicInteger(0);
    private final AtomicInteger peerWindow = new AtomicInteger(Frame.INITIAL_PEER_WINDOW);
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<Void>();
    private final InputStream inputStream = new StreamInputStream();
    private final OutputStream outputStream = new StreamOutputStream();

    private final int frameSize;
    private final int windowUpdateThreshold;

    private volatile Instant readDeadline;
    private volatile Instant writeDeadline;

    private int numRead;
    private int incr;

    Stream(long id, int frameSize, Session session) {
        this.id = id;
        this.frameSize = frameSize;
        this.session = Objects.requireNonNull(session, "session");
        this.windowUpdateThreshold = Math.max(1, session.getMaxStreamBuffer() / 2);
    }

    public long getId() {
        return id;
    }

    public Session getSession() {
        return session;
    }

    public int read(byte[] target) throws IOException {
        Objects.requireNonNull(target, "target");
        return read(target, 0, target.length);
    }

    public int read(byte[] target, int offset, int length) throws IOException {
        Objects.requireNonNull(target, "target");
        checkBounds(target.length, offset, length);
        if (length == 0) {
            return 0;
        }

        if (session.getProtocolVersion() == Config.VERSION_2) {
            while (true) {
                int read = tryReadV2(target, offset, length);
                if (read != WOULD_BLOCK) {
                    return read;
                }
                waitRead();
            }
        }

        while (true) {
            int read = tryReadV1(target, offset, length);
            if (read != WOULD_BLOCK) {
                return read;
            }
            waitRead();
        }
    }

    public long transferTo(OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        return session.getProtocolVersion() == Config.VERSION_2 ? transferToV2(output) : transferToV1(output);
    }

    public long writeTo(OutputStream output) throws IOException {
        return transferTo(output);
    }

    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (!target.hasRemaining()) {
            return 0;
        }
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        if (target.hasArray()) {
            int position = target.position();
            int read = read(target.array(), target.arrayOffset() + position, target.remaining());
            if (read > 0) {
                target.position(position + read);
            }
            return read;
        }

        byte[] buffer = new byte[transientBufferSize(target.remaining())];
        int read = read(buffer, 0, buffer.length);
        if (read > 0) {
            target.put(buffer, 0, read);
        }
        return read;
    }

    public void write(int value) throws IOException {
        byte[] singleByte = new byte[] {(byte) value};
        write(singleByte, 0, 1);
    }

    public void write(byte[] source) throws IOException {
        Objects.requireNonNull(source, "source");
        write(source, 0, source.length);
    }

    public void write(byte[] source, int offset, int length) throws IOException {
        Objects.requireNonNull(source, "source");
        checkBounds(source.length, offset, length);
        if (length == 0) {
            return;
        }

        if (session.getProtocolVersion() == Config.VERSION_2) {
            writeV2(source, offset, length);
            return;
        }
        writeV1(source, offset, length);
    }

    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        if (!source.hasRemaining()) {
            return 0;
        }

        int initialPosition = source.position();
        int total = 0;
        int chunkSize = Math.max(1, Math.min(frameSize, 8 * 1024));

        if (source.hasArray()) {
            while (source.hasRemaining()) {
                int size = Math.min(source.remaining(), chunkSize);
                write(source.array(), source.arrayOffset() + source.position(), size);
                source.position(source.position() + size);
                total += size;
            }
            return total;
        }

        ByteBuffer duplicate = source.duplicate();
        byte[] buffer = new byte[Math.min(duplicate.remaining(), chunkSize)];
        while (duplicate.hasRemaining()) {
            int size = Math.min(duplicate.remaining(), buffer.length);
            duplicate.get(buffer, 0, size);
            write(buffer, 0, size);
            total += size;
            source.position(initialPosition + total);
        }
        return total;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public void closeWrite() throws IOException {
        if (closed.get()) {
            throw new ClosedChannelException();
        }
        if (!writeClosed.compareAndSet(false, true)) {
            throw new ClosedChannelException();
        }

        wakeupWriter();
        try {
            session.writeFrameInternal(
                    Frame.create(session.getProtocolVersion(), Command.FIN, id),
                    Instant.now().plus(Session.OPEN_CLOSE_TIMEOUT),
                    WriteClass.DATA
            );
        } finally {
            tryHalfCloseCleanup();
        }
    }

    public boolean isWriteClosed() {
        return writeClosed.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isOpen() {
        return !isClosed();
    }

    public boolean isRemoteClosed() {
        return remoteFin.get();
    }

    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    public CompletableFuture<Void> getCloseFuture() {
        return closeFuture();
    }

    public void setReadDeadline(Instant readDeadline) {
        this.readDeadline = readDeadline;
        wakeupReader();
    }

    public Instant getReadDeadline() {
        return readDeadline;
    }

    public void setWriteDeadline(Instant writeDeadline) {
        this.writeDeadline = writeDeadline;
        wakeupWriter();
    }

    public Instant getWriteDeadline() {
        return writeDeadline;
    }

    public void setDeadline(Instant deadline) {
        this.readDeadline = deadline;
        this.writeDeadline = deadline;
        wakeupReader();
        wakeupWriter();
    }

    public SocketAddress getLocalAddress() {
        return session.getLocalAddress();
    }

    public SocketAddress getRemoteAddress() {
        return session.getRemoteAddress();
    }

    @Override
    public void flush() throws IOException {
        ensureWritable();
        session.flush();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            throw new ClosedChannelException();
        }

        closeFuture.complete(null);
        writeClosed.set(true);
        wakeupReader();
        wakeupWriter();

        IOException failure = null;
        try {
            if (!session.isClosed()) {
                session.writeFrameInternal(
                        Frame.create(session.getProtocolVersion(), Command.FIN, id),
                        Instant.now().plus(Session.OPEN_CLOSE_TIMEOUT),
                        WriteClass.DATA
                );
            }
        } catch (IOException exception) {
            failure = exception;
        } finally {
            session.streamClosed(id);
        }

        if (failure != null) {
            throw failure;
        }
    }

    void sessionClose() {
        if (closed.compareAndSet(false, true)) {
            closeFuture.complete(null);
            wakeupReader();
            wakeupWriter();
        }
    }

    void pushBytes(byte[] ownerBuffer, int length) {
        synchronized (bufferLock) {
            bufferRing.push(ownerBuffer, length);
        }
    }

    int recycleTokens() {
        int recycled = 0;
        synchronized (bufferLock) {
            BufferRing.Entry entry;
            while ((entry = bufferRing.pop()) != null) {
                recycled += entry.getLength();
                Allocator.DEFAULT.release(entry.getOwnerBuffer());
            }
        }
        return recycled;
    }

    void wakeupReader() {
        synchronized (readerMonitor) {
            readerMonitor.notifyAll();
        }
    }

    void wakeupWriter() {
        synchronized (writerMonitor) {
            writerMonitor.notifyAll();
        }
    }

    void signalAll() {
        wakeupReader();
        wakeupWriter();
    }

    void update(int consumed, int window) {
        peerConsumed.set(consumed);
        peerWindow.set(window);
        wakeupWriter();
    }

    void fin() {
        if (remoteFin.compareAndSet(false, true)) {
            wakeupReader();
            tryHalfCloseCleanup();
        }
    }

    private int tryReadV1(byte[] target, int offset, int length) throws IOException {
        BufferRing.ConsumeResult consumeResult;
        synchronized (bufferLock) {
            consumeResult = bufferRing.consumeFront(target, offset, length);
        }

        if (consumeResult.getRecycledBuffer() != null) {
            Allocator.DEFAULT.release(consumeResult.getRecycledBuffer());
        }

        if (consumeResult.getBytesCopied() > 0) {
            session.returnTokens(consumeResult.getBytesCopied());
            return consumeResult.getBytesCopied();
        }

        if (remoteFin.get()) {
            return -1;
        }

        if (closed.get() || session.isClosed()) {
            throw new ClosedChannelException();
        }

        return WOULD_BLOCK;
    }

    private int tryReadV2(byte[] target, int offset, int length) throws IOException {
        int notifyConsumed = 0;
        BufferRing.ConsumeResult consumeResult;
        synchronized (bufferLock) {
            consumeResult = bufferRing.consumeFront(target, offset, length);
            if (consumeResult.getBytesCopied() > 0) {
                numRead += consumeResult.getBytesCopied();
                incr += consumeResult.getBytesCopied();
                if (incr >= windowUpdateThreshold || numRead == consumeResult.getBytesCopied()) {
                    notifyConsumed = numRead;
                    incr = 0;
                }
            }
        }

        if (consumeResult.getRecycledBuffer() != null) {
            Allocator.DEFAULT.release(consumeResult.getRecycledBuffer());
        }

        if (consumeResult.getBytesCopied() > 0) {
            session.returnTokens(consumeResult.getBytesCopied());
            if (notifyConsumed > 0) {
                sendWindowUpdate(notifyConsumed);
            }
            return consumeResult.getBytesCopied();
        }

        if (remoteFin.get()) {
            return -1;
        }

        if (closed.get() || session.isClosed()) {
            throw new ClosedChannelException();
        }

        return WOULD_BLOCK;
    }

    private long transferToV1(OutputStream output) throws IOException {
        long total = 0L;
        while (true) {
            BufferRing.Entry entry;
            synchronized (bufferLock) {
                entry = bufferRing.pop();
            }

            if (entry != null) {
                output.write(entry.getOwnerBuffer(), entry.getOffset(), entry.getLength());
                session.returnTokens(entry.getLength());
                Allocator.DEFAULT.release(entry.getOwnerBuffer());
                total += entry.getLength();
                continue;
            }

            if (remoteFin.get()) {
                return total;
            }
            waitRead();
        }
    }

    private long transferToV2(OutputStream output) throws IOException {
        long total = 0L;
        while (true) {
            int notifyConsumed = 0;
            BufferRing.Entry entry;
            synchronized (bufferLock) {
                entry = bufferRing.pop();
                if (entry != null) {
                    numRead += entry.getLength();
                    incr += entry.getLength();
                    if (incr >= windowUpdateThreshold || numRead == entry.getLength()) {
                        notifyConsumed = numRead;
                        incr = 0;
                    }
                }
            }

            if (entry != null) {
                output.write(entry.getOwnerBuffer(), entry.getOffset(), entry.getLength());
                session.returnTokens(entry.getLength());
                Allocator.DEFAULT.release(entry.getOwnerBuffer());
                total += entry.getLength();
                if (notifyConsumed > 0) {
                    sendWindowUpdate(notifyConsumed);
                }
                continue;
            }

            if (remoteFin.get()) {
                return total;
            }
            waitRead();
        }
    }

    private void sendWindowUpdate(int consumed) throws IOException {
        UpdateHeader header = UpdateHeader.of(consumed & 0xffff_ffffL, session.getMaxStreamBuffer());
        session.writeFrameInternal(
                Frame.create(session.getProtocolVersion(), Command.UPD, id, header.toByteArray()),
                readDeadline,
                WriteClass.CONTROL
        );
    }

    private void waitRead() throws IOException {
        while (true) {
            if (hasReadableBytes()) {
                return;
            }
            if (remoteFin.get()) {
                return;
            }
            IOException readError = session.currentReadError();
            if (readError != null) {
                throw readError;
            }
            IOException protocolError = session.currentProtocolError();
            if (protocolError != null) {
                throw protocolError;
            }
            if (closed.get() || session.isClosed()) {
                throw new ClosedChannelException();
            }

            long waitMillis = DeadlineSupport.waitSliceMillis(readDeadline, WAIT_SLICE_MILLIS);
            synchronized (readerMonitor) {
                if (hasReadableBytes() || remoteFin.get() || closed.get() || session.isClosed()) {
                    continue;
                }
                try {
                    readerMonitor.wait(waitMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException interrupted = new InterruptedIOException("read interrupted");
                    interrupted.initCause(exception);
                    throw interrupted;
                }
            }
        }
    }

    private void writeV1(byte[] source, int offset, int length) throws IOException {
        ensureWritable();
        int position = offset;
        int remaining = length;

        while (remaining > 0) {
            int size = Math.min(frameSize, remaining);
            session.writeFrameInternal(
                    new Frame(session.getProtocolVersion(), Command.PSH, id, source, position, size),
                    writeDeadline,
                    WriteClass.DATA
            );
            numWritten.addAndGet(size);
            position += size;
            remaining -= size;
        }
    }

    private void writeV2(byte[] source, int offset, int length) throws IOException {
        ensureWritable();

        int position = offset;
        int remaining = length;

        while (remaining > 0) {
            ensureWritable();

            int inflight = numWritten.get() - peerConsumed.get();
            if (inflight < 0) {
                throw new ConsumedException();
            }

            int window = peerWindow.get() - inflight;
            if (window > 0) {
                int writable = Math.min(remaining, window);
                int writablePosition = position;
                int writableRemaining = writable;
                while (writableRemaining > 0) {
                    int size = Math.min(frameSize, writableRemaining);
                    session.writeFrameInternal(
                            new Frame(session.getProtocolVersion(), Command.PSH, id, source, writablePosition, size),
                            writeDeadline,
                            WriteClass.DATA
                    );
                    numWritten.addAndGet(size);
                    writablePosition += size;
                    writableRemaining -= size;
                }

                position += writable;
                remaining -= writable;
                continue;
            }

            waitForWriteWindow();
        }
    }

    private void waitForWriteWindow() throws IOException {
        while (true) {
            ensureWritable();
            int inflight = numWritten.get() - peerConsumed.get();
            if (inflight < 0) {
                throw new ConsumedException();
            }
            int window = peerWindow.get() - inflight;
            if (window > 0) {
                return;
            }

            IOException writeError = session.currentWriteError();
            if (writeError != null) {
                throw writeError;
            }

            long waitMillis = DeadlineSupport.waitSliceMillis(writeDeadline, WAIT_SLICE_MILLIS);
            synchronized (writerMonitor) {
                ensureWritable();
                try {
                    writerMonitor.wait(waitMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException interrupted = new InterruptedIOException("write interrupted");
                    interrupted.initCause(exception);
                    throw interrupted;
                }
            }
        }
    }

    private void ensureWritable() throws IOException {
        if (writeClosed.get() || closed.get() || session.isClosed()) {
            throw new ClosedChannelException();
        }
        IOException writeError = session.currentWriteError();
        if (writeError != null) {
            throw writeError;
        }
        if (writeDeadline != null) {
            DeadlineSupport.remainingMillis(writeDeadline);
        }
    }

    private boolean hasReadableBytes() {
        synchronized (bufferLock) {
            return !bufferRing.isEmpty();
        }
    }

    private void tryHalfCloseCleanup() {
        if (!remoteFin.get() || !writeClosed.get()) {
            return;
        }
        if (closed.compareAndSet(false, true)) {
            closeFuture.complete(null);
            wakeupReader();
            wakeupWriter();
            session.streamClosed(id);
        }
    }

    private static void checkBounds(int size, int offset, int length) {
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length + ", size=" + size);
        }
    }

    private int transientBufferSize(int requestedLength) {
        return Math.max(1, Math.min(requestedLength, Math.min(frameSize, 8 * 1024)));
    }

    private final class StreamInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] singleByte = new byte[1];
            int read = Stream.this.read(singleByte, 0, 1);
            if (read < 0) {
                return -1;
            }
            return singleByte[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return Stream.this.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            Stream.this.close();
        }
    }

    private final class StreamOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            Stream.this.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            Stream.this.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            Stream.this.flush();
        }

        @Override
        public void close() throws IOException {
            Stream.this.closeWrite();
        }
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", "Stream{", "}")
                .add("id=" + id)
                .add("version=" + session.getProtocolVersion())
                .add("closed=" + closed.get())
                .add("writeClosed=" + writeClosed.get())
                .add("remoteClosed=" + remoteFin.get())
                .toString();
    }
}
