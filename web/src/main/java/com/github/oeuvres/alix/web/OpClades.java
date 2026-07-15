package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exports selected terms as taxa and live documents as binary characters.
 */
public class OpClades extends Op
{
    /**
     * Collects the live global document ids of the reader snapshot.
     */
    private static FixedBitSet liveDocs(final IndexReader reader)
    {
        final FixedBitSet liveDocs = new FixedBitSet(reader.maxDoc());
        for (final var context : reader.leaves()) {
            final Bits leafLiveDocs = context.reader().getLiveDocs();
            if (leafLiveDocs == null) {
                liveDocs.set(context.docBase, context.docBase + context.reader().maxDoc());
                continue;
            }
            for (int localDocId = 0; localDocId < context.reader().maxDoc(); localDocId++) {
                if (leafLiveDocs.get(localDocId)) {
                    liveDocs.set(context.docBase + localDocId);
                }
            }
        }
        return liveDocs;
    }
    
    /**
     * Prune non significative docs
     */
    private static FixedBitSet filterDocs(final FixedBitSet liveDocs, final FixedBitSet[] docPresence)
    {
        final FixedBitSet featDocs = new FixedBitSet(liveDocs.length());

        for (int docId = 0; docId < liveDocs.length(); docId++) {
            if (!liveDocs.get(docId)) {
                continue;
            }

            int present = 0;
            for (final FixedBitSet row : docPresence) {
                if (row.get(docId)) {
                    present++;
                }
            }

            if (present > 0 && present < docPresence.length) {
                featDocs.set(docId);
            }
        }
        return featDocs;
    }


    /**
     * Collects one document-presence bitset per selected term by walking its
     * postings directly.
     */
    private static FixedBitSet[] docPresence(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet liveDocs
    )
        throws IOException {
        final FixedBitSet[] rows = new FixedBitSet[rowIds.length];
        final Terms terms = MultiTerms.getTerms(reader, lexicon.field());
        final TermsEnum termsEnum = terms == null ? null : terms.iterator();
        final BytesRefBuilder termBytes = new BytesRefBuilder();
        PostingsEnum postings = null;

        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            final FixedBitSet docs = new FixedBitSet(reader.maxDoc());
            rows[rowRank] = docs;
            if (termsEnum == null
                || !termsEnum.seekExact(lexicon.formBytes(rowIds[rowRank], termBytes))) {
                continue;
            }
            postings = termsEnum.postings(postings, PostingsEnum.NONE);
            for (
                int docId = postings.nextDoc();
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = postings.nextDoc()
            ) {
                if (liveDocs.get(docId)) {
                    docs.set(docId);
                }
            }
        }
        return rows;
    }

    /**
     * Appends one safely quoted NEXUS label.
     */
    private static void appendNexusLabel(final Writer writer, final String label)
        throws IOException {
        writer.append('\'');
        for (int i = 0; i < label.length(); i++) {
            final char c = label.charAt(i);
            if (c == '\'') {
                writer.append('\'');
            }
            writer.append(c);
        }
        writer.append('\'');
    }

    /**
     * Writes a STANDARD binary CHARACTERS block. Columns follow live Lucene
     * document-id order; constant zero columns are retained for this first
     * experiment.
     */
    private static void writeNexus(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet[] docPresence,
        final FixedBitSet docFilter
    )
        throws IOException {
        writer.append("#NEXUS\n\n");
        writer.append("BEGIN CHARACTERS;\n");
        writer.append("    DIMENSIONS NTAX=")
            .append(Integer.toString(rowIds.length))
            .append(" NCHAR=")
            .append(Integer.toString(docFilter.cardinality()))
            .append(";\n");
        writer.append("    FORMAT DATATYPE=STANDARD SYMBOLS=\"01\" LABELS=LEFT;\n");
        writer.append("    MATRIX\n");

        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            writer.append("        ");
            appendNexusLabel(writer, lexicon.form(rowIds[rowRank]));
            writer.append(' ');
            for (int docId = 0; docId < docFilter.length(); docId++) {
                if (docFilter.get(docId)) {
                    writer.append(docPresence[rowRank].get(docId) ? '1' : '0');
                }
            }
            writer.append('\n');
        }

        writer.append("    ;\n");
        writer.append("END;\n");
    }
    
    

    @Override
    protected void txt(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final Writer writer = response.getWriter();

        // Keep the same taxon selection and order source as the terms endpoint.
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);
        if (topTerms == null) {
            response.setStatus(400);
            writer.append("Houston, on a un problème.");
            return;
        }

        final IntList rowList = new IntList(topTerms.size());
        for (final TermEntry term : topTerms) {
            rowList.push(term.termId());
        }
        final int[] rowIds = rowList.toUniq();
        final TermLexicon lexicon = topTerms.lexicon();
        final FixedBitSet liveDocs = liveDocs(index.reader());
        final FixedBitSet[] docPresence = docPresence(
            index.reader(),
            lexicon,
            rowIds,
            liveDocs
        );
        final FixedBitSet featDocs = filterDocs(liveDocs, docPresence);
        writeNexus(writer, lexicon, rowIds, docPresence, featDocs);
    }
}
