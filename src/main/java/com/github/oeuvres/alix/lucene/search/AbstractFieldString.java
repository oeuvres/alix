package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.util.IntList;

abstract class AbstractFieldString extends AbstractField
{
    /** for {@link Collection#toArray(Object[])}. */
    public static final BytesRef[] BYTES0 = new BytesRef[0];
    /** Store and populate the search */
    protected final BytesRefHash dic = new BytesRefHash();
    /** Global number of docs relevant for this field */
    protected int docs;
    /** By form, count of docs */
    protected int[] formDocs;
    /** Global number of occurrences for this field */
    protected long occs;

    public AbstractFieldString(IndexReader reader, String fieldName) throws IOException {
        super(reader, fieldName);
    }

    /**
     * Total count of document affected by the field
     * 
     * @return doc count.
     */
    public int docs()
    {
        return docs;
    }

    /**
     * Get String value for a formId.
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
     * Get a String value for a valueId, using a mutable array of bytes.
     * 
     * @param valueId a form id.
     * @param bytes reusable bytes.
     * @return the populated bytes.
     */
    public BytesRef form(int valueId, BytesRef bytes)
    {
        return this.dic.get(valueId, bytes);
    }

    /**
     * How many docs in all index for this valueId ?
     * 
     * @param valueId id of a form.
     * @return doc count.
     */
    public int formDocs(int valueId)
    {
        return formDocs[valueId];
    }

    /**
     * Get forms in order from a vector of valueId.
     * 
     * @param rail sequence of words as valueId.
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
     * Normalize a list of forms to search in an {@link IndexReader}.
     * Filter null and forms absent from the dictionary.
     * 
     * @param dic a dictionary of terms extracted from a field.
     * @param values  collection of form to test against dictionary.
     * @return set of unique values, sorted for efficient search with {@link TermsEnum#seekExact(BytesRef)}.
     */
    public static BytesRef[] bytesSorted(final BytesRefHash dic, CharSequence[] values)
    {
        if (values == null) {
            return null;
        }
        final int maxValue = dic.size();
        final int formsLen = values.length;
        if (formsLen < 1) {
            return null;
        }
        BitSet uniq = new FixedBitSet(maxValue);
        ArrayList<BytesRef> list = new ArrayList<>(formsLen);
        for (int i = 0; i < formsLen; i++) {
            final CharSequence form = values[i];
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
     * Normalize a list of valueIds to search in an {@link IndexReader}.
     * Filter forms absent from the dictionary.
     * 
     * @param dic a dictionary of terms extracted from a field.
     * @param bytesIds  collection of ids to test against dictionary.
     * @return set of unique values, sorted for efficient search with {@link TermsEnum#seekExact(BytesRef)}.
     */
    public static BytesRef[] bytesSorted(final BytesRefHash dic, int[] bytesIds)
    {
        bytesIds = IntList.uniq(bytesIds);
        if (bytesIds == null) {
            return null;
        }
        final int maxValue = dic.size();
        final int bytesLen = bytesIds.length;
        ArrayList<BytesRef> list = new ArrayList<>(bytesLen);
        for (int i = 0; i < bytesLen; i++) {
            final int byteId = bytesIds[i];
            // ?? shall we inform here that there bad values ?
            if (byteId >= maxValue) {
                continue;
            }
            BytesRef bytes = new BytesRef();
            dic.get(i, bytes);
            list.add(bytes);
        }
        if (list.isEmpty()) return null;
        BytesRef[] refs = list.toArray(BYTES0);
        Arrays.sort(refs);
        return refs;
    }


    /**
     * Global count of occurrences (except empty positions) for all index.
     * 
     * @return total occurrences for the index.
     */
    public long occs()
    {
        return occs;
    }
    
    /**
     * Returns valueId &gt;= 0 if exists, or -1 if not, like {@link BytesRefHash#find(BytesRef)}
     * 
     * @param bytes reusable utf8 bytes to look for.
     * @return valueId &gt;= 0 if found, -1 otherwise.
     */
    public int valueId(final BytesRef bytes)
    {
        return dic.find(bytes);
    }

    /**
     * Returns valueId &gt;= 0 if exists, or -1 if not, like {@link BytesRefHash#find(BytesRef)}
     * 
     * @param form char version of a form.
     * @return valueId &gt;= 0 if found, -1 otherwise.
     */
    public int valueId(final CharSequence form)
    {
        BytesRef bytes = new BytesRef(form);
        return dic.find(bytes);
    }

    /**
     * Returns a sorted array of valueId ready for binarySearch, or
     * null if no words found.
     * 
     * @param forms set of forms as {@link CharSequence}
     * @return a sorted array of valueId found, or null if no form found for this fiels.
     * @throws IOException lucene errors.
     */
    public int[] valueIds(CharSequence[] forms) throws IOException
    {
        if (forms == null) return null;
        final int formsLen = forms.length;
        if (formsLen < 0) return null;
        final BytesRef[] formsBytes = new BytesRef[formsLen];
        for (int i = 0; i < formsLen; i++) {
            formsBytes[i] = new BytesRef(forms[i]);
        }
        return valueIds(formsBytes);
    }
    
    /**
     * Returns a sorted array of valueId for found words, ready for binarySearch, or
     * null if not found.
     * 
     * @param forms array of form as bytes.
     * @return a sorted array of valueId found, or null if no form found for this fields.
     * @throws IOException lucene errors.
     */
    public int[] valueIds(BytesRef[] forms) throws IOException
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
}
