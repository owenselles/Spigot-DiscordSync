// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.io.InputStreamReader;
import okio.Buffer;
import java.nio.charset.Charset;
import okhttp3.internal.Util;
import java.io.IOException;
import okio.BufferedSource;
import java.io.InputStream;
import javax.annotation.Nullable;
import java.io.Reader;
import java.io.Closeable;

public abstract class ResponseBody implements Closeable
{
    private Reader reader;
    
    @Nullable
    public abstract MediaType contentType();
    
    public abstract long contentLength();
    
    public final InputStream byteStream() {
        return this.source().inputStream();
    }
    
    public abstract BufferedSource source();
    
    public final byte[] bytes() throws IOException {
        final long contentLength = this.contentLength();
        if (contentLength > 2147483647L) {
            throw new IOException("Cannot buffer entire body for content length: " + contentLength);
        }
        final BufferedSource source = this.source();
        byte[] bytes;
        try {
            bytes = source.readByteArray();
        }
        finally {
            Util.closeQuietly(source);
        }
        if (contentLength != -1L && contentLength != bytes.length) {
            throw new IOException("Content-Length (" + contentLength + ") and stream length (" + bytes.length + ") disagree");
        }
        return bytes;
    }
    
    public final Reader charStream() {
        final Reader r = this.reader;
        return (r != null) ? r : (this.reader = new BomAwareReader(this.source(), this.charset()));
    }
    
    public final String string() throws IOException {
        final BufferedSource source = this.source();
        try {
            final Charset charset = Util.bomAwareCharset(source, this.charset());
            return source.readString(charset);
        }
        finally {
            Util.closeQuietly(source);
        }
    }
    
    private Charset charset() {
        final MediaType contentType = this.contentType();
        return (contentType != null) ? contentType.charset(Util.UTF_8) : Util.UTF_8;
    }
    
    @Override
    public void close() {
        Util.closeQuietly(this.source());
    }
    
    public static ResponseBody create(@Nullable MediaType contentType, final String content) {
        Charset charset = Util.UTF_8;
        if (contentType != null) {
            charset = contentType.charset();
            if (charset == null) {
                charset = Util.UTF_8;
                contentType = MediaType.parse(contentType + "; charset=utf-8");
            }
        }
        final Buffer buffer = new Buffer().writeString(content, charset);
        return create(contentType, buffer.size(), buffer);
    }
    
    public static ResponseBody create(@Nullable final MediaType contentType, final byte[] content) {
        final Buffer buffer = new Buffer().write(content);
        return create(contentType, content.length, buffer);
    }
    
    public static ResponseBody create(@Nullable final MediaType contentType, final long contentLength, final BufferedSource content) {
        if (content == null) {
            throw new NullPointerException("source == null");
        }
        return new ResponseBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return contentType;
            }
            
            @Override
            public long contentLength() {
                return contentLength;
            }
            
            @Override
            public BufferedSource source() {
                return content;
            }
        };
    }
    
    static final class BomAwareReader extends Reader
    {
        private final BufferedSource source;
        private final Charset charset;
        private boolean closed;
        private Reader delegate;
        
        BomAwareReader(final BufferedSource source, final Charset charset) {
            this.source = source;
            this.charset = charset;
        }
        
        @Override
        public int read(final char[] cbuf, final int off, final int len) throws IOException {
            if (this.closed) {
                throw new IOException("Stream closed");
            }
            Reader delegate = this.delegate;
            if (delegate == null) {
                final Charset charset = Util.bomAwareCharset(this.source, this.charset);
                final InputStreamReader delegate2 = new InputStreamReader(this.source.inputStream(), charset);
                this.delegate = delegate2;
                delegate = delegate2;
            }
            return delegate.read(cbuf, off, len);
        }
        
        @Override
        public void close() throws IOException {
            this.closed = true;
            if (this.delegate != null) {
                this.delegate.close();
            }
            else {
                this.source.close();
            }
        }
    }
}
