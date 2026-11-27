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
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/** Regression tests for field-specific projection, exclusions and positions. */
public class CleanupFilterTest
{
    /** Complete input or observed token for the attributes used by cleanup. */
    private record Token(String term, String lemma, Upos pos, int increment, int boundary)
    {
        /**
         * Creates a token with an ordinary increment and no preceding boundary.
         * @param term surface form
         * @param lemma resolved lemma, or empty string
         * @param pos upstream POS
         */
        Token(String term, String lemma, Upos pos)
        {
            this(term, lemma, pos, 1, NONE);
        }
    }

    /** Emitted tokens and trailing structural information. */
    private record Result(List<Token> tokens, int increment, int boundary) {}

    /**
     * Checks canonical indexing against the same numeric annotations used for
     * training. In particular, a numerical lemma avoids the grammatical-word
     * exclusion of an ambiguous surface form.
     * @throws IOException if a token stream fails
     */
    @Test
    public void canonical() throws IOException
    {
        final CharArraySet rejected = new CharArraySet(List.of("un", "neuf", "de"), true);
        try (TokenStream stream = new CleanupFilter(new Fixture(
                new Token("un", "1", NUMcard),
                new Token("un", "", DET),
                new Token("neuf", "9", NUMcard),
                new Token("neuf", "", ADJ),
                new Token("d'", "de", ADP),
                new Token("troisième", "3e", ADJord),
                new Token("6ans", "", NOUNage)), "content", null, rejected)) {
            assertEquals(List.of(
                new Token("1", "1", NUMcard),
                new Token("9", "9", NUMcard, 2, NONE),
                new Token("3e", "3e", ADJord, 3, NONE),
                new Token("6ans", "", NOUNage)), read(stream).tokens());
        }
    }

    /**
     * Checks that the field, not the presence of a rejection dictionary,
     * determines the numerical representation.
     * @throws IOException if a token stream fails
     */
    @Test
    public void fieldPolicy() throws IOException
    {
        final Token number = new Token("sept", "7", NUMcard);
        try (TokenStream stream = new CleanupFilter(new Fixture(number),
                "content", null, null)) {
            assertEquals(List.of(new Token("7", "7", NUMcard)), read(stream).tokens());
        }
        try (TokenStream stream = new CleanupFilter(new Fixture(number),
                "word2vec-lemma", null, new CharArraySet(0, false))) {
            assertEquals(List.of(new Token("#", "7", NUMcard)), read(stream).tokens());
        }
    }

    /**
     * Checks gap accumulation, collapsed noise, incoming boundaries, trailing
     * boundaries and stream reuse.
     * @throws IOException if a token stream fails
     */
    @Test
    public void positions() throws IOException
    {
        final CharArraySet skipped = new CharArraySet(List.of("noise", ".", "%"), false);
        final CharArraySet rejected = new CharArraySet(List.of("et"), false);
        try (TokenStream stream = new CleanupFilter(new Fixture(
                new Token("enfant", "", NOUN),
                new Token("<p>", "", XML, 1, PARAGRAPH),
                new Token("noise", "", TOKEN),
                new Token(".", "", PUNCTsent),
                new Token("", "", TOKEN),
                new Token("%", "", SYMunit),
                new Token("et", "", CCONJ),
                new Token("objet", "", NOUN),
                new Token("¶", "", PUNCTpara)), "content", skipped, rejected)) {
            final Result expected = new Result(List.of(
                new Token("enfant", "", NOUN),
                new Token("objet", "", NOUN, 5, PARAGRAPH)), 1, PARAGRAPH);
            assertEquals(expected, read(stream));
            assertEquals(expected, read(stream));
        }
    }

    /**
     * Checks that training retains resolved lexical forms, including clitics,
     * non-Latin words, ordinals and aggregated quantities, without changing POS.
     * @throws IOException if a token stream fails
     */
    @Test
    public void word2vec() throws IOException
    {
        final CharArraySet skipped = new CharArraySet(List.of("d'", "#"), true);
        try (TokenStream stream = new CleanupFilter(new Fixture(
                new Token("d'", "de", ADP),
                new Token("un", "1", NUMcard),
                new Token("un", "", DET),
                new Token("neuf", "9", NUMcard),
                new Token("neuf", "", ADJ),
                new Token("12", "", NUM),
                new Token("16ème", "16e", ADJord),
                new Token("6ans", "", NOUNage),
                new Token("25kg", "", NOUNqty),
                new Token("enfants", "enfant", NOUN),
                new Token("prendre conscience", "", VERB),
                new Token("κόσμος", "", NOUN)), "word2vec", skipped, null)) {
            assertEquals(List.of(
                new Token("de", "de", ADP),
                new Token("#", "1", NUMcard),
                new Token("un", "", DET),
                new Token("#", "9", NUMcard),
                new Token("neuf", "", ADJ),
                new Token("#", "", NUM),
                new Token("16e", "16e", ADJord),
                new Token("6ans", "", NOUNage),
                new Token("25kg", "", NOUNqty),
                new Token("enfant", "enfant", NOUN),
                new Token("prendre conscience", "", VERB),
                new Token("κόσμος", "", NOUN)), read(stream).tokens());
        }
    }

    /**
     * Reads one complete stream and captures final increments and boundaries.
     * @param stream stream to read
     * @return all emitted tokens and final attributes
     * @throws IOException if the stream fails
     */
    private static Result read(final TokenStream stream) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final LemmaAttribute lemma = stream.addAttribute(LemmaAttribute.class);
        final PosAttribute pos = stream.addAttribute(PosAttribute.class);
        final PositionIncrementAttribute increment = stream.addAttribute(PositionIncrementAttribute.class);
        final BoundaryAttribute boundary = stream.addAttribute(BoundaryAttribute.class);
        final List<Token> tokens = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            tokens.add(new Token(term.toString(), lemma.toString(), Upos.get(pos.getPos()),
                increment.getPositionIncrement(), boundary.getBoundary()));
        }
        stream.end();
        return new Result(tokens, increment.getPositionIncrement(), boundary.getBoundary());
    }

    /** Explicit upstream annotations, independent of tokenizer and statistical models. */
    private static final class Fixture extends TokenStream
    {
        private final BoundaryAttribute boundary = addAttribute(BoundaryAttribute.class);
        private final PositionIncrementAttribute increment = addAttribute(PositionIncrementAttribute.class);
        private int index;
        private final LemmaAttribute lemma = addAttribute(LemmaAttribute.class);
        private final PosAttribute pos = addAttribute(PosAttribute.class);
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final Token[] tokens;

        /**
         * Creates a deterministic token stream.
         * @param tokens upstream tokens
         */
        Fixture(final Token... tokens)
        {
            this.tokens = tokens;
        }

        /**
         * Finishes the fixture without introducing an additional gap.
         * @throws IOException if the stream fails
         */
        @Override
        public void end() throws IOException
        {
            super.end();
            increment.setPositionIncrement(0);
            boundary.setBoundary(NONE);
        }

        /**
         * Publishes the next complete upstream annotation.
         * @return true if another token exists
         */
        @Override
        public boolean incrementToken()
        {
            if (index == tokens.length) return false;
            clearAttributes();
            final Token token = tokens[index++];
            term.append(token.term());
            lemma.append(token.lemma());
            pos.setPos(token.pos().ordinal());
            increment.setPositionIncrement(token.increment());
            boundary.setBoundary(token.boundary());
            return true;
        }

        /**
         * Resets the fixture for another pass.
         * @throws IOException if the stream fails
         */
        @Override
        public void reset() throws IOException
        {
            super.reset();
            index = 0;
        }
    }
}
