// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.http2;

import java.net.SocketTimeoutException;
import okio.AsyncTimeout;
import java.io.EOFException;
import okio.Buffer;
import java.io.InterruptedIOException;
import okio.BufferedSource;
import java.util.Collection;
import java.util.ArrayList;
import okio.Sink;
import okio.Source;
import okio.Timeout;
import java.io.IOException;
import java.util.List;

public final class Http2Stream
{
    long unacknowledgedBytesRead;
    long bytesLeftInWriteWindow;
    final int id;
    final Http2Connection connection;
    private final List<Header> requestHeaders;
    private List<Header> responseHeaders;
    private boolean hasResponseHeaders;
    private final FramingSource source;
    final FramingSink sink;
    final StreamTimeout readTimeout;
    final StreamTimeout writeTimeout;
    ErrorCode errorCode;
    
    Http2Stream(final int id, final Http2Connection connection, final boolean outFinished, final boolean inFinished, final List<Header> requestHeaders) {
        this.unacknowledgedBytesRead = 0L;
        this.readTimeout = new StreamTimeout();
        this.writeTimeout = new StreamTimeout();
        this.errorCode = null;
        if (connection == null) {
            throw new NullPointerException("connection == null");
        }
        if (requestHeaders == null) {
            throw new NullPointerException("requestHeaders == null");
        }
        this.id = id;
        this.connection = connection;
        this.bytesLeftInWriteWindow = connection.peerSettings.getInitialWindowSize();
        this.source = new FramingSource(connection.okHttpSettings.getInitialWindowSize());
        this.sink = new FramingSink();
        this.source.finished = inFinished;
        this.sink.finished = outFinished;
        this.requestHeaders = requestHeaders;
    }
    
    public int getId() {
        return this.id;
    }
    
    public synchronized boolean isOpen() {
        return this.errorCode == null && ((!this.source.finished && !this.source.closed) || (!this.sink.finished && !this.sink.closed) || !this.hasResponseHeaders);
    }
    
    public boolean isLocallyInitiated() {
        final boolean streamIsClient = (this.id & 0x1) == 0x1;
        return this.connection.client == streamIsClient;
    }
    
    public Http2Connection getConnection() {
        return this.connection;
    }
    
    public List<Header> getRequestHeaders() {
        return this.requestHeaders;
    }
    
    public synchronized List<Header> takeResponseHeaders() throws IOException {
        if (!this.isLocallyInitiated()) {
            throw new IllegalStateException("servers cannot read response headers");
        }
        this.readTimeout.enter();
        try {
            while (this.responseHeaders == null && this.errorCode == null) {
                this.waitForIo();
            }
        }
        finally {
            this.readTimeout.exitAndThrowIfTimedOut();
        }
        final List<Header> result = this.responseHeaders;
        if (result != null) {
            this.responseHeaders = null;
            return result;
        }
        throw new StreamResetException(this.errorCode);
    }
    
    public synchronized ErrorCode getErrorCode() {
        return this.errorCode;
    }
    
    public void sendResponseHeaders(final List<Header> responseHeaders, final boolean out) throws IOException {
        assert !Thread.holdsLock(this);
        if (responseHeaders == null) {
            throw new NullPointerException("responseHeaders == null");
        }
        boolean outFinished = false;
        synchronized (this) {
            this.hasResponseHeaders = true;
            if (!out) {
                this.sink.finished = true;
                outFinished = true;
            }
        }
        this.connection.writeSynReply(this.id, outFinished, responseHeaders);
        if (outFinished) {
            this.connection.flush();
        }
    }
    
    public Timeout readTimeout() {
        return this.readTimeout;
    }
    
    public Timeout writeTimeout() {
        return this.writeTimeout;
    }
    
    public Source getSource() {
        return this.source;
    }
    
    public Sink getSink() {
        synchronized (this) {
            if (!this.hasResponseHeaders && !this.isLocallyInitiated()) {
                throw new IllegalStateException("reply before requesting the sink");
            }
        }
        return this.sink;
    }
    
    public void close(final ErrorCode rstStatusCode) throws IOException {
        if (!this.closeInternal(rstStatusCode)) {
            return;
        }
        this.connection.writeSynReset(this.id, rstStatusCode);
    }
    
    public void closeLater(final ErrorCode errorCode) {
        if (!this.closeInternal(errorCode)) {
            return;
        }
        this.connection.writeSynResetLater(this.id, errorCode);
    }
    
