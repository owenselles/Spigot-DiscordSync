// 
// Decompiled by Procyon v0.5.30
// 

package okhttp3;

import java.util.ArrayList;
import okio.Buffer;
import javax.annotation.Nullable;
import java.io.IOException;
import okio.BufferedSink;
import okhttp3.internal.Util;
import java.util.List;

public final class FormBody extends RequestBody
{
    private static final MediaType CONTENT_TYPE;
    private final List<String> encodedNames;
    private final List<String> encodedValues;
    
    FormBody(final List<String> encodedNames, final List<String> encodedValues) {
        this.encodedNames = Util.immutableList(encodedNames);
        this.encodedValues = Util.immutableList(encodedValues);
    }
    
    public int size() {
        return this.encodedNames.size();
    }
    
    public String encodedName(final int index) {
        return this.encodedNames.get(index);
    }
    
    public String name(final int index) {
        return HttpUrl.percentDecode(this.encodedName(index), true);
    }
    
    public String encodedValue(final int index) {
        return this.encodedValues.get(index);
    }
    
    public String value(final int index) {
        return HttpUrl.percentDecode(this.encodedValue(index), true);
    }
    
    @Override
    public MediaType contentType() {
        return FormBody.CONTENT_TYPE;
    }
    
    @Override
    public long contentLength() {
        return this.writeOrCountBytes(null, true);
    }
    
    @Override
    public void writeTo(final BufferedSink sink) throws IOException {
        this.writeOrCountBytes(sink, false);
    }
    
    private long writeOrCountBytes(@Nullable final BufferedSink sink, final boolean countBytes) {
        long byteCount = 0L;
        Buffer buffer;
        if (countBytes) {
            buffer = new Buffer();
        }
        else {
            buffer = sink.buffer();
        }
        for (int i = 0, size = this.encodedNames.size(); i < size; ++i) {
            if (i > 0) {
                buffer.writeByte(38);
            }
            buffer.writeUtf8((String)this.encodedNames.get(i));
            buffer.writeByte(61);
            buffer.writeUtf8((String)this.encodedValues.get(i));
        }
        if (countBytes) {
            byteCount = buffer.size();
            buffer.clear();
        }
        return byteCount;
    }
    
    static {
        CONTENT_TYPE = MediaType.parse("application/x-www-form-urlencoded");
    }
    
    public static final class Builder
    {
        private final List<String> names;
        private final List<String> values;
        
        public Builder() {
            this.names = new ArrayList<String>();
            this.values = new ArrayList<String>();
        }
        
        public Builder add(final String name, final String value) {
            this.names.add(HttpUrl.canonicalize(name, " \"':;<=>@[]^`{}|/\\?#&!$(),~", false, false, true, true));
            this.values.add(HttpUrl.canonicalize(value, " \"':;<=>@[]^`{}|/\\?#&!$(),~", false, false, true, true));
            return this;
        }
        
        public Builder addEncoded(final String name, final String value) {
            this.names.add(HttpUrl.canonicalize(name, " \"':;<=>@[]^`{}|/\\?#&!$(),~", true, false, true, true));
            this.values.add(HttpUrl.canonicalize(value, " \"':;<=>@[]^`{}|/\\?#&!$(),~", true, false, true, true));
            return this;
        }
        
        public FormBody build() {
            return new FormBody(this.names, this.values);
        }
    }
}
