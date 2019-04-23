// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.http1;

import java.util.concurrent.TimeUnit;
import java.net.ProtocolException;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.Timeout;
import okio.ForwardingTimeout;
import okhttp3.HttpUrl;
import okhttp3.internal.Internal;
import java.io.EOFException;
import okhttp3.internal.http.StatusLine;
import okhttp3.Headers;
import okhttp3.internal.http.HttpHeaders;
import okio.Source;
import okhttp3.internal.http.RealResponseBody;
import okio.Okio;
import okhttp3.ResponseBody;
import okhttp3.Response;
import java.io.IOException;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.connection.RealConnection;
import okio.Sink;
import okhttp3.Request;
import okio.BufferedSink;
import okio.BufferedSource;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.OkHttpClient;
import okhttp3.internal.http.HttpCodec;

public final class Http1Codec implements HttpCodec
{
    private static final int STATE_IDLE = 0;
    private static final int STATE_OPEN_REQUEST_BODY = 1;
    private static final int STATE_WRITING_REQUEST_BODY = 2;
    private static final int STATE_READ_RESPONSE_HEADERS = 3;
    private static final int STATE_OPEN_RESPONSE_BODY = 4;
    private static final int STATE_READING_RESPONSE_BODY = 5;
    private static final int STATE_CLOSED = 6;
    final OkHttpClient client;
    final StreamAllocation streamAllocation;
    final BufferedSource source;
    final BufferedSink sink;
    int state;
    
    public Http1Codec(final OkHttpClient client, final StreamAllocation streamAllocation, final BufferedSource source, final BufferedSink sink) {
        this.state = 0;
        this.client = client;
        this.streamAllocation = streamAllocation;
        this.source = source;
        this.sink = sink;
    }
    
    @Override
    public Sink createRequestBody(final Request request, final long contentLength) {
        if ("chunked".equalsIgnoreCase(request.header("Transfer-Encoding"))) {
            return this.newChunkedSink();
        }
        if (contentLength != -1L) {
            return this.newFixedLengthSink(contentLength);
        }
        throw new IllegalStateException("Cannot stream a request body without chunked encoding or a known content length!");
    }
    
    @Override
    public void cancel() {
        final RealConnection connection = this.streamAllocation.connection();
        if (connection != null) {
            connection.cancel();
        }
    }
    
    @Override
    public void writeRequestHeaders(final Request request) throws IOException {
        final String requestLine = RequestLine.get(request, this.streamAllocation.connection().route().proxy().type());
        this.writeRequest(request.headers(), requestLine);
    }
    
    @Override
    public ResponseBody openResponseBody(final Response response) throws IOException {
        final Source source = this.getTransferStream(response);
        return new RealResponseBody(response.headers(), Okio.buffer(source));
    }
    
    private Source getTransferStream(final Response response) throws IOException {
        if (!HttpHeaders.hasBody(response)) {
            return this.newFixedLengthSource(0L);
        }
        if ("chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
            return this.newChunkedSource(response.request().url());
        }
        final long contentLength = HttpHeaders.contentLength(response);
        if (contentLength != -1L) {
            return this.newFixedLengthSource(contentLength);
        }
        return this.newUnknownLengthSource();
    }
    
    public boolean isClosed() {
        return this.state == 6;
    }
    
    @Override
    public void flushRequest() throws IOException {
        this.sink.flush();
    }
    
    @Override
    public void finishRequest() throws IOException {
        this.sink.flush();
    }
    
    public void writeRequest(final Headers headers, final String requestLine) throws IOException {
        if (this.state != 0) {
            throw new IllegalStateException("state: " + this.state);
        }
        this.sink.writeUtf8(requestLine).writeUtf8("\r\n");
        for (int i = 0, size = headers.size(); i < size; ++i) {
            this.sink.writeUtf8(headers.name(i)).writeUtf8(": ").writeUtf8(headers.value(i)).writeUtf8("\r\n");
        }
        this.sink.writeUtf8("\r\n");
        this.state = 1;
    }
    
    @Override
    public Response.Builder readResponseHeaders(final boolean expectContinue) throws IOException {
        if (this.state != 1 && this.state != 3) {
            throw new IllegalStateException("state: " + this.state);
        }
        try {
            final StatusLine statusLine = StatusLine.parse(this.source.readUtf8LineStrict());
            final Response.Builder responseBuilder = new Response.Builder().protocol(statusLine.protocol).code(statusLine.code).message(statusLine.message).headers(this.readHeaders());
            if (expectContinue && statusLine.code == 100) {
                return null;
            }
            this.state = 4;
            return responseBuilder;
        }
        catch (EOFException e) {
            final IOException exception = new IOException("unexpected end of stream on " + this.streamAllocation);
            exception.initCause(e);
            throw exception;
        }
    }
    
