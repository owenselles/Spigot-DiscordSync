// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.io.IOException;

public interface Call extends Cloneable
{
    Request request();
    
    Response execute() throws IOException;
    
    void enqueue(final Callback p0);
    
    void cancel();
    
    boolean isExecuted();
    
    boolean isCanceled();
    
    Call clone();
    
    public interface Factory
    {
        Call newCall(final Request p0);
    }
}
