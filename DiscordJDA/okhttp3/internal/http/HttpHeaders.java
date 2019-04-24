// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3.internal.http;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.CookieJar;
import java.util.regex.Matcher;
import java.util.ArrayList;
import okhttp3.Challenge;
import java.util.List;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.Collections;
import java.util.Set;
import java.util.Iterator;
import okhttp3.internal.Util;
import okhttp3.Request;
import okhttp3.Headers;
import okhttp3.Response;
import java.util.regex.Pattern;

public final class HttpHeaders
{
    private static final String TOKEN = "([^ \"=]*)";
    private static final String QUOTED_STRING = "\"([^\"]*)\"";
    private static final Pattern PARAMETER;
    
    public static long contentLength(final Response response) {
        return contentLength(response.headers());
    }
    
    public static long contentLength(final Headers headers) {
        return stringToLong(headers.get("Content-Length"));
    }
    
    private static long stringToLong(final String s) {
        if (s == null) {
            return -1L;
        }
        try {
            return Long.parseLong(s);
        }
        catch (NumberFormatException e) {
            return -1L;
        }
    }
    
    public static boolean varyMatches(final Response cachedResponse, final Headers cachedRequest, final Request newRequest) {
        for (final String field : varyFields(cachedResponse)) {
            if (!Util.equal(cachedRequest.values(field), newRequest.headers(field))) {
                return false;
            }
        }
        return true;
    }
    
    public static boolean hasVaryAll(final Response response) {
        return hasVaryAll(response.headers());
    }
    
    public static boolean hasVaryAll(final Headers responseHeaders) {
        return varyFields(responseHeaders).contains("*");
    }
    
    private static Set<String> varyFields(final Response response) {
        return varyFields(response.headers());
    }
    
    public static Set<String> varyFields(final Headers responseHeaders) {
        Set<String> result = Collections.emptySet();
        for (int i = 0, size = responseHeaders.size(); i < size; ++i) {
            if ("Vary".equalsIgnoreCase(responseHeaders.name(i))) {
                final String value = responseHeaders.value(i);
                if (result.isEmpty()) {
                    result = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
                }
                for (final String varyField : value.split(",")) {
                    result.add(varyField.trim());
                }
            }
        }
        return result;
    }
    
    public static Headers varyHeaders(final Response response) {
        final Headers requestHeaders = response.networkResponse().request().headers();
        final Headers responseHeaders = response.headers();
        return varyHeaders(requestHeaders, responseHeaders);
    }
    
    public static Headers varyHeaders(final Headers requestHeaders, final Headers responseHeaders) {
        final Set<String> varyFields = varyFields(responseHeaders);
        if (varyFields.isEmpty()) {
            return new Headers.Builder().build();
        }
        final Headers.Builder result = new Headers.Builder();
        for (int i = 0, size = requestHeaders.size(); i < size; ++i) {
            final String fieldName = requestHeaders.name(i);
            if (varyFields.contains(fieldName)) {
                result.add(fieldName, requestHeaders.value(i));
            }
        }
        return result.build();
    }
    
    public static List<Challenge> parseChallenges(final Headers responseHeaders, final String challengeHeader) {
        final List<Challenge> challenges = new ArrayList<Challenge>();
        final List<String> authenticationHeaders = responseHeaders.values(challengeHeader);
        for (final String header : authenticationHeaders) {
            final int index = header.indexOf(32);
            if (index == -1) {
                continue;
            }
            final Matcher matcher = HttpHeaders.PARAMETER.matcher(header);
            for (int i = index; matcher.find(i); i = matcher.end()) {
                if (header.regionMatches(true, matcher.start(1), "realm", 0, 5)) {
                    final String scheme = header.substring(0, index);
                    final String realm = matcher.group(3);
                    if (realm != null) {
                        challenges.add(new Challenge(scheme, realm));
                        break;
                    }
                }
            }
        }
        return challenges;
    }
    
    public static void receiveHeaders(final CookieJar cookieJar, final HttpUrl url, final Headers headers) {
        if (cookieJar == CookieJar.NO_COOKIES) {
            return;
        }
        final List<Cookie> cookies = Cookie.parseAll(url, headers);
        if (cookies.isEmpty()) {
            return;
        }
        cookieJar.saveFromResponse(url, cookies);
    }
    
    public static boolean hasBody(final Response response) {
        if (response.request().method().equals("HEAD")) {
            return false;
        }
        final int responseCode = response.code();
        return ((responseCode < 100 || responseCode >= 200) && responseCode != 204 && responseCode != 304) || (contentLength(response) != -1L || "chunked".equalsIgnoreCase(response.header("Transfer-Encoding")));
    }
    
    public static int skipUntil(final String input, int pos, final String characters) {
        while (pos < input.length() && characters.indexOf(input.charAt(pos)) == -1) {
            ++pos;
        }
        return pos;
    }
    
    public static int skipWhitespace(final String input, int pos) {
        while (pos < input.length()) {
            final char c = input.charAt(pos);
            if (c != ' ' && c != '\t') {
                break;
            }
            ++pos;
        }
        return pos;
    }
    
    public static int parseSeconds(final String value, final int defaultValue) {
        try {
            final long seconds = Long.parseLong(value);
            if (seconds > 2147483647L) {
                return Integer.MAX_VALUE;
            }
            if (seconds < 0L) {
                return 0;
            }
            return (int)seconds;
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    static {
        PARAMETER = Pattern.compile(" +([^ \"=]*)=(:?\"([^\"]*)\"|([^ \"=]*)) *(:?,|$)");
    }
}
