// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import javax.annotation.Nullable;
import okio.ByteString;

public interface WebSocket
{
    Request request();
    
    long queueSize();
    
    boolean send(final String p0);
    
    boolean send(final ByteString p0);
    
    boolean close(final int p0, @Nullable final String p1);
    
    void cancel();
    
    public interface Factory
    {
        WebSocket newWebSocket(final Request p0, final WebSocketListener p1);
    }
}
