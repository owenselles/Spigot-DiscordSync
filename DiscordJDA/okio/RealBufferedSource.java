// 
// Decompiled by Procyon v0.5.30
// 

package okio;

import java.io.InputStream;
import javax.annotation.Nullable;
import java.nio.charset.Charset;
import java.io.EOFException;
import java.io.IOException;

final class RealBufferedSource implements BufferedSource
{
    public final Buffer buffer;
    public final Source source;
    boolean closed;
    
    RealBufferedSource(final Source source) {
        this.buffer = new Buffer();
        if (source == null) {
            throw new NullPointerException("source == null");
        }
        this.source = source;
    }
    
    @Override
    public Buffer buffer() {
        return this.buffer;
    }
    
    @Override
    public long read(final Buffer sink, final long byteCount) throws IOException {
        if (sink == null) {
            throw new IllegalArgumentException("sink == null");
        }
        if (byteCount < 0L) {
            throw new IllegalArgumentException("byteCount < 0: " + byteCount);
        }
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        if (this.buffer.size == 0L) {
            final long read = this.source.read(this.buffer, 8192L);
            if (read == -1L) {
                return -1L;
            }
        }
        final long toRead = Math.min(byteCount, this.buffer.size);
        return this.buffer.read(sink, toRead);
    }
    
    @Override
    public boolean exhausted() throws IOException {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        return this.buffer.exhausted() && this.source.read(this.buffer, 8192L) == -1L;
    }
    
    @Override
    public void require(final long byteCount) throws IOException {
        if (!this.request(byteCount)) {
            throw new EOFException();
        }
    }
    
