package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.util.CharsDic;
import com.github.oeuvres.alix.util.LongIntMap;

/**
 * Dictionary-backed lemma lexicon with stable integer identifiers.
 *
 * <p>This structure interns all strings (surface forms and lemma forms) into a single
 * {@link CharsDic}. A lemma mapping is then stored as:</p>
 *
 * <pre>
 * (formId, posId) -> lemmaId
 * </pre>
 *
 * <p>where all ids are ordinals in the same {@code forms} dictionary. This design keeps
 * lookup and copy operations allocation-free on hot paths.</p>
 *
 * <h2>Default POS semantics</h2>
 * <p>Entries stored with {@link #DEFAULT_POS_ID} are POS-agnostic fallback mappings. They are
 * useful when the tagger is uncertain, the lexicon is incomplete, or a dictionary source does not
 * provide part-of-speech distinctions.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>This class is not thread-safe for concurrent mutation. Read-only use after construction/freeze
 * is safe only if no other thread mutates the instance.</p>
 */
public final class LemmaLexicon
{
    /**
     * Reserved POS id used for POS-agnostic mappings.
     *
     * <p>An entry {@code (formId, DEFAULT_POS_ID) -> lemmaId} can be used as a fallback when no
     * POS-specific mapping is found.</p>
     */
    public static final int DEFAULT_POS_ID = 0;

    /**
     * Interned string store for both surface forms and lemma forms.
     *
     * <p>Every {@code formId} and {@code lemmaId} used by this class is an ordinal in this
     * dictionary.</p>
     */
    private final CharsDic forms;

    /**
     * Mapping from packed pair {@code (formId, posId)} to {@code lemmaId}.
     *
     * <p>Keys are encoded via {@link LongIntMap#packIntPair(int, int)}.</p>
     */
    private final LongIntMap lemmaByFormPos;

    // ------------------------------------------------------------------------
    // Construction / lifecycle
    // ------------------------------------------------------------------------

    /**
     * Creates a lemma lexicon with an expected number of distinct forms.
     *
     * <p>The value is only a capacity hint used to size internal hash structures. It does not
     * limit the number of entries.</p>
     *
     * @param expectedForms estimated number of distinct strings (surface + lemma forms);
     *        values {@code <= 0} are treated as a small positive default
     */
    public LemmaLexicon(final int expectedForms)
    {
        final int initialCapacity = Math.max(8, expectedForms);
        this.forms = new CharsDic(initialCapacity);
        this.lemmaByFormPos = new LongIntMap(initialCapacity);
    }

    /**
     * Returns the reserved POS id used for POS-agnostic mappings.
     *
     * @return {@link #DEFAULT_POS_ID}
     */
    public int defaultPosId()
    {
        return DEFAULT_POS_ID;
    }

    

    // ------------------------------------------------------------------------
    // Form interning / form dictionary access
    // ------------------------------------------------------------------------

    /**
     * Tests whether the given form slice exists in the lexicon.
     *
     * @param form source character array
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units
     * @return {@code true} if present, otherwise {@code false}
     */
    public boolean containsForm(final char[] form, final int off, final int len)
    {
        return findFormId(form, off, len) >= 0;
    }

    /**
     * Tests whether the given form exists in the lexicon.
     *
     * @param form form string
     * @return {@code true} if present, otherwise {@code false}
     */
    public boolean containsForm(final CharSequence form)
    {
        return findFormId(form) >= 0;
    }

    /**
     * Copies an interned form into a destination array.
     *
     * @param formId form id to copy
     * @param dst destination array
     * @param dstOff destination start offset
     * @return copied length (UTF-16 code units)
     * @throws NullPointerException if {@code dst} is null
     * @throws IllegalArgumentException if {@code formId} is invalid or {@code dst} is too small
     * @throws IndexOutOfBoundsException if {@code dstOff} is invalid
     */
    public int copyForm(final int formId, final char[] dst, final int dstOff)
    {
        return forms.get(formId, dst, dstOff);
    }

