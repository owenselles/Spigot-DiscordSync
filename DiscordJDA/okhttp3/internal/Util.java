// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal;

import okhttp3.MediaType;
import okio.BufferedSource;
import java.util.Locale;
import java.net.IDN;
import okhttp3.HttpUrl;
import java.util.concurrent.ThreadFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.io.InterruptedIOException;
import okio.Buffer;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okio.Source;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.Closeable;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.TimeZone;
import java.nio.charset.Charset;
import okio.ByteString;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class Util
{
    public static final byte[] EMPTY_BYTE_ARRAY;
    public static final String[] EMPTY_STRING_ARRAY;
    public static final ResponseBody EMPTY_RESPONSE;
    public static final RequestBody EMPTY_REQUEST;
    private static final ByteString UTF_8_BOM;
    private static final ByteString UTF_16_BE_BOM;
    private static final ByteString UTF_16_LE_BOM;
    private static final ByteString UTF_32_BE_BOM;
    private static final ByteString UTF_32_LE_BOM;
    public static final Charset UTF_8;
    private static final Charset UTF_16_BE;
    private static final Charset UTF_16_LE;
    private static final Charset UTF_32_BE;
    private static final Charset UTF_32_LE;
    public static final TimeZone UTC;
    public static final Comparator<String> NATURAL_ORDER;
    private static final Pattern VERIFY_AS_IP_ADDRESS;
    
    public static void checkOffsetAndCount(final long arrayLength, final long offset, final long count) {
        if ((offset | count) < 0L || offset > arrayLength || arrayLength - offset < count) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
    
    public static boolean equal(final Object a, final Object b) {
        return a == b || (a != null && a.equals(b));
    }
    
    public static void closeQuietly(final Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            }
            catch (RuntimeException rethrown) {
                throw rethrown;
            }
            catch (Exception ex) {}
        }
    }
    
    public static void closeQuietly(final Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (AssertionError e) {
                if (!isAndroidGetsocknameError(e)) {
                    throw e;
                }
            }
            catch (RuntimeException rethrown) {
                throw rethrown;
            }
            catch (Exception ex) {}
        }
    }
    
    public static void closeQuietly(final ServerSocket serverSocket) {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            }
            catch (RuntimeException rethrown) {
                throw rethrown;
            }
            catch (Exception ex) {}
        }
    }
    
    public static boolean discard(final Source source, final int timeout, final TimeUnit timeUnit) {
        try {
            return skipAll(source, timeout, timeUnit);
        }
        catch (IOException e) {
            return false;
        }
    }
    
    public static boolean skipAll(final Source source, final int duration, final TimeUnit timeUnit) throws IOException {
        final long now = System.nanoTime();
        final long originalDuration = source.timeout().hasDeadline() ? (source.timeout().deadlineNanoTime() - now) : Long.MAX_VALUE;
        source.timeout().deadlineNanoTime(now + Math.min(originalDuration, timeUnit.toNanos(duration)));
        try {
            final Buffer skipBuffer = new Buffer();
            while (source.read(skipBuffer, 8192L) != -1L) {
                skipBuffer.clear();
            }
            return true;
        }
        catch (InterruptedIOException e) {
            return false;
        }
        finally {
            if (originalDuration == Long.MAX_VALUE) {
                source.timeout().clearDeadline();
            }
            else {
                source.timeout().deadlineNanoTime(now + originalDuration);
            }
        }
    }
    
    public static <T> List<T> immutableList(final List<T> list) {
        return Collections.unmodifiableList((List<? extends T>)new ArrayList<T>((Collection<? extends T>)list));
    }
    
    public static <T> List<T> immutableList(final T... elements) {
        return Collections.unmodifiableList((List<? extends T>)Arrays.asList((T[])elements.clone()));
    }
    
    public static ThreadFactory threadFactory(final String name, final boolean daemon) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread result = new Thread(runnable, name);
                result.setDaemon(daemon);
                return result;
            }
        };
    }
    
    public static String[] intersect(final Comparator<? super String> comparator, final String[] first, final String[] second) {
        final List<String> result = new ArrayList<String>();
        for (final String a : first) {
            for (final String b : second) {
                if (comparator.compare(a, b) == 0) {
                    result.add(a);
                    break;
                }
            }
        }
        return result.toArray(new String[result.size()]);
    }
    
    public static boolean nonEmptyIntersection(final Comparator<String> comparator, final String[] first, final String[] second) {
        if (first == null || second == null || first.length == 0 || second.length == 0) {
            return false;
        }
        for (final String a : first) {
            for (final String b : second) {
                if (comparator.compare(a, b) == 0) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public static String hostHeader(final HttpUrl url, final boolean includeDefaultPort) {
        final String host = url.host().contains(":") ? ("[" + url.host() + "]") : url.host();
        return (includeDefaultPort || url.port() != HttpUrl.defaultPort(url.scheme())) ? (host + ":" + url.port()) : host;
    }
    
    public static String toHumanReadableAscii(final String s) {
        int c;
        for (int i = 0, length = s.length(); i < length; i += Character.charCount(c)) {
            c = s.codePointAt(i);
            if (c <= 31 || c >= 127) {
                final Buffer buffer = new Buffer();
                buffer.writeUtf8(s, 0, i);
                for (int j = i; j < length; j += Character.charCount(c)) {
                    c = s.codePointAt(j);
                    buffer.writeUtf8CodePoint((c > 31 && c < 127) ? c : 63);
                }
                return buffer.readUtf8();
            }
        }
        return s;
    }
    
    public static boolean isAndroidGetsocknameError(final AssertionError e) {
        return e.getCause() != null && e.getMessage() != null && e.getMessage().contains("getsockname failed");
    }
    
    public static int indexOf(final Comparator<String> comparator, final String[] array, final String value) {
        for (int i = 0, size = array.length; i < size; ++i) {
            if (comparator.compare(array[i], value) == 0) {
                return i;
            }
        }
        return -1;
    }
    
    public static String[] concat(final String[] array, final String value) {
        final String[] result = new String[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[result.length - 1] = value;
        return result;
    }
    
    public static int skipLeadingAsciiWhitespace(final String input, final int pos, final int limit) {
        int i = pos;
        while (i < limit) {
            switch (input.charAt(i)) {
                case '\t':
                case '\n':
                case '\f':
                case '\r':
                case ' ': {
                    ++i;
                    continue;
                }
                default: {
                    return i;
                }
            }
        }
        return limit;
    }
    
    public static int skipTrailingAsciiWhitespace(final String input, final int pos, final int limit) {
        int i = limit - 1;
        while (i >= pos) {
            switch (input.charAt(i)) {
                case '\t':
                case '\n':
                case '\f':
                case '\r':
                case ' ': {
                    --i;
                    continue;
                }
                default: {
                    return i + 1;
                }
            }
        }
        return pos;
    }
    
    public static String trimSubstring(final String string, final int pos, final int limit) {
        final int start = skipLeadingAsciiWhitespace(string, pos, limit);
        final int end = skipTrailingAsciiWhitespace(string, start, limit);
        return string.substring(start, end);
    }
    
    public static int delimiterOffset(final String input, final int pos, final int limit, final String delimiters) {
        for (int i = pos; i < limit; ++i) {
            if (delimiters.indexOf(input.charAt(i)) != -1) {
                return i;
            }
        }
        return limit;
    }
    
    public static int delimiterOffset(final String input, final int pos, final int limit, final char delimiter) {
        for (int i = pos; i < limit; ++i) {
            if (input.charAt(i) == delimiter) {
                return i;
            }
        }
        return limit;
    }
    
    public static String domainToAscii(final String input) {
        try {
            final String result = IDN.toASCII(input).toLowerCase(Locale.US);
            if (result.isEmpty()) {
                return null;
            }
            if (containsInvalidHostnameAsciiCodes(result)) {
                return null;
            }
            return result;
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    private static boolean containsInvalidHostnameAsciiCodes(final String hostnameAscii) {
        for (int i = 0; i < hostnameAscii.length(); ++i) {
            final char c = hostnameAscii.charAt(i);
            if (c <= '\u001f' || c >= '\u007f') {
                return true;
            }
            if (" #%/:?@[\\]".indexOf(c) != -1) {
                return true;
            }
        }
        return false;
    }
    
    public static int indexOfControlOrNonAscii(final String input) {
        for (int i = 0, length = input.length(); i < length; ++i) {
            final char c = input.charAt(i);
            if (c <= '\u001f' || c >= '\u007f') {
                return i;
            }
        }
        return -1;
    }
    
    public static boolean verifyAsIpAddress(final String host) {
        return Util.VERIFY_AS_IP_ADDRESS.matcher(host).matches();
    }
    
    public static String format(final String format, final Object... args) {
        return String.format(Locale.US, format, args);
    }
    
    public static Charset bomAwareCharset(final BufferedSource source, final Charset charset) throws IOException {
        if (source.rangeEquals(0L, Util.UTF_8_BOM)) {
            source.skip(Util.UTF_8_BOM.size());
            return Util.UTF_8;
        }
        if (source.rangeEquals(0L, Util.UTF_16_BE_BOM)) {
            source.skip(Util.UTF_16_BE_BOM.size());
            return Util.UTF_16_BE;
        }
        if (source.rangeEquals(0L, Util.UTF_16_LE_BOM)) {
            source.skip(Util.UTF_16_LE_BOM.size());
            return Util.UTF_16_LE;
        }
        if (source.rangeEquals(0L, Util.UTF_32_BE_BOM)) {
            source.skip(Util.UTF_32_BE_BOM.size());
            return Util.UTF_32_BE;
        }
        if (source.rangeEquals(0L, Util.UTF_32_LE_BOM)) {
            source.skip(Util.UTF_32_LE_BOM.size());
            return Util.UTF_32_LE;
        }
        return charset;
    }
    
    static {
        EMPTY_BYTE_ARRAY = new byte[0];
        EMPTY_STRING_ARRAY = new String[0];
        EMPTY_RESPONSE = ResponseBody.create(null, Util.EMPTY_BYTE_ARRAY);
        EMPTY_REQUEST = RequestBody.create(null, Util.EMPTY_BYTE_ARRAY);
        UTF_8_BOM = ByteString.decodeHex("efbbbf");
        UTF_16_BE_BOM = ByteString.decodeHex("feff");
        UTF_16_LE_BOM = ByteString.decodeHex("fffe");
        UTF_32_BE_BOM = ByteString.decodeHex("0000ffff");
        UTF_32_LE_BOM = ByteString.decodeHex("ffff0000");
        UTF_8 = Charset.forName("UTF-8");
        UTF_16_BE = Charset.forName("UTF-16BE");
        UTF_16_LE = Charset.forName("UTF-16LE");
        UTF_32_BE = Charset.forName("UTF-32BE");
        UTF_32_LE = Charset.forName("UTF-32LE");
        UTC = TimeZone.getTimeZone("GMT");
        NATURAL_ORDER = new Comparator<String>() {
            @Override
            public int compare(final String a, final String b) {
                return a.compareTo(b);
            }
        };
        VERIFY_AS_IP_ADDRESS = Pattern.compile("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)");
    }
}
