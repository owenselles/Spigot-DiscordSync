// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.util.Collections;
import okhttp3.internal.platform.Platform;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
import okhttp3.internal.tls.OkHostnameVerifier;
import java.util.ArrayList;
import java.net.UnknownHostException;
import java.net.MalformedURLException;
import javax.net.ssl.SSLSocket;
import okhttp3.internal.connection.RouteDatabase;
import java.net.Socket;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.Internal;
import okhttp3.internal.ws.RealWebSocket;
import java.util.Random;
import java.security.SecureRandom;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.security.KeyStore;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.util.Iterator;
import okhttp3.internal.Util;
import javax.net.ssl.HostnameVerifier;
import okhttp3.internal.tls.CertificateChainCleaner;
import javax.net.ssl.SSLSocketFactory;
import javax.net.SocketFactory;
import okhttp3.internal.cache.InternalCache;
import java.net.ProxySelector;
import javax.annotation.Nullable;
import java.net.Proxy;
import java.util.List;

public class OkHttpClient implements Cloneable, Call.Factory, WebSocket.Factory
{
    static final List<Protocol> DEFAULT_PROTOCOLS;
    static final List<ConnectionSpec> DEFAULT_CONNECTION_SPECS;
    final Dispatcher dispatcher;
    @Nullable
    final Proxy proxy;
    final List<Protocol> protocols;
    final List<ConnectionSpec> connectionSpecs;
    final List<Interceptor> interceptors;
    final List<Interceptor> networkInterceptors;
    final EventListener.Factory eventListenerFactory;
    final ProxySelector proxySelector;
    final CookieJar cookieJar;
    @Nullable
    final Cache cache;
    @Nullable
    final InternalCache internalCache;
    final SocketFactory socketFactory;
    @Nullable
    final SSLSocketFactory sslSocketFactory;
    @Nullable
    final CertificateChainCleaner certificateChainCleaner;
    final HostnameVerifier hostnameVerifier;
    final CertificatePinner certificatePinner;
    final Authenticator proxyAuthenticator;
    final Authenticator authenticator;
    final ConnectionPool connectionPool;
    final Dns dns;
    final boolean followSslRedirects;
    final boolean followRedirects;
    final boolean retryOnConnectionFailure;
    final int connectTimeout;
    final int readTimeout;
    final int writeTimeout;
    final int pingInterval;
    
    public OkHttpClient() {
        this(new Builder());
    }
    
    OkHttpClient(final Builder builder) {
        this.dispatcher = builder.dispatcher;
        this.proxy = builder.proxy;
        this.protocols = builder.protocols;
        this.connectionSpecs = builder.connectionSpecs;
        this.interceptors = Util.immutableList(builder.interceptors);
        this.networkInterceptors = Util.immutableList(builder.networkInterceptors);
        this.eventListenerFactory = builder.eventListenerFactory;
        this.proxySelector = builder.proxySelector;
        this.cookieJar = builder.cookieJar;
        this.cache = builder.cache;
        this.internalCache = builder.internalCache;
        this.socketFactory = builder.socketFactory;
        boolean isTLS = false;
        for (final ConnectionSpec spec : this.connectionSpecs) {
            isTLS = (isTLS || spec.isTls());
        }
        if (builder.sslSocketFactory != null || !isTLS) {
            this.sslSocketFactory = builder.sslSocketFactory;
            this.certificateChainCleaner = builder.certificateChainCleaner;
        }
        else {
            final X509TrustManager trustManager = this.systemDefaultTrustManager();
            this.sslSocketFactory = this.systemDefaultSslSocketFactory(trustManager);
            this.certificateChainCleaner = CertificateChainCleaner.get(trustManager);
        }
        this.hostnameVerifier = builder.hostnameVerifier;
        this.certificatePinner = builder.certificatePinner.withCertificateChainCleaner(this.certificateChainCleaner);
        this.proxyAuthenticator = builder.proxyAuthenticator;
        this.authenticator = builder.authenticator;
        this.connectionPool = builder.connectionPool;
        this.dns = builder.dns;
        this.followSslRedirects = builder.followSslRedirects;
        this.followRedirects = builder.followRedirects;
        this.retryOnConnectionFailure = builder.retryOnConnectionFailure;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.writeTimeout = builder.writeTimeout;
        this.pingInterval = builder.pingInterval;
    }
    
