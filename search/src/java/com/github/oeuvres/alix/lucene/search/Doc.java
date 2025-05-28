/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.lucene.search;


import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

import static com.github.oeuvres.alix.common.Flags.*;
import static com.github.oeuvres.alix.common.Names.*;
import com.github.oeuvres.alix.common.TagFilter;
import com.github.oeuvres.alix.lucene.index.BytesDic;
import com.github.oeuvres.alix.util.Top;

/**
 * Tools to display a document
 */
public class Doc
{
    /** Just the mandatory fields */
    final static HashSet<String> FIELDS_REQUIRED = new HashSet<String>(
            Arrays.asList(new String[] { ALIX_FILENAME, ALIX_BOOKID, ALIX_ID, ALIX_TYPE }));
    /** Format numbers with the dot */
    final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
    /** The lucene index to read in */
    final private AlixReader alix;
    /** Id of a document in this reader {@link IndexReader#document(int)} */
    final int docId;
    /** Permanent id for the document */
    final String id;
    /** Set of fields loaded */
    final private HashSet<String> fieldsToLoad;
    /** The loaded fields */
    final private Document document;

    /**
     * Get a document by String id (persists as long as the source XML doc is not
     * modified) with all fields loaded (even the big ones).
     * 
     * @param alix wrapper on a lucene index.
     * @param id persistant external id of document.
     * @throws IOException Lucene errors.
     */
    public Doc(final AlixReader alix, final String id) throws IOException {
        this(alix, id, null);
    }

    /**
     * Get a document by String id (persists as long as the source XML doc is not
     * modified), with the set of fields provided (or all fields if fieldsToLoad is
     * null).
     * 
     * @param alix wrapper on a lucene index.
     * @param id persistant external id of document.
     * @param fieldsToLoad list of stored fields to load.
     * @throws IOException Lucene errors.
     */
    public Doc(final AlixReader alix, final String id, final HashSet<String> fieldsToLoad) throws IOException {
        int docId = alix.getDocId(id);
        if (docId < 0) {
            throw new IllegalArgumentException("No document found with id: " + id);
        }
        StoredFields fread = alix.reader().storedFields();
        if (fieldsToLoad == null) {
            document = fread.document(docId);
        } else {
            fieldsToLoad.addAll(FIELDS_REQUIRED);
            document = fread.document(docId, fieldsToLoad);
        }
        this.alix = alix;
        this.docId = docId;
        this.id = id;
        this.fieldsToLoad = fieldsToLoad;
    }

    /**
     * Get a document by lucene docId (persists as long as the Lucene index is not
     * modified) with all fields loaded (even the big ones).
     * 
     * @param alix wrapper on a lucene index.
     * @param docId internal lucene docId.
     * @throws IOException Lucene errors.
     */
    public Doc(final AlixReader alix, final int docId) throws IOException {
        this(alix, docId, null);
    }

    /**
     * Get a document by lucene docId (persists as long as the Lucene index is not
     * modified) with the set of fields provided (or all fields fieldsToLoad is
     * null).
     * 
     * @param alix wrapper on a lucene index.
     * @param docId internal lucene docId.
     * @param fieldsToLoad list of stored fields to load.
     * @throws IOException Lucene errors.
     */
    public Doc(final AlixReader alix, final int docId, final HashSet<String> fieldsToLoad) throws IOException {
        StoredFields fread = alix.reader().storedFields();
        if (fieldsToLoad == null) {
            document = fread.document(docId);
        } else {
            fieldsToLoad.addAll(FIELDS_REQUIRED);
            document = fread.document(docId, fieldsToLoad);
        }
        if (document == null) {
            throw new IllegalArgumentException("No stored fields found for docId: " + docId);
        }
        this.alix = alix;
        this.docId = docId;
        this.id = document.get(ALIX_ID);
        this.fieldsToLoad = fieldsToLoad;
    }

    /**
     * With this docId and another docId, with a field name, get stored text, get offsets for indexed term, 
     * hilite specific terms of this doc, absent of the other.
     * 
     * @param field field name with a stored text and terms indexed with offsets.
     * @param docId2 id of another docuent to compare with.
     * @return stored text hilited.
     * @throws IOException          Lucene errors.
     * @throws NoSuchFieldException Field doesn’t exists.
     */
    public String contrast(final String field, final int docId2, final BytesDic stopwords) throws IOException, NoSuchFieldException
    {
        return contrast(field, docId2, stopwords, false);
    }

