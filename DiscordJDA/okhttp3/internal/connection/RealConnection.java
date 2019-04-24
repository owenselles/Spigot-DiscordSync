// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.connection;

import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Http2Stream;
import java.net.SocketTimeoutException;
import okhttp3.internal.ws.RealWebSocket;
import java.net.SocketException;
import okhttp3.internal.http2.Http2Codec;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.Internal;
import javax.annotation.Nullable;
import okhttp3.internal.Version;
import okio.Source;
import okhttp3.Response;
import okhttp3.internal.http.HttpHeaders;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.internal.http1.Http1Codec;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import okhttp3.internal.tls.OkHostnameVerifier;
import java.security.cert.Certificate;
import okhttp3.CertificatePinner;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSocket;
import okhttp3.Address;
import okio.Okio;
import java.net.ConnectException;
import java.net.Proxy;
import okhttp3.HttpUrl;
import okhttp3.Request;
import java.net.ProtocolException;
import okhttp3.internal.Util;
import okhttp3.internal.platform.Platform;
import java.io.IOException;
import java.net.UnknownServiceException;
import okhttp3.ConnectionSpec;
import java.util.ArrayList;
import java.lang.ref.Reference;
import java.util.List;
import okio.BufferedSink;
import okio.BufferedSource;
import okhttp3.Protocol;
import okhttp3.Handshake;
import java.net.Socket;
import okhttp3.Route;
import okhttp3.ConnectionPool;
import okhttp3.Connection;
import okhttp3.internal.http2.Http2Connection;

public final class RealConnection extends Http2Connection.Listener implements Connection
{
    private static final String NPE_THROW_WITH_NULL = "throw with null exception";
    private final ConnectionPool connectionPool;
    private final Route route;
    private Socket rawSocket;
    private Socket socket;
    private Handshake handshake;
    private Protocol protocol;
    private Http2Connection http2Connection;
    private BufferedSource source;
    private BufferedSink sink;
    public boolean noNewStreams;
    public int successCount;
    public int allocationLimit;
    public final List<Reference<StreamAllocation>> allocations;
    public long idleAtNanos;
    
    public RealConnection(final ConnectionPool connectionPool, final Route route) {
        this.allocationLimit = 1;
        this.allocations = new ArrayList<Reference<StreamAllocation>>();
        this.idleAtNanos = Long.MAX_VALUE;
        this.connectionPool = connectionPool;
        this.route = route;
    }
    
    public static RealConnection testConnection(final ConnectionPool connectionPool, final Route route, final Socket socket, final long idleAtNanos) {
        final RealConnection result = new RealConnection(connectionPool, route);
        result.socket = socket;
        result.idleAtNanos = idleAtNanos;
        return result;
    }
    