    /**
     * Copies an interned form into a Lucene {@link CharTermAttribute}.
     *
     * @param formId form id to copy
     * @param dstAtt destination term attribute
     * @return {@code true} if copied, {@code false} if {@code formId} is out of range
     * @throws NullPointerException if {@code dstAtt} is null
     */
    public boolean copyForm(final int formId, final CharTermAttribute dstAtt)
    {
        if (formId < 0 || formId >= forms.size()) return false;
        final int formLength = forms.termLength(formId);
        final char[] dst = dstAtt.resizeBuffer(formLength);
        forms.get(formId, dst, 0);
        dstAtt.setLength(formLength);
        return true;
    }

    /**
     * Returns an interned form as a newly allocated {@link String}.
     *
     * <p>This is intended for diagnostics and tests, not hot paths.</p>
     *
     * @param formId form id
     * @return form as string
     * @throws IllegalArgumentException if {@code formId} is invalid
     */
    public String formAsString(final int formId)
    {
        return forms.getAsString(formId);
    }

    // ------------------------------------------------------------------------
    // Lemma lookup by ids
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // Form interning / form dictionary access
    // ------------------------------------------------------------------------
    
    // ------------------------------------------------------------------------
    // Form interning / form dictionary access
    // ------------------------------------------------------------------------
    
    /**
     * Interns a slice of a character array and returns its stable {@code formId}.
     *
     * <p>If the form already exists, its existing id is returned.</p>
     *
     * @param form source character array
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units to read
     * @return stable form id (ordinal in the internal {@link CharsDic})
     * @throws NullPointerException if {@code form} is null
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     * @throws IllegalArgumentException if the form length exceeds {@link CharsDic} limits
     */
    public int internForm(final char[] form, final int off, final int len)
    {
        final int result = forms.add(form, off, len);
        return (result >= 0) ? result : (-result - 1);
    }

    /**
     * Interns a form string and returns its stable {@code formId}.
     *
     * <p>If the form already exists, its existing id is returned.</p>
     *
     * @param form form string to intern
     * @return stable form id (ordinal in the internal {@link CharsDic})
     * @throws NullPointerException if {@code form} is null
     * @throws IndexOutOfBoundsException if the provided slice is invalid (via delegated method)
     * @throws IllegalArgumentException if the form length exceeds {@link CharsDic} limits
     */
    public int internForm(final CharSequence form)
    {
        final int result = forms.add(form);
        return (result >= 0) ? result : (-result - 1);
    }

    /**
     * Interns a slice of a character sequence and returns its stable {@code formId}.
     *
     * <p>If the form already exists, its existing id is returned.</p>
     *
     * @param form source character sequence
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units to read
     * @return stable form id (ordinal in the internal {@link CharsDic})
     * @throws NullPointerException if {@code form} is null
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     * @throws IllegalArgumentException if the form length exceeds {@link CharsDic} limits
     */
    public int internForm(final CharSequence form, final int off, final int len)
    {
        final int result = forms.add(form, off, len);
        return (result >= 0) ? result : (-result - 1);
    }

    // ------------------------------------------------------------------------
    // Form interning / form dictionary access
    // ------------------------------------------------------------------------
    
    // ------------------------------------------------------------------------
    // Form interning / form dictionary access
    // ------------------------------------------------------------------------
    
    /**
     * Returns the form id for a form slice, or {@code -1} if unknown.
     *
     * @param form source character array
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units to read
     * @return form id, or {@code -1} if absent
     */
    public int findFormId(final char[] form, final int off, final int len)
    {
        return forms.find(form, off, len);
    }

    // ------------------------------------------------------------------------
    // Form interning / form dictionary access
    // ------------------------------------------------------------------------
    
    /**
     * Returns the form id for the contents of a Lucene {@link CharTermAttribute}, or {@code -1} if
     * unknown.
     *
     * @param formAtt source term attribute
     * @return form id, or {@code -1} if absent
     * @throws NullPointerException if {@code formAtt} is null
     */
    public int findFormId(final CharTermAttribute formAtt)
    {
        return forms.find(formAtt.buffer(), 0, formAtt.length());
    }

    /**
     * Returns the form id for a form string, or {@code -1} if unknown.
     *
     * @param form surface form
     * @return form id, or {@code -1} if absent
     */
    public int findFormId(final CharSequence form)
    {
        return forms.find(form);
    }

