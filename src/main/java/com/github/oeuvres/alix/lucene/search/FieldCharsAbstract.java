package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.util.IntList;

/**
 * Stats for a field with chars, indexed {@link TextField}, or not, like for facets {@link StringField},
 * {@link SortedDocValuesField} or {@link SortedSetDocValuesField}.
 */
abstract class FieldCharsAbstract extends FieldAbstract
{
    /** for {@link Collection#toArray(Object[])}. */
    public static final BytesRef[] BYTES0 = new BytesRef[0];
    /** Number of different values found = biggest formId + 1. */
    protected int maxForm = -1;
    /** Dictionary of terms from this field. */
    protected final BytesRefHash dic = new BytesRefHash();
    /** docsByform[formId] = docs; count of docs by form. */
    protected int[] docsByForm;

    public FieldCharsAbstract(DirectoryReader reader, String fieldName) throws IOException {
        super(reader, fieldName);
    }

    /**
     * How many docs in all index for this formId ?
     * 
     * @param formId id of a form.
     * @return doc count.
     */
    public int docs(int formId)
    {
        return docsByForm[formId];
    }

    /**
     * Get String form for a formId.
     * 
     * @param formId a form id.
     * @return the chars as a string.
     */
    public String form(final int formId)
    {
        BytesRef bytes = new BytesRef();
        this.dic.get(formId, bytes);
        return bytes.utf8ToString();
    }

    /**
     * Get a String form for a formId, using a mutable array of bytes.
     * 
     * @param formId a form id.
     * @param bytes reusable bytes.
     * @return the populated bytes.
     */
    public BytesRef form(int formId, BytesRef bytes)
    {
        return this.dic.get(formId, bytes);
    }

    /**
     * Returns formId &gt;= 0 if exists, or -1 if not, like {@link BytesRefHash#find(BytesRef)}
     * 
     * @param bytes reusable utf8 bytes to look for.
     * @return formId &gt;= 0 if found, -1 otherwise.
     */
    public int formId(final BytesRef bytes)
    {
        return dic.find(bytes);
    }

    /**
     * Returns formId &gt;= 0 if exists, or -1 if not, like {@link BytesRefHash#find(BytesRef)}
     * 
     * @param form char version of a form.
     * @return formId &gt;= 0 if found, -1 otherwise.
     */
    public int formId(final CharSequence form)
    {
        BytesRef bytes = new BytesRef(form);
        return dic.find(bytes);
    }

    /**
     * Returns a sorted array of formId ready for binarySearch, or
     * null if no words found.
     * 
     * @param forms set of forms as {@link CharSequence}
     * @return a sorted array of formId found, or null if no form found for this fiels.
     * @throws IOException lucene errors.
     */
    public int[] formIds(CharSequence[] forms) throws IOException
    {
        if (forms == null) return null;
        final BytesRef[] formsBytes = bytesSorted(forms);
        return formIds(formsBytes);
    }

    /**
     * Returns a sorted array of formId for found words, ready for binarySearch, or
     * null if not found.
     * 
     * @param forms array of form as bytes.
     * @return a sorted array of formId found, or null if no form found for this fields.
     * @throws IOException lucene errors.
     */
    public int[] formIds(BytesRef[] forms) throws IOException
    {
        if (forms == null) {
            return null;
        }
        final int formsLen = forms.length;
        if (formsLen < 1) {
            return null;
        }
        IntList list = new IntList();
        for (int i = 0; i < formsLen; i++) {
            final BytesRef bytes = forms[i];
            if (bytes == null) continue;
            final int formId = dic.find(bytes);
            // form not known, OK
            if (formId < 0) continue;
            list.push(formId);
        }
        if (list.isEmpty()) {
            return null;
        }
        int[] pivots = list.uniq();
        return pivots;
    }

    /**
     * Get forms in order from a vector of formId.
     * 
     * @param rail sequence of words as formId.
     * @return sequence of words as String.
     */
    public String[] forms(int[] rail)
    {
        int len = rail.length;
        String[] words = new String[len];
        BytesRef ref = new BytesRef();
        for (int i = 0; i < len; i++) {
            int formId = rail[i];
            this.dic.get(formId, ref);
            words[i] = ref.utf8ToString();
        }
        return words;
    }
    
    /**
     * Count of different values.
     * Biggest formId+1 like {@link IndexReader#maxDoc()}. Used to build fixed array of form id.
     * 
     * @return max value id.
     */
    public int maxForm()
    {
        return maxForm;
    }
    
    /**
     * Normalize a list of forms to search in an {@link IndexReader}.
     * Filter null and forms absent from the dictionary.
     * 
     * @param forms  collection of forms to test against dictionary.
     * @return set of unique forms, sorted for efficient search with {@link TermsEnum#seekExact(BytesRef)}.
     */
    public BytesRef[] bytesSorted(CharSequence[] forms)
    {
        if (forms == null) {
            return null;
        }
        final int formsLen = forms.length;
        if (formsLen < 1) {
            return null;
        }
        final int maxForm = dic.size();
        BitSet uniq = new FixedBitSet(maxForm);
        ArrayList<BytesRef> list = new ArrayList<>(formsLen);
        for (int i = 0; i < formsLen; i++) {
            final CharSequence form = forms[i];
            if (form == null) continue;
            BytesRef bytes = new BytesRef(form);
            final int formId = dic.find(bytes);
            // unknown, continue
            if (formId < 0) continue;
            // already seen
            if (uniq.get(formId)) continue;
            uniq.set(formId);
            list.add(bytes);
        }
        if (list.isEmpty()) return null;
        BytesRef[] refs = list.toArray(BYTES0);
        Arrays.sort(refs);
        return refs;
    }
    
    /**
     * Normalize a list of formIds to search in an {@link IndexReader}.
     * Filter forms absent from the dictionary.
     * 
     * @param formIds  collection of ids to test against dictionary.
     * @return set of unique forms, sorted for efficient search with {@link TermsEnum#seekExact(BytesRef)}.
     */
    public BytesRef[] bytesSorted(int[] formIds)
    {
        formIds = IntList.uniq(formIds);
        if (formIds == null) {
            return null;
        }
        final int maxForm = dic.size();
        final int bytesLen = formIds.length;
        ArrayList<BytesRef> list = new ArrayList<>(bytesLen);
        for (int i = 0; i < bytesLen; i++) {
            final int formId = formIds[i];
            // ?? shall we inform here that there bad values ?
            if (formId < 1 || formId >= maxForm) {
                continue;
            }
            BytesRef bytes = new BytesRef();
            dic.get(formId, bytes);
            list.add(bytes);
        }
        if (list.isEmpty()) return null;
        BytesRef[] refs = list.toArray(BYTES0);
        Arrays.sort(refs);
        return refs;
    }
    

}
