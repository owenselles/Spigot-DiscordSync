// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import okhttp3.internal.Util;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;

public final class Dispatcher
{
    private int maxRequests;
    private int maxRequestsPerHost;
    @Nullable
    private Runnable idleCallback;
    @Nullable
    private ExecutorService executorService;
    private final Deque<RealCall.AsyncCall> readyAsyncCalls;
    private final Deque<RealCall.AsyncCall> runningAsyncCalls;
    private final Deque<RealCall> runningSyncCalls;
    
    public Dispatcher(final ExecutorService executorService) {
        this.maxRequests = 64;
        this.maxRequestsPerHost = 5;
        this.readyAsyncCalls = new ArrayDeque<RealCall.AsyncCall>();
        this.runningAsyncCalls = new ArrayDeque<RealCall.AsyncCall>();
        this.runningSyncCalls = new ArrayDeque<RealCall>();
        this.executorService = executorService;
    }
    
    public Dispatcher() {
        this.maxRequests = 64;
        this.maxRequestsPerHost = 5;
        this.readyAsyncCalls = new ArrayDeque<RealCall.AsyncCall>();
        this.runningAsyncCalls = new ArrayDeque<RealCall.AsyncCall>();
        this.runningSyncCalls = new ArrayDeque<RealCall>();
    }
    
    public synchronized ExecutorService executorService() {
        if (this.executorService == null) {
            this.executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
        }
        return this.executorService;
    }
    
    public synchronized void setMaxRequests(final int maxRequests) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequests);
        }
        this.maxRequests = maxRequests;
        this.promoteCalls();
    }
    
    public synchronized int getMaxRequests() {
        return this.maxRequests;
    }
    
    public synchronized void setMaxRequestsPerHost(final int maxRequestsPerHost) {
        if (maxRequestsPerHost < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
        }
        this.maxRequestsPerHost = maxRequestsPerHost;
        this.promoteCalls();
    }
    
    public synchronized int getMaxRequestsPerHost() {
        return this.maxRequestsPerHost;
    }
    
    public synchronized void setIdleCallback(@Nullable final Runnable idleCallback) {
        this.idleCallback = idleCallback;
    }
    
    synchronized void enqueue(final RealCall.AsyncCall call) {
        if (this.runningAsyncCalls.size() < this.maxRequests && this.runningCallsForHost(call) < this.maxRequestsPerHost) {
            this.runningAsyncCalls.add(call);
            this.executorService().execute(call);
        }
        else {
            this.readyAsyncCalls.add(call);
        }
    }
    
    public synchronized void cancelAll() {
        for (final RealCall.AsyncCall call : this.readyAsyncCalls) {
            call.get().cancel();
        }
        for (final RealCall.AsyncCall call : this.runningAsyncCalls) {
            call.get().cancel();
        }
        for (final RealCall call2 : this.runningSyncCalls) {
            call2.cancel();
        }
    }
    
    private void promoteCalls() {
        if (this.runningAsyncCalls.size() >= this.maxRequests) {
            return;
        }
        if (this.readyAsyncCalls.isEmpty()) {
            return;
        }
        final Iterator<RealCall.AsyncCall> i = this.readyAsyncCalls.iterator();
        while (i.hasNext()) {
            final RealCall.AsyncCall call = i.next();
            if (this.runningCallsForHost(call) < this.maxRequestsPerHost) {
                i.remove();
                this.runningAsyncCalls.add(call);
                this.executorService().execute(call);
            }
            if (this.runningAsyncCalls.size() >= this.maxRequests) {
                return;
            }
        }
    }
    
    private int runningCallsForHost(final RealCall.AsyncCall call) {
        int result = 0;
        for (final RealCall.AsyncCall c : this.runningAsyncCalls) {
            if (c.host().equals(call.host())) {
                ++result;
            }
        }
        return result;
    }
    
    synchronized void executed(final RealCall call) {
        this.runningSyncCalls.add(call);
    }
    
    void finished(final RealCall.AsyncCall call) {
        this.finished(this.runningAsyncCalls, call, true);
    }
    
    void finished(final RealCall call) {
        this.finished(this.runningSyncCalls, call, false);
    }
    
    private <T> void finished(final Deque<T> calls, final T call, final boolean promoteCalls) {
        final int runningCallsCount;
        final Runnable idleCallback;
        synchronized (this) {
            if (!calls.remove(call)) {
                throw new AssertionError((Object)"Call wasn't in-flight!");
            }
            if (promoteCalls) {
                this.promoteCalls();
            }
            runningCallsCount = this.runningCallsCount();
            idleCallback = this.idleCallback;
        }
        if (runningCallsCount == 0 && idleCallback != null) {
            idleCallback.run();
        }
    }
    
    public synchronized List<Call> queuedCalls() {
        final List<Call> result = new ArrayList<Call>();
        for (final RealCall.AsyncCall asyncCall : this.readyAsyncCalls) {
            result.add(asyncCall.get());
        }
        return Collections.unmodifiableList((List<? extends Call>)result);
    }
    
    public synchronized List<Call> runningCalls() {
        final List<Call> result = new ArrayList<Call>();
        result.addAll(this.runningSyncCalls);
        for (final RealCall.AsyncCall asyncCall : this.runningAsyncCalls) {
            result.add(asyncCall.get());
        }
        return Collections.unmodifiableList((List<? extends Call>)result);
    }
    
    public synchronized int queuedCallsCount() {
        return this.readyAsyncCalls.size();
    }
    
    public synchronized int runningCallsCount() {
        return this.runningAsyncCalls.size() + this.runningSyncCalls.size();
    }
}
