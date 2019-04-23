// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.cache;

import okhttp3.internal.Internal;
import okhttp3.Headers;
import okio.Sink;
import okhttp3.internal.http.RealResponseBody;
import java.util.concurrent.TimeUnit;
import okio.Timeout;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Source;
import okio.Okio;
import okhttp3.ResponseBody;
import okhttp3.Request;
import java.io.IOException;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.Protocol;
import java.io.Closeable;
import okhttp3.internal.Util;
import okhttp3.Response;
import okhttp3.Interceptor;

public final class CacheInterceptor implements Interceptor
{
    final InternalCache cache;
    
    public CacheInterceptor(final InternalCache cache) {
        this.cache = cache;
    }
    
    @Override
    public Response intercept(final Chain chain) throws IOException {
        final Response cacheCandidate = (this.cache != null) ? this.cache.get(chain.request()) : null;
        final long now = System.currentTimeMillis();
        final CacheStrategy strategy = new CacheStrategy.Factory(now, chain.request(), cacheCandidate).get();
        final Request networkRequest = strategy.networkRequest;
        final Response cacheResponse = strategy.cacheResponse;
        if (this.cache != null) {
            this.cache.trackResponse(strategy);
        }
        if (cacheCandidate != null && cacheResponse == null) {
            Util.closeQuietly(cacheCandidate.body());
        }
        if (networkRequest == null && cacheResponse == null) {
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(504).message("Unsatisfiable Request (only-if-cached)").body(Util.EMPTY_RESPONSE).sentRequestAtMillis(-1L).receivedResponseAtMillis(System.currentTimeMillis()).build();
        }
        if (networkRequest == null) {
            return cacheResponse.newBuilder().cacheResponse(stripBody(cacheResponse)).build();
        }
        Response networkResponse = null;
        try {
            networkResponse = chain.proceed(networkRequest);
        }
        finally {
            if (networkResponse == null && cacheCandidate != null) {
                Util.closeQuietly(cacheCandidate.body());
            }
        }
        if (cacheResponse != null) {
            if (networkResponse.code() == 304) {
                final Response response = cacheResponse.newBuilder().headers(combine(cacheResponse.headers(), networkResponse.headers())).sentRequestAtMillis(networkResponse.sentRequestAtMillis()).receivedResponseAtMillis(networkResponse.receivedResponseAtMillis()).cacheResponse(stripBody(cacheResponse)).networkResponse(stripBody(networkResponse)).build();
                networkResponse.body().close();
                this.cache.trackConditionalCacheHit();
                this.cache.update(cacheResponse, response);
                return response;
            }
            Util.closeQuietly(cacheResponse.body());
        }
        final Response response = networkResponse.newBuilder().cacheResponse(stripBody(cacheResponse)).networkResponse(stripBody(networkResponse)).build();
        if (this.cache != null) {
            if (HttpHeaders.hasBody(response) && CacheStrategy.isCacheable(response, networkRequest)) {
                final CacheRequest cacheRequest = this.cache.put(response);
                return this.cacheWritingResponse(cacheRequest, response);
            }
            if (HttpMethod.invalidatesCache(networkRequest.method())) {
                try {
                    this.cache.remove(networkRequest);
                }
                catch (IOException ex) {}
            }
        }
        return response;
    }
    
    private static Response stripBody(final Response response) {
        return (response != null && response.body() != null) ? response.newBuilder().body(null).build() : response;
    }
    
    private Response cacheWritingResponse(final CacheRequest cacheRequest, final Response response) throws IOException {
        if (cacheRequest == null) {
            return response;
        }
        final Sink cacheBodyUnbuffered = cacheRequest.body();
        if (cacheBodyUnbuffered == null) {
            return response;
        }
        final BufferedSource source = response.body().source();
        final BufferedSink cacheBody = Okio.buffer(cacheBodyUnbuffered);
        final Source cacheWritingSource = new Source() {
            boolean cacheRequestClosed;
            
            @Override
            public long read(final Buffer sink, final long byteCount) throws IOException {
                long bytesRead;
                try {
                    bytesRead = source.read(sink, byteCount);
                }
                catch (IOException e) {
                    if (!this.cacheRequestClosed) {
                        this.cacheRequestClosed = true;
                        cacheRequest.abort();
                    }
                    throw e;
                }
                if (bytesRead == -1L) {
                    if (!this.cacheRequestClosed) {
                        this.cacheRequestClosed = true;
                        cacheBody.close();
                    }
                    return -1L;
                }
                sink.copyTo(cacheBody.buffer(), sink.size() - bytesRead, bytesRead);
                cacheBody.emitCompleteSegments();
                return bytesRead;
            }
            
            @Override
            public Timeout timeout() {
                return source.timeout();
            }
            
            @Override
            public void close() throws IOException {
                if (!this.cacheRequestClosed && !Util.discard(this, 100, TimeUnit.MILLISECONDS)) {
                    this.cacheRequestClosed = true;
                    cacheRequest.abort();
                }
                source.close();
            }
        };
        return response.newBuilder().body(new RealResponseBody(response.headers(), Okio.buffer(cacheWritingSource))).build();
    }
    
    private static Headers combine(final Headers cachedHeaders, final Headers networkHeaders) {
        final Headers.Builder result = new Headers.Builder();
        for (int i = 0, size = cachedHeaders.size(); i < size; ++i) {
            final String fieldName = cachedHeaders.name(i);
            final String value = cachedHeaders.value(i);
            if (!"Warning".equalsIgnoreCase(fieldName) || !value.startsWith("1")) {
                if (!isEndToEnd(fieldName) || networkHeaders.get(fieldName) == null) {
                    Internal.instance.addLenient(result, fieldName, value);
                }
            }
        }
        for (int i = 0, size = networkHeaders.size(); i < size; ++i) {
            final String fieldName = networkHeaders.name(i);
            if (!"Content-Length".equalsIgnoreCase(fieldName)) {
                if (isEndToEnd(fieldName)) {
                    Internal.instance.addLenient(result, fieldName, networkHeaders.value(i));
                }
            }
        }
        return result.build();
    }
    
    static boolean isEndToEnd(final String fieldName) {
        return !"Connection".equalsIgnoreCase(fieldName) && !"Keep-Alive".equalsIgnoreCase(fieldName) && !"Proxy-Authenticate".equalsIgnoreCase(fieldName) && !"Proxy-Authorization".equalsIgnoreCase(fieldName) && !"TE".equalsIgnoreCase(fieldName) && !"Trailers".equalsIgnoreCase(fieldName) && !"Transfer-Encoding".equalsIgnoreCase(fieldName) && !"Upgrade".equalsIgnoreCase(fieldName);
    }
}
