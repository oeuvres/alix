package com.github.oeuvres.alix.util;

import java.util.Objects;

/**
 * Dictionary-backed lemma lexicon with stable integer identifiers.
 *
 * <p>
 * This class is independent of Lucene. It stores surface forms and lemma
 * forms in one {@link CharsDic}. Lemma mappings are represented as:
 * </p>
 *
 * <pre>{@code
 * (formId, posId) -> lemmaId
 * }</pre>
 *
 * <p>
 * All ids are ordinals in the internal {@link CharsDic}. Public mutation is
 * intentionally limited to {@link #put(CharSequence, CharSequence)} and its
 * variants: a form enters the dictionary only because it participates in a
 * lemma mapping. If a caller needs a standalone character dictionary, it should
 * use {@link CharsDic} directly.
 * </p>
 *
 * <p>
 * Lookup is strict. {@link #lemmaId(int, int)} does not automatically fall
 * back to {@link #ANY_POS}; callers decide whether and when to perform fallback
 * lookup.
 * </p>
 *
 * <p>
 * Thread-safety: not thread-safe during mutation. Concurrent reads are safe
 * only when no thread mutates the instance.
 * </p>
 */
public final class LemmaLexicon
{
    /**
     * POS-agnostic mapping id.
     *
     * <p>
     * An entry {@code (formId, ANY_POS) -> lemmaId} may be used by callers
     * as a fallback when no POS-specific mapping exists.
     * </p>
     */
    public static final int ANY_POS = 0;
    
    /** Interned storage for both surface forms and lemma forms. */
    private final CharsDic forms;
    
    /** Mapping from packed {@code (formId, posId)} key to {@code lemmaId}. */
    private final LongIntMap lemmas;
    
    /** Default duplicate policy used by {@code put(...)} methods. */
    private OnDuplicate onDuplicate = OnDuplicate.IGNORE;
    
    /**
     * Creates a lemma lexicon.
     *
     * <p>
     * The expected-entry count is only a sizing hint. It does not limit the
     * number of mappings. Since each mapping may contribute both a surface form
     * and a lemma form, the internal string dictionary is initially sized from
     * approximately twice this value.
     * </p>
     *
     * @param expectedEntries estimated number of lemma mappings
     */
    public LemmaLexicon(final int expectedEntries)
    {
        final int entries = Math.max(8, expectedEntries);
        final int expectedForms = entries > Integer.MAX_VALUE / 2 ? entries : entries * 2;
        this.forms = new CharsDic(expectedForms);
        this.lemmas = new LongIntMap(entries);
    }
    
    /**
     * Returns an interned form as a new {@link String}.
     *
     * <p>
     * This method is intended for tests, diagnostics, and messages, not hot
     * paths.
     * </p>
     *
     * @param id form or lemma id
     * @return form string
     */
    public String asString(final int id)
    {
        return forms.asString(id);
    }

    /**
     * Copies an interned form into a destination character array.
     *
     * @param ord  form or lemma id
     * @param dst destination character array
     * @param off destination offset
     * @return number of copied characters
     */
    public int copy(final int ord, final char[] dst, final int off)
    {
        return forms.copy(ord, dst, off);
    }
    
