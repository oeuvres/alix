package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeFactory;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.AttributeFactoryAlix;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.util.Char;

/**
 * A tokenizer for latin script languages and possible XML like tags. Tags are kept in token stream for further analysis.
 * A {@link FlagsAttribute} is set with an int define in {@link Tag}.
 *
 * <pre>
 * &lt;p&gt;            XML
 * &lt;b&gt;            XML
 * Lexical
 * tokenization
 * &lt;/b&gt;           XML
 * is
 * conversion
 * of
 * a
 * text
 * into
 * (              PUNclause
 * semantically
 * or
 * syntactically
 * )              PUNclause
 * meaningful
 * &lt;i&gt;            XML
 * lexical
 * tokens
 * &lt;/i&gt;           XML
 * belonging
 * to
 * categories
 * defined
 * by
 * a
 * lexer
 * program
 * .              PUNphrase
 * In
 * case
 * of
 * a
 * &lt;a href="/wiki/Natural_language" title="Natural language"&gt;    XML
 * natural
 * language
 * &lt;/a&gt;           XML
 * ,              PUNclause
 * those
 * categories
 * include
 * nouns
 * ,              PUNclause
 * verbs
 * ,              PUNclause
 * adjectives
 * ,              PUNclause
 * punctuations
 * etc
 * </pre>
 * 
 */
public class TokenizerML  extends Tokenizer
{
    /** Max size of a word */
    private final int TOKEN_MAX_SIZE = 256;
    /** The term provided by the Tokenizer */
    private final CharsAttImpl termAtt = (CharsAttImpl) addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** A linguistic category as a short number, see {@link Tag} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Buffer of chars */
    private final CharacterBuffer buffer = CharacterUtils.newCharacterBuffer(8192);
    /** Position in buffer */
    private int bufferIndex = 0;
    /** size of buffer*/
    private int  bufferLen = 0;
    /** current char offset */
    private int offset = 0;
    
    /**
     * Build a Tokenizer for Markup tagged text.
     */
    public TokenizerML() {
        super(new AttributeFactoryAlix(AttributeFactory.DEFAULT_ATTRIBUTE_FACTORY));
    }
    
    @Override
    public final boolean incrementToken() throws IOException
    {
        clearAttributes();
        // flags
        boolean intag = false;
        boolean number = false;
        
        // Mandatory start of a term
        int startOffset = -1;
        char lastChar;
        char c = 0;
        int endOffset;
        while (true) {
            endOffset = offset;
            // needs more chars ? start (-1, -1). End (0, 0).
            if (bufferIndex >= bufferLen) {
                // use default lucene code to read chars from source
                CharacterUtils.fill(buffer, input); // read supplementary char aware with CharacterUtils
                bufferLen = buffer.getLength();
                bufferIndex = 0;
                // end of stream
                if (bufferLen == 0) {
                    // last term to send, 
                    if (!termAtt.isEmpty()) {
                        break;
                    }
                    return false;
                }
            }
            // if no luck, a try to go back in buffer can fall in negative
            if (bufferIndex < 0) {
                // bufferIndex = 0;
            }
            lastChar = c;
            c = buffer.getBuffer()[bufferIndex];
            // default, go next
            
            // start of a tag, do not advance cursors
            if (c == '<') {
                // send pending term, come back next call
                if (!termAtt.isEmpty()) {
                    break;
                }
                intag = true;
                startOffset = offset;
                termAtt.append(c);
            }
            // inside a tag, record char
            else if (intag) {
                termAtt.append(c);
                if (c == '>') { // end of tag, send it, set for next char
                    flagsAtt.setFlags(Tag.XML.flag);
                    endOffset = offset + 1; // position of '>' + 1
                    bufferIndex++;
                    offset++;
                    break;
                }
            }
            // num
            else if (Char.isDigit(c)) {
                if (termAtt.isEmpty()) {
                    number = true;
                    flagsAtt.setFlags(Tag.NUM.flag());
                    startOffset = offset;
                }
                // start a negative number
                if (termAtt.length() == 1 && lastChar == '-') {
                    number = true;
                    flagsAtt.setFlags(Tag.NUM.flag());
                }
                // if not a number started, will be appended to something else
                termAtt.append(c);
            }
            // decimal ?
            else if (number && (c == '.' || c == ',') && lastChar != '.' && lastChar != ',') {
                termAtt.append(c);
            }
            // end of number, send, and go back on next call
            else if (number && !Char.isDigit(c)) {
                number = false;
                // dot or comma after number was punctuation
                if (lastChar == '.' || lastChar == ',') {
                    termAtt.setLength(termAtt.length() - 1);
                    bufferIndex--;
                    offset--;
                }
                break;
            }
            // if a letter, sure we add it 
            else if (Char.isToken(c)) {
                // start of token, record startOffset
                if (termAtt.isEmpty()) {
                    startOffset = offset;
                }

                if (c == '’') c = '\''; // normalize apos
                if (c != (char) 0xAD) { // soft hyphen, do not append to term
                    termAtt.append(c);
                }
                // something went wrong in loops or it is not a text with space like 
                if (termAtt.length() >= TOKEN_MAX_SIZE) {
                    bufferIndex++;
                    offset++;
                    break; // a too big token stop
                }
            }
            // abbreviation ?
            else if (c == '.' && Char.isLetter(lastChar) && (
                   termAtt.length() == 1 // M., p., probably abbr
                || (termAtt.length() > 2 && termAtt.charAt(termAtt.length()-2) == '.') // U.S.S.R.
            )) {
                termAtt.append(c);
            }
            // Clause punctuation, send a punctuation event to separate tokens
            else if (',' == c || ';' == c || ':' == c || '(' == c || ')' == c || '—' == c || '–' == c 
                    || '"' == c || '«' == c || '»' == c ) {
                // send pending term, come back next call, 
                if (!termAtt.isEmpty()) {
                    break;
                }
                // send the punctuation
                termAtt.append(c);
                startOffset = offset;
                endOffset = offset + 1;
                flagsAtt.setFlags(Tag.PUNclause.flag);
                bufferIndex++;
                offset++;
                break;
            }
            // Possible sentence delimiters
            else if (c == '.' || c == '…' || c == '?' || c == '!' ) {
                // if pending word, send, and come back later
                if (!termAtt.isEmpty() && lastChar != '.' && lastChar != '?' && lastChar != '!') {
                    break;
                }
                // append punctuation and wait for space to send (???, !!!, ...)
                if (termAtt.isEmpty()) {
                    flagsAtt.setFlags(Tag.PUNsent.flag);
                    startOffset = offset;
                }
                termAtt.append(c);
            }
            // not token char, token to send
            else if (!termAtt.isEmpty()) {
                break;
            }
            bufferIndex++;
            offset++;
        }
        // here, a term should be ready, send it
        posIncAtt.setPositionIncrement(1);
        posLenAtt.setPositionLength(1);
        offsetAtt.setOffset(correctOffset(startOffset), correctOffset(endOffset));
        // do not increment buffer and offset here, ex: word<endOfWord/>, break is done on '<'
        return true;
    }
    
    @Override
    public final void end() throws IOException
    {
        super.end();
        // set final offset ?
        offsetAtt.setOffset(correctOffset(offset), correctOffset(offset));
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        bufferIndex = 0;
        bufferLen = 0;
        offset = 0;
        buffer.reset(); // make sure to reset the IO buffer!!
    }
    

}