    // ------------------------------------------------------------------------
    // Form interning / form dictionary access
    // ------------------------------------------------------------------------
    
    /**
     * Returns the form id for a form slice, or {@code -1} if unknown.
     *
     * @param form source character sequence
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units to read
     * @return form id, or {@code -1} if absent
     */
    public int findFormId(final CharSequence form, final int off, final int len)
    {
        return forms.find(form, off, len);
    }

    /**
     * Returns the lemma form id for a POS-agnostic mapping of {@code formId}.
     *
     * <p>This is equivalent to {@link #findLemmaId(int, int) findLemmaId(formId, DEFAULT_POS_ID)}.</p>
     *
     * @param formId form id
     * @return lemma form id, or {@code -1} if missing
     */
    public int findLemmaId(final int formId)
    {
        return lemmaByFormPos.get(formId, DEFAULT_POS_ID);
    }

    /**
     * Returns the lemma form id for the pair {@code (formId, posId)}.
     *
     * @param formId form id
     * @param posId part-of-speech id
     * @return lemma form id, or {@code -1} if missing
     */
    public int findLemmaId(final int formId, final int posId)
    {
        return lemmaByFormPos.get(formId, posId);
    }

    /**
     * Returns the lemma form id for {@code (formId, posId)} and falls back to
     * {@link #DEFAULT_POS_ID} if no POS-specific mapping exists.
     *
     * <p>This method is useful when POS tagging is uncertain or the dictionary is incomplete.</p>
     *
     * @param formId form id
     * @param posId part-of-speech id
     * @return POS-specific lemma form id if present, otherwise POS-agnostic lemma form id, or
     *         {@code -1} if neither exists
     */
    public int findLemmaIdOrDefaultPos(final int formId, final int posId)
    {
        final int lemmaId = findLemmaId(formId, posId);
        if (lemmaId >= 0 || posId == DEFAULT_POS_ID) return lemmaId;
        return findLemmaId(formId, DEFAULT_POS_ID);
    }

    

    

    // ------------------------------------------------------------------------
    // One-shot lookup from form text
    // ------------------------------------------------------------------------

    /**
     * Resolves a lemma form id from a form slice and POS id.
     *
     * <p>This performs two lookups:
     * <ol>
     *   <li>form text -> {@code formId}</li>
     *   <li>{@code (formId, posId)} -> {@code lemmaId}</li>
     * </ol>
     * and returns {@code -1} if either stage fails.</p>
     *
     * @param form source character array
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units
     * @param posId part-of-speech id
     * @return lemma form id, or {@code -1} if the form is unknown or mapping is missing
     */
    public int findLemmaId(final char[] form, final int off, final int len, final int posId)
    {
        final int formId = forms.find(form, off, len);
        if (formId < 0) return -1;
        return lemmaByFormPos.get(formId, posId);
    }

    /**
     * Resolves a POS-agnostic lemma form id from a form slice.
     *
     * @param form source character array
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units
     * @return lemma form id, or {@code -1} if the form is unknown or mapping is missing
     */
    public int findLemmaId(final char[] form, final int off, final int len)
    {
        return findLemmaId(form, off, len, DEFAULT_POS_ID);
    }

    /**
     * Resolves a lemma form id from a form slice and POS id.
     *
     * @param form source character sequence
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units
     * @param posId part-of-speech id
     * @return lemma form id, or {@code -1} if the form is unknown or mapping is missing
     */
    public int findLemmaId(final CharSequence form, final int off, final int len, final int posId)
    {
        final int formId = forms.find(form, off, len);
        if (formId < 0) return -1;
        return lemmaByFormPos.get(formId, posId);
    }

    /**
     * Resolves a POS-agnostic lemma form id from a form slice.
     *
     * @param form source character sequence
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units
     * @return lemma form id, or {@code -1} if the form is unknown or mapping is missing
     */
    public int findLemmaId(final CharSequence form, final int off, final int len)
    {
        return findLemmaId(form, off, len, DEFAULT_POS_ID);
    }

