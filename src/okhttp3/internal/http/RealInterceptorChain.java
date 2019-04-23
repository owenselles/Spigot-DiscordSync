// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.http;

import java.io.IOException;
import okhttp3.Response;
import okhttp3.Connection;
import okhttp3.Request;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import java.util.List;
import okhttp3.Interceptor;

public final class RealInterceptorChain implements Interceptor.Chain
{
    private final List<Interceptor> interceptors;
    private final StreamAllocation streamAllocation;
    private final HttpCodec httpCodec;
    private final RealConnection connection;
    private final int index;
    private final Request request;
    private int calls;
    
    public RealInterceptorChain(final List<Interceptor> interceptors, final StreamAllocation streamAllocation, final HttpCodec httpCodec, final RealConnection connection, final int index, final Request request) {
        this.interceptors = interceptors;
        this.connection = connection;
        this.streamAllocation = streamAllocation;
        this.httpCodec = httpCodec;
        this.index = index;
        this.request = request;
    }
    
    @Override
    public Connection connection() {
        return this.connection;
    }
    
    public StreamAllocation streamAllocation() {
        return this.streamAllocation;
    }
    
    public HttpCodec httpStream() {
        return this.httpCodec;
    }
    
    @Override
    public Request request() {
        return this.request;
    }
    
    @Override
    public Response proceed(final Request request) throws IOException {
        return this.proceed(request, this.streamAllocation, this.httpCodec, this.connection);
    }
    
    public Response proceed(final Request request, final StreamAllocation streamAllocation, final HttpCodec httpCodec, final RealConnection connection) throws IOException {
        if (this.index >= this.interceptors.size()) {
            throw new AssertionError();
        }
        ++this.calls;
        if (this.httpCodec != null && !this.connection.supportsUrl(request.url())) {
            throw new IllegalStateException("network interceptor " + this.interceptors.get(this.index - 1) + " must retain the same host and port");
        }
        if (this.httpCodec != null && this.calls > 1) {
            throw new IllegalStateException("network interceptor " + this.interceptors.get(this.index - 1) + " must call proceed() exactly once");
        }
        final RealInterceptorChain next = new RealInterceptorChain(this.interceptors, streamAllocation, httpCodec, connection, this.index + 1, request);
        final Interceptor interceptor = this.interceptors.get(this.index);
        final Response response = interceptor.intercept(next);
        if (httpCodec != null && this.index + 1 < this.interceptors.size() && next.calls != 1) {
            throw new IllegalStateException("network interceptor " + interceptor + " must call proceed() exactly once");
        }
        if (response == null) {
            throw new NullPointerException("interceptor " + interceptor + " returned null");
        }
        return response;
    }
}
