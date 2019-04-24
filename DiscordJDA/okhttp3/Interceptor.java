// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import javax.annotation.Nullable;
import java.io.IOException;

public interface Interceptor
{
    Response intercept(final Chain p0) throws IOException;
    
    public interface Chain
    {
        Request request();
        
        Response proceed(final Request p0) throws IOException;
        
        @Nullable
        Connection connection();
    }
}