    /**
     * Resolves a lemma form id from a full form string and POS id.
     *
     * @param form form string
     * @param posId part-of-speech id
     * @return lemma form id, or {@code -1} if unknown
     */
    public int findLemmaId(final CharSequence form, final int posId)
    {
        final int formId = forms.find(form);
        if (formId < 0) return -1;
        return lemmaByFormPos.get(formId, posId);
    }

    /**
     * Resolves a POS-agnostic lemma form id from a full form string.
     *
     * @param form form string
     * @return lemma form id, or {@code -1} if unknown
     */
    public int findLemmaId(final CharSequence form)
    {
        return findLemmaId(form, DEFAULT_POS_ID);
    }

    /**
     * Resolves a POS-agnostic lemma form id from a Lucene term attribute.
     *
     * @param formAtt source term attribute
     * @return lemma form id, or {@code -1} if unknown
     * @throws NullPointerException if {@code formAtt} is null
     */
    public int findLemmaId(final CharTermAttribute formAtt)
    {
        return findLemmaId(formAtt, DEFAULT_POS_ID);
    }

    /**
     * Resolves a lemma form id from a Lucene term attribute and POS id.
     *
     * @param formAtt source term attribute
     * @param posId part-of-speech id
     * @return lemma form id, or {@code -1} if unknown
     * @throws NullPointerException if {@code formAtt} is null
     */
    public int findLemmaId(final CharTermAttribute formAtt, final int posId)
    {
        final int formId = findFormId(formAtt);
        if (formId < 0) return -1;
        return lemmaByFormPos.get(formId, posId);
    }

    // ------------------------------------------------------------------------
    // Lemma copy helpers (TokenFilter-oriented)
    // ------------------------------------------------------------------------

    /**
     * Returns the number of distinct interned forms (surface + lemma forms).
     *
     * @return number of interned forms
     */
    public int formCount()
    {
        return forms.size();
    }

    /**
     * Returns the length of the interned form identified by {@code formId}.
     *
     * @param formId form id
     * @return form length (UTF-16 code units)
     * @throws IllegalArgumentException if {@code formId} is invalid
     */
    public int formLength(final int formId)
    {
        return forms.termLength(formId);
    }

    /**
     * Returns the offset of the interned form identified by {@code formId} in the internal slab.
     *
     * <p>This is an advanced method. The returned offset is valid only against the array returned by
     * {@link #formSlab()}.</p>
     *
     * @param formId form id
     * @return offset in the internal slab
     * @throws IllegalArgumentException if {@code formId} is invalid
     */
    public int formOffset(final int formId)
    {
        return forms.termOffset(formId);
    }

    /**
     * Returns the internal character slab used by the underlying dictionary.
     *
     * <p>The returned array is internal mutable storage and must be treated as read-only.</p>
     *
     * @return internal form slab
     */
    public char[] formSlab()
    {
        return forms.slab();
    }

    /**
     * Shrinks internal storage to reduce memory footprint after bulk loading.
     *
     * <p>This method is optional and not required for correctness. It is typically called once
     * after all entries have been inserted.</p>
     */
    public void trimToSize()
    {
        forms.trimToSize();
        lemmaByFormPos.trimToSize();
    }

    /**
     * Resolves and copies the lemma form for {@code srcFormAtt} + {@code posId} into
     * {@code dstLemmaAtt}.
     *
     * <p>No fallback to {@link #DEFAULT_POS_ID} is performed. Use
     * {@link #lemmaToBufferOrDefaultPos(CharTermAttribute, int, CharTermAttribute)} if you want a
     * POS-agnostic fallback.</p>
     *
     * @param srcFormAtt source form attribute
     * @param posId part-of-speech id
     * @param dstLemmaAtt destination attribute receiving lemma characters
     * @return resolved lemma form id, or {@code -1} if the form is unknown or the mapping is missing
     */
    public int lemmaToBuffer(final CharTermAttribute srcFormAtt, final int posId, final CharTermAttribute dstLemmaAtt)
    {
        final int lemmaId = findLemmaId(srcFormAtt, posId);
        if (lemmaId < 0) return -1;
        copyForm(lemmaId, dstLemmaAtt);
        return lemmaId;
    }

