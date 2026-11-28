package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;
import static com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchCliticSplitFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/** Tests before-token boundary propagation without statistical analysis. */
public class MarkupBoundaryFilterTest
{
    /** Term and POS together with the boundary immediately before the term. */
    private record Token(String term, Upos pos, int boundary)
    {
        /**
         * Creates an unannotated source token.
         * @param term token form
         * @param pos grammatical annotation
         */
        Token(String term, Upos pos)
        {
            this(term, pos, NONE);
        }
    }

    /** Stream output, including the boundary exposed at end of input. */
    private record Result(List<Token> tokens, int trailing) {}

    /**
     * Checks forward propagation through removed punctuation and excluded words.
     * @throws IOException if a token stream fails
     */
    @Test
    public void cleanup() throws IOException
    {
        final CharArraySet rejected = new CharArraySet(List.of("et"), false);
        try (TokenStream stream = new CleanupFilter(new MarkupBoundaryFilter(new Fixture(
                new Token("enfant", NOUN), new Token(".", PUNCTsent),
                new Token("»", PUNCT), new Token("et", CCONJ),
                new Token("objet", NOUN), new Token("</p>", XML))),
                "word2vec", null, rejected)) {
            assertEquals(new Result(List.of(new Token("enfant", NOUN),
                new Token("objet", NOUN, SENTENCE)), PARAGRAPH), read(stream));
        }
    }

    /**
     * Checks that an elided sentence-initial word receives one boundary,
     * not a copy on each of its components.
     * @throws IOException if a token stream fails
     */
    @Test
    public void clitics() throws IOException
    {
        try (TokenStream stream = new MarkupBoundaryFilter(new FrenchCliticSplitFilter(
                new Fixture(new Token("enfant", TOKEN), new Token(".", PUNCTsent),
                    new Token("l'enfant", TOKEN), new Token("comprend", TOKEN))))) {
            assertEquals(new Result(List.of(new Token("enfant", TOKEN),
                new Token(".", PUNCTsent), new Token("l'", TOKEN, SENTENCE),
                new Token("enfant", TOKEN), new Token("comprend", TOKEN)), NONE), read(stream));
        }
    }

    /**
     * Checks punctuation runs, coalesced closing tags, trailing boundaries,
     * structural line breaks, and repeatability on reset.
     * @throws IOException if a token stream fails
     */
    @Test
    public void punctuationRuns() throws IOException
    {
        try (TokenStream stream = new MarkupBoundaryFilter(new Fixture(
                new Token("<p>", XML), new Token("enfant", NOUN),
                new Token(".", PUNCTsent), new Token("»", PUNCT),
                new Token("</p>", XML), new Token("</section>", XML),
                new Token("<p>", XML), new Token("«", PUNCT),
                new Token("il", PRON), new Token("↵↵", PUNCTstruct),
                new Token("comprend", VERB), new Token(".", PUNCTsent),
                new Token("</p>", XML)))) {
            final Result expected = new Result(List.of(new Token("enfant", NOUN),
                new Token(".", PUNCTsent), new Token("»", PUNCT),
                new Token("§", PUNCTsection), new Token("«", PUNCT),
                new Token("il", PRON, SECTION), new Token("↵↵", PUNCTstruct),
                new Token("comprend", VERB), new Token(".", PUNCTsent),
                new Token("¶", PUNCTpara)), PARAGRAPH);
            assertEquals(expected, read(stream));
            assertEquals(expected, read(stream));
        }
    }

    /**
     * Reads all emitted tokens and the terminal boundary.
     * @param stream stream to consume
     * @return token annotations and final boundary
     * @throws IOException if reading fails
     */
    private static Result read(final TokenStream stream) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final PosAttribute pos = stream.addAttribute(PosAttribute.class);
        final BoundaryAttribute boundary = stream.addAttribute(BoundaryAttribute.class);
        final List<Token> tokens = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            tokens.add(new Token(term.toString(), Upos.get(pos.getPos()), boundary.getBoundary()));
        }
        stream.end();
        return new Result(tokens, boundary.getBoundary());
    }

    /** Deterministic source with ordinary positions and offsets. */
    private static final class Fixture extends TokenStream
    {
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final PosAttribute pos = addAttribute(PosAttribute.class);
        private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
        private final PositionIncrementAttribute increment = addAttribute(PositionIncrementAttribute.class);
        private final Token[] tokens;
        private int index;
        private int cursor;

        /**
         * Creates the source.
         * @param tokens tokens in input order
         */
        Fixture(final Token... tokens)
        {
            this.tokens = tokens;
        }

        /**
         * Emits the next source annotation.
         * @return true if another token exists
         */
        @Override
        public boolean incrementToken()
        {
            if (index == tokens.length) return false;
            clearAttributes();
            final Token token = tokens[index++];
            term.append(token.term());
            pos.setPos(token.pos().ordinal());
            increment.setPositionIncrement(1);
            offset.setOffset(cursor, cursor + term.length());
            cursor += term.length() + 1;
            return true;
        }

        /**
         * Resets the source for reuse.
         * @throws IOException if the stream fails to reset
         */
        @Override
        public void reset() throws IOException
        {
            super.reset();
            index = 0;
            cursor = 0;
        }
    }
}
