// 
// Decompiled by Procyon v0.5.30
// 

package okio;

import java.util.logging.Level;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import javax.annotation.Nullable;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Socket;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Logger;

public final class Okio
{
    static final Logger logger;
    
    public static BufferedSource buffer(final Source source) {
        return new RealBufferedSource(source);
    }
    
    public static BufferedSink buffer(final Sink sink) {
        return new RealBufferedSink(sink);
    }
    
    public static Sink sink(final OutputStream out) {
        return sink(out, new Timeout());
    }
    
    private static Sink sink(final OutputStream out, final Timeout timeout) {
        if (out == null) {
            throw new IllegalArgumentException("out == null");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("timeout == null");
        }
        return new Sink() {
            @Override
            public void write(final Buffer source, long byteCount) throws IOException {
                Util.checkOffsetAndCount(source.size, 0L, byteCount);
                while (byteCount > 0L) {
                    timeout.throwIfReached();
                    final Segment head = source.head;
                    final int toCopy = (int)Math.min(byteCount, head.limit - head.pos);
                    out.write(head.data, head.pos, toCopy);
                    final Segment segment = head;
                    segment.pos += toCopy;
                    byteCount -= toCopy;
                    source.size -= toCopy;
                    if (head.pos == head.limit) {
                        source.head = head.pop();
                        SegmentPool.recycle(head);
                    }
                }
            }
            
            @Override
            public void flush() throws IOException {
                out.flush();
            }
            
            @Override
            public void close() throws IOException {
                out.close();
            }
            
            @Override
            public Timeout timeout() {
                return timeout;
            }
            
            @Override
            public String toString() {
                return "sink(" + out + ")";
            }
        };
    }
    
    public static Sink sink(final Socket socket) throws IOException {
        if (socket == null) {
            throw new IllegalArgumentException("socket == null");
        }
        final AsyncTimeout timeout = timeout(socket);
        final Sink sink = sink(socket.getOutputStream(), timeout);
        return timeout.sink(sink);
    }
    
    public static Source source(final InputStream in) {
        return source(in, new Timeout());
    }
    
    private static Source source(final InputStream in, final Timeout timeout) {
        if (in == null) {
            throw new IllegalArgumentException("in == null");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("timeout == null");
        }
        return new Source() {
            @Override
            public long read(final Buffer sink, final long byteCount) throws IOException {
                if (byteCount < 0L) {
                    throw new IllegalArgumentException("byteCount < 0: " + byteCount);
                }
                if (byteCount == 0L) {
                    return 0L;
                }
                try {
                    timeout.throwIfReached();
                    final Segment tail = sink.writableSegment(1);
                    final int maxToCopy = (int)Math.min(byteCount, 8192 - tail.limit);
                    final int bytesRead = in.read(tail.data, tail.limit, maxToCopy);
                    if (bytesRead == -1) {
                        return -1L;
                    }
                    final Segment segment = tail;
                    segment.limit += bytesRead;
                    sink.size += bytesRead;
                    return bytesRead;
                }
                catch (AssertionError e) {
                    if (Okio.isAndroidGetsocknameError(e)) {
                        throw new IOException(e);
                    }
                    throw e;
                }
            }
            
            @Override
            public void close() throws IOException {
                in.close();
            }
            
            @Override
            public Timeout timeout() {
                return timeout;
            }
            
            @Override
            public String toString() {
                return "source(" + in + ")";
            }
        };
    }
    
    public static Source source(final File file) throws FileNotFoundException {
        if (file == null) {
            throw new IllegalArgumentException("file == null");
        }
        return source(new FileInputStream(file));
    }
    
    public static Source source(final Path path, final OpenOption... options) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path == null");
        }
        return source(Files.newInputStream(path, options));
    }
    
    public static Sink sink(final File file) throws FileNotFoundException {
        if (file == null) {
            throw new IllegalArgumentException("file == null");
        }
        return sink(new FileOutputStream(file));
    }
    
    public static Sink appendingSink(final File file) throws FileNotFoundException {
        if (file == null) {
            throw new IllegalArgumentException("file == null");
        }
        return sink(new FileOutputStream(file, true));
    }
    
    public static Sink sink(final Path path, final OpenOption... options) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path == null");
        }
        return sink(Files.newOutputStream(path, options));
    }
    
    public static Sink blackhole() {
        return new Sink() {
            @Override
            public void write(final Buffer source, final long byteCount) throws IOException {
                source.skip(byteCount);
            }
            
            @Override
            public void flush() throws IOException {
            }
            
            @Override
            public Timeout timeout() {
                return Timeout.NONE;
            }
            
            @Override
            public void close() throws IOException {
            }
        };
    }
    
    public static Source source(final Socket socket) throws IOException {
        if (socket == null) {
            throw new IllegalArgumentException("socket == null");
        }
        final AsyncTimeout timeout = timeout(socket);
        final Source source = source(socket.getInputStream(), timeout);
        return timeout.source(source);
    }
    
    private static AsyncTimeout timeout(final Socket socket) {
        return new AsyncTimeout() {
            @Override
            protected IOException newTimeoutException(@Nullable final IOException cause) {
                final InterruptedIOException ioe = new SocketTimeoutException("timeout");
                if (cause != null) {
                    ioe.initCause(cause);
                }
                return ioe;
            }
            
            @Override
            protected void timedOut() {
                try {
                    socket.close();
                }
                catch (Exception e) {
                    Okio.logger.log(Level.WARNING, "Failed to close timed out socket " + socket, e);
                }
                catch (AssertionError e2) {
                    if (!Okio.isAndroidGetsocknameError(e2)) {
                        throw e2;
                    }
                    Okio.logger.log(Level.WARNING, "Failed to close timed out socket " + socket, e2);
                }
            }
        };
    }
    
    static boolean isAndroidGetsocknameError(final AssertionError e) {
        return e.getCause() != null && e.getMessage() != null && e.getMessage().contains("getsockname failed");
    }
    
    static {
        logger = Logger.getLogger(Okio.class.getName());
    }
}
