package com.github.oeuvres.alix.lucene.cli;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.maths.ContingencySvd;
import com.github.oeuvres.alix.maths.ContingencySvd.Assoc;

/**
 * Reduces a Lucene term-by-document table to dense term vectors by signed G²
 * residual SVD, and writes them in the word2vec binary format.
 *
 * <p>
 * This is a single-run experiment, not a library. It opens an on-disk index,
 * selects the most frequent terms of one field passing a minimum document
 * frequency, fills a raw term-by-document count table, hands it to
 * {@link ContingencySvd} for a G² residual decomposition, weights the axes by
 * {@code sigma^power}, and exports the leading coordinates. A word2vec reader
 * (the historical C tool, gensim, or a Java port) normalises each row on load
 * and ranks by dot product, so the two knobs that shape the result are the
 * retained dimension count and the singular-value power: {@code 0} keeps plain
 * {@code U}, {@code 0.5} keeps {@code U sqrt(Sigma)}, {@code 1} keeps
 * {@code U Sigma}. The retained width cannot exceed the number of documents.
 * </p>
 *
 * <p>
 * Progress is written to standard error with an elapsed-milliseconds stamp; the
 * dense decomposition is the dominant cost and is bracketed by explicit start
 * and end lines. The final one-line summary is written to standard output.
 * </p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.cli.Lucene2vec <indexDir> <field> \
 *     [--dims 100] [--power 0.5] [--minDocFreq 3] [--maxTerms 10000] \
 *     [--out vectors.bin]
 * }</pre>
 */
public final class Lucene2vec
{
    /** Selected vocabulary and its raw term-by-document count table. */
    private record Table(
        String[] words,
        double[][] cells,
        long nonZero
    ) {}

    private static final String USAGE =
        "usage: Lucene2vec <indexDir> <field>"
            + " [--dims N] [--power P] [--minDocFreq N] [--maxTerms N] [--out FILE]";

    /** Wall-clock start, set once at the beginning of {@link #main(String[])}. */
    private static long started;

    private Lucene2vec()
    {
    }

    /**
     * Runs the export.
     *
     * @param args index directory, field, then options
     * @throws IOException if the index or the output file cannot be accessed
     */
    public static void main(
        final String[] args
    ) throws IOException {
        started = System.currentTimeMillis();
        if (args.length < 2) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        final Path indexDir = Paths.get(args[0]);
        final String field = args[1];
        int dims = 100;
        double power = 0.5d;
        int minDocFreq = 3;
        int maxTerms = 10_000;
        Path out = Paths.get("vectors.bin");
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--power" -> power = Double.parseDouble(args[++i]);
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
                case "--out" -> out = Paths.get(args[++i]);
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }

        log("opening index %s", indexDir);
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            final Table table = termDocTable(reader, field, minDocFreq, maxTerms);
            final int termCount = table.words().length;
            final int docCount = termCount == 0 ? 0 : table.cells()[0].length;
            if (termCount < 2 || docCount < 2) {
                System.err.println(
                    "too few terms or documents after selection: "
                        + termCount + " terms, " + docCount + " documents");
                System.exit(1);
                return;
            }
            log("matrix %d x %d, %,d non-zero cells (%.2f%% dense)",
                termCount, docCount, table.nonZero(),
                100d * table.nonZero() / ((long) termCount * docCount));

            log("computing G2 residuals against IPF independence expectation");
            final ContingencySvd svd = new ContingencySvd(table.cells(), null)
                .residual(Assoc.G2);

            log("decomposing %d x %d residual matrix (dense SVD, dominant cost)",
                termCount, docCount);
            svd.decompose();
            log("decomposition done");

            if (power > 0d) {
                log("weighting axes by sigma^%.3f", power);
                svd.weightAxes(power);
            }
            final double[][] coords = svd.project(dims).coords();
            final int outDim = coords[0].length;
            log("projected to %d dimensions (requested %d)", outDim, dims);

