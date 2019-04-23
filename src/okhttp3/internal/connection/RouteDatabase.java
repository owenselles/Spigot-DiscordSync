// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.connection;

import java.util.LinkedHashSet;
import okhttp3.Route;
import java.util.Set;

public final class RouteDatabase
{
    private final Set<Route> failedRoutes;
    
    public RouteDatabase() {
        this.failedRoutes = new LinkedHashSet<Route>();
    }
    
    public synchronized void failed(final Route failedRoute) {
        this.failedRoutes.add(failedRoute);
    }
    
    public synchronized void connected(final Route route) {
        this.failedRoutes.remove(route);
    }
    
    public synchronized boolean shouldPostpone(final Route route) {
        return this.failedRoutes.contains(route);
    }
}
