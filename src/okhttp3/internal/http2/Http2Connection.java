// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.http2;

import okio.ByteString;
import okhttp3.internal.platform.Platform;
import okio.Okio;
import java.net.InetSocketAddress;
import okio.BufferedSink;
import java.util.concurrent.SynchronousQueue;
import okio.BufferedSource;
import okhttp3.internal.NamedRunnable;
import java.io.InterruptedIOException;
import okio.Buffer;
import java.io.IOException;
import java.util.List;
import okhttp3.Protocol;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import okhttp3.internal.Util;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.io.Closeable;

public final class Http2Connection implements Closeable
{
    static final ExecutorService executor;
    final boolean client;
    final Listener listener;
    final Map<Integer, Http2Stream> streams;
    final String hostname;
    int lastGoodStreamId;
    int nextStreamId;
    boolean shutdown;
    private final ExecutorService pushExecutor;
    private Map<Integer, Ping> pings;
    final PushObserver pushObserver;
    private int nextPingId;
    long unacknowledgedBytesRead;
    long bytesLeftInWriteWindow;
    Settings okHttpSettings;
    private static final int OKHTTP_CLIENT_WINDOW_SIZE = 16777216;
    final Settings peerSettings;
    boolean receivedInitialPeerSettings;
    final Socket socket;
    final Http2Writer writer;
    final ReaderRunnable readerRunnable;
    final Set<Integer> currentPushRequests;
    
