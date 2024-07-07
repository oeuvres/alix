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
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeFactory;
import org.apache.lucene.util.AttributeImpl;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;
import com.github.oeuvres.alix.util.Char;

public class XMLTokenizer  extends Tokenizer
{
    /** Max size of a word */
    private final int TOKEN_MAX_SIZE = 256;
    /** The term provided by the Tokenizer */
    private final CharsAtt termAtt = (CharsAtt) addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** A linguistic category as a short number, see {@link Tag} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Buffer of chars */
    private final CharacterBuffer buffer = CharacterUtils.newCharacterBuffer(8192);
    /** Position in buffer */
    private int bufferIndex;
    /** size of buffer*/
    private int  bufferLen;
    /** current char offset */
    private int offset;
    
    /**
     * Build a Tokenizer for French with possible flags
     * 
     * @param flags {@link #XML} | {@link #SEARCH}
     */
    public XMLTokenizer() {
        super(new AlixAttributeFactory(AttributeFactory.DEFAULT_ATTRIBUTE_FACTORY));
    }
    
    @Override
    public boolean incrementToken() throws IOException
    {
        clearAttributes();
        boolean intag = false;
        // Mandatory start of a term
        int startOffset = offset;
        // Optional end of term if offset is wrong
        int endOffset = -1;
        char lastChar = 0;
        char c = 0;
        CharsAtt test = new CharsAtt();
        while (true) {
            // needs more chars ?
            if (bufferIndex >= bufferLen) {
                // use default lucene code to read chars from source
                CharacterUtils.fill(buffer, input); // read supplementary char aware with CharacterUtils
                bufferLen = buffer.getLength();
                bufferIndex = 0;
                // end of stream
                if (bufferLen == 0) {
                    // last term to send
                    if (!termAtt.isEmpty()) {
                        offset++;
                        break;
                    }
                    return false;
                }
            }
            lastChar = c;
            c = buffer.getBuffer()[bufferIndex];
            // default, go next
            bufferIndex++;
            offset++;
            
            // start of a tag, do not advance cursors
            if (c == '<') {
                // send pending term, come back next call
                if (!termAtt.isEmpty()) {
                    bufferIndex--;
                    offset--;
                    endOffset = offset; // position of '<'
                    break;
                }
                intag = true;
                startOffset = offset - 1;
            }
            // inside a tag, record char
            if (intag) {
                termAtt.append(c);
                if (c == '>') { // end of tag, send it
                    flagsAtt.setFlags(Tag.XML.flag);
                    endOffset = offset; // position of '>' + 1
                    break;
                }
                continue;
            }
            
            
            // if a letter, sure we add it 
            if (Char.isToken(c)) {
                // start of token, record startOffset
                if (termAtt.isEmpty()) {
                    startOffset = offset - 1;
                }

                if (c == (char) 0xAD) continue; // soft hyphen, do not append to term
                if (c == '’') c = '\''; // normalize apos
                termAtt.append(c);

                /* TODO
                // Is hyphen breakable?
                if (hyphOffset > 0 && c != '-')
                    test.append(c);
                */
                
                // Is apos breakable?
                if (c == '\'') {
                    CharsAtt val = FrDics.ELISION.get(termAtt);
                    if (val != null) {
                        val.copyTo(termAtt);
                        break;
                    }
                }
                // something went wrong in loops or it is not a text with space, for example
                if (termAtt.length() >= TOKEN_MAX_SIZE)
                    break; // a too big token stop
                // default, go next char
                continue;
            }
            
            // decimal number
            if (Char.isDigit(lastChar) && (c == '.' || c == ',')) {
                termAtt.append(c);
                continue;
            }
            
            // abbreviation ?
            if (c == '.' && Char.isLetter(lastChar)) {
                // M. probably abbr
                if (Char.isUpperCase(lastChar)) {
                    termAtt.append(c);
                    continue;
                }
                test.copy(termAtt).append(c);
                // dictionary of abbr
                if (FrDics.brevidot(test)) {
                    flagsAtt.setFlags(Tag.ABBR.flag);
                    break; // keep dot
                }
                // probably end of sentence, useful to interpret capitalization
                // send pending term, come back next call
                bufferIndex--;
                offset--;
                endOffset = offset; // position of '.'
                break;
            }
            
            // Clause punctuation, send a punctuation event to separate tokens
            if (Char.isPUNcl(c)) {
                // send pending term, come back next call
                if (!termAtt.isEmpty()) {
                    bufferIndex--;
                    offset--;
                    endOffset = offset; // position of ','
                    break;
                }
                startOffset = offset - 1;
                endOffset = offset; // position of ','
                termAtt.append(c);
                flagsAtt.setFlags(Tag.PUNcl.flag);
                break;
            }
            
            // Possible sentence delimiters
            if (c == '.' || c == '…' || c == '?' || c == '!' || c == '«' || c == '—' || c == ':') {
                // if pending word, send, and come back later
                if (Char.isLetter(lastChar)) {
                    bufferIndex--;
                    offset--;
                    endOffset = offset;
                    break;
                }
                // append punctuation and wait for space to send (???, !!!)
                if (termAtt.isEmpty()) {
                    flagsAtt.setFlags(Tag.PUNsent.flag);
                    startOffset = offset - 1;
                }
                termAtt.append(c);
                continue; // !!!, ..., ???
            }
            
            // no word to append, go next char
            if (termAtt.isEmpty()) {
                continue;
            }
            break;
        }
        // here, a term should be ready, send it
        posIncAtt.setPositionIncrement(1);
        posLenAtt.setPositionLength(1);
        if (endOffset < 1) endOffset = offset - 1;
        offsetAtt.setOffset(correctOffset(startOffset), correctOffset(endOffset));


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
    
    /**
     * An attribute factory
     */
    private static final class AlixAttributeFactory extends AttributeFactory
    {
        private final AttributeFactory delegate;

        public AlixAttributeFactory(AttributeFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public AttributeImpl createAttributeInstance(Class<? extends Attribute> attClass)
        {
            if (attClass == CharTermAttribute.class)
                return new CharsAtt();
            return delegate.createAttributeInstance(attClass);
        }
    }
}