    private boolean closeInternal(final ErrorCode errorCode) {
        assert !Thread.holdsLock(this);
        synchronized (this) {
            if (this.errorCode != null) {
                return false;
            }
            if (this.source.finished && this.sink.finished) {
                return false;
            }
            this.errorCode = errorCode;
            this.notifyAll();
        }
        this.connection.removeStream(this.id);
        return true;
    }
    
    void receiveHeaders(final List<Header> headers) {
        assert !Thread.holdsLock(this);
        boolean open = true;
        synchronized (this) {
            this.hasResponseHeaders = true;
            if (this.responseHeaders == null) {
                this.responseHeaders = headers;
                open = this.isOpen();
                this.notifyAll();
            }
            else {
                final List<Header> newHeaders = new ArrayList<Header>();
                newHeaders.addAll(this.responseHeaders);
                newHeaders.add(null);
                newHeaders.addAll(headers);
                this.responseHeaders = newHeaders;
            }
        }
        if (!open) {
            this.connection.removeStream(this.id);
        }
    }
    
    void receiveData(final BufferedSource in, final int length) throws IOException {
        assert !Thread.holdsLock(this);
        this.source.receive(in, length);
    }
    
    void receiveFin() {
        assert !Thread.holdsLock(this);
        final boolean open;
        synchronized (this) {
            this.source.finished = true;
            open = this.isOpen();
            this.notifyAll();
        }
        if (!open) {
            this.connection.removeStream(this.id);
        }
    }
    
    synchronized void receiveRstStream(final ErrorCode errorCode) {
        if (this.errorCode == null) {
            this.errorCode = errorCode;
            this.notifyAll();
        }
    }
    
    void cancelStreamIfNecessary() throws IOException {
        assert !Thread.holdsLock(this);
        final boolean cancel;
        final boolean open;
        synchronized (this) {
            cancel = (!this.source.finished && this.source.closed && (this.sink.finished || this.sink.closed));
            open = this.isOpen();
        }
        if (cancel) {
            this.close(ErrorCode.CANCEL);
        }
        else if (!open) {
            this.connection.removeStream(this.id);
        }
    }
    
    void addBytesToWriteWindow(final long delta) {
        this.bytesLeftInWriteWindow += delta;
        if (delta > 0L) {
            this.notifyAll();
        }
    }
    
    void checkOutNotClosed() throws IOException {
        if (this.sink.closed) {
            throw new IOException("stream closed");
        }
        if (this.sink.finished) {
            throw new IOException("stream finished");
        }
        if (this.errorCode != null) {
            throw new StreamResetException(this.errorCode);
        }
    }
    
    void waitForIo() throws InterruptedIOException {
        try {
            this.wait();
        }
        catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }
    
    private final class FramingSource implements Source
    {
        private final Buffer receiveBuffer;
        private final Buffer readBuffer;
        private final long maxByteCount;
        boolean closed;
        boolean finished;
        
        FramingSource(final long maxByteCount) {
            this.receiveBuffer = new Buffer();
            this.readBuffer = new Buffer();
            this.maxByteCount = maxByteCount;
        }
        
        @Override
        public long read(final Buffer sink, final long byteCount) throws IOException {
            if (byteCount < 0L) {
                throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            }
            final long read;
            synchronized (Http2Stream.this) {
                this.waitUntilReadable();
                this.checkNotClosed();
                if (this.readBuffer.size() == 0L) {
                    return -1L;
                }
                read = this.readBuffer.read(sink, Math.min(byteCount, this.readBuffer.size()));
                final Http2Stream this$0 = Http2Stream.this;
                this$0.unacknowledgedBytesRead += read;
                if (Http2Stream.this.unacknowledgedBytesRead >= Http2Stream.this.connection.okHttpSettings.getInitialWindowSize() / 2) {
                    Http2Stream.this.connection.writeWindowUpdateLater(Http2Stream.this.id, Http2Stream.this.unacknowledgedBytesRead);
                    Http2Stream.this.unacknowledgedBytesRead = 0L;
                }
            }
            synchronized (Http2Stream.this.connection) {
                final Http2Connection connection = Http2Stream.this.connection;
                connection.unacknowledgedBytesRead += read;
                if (Http2Stream.this.connection.unacknowledgedBytesRead >= Http2Stream.this.connection.okHttpSettings.getInitialWindowSize() / 2) {
                    Http2Stream.this.connection.writeWindowUpdateLater(0, Http2Stream.this.connection.unacknowledgedBytesRead);
                    Http2Stream.this.connection.unacknowledgedBytesRead = 0L;
                }
            }
            return read;
        }
        
