// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.platform;

import okhttp3.internal.Util;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import okhttp3.Protocol;
import java.util.List;
import javax.net.ssl.SSLSocket;
import java.lang.reflect.Method;

class JdkWithJettyBootPlatform extends Platform
{
    private final Method putMethod;
    private final Method getMethod;
    private final Method removeMethod;
    private final Class<?> clientProviderClass;
    private final Class<?> serverProviderClass;
    
    JdkWithJettyBootPlatform(final Method putMethod, final Method getMethod, final Method removeMethod, final Class<?> clientProviderClass, final Class<?> serverProviderClass) {
        this.putMethod = putMethod;
        this.getMethod = getMethod;
        this.removeMethod = removeMethod;
        this.clientProviderClass = clientProviderClass;
        this.serverProviderClass = serverProviderClass;
    }
    
    @Override
    public void configureTlsExtensions(final SSLSocket sslSocket, final String hostname, final List<Protocol> protocols) {
        final List<String> names = Platform.alpnProtocolNames(protocols);
        try {
            final Object provider = Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class[] { this.clientProviderClass, this.serverProviderClass }, new JettyNegoProvider(names));
            this.putMethod.invoke(null, sslSocket, provider);
        }
        catch (InvocationTargetException | IllegalAccessException ex2) {
            final ReflectiveOperationException ex = null;
            final ReflectiveOperationException e = ex;
            throw new AssertionError((Object)e);
        }
    }
    
    @Override
    public void afterHandshake(final SSLSocket sslSocket) {
        try {
            this.removeMethod.invoke(null, sslSocket);
        }
        catch (IllegalAccessException | InvocationTargetException ex2) {
            final ReflectiveOperationException ex = null;
            final ReflectiveOperationException ignored = ex;
            throw new AssertionError();
        }
    }
    
    @Override
    public String getSelectedProtocol(final SSLSocket socket) {
        try {
            final JettyNegoProvider provider = (JettyNegoProvider)Proxy.getInvocationHandler(this.getMethod.invoke(null, socket));
            if (!provider.unsupported && provider.selected == null) {
                Platform.get().log(4, "ALPN callback dropped: HTTP/2 is disabled. Is alpn-boot on the boot class path?", null);
                return null;
            }
            return provider.unsupported ? null : provider.selected;
        }
        catch (InvocationTargetException | IllegalAccessException ex2) {
            final ReflectiveOperationException ex = null;
            final ReflectiveOperationException e = ex;
            throw new AssertionError();
        }
    }
    
    public static Platform buildIfSupported() {
        try {
            final String negoClassName = "org.eclipse.jetty.alpn.ALPN";
            final Class<?> negoClass = Class.forName(negoClassName);
            final Class<?> providerClass = Class.forName(negoClassName + "$Provider");
            final Class<?> clientProviderClass = Class.forName(negoClassName + "$ClientProvider");
            final Class<?> serverProviderClass = Class.forName(negoClassName + "$ServerProvider");
            final Method putMethod = negoClass.getMethod("put", SSLSocket.class, providerClass);
            final Method getMethod = negoClass.getMethod("get", SSLSocket.class);
            final Method removeMethod = negoClass.getMethod("remove", SSLSocket.class);
            return new JdkWithJettyBootPlatform(putMethod, getMethod, removeMethod, clientProviderClass, serverProviderClass);
        }
        catch (ClassNotFoundException | NoSuchMethodException ex) {
            return null;
        }
    }
    
    private static class JettyNegoProvider implements InvocationHandler
    {
        private final List<String> protocols;
        boolean unsupported;
        String selected;
        
        JettyNegoProvider(final List<String> protocols) {
            this.protocols = protocols;
        }
        
        @Override
        public Object invoke(final Object proxy, final Method method, Object[] args) throws Throwable {
            final String methodName = method.getName();
            final Class<?> returnType = method.getReturnType();
            if (args == null) {
                args = Util.EMPTY_STRING_ARRAY;
            }
            if (methodName.equals("supports") && Boolean.TYPE == returnType) {
                return true;
            }
            if (methodName.equals("unsupported") && Void.TYPE == returnType) {
                this.unsupported = true;
                return null;
            }
            if (methodName.equals("protocols") && args.length == 0) {
                return this.protocols;
            }
            if ((methodName.equals("selectProtocol") || methodName.equals("select")) && String.class == returnType && args.length == 1 && args[0] instanceof List) {
                final List<String> peerProtocols = (List<String>)args[0];
                for (int i = 0, size = peerProtocols.size(); i < size; ++i) {
                    if (this.protocols.contains(peerProtocols.get(i))) {
                        return this.selected = peerProtocols.get(i);
                    }
                }
                return this.selected = this.protocols.get(0);
            }
            if ((methodName.equals("protocolSelected") || methodName.equals("selected")) && args.length == 1) {
                this.selected = (String)args[0];
                return null;
            }
            return method.invoke(this, args);
        }
    }
}
