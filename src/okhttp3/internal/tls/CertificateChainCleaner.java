// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.tls;

import java.security.cert.X509Certificate;
import okhttp3.internal.platform.Platform;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.Certificate;
import java.util.List;

public abstract class CertificateChainCleaner
{
    public abstract List<Certificate> clean(final List<Certificate> p0, final String p1) throws SSLPeerUnverifiedException;
    
    public static CertificateChainCleaner get(final X509TrustManager trustManager) {
        return Platform.get().buildCertificateChainCleaner(trustManager);
    }
    
    public static CertificateChainCleaner get(final X509Certificate... caCerts) {
        return new BasicCertificateChainCleaner(TrustRootIndex.get(caCerts));
    }
}
