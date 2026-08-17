package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

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

import com.github.oeuvres.alix.lucene.vecs.VecUtil.SelectedTerm;

import smile.util.SparseArray;

/**
 * Reduces a Lucene term-by-document table to dense term vectors by signed G²
 * residual SVD, and writes them in the word2vec binary format.
 *
 * <p>
 * This is a single-run experiment, not a library. It opens an on-disk index,
 * selects the most frequent terms of one field passing a minimum document
 * frequency, fills a sparse raw term-by-document count table, hands it to
 * {@link TermDocSvd} for a signed G² deviance-residual decomposition, weights
 * the axes by {@code sigma^power}, and exports the leading coordinates.
 * </p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.Docs2vec <indexDir> <field> \
 *     [--dims 100] [--power 0.5] [--minDocFreq 3] [--maxTerms 10000] \
 *     [--out vectors.bin]
 * }</pre>
 */
public final class Docs2vec
{
    /** Selected vocabulary and its raw term-by-document count table. */
    private record Table(
        String[] words,
        SparseArray[] cells,
        int docCount,
        long nonZero
    ) {}

    private static final String USAGE =
        "usage: docs2vec <indexDir> <field>"
            + " [--dims N] [--power P] [--minDocFreq N] [--maxTerms N] [--out FILE]";

    /** Wall-clock start, set once at the beginning of {@link #main(String[])}. */
    private static long started;

    private Docs2vec()
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
        Path out = null;
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
        
        if (out == null) {
            String name = indexDir.getFileName().toString();
            name += "-docs";
            name += "-" + field;
            name += "-dims" + dims;
            name += ".bin";
            out = Paths.get(name);
        }

        log("opening index %s", indexDir);
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            log("selecting terms (minDocFreq=%d, cap=%d)", minDocFreq, maxTerms);
            final SelectedTerm[] selected = VecUtil.selectTerms(
                reader, field, minDocFreq, maxTerms);
            final Table table = termDocTable(reader, field, selected);
            final int termCount = table.words().length;
            final int docCount = table.docCount();
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

            log("preparing signed G2 deviance residuals against independence expectation");
            final TermDocSvd svd = new TermDocSvd(
                table.cells(), docCount, TermDocSvd.Residual.DEVIANCE);

            log("decomposing %d x %d residual matrix to top %d dims (dense column Gram EVD)",
                termCount, docCount, dims);
            svd.decompose(dims);
            log("decomposition done, rank %d", svd.rank());

            if (power != 0d) {
                log("weighting axes by sigma^%.3f", power);
            }
            final double[][] coords = svd.coords(power);
            final int outDim = coords[0].length;
            log("projected to %d dimensions (requested %d)", outDim, dims);

            log("writing %d vectors to %s", termCount, out);
            VecUtil.writeWord2vec(out, table.words(), coords, outDim);
            log("done");

            System.out.printf(
                "%d terms x %d documents -> %d-dim vectors (requested %d)%n"
                    + "signed G2 deviance residual, power=%.3f, written to %s in %d ms%n",
                termCount, docCount, outDim, dims, power, out,
                System.currentTimeMillis() - started);
        }
    }

    /**
     * Prints one elapsed-stamped progress line to standard error.
     *
     * @param format printf-style format string
     * @param args format arguments
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
     * Fills the raw term-by-document count table for a selected vocabulary.
     *
     * @param reader index reader
     * @param field indexed field name
     * @param selected selected vocabulary
     * @return vocabulary forms and raw count table
     * @throws IOException if postings cannot be read
     */
    private static Table termDocTable(
        final IndexReader reader,
        final String field,
        final SelectedTerm[] selected
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

        final int termCount = selected.length;
        log("filling sparse %d x %d count matrix", termCount, docCount);
        final String[] words = new String[termCount];
        final SparseArray[] cells = new SparseArray[termCount];
        final TermsEnum seek = terms.iterator();
        PostingsEnum postings = null;
        long nonZero = 0L;
        final int logStep = Math.max(1, termCount / 20);
        for (int row = 0; row < termCount; row++) {
            final SelectedTerm term = selected[row];
            final SparseArray sparseRow = new SparseArray();
            cells[row] = sparseRow;
            words[row] = term.word();
            if (seek.seekExact(term.bytes())) {
                postings = seek.postings(postings, PostingsEnum.FREQS);
                for (int doc = postings.nextDoc();
                        doc != DocIdSetIterator.NO_MORE_DOCS;
                        doc = postings.nextDoc()) {
                    final int col = column[doc];
                    if (col >= 0) {
                        sparseRow.append(col, postings.freq());
                        nonZero++;
                    }
                }
            }
            if ((row + 1) % logStep == 0 || row + 1 == termCount) {
                log("  filled %,d / %,d terms", row + 1, termCount);
            }
        }
        return new Table(words, cells, docCount, nonZero);
    }
}
