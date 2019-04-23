// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.platform;

import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;
import java.lang.reflect.InvocationTargetException;
import okhttp3.Protocol;
import java.util.List;
import javax.net.ssl.SSLSocket;
import java.lang.reflect.Method;

final class Jdk9Platform extends Platform
{
    final Method setProtocolMethod;
    final Method getProtocolMethod;
    
    Jdk9Platform(final Method setProtocolMethod, final Method getProtocolMethod) {
        this.setProtocolMethod = setProtocolMethod;
        this.getProtocolMethod = getProtocolMethod;
    }
    
    @Override
    public void configureTlsExtensions(final SSLSocket sslSocket, final String hostname, final List<Protocol> protocols) {
        try {
            final SSLParameters sslParameters = sslSocket.getSSLParameters();
            final List<String> names = Platform.alpnProtocolNames(protocols);
            this.setProtocolMethod.invoke(sslParameters, names.toArray(new String[names.size()]));
            sslSocket.setSSLParameters(sslParameters);
        }
        catch (IllegalAccessException | InvocationTargetException ex2) {
            final ReflectiveOperationException ex = null;
            final ReflectiveOperationException e = ex;
            throw new AssertionError();
        }
    }
    
    @Override
    public String getSelectedProtocol(final SSLSocket socket) {
        try {
            final String protocol = (String)this.getProtocolMethod.invoke(socket, new Object[0]);
            if (protocol == null || protocol.equals("")) {
                return null;
            }
            return protocol;
        }
        catch (IllegalAccessException | InvocationTargetException ex2) {
            final ReflectiveOperationException ex = null;
            final ReflectiveOperationException e = ex;
            throw new AssertionError();
        }
    }
    
    @Override
    public X509TrustManager trustManager(final SSLSocketFactory sslSocketFactory) {
        throw new UnsupportedOperationException("clientBuilder.sslSocketFactory(SSLSocketFactory) not supported on JDK 9+");
    }
    
    public static Jdk9Platform buildIfSupported() {
        try {
            final Method setProtocolMethod = SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
            final Method getProtocolMethod = SSLSocket.class.getMethod("getApplicationProtocol", (Class<?>[])new Class[0]);
            return new Jdk9Platform(setProtocolMethod, getProtocolMethod);
        }
        catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
