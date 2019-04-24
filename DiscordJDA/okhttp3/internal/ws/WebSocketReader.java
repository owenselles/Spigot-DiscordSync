// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.ws;

import okio.ByteString;
import java.io.EOFException;
import okio.Buffer;
import java.net.ProtocolException;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import okio.BufferedSource;

final class WebSocketReader
{
    final boolean isClient;
    final BufferedSource source;
    final FrameCallback frameCallback;
    boolean closed;
    int opcode;
    long frameLength;
    long frameBytesRead;
    boolean isFinalFrame;
    boolean isControlFrame;
    boolean isMasked;
    final byte[] maskKey;
    final byte[] maskBuffer;
    
    WebSocketReader(final boolean isClient, final BufferedSource source, final FrameCallback frameCallback) {
        this.maskKey = new byte[4];
        this.maskBuffer = new byte[8192];
        if (source == null) {
            throw new NullPointerException("source == null");
        }
        if (frameCallback == null) {
            throw new NullPointerException("frameCallback == null");
        }
        this.isClient = isClient;
        this.source = source;
        this.frameCallback = frameCallback;
    }
    
    void processNextFrame() throws IOException {
        this.readHeader();
        if (this.isControlFrame) {
            this.readControlFrame();
        }
        else {
            this.readMessageFrame();
        }
    }
    
    private void readHeader() throws IOException {
        if (this.closed) {
            throw new IOException("closed");
        }
        final long timeoutBefore = this.source.timeout().timeoutNanos();
        this.source.timeout().clearTimeout();
        int b0;
        try {
            b0 = (this.source.readByte() & 0xFF);
        }
        finally {
            this.source.timeout().timeout(timeoutBefore, TimeUnit.NANOSECONDS);
        }
        this.opcode = (b0 & 0xF);
        this.isFinalFrame = ((b0 & 0x80) != 0x0);
        this.isControlFrame = ((b0 & 0x8) != 0x0);
        if (this.isControlFrame && !this.isFinalFrame) {
            throw new ProtocolException("Control frames must be final.");
        }
        final boolean reservedFlag1 = (b0 & 0x40) != 0x0;
        final boolean reservedFlag2 = (b0 & 0x20) != 0x0;
        final boolean reservedFlag3 = (b0 & 0x10) != 0x0;
        if (reservedFlag1 || reservedFlag2 || reservedFlag3) {
            throw new ProtocolException("Reserved flags are unsupported.");
        }
        final int b2 = this.source.readByte() & 0xFF;
        this.isMasked = ((b2 & 0x80) != 0x0);
        if (this.isMasked == this.isClient) {
            throw new ProtocolException(this.isClient ? "Server-sent frames must not be masked." : "Client-sent frames must be masked.");
        }
        this.frameLength = (b2 & 0x7F);
        if (this.frameLength == 126L) {
            this.frameLength = (this.source.readShort() & 0xFFFFL);
        }
        else if (this.frameLength == 127L) {
            this.frameLength = this.source.readLong();
            if (this.frameLength < 0L) {
                throw new ProtocolException("Frame length 0x" + Long.toHexString(this.frameLength) + " > 0x7FFFFFFFFFFFFFFF");
            }
        }
        this.frameBytesRead = 0L;
        if (this.isControlFrame && this.frameLength > 125L) {
            throw new ProtocolException("Control frame must be less than 125B.");
        }
        if (this.isMasked) {
            this.source.readFully(this.maskKey);
        }
    }
    
    private void readControlFrame() throws IOException {
        final Buffer buffer = new Buffer();
        if (this.frameBytesRead < this.frameLength) {
            if (this.isClient) {
                this.source.readFully(buffer, this.frameLength);
            }
            else {
                while (this.frameBytesRead < this.frameLength) {
                    final int toRead = (int)Math.min(this.frameLength - this.frameBytesRead, this.maskBuffer.length);
                    final int read = this.source.read(this.maskBuffer, 0, toRead);
                    if (read == -1) {
                        throw new EOFException();
                    }
                    WebSocketProtocol.toggleMask(this.maskBuffer, read, this.maskKey, this.frameBytesRead);
                    buffer.write(this.maskBuffer, 0, read);
                    this.frameBytesRead += read;
                }
            }
        }
        switch (this.opcode) {
            case 9: {
                this.frameCallback.onReadPing(buffer.readByteString());
                break;
            }
            case 10: {
                this.frameCallback.onReadPong(buffer.readByteString());
                break;
            }
            case 8: {
                int code = 1005;
                String reason = "";
                final long bufferSize = buffer.size();
                if (bufferSize == 1L) {
                    throw new ProtocolException("Malformed close payload length of 1.");
                }
                if (bufferSize != 0L) {
                    code = buffer.readShort();
                    reason = buffer.readUtf8();
                    final String codeExceptionMessage = WebSocketProtocol.closeCodeExceptionMessage(code);
                    if (codeExceptionMessage != null) {
                        throw new ProtocolException(codeExceptionMessage);
                    }
                }
                this.frameCallback.onReadClose(code, reason);
                this.closed = true;
                break;
            }
            default: {
                throw new ProtocolException("Unknown control opcode: " + Integer.toHexString(this.opcode));
            }
        }
    }
    
    private void readMessageFrame() throws IOException {
        final int opcode = this.opcode;
        if (opcode != 1 && opcode != 2) {
            throw new ProtocolException("Unknown opcode: " + Integer.toHexString(opcode));
        }
        final Buffer message = new Buffer();
        this.readMessage(message);
        if (opcode == 1) {
            this.frameCallback.onReadMessage(message.readUtf8());
        }
        else {
            this.frameCallback.onReadMessage(message.readByteString());
        }
    }
    
    void readUntilNonControlFrame() throws IOException {
        while (!this.closed) {
            this.readHeader();
            if (!this.isControlFrame) {
                break;
            }
            this.readControlFrame();
        }
    }
    
    private void readMessage(final Buffer sink) throws IOException {
        while (!this.closed) {
            if (this.frameBytesRead == this.frameLength) {
                if (this.isFinalFrame) {
                    return;
                }
                this.readUntilNonControlFrame();
                if (this.opcode != 0) {
                    throw new ProtocolException("Expected continuation opcode. Got: " + Integer.toHexString(this.opcode));
                }
                if (this.isFinalFrame && this.frameLength == 0L) {
                    return;
                }
            }
            long toRead = this.frameLength - this.frameBytesRead;
            long read;
            if (this.isMasked) {
                toRead = Math.min(toRead, this.maskBuffer.length);
                read = this.source.read(this.maskBuffer, 0, (int)toRead);
                if (read == -1L) {
                    throw new EOFException();
                }
                WebSocketProtocol.toggleMask(this.maskBuffer, read, this.maskKey, this.frameBytesRead);
                sink.write(this.maskBuffer, 0, (int)read);
            }
            else {
                read = this.source.read(sink, toRead);
                if (read == -1L) {
                    throw new EOFException();
                }
            }
            this.frameBytesRead += read;
        }
        throw new IOException("closed");
    }
    
    public interface FrameCallback
    {
        void onReadMessage(final String p0) throws IOException;
        
        void onReadMessage(final ByteString p0) throws IOException;
        
        void onReadPing(final ByteString p0);
        
        void onReadPong(final ByteString p0);
        
        void onReadClose(final int p0, final String p1);
    }
}
