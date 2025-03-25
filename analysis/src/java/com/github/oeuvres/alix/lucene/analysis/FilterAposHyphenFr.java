package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.util.HashMap;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.common.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
/**
 * A filter that decomposes words on a list of suffixes and prefixes, mainly to handle 
 * hyphenation and apostrophe ellision in French. The original token is broken and lost,
 * offset are precisely kept, so that word counting and stats are not biased by multiple
 * words on same positions.
 * 
 * Known side effect : qu’en-dira-t-on, donne-m’en, emmène-m’y.
 */
public class FilterAposHyphenFr extends TokenFilter
{
    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Char index in source text. */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** A linguistic category as a short number, see {@link TagFr} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Stack of stored states */
    private final AttLinkedList deque = new AttLinkedList();
    
    
    /** Ellisions prefix */
    static final public HashMap<CharsAttImpl, CharsAttImpl> PREFIX = new HashMap<CharsAttImpl, CharsAttImpl>((int) (30 / 0.75));
    static { // ellisions
        put(PREFIX, "d'", "de"); // keep ' for locution, like d’abord
        put(PREFIX, "D'", "de");
        put(PREFIX, "j'", "je"); // j’aime.
        put(PREFIX, "J'", "je");
        put(PREFIX, "jusqu'", "jusque");
        put(PREFIX, "jusqu'", "jusque");
        put(PREFIX, "l'", "l'"); // je l’aime. le ou la
        put(PREFIX, "L'", "l'");
        put(PREFIX, "lorsqu'", "lorsque");
        put(PREFIX, "Lorsqu'", "lorsque");
        put(PREFIX, "m'", "me"); // il m’aime.
        put(PREFIX, "M'", "me");
        put(PREFIX, "n'", "ne"); // N’y va pas.
        put(PREFIX, "N'", "ne");
        put(PREFIX, "puisqu'", "puisque");
        put(PREFIX, "Puisqu'", "puisque");
        put(PREFIX, "qu'", "que");
        put(PREFIX, "Qu'", "que");
        put(PREFIX, "quelqu'", "quelque");
        put(PREFIX, "Quelqu'", "quelque");
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
    

    /**
     * Default constructor.
     * @param input previous filter.
     */
    protected FilterAposHyphenFr(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        final CharsAttImpl test = new CharsAttImpl();
        // check if a term has been stored from last call
        if (!deque.isEmpty()) {
            deque.removeFirst(termAtt, offsetAtt);
        }
        else {
            if (!input.incrementToken()) {
                // end of stream
                return false;
            }
        }
        // do not try to split in XML tags
        if (flagsAtt.getFlags() == Tag.XML.no) {
            return true;
        }
        int loop = 0;
        while (true) {
            if (++loop > 10) {
                throw new IOException("AposHyph décon: " + deque);
            }
            char[] chars = termAtt.buffer();
            int hyphLast = termAtt.length() - 1;
            for (; hyphLast >= 0; hyphLast--) {
                if ('-' == chars[hyphLast]) break;
            }
            int aposFirst = 0;
            for (; aposFirst < termAtt.length(); aposFirst++) {
                if ('\'' == chars[aposFirst]) break;
            }
            if (aposFirst >= termAtt.length()) aposFirst = -1;

            if (aposFirst < 0 && hyphLast < 0) {
                // no changes
                return true;
            }
            // apos is last char, let it run, maybe maths A', D'
            if ((aposFirst + 1) == termAtt.length()) {
                return true;
            }
            // hyphen is first char, let it run, maybe linguistic -suffix
            if (hyphLast == 0) {
                return true;
            }
            // test prefixes
            if (aposFirst > 0) {
                test.wrap(termAtt.buffer(), 0, aposFirst + 1);
                final int startOffset = offsetAtt.startOffset();
                if (PREFIX.containsKey(test)) {
                    /*
                    final CharsAttImpl value = PREFIX.get(test);
                    if (value == null) {
                        // skip this prefix, retry to find something
                        termAtt.copyBuffer(termAtt.buffer(), aposFirst + 1, termAtt.length() - aposFirst - 1);
                        offsetAtt.setOffset(startOffset + aposFirst + 1, offsetAtt.endOffset());
                        continue;
                    }
                    */
                    // keep term after prefix for next call
                    deque.addLast(
                        termAtt.buffer(), 
                        aposFirst + 1, 
                        termAtt.length() - aposFirst - 1,
                        startOffset + aposFirst + 1,
                        offsetAtt.endOffset()
                    );
                    // send the prefix
                    termAtt.setLength(aposFirst + 1);
                    offsetAtt.setOffset(startOffset, startOffset + aposFirst + 1);
                    return true;
                }
            }
            if (hyphLast > 0) {
                // test suffix
                test.wrap(termAtt.buffer(), hyphLast, termAtt.length() - hyphLast);
                if (SUFFIX.containsKey(test)) {
                    final CharsAttImpl value = SUFFIX.get(test);
                    // if value is not skipped, add it at start in stack
                    if (value != null) {
                        deque.addFirst(value, offsetAtt.startOffset()+hyphLast, offsetAtt.endOffset());
                    }
                    // set term without suffix, let work the loop
                    offsetAtt.setOffset(offsetAtt.startOffset(), offsetAtt.startOffset() + hyphLast);
                    termAtt.setLength(hyphLast);
                    continue;
                }
            }
            return true; // term is OK like that
        }
    }
    
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
