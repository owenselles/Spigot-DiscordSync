// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.tls;

import java.security.PublicKey;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.security.cert.TrustAnchor;
import java.lang.reflect.Method;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public abstract class TrustRootIndex
{
    public abstract X509Certificate findByIssuerAndSignature(final X509Certificate p0);
    
    public static TrustRootIndex get(final X509TrustManager trustManager) {
        try {
            final Method method = trustManager.getClass().getDeclaredMethod("findTrustAnchorByIssuerAndSignature", X509Certificate.class);
            method.setAccessible(true);
            return new AndroidTrustRootIndex(trustManager, method);
        }
        catch (NoSuchMethodException e) {
            return get(trustManager.getAcceptedIssuers());
        }
    }
    
    public static TrustRootIndex get(final X509Certificate... caCerts) {
        return new BasicTrustRootIndex(caCerts);
    }
    
    static final class AndroidTrustRootIndex extends TrustRootIndex
    {
        private final X509TrustManager trustManager;
        private final Method findByIssuerAndSignatureMethod;
        
        AndroidTrustRootIndex(final X509TrustManager trustManager, final Method findByIssuerAndSignatureMethod) {
            this.findByIssuerAndSignatureMethod = findByIssuerAndSignatureMethod;
            this.trustManager = trustManager;
        }
        
        @Override
        public X509Certificate findByIssuerAndSignature(final X509Certificate cert) {
            try {
                final TrustAnchor trustAnchor = (TrustAnchor)this.findByIssuerAndSignatureMethod.invoke(this.trustManager, cert);
                return (trustAnchor != null) ? trustAnchor.getTrustedCert() : null;
            }
            catch (IllegalAccessException e) {
                throw new AssertionError();
            }
            catch (InvocationTargetException e2) {
                return null;
            }
        }
        
        @Override
        public boolean equals(final Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof AndroidTrustRootIndex)) {
                return false;
            }
            final AndroidTrustRootIndex that = (AndroidTrustRootIndex)obj;
            return this.trustManager.equals(that.trustManager) && this.findByIssuerAndSignatureMethod.equals(that.findByIssuerAndSignatureMethod);
        }
        
        @Override
        public int hashCode() {
            return this.trustManager.hashCode() + 31 * this.findByIssuerAndSignatureMethod.hashCode();
        }
    }
    
    static final class BasicTrustRootIndex extends TrustRootIndex
    {
        private final Map<X500Principal, Set<X509Certificate>> subjectToCaCerts;
        
        BasicTrustRootIndex(final X509Certificate... caCerts) {
            this.subjectToCaCerts = new LinkedHashMap<X500Principal, Set<X509Certificate>>();
            for (final X509Certificate caCert : caCerts) {
                final X500Principal subject = caCert.getSubjectX500Principal();
                Set<X509Certificate> subjectCaCerts = this.subjectToCaCerts.get(subject);
                if (subjectCaCerts == null) {
                    subjectCaCerts = new LinkedHashSet<X509Certificate>(1);
                    this.subjectToCaCerts.put(subject, subjectCaCerts);
                }
                subjectCaCerts.add(caCert);
            }
        }
        
        @Override
        public X509Certificate findByIssuerAndSignature(final X509Certificate cert) {
            final X500Principal issuer = cert.getIssuerX500Principal();
            final Set<X509Certificate> subjectCaCerts = this.subjectToCaCerts.get(issuer);
            if (subjectCaCerts == null) {
                return null;
            }
            for (final X509Certificate caCert : subjectCaCerts) {
                final PublicKey publicKey = caCert.getPublicKey();
                try {
                    cert.verify(publicKey);
                    return caCert;
                }
                catch (Exception ex) {
                    continue;
                }
            }
            return null;
        }
        
        @Override
        public boolean equals(final Object other) {
            return other == this || (other instanceof BasicTrustRootIndex && ((BasicTrustRootIndex)other).subjectToCaCerts.equals(this.subjectToCaCerts));
        }
        
        @Override
        public int hashCode() {
            return this.subjectToCaCerts.hashCode();
        }
    }
}
