// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import okhttp3.internal.NamedRunnable;
import java.util.List;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.http.BridgeInterceptor;
import java.util.Collection;
import java.util.ArrayList;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.platform.Platform;
import java.io.IOException;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;

final class RealCall implements Call
{
    final OkHttpClient client;
    final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;
    final EventListener eventListener;
    final Request originalRequest;
    final boolean forWebSocket;
    private boolean executed;
    
    RealCall(final OkHttpClient client, final Request originalRequest, final boolean forWebSocket) {
        final EventListener.Factory eventListenerFactory = client.eventListenerFactory();
        this.client = client;
        this.originalRequest = originalRequest;
        this.forWebSocket = forWebSocket;
        this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
        this.eventListener = eventListenerFactory.create(this);
    }
    
    @Override
    public Request request() {
        return this.originalRequest;
    }
    
    @Override
    public Response execute() throws IOException {
        synchronized (this) {
            if (this.executed) {
                throw new IllegalStateException("Already Executed");
            }
            this.executed = true;
        }
        this.captureCallStackTrace();
        try {
            this.client.dispatcher().executed(this);
            final Response result = this.getResponseWithInterceptorChain();
            if (result == null) {
                throw new IOException("Canceled");
            }
            return result;
        }
        finally {
            this.client.dispatcher().finished(this);
        }
    }
    
    private void captureCallStackTrace() {
        final Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
        this.retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
    }
    
    @Override
    public void enqueue(final Callback responseCallback) {
        synchronized (this) {
            if (this.executed) {
                throw new IllegalStateException("Already Executed");
            }
            this.executed = true;
        }
        this.captureCallStackTrace();
        this.client.dispatcher().enqueue(new AsyncCall(responseCallback));
    }
    
    @Override
    public void cancel() {
        this.retryAndFollowUpInterceptor.cancel();
    }
    
    @Override
    public synchronized boolean isExecuted() {
        return this.executed;
    }
    
    @Override
    public boolean isCanceled() {
        return this.retryAndFollowUpInterceptor.isCanceled();
    }
    
    @Override
    public RealCall clone() {
        return new RealCall(this.client, this.originalRequest, this.forWebSocket);
    }
    
    StreamAllocation streamAllocation() {
        return this.retryAndFollowUpInterceptor.streamAllocation();
    }
    
    String toLoggableString() {
        return (this.isCanceled() ? "canceled " : "") + (this.forWebSocket ? "web socket" : "call") + " to " + this.redactedUrl();
    }
    
    String redactedUrl() {
        return this.originalRequest.url().redact();
    }
    
    Response getResponseWithInterceptorChain() throws IOException {
        final List<Interceptor> interceptors = new ArrayList<Interceptor>();
        interceptors.addAll(this.client.interceptors());
        interceptors.add(this.retryAndFollowUpInterceptor);
        interceptors.add(new BridgeInterceptor(this.client.cookieJar()));
        interceptors.add(new CacheInterceptor(this.client.internalCache()));
        interceptors.add(new ConnectInterceptor(this.client));
        if (!this.forWebSocket) {
            interceptors.addAll(this.client.networkInterceptors());
        }
        interceptors.add(new CallServerInterceptor(this.forWebSocket));
        final Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0, this.originalRequest);
        return chain.proceed(this.originalRequest);
    }
    
    final class AsyncCall extends NamedRunnable
    {
        private final Callback responseCallback;
        
        AsyncCall(final Callback responseCallback) {
            super("OkHttp %s", new Object[] { RealCall.this.redactedUrl() });
            this.responseCallback = responseCallback;
        }
        
        String host() {
            return RealCall.this.originalRequest.url().host();
        }
        
        Request request() {
            return RealCall.this.originalRequest;
        }
        
        RealCall get() {
            return RealCall.this;
        }
        
        @Override
        protected void execute() {
            boolean signalledCallback = false;
            try {
                final Response response = RealCall.this.getResponseWithInterceptorChain();
                if (RealCall.this.retryAndFollowUpInterceptor.isCanceled()) {
                    signalledCallback = true;
                    this.responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
                }
                else {
                    signalledCallback = true;
                    this.responseCallback.onResponse(RealCall.this, response);
                }
            }
            catch (IOException e) {
                if (signalledCallback) {
                    Platform.get().log(4, "Callback failure for " + RealCall.this.toLoggableString(), e);
                }
                else {
                    this.responseCallback.onFailure(RealCall.this, e);
                }
            }
            finally {
                RealCall.this.client.dispatcher().finished(this);
            }
        }
    }
}
