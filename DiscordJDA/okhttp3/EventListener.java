// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.net.InetAddress;
import java.util.List;

abstract class EventListener
{
    public static final EventListener NONE;
    
    static Factory factory(final EventListener listener) {
        return new Factory() {
            @Override
            public EventListener create(final Call call) {
                return listener;
            }
        };
    }
    
    public void fetchStart(final Call call) {
    }
    
    public void dnsStart(final Call call, final String domainName) {
    }
    
    public void dnsEnd(final Call call, final String domainName, final List<InetAddress> inetAddressList, final Throwable throwable) {
    }
    
    public void connectStart(final Call call, final InetAddress address, final int port) {
    }
    
    public void secureConnectStart(final Call call) {
    }
    
    public void secureConnectEnd(final Call call, final Handshake handshake, final Throwable throwable) {
    }
    
    public void connectEnd(final Call call, final InetAddress address, final int port, final String protocol, final Throwable throwable) {
    }
    
    public void requestHeadersStart(final Call call) {
    }
    
    public void requestHeadersEnd(final Call call, final Throwable throwable) {
    }
    
    public void requestBodyStart(final Call call) {
    }
    
    public void requestBodyEnd(final Call call, final Throwable throwable) {
    }
    
    public void responseHeadersStart(final Call call) {
    }
    
    public void responseHeadersEnd(final Call call, final Throwable throwable) {
    }
    
    public void responseBodyStart(final Call call) {
    }
    
    public void responseBodyEnd(final Call call, final Throwable throwable) {
    }
    
    public void fetchEnd(final Call call, final Throwable throwable) {
    }
    
    static {
        NONE = new EventListener() {};
    }
    
    public interface Factory
    {
        EventListener create(final Call p0);
    }
}
