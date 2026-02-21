package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

public class LemmaFilterTest {

    // Test-only POS ids. They just need to be consistent within the test + lexicon.
    private static final int POS_NOUN = 1;
    private static final int POS_VERB = 2;
    private static final int POS_ADJ  = 3;

    private LemmaLexicon lex;

    @BeforeEach
    void setUp() {
        lex = new LemmaLexicon(64);

        // -------------------------
        // POS-dependent homographs
        // -------------------------
        // "saw": noun lemma == surface; verb lemma == "see"
        put(lex, "saw",  POS_NOUN, "saw");
        put(lex, "saw",  POS_VERB, "see");

        // "rose": noun "rose"; verb past of rise => "rise"
        put(lex, "rose", POS_NOUN, "rose");
        put(lex, "rose", POS_VERB, "rise");

        // "dove": noun "dove"; verb (AmE past of dive) => "dive"
        put(lex, "dove", POS_NOUN, "dove");
        put(lex, "dove", POS_VERB, "dive");

        // "shot": noun "shot"; verb past of shoot => "shoot"
        put(lex, "shot", POS_NOUN, "shot");
        put(lex, "shot", POS_VERB, "shoot");

        // "left": adjective "left"; verb past of leave => "leave"
        put(lex, "left", POS_ADJ,  "left");
        put(lex, "left", POS_VERB, "leave");

        // -------------------------
        // POS-agnostic (DEFAULT_POS) entries
        // -------------------------
        put(lex, "children", lex.DEFAULT_POS_ID, "child");
        put(lex, "mice",     lex.DEFAULT_POS_ID, "mouse");

        // A normal plural -> singular POS-specific example
        put(lex, "cats", POS_NOUN, "cat");

        lex.freeze();
    }

    @Test
    void lemmatizes_homographs_by_pos() throws Exception {
        // "I saw a saw ."
        // VERB saw -> see ; NOUN saw -> saw (no rewrite because lemma == surface)
        assertArrayEquals(
            new String[] { "I", "see", "a", "saw", "." },
            runFilter(
                new String[] { "I", "saw", "a", "saw", "." },
                new int[]    { -1,  POS_VERB, -1, POS_NOUN, -1 },
                new boolean[] { false, false, false, false, false }
            )
        );

        // "A rose rose ."
        assertArrayEquals(
            new String[] { "A", "rose", "rise", "." },
            runFilter(
                new String[] { "A", "rose", "rose", "." },
                new int[]    { -1,  POS_NOUN, POS_VERB, -1 },
                new boolean[] { false, false, false, false }
            )
        );

        // "A dove dove ."
        assertArrayEquals(
            new String[] { "A", "dove", "dive", "." },
            runFilter(
                new String[] { "A", "dove", "dove", "." },
                new int[]    { -1,  POS_NOUN, POS_VERB, -1 },
                new boolean[] { false, false, false, false }
            )
        );

        // "They shot the shot ."
        assertArrayEquals(
            new String[] { "They", "shoot", "the", "shot", "." },
            runFilter(
                new String[] { "They", "shot", "the", "shot", "." },
                new int[]    { -1,     POS_VERB, -1,  POS_NOUN, -1 },
                new boolean[] { false, false, false, false, false }
            )
        );

        // "The left left ."
        assertArrayEquals(
            new String[] { "The", "left", "leave", "." },
            runFilter(
                new String[] { "The", "left", "left", "." },
                new int[]    { -1,    POS_ADJ, POS_VERB, -1 },
                new boolean[] { false, false, false, false }
            )
        );
    }

    @Test
    void falls_back_to_default_pos_mapping_when_specific_missing() throws Exception {
        // children has DEFAULT_POS mapping but no explicit (children, POS_NOUN) mapping
        assertArrayEquals(
            new String[] { "child" },
            runFilter(
                new String[] { "children" },
                new int[]    { POS_NOUN },
                new boolean[] { false }
            )
        );
    }

    @Test
    void uses_default_mapping_when_pos_is_negative() throws Exception {
        // LemmaFilter: posId < 0 -> skips exact lookup and tries DEFAULT_POS
        assertArrayEquals(
            new String[] { "mouse" },
            runFilter(
                new String[] { "mice" },
                new int[]    { -1 },
                new boolean[] { false }
            )
        );
    }

    @Test
    void does_not_touch_keyword_tokens_even_if_mapping_exists() throws Exception {
        assertArrayEquals(
            new String[] { "cats" },
            runFilter(
                new String[] { "cats" },
                new int[]    { POS_NOUN },
                new boolean[] { true } // keyword => bypass
            )
        );
    }

    @Test
    void leaves_unknown_terms_untouched() throws Exception {
        assertArrayEquals(
            new String[] { "quux" },
            runFilter(
                new String[] { "quux" },
                new int[]    { POS_NOUN },
                new boolean[] { false }
            )
        );
    }

    // -----------------------
    // Helpers
    // -----------------------

    private static void put(LemmaLexicon lex, String inflected, int posId, String lemma) {
        char[] i = inflected.toCharArray();
        char[] l = lemma.toCharArray();
        lex.putEntry(i, 0, i.length, posId, l, 0, l.length, LemmaLexicon.OnDuplicate.ERROR);
    }

    private String[] runFilter(String[] terms, int[] pos, boolean[] keyword) throws Exception {
        TokenStream src = new TermPosTokenStream(terms, pos, keyword);
        TokenStream ts = new LemmaFilter(src, lex);

        List<String> out = new ArrayList<>();
        CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);

        ts.reset();
        while (ts.incrementToken()) {
            out.add(termAtt.toString());
        }
        ts.end();
        ts.close();

        return out.toArray(new String[0]);
    }

    /**
     * Minimal TokenStream for tests: emits (term, pos, keyword) triples.
     * This avoids depending on a tokenizer/tagger in unit tests.
     */
    private static final class TermPosTokenStream extends TokenStream {
        private final String[] terms;
        private final int[] pos;
        private final boolean[] keyword;
        private int i = 0;

        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        private final PosAttribute posAtt = addAttribute(PosAttribute.class);
        private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);

        TermPosTokenStream(String[] terms, int[] pos, boolean[] keyword) {
            if (terms.length != pos.length) throw new IllegalArgumentException("terms.length != pos.length");
            if (keyword != null && keyword.length != terms.length) throw new IllegalArgumentException("keyword.length != terms.length");
            this.terms = terms;
            this.pos = pos;
            this.keyword = keyword;
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (i >= terms.length) return false;

            clearAttributes();
            termAtt.setEmpty().append(terms[i]);

            // This is the only line you may need to adapt if your setter is not named setPos(int).
            posAtt.setPos(pos[i]);

            keywordAtt.setKeyword(keyword != null && keyword[i]);

            i++;
            return true;
        }

        @Override
        public void reset() throws IOException {
            super.reset();
            i = 0;
        }
    }
}
