// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import javax.annotation.Nullable;

public final class Challenge
{
    private final String scheme;
    private final String realm;
    
    public Challenge(final String scheme, final String realm) {
        if (scheme == null) {
            throw new NullPointerException("scheme == null");
        }
        if (realm == null) {
            throw new NullPointerException("realm == null");
        }
        this.scheme = scheme;
        this.realm = realm;
    }
    
    public String scheme() {
        return this.scheme;
    }
    
    public String realm() {
        return this.realm;
    }
    
    @Override
    public boolean equals(@Nullable final Object other) {
        return other instanceof Challenge && ((Challenge)other).scheme.equals(this.scheme) && ((Challenge)other).realm.equals(this.realm);
    }
    
    @Override
    public int hashCode() {
        int result = 29;
        result = 31 * result + this.realm.hashCode();
        result = 31 * result + this.scheme.hashCode();
        return result;
    }
    
    @Override
    public String toString() {
        return this.scheme + " realm=\"" + this.realm + "\"";
    }
}
