// 
// Decompiled by Procyon v0.5.30
// 

package okio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.nio.charset.Charset;

final class SegmentedByteString extends ByteString
{
    final transient byte[][] segments;
    final transient int[] directory;
    
    SegmentedByteString(final Buffer buffer, final int byteCount) {
        super(null);
        Util.checkOffsetAndCount(buffer.size, 0L, byteCount);
        int offset = 0;
        int segmentCount = 0;
        for (Segment s = buffer.head; offset < byteCount; offset += s.limit - s.pos, ++segmentCount, s = s.next) {
            if (s.limit == s.pos) {
                throw new AssertionError((Object)"s.limit == s.pos");
            }
        }
        this.segments = new byte[segmentCount][];
        this.directory = new int[segmentCount * 2];
        offset = 0;
        segmentCount = 0;
        Segment s = buffer.head;
        while (offset < byteCount) {
            this.segments[segmentCount] = s.data;
            offset += s.limit - s.pos;
            if (offset > byteCount) {
                offset = byteCount;
            }
            this.directory[segmentCount] = offset;
            this.directory[segmentCount + this.segments.length] = s.pos;
            s.shared = true;
            ++segmentCount;
            s = s.next;
        }
    }
    
    @Override
    public String utf8() {
        return this.toByteString().utf8();
    }
    
    @Override
    public String string(final Charset charset) {
        return this.toByteString().string(charset);
    }
    
    @Override
    public String base64() {
        return this.toByteString().base64();
    }
    
    @Override
    public String hex() {
        return this.toByteString().hex();
    }
    
    @Override
    public ByteString toAsciiLowercase() {
        return this.toByteString().toAsciiLowercase();
    }
    
    @Override
    public ByteString toAsciiUppercase() {
        return this.toByteString().toAsciiUppercase();
    }
    
    @Override
    public ByteString md5() {
        return this.toByteString().md5();
    }
    
    @Override
    public ByteString sha1() {
        return this.toByteString().sha1();
    }
    
    @Override
    public ByteString sha256() {
        return this.toByteString().sha256();
    }
    
    @Override
    public ByteString hmacSha1(final ByteString key) {
        return this.toByteString().hmacSha1(key);
    }
    
    @Override
    public ByteString hmacSha256(final ByteString key) {
        return this.toByteString().hmacSha256(key);
    }
    
    @Override
    public String base64Url() {
        return this.toByteString().base64Url();
    }
    
    @Override
    public ByteString substring(final int beginIndex) {
        return this.toByteString().substring(beginIndex);
    }
    
    @Override
    public ByteString substring(final int beginIndex, final int endIndex) {
        return this.toByteString().substring(beginIndex, endIndex);
    }
    
    @Override
    public byte getByte(final int pos) {
        Util.checkOffsetAndCount(this.directory[this.segments.length - 1], pos, 1L);
        final int segment = this.segment(pos);
        final int segmentOffset = (segment == 0) ? 0 : this.directory[segment - 1];
        final int segmentPos = this.directory[segment + this.segments.length];
        return this.segments[segment][pos - segmentOffset + segmentPos];
    }
    
    private int segment(final int pos) {
        final int i = Arrays.binarySearch(this.directory, 0, this.segments.length, pos + 1);
        return (i >= 0) ? i : (~i);
    }
    
    @Override
    public int size() {
        return this.directory[this.segments.length - 1];
    }
    
    @Override
    public byte[] toByteArray() {
        final byte[] result = new byte[this.directory[this.segments.length - 1]];
        int segmentOffset = 0;
        for (int s = 0, segmentCount = this.segments.length; s < segmentCount; ++s) {
            final int segmentPos = this.directory[segmentCount + s];
            final int nextSegmentOffset = this.directory[s];
            System.arraycopy(this.segments[s], segmentPos, result, segmentOffset, nextSegmentOffset - segmentOffset);
            segmentOffset = nextSegmentOffset;
        }
        return result;
    }
    
