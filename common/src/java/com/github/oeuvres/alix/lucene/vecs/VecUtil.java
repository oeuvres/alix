package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

/**
 * Shared utilities for experimental Lucene-to-vector exporters.
 */
public final class VecUtil
{
    /**
     * One selected indexed term.
     *
     * @param bytes immutable copy of the indexed UTF-8 term bytes
     * @param word decoded term form
     * @param totalFreq total term frequency in the field
     */
    public record SelectedTerm(
        BytesRef bytes,
        String word,
        long totalFreq
    ) {}

    /** Utility class. */
    private VecUtil()
    {
    }

    /**
     * Selects the most frequent terms of a field passing a minimum document
     * frequency.
     *
     * @param reader index reader
     * @param field indexed field name
     * @param minDocFreq minimum document frequency
     * @param maxTerms maximum number of terms to keep
     * @return selected terms sorted by decreasing total term frequency
     * @throws IOException if the term dictionary cannot be read
     * @throws IllegalArgumentException if the field has no indexed terms
     */
    public static SelectedTerm[] selectTerms(
        final IndexReader reader,
        final String field,
        final int minDocFreq,
        final int maxTerms
    ) throws IOException {
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("no indexed terms for field: " + field);
        }

        final List<SelectedTerm> kept = new ArrayList<>();
        final TermsEnum scan = terms.iterator();
        BytesRef term;
        while ((term = scan.next()) != null) {
            if (scan.docFreq() < minDocFreq) {
                continue;
            }
            kept.add(new SelectedTerm(
                BytesRef.deepCopyOf(term),
                term.utf8ToString(),
                scan.totalTermFreq()));
        }
        kept.sort((a, b) -> Long.compare(b.totalFreq(), a.totalFreq()));
        if (kept.size() > maxTerms) {
            return kept.subList(0, maxTerms).toArray(SelectedTerm[]::new);
        }
        return kept.toArray(SelectedTerm[]::new);
    }

    /**
     * Writes term vectors in the word2vec binary format: an ASCII header line
     * {@code "count dim\n"}, then per term its UTF-8 form, a space, {@code dim}
     * little-endian float32 values, and a newline. Whitespace inside a term is
     * replaced by an underscore so the space-delimited format stays parseable.
     *
     * @param out output path
     * @param words term forms
     * @param coords dense coordinates, one row per term
     * @param dim number of coordinates to write from each row
     * @throws IOException if the output cannot be written
     */
    public static void writeWord2vec(
        final Path out,
        final String[] words,
        final double[][] coords,
        final int dim
    ) throws IOException {
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            os.write((words.length + " " + dim + "\n").getBytes(StandardCharsets.US_ASCII));
            final ByteBuffer buffer = ByteBuffer
                .allocate(Math.max(1, dim) * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < words.length; row++) {
                os.write(words[row].replaceAll("\\s", "_").getBytes(StandardCharsets.UTF_8));
                os.write(' ');
                buffer.clear();
                for (int axis = 0; axis < dim; axis++) {
                    buffer.putFloat((float) coords[row][axis]);
                }
                os.write(buffer.array(), 0, dim * Float.BYTES);
                os.write('\n');
            }
        }
    }
}