    public Headers readHeaders() throws IOException {
        final Headers.Builder headers = new Headers.Builder();
        String line;
        while ((line = this.source.readUtf8LineStrict()).length() != 0) {
            Internal.instance.addLenient(headers, line);
        }
        return headers.build();
    }
    
    public Sink newChunkedSink() {
        if (this.state != 1) {
            throw new IllegalStateException("state: " + this.state);
        }
        this.state = 2;
        return new ChunkedSink();
    }
    
    public Sink newFixedLengthSink(final long contentLength) {
        if (this.state != 1) {
            throw new IllegalStateException("state: " + this.state);
        }
        this.state = 2;
        return new FixedLengthSink(contentLength);
    }
    
    public Source newFixedLengthSource(final long length) throws IOException {
        if (this.state != 4) {
            throw new IllegalStateException("state: " + this.state);
        }
        this.state = 5;
        return new FixedLengthSource(length);
    }
    
    public Source newChunkedSource(final HttpUrl url) throws IOException {
        if (this.state != 4) {
            throw new IllegalStateException("state: " + this.state);
        }
        this.state = 5;
        return new ChunkedSource(url);
    }
    
    public Source newUnknownLengthSource() throws IOException {
        if (this.state != 4) {
            throw new IllegalStateException("state: " + this.state);
        }
        if (this.streamAllocation == null) {
            throw new IllegalStateException("streamAllocation == null");
        }
        this.state = 5;
        this.streamAllocation.noNewStreams();
        return new UnknownLengthSource();
    }
    
    void detachTimeout(final ForwardingTimeout timeout) {
        final Timeout oldDelegate = timeout.delegate();
        timeout.setDelegate(Timeout.NONE);
        oldDelegate.clearDeadline();
        oldDelegate.clearTimeout();
    }
    
    private final class FixedLengthSink implements Sink
    {
        private final ForwardingTimeout timeout;
        private boolean closed;
        private long bytesRemaining;
        
        FixedLengthSink(final long bytesRemaining) {
            this.timeout = new ForwardingTimeout(Http1Codec.this.sink.timeout());
            this.bytesRemaining = bytesRemaining;
        }
        
        @Override
        public Timeout timeout() {
            return this.timeout;
        }
        
        @Override
        public void write(final Buffer source, final long byteCount) throws IOException {
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            Util.checkOffsetAndCount(source.size(), 0L, byteCount);
            if (byteCount > this.bytesRemaining) {
                throw new ProtocolException("expected " + this.bytesRemaining + " bytes but received " + byteCount);
            }
            Http1Codec.this.sink.write(source, byteCount);
            this.bytesRemaining -= byteCount;
        }
        
