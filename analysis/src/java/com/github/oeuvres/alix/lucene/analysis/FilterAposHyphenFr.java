package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import static com.github.oeuvres.alix.common.Upos.*;
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
    /** A linguistic category as a short number, see {@link Upos} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Stack of stored states */
    private final AttLinkedList deque = new AttLinkedList();
    
    
    /** Ellisions prefix */
    static CharArrayMap<char[]> PREFIX = new CharArrayMap<>(30, false);
    static { // ellisions
        PREFIX.put("d'", "de".toCharArray());
        PREFIX.put("d'", "de".toCharArray()); // keep ' for locution, like d’abord
        PREFIX.put("D'", "de".toCharArray());
        PREFIX.put("j'", "je".toCharArray()); // j’aime.
        PREFIX.put("J'", "je".toCharArray());
        PREFIX.put("jusqu'", "jusque".toCharArray());
        PREFIX.put("Jusqu'", "jusque".toCharArray());
        PREFIX.put("l'", "l'".toCharArray()); // je l’aime. le ou la
        PREFIX.put("L'", "l'".toCharArray());
        PREFIX.put("lorsqu'", "lorsque".toCharArray());
        PREFIX.put("Lorsqu'", "lorsque".toCharArray());
        PREFIX.put("m'", "me".toCharArray()); // il m’aime.
        PREFIX.put("M'", "me".toCharArray());
        PREFIX.put("n'", "ne".toCharArray()); // N’y va pas.
        PREFIX.put("N'", "ne".toCharArray());
        PREFIX.put("puisqu'", "puisque".toCharArray());
        PREFIX.put("Puisqu'", "puisque".toCharArray());
        PREFIX.put("qu'", "que".toCharArray());
        PREFIX.put("Qu'", "que".toCharArray());
        PREFIX.put("quelqu'", "quelque".toCharArray());
        PREFIX.put("Quelqu'", "quelque".toCharArray());
        PREFIX.put("quoiqu'", "quoique".toCharArray());
        PREFIX.put("Quoiqu'", "quoique".toCharArray());
        PREFIX.put("s'", "se".toCharArray()); // il s’aime.
        PREFIX.put("S'", "se".toCharArray());
        PREFIX.put("t'", "te".toCharArray()); // il t’aime.
        PREFIX.put("T'", "te".toCharArray());
    }
    // https://fr.wikipedia.org/wiki/Emploi_du_trait_d%27union_pour_les_pr%C3%A9fixes_en_fran%C3%A7ais
    /** Hyphen suffixes */
    static final CharArrayMap<char[]> SUFFIX = new CharArrayMap<>(30, false);
    static {
        SUFFIX.put("-ce", "ce".toCharArray()); // Serait-ce ?
        SUFFIX.put("-ci", null); // cette année-ci, ceux-ci.
        SUFFIX.put("-elle", "elle".toCharArray()); // dit-elle.
        SUFFIX.put("-elles", "elles".toCharArray()); // disent-elles.
        SUFFIX.put("-en", "en".toCharArray()); // parlons-en.
        SUFFIX.put("-eux", "eux".toCharArray()); // 
        SUFFIX.put("-il", "il".toCharArray()); // dit-il.
        SUFFIX.put("-ils", "ils".toCharArray()); // disent-ils.
        SUFFIX.put("-je", "je".toCharArray()); // dis-je.
        SUFFIX.put("-la", "la".toCharArray()); // prends-la !
        SUFFIX.put("-là", null); // cette année-là, ceux-là.
        SUFFIX.put("-le", "le".toCharArray()); // rends-le !
        SUFFIX.put("-les", "les".toCharArray()); // rends-les !
        SUFFIX.put("-leur", "leur".toCharArray()); // rends-leur !
        SUFFIX.put("-lui", "lui".toCharArray()); // rends-leur !
        SUFFIX.put("-me", "me".toCharArray()); // laissez-moi !
        SUFFIX.put("-moi", "moi".toCharArray()); // laissez-moi !
        SUFFIX.put("-nous", "nous".toCharArray()); // laisse-nous.
        SUFFIX.put("-on", "on".toCharArray()); // laisse-nous.
        SUFFIX.put("-t", null); // habite-t-il ici ?
        SUFFIX.put("-te", "te".toCharArray()); // 
        SUFFIX.put("-toi", "toi".toCharArray()); // 
        SUFFIX.put("-tu", "tu".toCharArray()); // viendras-tu ?
        SUFFIX.put("-vous", "vous".toCharArray()); // voulez-vous ?
        SUFFIX.put("-y", "y".toCharArray()); // allons-y.
    }

    

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterAposHyphenFr(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
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
        if (flagsAtt.getFlags() == XML.code) {
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
                if (chars[aposFirst] == '’') chars[aposFirst] = '\'';
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
                final int startOffset = offsetAtt.startOffset();
                if (PREFIX.containsKey(termAtt.buffer(), 0, aposFirst + 1)) {
                    final char[] value = PREFIX.get(termAtt.buffer(), 0, aposFirst + 1);
                    /* Strip prefix ?
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
                    termAtt.copyBuffer(value, 0, value.length);
                    termAtt.setLength(aposFirst + 1);
                    offsetAtt.setOffset(startOffset, startOffset + aposFirst + 1);
                    return true;
                }
            }
            if (hyphLast > 0) {
                // test suffix
                if (SUFFIX.containsKey(termAtt.buffer(), hyphLast, termAtt.length() - hyphLast)) {
                    final char[] value = SUFFIX.get(termAtt.buffer(), hyphLast, termAtt.length() - hyphLast);
                    // if value is not skipped, add it at start in stack
                    if (value != null) {
                        deque.addFirst(
                            value, 
                            0, 
                            value.length,
                            offsetAtt.startOffset()+hyphLast,
                            offsetAtt.endOffset()
                        );
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