    Http2Connection(final Builder builder) {
        this.streams = new LinkedHashMap<Integer, Http2Stream>();
        this.unacknowledgedBytesRead = 0L;
        this.okHttpSettings = new Settings();
        this.peerSettings = new Settings();
        this.receivedInitialPeerSettings = false;
        this.currentPushRequests = new LinkedHashSet<Integer>();
        this.pushObserver = builder.pushObserver;
        this.client = builder.client;
        this.listener = builder.listener;
        this.nextStreamId = (builder.client ? 1 : 2);
        if (builder.client) {
            this.nextStreamId += 2;
        }
        this.nextPingId = (builder.client ? 1 : 2);
        if (builder.client) {
            this.okHttpSettings.set(7, 16777216);
        }
        this.hostname = builder.hostname;
        this.pushExecutor = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), Util.threadFactory(Util.format("OkHttp %s Push Observer", this.hostname), true));
        this.peerSettings.set(7, 65535);
        this.peerSettings.set(5, 16384);
        this.bytesLeftInWriteWindow = this.peerSettings.getInitialWindowSize();
        this.socket = builder.socket;
        this.writer = new Http2Writer(builder.sink, this.client);
        this.readerRunnable = new ReaderRunnable(new Http2Reader(builder.source, this.client));
    }
    
    public Protocol getProtocol() {
        return Protocol.HTTP_2;
    }
    
    public synchronized int openStreamCount() {
        return this.streams.size();
    }
    
    synchronized Http2Stream getStream(final int id) {
        return this.streams.get(id);
    }
    
    synchronized Http2Stream removeStream(final int streamId) {
        final Http2Stream stream = this.streams.remove(streamId);
        this.notifyAll();
        return stream;
    }
    
    public synchronized int maxConcurrentStreams() {
        return this.peerSettings.getMaxConcurrentStreams(Integer.MAX_VALUE);
    }
    
    public Http2Stream pushStream(final int associatedStreamId, final List<Header> requestHeaders, final boolean out) throws IOException {
        if (this.client) {
            throw new IllegalStateException("Client cannot push requests.");
        }
        return this.newStream(associatedStreamId, requestHeaders, out);
    }
    
    public Http2Stream newStream(final List<Header> requestHeaders, final boolean out) throws IOException {
        return this.newStream(0, requestHeaders, out);
    }
    
    private Http2Stream newStream(final int associatedStreamId, final List<Header> requestHeaders, final boolean out) throws IOException {
        final boolean outFinished = !out;
        final boolean inFinished = false;
        final Http2Stream stream;
        final boolean flushHeaders;
        synchronized (this.writer) {
            final int streamId;
            synchronized (this) {
                if (this.shutdown) {
                    throw new ConnectionShutdownException();
                }
                streamId = this.nextStreamId;
                this.nextStreamId += 2;
                stream = new Http2Stream(streamId, this, outFinished, inFinished, requestHeaders);
                flushHeaders = (!out || this.bytesLeftInWriteWindow == 0L || stream.bytesLeftInWriteWindow == 0L);
                if (stream.isOpen()) {
                    this.streams.put(streamId, stream);
                }
            }
            if (associatedStreamId == 0) {
                this.writer.synStream(outFinished, streamId, associatedStreamId, requestHeaders);
            }
            else {
                if (this.client) {
                    throw new IllegalArgumentException("client streams shouldn't have associated stream IDs");
                }
                this.writer.pushPromise(associatedStreamId, streamId, requestHeaders);
            }
        }
        if (flushHeaders) {
            this.writer.flush();
        }
        return stream;
    }
    
    void writeSynReply(final int streamId, final boolean outFinished, final List<Header> alternating) throws IOException {
        this.writer.synReply(outFinished, streamId, alternating);
    }
    
    public void writeData(final int streamId, final boolean outFinished, final Buffer buffer, long byteCount) throws IOException {
        if (byteCount == 0L) {
            this.writer.data(outFinished, streamId, buffer, 0);
            return;
        }
        while (byteCount > 0L) {
            int toWrite;
            synchronized (this) {
                try {
                    while (this.bytesLeftInWriteWindow <= 0L) {
                        if (!this.streams.containsKey(streamId)) {
                            throw new IOException("stream closed");
                        }
                        this.wait();
                    }
                }
                catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
                toWrite = (int)Math.min(byteCount, this.bytesLeftInWriteWindow);
                toWrite = Math.min(toWrite, this.writer.maxDataLength());
                this.bytesLeftInWriteWindow -= toWrite;
            }
            byteCount -= toWrite;
            this.writer.data(outFinished && byteCount == 0L, streamId, buffer, toWrite);
        }
    }
    
    void addBytesToWriteWindow(final long delta) {
        this.bytesLeftInWriteWindow += delta;
        if (delta > 0L) {
            this.notifyAll();
        }
    }
    
    void writeSynResetLater(final int streamId, final ErrorCode errorCode) {
        Http2Connection.executor.execute(new NamedRunnable("OkHttp %s stream %d", new Object[] { this.hostname, streamId }) {
            public void execute() {
                try {
                    Http2Connection.this.writeSynReset(streamId, errorCode);
                }
                catch (IOException ex) {}
            }
        });
    }
    
    void writeSynReset(final int streamId, final ErrorCode statusCode) throws IOException {
        this.writer.rstStream(streamId, statusCode);
    }
    
    void writeWindowUpdateLater(final int streamId, final long unacknowledgedBytesRead) {
        Http2Connection.executor.execute(new NamedRunnable("OkHttp Window Update %s stream %d", new Object[] { this.hostname, streamId }) {
            public void execute() {
                try {
                    Http2Connection.this.writer.windowUpdate(streamId, unacknowledgedBytesRead);
                }
                catch (IOException ex) {}
            }
        });
    }
    
    public Ping ping() throws IOException {
        final Ping ping = new Ping();
        final int pingId;
        synchronized (this) {
            if (this.shutdown) {
                throw new ConnectionShutdownException();
            }
            pingId = this.nextPingId;
            this.nextPingId += 2;
            if (this.pings == null) {
                this.pings = new LinkedHashMap<Integer, Ping>();
            }
            this.pings.put(pingId, ping);
        }
        this.writePing(false, pingId, 1330343787, ping);
        return ping;
    }
    
    void writePingLater(final boolean reply, final int payload1, final int payload2, final Ping ping) {
        Http2Connection.executor.execute(new NamedRunnable("OkHttp %s ping %08x%08x", new Object[] { this.hostname, payload1, payload2 }) {
            public void execute() {
                try {
                    Http2Connection.this.writePing(reply, payload1, payload2, ping);
                }
                catch (IOException ex) {}
            }
        });
    }
    
    void writePing(final boolean reply, final int payload1, final int payload2, final Ping ping) throws IOException {
        synchronized (this.writer) {
            if (ping != null) {
                ping.send();
            }
            this.writer.ping(reply, payload1, payload2);
        }
    }
    
    synchronized Ping removePing(final int id) {
        return (this.pings != null) ? this.pings.remove(id) : null;
    }
    
    public void flush() throws IOException {
        this.writer.flush();
    }
    
    public void shutdown(final ErrorCode statusCode) throws IOException {
        synchronized (this.writer) {
            final int lastGoodStreamId;
            synchronized (this) {
                if (this.shutdown) {
                    return;
                }
                this.shutdown = true;
                lastGoodStreamId = this.lastGoodStreamId;
            }
            this.writer.goAway(lastGoodStreamId, statusCode, Util.EMPTY_BYTE_ARRAY);
        }
    }
    
    @Override
    public void close() throws IOException {
        this.close(ErrorCode.NO_ERROR, ErrorCode.CANCEL);
    }
    
    void close(final ErrorCode connectionCode, final ErrorCode streamCode) throws IOException {
        assert !Thread.holdsLock(this);
        IOException thrown = null;
        try {
            this.shutdown(connectionCode);
        }
        catch (IOException e) {
            thrown = e;
        }
        Http2Stream[] streamsToClose = null;
        Ping[] pingsToCancel = null;
        synchronized (this) {
            if (!this.streams.isEmpty()) {
                streamsToClose = this.streams.values().toArray(new Http2Stream[this.streams.size()]);
                this.streams.clear();
            }
            if (this.pings != null) {
                pingsToCancel = this.pings.values().toArray(new Ping[this.pings.size()]);
                this.pings = null;
            }
        }
        if (streamsToClose != null) {
            for (final Http2Stream stream : streamsToClose) {
                try {
                    stream.close(streamCode);
                }
                catch (IOException e2) {
                    if (thrown != null) {
                        thrown = e2;
                    }
                }
            }
        }
        if (pingsToCancel != null) {
            for (final Ping ping : pingsToCancel) {
                ping.cancel();
            }
        }
        try {
            this.writer.close();
        }
        catch (IOException e3) {
            if (thrown == null) {
                thrown = e3;
            }
        }
        try {
            this.socket.close();
        }
        catch (IOException e3) {
            thrown = e3;
        }
        if (thrown != null) {
            throw thrown;
        }
    }
    
    public void start() throws IOException {
        this.start(true);
    }
    
    void start(final boolean sendConnectionPreface) throws IOException {
        if (sendConnectionPreface) {
            this.writer.connectionPreface();
            this.writer.settings(this.okHttpSettings);
            final int windowSize = this.okHttpSettings.getInitialWindowSize();
            if (windowSize != 65535) {
                this.writer.windowUpdate(0, windowSize - 65535);
            }
        }
        new Thread(this.readerRunnable).start();
    }
    
    public void setSettings(final Settings settings) throws IOException {
        synchronized (this.writer) {
            synchronized (this) {
                if (this.shutdown) {
                    throw new ConnectionShutdownException();
                }
                this.okHttpSettings.merge(settings);
                this.writer.settings(settings);
            }
        }
    }
    
    public synchronized boolean isShutdown() {
        return this.shutdown;
    }
    
    boolean pushedStream(final int streamId) {
        return streamId != 0 && (streamId & 0x1) == 0x0;
    }
    
    void pushRequestLater(final int streamId, final List<Header> requestHeaders) {
        synchronized (this) {
            if (this.currentPushRequests.contains(streamId)) {
                this.writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR);
                return;
            }
            this.currentPushRequests.add(streamId);
        }
        this.pushExecutor.execute(new NamedRunnable("OkHttp %s Push Request[%s]", new Object[] { this.hostname, streamId }) {
            public void execute() {
                final boolean cancel = Http2Connection.this.pushObserver.onRequest(streamId, requestHeaders);
                try {
                    if (cancel) {
                        Http2Connection.this.writer.rstStream(streamId, ErrorCode.CANCEL);
                        synchronized (Http2Connection.this) {
                            Http2Connection.this.currentPushRequests.remove(streamId);
                        }
                    }
                }
                catch (IOException ex) {}
            }
        });
    }
    
    void pushHeadersLater(final int streamId, final List<Header> requestHeaders, final boolean inFinished) {
        this.pushExecutor.execute(new NamedRunnable("OkHttp %s Push Headers[%s]", new Object[] { this.hostname, streamId }) {
            public void execute() {
                final boolean cancel = Http2Connection.this.pushObserver.onHeaders(streamId, requestHeaders, inFinished);
                try {
                    if (cancel) {
                        Http2Connection.this.writer.rstStream(streamId, ErrorCode.CANCEL);
                    }
                    if (cancel || inFinished) {
                        synchronized (Http2Connection.this) {
                            Http2Connection.this.currentPushRequests.remove(streamId);
                        }
                    }
                }
                catch (IOException ex) {}
            }
        });
    }
    
    void pushDataLater(final int streamId, final BufferedSource source, final int byteCount, final boolean inFinished) throws IOException {
        final Buffer buffer = new Buffer();
        source.require(byteCount);
        source.read(buffer, byteCount);
        if (buffer.size() != byteCount) {
            throw new IOException(buffer.size() + " != " + byteCount);
        }
        this.pushExecutor.execute(new NamedRunnable("OkHttp %s Push Data[%s]", new Object[] { this.hostname, streamId }) {
            public void execute() {
                try {
                    final boolean cancel = Http2Connection.this.pushObserver.onData(streamId, buffer, byteCount, inFinished);
                    if (cancel) {
                        Http2Connection.this.writer.rstStream(streamId, ErrorCode.CANCEL);
                    }
                    if (cancel || inFinished) {
                        synchronized (Http2Connection.this) {
                            Http2Connection.this.currentPushRequests.remove(streamId);
                        }
                    }
                }
                catch (IOException ex) {}
            }
        });
    }
    
    void pushResetLater(final int streamId, final ErrorCode errorCode) {
        this.pushExecutor.execute(new NamedRunnable("OkHttp %s Push Reset[%s]", new Object[] { this.hostname, streamId }) {
            public void execute() {
                Http2Connection.this.pushObserver.onReset(streamId, errorCode);
                synchronized (Http2Connection.this) {
                    Http2Connection.this.currentPushRequests.remove(streamId);
                }
            }
        });
    }
    
    static {
        executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Http2Connection", true));
    }
    
    public static class Builder
    {
        Socket socket;
        String hostname;
        BufferedSource source;
        BufferedSink sink;
        Listener listener;
        PushObserver pushObserver;
        boolean client;
        
        public Builder(final boolean client) {
            this.listener = Listener.REFUSE_INCOMING_STREAMS;
            this.pushObserver = PushObserver.CANCEL;
            this.client = client;
        }
        
        public Builder socket(final Socket socket) throws IOException {
            return this.socket(socket, ((InetSocketAddress)socket.getRemoteSocketAddress()).getHostName(), Okio.buffer(Okio.source(socket)), Okio.buffer(Okio.sink(socket)));
        }
        
        public Builder socket(final Socket socket, final String hostname, final BufferedSource source, final BufferedSink sink) {
            this.socket = socket;
            this.hostname = hostname;
            this.source = source;
            this.sink = sink;
            return this;
        }
        
        public Builder listener(final Listener listener) {
            this.listener = listener;
            return this;
        }
        
        public Builder pushObserver(final PushObserver pushObserver) {
            this.pushObserver = pushObserver;
            return this;
        }
        
        public Http2Connection build() throws IOException {
            return new Http2Connection(this);
        }
    }
    
    class ReaderRunnable extends NamedRunnable implements Http2Reader.Handler
    {
        final Http2Reader reader;
        
        ReaderRunnable(final Http2Reader reader) {
            super("OkHttp %s", new Object[] { Http2Connection.this.hostname });
            this.reader = reader;
        }
        
        @Override
        protected void execute() {
            ErrorCode connectionErrorCode = ErrorCode.INTERNAL_ERROR;
            ErrorCode streamErrorCode = ErrorCode.INTERNAL_ERROR;
            try {
                this.reader.readConnectionPreface(this);
                while (this.reader.nextFrame(false, this)) {}
                connectionErrorCode = ErrorCode.NO_ERROR;
                streamErrorCode = ErrorCode.CANCEL;
            }
            catch (IOException e) {
                connectionErrorCode = ErrorCode.PROTOCOL_ERROR;
                streamErrorCode = ErrorCode.PROTOCOL_ERROR;
            }
            finally {
                try {
                    Http2Connection.this.close(connectionErrorCode, streamErrorCode);
                }
                catch (IOException ex) {}
                Util.closeQuietly(this.reader);
            }
        }
        
        @Override
        public void data(final boolean inFinished, final int streamId, final BufferedSource source, final int length) throws IOException {
            if (Http2Connection.this.pushedStream(streamId)) {
                Http2Connection.this.pushDataLater(streamId, source, length, inFinished);
                return;
            }
            final Http2Stream dataStream = Http2Connection.this.getStream(streamId);
            if (dataStream == null) {
                Http2Connection.this.writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR);
                source.skip(length);
                return;
            }
            dataStream.receiveData(source, length);
            if (inFinished) {
                dataStream.receiveFin();
            }
        }
        
        @Override
        public void headers(final boolean inFinished, final int streamId, final int associatedStreamId, final List<Header> headerBlock) {
            if (Http2Connection.this.pushedStream(streamId)) {
                Http2Connection.this.pushHeadersLater(streamId, headerBlock, inFinished);
                return;
            }
            final Http2Stream stream;
            synchronized (Http2Connection.this) {
                if (Http2Connection.this.shutdown) {
                    return;
                }
                stream = Http2Connection.this.getStream(streamId);
                if (stream == null) {
                    if (streamId <= Http2Connection.this.lastGoodStreamId) {
                        return;
                    }
                    if (streamId % 2 == Http2Connection.this.nextStreamId % 2) {
                        return;
                    }
                    final Http2Stream newStream = new Http2Stream(streamId, Http2Connection.this, false, inFinished, headerBlock);
                    Http2Connection.this.lastGoodStreamId = streamId;
                    Http2Connection.this.streams.put(streamId, newStream);
                    Http2Connection.executor.execute(new NamedRunnable("OkHttp %s stream %d", new Object[] { Http2Connection.this.hostname, streamId }) {
                        public void execute() {
                            try {
                                Http2Connection.this.listener.onStream(newStream);
                            }
                            catch (IOException e) {
                                Platform.get().log(4, "Http2Connection.Listener failure for " + Http2Connection.this.hostname, e);
                                try {
                                    newStream.close(ErrorCode.PROTOCOL_ERROR);
                                }
                                catch (IOException ex) {}
                            }
                        }
                    });
                    return;
                }
            }
            stream.receiveHeaders(headerBlock);
            if (inFinished) {
                stream.receiveFin();
            }
        }
        
        @Override
        public void rstStream(final int streamId, final ErrorCode errorCode) {
            if (Http2Connection.this.pushedStream(streamId)) {
                Http2Connection.this.pushResetLater(streamId, errorCode);
                return;
            }
            final Http2Stream rstStream = Http2Connection.this.removeStream(streamId);
            if (rstStream != null) {
                rstStream.receiveRstStream(errorCode);
            }
        }
        
        @Override
        public void settings(final boolean clearPrevious, final Settings newSettings) {
            long delta = 0L;
            Http2Stream[] streamsToNotify = null;
            synchronized (Http2Connection.this) {
                final int priorWriteWindowSize = Http2Connection.this.peerSettings.getInitialWindowSize();
                if (clearPrevious) {
                    Http2Connection.this.peerSettings.clear();
                }
                Http2Connection.this.peerSettings.merge(newSettings);
                this.applyAndAckSettings(newSettings);
                final int peerInitialWindowSize = Http2Connection.this.peerSettings.getInitialWindowSize();
                if (peerInitialWindowSize != -1 && peerInitialWindowSize != priorWriteWindowSize) {
                    delta = peerInitialWindowSize - priorWriteWindowSize;
                    if (!Http2Connection.this.receivedInitialPeerSettings) {
                        Http2Connection.this.addBytesToWriteWindow(delta);
                        Http2Connection.this.receivedInitialPeerSettings = true;
                    }
                    if (!Http2Connection.this.streams.isEmpty()) {
                        streamsToNotify = Http2Connection.this.streams.values().toArray(new Http2Stream[Http2Connection.this.streams.size()]);
                    }
                }
                Http2Connection.executor.execute(new NamedRunnable("OkHttp %s settings", new Object[] { Http2Connection.this.hostname }) {
                    public void execute() {
                        Http2Connection.this.listener.onSettings(Http2Connection.this);
                    }
                });
            }
            if (streamsToNotify != null && delta != 0L) {
                for (final Http2Stream stream : streamsToNotify) {
                    synchronized (stream) {
                        stream.addBytesToWriteWindow(delta);
                    }
                }
            }
        }
        
        private void applyAndAckSettings(final Settings peerSettings) {
            Http2Connection.executor.execute(new NamedRunnable("OkHttp %s ACK Settings", new Object[] { Http2Connection.this.hostname }) {
                public void execute() {
                    try {
                        Http2Connection.this.writer.applyAndAckSettings(peerSettings);
                    }
                    catch (IOException ex) {}
                }
            });
        }
        
        @Override
        public void ackSettings() {
        }
        
        @Override
        public void ping(final boolean reply, final int payload1, final int payload2) {
            if (reply) {
                final Ping ping = Http2Connection.this.removePing(payload1);
                if (ping != null) {
                    ping.receive();
                }
            }
            else {
                Http2Connection.this.writePingLater(true, payload1, payload2, null);
            }
        }
        
        @Override
        public void goAway(final int lastGoodStreamId, final ErrorCode errorCode, final ByteString debugData) {
            if (debugData.size() > 0) {}
            final Http2Stream[] streamsCopy;
            synchronized (Http2Connection.this) {
                streamsCopy = Http2Connection.this.streams.values().toArray(new Http2Stream[Http2Connection.this.streams.size()]);
                Http2Connection.this.shutdown = true;
            }
            for (final Http2Stream http2Stream : streamsCopy) {
                if (http2Stream.getId() > lastGoodStreamId && http2Stream.isLocallyInitiated()) {
                    http2Stream.receiveRstStream(ErrorCode.REFUSED_STREAM);
                    Http2Connection.this.removeStream(http2Stream.getId());
                }
            }
        }
        
        @Override
        public void windowUpdate(final int streamId, final long windowSizeIncrement) {
            if (streamId == 0) {
                synchronized (Http2Connection.this) {
                    final Http2Connection this$0 = Http2Connection.this;
                    this$0.bytesLeftInWriteWindow += windowSizeIncrement;
                    Http2Connection.this.notifyAll();
                }
            }
            else {
                final Http2Stream stream = Http2Connection.this.getStream(streamId);
                if (stream != null) {
                    synchronized (stream) {
                        stream.addBytesToWriteWindow(windowSizeIncrement);
                    }
                }
            }
        }
        
        @Override
        public void priority(final int streamId, final int streamDependency, final int weight, final boolean exclusive) {
        }
        
        @Override
        public void pushPromise(final int streamId, final int promisedStreamId, final List<Header> requestHeaders) {
            Http2Connection.this.pushRequestLater(promisedStreamId, requestHeaders);
        }
        
        @Override
        public void alternateService(final int streamId, final String origin, final ByteString protocol, final String host, final int port, final long maxAge) {
        }
    }
    
    public abstract static class Listener
    {
        public static final Listener REFUSE_INCOMING_STREAMS;
        
        public abstract void onStream(final Http2Stream p0) throws IOException;
        
        public void onSettings(final Http2Connection connection) {
        }
        
        static {
            REFUSE_INCOMING_STREAMS = new Listener() {
                @Override
                public void onStream(final Http2Stream stream) throws IOException {
                    stream.close(ErrorCode.REFUSED_STREAM);
                }
            };
        }
    }
}