            log("writing %d vectors to %s", termCount, out);
            writeWord2vec(out, table.words(), coords, outDim);
            log("done");

            System.out.printf(
                "%d terms x %d documents -> %d-dim vectors (requested %d)%n"
                    + "G2 residual, power=%.3f, written to %s in %d ms%n",
                termCount, docCount, outDim, dims, power, out,
                System.currentTimeMillis() - started);
        }
    }

    /**
     * Prints one elapsed-stamped progress line to standard error.
     */
    private static void log(
        final String format,
        final Object... args
    ) {
        System.err.printf(
            "[%,8d ms] %s%n",
            System.currentTimeMillis() - started,
            String.format(format, args));
    }

    /**
     * Selects the most frequent qualifying terms of a field and fills their raw
     * term-by-document count table over the live documents.
     */
    private static Table termDocTable(
        final IndexReader reader,
        final String field,
        final int minDocFreq,
        final int maxTerms
    ) throws IOException {
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("no indexed terms for field: " + field);
        }

        final Bits live = MultiBits.getLiveDocs(reader);
        final int maxDoc = reader.maxDoc();
        final int[] column = new int[maxDoc];
        Arrays.fill(column, -1);
        int docCount = 0;
        for (int doc = 0; doc < maxDoc; doc++) {
            if (live == null || live.get(doc)) {
                column[doc] = docCount++;
            }
        }
        log("index has %,d documents (%,d live)", maxDoc, docCount);

        log("scanning term dictionary of field '%s' (minDocFreq=%d)", field, minDocFreq);
        record Selected(BytesRef bytes, String word, long totalFreq) {}
        final List<Selected> kept = new ArrayList<>();
        final TermsEnum scan = terms.iterator();
        BytesRef term;
        long scanned = 0L;
        while ((term = scan.next()) != null) {
            scanned++;
            if (scanned % 500_000L == 0L) {
                log("  scanned %,d terms, %,d kept", scanned, kept.size());
            }
            if (scan.docFreq() < minDocFreq) {
                continue;
            }
            kept.add(new Selected(
                BytesRef.deepCopyOf(term),
                term.utf8ToString(),
                scan.totalTermFreq()));
        }
        kept.sort((a, b) -> Long.compare(b.totalFreq(), a.totalFreq()));
        final int termCount = Math.min(kept.size(), maxTerms);
        log("scanned %,d terms, %,d passed minDocFreq, keeping %,d (cap %,d)",
            scanned, kept.size(), termCount, maxTerms);

        log("filling %d x %d count matrix", termCount, docCount);
        final String[] words = new String[termCount];
        final double[][] cells = new double[termCount][docCount];
        final TermsEnum seek = terms.iterator();
        PostingsEnum postings = null;
        long nonZero = 0L;
        final int logStep = Math.max(1, termCount / 20);
        for (int row = 0; row < termCount; row++) {
            final Selected selected = kept.get(row);
            words[row] = selected.word();
            if (seek.seekExact(selected.bytes())) {
                postings = seek.postings(postings, PostingsEnum.FREQS);
                for (int doc = postings.nextDoc();
                        doc != DocIdSetIterator.NO_MORE_DOCS;
                        doc = postings.nextDoc()) {
                    final int col = column[doc];
                    if (col >= 0) {
                        cells[row][col] = postings.freq();
                        nonZero++;
                    }
                }
            }
            if ((row + 1) % logStep == 0 || row + 1 == termCount) {
                log("  filled %,d / %,d terms", row + 1, termCount);
            }
        }
        return new Table(words, cells, nonZero);
    }

    /**
     * Writes term vectors in the word2vec binary format: an ASCII header line
     * {@code "count dim\n"}, then per term its UTF-8 form, a space, {@code dim}
     * little-endian float32 values, and a newline. Whitespace inside a term is
     * replaced by an underscore so the space-delimited format stays parseable.
     */
    private static void writeWord2vec(
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
