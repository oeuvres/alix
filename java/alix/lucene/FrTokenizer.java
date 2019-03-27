package alix.lucene;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Random;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.util.CharTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.AttributeFactory;
import org.apache.lucene.util.Version;

import alix.util.Char;
import alix.util.IntList;

/**
 * A lucene tokenizer for French, 
 * 
 * @author glorieux-f
 *
 */
public class FrTokenizer extends Tokenizer
{
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** Source buffer of chars, delegate to Lucene experts */
    private final CharacterBuffer bufSrc = CharacterUtils.newCharacterBuffer(4096);
    /** Pointer in buffer */
    private int bufIndex = 0;
    /** Length of the buffer */
    private int bufLen = 0;
    /** Max length for a token */
    int maxTokenLen = 256;
    /** Input offset */
    private int offset = 0;
    /** Final input offset */
    private int finalOffset = 0;
    /** A growable array of ints to record offset of each tokens */
    ByteIntList offsets;
    /** French, « vois-tu » hyphen is breakable before these words, except: arc-en-ciel */
    public static final HashSet<String> HYPHEN_POST = new HashSet<String>();
    static {
        for (String w : new String[] { "-ce", "-ci", "-elle", "-elles", "-en", "-eux", "-il", "-ils", "-je", "-la",
                "-là", "-le", "-les", "-leur", "-lui", "-me", "-moi", "-nous", "-on", "-t", "-t-", "-te", "-toi", "-tu",
                "-vous", "-y" })
            HYPHEN_POST.add(w);
    }
    /** French, « j’aime », break apostrophe after those words */
    public static final HashSet<String> ELLISION = new HashSet<String>();
    static {
        for (String w : new String[] { "c'", "C'", "d'", "D'", "j'", "J'", "jusqu'", "Jusqu'", "l'", "L'", "lorsqu'",
                "Lorsqu'", "m'", "M'", "n'", "N'", "puisqu'", "Puisqu'", "qu'", "Qu'", "quoiqu'", "Quoiqu'", "s'", "S'",
                "t'", "-t'", "T'" })
            ELLISION.add(w);
    }

    boolean tag;
    
    public FrTokenizer() {
    }
    public FrTokenizer(AttributeFactory factory) {
        super(factory);
    }
    public FrTokenizer(ByteIntList offsets) {
        this.offsets = offsets;
        offsets.reset();
    }

    /**
     * The simple parser
     */
    protected boolean isTokenChar(int c)
    {
        /*
        if (this.tag && '>' == c) {
            this.tag = false;
            return false;
        }
        if ('<' == c) {
            this.tag = true;
            return false;
        }
        if (this.tag) {
            return false;
        }
        */
        return Char.isToken(c);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        clearAttributes();
        int length = 0;
        int start = -1; // this variable is always initialized
        int end = -1;
        char[] term = termAtt.buffer();
        // grab chars to build term
        while (true) {
            if (bufIndex >= bufLen) {
                offset += bufLen;
                // use default lucene code to read chars from source 
                CharacterUtils.fill(bufSrc, input); // read supplementary char aware with CharacterUtils
                if (bufSrc.getLength() == 0) {
                    bufLen = 0; // so next offset += bufLen won't decrement offset
                    if (length > 0) {
                        break;
                    }
                    else {
                        finalOffset = correctOffset(offset);
                        return false;
                    }
                }
                bufLen = bufSrc.getLength();
                bufIndex = 0;
            }
            // use CharacterUtils here to support < 3.1 UTF-16 code unit behavior if the
            // char based methods are gone
            final int c = Character.codePointAt(bufSrc.getBuffer(), bufIndex, bufSrc.getLength());
            final int charCount = Character.charCount(c);
            bufIndex += charCount;

            if (isTokenChar(c)) { // if it's a token char
                if (length == 0) { // start of token
                    assert start == -1;
                    start = offset + bufIndex - charCount;
                    end = start;
                }
                else if (length >= term.length - 1) { // check if a supplementary could run out of bounds
                    term = termAtt.resizeBuffer(2 + length); // make sure a supplementary fits in the buffer
                }
                end += charCount;
                length += Character.toChars(c, term, length); // buffer it, normalized
                if (length >= maxTokenLen) break; // a too big term, stop
            }
            // 
            else if (length > 0) { // at non-Letter w/ chars
                break; // return 'em
            }
        }
        // send term event
        termAtt.setLength(length);
        assert start != -1;
        start = correctOffset(start);
        finalOffset = correctOffset(end);
        // populate the offsets index
        if(offsets != null) {
            offsets.put(start).put(finalOffset);
        }
        // 
        // offsets.push(finalOffset);
        offsetAtt.setOffset(start, finalOffset);
        return true;

    }
    @Override
    public final void end() throws IOException {
      super.end();
      // set final offset
      offsetAtt.setOffset(finalOffset, finalOffset);
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      bufIndex = 0;
      offset = 0;
      bufLen = 0;
      finalOffset = 0;
      bufSrc.reset(); // make sure to reset the IO buffer!!
    }
}
