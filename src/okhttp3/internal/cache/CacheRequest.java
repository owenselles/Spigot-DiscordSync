// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.cache;

import java.io.IOException;
import okio.Sink;

public interface CacheRequest
{
    Sink body() throws IOException;
    
    void abort();
}
