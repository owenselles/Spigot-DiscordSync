// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.http;

import okhttp3.Route;
import okhttp3.Connection;
import okhttp3.RequestBody;
import java.net.Proxy;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
import java.net.SocketTimeoutException;
import java.io.InterruptedIOException;
import okhttp3.CertificatePinner;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.HttpUrl;
import okhttp3.Request;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.io.Closeable;
import okhttp3.internal.Util;
import okhttp3.ResponseBody;
import java.io.IOException;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.RealConnection;
import okhttp3.Response;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.OkHttpClient;
import okhttp3.Interceptor;

public final class RetryAndFollowUpInterceptor implements Interceptor
{
    private static final int MAX_FOLLOW_UPS = 20;
    private final OkHttpClient client;
    private final boolean forWebSocket;
    private StreamAllocation streamAllocation;
    private Object callStackTrace;
    private volatile boolean canceled;
    
    public RetryAndFollowUpInterceptor(final OkHttpClient client, final boolean forWebSocket) {
        this.client = client;
        this.forWebSocket = forWebSocket;
    }
    
    public void cancel() {
        this.canceled = true;
        final StreamAllocation streamAllocation = this.streamAllocation;
        if (streamAllocation != null) {
            streamAllocation.cancel();
        }
    }
    
    public boolean isCanceled() {
        return this.canceled;
    }
    
    public void setCallStackTrace(final Object callStackTrace) {
        this.callStackTrace = callStackTrace;
    }
    
    public StreamAllocation streamAllocation() {
        return this.streamAllocation;
    }
    
    @Override
    public Response intercept(final Chain chain) throws IOException {
        Request request = chain.request();
        this.streamAllocation = new StreamAllocation(this.client.connectionPool(), this.createAddress(request.url()), this.callStackTrace);
        int followUpCount = 0;
        Response priorResponse = null;
        while (!this.canceled) {
            Response response = null;
            boolean releaseConnection = true;
            try {
                response = ((RealInterceptorChain)chain).proceed(request, this.streamAllocation, null, null);
                releaseConnection = false;
            }
            catch (RouteException e) {
                if (!this.recover(e.getLastConnectException(), false, request)) {
                    throw e.getLastConnectException();
                }
                releaseConnection = false;
                continue;
            }
            catch (IOException e2) {
                final boolean requestSendStarted = !(e2 instanceof ConnectionShutdownException);
                if (!this.recover(e2, requestSendStarted, request)) {
                    throw e2;
                }
                releaseConnection = false;
                continue;
            }
            finally {
                if (releaseConnection) {
                    this.streamAllocation.streamFailed(null);
                    this.streamAllocation.release();
                }
            }
            if (priorResponse != null) {
                response = response.newBuilder().priorResponse(priorResponse.newBuilder().body(null).build()).build();
            }
            final Request followUp = this.followUpRequest(response);
            if (followUp == null) {
                if (!this.forWebSocket) {
                    this.streamAllocation.release();
                }
                return response;
            }
            Util.closeQuietly(response.body());
            if (++followUpCount > 20) {
                this.streamAllocation.release();
                throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            }
            if (followUp.body() instanceof UnrepeatableRequestBody) {
                this.streamAllocation.release();
                throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
            }
            if (!this.sameConnection(response, followUp.url())) {
                this.streamAllocation.release();
                this.streamAllocation = new StreamAllocation(this.client.connectionPool(), this.createAddress(followUp.url()), this.callStackTrace);
            }
            else if (this.streamAllocation.codec() != null) {
                throw new IllegalStateException("Closing the body of " + response + " didn't close its backing stream. Bad interceptor?");
            }
            request = followUp;
            priorResponse = response;
        }
        this.streamAllocation.release();
        throw new IOException("Canceled");
    }
    
    private Address createAddress(final HttpUrl url) {
        SSLSocketFactory sslSocketFactory = null;
        HostnameVerifier hostnameVerifier = null;
        CertificatePinner certificatePinner = null;
        if (url.isHttps()) {
            sslSocketFactory = this.client.sslSocketFactory();
            hostnameVerifier = this.client.hostnameVerifier();
            certificatePinner = this.client.certificatePinner();
        }
        return new Address(url.host(), url.port(), this.client.dns(), this.client.socketFactory(), sslSocketFactory, hostnameVerifier, certificatePinner, this.client.proxyAuthenticator(), this.client.proxy(), this.client.protocols(), this.client.connectionSpecs(), this.client.proxySelector());
    }
    
    private boolean recover(final IOException e, final boolean requestSendStarted, final Request userRequest) {
        this.streamAllocation.streamFailed(e);
        return this.client.retryOnConnectionFailure() && (!requestSendStarted || !(userRequest.body() instanceof UnrepeatableRequestBody)) && this.isRecoverable(e, requestSendStarted) && this.streamAllocation.hasMoreRoutes();
    }
    
    private boolean isRecoverable(final IOException e, final boolean requestSendStarted) {
        if (e instanceof ProtocolException) {
            return false;
        }
        if (e instanceof InterruptedIOException) {
            return e instanceof SocketTimeoutException && !requestSendStarted;
        }
        return (!(e instanceof SSLHandshakeException) || !(e.getCause() instanceof CertificateException)) && !(e instanceof SSLPeerUnverifiedException);
    }
    
    private Request followUpRequest(final Response userResponse) throws IOException {
        if (userResponse == null) {
            throw new IllegalStateException();
        }
        final Connection connection = this.streamAllocation.connection();
        final Route route = (connection != null) ? connection.route() : null;
        final int responseCode = userResponse.code();
        final String method = userResponse.request().method();
        switch (responseCode) {
            case 407: {
                final Proxy selectedProxy = (route != null) ? route.proxy() : this.client.proxy();
                if (selectedProxy.type() != Proxy.Type.HTTP) {
                    throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
                }
                return this.client.proxyAuthenticator().authenticate(route, userResponse);
            }
            case 401: {
                return this.client.authenticator().authenticate(route, userResponse);
            }
            case 307:
            case 308: {
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return null;
                }
            }
            case 300:
            case 301:
            case 302:
            case 303: {
                if (!this.client.followRedirects()) {
                    return null;
                }
                final String location = userResponse.header("Location");
                if (location == null) {
                    return null;
                }
                final HttpUrl url = userResponse.request().url().resolve(location);
                if (url == null) {
                    return null;
                }
                final boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
                if (!sameScheme && !this.client.followSslRedirects()) {
                    return null;
                }
                final Request.Builder requestBuilder = userResponse.request().newBuilder();
                if (HttpMethod.permitsRequestBody(method)) {
                    final boolean maintainBody = HttpMethod.redirectsWithBody(method);
                    if (HttpMethod.redirectsToGet(method)) {
                        requestBuilder.method("GET", null);
                    }
                    else {
                        final RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
                        requestBuilder.method(method, requestBody);
                    }
                    if (!maintainBody) {
                        requestBuilder.removeHeader("Transfer-Encoding");
                        requestBuilder.removeHeader("Content-Length");
                        requestBuilder.removeHeader("Content-Type");
                    }
                }
                if (!this.sameConnection(userResponse, url)) {
                    requestBuilder.removeHeader("Authorization");
                }
                return requestBuilder.url(url).build();
            }
            case 408: {
                if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
                    return null;
                }
                return userResponse.request();
            }
            default: {
                return null;
            }
        }
    }
    
    private boolean sameConnection(final Response response, final HttpUrl followUp) {
        final HttpUrl url = response.request().url();
        return url.host().equals(followUp.host()) && url.port() == followUp.port() && url.scheme().equals(followUp.scheme());
    }
}