    /**
     * With this docId and another docId, with a field name, get stored text, get offsets for indexed term, 
     * hilite specific terms of this doc, absent of the other.
     * 
     * @param field field name with a stored text and terms indexed with offsets.
     * @param docId2 id of another docuent to compare with.
     * @param right if contrasted doc is displayed on a right side.
     * @return stored text hilited.
     * @throws IOException          Lucene errors.
     * @throws NoSuchFieldException Field doesn’t exists.
     */
    public String contrast(final String field, final int docId2, final BytesDic stopwords, final boolean right)
            throws IOException, NoSuchFieldException
    {
        String text = get(field);
        StringBuilder sb = new StringBuilder();

        FieldText ftext = alix.fieldText(field);
        int len1 = ftext.occsByDoc(docId);
        int len2 = ftext.occsByDoc(docId2);

        Terms vek1 = getTermVector(field);
        Terms vek2 = alix.reader().termVectors().get(docId2, field);
        TermsEnum termit1 = vek1.iterator();
        BytesRef term1;
        TermsEnum termit2 = vek2.iterator();
        BytesRef term2 = termit2.next();
        ArrayList<Token> offsets = new ArrayList<Token>();
        PostingsEnum postings = null;
        // loop on search source, compare with dest
        double max1 = Double.MIN_VALUE;
        double max2 = Double.MIN_VALUE;
        while (termit1.next() != null) {
            term1 = termit1.term();
            if (stopwords != null && stopwords.contains(term1)) continue;

            // termit1.ord(); UnsupportedOperationException
            final int count1 = (int) termit1.totalTermFreq();
            String form = term1.utf8ToString();
            int count2 = 0;
            while (true) {
                if (term2 == null)
                    break;
                int comp = term1.compareTo(term2);
                if (comp < 0)
                    break; // term2 is bigger, get it after
                if (comp == 0) { // match
                    count2 = (int) termit2.totalTermFreq();
                    break;
                }
                term2 = termit2.next();
            }
            if (max1 < count1)
                max1 = count1;
            if (max2 < count2)
                max2 = count2;
            // loop on positions to get offset
            postings = termit1.postings(postings, PostingsEnum.OFFSETS);
            while (postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
                int pos = -1;
                for (int freq = postings.freq(); freq > 0; freq--) {
                    pos = postings.nextPosition();
                    offsets.add(new Token(pos, postings.startOffset(), postings.endOffset(), form, count1, count2));
                }
            }
        }
        Collections.sort(offsets); // sort offsets before hilite
        int off = 0;
        final double scoremax = max1 / len1 + max2 / len2;
        for (int i = 0, size = offsets.size(); i < size; i++) {
            Token tok = offsets.get(i);
            double count1 = tok.count;
            double count2 = tok.count2;
            // skip token
            if (count2 == 0 && count1 < 2)
                continue;
            sb.append(text.substring(off, tok.start)); // append text before token
            String type = "tokshared";
            // specific to this doc
            if (count2 == 0)
                type = "tokspec";
            // change boldness
            double score = count1 / len1 + count2 / len2;
            double sum = count1 + count2;
            String level = "em1";
            if (score >= 0.6 * scoremax)
                level = "em9";
            else if (score >= 0.3 * scoremax)
                level = "em5";
            else if (sum > 4)
                level = "em3";
            else
                level = "em2";

            String form = tok.form.replace(' ', '_');
            String title = "";
            if (right)
                title += (int) tok.count2 + " | " + (int) tok.count;
            else
                title += (int) tok.count + " | " + (int) tok.count2;
            title += " occurrences";
            sb.append("<a id=\"tok" + tok.pos + "\" class=\"" + type + " " + form + " " + level + "\" title=\"" + title
                    + "\">");
            sb.append(text.substring(tok.start, tok.end));
            sb.append("</a>");
            off = tok.end;
        }
        sb.append(text.substring(off)); // do not forget end
        return sb.toString();
    }