    /**
     * Returns the POS-agnostic lemma id for a form id.
     *
     * <p>
     * This is equivalent to {@code lemmaId(formId, ANY_POS)}.
     * </p>
     *
     * @param formId form id
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final int formId)
    {
        return lemmaId(formId, ANY_POS);
    }
    
    /**
     * Returns the lemma id for a form id and POS id.
     *
     * <p>
     * No fallback to {@link #ANY_POS} is performed.
     * </p>
     *
     * @param formId form id
     * @param posId  POS id
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final int formId, final int posId)
    {
        if (formId < 0) {
            return -1;
        }
        return lemmas.get(formId, posId);
    }
    
    /**
     * Returns the POS-agnostic lemma id for a character-array slice.
     *
     * <p>
     * This is equivalent to {@code lemmaId(form, off, len, ANY_POS)}.
     * </p>
     *
     * @param form source character array
     * @param off  start offset
     * @param len  number of characters
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final char[] form, final int off, final int len)
    {
        return lemmaId(form, off, len, ANY_POS);
    }
    
    /**
     * Returns the lemma id for a character-array slice and POS id.
     *
     * <p>
     * No fallback to {@link #ANY_POS} is performed.
     * </p>
     *
     * @param form  source character array
     * @param off   start offset
     * @param len   number of characters
     * @param posId POS id
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final char[] form, final int off, final int len, final int posId)
    {
        final int formId = ord(form, off, len);
        if (formId < 0) {
            return -1;
        }
        return lemmaId(formId, posId);
    }
    
    /**
     * Returns the POS-agnostic lemma id for a character sequence.
     *
     * <p>
     * This is equivalent to {@code lemmaId(form, ANY_POS)}.
     * </p>
     *
     * @param form source form
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final CharSequence form)
    {
        return lemmaId(form, ANY_POS);
    }
    
    /**
     * Returns the lemma id for a character sequence and POS id.
     *
     * <p>
     * No fallback to {@link #ANY_POS} is performed.
     * </p>
     *
     * @param form  source form
     * @param posId POS id
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final CharSequence form, final int posId)
    {
        final int formId = ord(form);
        if (formId < 0) {
            return -1;
        }
        return lemmaId(formId, posId);
    }
    
    /**
     * Returns the POS-agnostic lemma id for a character-sequence slice.
     *
     * <p>
     * This is equivalent to {@code lemmaId(form, off, len, ANY_POS)}.
     * </p>
     *
     * @param form source character sequence
     * @param off  start offset
     * @param len  number of characters
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final CharSequence form, final int off, final int len)
    {
        return lemmaId(form, off, len, ANY_POS);
    }
    
    /**
     * Returns the lemma id for a character-sequence slice and POS id.
     *
     * <p>
     * No fallback to {@link #ANY_POS} is performed.
     * </p>
     *
     * @param form  source character sequence
     * @param off   start offset
     * @param len   number of characters
     * @param posId POS id
     * @return lemma id, or {@code -1} if absent
     */
    public int lemmaId(final CharSequence form, final int off, final int len, final int posId)
    {
        final int formId = ord(form, off, len);
        if (formId < 0) {
            return -1;
        }
        return lemmaId(formId, posId);
    }
    
    /**
     * Returns the length of an interned form.
     *
     * @param id form or lemma id
     * @return form length
     */
    public int length(final int id)
    {
        return forms.termLength(id);
    }
    
    /**
     * Returns the maximum interned form length.
     *
     * @return maximum form length
     */
    public int maxLength()
    {
        return forms.maxTermLength();
    }
    
    /**
     * Returns the current default duplicate policy.
     *
     * @return duplicate policy
     */
    public OnDuplicate onDuplicate()
    {
        return onDuplicate;
    }
    
    /**
     * Sets the current default duplicate policy.
     *
     * @param policy duplicate policy
     */
    public void onDuplicate(final OnDuplicate policy)
    {
        this.onDuplicate = Objects.requireNonNull(policy, "policy");
    }
    
    /**
     * Returns the id of a character-array slice.
     *
     * <p>
     * This method never inserts. It returns {@code -1} when the form is not
     * already present.
     * </p>
     *
     * @param form source character array
     * @param off  start offset
     * @param len  number of characters
     * @return form id, or {@code -1} if absent
     */
    public int ord(final char[] form, final int off, final int len)
    {
        return forms.ord(form, off, len);
    }

    /**
     * Returns the id of a character sequence.
     *
     * <p>
     * This method never inserts. It returns {@code -1} when the form is not
     * already present.
     * </p>
     *
     * @param form source form
     * @return form id, or {@code -1} if absent
     */
    public int ord(final CharSequence form)
    {
        return forms.ord(form);
    }

    /**
     * Returns the id of a character-sequence slice.
     *
     * <p>
     * This method never inserts. It returns {@code -1} when the form is not
     * already present.
     * </p>
     *
     * @param form source character sequence
     * @param off  start offset
     * @param len  number of characters
     * @return form id, or {@code -1} if absent
     */
    public int ord(final CharSequence form, final int off, final int len)
    {
        return forms.ord(form, off, len);
    }

    /**
     * Inserts a POS-agnostic lemma mapping using the current duplicate policy.
     *
     * @param form  surface form
     * @param lemma lemma form
     * @return lemma id
     */
    public int put(final CharSequence form, final CharSequence lemma)
    {
        return put(form, ANY_POS, lemma);
    }
    
    /**
     * Inserts a lemma mapping using the current duplicate policy.
     *
     * @param form  surface form
     * @param posId POS id
     * @param lemma lemma form
     * @return lemma id
     */
    public int put(final CharSequence form, final int posId, final CharSequence lemma)
    {
        final int formId = add(form);
        final int lemmaId = add(lemma);
        put(formId, posId, lemmaId);
        return lemmaId;
    }
    
