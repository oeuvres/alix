package alix.lucene;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.AttributeFactory;

import alix.util.Chain;
import alix.util.Char;

/**
 * A lucene tokenizer for French, adapted fron Lucene CharTokenizer.
 * 
 * @author glorieux-f
 *
 */
public class FrTokenizer extends Tokenizer
{
    /** Current term, as an array of chars */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Wrapper on the char array of the term to test some value */
    Chain termHash = new Chain(termAtt.buffer());
    /** Tool for string testings */
    Chain chain = new Chain();
    /** A term waiting to be send */
    PendingTerm pending = new PendingTerm();
    /** Current char offset */
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
    /** French, « j’aime », break apostrophe after those words */
    public static final HashSet<String> ELLISION = new HashSet<String>();
    static {
        for (String w : new String[] { "d'", "D'", "j'", "J'", "jusqu'", "Jusqu'", "l'", "L'", "lorsqu'",
                "Lorsqu'", "m'", "M'", "n'", "N'", "puisqu'", "Puisqu'", "qu'", "Qu'", "quoiqu'", "Quoiqu'", "s'", "S'",
                "t'", "-t'", "T'" })
            ELLISION.add(w);
    }
    boolean xml = true;
    /** tags to send and translate */
    public static final HashMap<String, String> TAGS = new HashMap<String, String>();
    static {
        TAGS.put("p", "<p>");
        TAGS.put("section", "<section>");
        TAGS.put("/section", "</section>");
    }
    public FrTokenizer() {
    }
    /**
     * Handle xml tags ?
     * @param ml
     */
    public FrTokenizer(boolean xml) {
        this.xml = xml;
    }
    public FrTokenizer(AttributeFactory factory) {
        super(factory);
    }



    @Override
    public final boolean incrementToken() throws IOException
    {
        clearAttributes(); // 
        // send term event
        if (!pending.isEmpty()) {
            termAtt.append(pending.term);
            offsetAtt.setOffset(correctOffset(pending.startOffset), correctOffset(pending.endOffset));
            pending.reset();
            return true;
        }
        
        
        int length = 0;
        int startOffset = -1; // this variable is always initialized
        int endOffset = -1;
        int markOffset = -1;
        CharTermAttribute term = this.termAtt;
        Chain termHash = this.termHash;
        Chain chain = this.chain;
        char[] buffer = bufSrc.getBuffer();
        boolean tag = false;
        boolean tagname = false;
        boolean xmlent = false;
        char lastChar = 0;
        
        boolean dot;
        
        while (true) {
            // grab more chars
            if (bufIndex >= bufLen) {
                offset += bufLen;
                // use default lucene code to read chars from source 
                CharacterUtils.fill(bufSrc, input); // read supplementary char aware with CharacterUtils
                if (bufSrc.getLength() == 0) {
                    bufLen = 0; // so next offset += bufLen won't decrement offset
                    if (length > 0) {
                        break;
                    }
                    else { // finish !
                        finalOffset = correctOffset(offset);
                        return false;
                    }
                }
                bufLen = bufSrc.getLength();
                bufIndex = 0;
            }
            
            char c = buffer[bufIndex];
            bufIndex ++;
            // got a char, let's work
            
            // xml tags
            if (!xml);
            else if (c == '<') {
                // we may send a term event on some tags
                markOffset = offset + bufIndex - 1;
                tag = true;
                tagname = true;
                continue;
            }
            else if (tag) {
                if (tagname) {
                    if (!chain.isEmpty() && (c == ' ' || c == '>' || (c == '/'))) tagname = false;
                    else chain.append(c);
                }
                if (c == '>') {
                    tag = false;
                    String el = TAGS.get(chain);
                    chain.reset();
                    if(el == null) continue;
                    // A word was recorded, send it, record the tag as a pending term
                    if (length != 0) {
                        pending.set(el, markOffset, offset + bufIndex - 1);
                        break;
                    }
                    startOffset = markOffset;
                    term.append(el);
                    length = el.length();
                    endOffset = offset + bufIndex - 1;
                    break;
                }
                continue;
            }
            else if(c == '&') {
                if (length == 0) startOffset = offset + bufIndex - 1;
                xmlent = true;
                chain.reset();
                chain.append(c);
                continue;
            }
            else if(xmlent == true) {
                chain.append(c);
                if (c != ';') continue;
                // end of entity
                xmlent = false;
                c = Char.htmlent(chain);
                chain.reset();
                // char fron entity should be sended
                endOffset = offset + bufIndex - 1;
            }
            // decimals
            if (Char.isDigit(lastChar) && (c == '.' || c == ',')) {
                term.append(c);
                endOffset++;
                length++;
                continue;
            }
            // sentence punctuation
     
            
            if (Char.isToken(c)) { // if it's a token char
                if (length == 0) { // start of token
                    assert startOffset == -1;
                    startOffset = offset + bufIndex - 1;
                    endOffset = startOffset;
                }
                if (c == '’') c = '\'';
                // append char to term, if not a soft hyphen
                if (c != (char) 0xAD) {
                    term.append(c);
                    endOffset++;
                    length++;
                }
                if (c == '\'') {
                    // wrap the char array of the term in an object inplenentinc hashCode() and Comaparable
                    // because char array is changing when growing, it needs to be reaffected
                    termHash.set(term.buffer(), 0, length);
                    if(ELLISION.contains(termHash)) break;
                }
                if (length >= maxTokenLen) break; // a too big token stop
            }
            // 
            else if (length > 0) { // at non-Letter w/ chars
                break; // return 'em
            }
            lastChar = c;
        }
        // send term event
        termAtt.setLength(length);
        assert startOffset != -1;
        startOffset = correctOffset(startOffset);
        finalOffset = correctOffset(endOffset);
        offsetAtt.setOffset(startOffset, finalOffset);
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
    
    public class PendingTerm {
        private String term;
        private int startOffset;
        private int endOffset;
        private int length;
        public void set(final String term, final int startOffset, final int endOffset) {
            this.term = term;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.length = term.length();
        }
        public boolean isEmpty() {
            return (term == null);
        }
        public void reset() {
            this.term = null;
            this.startOffset = -1;
            this.endOffset = -1;
            this.length = -1;
        }
    }
    public static void main(String[] args) throws IOException
    {
        // text to tokenize
        final String text = "<p>" + "C’est m&eacute;connaître 1,5 &lt; -1.5 cts &amp; M<b>o</b>t Avec de <i>l'italique</i></section>. FIN.";

        Analyzer[] analyzers = {
            new AlixAnalyzer(),
            // new StandardAnalyzer(),
            // new FrenchAnalyzer()
        };
        for(Analyzer analyzer: analyzers) {
            System.out.println(analyzer.getClass());
            System.out.println();
            TokenStream stream = analyzer.tokenStream("field", new StringReader(text));

            // get the CharTermAttribute from the TokenStream
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute pos = stream.addAttribute(PositionIncrementAttribute.class);

            try {
                stream.reset();
                // print all tokens until stream is exhausted
                while (stream.incrementToken()) {
                    System.out.println("\"" + term + "\" " + pos.getPositionIncrement() + " " + offset.startOffset() + " "
                            + offset.endOffset());
                }
                stream.end();
            }
            finally {
                stream.close();
                analyzer.close();
            }
            System.out.println();
        }
    }
}
