package com.github.oeuvres.alix.lucene.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

/** Fast tokenizer for input where each line is already a token. */
public final class LineTokenizer extends Tokenizer
{
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offAtt = addAttribute(OffsetAttribute.class);

    private BufferedReader br;
    private int offset;

    @Override
    public void reset() throws IOException
    {
        super.reset();
        Reader r = this.input;
        this.br = (r instanceof BufferedReader) ? (BufferedReader) r : new BufferedReader(r);
        this.offset = 0;
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        clearAttributes();
        String line = br.readLine();
        if (line == null)
            return false;

        // Drop empty lines (optional). If you want to preserve them as tokens, remove
        // this loop.
        while (line != null && line.isEmpty()) {
            offset++; // accounts for newline
            line = br.readLine();
            if (line == null)
                return false;
        }

        termAtt.setEmpty().append(line);

        int start = offset;
        int end = start + line.length();
        offAtt.setOffset(start, end);

        // +1 for '\n' (works for '\r\n' too; offsets are not used here anyway)
        offset = end + 1;
        return true;
    }
}
