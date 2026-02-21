package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.XML;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;



public final class LemmaFilter extends TokenFilter
{
    private final LemmaLexicon lex;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);



    public LemmaFilter(TokenStream input, LemmaLexicon lex)
    {
        super(input);
        this.lex = lex;
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        if (!input.incrementToken()) return false;
        if (keywordAtt.isKeyword()) return true;

        final int posId = posAtt.getPos();
        if (posId == XML.code || Upos.isPunct(posId)) {
            return true;
        }
        // Step 1: surface known ?
        final int formId = lex.findFormId(termAtt);
        if (formId < 0) return true;

        // Step 2: exact lookup
        int lemmaId = (posId >= 0) ? lex.findLemmaId(formId, posId) : -1;

        // Step 3: default lemma (pos-agnostic)
        if (lemmaId < 0) {
            lemmaId = lex.findLemmaId(formId); // returns -1 if none
        }

        // Step 4: nothing usable
        if (lemmaId < 0 || lemmaId == formId) return true;

        // Step 5: copy lemma safely (CharsDic.get() throws if dst too small)
        lex.copyForm(lemmaId, termAtt);

        return true;
    }
}
