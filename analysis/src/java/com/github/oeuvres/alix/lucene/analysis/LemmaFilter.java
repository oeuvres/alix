package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;



public final class LemmaFilter extends TokenFilter
{
    private final LemmaLexicon lex;

    // Optional: user provides a small fallback list per posId (or null).
    // Example: fallback[PROPN] = { NOUN }, fallback[AUX] = { VERB }.
    private final int[][] posFallbacks;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    private char[] scratch = new char[32];

    public LemmaFilter(TokenStream input, LemmaLexicon lex, int[][] posFallbacks)
    {
        super(input);
        this.lex = lex;
        this.posFallbacks = posFallbacks;
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        if (!input.incrementToken()) return false;
        if (keywordAtt.isKeyword()) return true;

        final int posId = posAtt.getPos();

        final char[] buf = termAtt.buffer();
        final int len = termAtt.length();

        // Step 1: surface known ?
        final int formId = lex.findFormId(buf, 0, len);
        if (formId < 0) return true;

        // Step 2: exact lookup
        int lemmaId = (posId >= 0) ? lex.findLemmaId(formId, posId) : -1;

        // Step 3a: limited POS fallback (cheap; avoids scanning the whole lexicon)
        if (lemmaId < 0 && posId >= 0 && posFallbacks != null && posId < posFallbacks.length) {
            final int[] alts = posFallbacks[posId];
            if (alts != null) {
                for (int i = 0; i < alts.length; i++) {
                    lemmaId = lex.findLemmaId(formId, alts[i]);
                    if (lemmaId >= 0) break;
                }
            }
        }

        // Step 3b: default lemma (pos-agnostic)
        if (lemmaId < 0) {
            lemmaId = lex.defaultLemmaId(formId); // returns -1 if none
        }

        // Step 4: nothing usable
        if (lemmaId < 0 || lemmaId == formId) return true;

        // Step 5: copy lemma safely (CharsDic.get() throws if dst too small)
        final int need = lex.formLength(lemmaId);
        if (scratch.length < need) scratch = new char[Math.max(need, scratch.length << 1)];

        final int n = lex.getForm(lemmaId, scratch);
        termAtt.copyBuffer(scratch, 0, n);

        return true;
    }
}