        private void waitUntilReadable() throws IOException {
            Http2Stream.this.readTimeout.enter();
            try {
                while (this.readBuffer.size() == 0L && !this.finished && !this.closed && Http2Stream.this.errorCode == null) {
                    Http2Stream.this.waitForIo();
                }
            }
            finally {
                Http2Stream.this.readTimeout.exitAndThrowIfTimedOut();
            }
        }
        
        void receive(final BufferedSource in, long byteCount) throws IOException {
            assert !Thread.holdsLock(Http2Stream.this);
            while (byteCount > 0L) {
                final boolean finished;
                final boolean flowControlError;
                synchronized (Http2Stream.this) {
                    finished = this.finished;
                    flowControlError = (byteCount + this.readBuffer.size() > this.maxByteCount);
                }
                if (flowControlError) {
                    in.skip(byteCount);
                    Http2Stream.this.closeLater(ErrorCode.FLOW_CONTROL_ERROR);
                    return;
                }
                if (finished) {
                    in.skip(byteCount);
                    return;
                }
                final long read = in.read(this.receiveBuffer, byteCount);
                if (read == -1L) {
                    throw new EOFException();
                }
                byteCount -= read;
                synchronized (Http2Stream.this) {
                    final boolean wasEmpty = this.readBuffer.size() == 0L;
                    this.readBuffer.writeAll(this.receiveBuffer);
                    if (!wasEmpty) {
                        continue;
                    }
                    Http2Stream.this.notifyAll();
                }
            }
        }
        
        @Override
        public Timeout timeout() {
            return Http2Stream.this.readTimeout;
        }
        
        @Override
        public void close() throws IOException {
            synchronized (Http2Stream.this) {
                this.closed = true;
                this.readBuffer.clear();
                Http2Stream.this.notifyAll();
            }
            Http2Stream.this.cancelStreamIfNecessary();
        }
        
        private void checkNotClosed() throws IOException {
            if (this.closed) {
                throw new IOException("stream closed");
            }
            if (Http2Stream.this.errorCode != null) {
                throw new StreamResetException(Http2Stream.this.errorCode);
            }
        }
    }
    
    final class FramingSink implements Sink
    {
        private static final long EMIT_BUFFER_SIZE = 16384L;
        private final Buffer sendBuffer;
        boolean closed;
        boolean finished;
        
        FramingSink() {
            this.sendBuffer = new Buffer();
        }
        
        @Override
        public void write(final Buffer source, final long byteCount) throws IOException {
            assert !Thread.holdsLock(Http2Stream.this);
            this.sendBuffer.write(source, byteCount);
            while (this.sendBuffer.size() >= 16384L) {
                this.emitFrame(false);
            }
        }
        
