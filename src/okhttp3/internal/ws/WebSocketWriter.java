// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.ws;

import okio.Timeout;
import okio.Sink;
import java.io.IOException;
import okio.ByteString;
import okio.Buffer;
import okio.BufferedSink;
import java.util.Random;

final class WebSocketWriter
{
    final boolean isClient;
    final Random random;
    final BufferedSink sink;
    boolean writerClosed;
    final Buffer buffer;
    final FrameSink frameSink;
    boolean activeWriter;
    final byte[] maskKey;
    final byte[] maskBuffer;
    
    WebSocketWriter(final boolean isClient, final BufferedSink sink, final Random random) {
        this.buffer = new Buffer();
        this.frameSink = new FrameSink();
        if (sink == null) {
            throw new NullPointerException("sink == null");
        }
        if (random == null) {
            throw new NullPointerException("random == null");
        }
        this.isClient = isClient;
        this.sink = sink;
        this.random = random;
        this.maskKey = (byte[])(isClient ? new byte[4] : null);
        this.maskBuffer = (byte[])(isClient ? new byte[8192] : null);
    }
    
    void writePing(final ByteString payload) throws IOException {
        synchronized (this) {
            this.writeControlFrameSynchronized(9, payload);
        }
    }
    
    void writePong(final ByteString payload) throws IOException {
        synchronized (this) {
            this.writeControlFrameSynchronized(10, payload);
        }
    }
    
    void writeClose(final int code, final ByteString reason) throws IOException {
        ByteString payload = ByteString.EMPTY;
        if (code != 0 || reason != null) {
            if (code != 0) {
                WebSocketProtocol.validateCloseCode(code);
            }
            final Buffer buffer = new Buffer();
            buffer.writeShort(code);
            if (reason != null) {
                buffer.write(reason);
            }
            payload = buffer.readByteString();
        }
        synchronized (this) {
            try {
                this.writeControlFrameSynchronized(8, payload);
            }
            finally {
                this.writerClosed = true;
            }
        }
    }
    
    private void writeControlFrameSynchronized(final int opcode, final ByteString payload) throws IOException {
        assert Thread.holdsLock(this);
        if (this.writerClosed) {
            throw new IOException("closed");
        }
        final int length = payload.size();
        if (length > 125L) {
            throw new IllegalArgumentException("Payload size must be less than or equal to 125");
        }
        final int b0 = 0x80 | opcode;
        this.sink.writeByte(b0);
        int b2 = length;
        if (this.isClient) {
            b2 |= 0x80;
            this.sink.writeByte(b2);
            this.random.nextBytes(this.maskKey);
            this.sink.write(this.maskKey);
            final byte[] bytes = payload.toByteArray();
            WebSocketProtocol.toggleMask(bytes, bytes.length, this.maskKey, 0L);
            this.sink.write(bytes);
        }
        else {
            this.sink.writeByte(b2);
            this.sink.write(payload);
        }
        this.sink.flush();
    }
    
    Sink newMessageSink(final int formatOpcode, final long contentLength) {
        if (this.activeWriter) {
            throw new IllegalStateException("Another message writer is active. Did you call close()?");
        }
        this.activeWriter = true;
        this.frameSink.formatOpcode = formatOpcode;
        this.frameSink.contentLength = contentLength;
        this.frameSink.isFirstFrame = true;
        this.frameSink.closed = false;
        return this.frameSink;
    }
    
    void writeMessageFrameSynchronized(final int formatOpcode, final long byteCount, final boolean isFirstFrame, final boolean isFinal) throws IOException {
        assert Thread.holdsLock(this);
        if (this.writerClosed) {
            throw new IOException("closed");
        }
        int b0 = isFirstFrame ? formatOpcode : 0;
        if (isFinal) {
            b0 |= 0x80;
        }
        this.sink.writeByte(b0);
        int b2 = 0;
        if (this.isClient) {
            b2 |= 0x80;
        }
        if (byteCount <= 125L) {
            b2 |= (int)byteCount;
            this.sink.writeByte(b2);
        }
        else if (byteCount <= 65535L) {
            b2 |= 0x7E;
            this.sink.writeByte(b2);
            this.sink.writeShort((int)byteCount);
        }
        else {
            b2 |= 0x7F;
            this.sink.writeByte(b2);
            this.sink.writeLong(byteCount);
        }
        if (this.isClient) {
            this.random.nextBytes(this.maskKey);
            this.sink.write(this.maskKey);
            int read;
            for (long written = 0L; written < byteCount; written += read) {
                final int toRead = (int)Math.min(byteCount, this.maskBuffer.length);
                read = this.buffer.read(this.maskBuffer, 0, toRead);
                if (read == -1) {
                    throw new AssertionError();
                }
                WebSocketProtocol.toggleMask(this.maskBuffer, read, this.maskKey, written);
                this.sink.write(this.maskBuffer, 0, read);
            }
        }
        else {
            this.sink.write(this.buffer, byteCount);
        }
        this.sink.emit();
    }
    
    final class FrameSink implements Sink
    {
        int formatOpcode;
        long contentLength;
        boolean isFirstFrame;
        boolean closed;
        
        @Override
        public void write(final Buffer source, final long byteCount) throws IOException {
            if (this.closed) {
                throw new IOException("closed");
            }
            WebSocketWriter.this.buffer.write(source, byteCount);
            final boolean deferWrite = this.isFirstFrame && this.contentLength != -1L && WebSocketWriter.this.buffer.size() > this.contentLength - 8192L;
            final long emitCount = WebSocketWriter.this.buffer.completeSegmentByteCount();
            if (emitCount > 0L && !deferWrite) {
                synchronized (WebSocketWriter.this) {
                    WebSocketWriter.this.writeMessageFrameSynchronized(this.formatOpcode, emitCount, this.isFirstFrame, false);
                }
                this.isFirstFrame = false;
            }
        }
        
        @Override
        public void flush() throws IOException {
            if (this.closed) {
                throw new IOException("closed");
            }
            synchronized (WebSocketWriter.this) {
                WebSocketWriter.this.writeMessageFrameSynchronized(this.formatOpcode, WebSocketWriter.this.buffer.size(), this.isFirstFrame, false);
            }
            this.isFirstFrame = false;
        }
        
        @Override
        public Timeout timeout() {
            return WebSocketWriter.this.sink.timeout();
        }
        
        @Override
        public void close() throws IOException {
            if (this.closed) {
                throw new IOException("closed");
            }
            synchronized (WebSocketWriter.this) {
                WebSocketWriter.this.writeMessageFrameSynchronized(this.formatOpcode, WebSocketWriter.this.buffer.size(), this.isFirstFrame, true);
            }
            this.closed = true;
            WebSocketWriter.this.activeWriter = false;
        }
    }
}
