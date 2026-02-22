package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.util.TermProbe;

/**
 * Lowercase the first lexical token after a sentence boundary if its lowercase form
 * is found in the lexicon, while preserving punctuation/XML tokens and keyword tokens.
 *
 * <p>This filter assumes upstream tokenization keeps sentence punctuation tokens
 * (for example {@code PUNCTsent}, {@code PUNCTsection}) and exposes a POS tag in
 * {@link PosAttribute}.
 *
 * <p>Current policy:
 * <ul>
 *   <li>Start of stream is treated as sentence start.</li>
 *   <li>{@code XML} and {@code PUNCTclause} do not consume sentence-start state.</li>
 *   <li>{@code PUNCTsent} and {@code PUNCTsection} set sentence-start state.</li>
 *   <li>{@link KeywordAttribute#isKeyword()} prevents rewriting, but does not prevent state transitions.</li>
 * </ul>
 */
public final class SentenceStartLowerCaseFilter extends TokenFilter
{
    private final LemmaLexicon lex;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Reusable probe for transformed dictionary lookup (no String allocation in hot path). */
    private final TermProbe probe = new TermProbe();

    /**
     * State machine flag:
     * true  -> the next lexical token is sentence-initial candidate
     * false -> currently inside a sentence
     */
    private boolean sentenceStartPending = true;

    public SentenceStartLowerCaseFilter(final TokenStream input, final LemmaLexicon lex)
    {
        super(input);
        this.lex = Objects.requireNonNull(lex, "lex");
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        // Start-of-stream is treated as sentence start.
        sentenceStartPending = true;
        probe.clear();
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        if (!input.incrementToken()) return false;

        final int posId = posAtt.getPos();

        // 1) Sentence boundary tokens: set state, never rewritten here.
        if (isSentenceBoundary(posId)) {
            sentenceStartPending = true;
            return true;
        }

        // 2) Ignorable tokens before sentence start (XML, clause punctuation):
        //    pass through without consuming the pending sentence-start state.
        if (isIgnorableBeforeSentenceStart(posId)) {
            return true;
        }

        // 3) Any other token is lexical/content for this state machine.
        //    If we are not at sentence start, do nothing.
        if (!sentenceStartPending) {
            return true;
        }

        // 4) We are on the first lexical token after a sentence boundary (or stream start):
        //    consume the state now, regardless of whether token is keyword.
        sentenceStartPending = false;

        // 5) Keyword token: preserve as-is, but state is already consumed.
        if (keywordAtt.isKeyword()) {
            return true;
        }

        // 6) Probe lowercase form in lexicon; if known, rewrite with canonical form.
        probe.copyFrom(termAtt).toLowerCase();
        final int formId = lex.findFormId(probe);
        if (formId < 0) {
            return true;
        }

        lex.copyForm(formId, termAtt);
        return true;
    }

    private static boolean isSentenceBoundary(final int posId)
    {
        return (posId == PUNCTsent.code || posId == PUNCTpara.code || posId == PUNCTsection.code);
    }

    private static boolean isIgnorableBeforeSentenceStart(final int posId)
    {
        // XML tags and clause punctuation do not consume sentence-start state.
        // (Sentence-boundary XML tags are not interpreted here.)
        return (posId == XML.code || posId == PUNCTclause.code);
    }
}