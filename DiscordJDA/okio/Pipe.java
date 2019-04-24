// 
// Decompiled by Procyon v0.5.30
// 

package okio;

import java.io.IOException;

public final class Pipe
{
    final long maxBufferSize;
    final Buffer buffer;
    boolean sinkClosed;
    boolean sourceClosed;
    private final Sink sink;
    private final Source source;
    
    public Pipe(final long maxBufferSize) {
        this.buffer = new Buffer();
        this.sink = new PipeSink();
        this.source = new PipeSource();
        if (maxBufferSize < 1L) {
            throw new IllegalArgumentException("maxBufferSize < 1: " + maxBufferSize);
        }
        this.maxBufferSize = maxBufferSize;
    }
    
    public Source source() {
        return this.source;
    }
    
    public Sink sink() {
        return this.sink;
    }
    
    final class PipeSink implements Sink
    {
        final Timeout timeout;
        
        PipeSink() {
            this.timeout = new Timeout();
        }
        
        @Override
        public void write(final Buffer source, long byteCount) throws IOException {
            synchronized (Pipe.this.buffer) {
                if (Pipe.this.sinkClosed) {
                    throw new IllegalStateException("closed");
                }
                while (byteCount > 0L) {
                    if (Pipe.this.sourceClosed) {
                        throw new IOException("source is closed");
                    }
                    final long bufferSpaceAvailable = Pipe.this.maxBufferSize - Pipe.this.buffer.size();
                    if (bufferSpaceAvailable == 0L) {
                        this.timeout.waitUntilNotified(Pipe.this.buffer);
                    }
                    else {
                        final long bytesToWrite = Math.min(bufferSpaceAvailable, byteCount);
                        Pipe.this.buffer.write(source, bytesToWrite);
                        byteCount -= bytesToWrite;
                        Pipe.this.buffer.notifyAll();
                    }
                }
            }
        }
        
        @Override
        public void flush() throws IOException {
            synchronized (Pipe.this.buffer) {
                if (Pipe.this.sinkClosed) {
                    throw new IllegalStateException("closed");
                }
                if (Pipe.this.sourceClosed && Pipe.this.buffer.size() > 0L) {
                    throw new IOException("source is closed");
                }
            }
        }
        
        @Override
        public void close() throws IOException {
            synchronized (Pipe.this.buffer) {
                if (Pipe.this.sinkClosed) {
                    return;
                }
                if (Pipe.this.sourceClosed && Pipe.this.buffer.size() > 0L) {
                    throw new IOException("source is closed");
                }
                Pipe.this.sinkClosed = true;
                Pipe.this.buffer.notifyAll();
            }
        }
        
        @Override
        public Timeout timeout() {
            return this.timeout;
        }
    }
    
    final class PipeSource implements Source
    {
        final Timeout timeout;
        
        PipeSource() {
            this.timeout = new Timeout();
        }
        
        @Override
        public long read(final Buffer sink, final long byteCount) throws IOException {
            synchronized (Pipe.this.buffer) {
                if (Pipe.this.sourceClosed) {
                    throw new IllegalStateException("closed");
                }
                while (Pipe.this.buffer.size() == 0L) {
                    if (Pipe.this.sinkClosed) {
                        return -1L;
                    }
                    this.timeout.waitUntilNotified(Pipe.this.buffer);
                }
                final long result = Pipe.this.buffer.read(sink, byteCount);
                Pipe.this.buffer.notifyAll();
                return result;
            }
        }
        
        @Override
        public void close() throws IOException {
            synchronized (Pipe.this.buffer) {
                Pipe.this.sourceClosed = true;
                Pipe.this.buffer.notifyAll();
            }
        }
        
        @Override
        public Timeout timeout() {
            return this.timeout;
        }
    }
}