        private void emitFrame(final boolean outFinished) throws IOException {
            // 
            // This method could not be decompiled.
            // 
            // Original Bytecode:
            // 
            //     0: aload_0         /* this */
            //     1: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //     4: dup            
            //     5: astore          4
            //     7: monitorenter   
            //     8: aload_0         /* this */
            //     9: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //    12: getfield        okhttp3/internal/http2/Http2Stream.writeTimeout:Lokhttp3/internal/http2/Http2Stream$StreamTimeout;
            //    15: invokevirtual   okhttp3/internal/http2/Http2Stream$StreamTimeout.enter:()V
            //    18: aload_0         /* this */
            //    19: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //    22: getfield        okhttp3/internal/http2/Http2Stream.bytesLeftInWriteWindow:J
            //    25: lconst_0       
            //    26: lcmp           
            //    27: ifgt            64
            //    30: aload_0         /* this */
            //    31: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.finished:Z
            //    34: ifne            64
            //    37: aload_0         /* this */
            //    38: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.closed:Z
            //    41: ifne            64
            //    44: aload_0         /* this */
            //    45: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //    48: getfield        okhttp3/internal/http2/Http2Stream.errorCode:Lokhttp3/internal/http2/ErrorCode;
            //    51: ifnonnull       64
            //    54: aload_0         /* this */
            //    55: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //    58: invokevirtual   okhttp3/internal/http2/Http2Stream.waitForIo:()V
            //    61: goto            18
            //    64: aload_0         /* this */
            //    65: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //    68: getfield        okhttp3/internal/http2/Http2Stream.writeTimeout:Lokhttp3/internal/http2/Http2Stream$StreamTimeout;
            //    71: invokevirtual   okhttp3/internal/http2/Http2Stream$StreamTimeout.exitAndThrowIfTimedOut:()V
            //    74: goto            92
            //    77: astore          5
            //    79: aload_0         /* this */
            //    80: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //    83: getfield        okhttp3/internal/http2/Http2Stream.writeTimeout:Lokhttp3/internal/http2/Http2Stream$StreamTimeout;
            //    86: invokevirtual   okhttp3/internal/http2/Http2Stream$StreamTimeout.exitAndThrowIfTimedOut:()V
            //    89: aload           5
            //    91: athrow         
            //    92: aload_0         /* this */
            //    93: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //    96: invokevirtual   okhttp3/internal/http2/Http2Stream.checkOutNotClosed:()V
            //    99: aload_0         /* this */
            //   100: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //   103: getfield        okhttp3/internal/http2/Http2Stream.bytesLeftInWriteWindow:J
            //   106: aload_0         /* this */
            //   107: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.sendBuffer:Lokio/Buffer;
            //   110: invokevirtual   okio/Buffer.size:()J
            //   113: invokestatic    java/lang/Math.min:(JJ)J
            //   116: lstore_2        /* toWrite */
            //   117: aload_0         /* this */
            //   118: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //   121: dup            
            //   122: getfield        okhttp3/internal/http2/Http2Stream.bytesLeftInWriteWindow:J
            //   125: lload_2         /* toWrite */
            //   126: lsub           
            //   127: putfield        okhttp3/internal/http2/Http2Stream.bytesLeftInWriteWindow:J
            //   130: aload           4
            //   132: monitorexit    
            //   133: goto            144
            //   136: astore          6
            //   138: aload           4
            //   140: monitorexit    
            //   141: aload           6
            //   143: athrow         
            //   144: aload_0         /* this */
            //   145: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //   148: getfield        okhttp3/internal/http2/Http2Stream.writeTimeout:Lokhttp3/internal/http2/Http2Stream$StreamTimeout;
            //   151: invokevirtual   okhttp3/internal/http2/Http2Stream$StreamTimeout.enter:()V
            //   154: aload_0         /* this */
            //   155: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //   158: getfield        okhttp3/internal/http2/Http2Stream.connection:Lokhttp3/internal/http2/Http2Connection;
            //   161: aload_0         /* this */
            //   162: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //   165: getfield        okhttp3/internal/http2/Http2Stream.id:I
            //   168: iload_1         /* outFinished */
            //   169: ifeq            188
            //   172: lload_2         /* toWrite */
            //   173: aload_0         /* this */
            //   174: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.sendBuffer:Lokio/Buffer;
            //   177: invokevirtual   okio/Buffer.size:()J
            //   180: lcmp           
            //   181: ifne            188
            //   184: iconst_1       
            //   185: goto            189
            //   188: iconst_0       
            //   189: aload_0         /* this */
            //   190: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.sendBuffer:Lokio/Buffer;
            //   193: lload_2         /* toWrite */
            //   194: invokevirtual   okhttp3/internal/http2/Http2Connection.writeData:(IZLokio/Buffer;J)V
            //   197: aload_0         /* this */
            //   198: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //   201: getfield        okhttp3/internal/http2/Http2Stream.writeTimeout:Lokhttp3/internal/http2/Http2Stream$StreamTimeout;
            //   204: invokevirtual   okhttp3/internal/http2/Http2Stream$StreamTimeout.exitAndThrowIfTimedOut:()V
            //   207: goto            225
            //   210: astore          7
            //   212: aload_0         /* this */
            //   213: getfield        okhttp3/internal/http2/Http2Stream$FramingSink.this$0:Lokhttp3/internal/http2/Http2Stream;
            //   216: getfield        okhttp3/internal/http2/Http2Stream.writeTimeout:Lokhttp3/internal/http2/Http2Stream$StreamTimeout;
            //   219: invokevirtual   okhttp3/internal/http2/Http2Stream$StreamTimeout.exitAndThrowIfTimedOut:()V
            //   222: aload           7
            //   224: athrow         
            //   225: return         
            //    Exceptions:
            //  throws java.io.IOException
            //    LocalVariableTable:
            //  Start  Length  Slot  Name         Signature
            //  -----  ------  ----  -----------  ------------------------------------------------
            //  117    19      2     toWrite      J
            //  0      226     0     this         Lokhttp3/internal/http2/Http2Stream$FramingSink;
            //  0      226     1     outFinished  Z
            //  144    82      2     toWrite      J
            //    Exceptions:
            //  Try           Handler
            //  Start  End    Start  End    Type
            //  -----  -----  -----  -----  ----
            //  18     64     77     92     Any
            //  77     79     77     92     Any
            //  8      133    136    144    Any
            //  136    141    136    144    Any
            //  154    197    210    225    Any
            //  210    212    210    225    Any
            // 
            // The error that occurred was:
            // 
            // java.lang.IndexOutOfBoundsException: Index: 4, Size: 4
            //     at java.util.ArrayList.rangeCheck(ArrayList.java:653)
            //     at java.util.ArrayList.get(ArrayList.java:429)
            //     at com.strobel.assembler.Collection.get(Collection.java:43)
            //     at java.util.Collections$UnmodifiableList.get(Collections.java:1309)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.adjustArgumentsForMethodCallCore(AstMethodBodyBuilder.java:1310)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.adjustArgumentsForMethodCall(AstMethodBodyBuilder.java:1283)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.transformCall(AstMethodBodyBuilder.java:1195)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.transformByteCode(AstMethodBodyBuilder.java:714)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.transformExpression(AstMethodBodyBuilder.java:540)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.transformNode(AstMethodBodyBuilder.java:392)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.transformBlock(AstMethodBodyBuilder.java:333)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.transformNode(AstMethodBodyBuilder.java:494)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.transformBlock(AstMethodBodyBuilder.java:333)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.createMethodBody(AstMethodBodyBuilder.java:294)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.createMethodBody(AstMethodBodyBuilder.java:99)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createMethodBody(AstBuilder.java:757)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createMethod(AstBuilder.java:655)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.addTypeMembers(AstBuilder.java:532)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeCore(AstBuilder.java:499)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeNoCache(AstBuilder.java:141)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.addTypeMembers(AstBuilder.java:556)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeCore(AstBuilder.java:499)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeNoCache(AstBuilder.java:141)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createType(AstBuilder.java:130)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.addType(AstBuilder.java:105)
            //     at com.strobel.decompiler.languages.java.JavaLanguage.buildAst(JavaLanguage.java:71)
            //     at com.strobel.decompiler.languages.java.JavaLanguage.decompileType(JavaLanguage.java:59)
            //     at com.strobel.decompiler.DecompilerDriver.decompileType(DecompilerDriver.java:317)
            //     at com.strobel.decompiler.DecompilerDriver.decompileJar(DecompilerDriver.java:238)
            //     at com.strobel.decompiler.DecompilerDriver.main(DecompilerDriver.java:123)
            // 
            throw new IllegalStateException("An error occurred while decompiling this method.");
        }
        
