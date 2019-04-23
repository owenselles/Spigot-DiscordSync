// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.connection;

import java.io.IOException;
import okhttp3.internal.http.HttpCodec;
import okhttp3.Request;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.Response;
import okhttp3.OkHttpClient;
import okhttp3.Interceptor;

public final class ConnectInterceptor implements Interceptor
{
    public final OkHttpClient client;
    
    public ConnectInterceptor(final OkHttpClient client) {
        this.client = client;
    }
    
    @Override
    public Response intercept(final Chain chain) throws IOException {
        final RealInterceptorChain realChain = (RealInterceptorChain)chain;
        final Request request = realChain.request();
        final StreamAllocation streamAllocation = realChain.streamAllocation();
        final boolean doExtensiveHealthChecks = !request.method().equals("GET");
        final HttpCodec httpCodec = streamAllocation.newStream(this.client, doExtensiveHealthChecks);
        final RealConnection connection = streamAllocation.connection();
        return realChain.proceed(request, streamAllocation, httpCodec, connection);
    }
}