    /**
     * Inserts a lemma mapping from character arrays using the current duplicate
     * policy.
     *
     * @param form     source surface form array
     * @param formOff  surface form offset
     * @param formLen  surface form length
     * @param posId    POS id
     * @param lemma    source lemma form array
     * @param lemmaOff lemma form offset
     * @param lemmaLen lemma form length
     * @return lemma id
     */
    public int put(
        final char[] form,
        final int formOff,
        final int formLen,
        final int posId,
        final char[] lemma,
        final int lemmaOff,
        final int lemmaLen)
    {
        final int formId = add(form, formOff, formLen);
        final int lemmaId = add(lemma, lemmaOff, lemmaLen);
        put(formId, posId, lemmaId);
        return lemmaId;
    }
    
    /**
     * Inserts a lemma mapping from already-interned ids using the current
     * duplicate policy.
     *
     * <p>
     * The ids must already be present in this lexicon. This method is useful
     * when the caller has previously received ids from another {@code put(...)}
     * operation.
     * </p>
     *
     * @param formId  surface form id
     * @param posId   POS id
     * @param lemmaId lemma form id
     */
    public void put(final int formId, final int posId, final int lemmaId)
    {
        put(formId, posId, lemmaId, onDuplicate);
    }
    
    /**
     * Inserts a lemma mapping from already-interned ids using an explicit
     * duplicate policy.
     *
     * <p>
     * The ids must already be present in this lexicon.
     * </p>
     *
     * @param formId  surface form id
     * @param posId   POS id
     * @param lemmaId lemma form id
     * @param policy  duplicate policy
     */
    public void put(final int formId, final int posId, final int lemmaId, final OnDuplicate policy)
    {
        Objects.requireNonNull(policy, "policy");
        checkId(formId, "formId");
        checkId(lemmaId, "lemmaId");
        
        final long key = LongIntMap.packIntPair(formId, posId);
        
        switch (policy) {
            case IGNORE:
                lemmas.putIfAbsent(key, lemmaId);
                return;
            
            case REPLACE:
                lemmas.put(key, lemmaId);
                return;
            
            case ERROR:
                final int previous = lemmas.putIfAbsent(key, lemmaId);
                if (previous != lemmas.missingValue() && previous != lemmaId) {
                    throw new IllegalArgumentException(
                            "Duplicate lemma mapping: formId=" + formId
                                    + " \"" + asString(formId) + "\""
                                    + ", posId=" + posId
                                    + ", previous lemmaId=" + previous
                                    + " \"" + asString(previous) + "\""
                                    + ", new lemmaId=" + lemmaId
                                    + " \"" + asString(lemmaId) + "\"");
                }
                return;
            
            default:
                throw new AssertionError(policy);
        }
    }
    
    /**
     * Returns the number of interned forms.
     *
     * @return number of interned forms
     */
    public int size()
    {
        return forms.size();
    }
    
    /**
     * Shrinks internal storage after bulk loading.
     */
    public void trimToSize()
    {
        forms.trimToSize();
        lemmas.trimToSize();
    }
    
    /**
     * Duplicate handling policy for lemma mappings.
     */
    public enum OnDuplicate
    {
        /**
         * Keep the existing mapping and ignore the new one.
         */
        IGNORE,
        
        /**
         * Replace any existing mapping with the new one.
         */
        REPLACE,
        
        /**
         * Insert only if absent; if a different mapping already exists, throw.
         *
         * <p>
         * Reinserting the exact same mapping is accepted.
         * </p>
         */
        ERROR
    }
    
    /**
     * Interns a character-array slice.
     *
     * @param form source character array
     * @param off  start offset
     * @param len  number of characters
     * @return form id
     */
    private int add(final char[] form, final int off, final int len)
    {
        return ord(forms.add(form, off, len));
    }
    
    /**
     * Interns a character sequence.
     *
     * @param form source form
     * @return form id
     */
    private int add(final CharSequence form)
    {
        return ord(forms.add(form));
    }
    
    /**
     * Validates an assigned id.
     *
     * @param id   form or lemma id
     * @param name parameter name
     */
    private void checkId(final int id, final String name)
    {
        if (id < 0 || id >= forms.size()) {
            throw new IllegalArgumentException(
                    name + " out of range: " + id + " (size=" + forms.size() + ")");
        }
    }
    
    /**
     * Converts the raw return value of {@link CharsDic#add(CharSequence)} to an ordinal.
     *
     * @param raw raw return value
     * @return ordinal
     */
    private static int ord(final int raw)
    {
        return raw >= 0 ? raw : -raw - 1;
    }
}