        @Override
        public void flush() throws IOException {
            assert !Thread.holdsLock(Http2Stream.this);
            synchronized (Http2Stream.this) {
                Http2Stream.this.checkOutNotClosed();
            }
            while (this.sendBuffer.size() > 0L) {
                this.emitFrame(false);
                Http2Stream.this.connection.flush();
            }
        }
        
        @Override
        public Timeout timeout() {
            return Http2Stream.this.writeTimeout;
        }
        
        @Override
        public void close() throws IOException {
            assert !Thread.holdsLock(Http2Stream.this);
            synchronized (Http2Stream.this) {
                if (this.closed) {
                    return;
                }
            }
            if (!Http2Stream.this.sink.finished) {
                if (this.sendBuffer.size() > 0L) {
                    while (this.sendBuffer.size() > 0L) {
                        this.emitFrame(true);
                    }
                }
                else {
                    Http2Stream.this.connection.writeData(Http2Stream.this.id, true, null, 0L);
                }
            }
            synchronized (Http2Stream.this) {
                this.closed = true;
            }
            Http2Stream.this.connection.flush();
            Http2Stream.this.cancelStreamIfNecessary();
        }
    }
    
    class StreamTimeout extends AsyncTimeout
    {
        @Override
        protected void timedOut() {
            Http2Stream.this.closeLater(ErrorCode.CANCEL);
        }
        
        @Override
        protected IOException newTimeoutException(final IOException cause) {
            final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("timeout");
            if (cause != null) {
                socketTimeoutException.initCause(cause);
            }
            return socketTimeoutException;
        }
        
        public void exitAndThrowIfTimedOut() throws IOException {
            if (this.exit()) {
                throw this.newTimeoutException(null);
            }
        }
    }
}
