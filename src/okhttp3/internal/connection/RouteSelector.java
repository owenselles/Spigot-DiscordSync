// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.connection;

import java.net.SocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.SocketException;
import okhttp3.internal.Util;
import okhttp3.HttpUrl;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.ArrayList;
import java.util.Collections;
import okhttp3.Route;
import java.util.List;
import java.net.InetSocketAddress;
import java.net.Proxy;
import okhttp3.Address;

public final class RouteSelector
{
    private final Address address;
    private final RouteDatabase routeDatabase;
    private Proxy lastProxy;
    private InetSocketAddress lastInetSocketAddress;
    private List<Proxy> proxies;
    private int nextProxyIndex;
    private List<InetSocketAddress> inetSocketAddresses;
    private int nextInetSocketAddressIndex;
    private final List<Route> postponedRoutes;
    
    public RouteSelector(final Address address, final RouteDatabase routeDatabase) {
        this.proxies = Collections.emptyList();
        this.inetSocketAddresses = Collections.emptyList();
        this.postponedRoutes = new ArrayList<Route>();
        this.address = address;
        this.routeDatabase = routeDatabase;
        this.resetNextProxy(address.url(), address.proxy());
    }
    
    public boolean hasNext() {
        return this.hasNextInetSocketAddress() || this.hasNextProxy() || this.hasNextPostponed();
    }
    
    public Route next() throws IOException {
        if (!this.hasNextInetSocketAddress()) {
            if (!this.hasNextProxy()) {
                if (!this.hasNextPostponed()) {
                    throw new NoSuchElementException();
                }
                return this.nextPostponed();
            }
            else {
                this.lastProxy = this.nextProxy();
            }
        }
        this.lastInetSocketAddress = this.nextInetSocketAddress();
        final Route route = new Route(this.address, this.lastProxy, this.lastInetSocketAddress);
        if (this.routeDatabase.shouldPostpone(route)) {
            this.postponedRoutes.add(route);
            return this.next();
        }
        return route;
    }
    
    public void connectFailed(final Route failedRoute, final IOException failure) {
        if (failedRoute.proxy().type() != Proxy.Type.DIRECT && this.address.proxySelector() != null) {
            this.address.proxySelector().connectFailed(this.address.url().uri(), failedRoute.proxy().address(), failure);
        }
        this.routeDatabase.failed(failedRoute);
    }
    
    private void resetNextProxy(final HttpUrl url, final Proxy proxy) {
        if (proxy != null) {
            this.proxies = Collections.singletonList(proxy);
        }
        else {
            final List<Proxy> proxiesOrNull = this.address.proxySelector().select(url.uri());
            this.proxies = ((proxiesOrNull != null && !proxiesOrNull.isEmpty()) ? Util.immutableList(proxiesOrNull) : Util.immutableList(Proxy.NO_PROXY));
        }
        this.nextProxyIndex = 0;
    }
    
    private boolean hasNextProxy() {
        return this.nextProxyIndex < this.proxies.size();
    }
    
    private Proxy nextProxy() throws IOException {
        if (!this.hasNextProxy()) {
            throw new SocketException("No route to " + this.address.url().host() + "; exhausted proxy configurations: " + this.proxies);
        }
        final Proxy result = this.proxies.get(this.nextProxyIndex++);
        this.resetNextInetSocketAddress(result);
        return result;
    }
    
    private void resetNextInetSocketAddress(final Proxy proxy) throws IOException {
        this.inetSocketAddresses = new ArrayList<InetSocketAddress>();
        String socketHost;
        int socketPort;
        if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
            socketHost = this.address.url().host();
            socketPort = this.address.url().port();
        }
        else {
            final SocketAddress proxyAddress = proxy.address();
            if (!(proxyAddress instanceof InetSocketAddress)) {
                throw new IllegalArgumentException("Proxy.address() is not an InetSocketAddress: " + proxyAddress.getClass());
            }
            final InetSocketAddress proxySocketAddress = (InetSocketAddress)proxyAddress;
            socketHost = getHostString(proxySocketAddress);
            socketPort = proxySocketAddress.getPort();
        }
        if (socketPort < 1 || socketPort > 65535) {
            throw new SocketException("No route to " + socketHost + ":" + socketPort + "; port is out of range");
        }
        if (proxy.type() == Proxy.Type.SOCKS) {
            this.inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
        }
        else {
            final List<InetAddress> addresses = this.address.dns().lookup(socketHost);
            if (addresses.isEmpty()) {
                throw new UnknownHostException(this.address.dns() + " returned no addresses for " + socketHost);
            }
            for (int i = 0, size = addresses.size(); i < size; ++i) {
                final InetAddress inetAddress = addresses.get(i);
                this.inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
            }
        }
        this.nextInetSocketAddressIndex = 0;
    }
    
    static String getHostString(final InetSocketAddress socketAddress) {
        final InetAddress address = socketAddress.getAddress();
        if (address == null) {
            return socketAddress.getHostName();
        }
        return address.getHostAddress();
    }
    
    private boolean hasNextInetSocketAddress() {
        return this.nextInetSocketAddressIndex < this.inetSocketAddresses.size();
    }
    
    private InetSocketAddress nextInetSocketAddress() throws IOException {
        if (!this.hasNextInetSocketAddress()) {
            throw new SocketException("No route to " + this.address.url().host() + "; exhausted inet socket addresses: " + this.inetSocketAddresses);
        }
        return this.inetSocketAddresses.get(this.nextInetSocketAddressIndex++);
    }
    
    private boolean hasNextPostponed() {
        return !this.postponedRoutes.isEmpty();
    }
    
    private Route nextPostponed() {
        return this.postponedRoutes.remove(0);
    }
}
