package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.util.CharsDic;
import com.github.oeuvres.alix.util.LongIntMap;

public final class LemmaLexicon
{
    /** An internal default value for pos, to get a lemma without pos */
    final int DEFAULT_POS = 0;

    /** Interned strings (inflected + lemma). formId == ord in this dic. */
    private final CharsDic forms;

    /** (formId,posId) -> lemmaFormId (both are ords in {@link #forms}). */
    private final LongIntMap lemmaByFormPos;

    // ---------------------------
    // Construction / lifecycle
    // ---------------------------

    /**
     * @param expectedForms
     */
    public LemmaLexicon(int expectedForms)
    {
        this.forms = new CharsDic(Math.max(8, expectedForms));
        this.lemmaByFormPos = new LongIntMap(Math.max(8, expectedForms));
    }

    /** Shrink internal storage (not required for correctness). */
    public void freeze()
    {
        forms.freeze();
        lemmaByFormPos.trimToSize();
    }

    // ---------------------------
    // Lookup API (TokenFilter hot path)
    // ---------------------------

    /** Return formId for a surface form, or -1 if unknown. */
    public int findFormId(char[] term, int off, int len)
    {
        return forms.find(term, off, len);
    }

    /** Return formId for a surface form, or -1 if unknown. */
    public int findFormId(CharSequence term)
    {
        return forms.find(term);
    }

    public int findFormId(CharTermAttribute att)
    {
        return forms.find(att.buffer(), 0, att.length());
    }

    /** Return lemmaFormId for (formId,posId), or -1 if unknown. */
    public int findLemmaId(int formId, int posId)
    {
        return lemmaByFormPos.get(formId, posId);
    }

    /** Return lemmaFormId for (formId) with DEFAULT_POS, or -1 if unknown. */
    public int findLemmaId(int formId)
    {
        return lemmaByFormPos.get(formId, DEFAULT_POS);
    }

    /**
     * One-shot: surface + posId -> lemmaFormId, or -1 if missing at any stage.
     * This avoids the caller having to do two lookups explicitly.
     */
    public int findLemmaId(char[] surface, int off, int len, int posId)
    {
        final int formId = forms.find(surface, off, len);
        if (formId < 0)
            return -1;
        return lemmaByFormPos.get(formId, posId);
    }

    /**
     * Intern a string into forms, returning its formId. Uses CharsDic.add
     * contract (negative means "already existed").
     */
    public int internForm(CharSequence term)
    {
        int ord = forms.add(term);
        return (ord >= 0) ? ord : (-ord - 1);
    }

    public int internForm(CharSequence form, int off, int len)
    {
        int ord = forms.add(form, off, len);
        return (ord >= 0) ? ord : (-ord - 1);
    }

    /**
     * Intern a string into forms, returning its formId. Uses CharsDic.add
     * contract (negative means "already existed").
     */
    public int internForm(char[] form, int off, int len)
    {
        int ord = forms.add(form, off, len);
        return (ord >= 0) ? ord : (-ord - 1);
    }

    /**
     * TOOO Javadoc
     */
    public int lemmaToBuffer(CharTermAttribute srcAtt, int posId, CharTermAttribute dstAtt)
    {
        final int formId = findFormId(srcAtt);
        if (formId < 0)
            return -1;
        final int lemmaId = findLemmaId(formId, posId);
        if (lemmaId < 0)
            return -1;
        copyForm(lemmaId, dstAtt);
        return lemmaId;
    }

    /**
     * TOOO Javadoc
     */
    public int lemmaToBuffer(CharTermAttribute srcAtt, CharTermAttribute dstAtt)
    {
        final int formId = findFormId(srcAtt);
        if (formId < 0)
            return -1;
        final int lemmaId = findLemmaId(formId, DEFAULT_POS);
        if (lemmaId < 0)
            return -1;
        copyForm(lemmaId, dstAtt);
        return lemmaId;
    }

    /**
     * TOOO Javadoc
     */
    public boolean copyForm(int formId, CharTermAttribute dstAtt)
    {
        if (formId < 0 || formId >= forms.size())
            return false;
        dstAtt.copyBuffer(forms.slab(), forms.termOffset(formId), forms.termLength(formId));
        return true;
    }

    /**
     * Max length of any interned string (useful to size TokenFilter scratch
     * buffers).
     */
    public int maxFormLength()
    {
        return forms.maxTermLength();
    }

    public int putEntry(CharSequence form, int posId, CharSequence lemma, OnDuplicate policy)
    {
        return putEntry(form, 0, form.length(), posId, lemma, 0, lemma.length(), policy);
    }

    public int putEntry(char[] inflected, int iOff, int iLen, char[] lemma, int lOff, int lLen, OnDuplicate policy)
    {
        return putEntry(inflected, iOff, iLen, DEFAULT_POS, lemma, lOff, lLen, policy);
    }

    public int putEntry(CharSequence inflected, int iOff, int iLen, CharSequence lemma, int lOff, int lLen,
            OnDuplicate policy)
    {

        return putEntry(inflected, iOff, iLen, DEFAULT_POS, lemma, lOff, lLen, policy);
    }

    public int putEntry(CharSequence form, int iOff, int iLen, int posId, CharSequence lemma, int lOff, int lLen,
            OnDuplicate policy)
    {
        final int formId = internForm(form, iOff, iLen);
        final int lemmaId = internForm(lemma, lOff, lLen);

        putEntry(formId, posId, lemmaId, policy);

        return lemmaId;

    }

    /**
     * Add one mapping entry (inflected,posId)->lemma. Returns lemmaFormId.
     * Duplicate handling is controlled by policy.
     */
    public int putEntry(char[] inflected, int iOff, int iLen, int posId, char[] lemma, int lOff, int lLen,
            OnDuplicate policy)
    {

        final int formId = internForm(inflected, iOff, iLen);
        final int lemmaId = internForm(lemma, lOff, lLen);

        putEntry(formId, posId, lemmaId, policy);

        return lemmaId;
    }

    public void putEntry(int formId, int posId, int lemmaId, OnDuplicate policy)
    {
        final long key = LongIntMap.packIntPair(formId, posId);
        switch (policy) {
            case IGNORE:
                lemmaByFormPos.putIfAbsent(key, lemmaId);
                break;
            case REPLACE:
                lemmaByFormPos.put(key, lemmaId);
                break;
            case ERROR:
                int prev = lemmaByFormPos.putIfAbsent(key, lemmaId);
                if (prev != lemmaByFormPos.missingValue() && prev != lemmaId) {
                    throw new IllegalArgumentException("duplicate (formId,posId) with different lemmaId");
                }
                break;
            default:
                throw new AssertionError(policy);
        }
    }

    public enum OnDuplicate
    {
        IGNORE, REPLACE, ERROR
    }

}