    /**
     * Build a token compatible with a css className with any kind of lexical word.
     * 
     * @param form a word.
     * @return a css token for a class name.
     */
    static public String csstok(String form)
    {
        return form.replaceAll("[ \\.<>&\"']", "_");
    }

    /**
     * Returns the loaded lucene document.
     *
     * @return set of {@link Field}s.
     */
    public Document doc()
    {
        return document;
    }

    /**
     * Returns the local Lucene int docId of the document.
     * 
     * @return docId.
     */
    public int docId()
    {
        return docId;
    }

    /**
     * Count of occurrences by term for the document. Returns an iterator sorted
     * according to a scorer. If scorer is null, default is count of occurrences.
     * {@link FieldText#formEnum(org.apache.lucene.util.BitSet, TagFilter, OptionDistrib)}
     * 
     * @param alix wrapper around an {@link IndexReader} with cached stats.
     * @param docId a document id in t
     * @param field text field name for a {@link FieldText}.
     * @param distrib score for the terms.
     * @param formFilter filter words by tags.
     * @return forms for this document.
     * @throws NoSuchFieldException  not a text field.
     * @throws IOException           Lucene errors.
     */
    static public FormEnum formEnum(AlixReader alix, int docId, String field, Distrib distrib, boolean nostop)
            throws NoSuchFieldException, IOException
    {
        boolean hasDistrib = (distrib != null);

        // get index term stats
        FieldText fieldText = alix.fieldText(field);
        FormEnum forms = fieldText.formEnum();
        if (hasDistrib) {
            forms.scoreByForm = new double[fieldText.maxForm];
        }
        forms.formId4freq = new long[fieldText.maxForm]; // freqs by form
        int occsByDoc = fieldText.occsByDoc(docId);

        // loop on all forms of the document, get score, keep the top
        // final long restLen = fieldText.occsAll - occsDoc;
        Terms tvek = alix.reader().termVectors().get(docId, field);
        if (tvek == null) {
            throw new NoSuchFieldException("Missing search Vector for field=" + field + " docId=" + docId);
        }
        TermsEnum termit = tvek.iterator();
        while (termit.next() != null) {
            BytesRef bytes = termit.term();
            if (bytes.length < 1) {
                continue;
            }
            final int formId = fieldText.formId(bytes);
            if (nostop) {
                if (fieldText.isStop(formId)) continue;
            }
            if (hasDistrib) {
                distrib.expectation(fieldText.occs(formId), fieldText.occsAll);
                distrib.idf(fieldText.formId4docs[formId], fieldText.docsAll, fieldText.occsAll);
            }
            // scorer.weight(termOccs, termDocs); // collection level stats
            long freq = termit.totalTermFreq();
            forms.formId4freq[formId] = freq;
            forms.freqAll += freq;
            if (hasDistrib) {
                forms.scoreByForm[formId] += distrib.score(freq, occsByDoc);
                // scores[formId] -= scorer.last(formOccsAll[formId] - freq, restLen); // sub
                // complement ?
            }
        }
        // add some more stats on this iterator
        return forms;
    }

    /**
     * Count occurences of terms in a doc.
     *
     * @param field  name of a field with indexed term.
     * @param forms  Array of forms.
     * @return Occurrences count for founded forms.
     * @throws NoSuchFieldException  not a text field.
     * @throws IOException           Lucene errors.
     */
    public int freq(final String field, final String[] forms) throws NoSuchFieldException, IOException
    {
        return freq(alix.reader(), this.docId, field, forms);
    }

