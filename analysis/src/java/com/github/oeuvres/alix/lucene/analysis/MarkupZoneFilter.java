package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

import java.io.IOException;
import java.util.Objects;

/**
 * Stream filter: keeps content tokens inside elements matching includePairs,
 * and optionally suppresses content inside elements matching excludePairs.
 *
 * No DOM, no XPath engine, no per-token String allocation.
 */
public final class MarkupZoneFilter extends TokenFilter
{
    
    public static final class Pair
    {
        public final char[] name;
        public final char[] value; // non-null => exact match only
        
        public Pair(String name, String value)
        {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            this.name = name.toCharArray();
            this.value = value.toCharArray();
        }
    }
    
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    
    private final Pair[] includePairs;
    private final Pair[] excludePairs;
    
    private boolean[] incStack = new boolean[32];
    private boolean[] excStack = new boolean[32];
    private int depth = 0;
    private int includeDepth = 0;
    private int excludeDepth = 0;
    
    // Optional: if you want to preserve token position gaps when suppressing content
    private final boolean preserveContentGaps;
    private int pendingPosInc = 0;
    
    public MarkupZoneFilter(TokenStream input, Pair[] includePairs, Pair[] excludePairs)
    {
        this(input, includePairs, excludePairs, false);
    }
    
    public MarkupZoneFilter(TokenStream input, Pair[] includePairs, Pair[] excludePairs, boolean preserveContentGaps)
    {
        super(input);
        this.includePairs = includePairs != null ? includePairs : new Pair[0];
        this.excludePairs = excludePairs != null ? excludePairs : new Pair[0];
        this.preserveContentGaps = preserveContentGaps;
    }
    
    @Override
    public void reset() throws IOException
    {
        super.reset();
        depth = 0;
        includeDepth = 0;
        excludeDepth = 0;
        pendingPosInc = 0;
    }
    
    @Override
    public boolean incrementToken() throws IOException
    {
        while (input.incrementToken()) {
            
            final boolean isXml = (posAtt.getPos() == XML.code);
            
            if (isXml) {
                final char[] buf = termAtt.buffer();
                final int len = termAtt.length();
                processTag(buf, len);
                // drop tag tokens from output
                continue;
            }
            
            final boolean keep = shouldKeepContent();
            if (!keep) {
                if (preserveContentGaps) {
                    pendingPosInc += Math.max(0, posIncAtt.getPositionIncrement());
                }
                continue;
            }
            
            if (pendingPosInc != 0) {
                posIncAtt.setPositionIncrement(posIncAtt.getPositionIncrement() + pendingPosInc);
                pendingPosInc = 0;
            }
            if (posIncAtt.getPositionIncrement() <= 0) {
                posIncAtt.setPositionIncrement(1);
            }
            return true;
        }
        return false;
    }
    
    private boolean shouldKeepContent()
    {
        if (excludeDepth > 0)
            return false;
        if (includePairs.length == 0)
            return true;
        return includeDepth > 0;
    }
    
    private void processTag(char[] tag, int n)
    {
        if (n < 3 || tag[0] != '<')
            return;
        
        char c1 = tag[1];
        // ignore PI, comments, doctype, CDATA
        if (c1 == '?' || c1 == '!')
            return;
        
        // end tag
        if (c1 == '/') {
            pop();
            return;
        }
        
        // start tag
        final boolean selfClosing = isSelfClosing(tag, n);
        
        final boolean inc = (includePairs.length != 0) && matchesAnyPair(tag, n, includePairs);
        final boolean exc = (excludePairs.length != 0) && matchesAnyPair(tag, n, excludePairs);
        
        push(inc, exc);
        
        if (selfClosing) {
            pop();
        }
    }
    
    private static boolean isSelfClosing(char[] tag, int n)
    {
        // Detect "/>" ignoring whitespace before '>'
        int i = n - 2; // before last '>'
        while (i > 0) {
            char ch = tag[i];
            if (isSpace(ch)) {
                i--;
                continue;
            }
            return ch == '/';
        }
        return false;
    }
    
    private void push(boolean inc, boolean exc)
    {
        if (depth == incStack.length) {
            int newCap = depth << 1;
            boolean[] ni = new boolean[newCap];
            boolean[] ne = new boolean[newCap];
            System.arraycopy(incStack, 0, ni, 0, depth);
            System.arraycopy(excStack, 0, ne, 0, depth);
            incStack = ni;
            excStack = ne;
        }
        incStack[depth] = inc;
        excStack[depth] = exc;
        depth++;
        if (inc)
            includeDepth++;
        if (exc)
            excludeDepth++;
    }
    
    private void pop()
    {
        if (depth == 0)
            return; // tolerate mild mismatch
        depth--;
        if (incStack[depth])
            includeDepth--;
        if (excStack[depth])
            excludeDepth--;
    }
    
    /** Minimal attribute scanner; checks //*[@name="value"] for a few pairs. */
    private static boolean matchesAnyPair(char[] tag, int n, Pair[] rules)
    {
        int i = 1; // after '<'
        
        // skip element name
        while (i < n) {
            char ch = tag[i];
            if (isSpace(ch) || ch == '>' || ch == '/')
                break;
            i++;
        }
        
        while (i < n) {
            while (i < n && isSpace(tag[i]))
                i++;
            if (i >= n)
                return false;
            
            char ch = tag[i];
            if (ch == '>' || ch == '/')
                return false;
            
            // attr name [nameStart, nameEnd)
            int nameStart = i;
            while (i < n) {
                ch = tag[i];
                if (isSpace(ch) || ch == '=' || ch == '>' || ch == '/')
                    break;
                i++;
            }
            int nameEnd = i;
            
            while (i < n && isSpace(tag[i]))
                i++;
            if (i >= n || tag[i] != '=') {
                // boolean attribute or malformed; skip token
                continue;
            }
            i++; // '='
            while (i < n && isSpace(tag[i]))
                i++;
            if (i >= n)
                return false;
            
            // attr value [valStart, valEnd)
            int valStart, valEnd;
            char q = tag[i];
            if (q == '"' || q == '\'') {
                i++;
                valStart = i;
                while (i < n && tag[i] != q)
                    i++;
                valEnd = i;
                if (i < n)
                    i++; // consume quote
            } else {
                valStart = i;
                while (i < n) {
                    ch = tag[i];
                    if (isSpace(ch) || ch == '>' || ch == '/')
                        break;
                    i++;
                }
                valEnd = i;
            }
            
            // check rules (few rules => linear scan)
            for (Pair r : rules) {
                if (regionEquals(tag, nameStart, nameEnd, r.name)
                        && regionEquals(tag, valStart, valEnd, r.value))
                {
                    return true;
                }
            }
        }
        return false;
    }
    
    private static boolean isSpace(char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
    
    private static boolean regionEquals(char[] s, int start, int end, char[] needle)
    {
        int len = end - start;
        if (len != needle.length)
            return false;
        for (int k = 0; k < len; k++) {
            if (s[start + k] != needle[k])
                return false;
        }
        return true;
    }
}