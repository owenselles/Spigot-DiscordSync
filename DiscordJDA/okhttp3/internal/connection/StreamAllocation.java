// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.connection;

import java.lang.ref.WeakReference;
import java.lang.ref.Reference;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;
import java.net.Socket;
import okhttp3.internal.Util;
import okhttp3.internal.Internal;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.internal.http.HttpCodec;
import okhttp3.ConnectionPool;
import okhttp3.Route;
import okhttp3.Address;

public final class StreamAllocation
{
    public final Address address;
    private Route route;
    private final ConnectionPool connectionPool;
    private final Object callStackTrace;
    private final RouteSelector routeSelector;
    private int refusedStreamCount;
    private RealConnection connection;
    private boolean released;
    private boolean canceled;
    private HttpCodec codec;
    
    public StreamAllocation(final ConnectionPool connectionPool, final Address address, final Object callStackTrace) {
        this.connectionPool = connectionPool;
        this.address = address;
        this.routeSelector = new RouteSelector(address, this.routeDatabase());
        this.callStackTrace = callStackTrace;
    }
    
    public HttpCodec newStream(final OkHttpClient client, final boolean doExtensiveHealthChecks) {
        final int connectTimeout = client.connectTimeoutMillis();
        final int readTimeout = client.readTimeoutMillis();
        final int writeTimeout = client.writeTimeoutMillis();
        final boolean connectionRetryEnabled = client.retryOnConnectionFailure();
        try {
            final RealConnection resultConnection = this.findHealthyConnection(connectTimeout, readTimeout, writeTimeout, connectionRetryEnabled, doExtensiveHealthChecks);
            final HttpCodec resultCodec = resultConnection.newCodec(client, this);
            synchronized (this.connectionPool) {
                return this.codec = resultCodec;
            }
        }
        catch (IOException e) {
            throw new RouteException(e);
        }
    }
    
    private RealConnection findHealthyConnection(final int connectTimeout, final int readTimeout, final int writeTimeout, final boolean connectionRetryEnabled, final boolean doExtensiveHealthChecks) throws IOException {
        RealConnection candidate;
        while (true) {
            candidate = this.findConnection(connectTimeout, readTimeout, writeTimeout, connectionRetryEnabled);
            synchronized (this.connectionPool) {
                if (candidate.successCount == 0) {
                    return candidate;
                }
            }
            if (candidate.isHealthy(doExtensiveHealthChecks)) {
                break;
            }
            this.noNewStreams();
        }
        return candidate;
    }
    
    private RealConnection findConnection(final int connectTimeout, final int readTimeout, final int writeTimeout, final boolean connectionRetryEnabled) throws IOException {
        Route selectedRoute;
        synchronized (this.connectionPool) {
            if (this.released) {
                throw new IllegalStateException("released");
            }
            if (this.codec != null) {
                throw new IllegalStateException("codec != null");
            }
            if (this.canceled) {
                throw new IOException("Canceled");
            }
            final RealConnection allocatedConnection = this.connection;
            if (allocatedConnection != null && !allocatedConnection.noNewStreams) {
                return allocatedConnection;
            }
            Internal.instance.get(this.connectionPool, this.address, this, null);
            if (this.connection != null) {
                return this.connection;
            }
            selectedRoute = this.route;
        }
        if (selectedRoute == null) {
            selectedRoute = this.routeSelector.next();
        }
        RealConnection result;
        synchronized (this.connectionPool) {
            if (this.canceled) {
                throw new IOException("Canceled");
            }
            Internal.instance.get(this.connectionPool, this.address, this, selectedRoute);
            if (this.connection != null) {
                this.route = selectedRoute;
                return this.connection;
            }
            this.route = selectedRoute;
            this.refusedStreamCount = 0;
            result = new RealConnection(this.connectionPool, selectedRoute);
            this.acquire(result);
        }
        result.connect(connectTimeout, readTimeout, writeTimeout, connectionRetryEnabled);
        this.routeDatabase().connected(result.route());
        Socket socket = null;
        synchronized (this.connectionPool) {
            Internal.instance.put(this.connectionPool, result);
            if (result.isMultiplexed()) {
                socket = Internal.instance.deduplicate(this.connectionPool, this.address, this);
                result = this.connection;
            }
        }
        Util.closeQuietly(socket);
        return result;
    }
    
    public void streamFinished(final boolean noNewStreams, final HttpCodec codec) {
        final Socket socket;
        synchronized (this.connectionPool) {
            if (codec == null || codec != this.codec) {
                throw new IllegalStateException("expected " + this.codec + " but was " + codec);
            }
            if (!noNewStreams) {
                final RealConnection connection = this.connection;
                ++connection.successCount;
            }
            socket = this.deallocate(noNewStreams, false, true);
        }
        Util.closeQuietly(socket);
    }
    
