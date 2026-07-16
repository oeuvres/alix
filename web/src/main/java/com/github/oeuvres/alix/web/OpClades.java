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
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exports selected terms as taxa with live-document binary characters and a
 * selected distance matrix.
 */
public class OpClades extends Op
{
    /** Distance matrices that Alix computes in addition to raw characters. */
    private enum Distance
    {
        OCHIAI;
    }

    /** HTTP serialization selected by the response extension. */
    private enum Format
    {
        NEXUS,
        CSV;
    }

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
     * Retains document columns that are not constant across selected terms.
     */
    private static FixedBitSet filterDocs(
        final FixedBitSet liveDocs,
        final FixedBitSet[] docPresence
    )
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
     * Writes a STANDARD binary CHARACTERS block. Columns follow Lucene
     * document-id order after constant-column filtering.
     */
    private static void writeCharacters(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet[] docPresence,
        final FixedBitSet docFilter
    )
        throws IOException {
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

    /**
     * Returns the chord distance associated with binary Ochiai similarity.
     *
     * <p>
     * For binary document vectors, Ochiai similarity is cosine similarity:
     * {@code |A intersection B| / sqrt(|A| |B|)}. Chord distance is the
     * Euclidean distance between the corresponding unit vectors:
     * {@code sqrt(2 - 2 * similarity)}.
     * </p>
     */
    private static double ochiaiDistance(
        final FixedBitSet rowA,
        final FixedBitSet rowB,
        final int cardA,
        final int cardB
    ) {
        if (cardA == 0 || cardB == 0) {
            return cardA == cardB ? 0d : Math.sqrt(2d);
        }

        final long intersection = FixedBitSet.intersectionCount(rowA, rowB);
        final double similarity = intersection / Math.sqrt((double) cardA * cardB);
        return Math.sqrt(Math.max(0d, 2d - 2d * similarity));
    }

    /** Computes the full symmetric Ochiai chord-distance matrix. */
    private static double[][] ochiaiDistances(
        final FixedBitSet[] docPresence
    ) {
        final int size = docPresence.length;
        final int[] cardinalities = new int[size];
        for (int rowRank = 0; rowRank < size; rowRank++) {
            cardinalities[rowRank] = docPresence[rowRank].cardinality();
        }

        // Compute each symmetric pair only once.
        final double[][] distances = new double[size][size];
        for (int rowRank = 0; rowRank < size; rowRank++) {
            for (int colRank = 0; colRank < rowRank; colRank++) {
                final double distance = ochiaiDistance(
                    docPresence[rowRank],
                    docPresence[colRank],
                    cardinalities[rowRank],
                    cardinalities[colRank]
                );
                distances[rowRank][colRank] = distance;
                distances[colRank][rowRank] = distance;
            }
        }
        return distances;
    }

    /** Writes a full square distance matrix as a NEXUS block. */
    private static void writeNexusDistances(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final double[][] distances,
        final Distance distance
    )
        throws IOException {
        final int size = rowIds.length;
        writer.append("BEGIN DISTANCES;\n");
        writer.append("    DIMENSIONS NTAX=")
            .append(Integer.toString(size))
            .append(";\n");
        writer.append("    FORMAT TRIANGLE=BOTH DIAGONAL LABELS=LEFT;\n");
        writer.append("    [DISTANCE=").append(distance.name()).append("]\n");
        writer.append("    MATRIX\n");

        for (int rowRank = 0; rowRank < size; rowRank++) {
            writer.append("        ");
            appendNexusLabel(writer, lexicon.form(rowIds[rowRank]));
            for (int colRank = 0; colRank < size; colRank++) {
                writer.append(' ')
                    .append(Double.toString(distances[rowRank][colRank]));
            }
            writer.append('\n');
        }

        writer.append("    ;\n");
        writer.append("END;\n");
    }

    /** Writes a full square distance matrix for SplitsTree's CSV importer. */
    private static void writeCsvDistances(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final double[][] distances
    )
        throws IOException {
        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            writer.append(csvEscape(lexicon.form(rowIds[rowRank])));
            for (int colRank = 0; colRank < rowIds.length; colRank++) {
                writer.append(',')
                    .append(Double.toString(distances[rowRank][colRank]));
            }
            writer.append('\n');
        }
    }

    /**
     * Builds the selected term-by-document data once. NEXUS contains both raw
     * characters and distances; CSV contains the selected distance matrix.
     */
    private static void write(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Format format
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
        // here get different stats used by distance algos
        TermStats termStats = topTerms.termStats();

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

        // Distances and exported characters must use exactly the same columns.
        for (final FixedBitSet row : docPresence) {
            row.and(featDocs);
        }

        final Distance distance = pars.getEnum("distance", Distance.OCHIAI);
        final double[][] distances = switch (distance) {
            case OCHIAI -> ochiaiDistances(docPresence);
        };

        switch (format) {
            case NEXUS -> {
                writer.append("#NEXUS\n\n");
                writeCharacters(writer, lexicon, rowIds, docPresence, featDocs);
                writer.append('\n');
                writeNexusDistances(
                    writer,
                    lexicon,
                    rowIds,
                    distances,
                    distance
                );
            }
            case CSV -> writeCsvDistances(
                writer,
                lexicon,
                rowIds,
                distances
            );
        }
    }

    @Override
    protected void txt(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        write(index, request, response, Format.NEXUS);
    }

    @Override
    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        write(index, request, response, Format.CSV);
    }
}
