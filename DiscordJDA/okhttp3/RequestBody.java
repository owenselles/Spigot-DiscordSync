// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import okio.Source;
import java.io.Closeable;
import okio.Okio;
import java.io.File;
import okio.ByteString;
import java.nio.charset.Charset;
import okhttp3.internal.Util;
import okio.BufferedSink;
import java.io.IOException;
import javax.annotation.Nullable;

public abstract class RequestBody
{
    @Nullable
    public abstract MediaType contentType();
    
    public long contentLength() throws IOException {
        return -1L;
    }
    
    public abstract void writeTo(final BufferedSink p0) throws IOException;
    
    public static RequestBody create(@Nullable MediaType contentType, final String content) {
        Charset charset = Util.UTF_8;
        if (contentType != null) {
            charset = contentType.charset();
            if (charset == null) {
                charset = Util.UTF_8;
                contentType = MediaType.parse(contentType + "; charset=utf-8");
            }
        }
        final byte[] bytes = content.getBytes(charset);
        return create(contentType, bytes);
    }
    
    public static RequestBody create(@Nullable final MediaType contentType, final ByteString content) {
        return new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return contentType;
            }
            
            @Override
            public long contentLength() throws IOException {
                return content.size();
            }
            
            @Override
            public void writeTo(final BufferedSink sink) throws IOException {
                sink.write(content);
            }
        };
    }
    
    public static RequestBody create(@Nullable final MediaType contentType, final byte[] content) {
        return create(contentType, content, 0, content.length);
    }
    
    public static RequestBody create(@Nullable final MediaType contentType, final byte[] content, final int offset, final int byteCount) {
        if (content == null) {
            throw new NullPointerException("content == null");
        }
        Util.checkOffsetAndCount(content.length, offset, byteCount);
        return new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return contentType;
            }
            
            @Override
            public long contentLength() {
                return byteCount;
            }
            
            @Override
            public void writeTo(final BufferedSink sink) throws IOException {
                sink.write(content, offset, byteCount);
            }
        };
    }
    
    public static RequestBody create(@Nullable final MediaType contentType, final File file) {
        if (file == null) {
            throw new NullPointerException("content == null");
        }
        return new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return contentType;
            }
            
            @Override
            public long contentLength() {
                return file.length();
            }
            
            @Override
            public void writeTo(final BufferedSink sink) throws IOException {
                Source source = null;
                try {
                    source = Okio.source(file);
                    sink.writeAll(source);
                }
                finally {
                    Util.closeQuietly(source);
                }
            }
        };
    }
}
