// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import okio.ByteString;
import java.nio.charset.Charset;

public final class Credentials
{
    public static String basic(final String userName, final String password) {
        return basic(userName, password, Charset.forName("ISO-8859-1"));
    }
    
    public static String basic(final String userName, final String password, final Charset charset) {
        final String usernameAndPassword = userName + ":" + password;
        final byte[] bytes = usernameAndPassword.getBytes(charset);
        final String encoded = ByteString.of(bytes).base64();
        return "Basic " + encoded;
    }
}