    public HttpCodec codec() {
        synchronized (this.connectionPool) {
            return this.codec;
        }
    }
    
    private RouteDatabase routeDatabase() {
        return Internal.instance.routeDatabase(this.connectionPool);
    }
    
    public synchronized RealConnection connection() {
        return this.connection;
    }
    
    public void release() {
        final Socket socket;
        synchronized (this.connectionPool) {
            socket = this.deallocate(false, true, false);
        }
        Util.closeQuietly(socket);
    }
    
    public void noNewStreams() {
        final Socket socket;
        synchronized (this.connectionPool) {
            socket = this.deallocate(true, false, false);
        }
        Util.closeQuietly(socket);
    }
    
    private Socket deallocate(final boolean noNewStreams, final boolean released, final boolean streamFinished) {
        assert Thread.holdsLock(this.connectionPool);
        if (streamFinished) {
            this.codec = null;
        }
        if (released) {
            this.released = true;
        }
        Socket socket = null;
        if (this.connection != null) {
            if (noNewStreams) {
                this.connection.noNewStreams = true;
            }
            if (this.codec == null && (this.released || this.connection.noNewStreams)) {
                this.release(this.connection);
                if (this.connection.allocations.isEmpty()) {
                    this.connection.idleAtNanos = System.nanoTime();
                    if (Internal.instance.connectionBecameIdle(this.connectionPool, this.connection)) {
                        socket = this.connection.socket();
                    }
                }
                this.connection = null;
            }
        }
        return socket;
    }
    
    public void cancel() {
        final HttpCodec codecToCancel;
        final RealConnection connectionToCancel;
        synchronized (this.connectionPool) {
            this.canceled = true;
            codecToCancel = this.codec;
            connectionToCancel = this.connection;
        }
        if (codecToCancel != null) {
            codecToCancel.cancel();
        }
        else if (connectionToCancel != null) {
            connectionToCancel.cancel();
        }
    }
    
    public void streamFailed(final IOException e) {
        boolean noNewStreams = false;
        final Socket socket;
        synchronized (this.connectionPool) {
            if (e instanceof StreamResetException) {
                final StreamResetException streamResetException = (StreamResetException)e;
                if (streamResetException.errorCode == ErrorCode.REFUSED_STREAM) {
                    ++this.refusedStreamCount;
                }
                if (streamResetException.errorCode != ErrorCode.REFUSED_STREAM || this.refusedStreamCount > 1) {
                    noNewStreams = true;
                    this.route = null;
                }
            }
            else if (this.connection != null && (!this.connection.isMultiplexed() || e instanceof ConnectionShutdownException)) {
                noNewStreams = true;
                if (this.connection.successCount == 0) {
                    if (this.route != null && e != null) {
                        this.routeSelector.connectFailed(this.route, e);
                    }
                    this.route = null;
                }
            }
            socket = this.deallocate(noNewStreams, false, true);
        }
        Util.closeQuietly(socket);
    }
    
    public void acquire(final RealConnection connection) {
        assert Thread.holdsLock(this.connectionPool);
        if (this.connection != null) {
            throw new IllegalStateException();
        }
        this.connection = connection;
        connection.allocations.add(new StreamAllocationReference(this, this.callStackTrace));
    }
    
    private void release(final RealConnection connection) {
        for (int i = 0, size = connection.allocations.size(); i < size; ++i) {
            final Reference<StreamAllocation> reference = connection.allocations.get(i);
            if (reference.get() == this) {
                connection.allocations.remove(i);
                return;
            }
        }
        throw new IllegalStateException();
    }
    
    public Socket releaseAndAcquire(final RealConnection newConnection) {
        assert Thread.holdsLock(this.connectionPool);
        if (this.codec != null || this.connection.allocations.size() != 1) {
            throw new IllegalStateException();
        }
        final Reference<StreamAllocation> onlyAllocation = this.connection.allocations.get(0);
        final Socket socket = this.deallocate(true, false, false);
        this.connection = newConnection;
        newConnection.allocations.add(onlyAllocation);
        return socket;
    }
    
    public boolean hasMoreRoutes() {
        return this.route != null || this.routeSelector.hasNext();
    }
    
    @Override
    public String toString() {
        final RealConnection connection = this.connection();
        return (connection != null) ? connection.toString() : this.address.toString();
    }
    
    public static final class StreamAllocationReference extends WeakReference<StreamAllocation>
    {
        public final Object callStackTrace;
        
        StreamAllocationReference(final StreamAllocation referent, final Object callStackTrace) {
            super(referent);
            this.callStackTrace = callStackTrace;
        }
    }
}
