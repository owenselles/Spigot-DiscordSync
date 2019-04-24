// 
// Decompiled by Procyon v0.5.30
// 

package okio;

import java.util.RandomAccess;
import java.util.AbstractList;

public final class Options extends AbstractList<ByteString> implements RandomAccess
{
    final ByteString[] byteStrings;
    
    private Options(final ByteString[] byteStrings) {
        this.byteStrings = byteStrings;
    }
    
    public static Options of(final ByteString... byteStrings) {
        return new Options(byteStrings.clone());
    }
    
    @Override
    public ByteString get(final int i) {
        return this.byteStrings[i];
    }
    
    @Override
    public int size() {
        return this.byteStrings.length;
    }
}
