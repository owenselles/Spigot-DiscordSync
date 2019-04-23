// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import okhttp3.internal.platform.Platform;
import java.lang.ref.Reference;
import java.util.List;
import okhttp3.internal.Util;
import java.util.ArrayList;
import java.net.Socket;
import javax.annotation.Nullable;
import okhttp3.internal.connection.StreamAllocation;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.RealConnection;
import java.util.Deque;
import java.util.concurrent.Executor;

public final class ConnectionPool
{
    private static final Executor executor;
    private final int maxIdleConnections;
    private final long keepAliveDurationNs;
    private final Runnable cleanupRunnable;
    private final Deque<RealConnection> connections;
    final RouteDatabase routeDatabase;
    boolean cleanupRunning;
    
    public ConnectionPool() {
        this(5, 5L, TimeUnit.MINUTES);
    }
    
    public ConnectionPool(final int maxIdleConnections, final long keepAliveDuration, final TimeUnit timeUnit) {
        this.cleanupRunnable = new Runnable() {
            @Override
            public void run() {
                while (true) {
                    long waitNanos = ConnectionPool.this.cleanup(System.nanoTime());
                    if (waitNanos == -1L) {
                        break;
                    }
                    if (waitNanos <= 0L) {
                        continue;
                    }
                    final long waitMillis = waitNanos / 1000000L;
                    waitNanos -= waitMillis * 1000000L;
                    synchronized (ConnectionPool.this) {
                        try {
                            ConnectionPool.this.wait(waitMillis, (int)waitNanos);
                        }
                        catch (InterruptedException ex) {}
                    }
                }
            }
        };
        this.connections = new ArrayDeque<RealConnection>();
        this.routeDatabase = new RouteDatabase();
        this.maxIdleConnections = maxIdleConnections;
        this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);
        if (keepAliveDuration <= 0L) {
            throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
        }
    }
    
    public synchronized int idleConnectionCount() {
        int total = 0;
        for (final RealConnection connection : this.connections) {
            if (connection.allocations.isEmpty()) {
                ++total;
            }
        }
        return total;
    }
    
    public synchronized int connectionCount() {
        return this.connections.size();
    }
    
    @Nullable
    RealConnection get(final Address address, final StreamAllocation streamAllocation, final Route route) {
        assert Thread.holdsLock(this);
        for (final RealConnection connection : this.connections) {
            if (connection.isEligible(address, route)) {
                streamAllocation.acquire(connection);
                return connection;
            }
        }
        return null;
    }
    
    @Nullable
    Socket deduplicate(final Address address, final StreamAllocation streamAllocation) {
        assert Thread.holdsLock(this);
        for (final RealConnection connection : this.connections) {
            if (connection.isEligible(address, null) && connection.isMultiplexed() && connection != streamAllocation.connection()) {
                return streamAllocation.releaseAndAcquire(connection);
            }
        }
        return null;
    }
    
    void put(final RealConnection connection) {
        assert Thread.holdsLock(this);
        if (!this.cleanupRunning) {
            this.cleanupRunning = true;
            ConnectionPool.executor.execute(this.cleanupRunnable);
        }
        this.connections.add(connection);
    }
    
    boolean connectionBecameIdle(final RealConnection connection) {
        assert Thread.holdsLock(this);
        if (connection.noNewStreams || this.maxIdleConnections == 0) {
            this.connections.remove(connection);
            return true;
        }
        this.notifyAll();
        return false;
    }
    
    public void evictAll() {
        final List<RealConnection> evictedConnections = new ArrayList<RealConnection>();
        synchronized (this) {
            final Iterator<RealConnection> i = this.connections.iterator();
            while (i.hasNext()) {
                final RealConnection connection = i.next();
                if (connection.allocations.isEmpty()) {
                    connection.noNewStreams = true;
                    evictedConnections.add(connection);
                    i.remove();
                }
            }
        }
        for (final RealConnection connection2 : evictedConnections) {
            Util.closeQuietly(connection2.socket());
        }
    }
    
    long cleanup(final long now) {
        int inUseConnectionCount = 0;
        int idleConnectionCount = 0;
        RealConnection longestIdleConnection = null;
        long longestIdleDurationNs = Long.MIN_VALUE;
        synchronized (this) {
            for (final RealConnection connection : this.connections) {
                if (this.pruneAndGetAllocationCount(connection, now) > 0) {
                    ++inUseConnectionCount;
                }
                else {
                    ++idleConnectionCount;
                    final long idleDurationNs = now - connection.idleAtNanos;
                    if (idleDurationNs <= longestIdleDurationNs) {
                        continue;
                    }
                    longestIdleDurationNs = idleDurationNs;
                    longestIdleConnection = connection;
                }
            }
            if (longestIdleDurationNs >= this.keepAliveDurationNs || idleConnectionCount > this.maxIdleConnections) {
                this.connections.remove(longestIdleConnection);
            }
            else {
                if (idleConnectionCount > 0) {
                    return this.keepAliveDurationNs - longestIdleDurationNs;
                }
                if (inUseConnectionCount > 0) {
                    return this.keepAliveDurationNs;
                }
                this.cleanupRunning = false;
                return -1L;
            }
        }
        Util.closeQuietly(longestIdleConnection.socket());
        return 0L;
    }
    
    private int pruneAndGetAllocationCount(final RealConnection connection, final long now) {
        final List<Reference<StreamAllocation>> references = connection.allocations;
        int i = 0;
        while (i < references.size()) {
            final Reference<StreamAllocation> reference = references.get(i);
            if (reference.get() != null) {
                ++i;
            }
            else {
                final StreamAllocation.StreamAllocationReference streamAllocRef = (StreamAllocation.StreamAllocationReference)reference;
                final String message = "A connection to " + connection.route().address().url() + " was leaked. Did you forget to close a response body?";
                Platform.get().logCloseableLeak(message, streamAllocRef.callStackTrace);
                references.remove(i);
                connection.noNewStreams = true;
                if (references.isEmpty()) {
                    connection.idleAtNanos = now - this.keepAliveDurationNs;
                    return 0;
                }
                continue;
            }
        }
        return references.size();
    }
    
    static {
        executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));
    }
}
