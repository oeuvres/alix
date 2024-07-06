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

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;

public class XmlTokenizer  extends Tokenizer
{

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
    /** Start of a term */
    private int startOffset;
    /** End of a term */
    private int endOffset;
    
    @Override
    public boolean incrementToken() throws IOException
    {
        clearAttributes();
        boolean intag = false;
        while (true) {
            // needs more chars ?
            if (bufferIndex >= bufferLen) {
                // use default lucene code to read chars from source
                CharacterUtils.fill(buffer, input); // read supplementary char aware with CharacterUtils
                bufferLen = buffer.getLength();
                bufferIndex = 0;
                // end of stream
                if (bufferLen == 0) {
                    return false;
                }
            }
            char[] chars = buffer.getBuffer();
            // got a char, let's work
            char c = chars[bufferIndex];
            
            // start of tag
            if (c == '<') {
                // send pending term, come back next call
                if (!termAtt.isEmpty()) break;
                intag = true;
                startOffset = offset + 1;
            }
            // inside a tag, record char
            if (intag) {
                bufferIndex++;
                offset++;
                termAtt.append(c);
                if (c == '>') { // end of tag, send it
                    endOffset = offset;
                    flagsAtt.setFlags(Tag.XML.flag);
                    break;
                }
            }
            
            
        }
        // here, a term should be ready, send it
        posIncAtt.setPositionIncrement(1);
        posLenAtt.setPositionLength(1);
        offsetAtt.setOffset(correctOffset(startOffset), correctOffset(endOffset));
        return true;
    }
    
    @Override
    public final void end() throws IOException
    {
        super.end();
        // set final offset ?
        // offsetAtt.setOffset(finalOffset, finalOffset);
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
