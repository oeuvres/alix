package alix.lucene;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.BytesRef;

/**
 * Analyzer for french. The method getOffsets is not thread safe (for
 * performance).
 * 
 * @author fred
 *
 */
public class AlixAnalyzer extends Analyzer
{
    /** Store offsets of the tokenizer, not thread safe */
    private OffsetList offsets = new OffsetList();

    public AlixAnalyzer()
    {

    }

    /**
     * Process a text to get offsets of tokens. Not thread safe.
     * 
     * @param text
     * @return
     * @throws IOException
     */
    public BytesRef getOffsets(String text) throws IOException
    {
        offsets.reset();
        TokenStream ts = this.tokenStream(Alix.OFFSETS, new StringReader(text));
        // listen to offsets
        OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
        try {
            ts.reset();
            while (ts.incrementToken()) {
                offsets.put(offsetAtt.startOffset(), offsetAtt.endOffset());
            }
            ts.end();
        }
        finally {
            ts.close();
        }
        return offsets.getBytesRef();
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
        final Tokenizer source = new FrTokenizer();
        // for offsets, no filters needed
        if (fieldName.equals(Alix.OFFSETS)) {
            return new TokenStreamComponents(new FrTokenizer());
        }
        return new TokenStreamComponents(new FrTokenizer());
    }


}