    /**
     * Resolves and copies the POS-agnostic lemma form for {@code srcFormAtt} into
     * {@code dstLemmaAtt}.
     *
     * @param srcFormAtt source form attribute
     * @param dstLemmaAtt destination attribute receiving lemma characters
     * @return resolved lemma form id, or {@code -1} if the form is unknown or the mapping is missing
     */
    public int lemmaToBuffer(final CharTermAttribute srcFormAtt, final CharTermAttribute dstLemmaAtt)
    {
        final int lemmaId = findLemmaId(srcFormAtt, DEFAULT_POS_ID);
        if (lemmaId < 0) return -1;
        copyForm(lemmaId, dstLemmaAtt);
        return lemmaId;
    }


    // ------------------------------------------------------------------------
    // Mapping insertion API
    // ------------------------------------------------------------------------

    /**
     * Returns the maximum length of any interned form (in UTF-16 code units).
     *
     * <p>Useful to size reusable scratch buffers in token filters.</p>
     *
     * @return maximum form length
     */
    public int maxFormLength()
    {
        return forms.maxTermLength();
    }

    /**
     * Inserts a mapping {@code (form, posId) -> lemmaForm}.
     *
     * <p>Both strings are interned (or reused if already interned), then the mapping is stored
     * according to {@code policy}.</p>
     *
     * @param form surface form
     * @param posId part-of-speech id
     * @param lemmaForm lemma form
     * @param policy duplicate handling policy
     * @return {@code lemmaId}
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code policy == ERROR} and a conflicting mapping exists
     */
    public int putEntry(final CharSequence form, final int posId, final CharSequence lemmaForm, final OnDuplicate policy)
    {
        return putEntry(form, 0, form.length(), posId, lemmaForm, 0, lemmaForm.length(), policy);
    }

    /**
     * Inserts a POS-agnostic mapping {@code (form, DEFAULT_POS_ID) -> lemmaForm} from character
     * arrays.
     *
     * @param form source surface form array
     * @param formOff form start offset
     * @param formLen form length
     * @param lemmaForm source lemma form array
     * @param lemmaOff lemma start offset
     * @param lemmaLen lemma length
     * @param policy duplicate handling policy
     * @return {@code lemmaId}
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code policy == ERROR} and a conflicting mapping exists
     */
    public int putEntry(
        final char[] form,
        final int formOff,
        final int formLen,
        final char[] lemmaForm,
        final int lemmaOff,
        final int lemmaLen,
        final OnDuplicate policy
    ) {
        return putEntry(form, formOff, formLen, DEFAULT_POS_ID, lemmaForm, lemmaOff, lemmaLen, policy);
    }

    /**
     * Inserts a POS-agnostic mapping {@code (form, DEFAULT_POS_ID) -> lemmaForm} from character
     * sequences.
     *
     * @param form source surface form sequence
     * @param formOff form start offset
     * @param formLen form length
     * @param lemmaForm source lemma form sequence
     * @param lemmaOff lemma start offset
     * @param lemmaLen lemma length
     * @param policy duplicate handling policy
     * @return {@code lemmaId}
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code policy == ERROR} and a conflicting mapping exists
     */
    public int putEntry(
        final CharSequence form,
        final int formOff,
        final int formLen,
        final CharSequence lemmaForm,
        final int lemmaOff,
        final int lemmaLen,
        final OnDuplicate policy
    ) {
        return putEntry(form, formOff, formLen, DEFAULT_POS_ID, lemmaForm, lemmaOff, lemmaLen, policy);
    }

    /**
     * Inserts a mapping {@code (form, posId) -> lemmaForm} from character sequences.
     *
     * @param form source surface form sequence
     * @param formOff form start offset
     * @param formLen form length
     * @param posId part-of-speech id
     * @param lemmaForm source lemma form sequence
     * @param lemmaOff lemma start offset
     * @param lemmaLen lemma length
     * @param policy duplicate handling policy
     * @return {@code lemmaId}
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code policy == ERROR} and a conflicting mapping exists
     */
    public int putEntry(
        final CharSequence form,
        final int formOff,
        final int formLen,
        final int posId,
        final CharSequence lemmaForm,
        final int lemmaOff,
        final int lemmaLen,
        final OnDuplicate policy
    ) {
        final int formId = internForm(form, formOff, formLen);
        final int lemmaId = internForm(lemmaForm, lemmaOff, lemmaLen);
        putEntry(formId, posId, lemmaId, policy);
        return lemmaId;
    }