    @Override
    public boolean request(final long byteCount) throws IOException {
        if (byteCount < 0L) {
            throw new IllegalArgumentException("byteCount < 0: " + byteCount);
        }
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        while (this.buffer.size < byteCount) {
            if (this.source.read(this.buffer, 8192L) == -1L) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public byte readByte() throws IOException {
        this.require(1L);
        return this.buffer.readByte();
    }
    
    @Override
    public ByteString readByteString() throws IOException {
        this.buffer.writeAll(this.source);
        return this.buffer.readByteString();
    }
    
    @Override
    public ByteString readByteString(final long byteCount) throws IOException {
        this.require(byteCount);
        return this.buffer.readByteString(byteCount);
    }
    
    @Override
    public int select(final Options options) throws IOException {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        while (true) {
            final int index = this.buffer.selectPrefix(options);
            if (index == -1) {
                return -1;
            }
            final int selectedSize = options.byteStrings[index].size();
            if (selectedSize <= this.buffer.size) {
                this.buffer.skip(selectedSize);
                return index;
            }
            if (this.source.read(this.buffer, 8192L) == -1L) {
                return -1;
            }
        }
    }
    
    @Override
    public byte[] readByteArray() throws IOException {
        this.buffer.writeAll(this.source);
        return this.buffer.readByteArray();
    }
    
    @Override
    public byte[] readByteArray(final long byteCount) throws IOException {
        this.require(byteCount);
        return this.buffer.readByteArray(byteCount);
    }
    
    @Override
    public int read(final byte[] sink) throws IOException {
        return this.read(sink, 0, sink.length);
    }
    
    @Override
    public void readFully(final byte[] sink) throws IOException {
        try {
            this.require(sink.length);
        }
        catch (EOFException e) {
            int offset = 0;
            while (this.buffer.size > 0L) {
                final int read = this.buffer.read(sink, offset, (int)this.buffer.size);
                if (read == -1) {
                    throw new AssertionError();
                }
                offset += read;
            }
            throw e;
        }
        this.buffer.readFully(sink);
    }
    
    @Override
    public int read(final byte[] sink, final int offset, final int byteCount) throws IOException {
        Util.checkOffsetAndCount(sink.length, offset, byteCount);
        if (this.buffer.size == 0L) {
            final long read = this.source.read(this.buffer, 8192L);
            if (read == -1L) {
                return -1;
            }
        }
        final int toRead = (int)Math.min(byteCount, this.buffer.size);
        return this.buffer.read(sink, offset, toRead);
    }
    
    @Override
    public void readFully(final Buffer sink, final long byteCount) throws IOException {
        try {
            this.require(byteCount);
        }
        catch (EOFException e) {
            sink.writeAll(this.buffer);
            throw e;
        }
        this.buffer.readFully(sink, byteCount);
    }
    
    @Override
    public long readAll(final Sink sink) throws IOException {
        if (sink == null) {
            throw new IllegalArgumentException("sink == null");
        }
        long totalBytesWritten = 0L;
        while (this.source.read(this.buffer, 8192L) != -1L) {
            final long emitByteCount = this.buffer.completeSegmentByteCount();
            if (emitByteCount > 0L) {
                totalBytesWritten += emitByteCount;
                sink.write(this.buffer, emitByteCount);
            }
        }
        if (this.buffer.size() > 0L) {
            totalBytesWritten += this.buffer.size();
            sink.write(this.buffer, this.buffer.size());
        }
        return totalBytesWritten;
    }
    
    @Override
    public String readUtf8() throws IOException {
        this.buffer.writeAll(this.source);
        return this.buffer.readUtf8();
    }
    
    @Override
    public String readUtf8(final long byteCount) throws IOException {
        this.require(byteCount);
        return this.buffer.readUtf8(byteCount);
    }
    
    @Override
    public String readString(final Charset charset) throws IOException {
        if (charset == null) {
            throw new IllegalArgumentException("charset == null");
        }
        this.buffer.writeAll(this.source);
        return this.buffer.readString(charset);
    }
    
    @Override
    public String readString(final long byteCount, final Charset charset) throws IOException {
        this.require(byteCount);
        if (charset == null) {
            throw new IllegalArgumentException("charset == null");
        }
        return this.buffer.readString(byteCount, charset);
    }
    
    @Nullable
    @Override
    public String readUtf8Line() throws IOException {
        final long newline = this.indexOf((byte)10);
        if (newline == -1L) {
            return (this.buffer.size != 0L) ? this.readUtf8(this.buffer.size) : null;
        }
        return this.buffer.readUtf8Line(newline);
    }
    
    @Override
    public String readUtf8LineStrict() throws IOException {
        return this.readUtf8LineStrict(Long.MAX_VALUE);
    }
    
    @Override
    public String readUtf8LineStrict(final long limit) throws IOException {
        if (limit < 0L) {
            throw new IllegalArgumentException("limit < 0: " + limit);
        }
        final long scanLength = (limit == Long.MAX_VALUE) ? Long.MAX_VALUE : (limit + 1L);
        final long newline = this.indexOf((byte)10, 0L, scanLength);
        if (newline != -1L) {
            return this.buffer.readUtf8Line(newline);
        }
        if (scanLength < Long.MAX_VALUE && this.request(scanLength) && this.buffer.getByte(scanLength - 1L) == 13 && this.request(scanLength + 1L) && this.buffer.getByte(scanLength) == 10) {
            return this.buffer.readUtf8Line(scanLength);
        }
        final Buffer data = new Buffer();
        this.buffer.copyTo(data, 0L, Math.min(32L, this.buffer.size()));
        throw new EOFException("\\n not found: limit=" + Math.min(this.buffer.size(), limit) + " content=" + data.readByteString().hex() + '\u2026');
    }
    
    @Override
    public int readUtf8CodePoint() throws IOException {
        this.require(1L);
        final byte b0 = this.buffer.getByte(0L);
        if ((b0 & 0xE0) == 0xC0) {
            this.require(2L);
        }
        else if ((b0 & 0xF0) == 0xE0) {
            this.require(3L);
        }
        else if ((b0 & 0xF8) == 0xF0) {
            this.require(4L);
        }
        return this.buffer.readUtf8CodePoint();
    }
    
    @Override
    public short readShort() throws IOException {
        this.require(2L);
        return this.buffer.readShort();
    }
    
    @Override
    public short readShortLe() throws IOException {
        this.require(2L);
        return this.buffer.readShortLe();
    }
    
    @Override
    public int readInt() throws IOException {
        this.require(4L);
        return this.buffer.readInt();
    }
    
    @Override
    public int readIntLe() throws IOException {
        this.require(4L);
        return this.buffer.readIntLe();
    }
    
    @Override
    public long readLong() throws IOException {
        this.require(8L);
        return this.buffer.readLong();
    }
    
    @Override
    public long readLongLe() throws IOException {
        this.require(8L);
        return this.buffer.readLongLe();
    }
    
    @Override
    public long readDecimalLong() throws IOException {
        this.require(1L);
        int pos = 0;
        while (this.request(pos + 1)) {
            final byte b = this.buffer.getByte(pos);
            if ((b < 48 || b > 57) && (pos != 0 || b != 45)) {
                if (pos == 0) {
                    throw new NumberFormatException(String.format("Expected leading [0-9] or '-' character but was %#x", b));
                }
                break;
            }
            else {
                ++pos;
            }
        }
        return this.buffer.readDecimalLong();
    }
    
    @Override
    public long readHexadecimalUnsignedLong() throws IOException {
        this.require(1L);
        int pos = 0;
        while (this.request(pos + 1)) {
            final byte b = this.buffer.getByte(pos);
            if ((b < 48 || b > 57) && (b < 97 || b > 102) && (b < 65 || b > 70)) {
                if (pos == 0) {
                    throw new NumberFormatException(String.format("Expected leading [0-9a-fA-F] character but was %#x", b));
                }
                break;
            }
            else {
                ++pos;
            }
        }
        return this.buffer.readHexadecimalUnsignedLong();
    }
    
    @Override
    public void skip(long byteCount) throws IOException {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        while (byteCount > 0L) {
            if (this.buffer.size == 0L && this.source.read(this.buffer, 8192L) == -1L) {
                throw new EOFException();
            }
            final long toSkip = Math.min(byteCount, this.buffer.size());
            this.buffer.skip(toSkip);
            byteCount -= toSkip;
        }
    }
    
    @Override
    public long indexOf(final byte b) throws IOException {
        return this.indexOf(b, 0L, Long.MAX_VALUE);
    }
    
    @Override
    public long indexOf(final byte b, final long fromIndex) throws IOException {
        return this.indexOf(b, fromIndex, Long.MAX_VALUE);
    }
    
    @Override
    public long indexOf(final byte b, long fromIndex, final long toIndex) throws IOException {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        if (fromIndex < 0L || toIndex < fromIndex) {
            throw new IllegalArgumentException(String.format("fromIndex=%s toIndex=%s", fromIndex, toIndex));
        }
        while (fromIndex < toIndex) {
            final long result = this.buffer.indexOf(b, fromIndex, toIndex);
            if (result != -1L) {
                return result;
            }
            final long lastBufferSize = this.buffer.size;
            if (lastBufferSize >= toIndex || this.source.read(this.buffer, 8192L) == -1L) {
                return -1L;
            }
            fromIndex = Math.max(fromIndex, lastBufferSize);
        }
        return -1L;
    }
    
    @Override
    public long indexOf(final ByteString bytes) throws IOException {
        return this.indexOf(bytes, 0L);
    }
    
    @Override
    public long indexOf(final ByteString bytes, long fromIndex) throws IOException {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        while (true) {
            final long result = this.buffer.indexOf(bytes, fromIndex);
            if (result != -1L) {
                return result;
            }
            final long lastBufferSize = this.buffer.size;
            if (this.source.read(this.buffer, 8192L) == -1L) {
                return -1L;
            }
            fromIndex = Math.max(fromIndex, lastBufferSize - bytes.size() + 1L);
        }
    }
    
    @Override
    public long indexOfElement(final ByteString targetBytes) throws IOException {
        return this.indexOfElement(targetBytes, 0L);
    }
    
    @Override
    public long indexOfElement(final ByteString targetBytes, long fromIndex) throws IOException {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        while (true) {
            final long result = this.buffer.indexOfElement(targetBytes, fromIndex);
            if (result != -1L) {
                return result;
            }
            final long lastBufferSize = this.buffer.size;
            if (this.source.read(this.buffer, 8192L) == -1L) {
                return -1L;
            }
            fromIndex = Math.max(fromIndex, lastBufferSize);
        }
    }
    
    @Override
    public boolean rangeEquals(final long offset, final ByteString bytes) throws IOException {
        return this.rangeEquals(offset, bytes, 0, bytes.size());
    }
    
    @Override
    public boolean rangeEquals(final long offset, final ByteString bytes, final int bytesOffset, final int byteCount) throws IOException {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        if (offset < 0L || bytesOffset < 0 || byteCount < 0 || bytes.size() - bytesOffset < byteCount) {
            return false;
        }
        for (int i = 0; i < byteCount; ++i) {
            final long bufferOffset = offset + i;
            if (!this.request(bufferOffset + 1L)) {
                return false;
            }
            if (this.buffer.getByte(bufferOffset) != bytes.getByte(bytesOffset + i)) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public InputStream inputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                if (RealBufferedSource.this.closed) {
                    throw new IOException("closed");
                }
                if (RealBufferedSource.this.buffer.size == 0L) {
                    final long count = RealBufferedSource.this.source.read(RealBufferedSource.this.buffer, 8192L);
                    if (count == -1L) {
                        return -1;
                    }
                }
                return RealBufferedSource.this.buffer.readByte() & 0xFF;
            }
            
            @Override
            public int read(final byte[] data, final int offset, final int byteCount) throws IOException {
                if (RealBufferedSource.this.closed) {
                    throw new IOException("closed");
                }
                Util.checkOffsetAndCount(data.length, offset, byteCount);
                if (RealBufferedSource.this.buffer.size == 0L) {
                    final long count = RealBufferedSource.this.source.read(RealBufferedSource.this.buffer, 8192L);
                    if (count == -1L) {
                        return -1;
                    }
                }
                return RealBufferedSource.this.buffer.read(data, offset, byteCount);
            }
            
            @Override
            public int available() throws IOException {
                if (RealBufferedSource.this.closed) {
                    throw new IOException("closed");
                }
                return (int)Math.min(RealBufferedSource.this.buffer.size, 2147483647L);
            }
            
            @Override
            public void close() throws IOException {
                RealBufferedSource.this.close();
            }
            
            @Override
            public String toString() {
                return RealBufferedSource.this + ".inputStream()";
            }
        };
    }
    
    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.source.close();
        this.buffer.clear();
    }
    
    @Override
    public Timeout timeout() {
        return this.source.timeout();
    }
    
    @Override
    public String toString() {
        return "buffer(" + this.source + ")";
    }
}