    private X509TrustManager systemDefaultTrustManager() {
        try {
            final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore)null);
            final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
            }
            return (X509TrustManager)trustManagers[0];
        }
        catch (GeneralSecurityException e) {
            throw new AssertionError();
        }
    }
    
    private SSLSocketFactory systemDefaultSslSocketFactory(final X509TrustManager trustManager) {
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { trustManager }, null);
            return sslContext.getSocketFactory();
        }
        catch (GeneralSecurityException e) {
            throw new AssertionError();
        }
    }
    
    public int connectTimeoutMillis() {
        return this.connectTimeout;
    }
    
    public int readTimeoutMillis() {
        return this.readTimeout;
    }
    
    public int writeTimeoutMillis() {
        return this.writeTimeout;
    }
    
    public int pingIntervalMillis() {
        return this.pingInterval;
    }
    
    public Proxy proxy() {
        return this.proxy;
    }
    
    public ProxySelector proxySelector() {
        return this.proxySelector;
    }
    
    public CookieJar cookieJar() {
        return this.cookieJar;
    }
    
    public Cache cache() {
        return this.cache;
    }
    
    InternalCache internalCache() {
        return (this.cache != null) ? this.cache.internalCache : this.internalCache;
    }
    
    public Dns dns() {
        return this.dns;
    }
    
    public SocketFactory socketFactory() {
        return this.socketFactory;
    }
    
    public SSLSocketFactory sslSocketFactory() {
        return this.sslSocketFactory;
    }
    
    public HostnameVerifier hostnameVerifier() {
        return this.hostnameVerifier;
    }
    
    public CertificatePinner certificatePinner() {
        return this.certificatePinner;
    }
    
    public Authenticator authenticator() {
        return this.authenticator;
    }
    
    public Authenticator proxyAuthenticator() {
        return this.proxyAuthenticator;
    }
    
    public ConnectionPool connectionPool() {
        return this.connectionPool;
    }
    
    public boolean followSslRedirects() {
        return this.followSslRedirects;
    }
    
    public boolean followRedirects() {
        return this.followRedirects;
    }
    
    public boolean retryOnConnectionFailure() {
        return this.retryOnConnectionFailure;
    }
    
    public Dispatcher dispatcher() {
        return this.dispatcher;
    }
    
    public List<Protocol> protocols() {
        return this.protocols;
    }
    
    public List<ConnectionSpec> connectionSpecs() {
        return this.connectionSpecs;
    }
    
    public List<Interceptor> interceptors() {
        return this.interceptors;
    }
    
    public List<Interceptor> networkInterceptors() {
        return this.networkInterceptors;
    }
    
    EventListener.Factory eventListenerFactory() {
        return this.eventListenerFactory;
    }
    
    @Override
    public Call newCall(final Request request) {
        return new RealCall(this, request, false);
    }
    
    @Override
    public WebSocket newWebSocket(final Request request, final WebSocketListener listener) {
        final RealWebSocket webSocket = new RealWebSocket(request, listener, new Random());
        webSocket.connect(this);
        return webSocket;
    }
    
    public Builder newBuilder() {
        return new Builder(this);
    }
    
    static {
        DEFAULT_PROTOCOLS = Util.immutableList(Protocol.HTTP_2, Protocol.HTTP_1_1);
        DEFAULT_CONNECTION_SPECS = Util.immutableList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT);
        Internal.instance = new Internal() {
            @Override
            public void addLenient(final Headers.Builder builder, final String line) {
                builder.addLenient(line);
            }
            
            @Override
            public void addLenient(final Headers.Builder builder, final String name, final String value) {
                builder.addLenient(name, value);
            }
            
            @Override
            public void setCache(final Builder builder, final InternalCache internalCache) {
                builder.setInternalCache(internalCache);
            }
            
            @Override
            public boolean connectionBecameIdle(final ConnectionPool pool, final RealConnection connection) {
                return pool.connectionBecameIdle(connection);
            }
            
            @Override
            public RealConnection get(final ConnectionPool pool, final Address address, final StreamAllocation streamAllocation, final Route route) {
                return pool.get(address, streamAllocation, route);
            }
            
            @Override
            public boolean equalsNonHost(final Address a, final Address b) {
                return a.equalsNonHost(b);
            }
            
            @Override
            public Socket deduplicate(final ConnectionPool pool, final Address address, final StreamAllocation streamAllocation) {
                return pool.deduplicate(address, streamAllocation);
            }
            
            @Override
            public void put(final ConnectionPool pool, final RealConnection connection) {
                pool.put(connection);
            }
            
            @Override
            public RouteDatabase routeDatabase(final ConnectionPool connectionPool) {
                return connectionPool.routeDatabase;
            }
            
            @Override
            public int code(final Response.Builder responseBuilder) {
                return responseBuilder.code;
            }
            
            @Override
            public void apply(final ConnectionSpec tlsConfiguration, final SSLSocket sslSocket, final boolean isFallback) {
                tlsConfiguration.apply(sslSocket, isFallback);
            }
            
            @Override
            public HttpUrl getHttpUrlChecked(final String url) throws MalformedURLException, UnknownHostException {
                return HttpUrl.getChecked(url);
            }
            
            @Override
            public StreamAllocation streamAllocation(final Call call) {
                return ((RealCall)call).streamAllocation();
            }
            
            @Override
            public Call newWebSocketCall(final OkHttpClient client, final Request originalRequest) {
                return new RealCall(client, originalRequest, true);
            }
        };
    }
    
    public static final class Builder
    {
        Dispatcher dispatcher;
        @Nullable
        Proxy proxy;
        List<Protocol> protocols;
        List<ConnectionSpec> connectionSpecs;
        final List<Interceptor> interceptors;
        final List<Interceptor> networkInterceptors;
        EventListener.Factory eventListenerFactory;
        ProxySelector proxySelector;
        CookieJar cookieJar;
        @Nullable
        Cache cache;
        @Nullable
        InternalCache internalCache;
        SocketFactory socketFactory;
        @Nullable
        SSLSocketFactory sslSocketFactory;
        @Nullable
        CertificateChainCleaner certificateChainCleaner;
        HostnameVerifier hostnameVerifier;
        CertificatePinner certificatePinner;
        Authenticator proxyAuthenticator;
        Authenticator authenticator;
        ConnectionPool connectionPool;
        Dns dns;
        boolean followSslRedirects;
        boolean followRedirects;
        boolean retryOnConnectionFailure;
        int connectTimeout;
        int readTimeout;
        int writeTimeout;
        int pingInterval;
        
        public Builder() {
            this.interceptors = new ArrayList<Interceptor>();
            this.networkInterceptors = new ArrayList<Interceptor>();
            this.dispatcher = new Dispatcher();
            this.protocols = OkHttpClient.DEFAULT_PROTOCOLS;
            this.connectionSpecs = OkHttpClient.DEFAULT_CONNECTION_SPECS;
            this.eventListenerFactory = EventListener.factory(EventListener.NONE);
            this.proxySelector = ProxySelector.getDefault();
            this.cookieJar = CookieJar.NO_COOKIES;
            this.socketFactory = SocketFactory.getDefault();
            this.hostnameVerifier = OkHostnameVerifier.INSTANCE;
            this.certificatePinner = CertificatePinner.DEFAULT;
            this.proxyAuthenticator = Authenticator.NONE;
            this.authenticator = Authenticator.NONE;
            this.connectionPool = new ConnectionPool();
            this.dns = Dns.SYSTEM;
            this.followSslRedirects = true;
            this.followRedirects = true;
            this.retryOnConnectionFailure = true;
            this.connectTimeout = 10000;
            this.readTimeout = 10000;
            this.writeTimeout = 10000;
            this.pingInterval = 0;
        }
        
        Builder(final OkHttpClient okHttpClient) {
            this.interceptors = new ArrayList<Interceptor>();
            this.networkInterceptors = new ArrayList<Interceptor>();
            this.dispatcher = okHttpClient.dispatcher;
            this.proxy = okHttpClient.proxy;
            this.protocols = okHttpClient.protocols;
            this.connectionSpecs = okHttpClient.connectionSpecs;
            this.interceptors.addAll(okHttpClient.interceptors);
            this.networkInterceptors.addAll(okHttpClient.networkInterceptors);
            this.eventListenerFactory = okHttpClient.eventListenerFactory;
            this.proxySelector = okHttpClient.proxySelector;
            this.cookieJar = okHttpClient.cookieJar;
            this.internalCache = okHttpClient.internalCache;
            this.cache = okHttpClient.cache;
            this.socketFactory = okHttpClient.socketFactory;
            this.sslSocketFactory = okHttpClient.sslSocketFactory;
            this.certificateChainCleaner = okHttpClient.certificateChainCleaner;
            this.hostnameVerifier = okHttpClient.hostnameVerifier;
            this.certificatePinner = okHttpClient.certificatePinner;
            this.proxyAuthenticator = okHttpClient.proxyAuthenticator;
            this.authenticator = okHttpClient.authenticator;
            this.connectionPool = okHttpClient.connectionPool;
            this.dns = okHttpClient.dns;
            this.followSslRedirects = okHttpClient.followSslRedirects;
            this.followRedirects = okHttpClient.followRedirects;
            this.retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure;
            this.connectTimeout = okHttpClient.connectTimeout;
            this.readTimeout = okHttpClient.readTimeout;
            this.writeTimeout = okHttpClient.writeTimeout;
            this.pingInterval = okHttpClient.pingInterval;
        }
        
        public Builder connectTimeout(final long timeout, final TimeUnit unit) {
            this.connectTimeout = checkDuration("timeout", timeout, unit);
            return this;
        }
        
        public Builder readTimeout(final long timeout, final TimeUnit unit) {
            this.readTimeout = checkDuration("timeout", timeout, unit);
            return this;
        }
        
        public Builder writeTimeout(final long timeout, final TimeUnit unit) {
            this.writeTimeout = checkDuration("timeout", timeout, unit);
            return this;
        }
        
        public Builder pingInterval(final long interval, final TimeUnit unit) {
            this.pingInterval = checkDuration("interval", interval, unit);
            return this;
        }
        
        private static int checkDuration(final String name, final long duration, final TimeUnit unit) {
            if (duration < 0L) {
                throw new IllegalArgumentException(name + " < 0");
            }
            if (unit == null) {
                throw new NullPointerException("unit == null");
            }
            final long millis = unit.toMillis(duration);
            if (millis > 2147483647L) {
                throw new IllegalArgumentException(name + " too large.");
            }
            if (millis == 0L && duration > 0L) {
                throw new IllegalArgumentException(name + " too small.");
            }
            return (int)millis;
        }
        
        public Builder proxy(@Nullable final Proxy proxy) {
            this.proxy = proxy;
            return this;
        }
        
        public Builder proxySelector(final ProxySelector proxySelector) {
            this.proxySelector = proxySelector;
            return this;
        }
        
        public Builder cookieJar(final CookieJar cookieJar) {
            if (cookieJar == null) {
                throw new NullPointerException("cookieJar == null");
            }
            this.cookieJar = cookieJar;
            return this;
        }
        
        void setInternalCache(@Nullable final InternalCache internalCache) {
            this.internalCache = internalCache;
            this.cache = null;
        }
        
        public Builder cache(@Nullable final Cache cache) {
            this.cache = cache;
            this.internalCache = null;
            return this;
        }
        
        public Builder dns(final Dns dns) {
            if (dns == null) {
                throw new NullPointerException("dns == null");
            }
            this.dns = dns;
            return this;
        }
        
        public Builder socketFactory(final SocketFactory socketFactory) {
            if (socketFactory == null) {
                throw new NullPointerException("socketFactory == null");
            }
            this.socketFactory = socketFactory;
            return this;
        }
        
        public Builder sslSocketFactory(final SSLSocketFactory sslSocketFactory) {
            if (sslSocketFactory == null) {
                throw new NullPointerException("sslSocketFactory == null");
            }
            final X509TrustManager trustManager = Platform.get().trustManager(sslSocketFactory);
            if (trustManager == null) {
                throw new IllegalStateException("Unable to extract the trust manager on " + Platform.get() + ", sslSocketFactory is " + sslSocketFactory.getClass());
            }
            this.sslSocketFactory = sslSocketFactory;
            this.certificateChainCleaner = CertificateChainCleaner.get(trustManager);
            return this;
        }
        
        public Builder sslSocketFactory(final SSLSocketFactory sslSocketFactory, final X509TrustManager trustManager) {
            if (sslSocketFactory == null) {
                throw new NullPointerException("sslSocketFactory == null");
            }
            if (trustManager == null) {
                throw new NullPointerException("trustManager == null");
            }
            this.sslSocketFactory = sslSocketFactory;
            this.certificateChainCleaner = CertificateChainCleaner.get(trustManager);
            return this;
        }
        
        public Builder hostnameVerifier(final HostnameVerifier hostnameVerifier) {
            if (hostnameVerifier == null) {
                throw new NullPointerException("hostnameVerifier == null");
            }
            this.hostnameVerifier = hostnameVerifier;
            return this;
        }
        
        public Builder certificatePinner(final CertificatePinner certificatePinner) {
            if (certificatePinner == null) {
                throw new NullPointerException("certificatePinner == null");
            }
            this.certificatePinner = certificatePinner;
            return this;
        }
        
        public Builder authenticator(final Authenticator authenticator) {
            if (authenticator == null) {
                throw new NullPointerException("authenticator == null");
            }
            this.authenticator = authenticator;
            return this;
        }
        
        public Builder proxyAuthenticator(final Authenticator proxyAuthenticator) {
            if (proxyAuthenticator == null) {
                throw new NullPointerException("proxyAuthenticator == null");
            }
            this.proxyAuthenticator = proxyAuthenticator;
            return this;
        }
        
        public Builder connectionPool(final ConnectionPool connectionPool) {
            if (connectionPool == null) {
                throw new NullPointerException("connectionPool == null");
            }
            this.connectionPool = connectionPool;
            return this;
        }
        
        public Builder followSslRedirects(final boolean followProtocolRedirects) {
            this.followSslRedirects = followProtocolRedirects;
            return this;
        }
        
        public Builder followRedirects(final boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }
        
        public Builder retryOnConnectionFailure(final boolean retryOnConnectionFailure) {
            this.retryOnConnectionFailure = retryOnConnectionFailure;
            return this;
        }
        
        public Builder dispatcher(final Dispatcher dispatcher) {
            if (dispatcher == null) {
                throw new IllegalArgumentException("dispatcher == null");
            }
            this.dispatcher = dispatcher;
            return this;
        }
        
        public Builder protocols(List<Protocol> protocols) {
            protocols = new ArrayList<Protocol>(protocols);
            if (!protocols.contains(Protocol.HTTP_1_1)) {
                throw new IllegalArgumentException("protocols doesn't contain http/1.1: " + protocols);
            }
            if (protocols.contains(Protocol.HTTP_1_0)) {
                throw new IllegalArgumentException("protocols must not contain http/1.0: " + protocols);
            }
            if (protocols.contains(null)) {
                throw new IllegalArgumentException("protocols must not contain null");
            }
            protocols.remove(Protocol.SPDY_3);
            this.protocols = Collections.unmodifiableList((List<? extends Protocol>)protocols);
            return this;
        }
        
        public Builder connectionSpecs(final List<ConnectionSpec> connectionSpecs) {
            this.connectionSpecs = Util.immutableList(connectionSpecs);
            return this;
        }
        
        public List<Interceptor> interceptors() {
            return this.interceptors;
        }
        
        public Builder addInterceptor(final Interceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }
        
        public List<Interceptor> networkInterceptors() {
            return this.networkInterceptors;
        }
        
        public Builder addNetworkInterceptor(final Interceptor interceptor) {
            this.networkInterceptors.add(interceptor);
            return this;
        }
        
        Builder eventListener(final EventListener eventListener) {
            if (eventListener == null) {
                throw new NullPointerException("eventListener == null");
            }
            this.eventListenerFactory = EventListener.factory(eventListener);
            return this;
        }
        
        Builder eventListenerFactory(final EventListener.Factory eventListenerFactory) {
            if (eventListenerFactory == null) {
                throw new NullPointerException("eventListenerFactory == null");
            }
            this.eventListenerFactory = eventListenerFactory;
            return this;
        }
        
        public OkHttpClient build() {
            return new OkHttpClient(this);
        }
    }
}
