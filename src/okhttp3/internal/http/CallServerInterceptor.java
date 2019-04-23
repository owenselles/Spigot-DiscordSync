// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.http;

import java.io.IOException;
import okio.BufferedSink;
import okio.Sink;
import okhttp3.Request;
import okhttp3.internal.connection.StreamAllocation;
import java.net.ProtocolException;
import okhttp3.internal.Util;
import okio.Okio;
import okhttp3.internal.connection.RealConnection;
import okhttp3.Response;
import okhttp3.Interceptor;

public final class CallServerInterceptor implements Interceptor
{
    private final boolean forWebSocket;
    
    public CallServerInterceptor(final boolean forWebSocket) {
        this.forWebSocket = forWebSocket;
    }
    
    @Override
    public Response intercept(final Chain chain) throws IOException {
        final RealInterceptorChain realChain = (RealInterceptorChain)chain;
        final HttpCodec httpCodec = realChain.httpStream();
        final StreamAllocation streamAllocation = realChain.streamAllocation();
        final RealConnection connection = (RealConnection)realChain.connection();
        final Request request = realChain.request();
        final long sentRequestMillis = System.currentTimeMillis();
        httpCodec.writeRequestHeaders(request);
        Response.Builder responseBuilder = null;
        if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
            if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
                httpCodec.flushRequest();
                responseBuilder = httpCodec.readResponseHeaders(true);
            }
            if (responseBuilder == null) {
                final Sink requestBodyOut = httpCodec.createRequestBody(request, request.body().contentLength());
                final BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);
                request.body().writeTo(bufferedRequestBody);
                bufferedRequestBody.close();
            }
            else if (!connection.isMultiplexed()) {
                streamAllocation.noNewStreams();
            }
        }
        httpCodec.finishRequest();
        if (responseBuilder == null) {
            responseBuilder = httpCodec.readResponseHeaders(false);
        }
        Response response = responseBuilder.request(request).handshake(streamAllocation.connection().handshake()).sentRequestAtMillis(sentRequestMillis).receivedResponseAtMillis(System.currentTimeMillis()).build();
        final int code = response.code();
        if (this.forWebSocket && code == 101) {
            response = response.newBuilder().body(Util.EMPTY_RESPONSE).build();
        }
        else {
            response = response.newBuilder().body(httpCodec.openResponseBody(response)).build();
        }
        if ("close".equalsIgnoreCase(response.request().header("Connection")) || "close".equalsIgnoreCase(response.header("Connection"))) {
            streamAllocation.noNewStreams();
        }
        if ((code == 204 || code == 205) && response.body().contentLength() > 0L) {
            throw new ProtocolException("HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
        }
        return response;
    }
}
