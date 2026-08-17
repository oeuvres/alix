package com.github.oeuvres.alix.lucene;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/**
 * Exports a Lucene field as a dense term × document frequency matrix.
 *
 * <p>The first row contains:
 * <pre>
 * field, fieldTokenCount, fieldDocCount, 0, 1, 2, ...
 * </pre>
 *
 * <p>Each following row contains:
 * <pre>
 * term, termFieldFreq, termDocFreq, freq(doc=0), freq(doc=1), ...
 * </pre>
 *
 * <p>Usage:
 * <pre>
 * java TermDocMatrix indexPath field [output.csv]
 * </pre>
 *
 * <p>If no output file is supplied, CSV is written to standard output.
 */
public final class TermDocMatrix {

    /**
     * Exports one indexed field as a dense term × document matrix.
     *
     * @param indexPath path to the Lucene index
     * @param field field to export
     * @param writer destination writer
     * @throws IOException if the index cannot be read or the output cannot be written
     */
    public static void export(final Path indexPath, final String field, final Writer writer)
            throws IOException {
        try (
            Directory directory = FSDirectory.open(indexPath);
            DirectoryReader reader = DirectoryReader.open(directory)
        ) {
            final Terms terms = MultiTerms.getTerms(reader, field);

            if (terms == null) {
                throw new IllegalArgumentException(
                    "Field does not exist or is not indexed: " + field
                );
            }

            if (!terms.hasFreqs()) {
                throw new IllegalArgumentException(
                    "Field does not store term frequencies: " + field
                );
            }

            final int maxDoc = reader.maxDoc();
            final long fieldTokenCount = terms.getSumTotalTermFreq();
            final int fieldDocCount = terms.getDocCount();

            writeCsvCell(writer, field);
            writer.write(',');
            writer.write(Long.toString(fieldTokenCount));
            writer.write(',');
            writer.write(Integer.toString(fieldDocCount));

            for (int docId = 0; docId < maxDoc; docId++) {
                writer.write(',');
                writer.write(Integer.toString(docId));
            }
            writer.write('\n');

            final TermsEnum termsEnum = terms.iterator();
            BytesRef term;

            while ((term = termsEnum.next()) != null) {
                writeCsvCell(writer, term.utf8ToString());
                writer.write(',');
                writer.write(Long.toString(termsEnum.totalTermFreq()));
                writer.write(',');
                writer.write(Integer.toString(termsEnum.docFreq()));

                final PostingsEnum postings =
                    termsEnum.postings(null, PostingsEnum.FREQS);

                int postingDoc = postings.nextDoc();

                for (int docId = 0; docId < maxDoc; docId++) {
                    writer.write(',');

                    if (postingDoc == docId) {
                        writer.write(Integer.toString(postings.freq()));
                        postingDoc = postings.nextDoc();
                    }
                    else {
                        writer.write('0');
                    }
                }

                writer.write('\n');
            }

            writer.flush();
        }
    }

    /**
     * Runs the command-line exporter.
     *
     * @param args index path, field name, and optional output CSV path
     * @throws IOException if the index cannot be read or the output cannot be written
     */
    public static void main(final String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println(
                "Usage: java TermDocMatrix <indexPath> <field> [output.csv]"
            );
            System.exit(2);
        }

        final Path indexPath = Path.of(args[0]);
        final String field = args[1];

        if (args.length == 3) {
            try (
                Writer writer = Files.newBufferedWriter(
                    Path.of(args[2]),
                    StandardCharsets.UTF_8
                )
            ) {
                export(indexPath, field, writer);
            }
        }
        else {
            final Writer writer = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            export(indexPath, field, writer);
        }
    }

    /**
     * Writes one RFC-style escaped CSV cell.
     *
     * @param writer destination writer
     * @param value cell value
     * @throws IOException if the value cannot be written
     */
    private static void writeCsvCell(final Writer writer, final String value)
            throws IOException {
        final boolean quote =
            value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;

        if (!quote) {
            writer.write(value);
            return;
        }

        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"') {
                writer.write("\"\"");
            }
            else {
                writer.write(c);
            }
        }
        writer.write('"');
    }

    private TermDocMatrix() {
    }
}