    public void connect(final int connectTimeout, final int readTimeout, final int writeTimeout, final boolean connectionRetryEnabled) {
        if (this.protocol != null) {
            throw new IllegalStateException("already connected");
        }
        RouteException routeException = null;
        final List<ConnectionSpec> connectionSpecs = this.route.address().connectionSpecs();
        final ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);
        if (this.route.address().sslSocketFactory() == null) {
            if (!connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
                throw new RouteException(new UnknownServiceException("CLEARTEXT communication not enabled for client"));
            }
            final String host = this.route.address().url().host();
            if (!Platform.get().isCleartextTrafficPermitted(host)) {
                throw new RouteException(new UnknownServiceException("CLEARTEXT communication to " + host + " not permitted by network security policy"));
            }
        }
        while (true) {
            try {
                if (this.route.requiresTunnel()) {
                    this.connectTunnel(connectTimeout, readTimeout, writeTimeout);
                }
                else {
                    this.connectSocket(connectTimeout, readTimeout);
                }
                this.establishProtocol(connectionSpecSelector);
            }
            catch (IOException e) {
                Util.closeQuietly(this.socket);
                Util.closeQuietly(this.rawSocket);
                this.socket = null;
                this.rawSocket = null;
                this.source = null;
                this.sink = null;
                this.handshake = null;
                this.protocol = null;
                this.http2Connection = null;
                if (routeException == null) {
                    routeException = new RouteException(e);
                }
                else {
                    routeException.addConnectException(e);
                }
                if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
                    throw routeException;
                }
                continue;
            }
            break;
        }
        if (this.http2Connection != null) {
            synchronized (this.connectionPool) {
                this.allocationLimit = this.http2Connection.maxConcurrentStreams();
            }
        }
    }
    
    private void connectTunnel(final int connectTimeout, final int readTimeout, final int writeTimeout) throws IOException {
        Request tunnelRequest = this.createTunnelRequest();
        final HttpUrl url = tunnelRequest.url();
        int attemptedConnections = 0;
        final int maxAttempts = 21;
        while (++attemptedConnections <= maxAttempts) {
            this.connectSocket(connectTimeout, readTimeout);
            tunnelRequest = this.createTunnel(readTimeout, writeTimeout, tunnelRequest, url);
            if (tunnelRequest == null) {
                return;
            }
            Util.closeQuietly(this.rawSocket);
            this.rawSocket = null;
            this.sink = null;
            this.source = null;
        }
        throw new ProtocolException("Too many tunnel connections attempted: " + maxAttempts);
    }
    
    private void connectSocket(final int connectTimeout, final int readTimeout) throws IOException {
        final Proxy proxy = this.route.proxy();
        final Address address = this.route.address();
        (this.rawSocket = ((proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP) ? address.socketFactory().createSocket() : new Socket(proxy))).setSoTimeout(readTimeout);
        try {
            Platform.get().connectSocket(this.rawSocket, this.route.socketAddress(), connectTimeout);
        }
        catch (ConnectException e) {
            final ConnectException ce = new ConnectException("Failed to connect to " + this.route.socketAddress());
            ce.initCause(e);
            throw ce;
        }
        try {
            this.source = Okio.buffer(Okio.source(this.rawSocket));
            this.sink = Okio.buffer(Okio.sink(this.rawSocket));
        }
        catch (NullPointerException npe) {
            if ("throw with null exception".equals(npe.getMessage())) {
                throw new IOException(npe);
            }
        }
    }
    
    private void establishProtocol(final ConnectionSpecSelector connectionSpecSelector) throws IOException {
        if (this.route.address().sslSocketFactory() == null) {
            this.protocol = Protocol.HTTP_1_1;
            this.socket = this.rawSocket;
            return;
        }
        this.connectTls(connectionSpecSelector);
        if (this.protocol == Protocol.HTTP_2) {
            this.socket.setSoTimeout(0);
            (this.http2Connection = new Http2Connection.Builder(true).socket(this.socket, this.route.address().url().host(), this.source, this.sink).listener(this).build()).start();
        }
    }
    
    private void connectTls(final ConnectionSpecSelector connectionSpecSelector) throws IOException {
        final Address address = this.route.address();
        final SSLSocketFactory sslSocketFactory = address.sslSocketFactory();
        boolean success = false;
        SSLSocket sslSocket = null;
        try {
            sslSocket = (SSLSocket)sslSocketFactory.createSocket(this.rawSocket, address.url().host(), address.url().port(), true);
            final ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
            if (connectionSpec.supportsTlsExtensions()) {
                Platform.get().configureTlsExtensions(sslSocket, address.url().host(), address.protocols());
            }
            sslSocket.startHandshake();
            final Handshake unverifiedHandshake = Handshake.get(sslSocket.getSession());
            if (!address.hostnameVerifier().verify(address.url().host(), sslSocket.getSession())) {
                final X509Certificate cert = (X509Certificate) unverifiedHandshake.peerCertificates().get(0);
                throw new SSLPeerUnverifiedException("Hostname " + address.url().host() + " not verified:\n    certificate: " + CertificatePinner.pin(cert) + "\n    DN: " + cert.getSubjectDN().getName() + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
            }
            address.certificatePinner().check(address.url().host(), unverifiedHandshake.peerCertificates());
            final String maybeProtocol = connectionSpec.supportsTlsExtensions() ? Platform.get().getSelectedProtocol(sslSocket) : null;
            this.socket = sslSocket;
            this.source = Okio.buffer(Okio.source(this.socket));
            this.sink = Okio.buffer(Okio.sink(this.socket));
            this.handshake = unverifiedHandshake;
            this.protocol = ((maybeProtocol != null) ? Protocol.get(maybeProtocol) : Protocol.HTTP_1_1);
            success = true;
        }
        catch (AssertionError e) {
            if (Util.isAndroidGetsocknameError(e)) {
                throw new IOException(e);
            }
            throw e;
        }
        finally {
            if (sslSocket != null) {
                Platform.get().afterHandshake(sslSocket);
            }
            if (!success) {
                Util.closeQuietly(sslSocket);
            }
        }
    }
    
    private Request createTunnel(final int readTimeout, final int writeTimeout, Request tunnelRequest, final HttpUrl url) throws IOException {
        final String requestLine = "CONNECT " + Util.hostHeader(url, true) + " HTTP/1.1";
        while (true) {
            final Http1Codec tunnelConnection = new Http1Codec(null, null, this.source, this.sink);
            this.source.timeout().timeout(readTimeout, TimeUnit.MILLISECONDS);
            this.sink.timeout().timeout(writeTimeout, TimeUnit.MILLISECONDS);
            tunnelConnection.writeRequest(tunnelRequest.headers(), requestLine);
            tunnelConnection.finishRequest();
            final Response response = tunnelConnection.readResponseHeaders(false).request(tunnelRequest).build();
            long contentLength = HttpHeaders.contentLength(response);
            if (contentLength == -1L) {
                contentLength = 0L;
            }
            final Source body = tunnelConnection.newFixedLengthSource(contentLength);
            Util.skipAll(body, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            body.close();
            switch (response.code()) {
                case 200: {
                    if (!this.source.buffer().exhausted() || !this.sink.buffer().exhausted()) {
                        throw new IOException("TLS tunnel buffered too many bytes!");
                    }
                    return null;
                }
                case 407: {
                    tunnelRequest = this.route.address().proxyAuthenticator().authenticate(this.route, response);
                    if (tunnelRequest == null) {
                        throw new IOException("Failed to authenticate with proxy");
                    }
                    if ("close".equalsIgnoreCase(response.header("Connection"))) {
                        return tunnelRequest;
                    }
                    continue;
                }
                default: {
                    throw new IOException("Unexpected response code for CONNECT: " + response.code());
                }
            }
        }
    }
    
    private Request createTunnelRequest() {
        return new Request.Builder().url(this.route.address().url()).header("Host", Util.hostHeader(this.route.address().url(), true)).header("Proxy-Connection", "Keep-Alive").header("User-Agent", Version.userAgent()).build();
    }
    
    public boolean isEligible(final Address address, @Nullable final Route route) {
        if (this.allocations.size() >= this.allocationLimit || this.noNewStreams) {
            return false;
        }
        if (!Internal.instance.equalsNonHost(this.route.address(), address)) {
            return false;
        }
        if (address.url().host().equals(this.route().address().url().host())) {
            return true;
        }
        if (this.http2Connection == null) {
            return false;
        }
        if (route == null) {
            return false;
        }
        if (route.proxy().type() != Proxy.Type.DIRECT) {
            return false;
        }
        if (this.route.proxy().type() != Proxy.Type.DIRECT) {
            return false;
        }
        if (!this.route.socketAddress().equals(route.socketAddress())) {
            return false;
        }
        if (route.address().hostnameVerifier() != OkHostnameVerifier.INSTANCE) {
            return false;
        }
        if (!this.supportsUrl(address.url())) {
            return false;
        }
        try {
            address.certificatePinner().check(address.url().host(), this.handshake().peerCertificates());
        }
        catch (SSLPeerUnverifiedException e) {
            return false;
        }
        return true;
    }
    
    public boolean supportsUrl(final HttpUrl url) {
        return url.port() == this.route.address().url().port() && (url.host().equals(this.route.address().url().host()) || (this.handshake != null && OkHostnameVerifier.INSTANCE.verify(url.host(), (SSLSession) this.handshake.peerCertificates().get(0))));
    }
    
    public HttpCodec newCodec(final OkHttpClient client, final StreamAllocation streamAllocation) throws SocketException {
        if (this.http2Connection != null) {
            return new Http2Codec(client, streamAllocation, this.http2Connection);
        }
        this.socket.setSoTimeout(client.readTimeoutMillis());
        this.source.timeout().timeout(client.readTimeoutMillis(), TimeUnit.MILLISECONDS);
        this.sink.timeout().timeout(client.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
        return new Http1Codec(client, streamAllocation, this.source, this.sink);
    }
    
    public RealWebSocket.Streams newWebSocketStreams(final StreamAllocation streamAllocation) {
        return new RealWebSocket.Streams(true, this.source, this.sink) {
            @Override
            public void close() throws IOException {
                streamAllocation.streamFinished(true, streamAllocation.codec());
            }
        };
    }
    
    @Override
    public Route route() {
        return this.route;
    }
    
    public void cancel() {
        Util.closeQuietly(this.rawSocket);
    }
    
    @Override
    public Socket socket() {
        return this.socket;
    }
    
    public boolean isHealthy(final boolean doExtensiveChecks) {
        if (this.socket.isClosed() || this.socket.isInputShutdown() || this.socket.isOutputShutdown()) {
            return false;
        }
        if (this.http2Connection != null) {
            return !this.http2Connection.isShutdown();
        }
        if (doExtensiveChecks) {
            try {
                final int readTimeout = this.socket.getSoTimeout();
                try {
                    this.socket.setSoTimeout(1);
                    return !this.source.exhausted();
                }
                finally {
                    this.socket.setSoTimeout(readTimeout);
                }
            }
            catch (SocketTimeoutException ex) {}
            catch (IOException e) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public void onStream(final Http2Stream stream) throws IOException {
        stream.close(ErrorCode.REFUSED_STREAM);
    }
    
    @Override
    public void onSettings(final Http2Connection connection) {
        synchronized (this.connectionPool) {
            this.allocationLimit = connection.maxConcurrentStreams();
        }
    }
    
    @Override
    public Handshake handshake() {
        return this.handshake;
    }
    
    public boolean isMultiplexed() {
        return this.http2Connection != null;
    }
    
    @Override
    public Protocol protocol() {
        return this.protocol;
    }
    
    @Override
    public String toString() {
        return "Connection{" + this.route.address().url().host() + ":" + this.route.address().url().port() + ", proxy=" + this.route.proxy() + " hostAddress=" + this.route.socketAddress() + " cipherSuite=" + ((this.handshake != null) ? this.handshake.cipherSuite() : "none") + " protocol=" + this.protocol + '}';
    }
}
