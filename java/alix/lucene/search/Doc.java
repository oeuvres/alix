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
package alix.lucene.search;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;

import static alix.Names.*;
import alix.fr.Tag.TagFilter;
import alix.lucene.Alix;
import alix.lucene.analysis.FrDics;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.search.DocHiliter.Token;
import alix.lucene.util.WordsAutomatonBuilder;
import alix.util.Chain;
import alix.util.ML;
import alix.util.Top;
import alix.web.OptionDistrib;

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
    final private Alix alix;
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
     * @param alix
     * @param id
     * @throws IOException
     */
    public Doc(final Alix alix, final String id) throws IOException
    {
        this(alix, id, null);
    }

    /**
     * Get a document by String id (persists as long as the source XML doc is not
     * modified), with the set of fields provided (or all fields fieldsToLoad is
     * null).
     * 
     * @param alix
     * @param id
     * @param fieldsToLoad
     * @throws IOException
     */
    public Doc(final Alix alix, final String id, final HashSet<String> fieldsToLoad) throws IOException
    {
        int docId = alix.getDocId(id);
        if (docId < 0) {
            throw new IllegalArgumentException("No document found with id: " + id);
        }
        if (fieldsToLoad == null) {
            document = alix.reader().document(docId);
        } else {
            fieldsToLoad.addAll(FIELDS_REQUIRED);
            document = alix.reader().document(docId, fieldsToLoad);
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
     * @param alix
     * @param docId
     * @throws IOException
     */
    public Doc(final Alix alix, final int docId) throws IOException
    {
        this(alix, docId, null);
    }

    /**
     * Get a document by lucene docId (persists as long as the Lucene index is not
     * modified) with the set of fields provided (or all fields fieldsToLoad is
     * null).
     * 
     * @param alix
     * @param docId
     * @param fieldsToLoad
     * @throws IOException
     */
    public Doc(final Alix alix, final int docId, final HashSet<String> fieldsToLoad) throws IOException
    {
        if (fieldsToLoad == null) {
            document = alix.reader().document(docId);
        } else {
            fieldsToLoad.addAll(FIELDS_REQUIRED);
            document = alix.reader().document(docId, fieldsToLoad);
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
     * Get count
     */

    public String contrast(final String field, final int docId2) throws IOException, NoSuchFieldException
    {
        return contrast(field, docId2, false);
    }

    /**
     * 
     * @param field
     * @param docId2
     * @param right
     * @return
     * @throws IOException
     * @throws NoSuchFieldException
     */
    public String contrast(final String field, final int docId2, final boolean right)
            throws IOException, NoSuchFieldException
    {
        String text = get(field);
        StringBuilder sb = new StringBuilder();

        FieldText ftext = alix.fieldText(field);
        int len1 = ftext.occs(docId);
        int len2 = ftext.occs(docId2);

        Terms vek1 = getTermVector(field);
        Terms vek2 = alix.reader().getTermVector(docId2, field);
        TermsEnum termit1 = vek1.iterator();
        BytesRef term1;
        TermsEnum termit2 = vek2.iterator();
        BytesRef term2 = termit2.next();
        ArrayList<Token> offsets = new ArrayList<Token>();
        PostingsEnum postings = null;
        // loop on search source, compare with dest
        double max1 = Double.MIN_VALUE;
        double max2 = Double.MIN_VALUE;
        CharsAtt att = new CharsAtt();
        while (termit1.next() != null) {
            // termit1.ord(); UnsupportedOperationException
            final int count1 = (int) termit1.totalTermFreq();
            term1 = termit1.term();
            String form = term1.utf8ToString();
            att.setEmpty().append(form);
            if (FrDics.isStop(att))
                continue;

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
            title += " occurremces";
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
     * Get count
     */

    static public String csstok(String form)
    {
        return form.replaceAll("[ \\.<>&\"']", "_");
    }

    /**
     * Returns the loaded document.
     */
    public Document doc()
    {
        return document;
    }

    /**
     * Returns the local Lucene int docId of the document.
     * 
     * @return
     */
    public int docId()
    {
        return docId;
    }

    /**
     * Count occurences of terms in the doc
     */
    public int freq(final String field, final String[] forms) throws NoSuchFieldException, IOException
    {
        if (forms == null || forms.length < 1)
            return 0;
        Arrays.sort(forms); // may optimize term seekink, useful for uniq
        Terms tvek = getTermVector(field);
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
     * Get contents of a field as String.
     * 
     * @param field
     * @return
     * @throws NoSuchFieldException
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
     * Get and cache a term vector for a field of this document.
     * 
     * @throws IOException
     * @throws NoSuchFieldException
     */
    public Terms getTermVector(String field) throws IOException, NoSuchFieldException
    {
        /*
         * Shall we cache ? Terms tvek = vectors.get(field); if (tvek != null) return
         * tvek; // cache OK
         */
        Terms tvek = alix.reader().getTermVector(docId, field);
        if (tvek == null)
            throw new NoSuchFieldException("Missig search Vector for field=" + field + " docId=" + docId);
        return tvek;
    }

    public String hilite(final String field, final String[] terms) throws NoSuchFieldException, IOException
    {
        if (terms == null || terms.length < 1) {
            return get(field);
        }
        Automaton automaton = WordsAutomatonBuilder.buildFronStrings(terms);
        ByteRunAutomaton include = new ByteRunAutomaton(automaton);
        return hilite(field, include);
    }

    /**
     * Hilite search in a stored document as html.
     * 
     * @param field
     * @throws IOException
     * @throws NoSuchFieldException
     */
    public String hilite(final String field, final ByteRunAutomaton include) throws NoSuchFieldException, IOException
    {
        Terms tvek = getTermVector(field);
        String text = get(field);
        DocHiliter rail = new DocHiliter(tvek, include, null);
        final Token[] toks = rail.toks;
        StringBuilder sb = new StringBuilder();

        int offset = 0;
        final int lim = toks.length;
        for (int i = 0; i < lim; i++) {
            Token tok = toks[i];
            sb.append(text.substring(offset, tok.start));
            sb.append("<mark class=\"mark\" id=\"pos" + (tok.pos) + "\">");
            if (i > 0)
                sb.append("<a href=\"#pos" + (toks[i - 1].pos)
                        + "\" onclick=\"location.replace(this.href); return false;\" class=\"prev\">◀</a>");
            sb.append("<b>");
            sb.append(text.substring(tok.start, tok.end));
            sb.append("</b>");
            if (i < lim - 1)
                sb.append("<a href=\"#pos" + (toks[i + 1].pos)
                        + "\" onclick=\"location.replace(this.href); return false;\" class=\"next\">▶</a>");
            sb.append("</mark>");
            offset = tok.end;
        }
        sb.append(text.substring(offset));

        int length = text.length();
        sb.append("<nav id=\"ruloccs\"><div>\n");
        final DecimalFormat dfdec1 = new DecimalFormat("0.#", ensyms);
        for (int i = 0; i < lim; i++) {
            Token tok = toks[i];
            offset = tok.start;
            sb.append("<a href=\"#pos" + (tok.pos) + "\" style=\"top: " + dfdec1.format(100.0 * offset / length)
                    + "%\"> </a>\n");
        }
        sb.append("</div></nav>\n");
        return sb.toString();
    }

    /**
     * Returns the persistent String id of the document.
     * 
     * @return
     */
    public String id()
    {
        return id;
    }

    /**
     * Get the search shared between 2 documents
     * 
     * @param field
     * @param docId2
     * @return
     * @throws IOException
     * @throws NoSuchFieldException
     */
    public Top<String> intersect(final String field, final int docId2) throws IOException, NoSuchFieldException
    {
        Terms vek2 = alix.reader().getTermVector(docId2, field);
        Top<String> top = new Top<String>(100);
        FieldText ftext = alix.fieldText(field);
        int len1 = ftext.occs(docId);
        int len2 = ftext.occs(docId2);
        Terms vek1 = getTermVector(field);
        // double max1 = Double.MIN_VALUE;
        // double max2 = Double.MIN_VALUE;
        TermsEnum termit1 = vek1.iterator();
        TermsEnum termit2 = vek2.iterator();
        BytesRef term1;
        BytesRef term2 = termit2.next();
        ByteRunAutomaton tomat = FrDics.STOP_BYTES;
        // loop on source search
        while ((term1 = termit1.next()) != null) {
            // filter stop word
            if (tomat.run(term1.bytes, term1.offset, term1.length))
                continue;
            double count1 = termit1.totalTermFreq();
            String form = term1.utf8ToString();
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
            top.push(count1 + count2, form);
        }
        return top;
    }

    /**
     * Extract a kwic (Key Word In Context) for a search.
     * 
     * @param field
     * @param include
     * @param left
     * @param right
     * @param limit
     * @return
     * @throws NoSuchFieldException
     * @throws IOException
     */
    public String[] kwic(final String field, ByteRunAutomaton include, final String href, int limit, int left,
            int right, final int gap, final boolean expressions, final boolean repetitions)
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
        Chain line = new Chain();
        int length = toks.length;
        if (limit < 0)
            limit = length;
        else
            limit = Math.min(limit, length);
        // store lines to get the ones with more occurrences
        Top<String> lines = new Top<String>(limit);
        // loop on all freqs to get the best
        for (int i = 0; i < length; i++) {
            Token tok = toks[i];
            // prepend left context, because search of full text is progressing from right
            // to left
            ML.prependChars(xml, tok.start - 1, line, left);
            line.prepend("<span class=\"left\">");
            line.append("</span><span class=\"right\"><a href=\"");
            line.append(href); // caller kows where to send
            line.append("#pos" + tok.pos); // here knows the ids in the hilited doc
            line.append("\">");
            ML.detag(xml, tok.start, tok.end, line); // multi word can contain tags
            line.append("</a>");
            ML.appendChars(xml, tok.end, line, right);
            line.append("</span>");
            lines.push(tok.span, line.toString());
            line.reset();
        }
        return lines.toArray();
    }

    /*
     * String text = document.get(TEXT); BinaryUbytes tags = new BinaryUbytes();
     * tags.open(document.getBinaryValue(TEXT+Alix._TAGS)); Offsets offsets = new
     * Offsets(); offsets.open(document.getBinaryValue(TEXT+Alix._OFFSETS));
     * 
     * 
     * TagFilter tagFilter = new TagFilter(); tagFilter.setName();
     * tagFilter.setAdj(); tagFilter.set(Tag.ADV);
     * 
     * // hilite int off = 0; for (int pos = 0, size = offsets.size(); pos < size;
     * pos++) { int tag = tags.get(pos); if (!tagFilter.accept(tag)) continue; int
     * offStart = offsets.getStart(pos); int offEnd = offsets.getEnd(pos);
     * out.print(text.substring(off, offStart));
     * out.print("<mark class=\""+Tag.label(Tag.group(tag))+"\">");
     * out.print(text.substring(offStart, offEnd)); out.print("</mark>"); off =
     * offEnd; } out.print(text.substring(off));
     */
    //

    /**
     * Return the count of tokens of this doc for field.
     * 
     * @param field
     * @return
     * @throws IOException
     */
    public int length(String field) throws IOException
    {
        return alix.fieldText(field).occs(docId);
    }

    /**
     * 
     * @param field
     * @return
     * @throws IOException
     * @throws NoSuchFieldException
     */
    public String paint(final String field) throws NoSuchFieldException, IOException
    {
        Terms tvek = getTermVector(field);
        String text = get(field);
        final DocHiliter rail = new DocHiliter(tvek, null, FrDics.STOP_BYTES);
        final int countMax = rail.countMax;
        final Token[] toks = rail.toks;
        final StringBuilder sb = new StringBuilder();
        // loop on all token of text
        int off = 0;
        for (int i = 0, len = toks.length; i < len; i++) {
            final Token tok = toks[i];
            final int count = tok.count;

            final String form = tok.form;
            sb.append(text.substring(off, tok.start)); // append text before token
            // change boldness
            String level;
            if (count == 1)
                level = "em1";
            else if (count < 4)
                level = "em2";
            else if (count >= 0.6 * countMax)
                level = "em9";
            else if (count >= 0.3 * countMax)
                level = "em5";
            else
                level = "em3";

            String title = "";
            title += count + " occurremces";
            sb.append("<a id=\"tok" + tok.pos + "\" class=\"" + csstok(form) + " " + level + "\" title=\"" + title
                    + "\">");
            sb.append(text.substring(tok.start, tok.end));
            sb.append("</a>");
            off = tok.end;
        }
        sb.append(text.substring(off)); // do not forget end
        return sb.toString();
    }

    /**
     * Count of occurrences by term for the document. Returns an iterator sorted
     * according to a scorer. If scorer is null, default is count of occurences.
     * {@link FieldText#forms(int, org.apache.lucene.util.BitSet, Specif, TagFilter)}
     * 
     * @param field
     * @param limit
     * @param specif
     * @param tags
     * @param reverse
     * @return
     * @throws IOException
     * @throws NoSuchFieldException
     */
    public FormEnum forms(String field, OptionDistrib distrib, TagFilter tags) throws IOException, NoSuchFieldException
    {
        boolean hasTags = (tags != null);
        boolean noStop = (tags != null && tags.nostop());
        boolean hasScorer = (distrib != null);

        // get index term stats
        FieldText fieldText = alix.fieldText(field);
        FormEnum forms = fieldText.forms();
        if (hasScorer) {
            forms.formScore = new double[fieldText.maxForm];
        }
        forms.formFreq = new long[fieldText.maxForm]; // freqs by form
        int docLen = fieldText.docOccs[docId];

        // loop on all forms of the document, get score, keep the top
        // final long restLen = fieldText.occsAll - occsDoc;
        Terms vector = getTermVector(field); // get the term vector to loop on
        TermsEnum termit = vector.iterator();
        while (termit.next() != null) {
            BytesRef bytes = termit.term();
            if (bytes.length < 1) {
                continue;
            }
            final int formId = fieldText.formId(bytes);
            if (hasTags && !tags.accept(fieldText.formTag[formId])) {
                continue;
            }
            if (noStop && fieldText.isStop(formId)) {
                continue;
            }
            if (formId < 0) {
                continue; // should not arrive, let cry
            }
            if (hasScorer) {
                distrib.expectation(fieldText.formOccs[formId], fieldText.occs);
                distrib.idf(fieldText.formDocs[formId], fieldText.docs, fieldText.occs);
            }
            // scorer.weight(termOccs, termDocs); // collection level stats
            long freq = termit.totalTermFreq();
            forms.formFreq[formId] = freq;
            forms.freq += freq;
            if (hasScorer) {
                forms.formScore[formId] += distrib.score(freq, docLen);
                // scores[formId] -= scorer.last(formOccsAll[formId] - freq, restLen); // sub
                // complement ?
            }
        }
        // add some more stats on this iterator
        return forms;
    }

}