    /**
     * Inserts a mapping {@code (form, posId) -> lemmaForm} from character arrays.
     *
     * @param form source surface form array
     * @param formOff form start offset
     * @param formLen form length
     * @param posId part-of-speech id
     * @param lemmaForm source lemma form array
     * @param lemmaOff lemma start offset
     * @param lemmaLen lemma length
     * @param policy duplicate handling policy
     * @return {@code lemmaId}
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code policy == ERROR} and a conflicting mapping exists
     */
    public int putEntry(
        final char[] form,
        final int formOff,
        final int formLen,
        final int posId,
        final char[] lemmaForm,
        final int lemmaOff,
        final int lemmaLen,
        final OnDuplicate policy
    ) {
        final int formId = internForm(form, formOff, formLen);
        final int lemmaId = internForm(lemmaForm, lemmaOff, lemmaLen);
        putEntry(formId, posId, lemmaId, policy);
        return lemmaId;
    }

    /**
     * Inserts a mapping using already interned ids.
     *
     * <p>This is the lowest-level insertion method. It does not intern strings. The ids must refer
     * to entries already present in this lexicon's form dictionary.</p>
     *
     * @param formId interned form id
     * @param posId part-of-speech id
     * @param lemmaId interned lemma form id
     * @param policy duplicate handling policy
     * @throws NullPointerException if {@code policy} is null
     * @throws IllegalArgumentException if ids are invalid, or if {@code policy == ERROR} and a
     *         conflicting mapping exists
     */
    public void putEntry(final int formId, final int posId, final int lemmaId, final OnDuplicate policy)
    {
        if (policy == null) throw new NullPointerException("policy");
        checkAssignedFormId(formId, "formId");
        checkAssignedFormId(lemmaId, "lemmaId");

        final long key = LongIntMap.packIntPair(formId, posId);
        switch (policy) {
            case IGNORE:
                lemmaByFormPos.putIfAbsent(key, lemmaId);
                break;
            case REPLACE:
                lemmaByFormPos.put(key, lemmaId);
                break;
            case ERROR:
                final int previous = lemmaByFormPos.putIfAbsent(key, lemmaId);
                if (previous != lemmaByFormPos.missingValue() && previous != lemmaId) {
                    throw new IllegalArgumentException(
                        "Duplicate (formId,posId) with different lemmaId: formId=" + formId
                        + ", posId=" + posId
                        + ", previous lemmaId=" + previous + " " + formAsString(previous)
                        + ", new lemmaId=" + lemmaId + " " + formAsString(lemmaId)
                    );
                }
                break;
            default:
                throw new AssertionError(policy);
        }
    }

    // ------------------------------------------------------------------------
    // Duplicate policy
    // ------------------------------------------------------------------------

    /**
     * Duplicate handling policy for {@code putEntry(...)} methods.
     */
    public enum OnDuplicate
    {
        /**
         * Keep the existing mapping and ignore the new one.
         *
         * <p>If the key is absent, insert the new mapping.</p>
         */
        IGNORE,

        /**
         * Replace any existing mapping with the new one.
         */
        REPLACE,

        /**
         * Insert only if absent; if a different mapping already exists for the same key, throw an
         * exception.
         *
         * <p>Reinserting the exact same mapping is accepted.</p>
         */
        ERROR
    }

    // ------------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------------

    /**
     * Validates that a form id refers to an assigned entry in the internal form dictionary.
     *
     * @param formId candidate id
     * @param name parameter name for error reporting
     * @throws IllegalArgumentException if the id is invalid
     */
    private void checkAssignedFormId(final int formId, final String name)
    {
        if (formId < 0 || formId >= forms.size()) {
            throw new IllegalArgumentException(name + " out of range: " + formId + " (size=" + forms.size() + ")");
        }
    }
}