    @Override
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(this.toByteArray()).asReadOnlyBuffer();
    }
    
    @Override
    public void write(final OutputStream out) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("out == null");
        }
        int segmentOffset = 0;
        for (int s = 0, segmentCount = this.segments.length; s < segmentCount; ++s) {
            final int segmentPos = this.directory[segmentCount + s];
            final int nextSegmentOffset = this.directory[s];
            out.write(this.segments[s], segmentPos, nextSegmentOffset - segmentOffset);
            segmentOffset = nextSegmentOffset;
        }
    }
    
    @Override
    void write(final Buffer buffer) {
        int segmentOffset = 0;
        for (int s = 0, segmentCount = this.segments.length; s < segmentCount; ++s) {
            final int segmentPos = this.directory[segmentCount + s];
            final int nextSegmentOffset = this.directory[s];
            final Segment segment = new Segment(this.segments[s], segmentPos, segmentPos + nextSegmentOffset - segmentOffset);
            if (buffer.head == null) {
                final Segment segment2 = segment;
                final Segment segment3 = segment;
                final Segment head = segment;
                segment3.prev = head;
                segment2.next = head;
                buffer.head = head;
            }
            else {
                buffer.head.prev.push(segment);
            }
            segmentOffset = nextSegmentOffset;
        }
        buffer.size += segmentOffset;
    }
    
    @Override
    public boolean rangeEquals(int offset, final ByteString other, int otherOffset, int byteCount) {
        if (offset < 0 || offset > this.size() - byteCount) {
            return false;
        }
        int stepSize;
        for (int s = this.segment(offset); byteCount > 0; byteCount -= stepSize, ++s) {
            final int segmentOffset = (s == 0) ? 0 : this.directory[s - 1];
            final int segmentSize = this.directory[s] - segmentOffset;
            stepSize = Math.min(byteCount, segmentOffset + segmentSize - offset);
            final int segmentPos = this.directory[this.segments.length + s];
            final int arrayOffset = offset - segmentOffset + segmentPos;
            if (!other.rangeEquals(otherOffset, this.segments[s], arrayOffset, stepSize)) {
                return false;
            }
            offset += stepSize;
            otherOffset += stepSize;
        }
        return true;
    }
    
    @Override
    public boolean rangeEquals(int offset, final byte[] other, int otherOffset, int byteCount) {
        if (offset < 0 || offset > this.size() - byteCount || otherOffset < 0 || otherOffset > other.length - byteCount) {
            return false;
        }
        int stepSize;
        for (int s = this.segment(offset); byteCount > 0; byteCount -= stepSize, ++s) {
            final int segmentOffset = (s == 0) ? 0 : this.directory[s - 1];
            final int segmentSize = this.directory[s] - segmentOffset;
            stepSize = Math.min(byteCount, segmentOffset + segmentSize - offset);
            final int segmentPos = this.directory[this.segments.length + s];
            final int arrayOffset = offset - segmentOffset + segmentPos;
            if (!Util.arrayRangeEquals(this.segments[s], arrayOffset, other, otherOffset, stepSize)) {
                return false;
            }
            offset += stepSize;
            otherOffset += stepSize;
        }
        return true;
    }
    
    @Override
    public int indexOf(final byte[] other, final int fromIndex) {
        return this.toByteString().indexOf(other, fromIndex);
    }
    
    @Override
    public int lastIndexOf(final byte[] other, final int fromIndex) {
        return this.toByteString().lastIndexOf(other, fromIndex);
    }
    
    private ByteString toByteString() {
        return new ByteString(this.toByteArray());
    }
    
    @Override
    byte[] internalArray() {
        return this.toByteArray();
    }
    
    @Override
    public boolean equals(final Object o) {
        return o == this || (o instanceof ByteString && ((ByteString)o).size() == this.size() && this.rangeEquals(0, (ByteString)o, 0, this.size()));
    }
    
    @Override
    public int hashCode() {
        int result = this.hashCode;
        if (result != 0) {
            return result;
        }
        result = 1;
        int segmentOffset = 0;
        for (int s = 0, segmentCount = this.segments.length; s < segmentCount; ++s) {
            final byte[] segment = this.segments[s];
            final int segmentPos = this.directory[segmentCount + s];
            final int nextSegmentOffset = this.directory[s];
            final int segmentSize = nextSegmentOffset - segmentOffset;
            for (int i = segmentPos, limit = segmentPos + segmentSize; i < limit; ++i) {
                result = 31 * result + segment[i];
            }
            segmentOffset = nextSegmentOffset;
        }
        return this.hashCode = result;
    }
    
    @Override
    public String toString() {
        return this.toByteString().toString();
    }
    
    private Object writeReplace() {
        return this.toByteString();
    }
}