    /**
     * Count occurences of terms in a doc.
     *
     * @param reader A Lucene reader to get stats from.
     * @param docId  Lucene internal id of a doc {@link StoredFields#document(int)}.
     * @param field  name of a field with indexed term.
     * @param forms  Array of forms.
     * @return Occurrences count for founded forms.
     * @throws NoSuchFieldException  not a text field.
     * @throws IOException           Lucene errors.
     */
    static public int freq(final IndexReader reader, final int docId, final String field, final String[] forms)
            throws NoSuchFieldException, IOException
    {
        if (forms == null || forms.length < 1)
            return 0;
        Arrays.sort(forms); // may optimize term seekink, useful for uniq
        Terms tvek = reader.termVectors().get(docId, field);

        if (!tvek.hasFreqs()) {
            throw new NoSuchFieldException("Missing freqs in TermVector for field=" + field + " docId=" + docId);
        }
        int freq = 0;
        TermsEnum tenum = tvek.iterator();
        if (tenum == null) {
            throw new NoSuchFieldException("Missing freqs in TermVector for field=" + field + " docId=" + docId);
        }
        PostingsEnum postings = null;
        String last = null;
        if (tenum.next() != null) { // if not, Exception: DocsEnum not started
            for (String form : forms) {
                if (form.equals(last))
                    continue; // uniq
                BytesRef ref = new BytesRef(form);
                if (!tenum.seekExact(ref))
                    continue;
                // set and get a postings to this tenum, should be there, before will not work
                postings = tenum.postings(postings, PostingsEnum.FREQS);
                if (postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) { // if not, Exception: DocsEnum not started
                    freq += postings.freq();
                }
                last = form;
            }
        }
        return freq;
    }

    /**
     * Get contents of a field as a {@link String}.
     * 
     * @param field name of aa field with stored contents.
     * @return stored contents.
     * @throws NoSuchFieldException  not a text field.
     */
    public String get(String field) throws NoSuchFieldException
    {
        // inform user that he has not load the field he wants
        if (fieldsToLoad != null && !fieldsToLoad.contains(field)) {
            throw new NoSuchFieldException(
                    "The field \"" + field + "\" has not been loaded with the document \"" + id + "\"");
        }
        String text = document.get(field);
        // too hard to send exceptions when used with wild life documents. Let user deal
        // with null
        // throw new NoSuchFieldException("No text for the field \""+field+"\" in the
        // document \""+id+"\"");
        return text;
    }

    /**
     * Get a term vector for a field of this document.
     * 
     * @param field name of a field with term vectors {@link IndexableFieldType#storeTermVectors()}.
     * @return term vector.
     * @throws NoSuchFieldException  not a text field.
     * @throws IOException          Lucene errors.
     */
    public Terms getTermVector(String field) throws IOException, NoSuchFieldException
    {
        // new lucene API, not tested
        Terms tvek = alix.reader().termVectors().get(docId, field);
        if (tvek == null)
            throw new NoSuchFieldException("Missig search Vector for field=" + field + " docId=" + docId);
        return tvek;
    }

    /**
     * Returns the persistent String id of the document.
     * 
     * @return AlixReader id.
     */
    public String id()
    {
        return id;
    }

    /**
     * Get the search shared between 2 documents.
     * 
     * @param field name of a stored field.
     * @param docId2 other doc.
     * @return most frequent words in common.
     * @throws NoSuchFieldException  not a text field.
     * @throws IOException          Lucene errors.
     * @throws SecurityException 
     * @throws NoSuchMethodException 
     * @throws InvocationTargetException 
     * @throws IllegalArgumentException 
     * @throws IllegalAccessException 
     * @throws InstantiationException 
     */
    public Top<String> intersect(final String field, final int docId2, final BytesDic stopwords) 
        throws IOException, NoSuchFieldException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException
    {
        // new lucene API, not tested
        Terms vek2 = alix.reader().termVectors().get(docId2, field);
        Top<String> top = new Top<String>(String.class, 100);
        FieldText ftext = alix.fieldText(field);
        int len1 = ftext.occsByDoc(docId);
        int len2 = ftext.occsByDoc(docId2);
        Terms vek1 = getTermVector(field);
        // double max1 = Double.MIN_VALUE;
        // double max2 = Double.MIN_VALUE;
        TermsEnum termit1 = vek1.iterator();
        TermsEnum termit2 = vek2.iterator();
        BytesRef term1;
        BytesRef term2 = termit2.next();
        // loop on source search
        while ((term1 = termit1.next()) != null) {
            // filter stop word
            if (stopwords != null && stopwords.contains(term1)) continue;
            double count1 = termit1.totalTermFreq();
            double count2 = 0;
            // loop on other doc to find
            while (true) {
                if (term2 == null)
                    break;
                int comp = term1.compareTo(term2);
                if (comp < 0)
                    break; // term2 is bigger, get it after
                if (comp == 0) { // match
                    count2 = termit2.totalTermFreq();
                    break;
                }
                term2 = termit2.next();
            }
            if (count2 == 0)
                continue;
            count1 = count1 / len1;
            count2 = count2 / len2;
            // final double ratio = Math.max(count1, count2) / Math.min(count1, count2);
            final double score = count1 + count2;
            if (top.isInsertable(score)) {
                top.insert(score, term1.utf8ToString());
            }
        }
        return top;
    }

