// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import javax.annotation.Nullable;
import java.net.Socket;

public interface Connection
{
    Route route();
    
    Socket socket();
    
    @Nullable
    Handshake handshake();
    
    Protocol protocol();
}
