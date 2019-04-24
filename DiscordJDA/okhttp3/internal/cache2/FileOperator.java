// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.cache2;

import java.io.EOFException;
import java.io.IOException;
import okio.Buffer;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

final class FileOperator
{
    private static final int BUFFER_SIZE = 8192;
    private final byte[] byteArray;
    private final ByteBuffer byteBuffer;
    private final FileChannel fileChannel;
    
    FileOperator(final FileChannel fileChannel) {
        this.byteArray = new byte[8192];
        this.byteBuffer = ByteBuffer.wrap(this.byteArray);
        this.fileChannel = fileChannel;
    }
    
    public void write(long pos, final Buffer source, long byteCount) throws IOException {
        if (byteCount < 0L || byteCount > source.size()) {
            throw new IndexOutOfBoundsException();
        }
        while (byteCount > 0L) {
            try {
                final int toWrite = (int)Math.min(8192L, byteCount);
                source.read(this.byteArray, 0, toWrite);
                this.byteBuffer.limit(toWrite);
                do {
                    final int bytesWritten = this.fileChannel.write(this.byteBuffer, pos);
                    pos += bytesWritten;
                } while (this.byteBuffer.hasRemaining());
                byteCount -= toWrite;
            }
            finally {
                this.byteBuffer.clear();
            }
        }
    }
    
    public void read(long pos, final Buffer sink, long byteCount) throws IOException {
        if (byteCount < 0L) {
            throw new IndexOutOfBoundsException();
        }
        while (byteCount > 0L) {
            try {
                this.byteBuffer.limit((int)Math.min(8192L, byteCount));
                if (this.fileChannel.read(this.byteBuffer, pos) == -1) {
                    throw new EOFException();
                }
                final int bytesRead = this.byteBuffer.position();
                sink.write(this.byteArray, 0, bytesRead);
                pos += bytesRead;
                byteCount -= bytesRead;
            }
            finally {
                this.byteBuffer.clear();
            }
        }
    }
}
