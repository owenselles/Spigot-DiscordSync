// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import okio.ByteString;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.X509Certificate;
import java.security.cert.Certificate;
import java.util.List;
import okhttp3.internal.Util;
import javax.annotation.Nullable;
import okhttp3.internal.tls.CertificateChainCleaner;
import java.util.Set;

public final class CertificatePinner
{
    public static final CertificatePinner DEFAULT;
    private final Set<Pin> pins;
    @Nullable
    private final CertificateChainCleaner certificateChainCleaner;
    
    CertificatePinner(final Set<Pin> pins, @Nullable final CertificateChainCleaner certificateChainCleaner) {
        this.pins = pins;
        this.certificateChainCleaner = certificateChainCleaner;
    }
    
    @Override
    public boolean equals(@Nullable final Object other) {
        return other == this || (other instanceof CertificatePinner && Util.equal(this.certificateChainCleaner, ((CertificatePinner)other).certificateChainCleaner) && this.pins.equals(((CertificatePinner)other).pins));
    }
    
    @Override
    public int hashCode() {
        int result = (this.certificateChainCleaner != null) ? this.certificateChainCleaner.hashCode() : 0;
        result = 31 * result + this.pins.hashCode();
        return result;
    }
    
    public void check(final String hostname, List<Certificate> peerCertificates) throws SSLPeerUnverifiedException {
        final List<Pin> pins = this.findMatchingPins(hostname);
        if (pins.isEmpty()) {
            return;
        }
        if (this.certificateChainCleaner != null) {
            peerCertificates = this.certificateChainCleaner.clean(peerCertificates, hostname);
        }
        for (int c = 0, certsSize = peerCertificates.size(); c < certsSize; ++c) {
            final X509Certificate x509Certificate = (X509Certificate) peerCertificates.get(c);
            ByteString sha1 = null;
            ByteString sha2 = null;
            for (int p = 0, pinsSize = pins.size(); p < pinsSize; ++p) {
                final Pin pin = pins.get(p);
                if (pin.hashAlgorithm.equals("sha256/")) {
                    if (sha2 == null) {
                        sha2 = sha256(x509Certificate);
                    }
                    if (pin.hash.equals(sha2)) {
                        return;
                    }
                }
                else {
                    if (!pin.hashAlgorithm.equals("sha1/")) {
                        throw new AssertionError();
                    }
                    if (sha1 == null) {
                        sha1 = sha1(x509Certificate);
                    }
                    if (pin.hash.equals(sha1)) {
                        return;
                    }
                }
            }
        }
        final StringBuilder message = new StringBuilder().append("Certificate pinning failure!").append("\n  Peer certificate chain:");
        for (int c2 = 0, certsSize2 = peerCertificates.size(); c2 < certsSize2; ++c2) {
            final X509Certificate x509Certificate2 = (X509Certificate) peerCertificates.get(c2);
            message.append("\n    ").append(pin(x509Certificate2)).append(": ").append(x509Certificate2.getSubjectDN().getName());
        }
        message.append("\n  Pinned certificates for ").append(hostname).append(":");
        for (int p2 = 0, pinsSize2 = pins.size(); p2 < pinsSize2; ++p2) {
            final Pin pin2 = pins.get(p2);
            message.append("\n    ").append(pin2);
        }
        throw new SSLPeerUnverifiedException(message.toString());
    }
    
    public void check(final String hostname, final Certificate... peerCertificates) throws SSLPeerUnverifiedException {
        this.check(hostname, Arrays.asList(peerCertificates));
    }
    
    List<Pin> findMatchingPins(final String hostname) {
        List<Pin> result = Collections.emptyList();
        for (final Pin pin : this.pins) {
            if (pin.matches(hostname)) {
                if (result.isEmpty()) {
                    result = new ArrayList<Pin>();
                }
                result.add(pin);
            }
        }
        return result;
    }
    
    CertificatePinner withCertificateChainCleaner(final CertificateChainCleaner certificateChainCleaner) {
        return Util.equal(this.certificateChainCleaner, certificateChainCleaner) ? this : new CertificatePinner(this.pins, certificateChainCleaner);
    }
    
    public static String pin(final Certificate certificate) {
        if (!(certificate instanceof X509Certificate)) {
            throw new IllegalArgumentException("Certificate pinning requires X509 certificates");
        }
        return "sha256/" + sha256((X509Certificate)certificate).base64();
    }
    
    static ByteString sha1(final X509Certificate x509Certificate) {
        return ByteString.of(x509Certificate.getPublicKey().getEncoded()).sha1();
    }
    
    static ByteString sha256(final X509Certificate x509Certificate) {
        return ByteString.of(x509Certificate.getPublicKey().getEncoded()).sha256();
    }
    
    static {
        DEFAULT = new Builder().build();
    }
    
    static final class Pin
    {
        private static final String WILDCARD = "*.";
        final String pattern;
        final String canonicalHostname;
        final String hashAlgorithm;
        final ByteString hash;
        
        Pin(final String pattern, final String pin) {
            this.pattern = pattern;
            this.canonicalHostname = (pattern.startsWith("*.") ? HttpUrl.parse("http://" + pattern.substring("*.".length())).host() : HttpUrl.parse("http://" + pattern).host());
            if (pin.startsWith("sha1/")) {
                this.hashAlgorithm = "sha1/";
                this.hash = ByteString.decodeBase64(pin.substring("sha1/".length()));
            }
            else {
                if (!pin.startsWith("sha256/")) {
                    throw new IllegalArgumentException("pins must start with 'sha256/' or 'sha1/': " + pin);
                }
                this.hashAlgorithm = "sha256/";
                this.hash = ByteString.decodeBase64(pin.substring("sha256/".length()));
            }
            if (this.hash == null) {
                throw new IllegalArgumentException("pins must be base64: " + pin);
            }
        }
        
        boolean matches(final String hostname) {
            if (this.pattern.startsWith("*.")) {
                final int firstDot = hostname.indexOf(46);
                return hostname.regionMatches(false, firstDot + 1, this.canonicalHostname, 0, this.canonicalHostname.length());
            }
            return hostname.equals(this.canonicalHostname);
        }
        
        @Override
        public boolean equals(final Object other) {
            return other instanceof Pin && this.pattern.equals(((Pin)other).pattern) && this.hashAlgorithm.equals(((Pin)other).hashAlgorithm) && this.hash.equals(((Pin)other).hash);
        }
        
        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + this.pattern.hashCode();
            result = 31 * result + this.hashAlgorithm.hashCode();
            result = 31 * result + this.hash.hashCode();
            return result;
        }
        
        @Override
        public String toString() {
            return this.hashAlgorithm + this.hash.base64();
        }
    }
    
    public static final class Builder
    {
        private final List<Pin> pins;
        
        public Builder() {
            this.pins = new ArrayList<Pin>();
        }
        
        public Builder add(final String pattern, final String... pins) {
            if (pattern == null) {
                throw new NullPointerException("pattern == null");
            }
            for (final String pin : pins) {
                this.pins.add(new Pin(pattern, pin));
            }
            return this;
        }
        
        public CertificatePinner build() {
            return new CertificatePinner(new LinkedHashSet<Pin>(this.pins), null);
        }
    }
}
