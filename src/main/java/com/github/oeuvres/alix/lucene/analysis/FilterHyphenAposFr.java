package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

/**
 * A filter that decomposes words on a list of suffixes and prefixes, mainly to handle 
 * hyphenation and apostrophe ellision in French. The original token is broken and lost,
 * offset are precisely kept, so that word counting and stats are not biased by multiple
 * words on same positions.
 * 
 * Known side effect : qu’en-dira-t-on, donne-m’en, emmène-m’y.
 */
public class FilterHyphenAposFr extends TokenFilter
{
    /** Term from tokenizer. */
    private final CharsAttImpl termAtt = (CharsAttImpl) addAttribute(CharTermAttribute.class);
    /** Char index in source text. */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** Stack of stored states */
    private final AttStack stack = new AttStack(6);
    
    
    /** Ellisions prefix */
    static final public HashMap<CharsAttImpl, CharsAttImpl> PREFIX = new HashMap<CharsAttImpl, CharsAttImpl>((int) (30 / 0.75));
    static { // ellisions
        put(PREFIX, "d'", "de"); // vient d’où ?
        put(PREFIX, "D'", "de");
        put(PREFIX, "j'", "je"); // j’aime.
        put(PREFIX, "J'", "je");
        put(PREFIX, "l'", "l'"); // je l’aime. le ou la
        put(PREFIX, "L'", "l'");
        put(PREFIX, "lorsqu'", "lorsque");
        put(PREFIX, "Lorsqu'", "Lorsque");
        put(PREFIX, "m'", "me"); // il m’aime.
        put(PREFIX, "M'", "me");
        put(PREFIX, "n'", "ne"); // N’y va pas.
        put(PREFIX, "N'", "ne");
        put(PREFIX, "puisqu'", "puisque");
        put(PREFIX, "Puisqu'", "puisque");
        put(PREFIX, "qu'", "que");
        put(PREFIX, "Qu'", "que");
        put(PREFIX, "quoiqu'", "quoique");
        put(PREFIX, "Quoiqu'", "quoique");
        put(PREFIX, "s'", "se"); // il s’aime.
        put(PREFIX, "S'", "se");
        put(PREFIX, "t'", "te"); // il t’aime.
        put(PREFIX, "T'", "te");
    }
    // https://fr.wikipedia.org/wiki/Emploi_du_trait_d%27union_pour_les_pr%C3%A9fixes_en_fran%C3%A7ais
    /** Hyphen suffixes */
    static final public HashMap<CharsAttImpl, CharsAttImpl> SUFFIX = new HashMap<CharsAttImpl, CharsAttImpl>((int) (30 / 0.75));
    static {
        put(SUFFIX, "-ce", "ce"); // Serait-ce ?
        put(SUFFIX, "-ci", null); // cette année-ci, ceux-ci.
        put(SUFFIX, "-elle", "elle"); // dit-elle.
        put(SUFFIX, "-elles", "elles"); // disent-elles.
        put(SUFFIX, "-en", "en"); // parlons-en.
        put(SUFFIX, "-eux", "eux"); // 
        put(SUFFIX, "-il", "il"); // dit-il.
        put(SUFFIX, "-ils", "ils"); // disent-ils.
        put(SUFFIX, "-je", "je"); // dis-je.
        put(SUFFIX, "-la", "la"); // prends-la !
        put(SUFFIX, "-là", null); // cette année-là, ceux-là.
        put(SUFFIX, "-le", "le"); // rends-le !
        put(SUFFIX, "-les", "les"); // rends-les !
        put(SUFFIX, "-leur", "leur"); // rends-leur !
        put(SUFFIX, "-lui", "lui"); // rends-leur !
        put(SUFFIX, "-me", "me"); // laissez-moi !
        put(SUFFIX, "-moi", "moi"); // laissez-moi !
        put(SUFFIX, "-nous", "nous"); // laisse-nous.
        put(SUFFIX, "-on", "on"); // laisse-nous.
        put(SUFFIX, "-t", null); // habite-t-il ici ?
        put(SUFFIX, "-te", "te"); // 
        put(SUFFIX, "-toi", "toi"); // 
        put(SUFFIX, "-tu", "tu"); // viendras-tu ?
        put(SUFFIX, "-vous", "vous"); // voulez-vous ?
        put(SUFFIX, "-y", "y"); // allons-y.
    }
    private static final void put(final HashMap<CharsAttImpl, CharsAttImpl> dic, final String key, final String value)
    {
        if (value == null) {
            dic.put(new CharsAttImpl(key), null);
        }
        else {
            dic.put(new CharsAttImpl(key), new CharsAttImpl(value));
        }
    }
    

    
    protected FilterHyphenAposFr(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        final CharsAttImpl test = new CharsAttImpl();
        // check if a term has been stored from last call
        if (termStore.length() > 0) {
            termAtt.copy(termStore);
            offsetAtt.setOffset(startOffset, endOffset);
        }
        else {
            if (!input.incrementToken()) {
                // end of stream
                return false;
            }
        }
        while (true) {
            final int hyphPos = termAtt.lastIndexOf('-');
            final int aposPos = termAtt.indexOf('\'');
            if (hyphPos < 0 && aposPos < 0) {
                // no changes
                return true;
            }
            // apos is last char, let it run, maybe maths A', D'
            if ((aposPos + 1) == termAtt.length()) {
                return true;
            }
            // apos is first char, let it run, maybe linguistic -suffix
            if (hyphPos == 0) {
                return true;
            }
            // test prefixes
            test.copy(termAtt, 0, aposPos + 1);
            if (PREFIX.containsKey(test)) {
                final CharsAttImpl value = PREFIX.get(test);
                if (value == null) {
                    // skip this prefix
                    termAtt.copy(termAtt, aposPos + 1, termAtt.length() - aposPos - 1);
                    final int startOffset = offsetAtt.startOffset();
                    offsetAtt.setOffset(startOffset, offsetAtt.endOffset());
                    continue;
                }
                final int startOffset = offsetAtt.startOffset();
                // keep term after prefix for next call
                stack.push(
                    termAtt.buffer(), 
                    aposPos + 1, 
                    termAtt.length() - aposPos - 1,
                    startOffset + aposPos + 1,
                    offsetAtt.endOffset()
                );
                termAtt.copy(value);
                offsetAtt.setOffset(startOffset, startOffset + aposPos + 1);
                return true;
            }
            test.copy(termAtt, hyphPos, termAtt.length() - hyphPos);
            if (SUFFIX.containsKey(test)) {
                final CharsAttImpl value = PREFIX.get(test);
                
                if (value == null) {
                    
                }
            }
        }
    }
    
    /**
     * 
     */

    @Override
    public void reset() throws IOException
    {
        super.reset();
    }

    @Override
    public void end() throws IOException
    {
        super.end();
    }

}
