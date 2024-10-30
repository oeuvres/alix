package org.apache.lucene.search.vectorhighlight;

import java.io.IOException;
import java.util.Set;

import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;

public class docStats
{
    static public Set<String> terms(
        final String fieldName, 
        final FieldQuery fieldQuery
    ) {
        Set<String> termSet = fieldQuery.getTermSet(fieldName);
        return termSet;
    }
    
    /**
     * Get count of terms from a query.
     *
     * @param reader IndexReader of the index
     * @param docId document id to be highlighted
     * @param fieldName field of the document to get terms from
     * @param fieldQuery FieldQuery object
     * @throws IOException If there is a low-level I/O error
     */
    static public int occs(
        final IndexReader reader, 
        final int docId, 
        final String fieldName, 
        final FieldQuery fieldQuery
    ) throws IOException 
    {
        int occs = 0;
        Set<String> termSet = fieldQuery.getTermSet(fieldName);
        if (termSet == null) return 0;
        final Fields vectors = reader.termVectors().get(docId);
        if (vectors == null) return 0;
        final Terms vector = vectors.terms(fieldName);
        if (vector == null || vector.hasPositions() == false) return 0;

        final CharsRefBuilder spare = new CharsRefBuilder();
        final TermsEnum termsEnum = vector.iterator();
        PostingsEnum dpEnum = null;
        BytesRef text;

        while ((text = termsEnum.next()) != null) {
            spare.copyUTF8Bytes(text);
            final String term = spare.toString();
            if (!termSet.contains(term)) {
                continue;
            }
            dpEnum = termsEnum.postings(dpEnum, PostingsEnum.FREQS);
            dpEnum.nextDoc();
            final int freq = dpEnum.freq();
            occs += freq;
            /*
            // we may have dups at the same position here
            // best approach should loop on position and set a bitSet
            for (int i = 0; i < freq; i++) {
                final int pos = dpEnum.nextPosition();
                if (pos >= 0) {
                    rail.set(pos);
                }
            }
            */
        }
        /*
        final int card = rail.cardinality();
        if (card != occs) {
            System.out.println("hilite docStats occ=" + occs + " card=" + card);
        }
        */
        return occs;
    }


}