        @Override
        public void flush() throws IOException {
            if (this.closed) {
                return;
            }
            Http1Codec.this.sink.flush();
        }
        
        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.bytesRemaining > 0L) {
                throw new ProtocolException("unexpected end of stream");
            }
            Http1Codec.this.detachTimeout(this.timeout);
            Http1Codec.this.state = 3;
        }
    }
    
    private final class ChunkedSink implements Sink
    {
        private final ForwardingTimeout timeout;
        private boolean closed;
        
        ChunkedSink() {
            this.timeout = new ForwardingTimeout(Http1Codec.this.sink.timeout());
        }
        
        @Override
        public Timeout timeout() {
            return this.timeout;
        }
        
        @Override
        public void write(final Buffer source, final long byteCount) throws IOException {
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            if (byteCount == 0L) {
                return;
            }
            Http1Codec.this.sink.writeHexadecimalUnsignedLong(byteCount);
            Http1Codec.this.sink.writeUtf8("\r\n");
            Http1Codec.this.sink.write(source, byteCount);
            Http1Codec.this.sink.writeUtf8("\r\n");
        }
        
        @Override
        public synchronized void flush() throws IOException {
            if (this.closed) {
                return;
            }
            Http1Codec.this.sink.flush();
        }
        
        @Override
        public synchronized void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            Http1Codec.this.sink.writeUtf8("0\r\n\r\n");
            Http1Codec.this.detachTimeout(this.timeout);
            Http1Codec.this.state = 3;
        }
    }
    
    private abstract class AbstractSource implements Source
    {
        protected final ForwardingTimeout timeout;
        protected boolean closed;
        
        private AbstractSource() {
            this.timeout = new ForwardingTimeout(Http1Codec.this.source.timeout());
        }
        
        @Override
        public Timeout timeout() {
            return this.timeout;
        }
        
        protected final void endOfInput(final boolean reuseConnection) throws IOException {
            if (Http1Codec.this.state == 6) {
                return;
            }
            if (Http1Codec.this.state != 5) {
                throw new IllegalStateException("state: " + Http1Codec.this.state);
            }
            Http1Codec.this.detachTimeout(this.timeout);
            Http1Codec.this.state = 6;
            if (Http1Codec.this.streamAllocation != null) {
                Http1Codec.this.streamAllocation.streamFinished(!reuseConnection, Http1Codec.this);
            }
        }
    }
    
    private class FixedLengthSource extends AbstractSource
    {
        private long bytesRemaining;
        
        FixedLengthSource(final long length) throws IOException {
            this.bytesRemaining = length;
            if (this.bytesRemaining == 0L) {
                this.endOfInput(true);
            }
        }
        
        @Override
        public long read(final Buffer sink, final long byteCount) throws IOException {
            if (byteCount < 0L) {
                throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            }
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            if (this.bytesRemaining == 0L) {
                return -1L;
            }
            final long read = Http1Codec.this.source.read(sink, Math.min(this.bytesRemaining, byteCount));
            if (read == -1L) {
                this.endOfInput(false);
                throw new ProtocolException("unexpected end of stream");
            }
            this.bytesRemaining -= read;
            if (this.bytesRemaining == 0L) {
                this.endOfInput(true);
            }
            return read;
        }
        
        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            if (this.bytesRemaining != 0L && !Util.discard(this, 100, TimeUnit.MILLISECONDS)) {
                this.endOfInput(false);
            }
            this.closed = true;
        }
    }
    
    private class ChunkedSource extends AbstractSource
    {
        private static final long NO_CHUNK_YET = -1L;
        private final HttpUrl url;
        private long bytesRemainingInChunk;
        private boolean hasMoreChunks;
        
        ChunkedSource(final HttpUrl url) {
            this.bytesRemainingInChunk = -1L;
            this.hasMoreChunks = true;
            this.url = url;
        }
        
        @Override
        public long read(final Buffer sink, final long byteCount) throws IOException {
            if (byteCount < 0L) {
                throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            }
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            if (!this.hasMoreChunks) {
                return -1L;
            }
            if (this.bytesRemainingInChunk == 0L || this.bytesRemainingInChunk == -1L) {
                this.readChunkSize();
                if (!this.hasMoreChunks) {
                    return -1L;
                }
            }
            final long read = Http1Codec.this.source.read(sink, Math.min(byteCount, this.bytesRemainingInChunk));
            if (read == -1L) {
                this.endOfInput(false);
                throw new ProtocolException("unexpected end of stream");
            }
            this.bytesRemainingInChunk -= read;
            return read;
        }
        
        private void readChunkSize() throws IOException {
            if (this.bytesRemainingInChunk != -1L) {
                Http1Codec.this.source.readUtf8LineStrict();
            }
            try {
                this.bytesRemainingInChunk = Http1Codec.this.source.readHexadecimalUnsignedLong();
                final String extensions = Http1Codec.this.source.readUtf8LineStrict().trim();
                if (this.bytesRemainingInChunk < 0L || (!extensions.isEmpty() && !extensions.startsWith(";"))) {
                    throw new ProtocolException("expected chunk size and optional extensions but was \"" + this.bytesRemainingInChunk + extensions + "\"");
                }
            }
            catch (NumberFormatException e) {
                throw new ProtocolException(e.getMessage());
            }
            if (this.bytesRemainingInChunk == 0L) {
                this.hasMoreChunks = false;
                HttpHeaders.receiveHeaders(Http1Codec.this.client.cookieJar(), this.url, Http1Codec.this.readHeaders());
                this.endOfInput(true);
            }
        }
        
        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            if (this.hasMoreChunks && !Util.discard(this, 100, TimeUnit.MILLISECONDS)) {
                this.endOfInput(false);
            }
            this.closed = true;
        }
    }
    
    private class UnknownLengthSource extends AbstractSource
    {
        private boolean inputExhausted;
        
        @Override
        public long read(final Buffer sink, final long byteCount) throws IOException {
            if (byteCount < 0L) {
                throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            }
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            if (this.inputExhausted) {
                return -1L;
            }
            final long read = Http1Codec.this.source.read(sink, byteCount);
            if (read == -1L) {
                this.endOfInput(this.inputExhausted = true);
                return -1L;
            }
            return read;
        }
        
        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            if (!this.inputExhausted) {
                this.endOfInput(false);
            }
            this.closed = true;
        }
    }
}