    /*
    public List<String[]> kwic(final String field, ByteRunAutomaton include, int limit, int left, int right,
            final int gap, final boolean expressions, final boolean repetitions)
            throws NoSuchFieldException, IOException
    {
        if (left < 0 || left > 500)
            left = 50;
        if (right < 0 || right > 500)
            right = 50;
        Terms tvek = getTermVector(field);
        String fstore = field;
        if (fstore.endsWith("_orth"))
            fstore = fstore.substring(0, fstore.lastIndexOf('_'));
        String xml = get(fstore);
        DocHiliter rail = new DocHiliter(tvek, include, null);
        Token[] toks = rail.toks;
        // group tokens for expression ?
        // do better testing here
        if (expressions)
            toks = rail.group(gap, repetitions);
        // no token or expression found
        if (toks == null || toks.length < 1)
            return null;
        int length = toks.length;
        if (limit < 0)
            limit = length;
        else
            limit = Math.min(limit, length);
        // store lines to get the ones with more occurrences
        List<String[]> lines = new ArrayList<>();
        Chain chain = new Chain();
        // loop on all freqs to get the best
        for (int i = 0; i < length; i++) {
            Token tok = toks[i];
            String[] chunks = new String[4];
            chunks[0] = "pos" + tok.pos;
            // go left from token position
            chain.reset();
            ML.prependChars(xml, tok.start - 1, chain, left);
            chunks[1] = chain.toString();
            chain.reset();
            ML.detag(xml, tok.start, tok.end, chain, null); // multi word can contain tags
            chunks[2] = chain.toString();
            chain.reset();
            ML.appendChars(xml, tok.end, chain, right);
            chunks[3] = chain.toString();
            lines.add(chunks);
        }
        return lines;
    }
    */
    
    /**
     * Return the count of tokens of this doc for field.
     * 
     * @param field name of a stored field.
     * @return {@link FieldText#occsByDoc(int)}.
     * @throws IOException Lucene errors.
     */
    public int length(String field) throws IOException
    {
        return alix.fieldText(field).occsByDoc(docId);
    }

    /**
     * A record to sort term vectors occurrences with offsets for hilite
     */
    static class Token implements Comparable<Token>
    {
        /** Token first position */
        final int pos;
        /** Start offset in chars */
        final int start;
        /** End offset in chars */
        int end;
        /** Indexed form (ex: lemma) */
        String form;
        /** A local id (scope: a document) for the term, used for fast equals */
        final int termId;
        /** Freq of term in document (repeated for each occurrences), used for info */
        final int count;
        /** Width in positions, used for concatenantion of search */
        int span;
        /** A second possible freq, for info */
        final int count2;
        /** A flag if different words */
        boolean phrase;
        /** for more than on positions */
        int posLast;

        public Token(final int pos, final int start, final int end) {
            this(pos, start, end, null, -1, 1, 0, 0);
        }

        public Token(final int pos, final int start, final int end, final String form, final int termId) {
            this(pos, start, end, form, termId, 0, 1, 0);
        }

        public Token(final int pos, final int start, final int end, final String form, final int termId,
                final int count) {
            this(pos, start, end, form, termId, count, 1, 0);
        }

        public Token(final int pos, final int start, final int end, final String form, final int termId,
                final int count, final int span, final int count2) {
            this.pos = this.posLast = pos;
            this.start = start;
            this.end = end;
            this.form = form;
            this.termId = termId;
            this.span = span;
            this.count = count;
            this.count2 = count2;
        }

        @Override
        public int compareTo(Token tok2)
        {
            // sort by position, and biggest token first for same position
            final int cp = Integer.compare(this.pos, tok2.pos);
            if (cp != 0)
                return cp;
            return Integer.compare(this.end, tok2.end);
        }
    }

}
