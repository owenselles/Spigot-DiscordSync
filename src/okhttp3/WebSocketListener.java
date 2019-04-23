// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import okio.ByteString;

public abstract class WebSocketListener
{
    public void onOpen(final WebSocket webSocket, final Response response) {
    }
    
    public void onMessage(final WebSocket webSocket, final String text) {
    }
    
    public void onMessage(final WebSocket webSocket, final ByteString bytes) {
    }
    
    public void onClosing(final WebSocket webSocket, final int code, final String reason) {
    }
    
    public void onClosed(final WebSocket webSocket, final int code, final String reason) {
    }
    
    public void onFailure(final WebSocket webSocket, final Throwable t, final Response response) {
    }
}
