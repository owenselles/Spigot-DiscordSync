// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.platform;

import okhttp3.OkHttpClient;
import java.lang.reflect.Field;
import okio.Buffer;
import okhttp3.internal.tls.BasicCertificateChainCleaner;
import okhttp3.internal.tls.TrustRootIndex;
import okhttp3.internal.tls.CertificateChainCleaner;
import java.util.ArrayList;
import java.util.logging.Level;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import okhttp3.Protocol;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLSocketFactory;
import java.util.logging.Logger;

public class Platform
{
    private static final Platform PLATFORM;
    public static final int INFO = 4;
    public static final int WARN = 5;
    private static final Logger logger;
    
    public static Platform get() {
        return Platform.PLATFORM;
    }
    
    public String getPrefix() {
        return "OkHttp";
    }
    
    public X509TrustManager trustManager(final SSLSocketFactory sslSocketFactory) {
        try {
            final Class<?> sslContextClass = Class.forName("sun.security.ssl.SSLContextImpl");
            final Object context = readFieldOrNull(sslSocketFactory, sslContextClass, "context");
            if (context == null) {
                return null;
            }
            return readFieldOrNull(context, X509TrustManager.class, "trustManager");
        }
        catch (ClassNotFoundException e) {
            return null;
        }
    }
    
    public void configureTlsExtensions(final SSLSocket sslSocket, final String hostname, final List<Protocol> protocols) {
    }
    
    public void afterHandshake(final SSLSocket sslSocket) {
    }
    
    public String getSelectedProtocol(final SSLSocket socket) {
        return null;
    }
    
    public void connectSocket(final Socket socket, final InetSocketAddress address, final int connectTimeout) throws IOException {
        socket.connect(address, connectTimeout);
    }
    
    public void log(final int level, final String message, final Throwable t) {
        final Level logLevel = (level == 5) ? Level.WARNING : Level.INFO;
        Platform.logger.log(logLevel, message, t);
    }
    
    public boolean isCleartextTrafficPermitted(final String hostname) {
        return true;
    }
    
    public Object getStackTraceForCloseable(final String closer) {
        if (Platform.logger.isLoggable(Level.FINE)) {
            return new Throwable(closer);
        }
        return null;
    }
    
    public void logCloseableLeak(String message, final Object stackTrace) {
        if (stackTrace == null) {
            message += " To see where this was allocated, set the OkHttpClient logger level to FINE: Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);";
        }
        this.log(5, message, (Throwable)stackTrace);
    }
    
    public static List<String> alpnProtocolNames(final List<Protocol> protocols) {
        final List<String> names = new ArrayList<String>(protocols.size());
        for (int i = 0, size = protocols.size(); i < size; ++i) {
            final Protocol protocol = protocols.get(i);
            if (protocol != Protocol.HTTP_1_0) {
                names.add(protocol.toString());
            }
        }
        return names;
    }
    
    public CertificateChainCleaner buildCertificateChainCleaner(final X509TrustManager trustManager) {
        return new BasicCertificateChainCleaner(TrustRootIndex.get(trustManager));
    }
    
    private static Platform findPlatform() {
        final Platform jdk9 = Jdk9Platform.buildIfSupported();
        if (jdk9 != null) {
            return jdk9;
        }
        final Platform jdkWithJettyBoot = JdkWithJettyBootPlatform.buildIfSupported();
        if (jdkWithJettyBoot != null) {
            return jdkWithJettyBoot;
        }
        return new Platform();
    }
    
    static byte[] concatLengthPrefixed(final List<Protocol> protocols) {
        final Buffer result = new Buffer();
        for (int i = 0, size = protocols.size(); i < size; ++i) {
            final Protocol protocol = protocols.get(i);
            if (protocol != Protocol.HTTP_1_0) {
                result.writeByte(protocol.toString().length());
                result.writeUtf8(protocol.toString());
            }
        }
        return result.readByteArray();
    }
    
    static <T> T readFieldOrNull(final Object instance, final Class<T> fieldType, final String fieldName) {
        for (Class<?> c = instance.getClass(); c != Object.class; c = c.getSuperclass()) {
            try {
                final Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                final Object value = field.get(instance);
                if (value == null || !fieldType.isInstance(value)) {
                    return null;
                }
                return fieldType.cast(value);
            }
            catch (NoSuchFieldException ex) {}
            catch (IllegalAccessException e) {
                throw new AssertionError();
            }
        }
        if (!fieldName.equals("delegate")) {
            final Object delegate = readFieldOrNull(instance, Object.class, "delegate");
            if (delegate != null) {
                return (T)readFieldOrNull(delegate, (Class<Object>)fieldType, fieldName);
            }
        }
        return null;
    }
    
    static {
        PLATFORM = findPlatform();
        logger = Logger.getLogger(OkHttpClient.class.getName());
    }
}
