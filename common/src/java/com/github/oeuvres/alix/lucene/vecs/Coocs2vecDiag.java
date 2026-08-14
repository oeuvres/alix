package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.vecs.VecUtil.SelectedTerm;

/**
 * Writes diagnostic data for a {@code coocs2vec}-style selected-term
 * co-occurrence model without rebuilding the full dense matrix or rerunning the
 * SVD.
 * <p>
 * The selected vocabulary and positional pair semantics are the same as in
 * {@code Coocs2vec}: terms are selected by minimum document frequency then by
 * decreasing total frequency, and each unordered pair of selected token
 * occurrences at distance {@code 1..distance} contributes one count in each
 * direction. A single rail pass accumulates the directed co-occurrence mass of
 * every selected term and the complete co-occurrence rows of the requested
 * diagnostic terms. This is sufficient to recover the independence expectation
 * and the signed Poisson-deviance residual used by the current
 * {@code ContingencySvd.Assoc.G2} when no structural mask is supplied.
 * </p>
 * <p>
 * For the pivot against each explicit target, the report also records an
 * absolute-distance histogram and the expected count under the stochastic
 * effective-window rule of the original word2vec implementation: a pair at
 * distance {@code d} in a maximum window {@code w} receives expected weight
 * {@code (w - d + 1) / w}. This isolates the effect of word2vec's dynamic
 * window from the current flat Coocs2vec window.
 * </p>
 * <p>
 * If {@code --vectors FILE} is supplied, the existing word2vec binary file is
 * read and normalised in memory. The report then adds cosine similarities and
 * ranks without recomputing the SVD. The report is one UTF-8 text file with
 * tab-separated sections: metadata, requested term statistics, pivot/target
 * pairs, distance histograms, and the top raw, G2, and vector neighbours of
 * every requested diagnostic term.
 * </p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.Coocs2vecDiag <indexDir> <field> <pivot> \
 *     [--targets schème,thématiser,instrumental,outil] \
 *     [--sideDir DIR] [--distance 30] [--minDocFreq 3] [--maxTerms 10000] \
 *     [--top 100] [--vectors content-coocs30.bin] [--out coocs-diag.tsv]
 * }</pre>
 */
public final class Coocs2vecDiag
{
    /** One rail-pass diagnostic aggregation. */
    private record Counts(
        long[][] focusCounts,
        long[][] distances,
        long[] masses,
        long pairs
    ) {}

    /** Term statistics from the Lucene term dictionary. */
    private record TermStats(
        int docFreq,
        long totalFreq
    ) {}

    /** Normalised vectors read from a word2vec binary file. */
    private record Vectors(
        int dimensions,
        Map<String, float[]> rows
    ) {}

    private static final String USAGE =
        "usage: Coocs2vecDiag <indexDir> <field> <pivot>"
            + " [--targets a,b,c] [--sideDir DIR] [--distance N]"
            + " [--minDocFreq N] [--maxTerms N] [--top N]"
            + " [--vectors FILE] [--out FILE]";

    /** Wall-clock start, set once at the beginning of {@link #main(String[])}. */
    private static long started;

    /**
     * Non-instantiable command-line utility.
     */
    private Coocs2vecDiag()
    {
    }

    /**
     * Runs the diagnostic extraction and writes the report.
     *
     * @param args index directory, field, pivot, then command-line options
     * @throws IOException if the index, rail, vector file, or report cannot be accessed
     */
    public static void main(
        final String[] args
    ) throws IOException {
        started = System.currentTimeMillis();
        if (args.length < 3) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        final Path indexDir = Paths.get(args[0]);
        final String field = args[1];
        final String pivot = args[2];
        Path sideDir = indexDir;
        int distance = 30;
        int minDocFreq = 3;
        int maxTerms = 10_000;
        int top = 100;
        Path vectorsPath = null;
        Path out = Paths.get("coocs-diag.tsv");
        String targetsArg = "";

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--distance" -> distance = Integer.parseInt(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--out" -> out = Paths.get(args[++i]);
                case "--sideDir" -> sideDir = Paths.get(args[++i]);
                case "--targets" -> targetsArg = args[++i];
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--vectors" -> vectorsPath = Paths.get(args[++i]);
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }
        if (distance < 1) {
            throw new IllegalArgumentException("distance must be >= 1: " + distance);
        }
        if (maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be >= 2: " + maxTerms);
        }
        if (top < 1) {
            throw new IllegalArgumentException("top must be >= 1: " + top);
        }

        final List<String> targets = parseTargets(targetsArg, pivot);
        final List<String> focusWords = new ArrayList<>(1 + targets.size());
        focusWords.add(pivot);
        focusWords.addAll(targets);

        log("opening index %s", indexDir);
        try (
            DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir));
            TermRail rail = TermRail.open(sideDir, field)
        ) {
            if (rail.docCount() != reader.maxDoc()) {
                throw new IllegalArgumentException(
                    "rail/index document mismatch: rail=" + rail.docCount()
                        + ", index=" + reader.maxDoc());
            }

            log("building term lexicon for field '%s'", field);
            final TermLexicon lexicon = new TermLexicon(reader, field);

            log("selecting terms (minDocFreq=%d, cap=%d)", minDocFreq, maxTerms);
            final SelectedTerm[] selected = VecUtil.selectTerms(
                reader, field, minDocFreq, maxTerms);
            final int termCount = selected.length;
            if (termCount < 2) {
                throw new IllegalStateException("too few selected terms: " + termCount);
            }
            log("selected %,d terms", termCount);

            final String[] words = new String[termCount];
            final long[] totalFreqs = new long[termCount];
            final int[] docFreqs = new int[termCount];
            final int[] rowByTermId = new int[lexicon.vocabSize()];
            Arrays.fill(rowByTermId, -1);
            final Map<String, Integer> rowByWord = new HashMap<>(termCount * 2);
            final Terms terms = MultiTerms.getTerms(reader, field);
            if (terms == null) {
                throw new IllegalArgumentException("no indexed terms for field: " + field);
            }
            final TermsEnum termsEnum = terms.iterator();
            for (int row = 0; row < termCount; row++) {
                final SelectedTerm term = selected[row];
                final int termId = lexicon.id(term.bytes());
                if (termId < 1) {
                    throw new IllegalStateException(
                        "selected term absent from lexicon: " + term.word());
                }
                words[row] = term.word();
                totalFreqs[row] = term.totalFreq();
                rowByTermId[termId] = row;
                rowByWord.put(term.word(), row);
                if (termsEnum.seekExact(term.bytes())) {
                    docFreqs[row] = termsEnum.docFreq();
                }
            }

            final int[] focusRows = new int[focusWords.size()];
            for (int i = 0; i < focusWords.size(); i++) {
                focusRows[i] = rowByWord.getOrDefault(focusWords.get(i), -1);
            }
            final int pivotRow = focusRows[0];
            if (pivotRow < 0) {
                throw new IllegalArgumentException(
                    "pivot not in selected vocabulary: " + pivot
                        + " (minDocFreq=" + minDocFreq + ", maxTerms=" + maxTerms + ")");
            }

            log(
                "scanning rail once for %,d selected terms, %,d diagnostic terms, distance +/-%,d",
                termCount, focusWords.size(), distance);
            final Counts counts = count(
                rail, rowByTermId, focusRows, pivotRow, distance);
            log("rail scan done: %,d positional pairs", counts.pairs());

            Vectors vectors = null;
            if (vectorsPath != null) {
                log("reading vectors from %s", vectorsPath);
                vectors = readVectors(vectorsPath);
                log(
                    "read %,d vectors x %,d dimensions",
                    vectors.rows().size(), vectors.dimensions());
            }

            log("writing diagnostic report to %s", out);
            writeReport(
                out,
                reader,
                field,
                terms,
                pivot,
                targets,
                focusWords,
                focusRows,
                words,
                totalFreqs,
                docFreqs,
                counts,
                distance,
                minDocFreq,
                maxTerms,
                top,
                vectorsPath,
                vectors);
            log("done");

            System.out.printf(
                "diagnostic for '%s': %,d selected terms, %,d positional pairs, written to %s in %,d ms%n",
                pivot, termCount, counts.pairs(), out,
                System.currentTimeMillis() - started);
        }
    }

    /**
     * Counts selected-term marginals, complete rows for the requested focus terms,
     * and pivot/target distance histograms in one rail pass.
     *
     * @param rail positional term rail
     * @param rowByTermId selected matrix row by rail term id, {@code -1} if unselected
     * @param focusRows selected row for each focus term, {@code -1} if the term is unselected
     * @param pivotRow selected row of the pivot
     * @param distance maximum positional distance, inclusive
     * @return diagnostic counts
     */
    private static Counts count(
        final TermRail rail,
        final int[] rowByTermId,
        final int[] focusRows,
        final int pivotRow,
        final int distance
    ) {
        final int termCount = 1 + Arrays.stream(rowByTermId).max().orElse(-1);
        final long[] masses = new long[termCount];
        final long[][] focusCounts = new long[focusRows.length][termCount];
        final long[][] distances = new long[Math.max(0, focusRows.length - 1)][distance + 1];

        final int[] focusByRow = new int[termCount];
        Arrays.fill(focusByRow, -1);
        for (int focus = 0; focus < focusRows.length; focus++) {
            final int row = focusRows[focus];
            if (row >= 0) {
                focusByRow[row] = focus;
            }
        }

        final int[] targetByRow = new int[termCount];
        Arrays.fill(targetByRow, -1);
        for (int target = 1; target < focusRows.length; target++) {
            final int row = focusRows[target];
            if (row >= 0) {
                targetByRow[row] = target - 1;
            }
        }

        int[] rows = new int[0];
        long pairs = 0L;
        for (int docId = 0; docId < rail.docCount(); docId++) {
            final int docLen = rail.docLength(docId);
            if (docLen > rows.length) {
                rows = new int[docLen];
            }
            rail.copyDocument(docId, rows);
            for (int position = 0; position < docLen; position++) {
                final int termId = rows[position];
                rows[position] = (termId >= 0 && termId < rowByTermId.length)
                    ? rowByTermId[termId]
                    : -1;
            }

            for (int position = 0; position < docLen; position++) {
                final int row = rows[position];
                if (row < 0) {
                    continue;
                }
                final int end = Math.min(docLen, position + distance + 1);
                for (int next = position + 1; next < end; next++) {
                    final int col = rows[next];
                    if (col < 0) {
                        continue;
                    }
                    pairs++;
                    masses[row]++;
                    masses[col]++;

                    final int rowFocus = focusByRow[row];
                    if (rowFocus >= 0) {
                        focusCounts[rowFocus][col]++;
                    }
                    final int colFocus = focusByRow[col];
                    if (colFocus >= 0) {
                        focusCounts[colFocus][row]++;
                    }

                    final int delta = next - position;
                    if (row == pivotRow) {
                        final int target = targetByRow[col];
                        if (target >= 0) {
                            distances[target][delta]++;
                        }
                    }
                    if (col == pivotRow) {
                        final int target = targetByRow[row];
                        if (target >= 0) {
                            distances[target][delta]++;
                        }
                    }
                }
            }
        }
        return new Counts(focusCounts, distances, masses, pairs);
    }

    /**
     * Computes cosine similarity between two already unit-normalised float vectors.
     *
     * @param a first vector
     * @param b second vector
     * @return cosine similarity, or {@code NaN} if either vector is absent or dimensions differ
     */
    private static double cosine(
        final float[] a,
        final float[] b
    ) {
        if (a == null || b == null || a.length != b.length) {
            return Double.NaN;
        }
        double sum = 0d;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /**
     * Computes the current Coocs2vec signed G2 residual for one observed cell
     * under ordinary row-by-column independence.
     *
     * @param observed observed directed co-occurrence count
     * @param expected expected count under independence
     * @return signed Poisson deviance residual, or {@code 0} when both values are zero
     */
    private static double g2(
        final double observed,
        final double expected
    ) {
        if (expected <= 0d) {
            return observed == 0d ? 0d : Double.NaN;
        }
        final double deviance = 2d * (
            (observed > 0d ? observed * Math.log(observed / expected) : 0d)
                - observed
                + expected);
        return Math.copySign(
            Math.sqrt(Math.max(0d, deviance)),
            observed - expected);
    }

    /**
     * Returns one selected word's form as encoded in the word2vec binary format,
     * where whitespace is replaced by underscores.
     *
     * @param word indexed word form
     * @return word2vec token form
     */
    private static String key(
        final String word
    ) {
        final StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            final char c = word.charAt(i);
            out.append(Character.isWhitespace(c) ? '_' : c);
        }
        return out.toString();
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
     * Normalises one vector in place to unit Euclidean length.
     *
     * @param vector vector to normalise
     */
    private static void normalize(
        final float[] vector
    ) {
        double norm2 = 0d;
        for (final float value : vector) {
            norm2 += (double) value * value;
        }
        if (norm2 <= 0d) {
            return;
        }
        final double inverse = 1d / Math.sqrt(norm2);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] * inverse);
        }
    }

    /**
     * Parses a comma-separated target list, preserving order and removing blanks,
     * duplicates, and the pivot itself.
     *
     * @param value comma-separated target forms
     * @param pivot pivot form to exclude
     * @return ordered unique target forms
     */
    private static List<String> parseTargets(
        final String value,
        final String pivot
    ) {
        final Set<String> unique = new LinkedHashSet<>();
        for (final String part : value.split(",")) {
            final String term = part.strip();
            if (!term.isEmpty() && !term.equals(pivot)) {
                unique.add(term);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Ranks all selected context rows by one score vector, excluding the focus
     * row. Rank 1 is the greatest score; ties are broken lexicographically by
     * context form. Non-finite scores receive rank 0.
     *
     * @param values score by selected row
     * @param words selected word forms
     * @param focusRow row to exclude
     * @return rank by selected row; the focus row and non-finite rows contain 0
     */
    private static int[] ranks(
        final double[] values,
        final String[] words,
        final int focusRow
    ) {
        final Integer[] order = new Integer[values.length - 1];
        int cursor = 0;
        for (int row = 0; row < values.length; row++) {
            if (row != focusRow) {
                order[cursor++] = row;
            }
        }
        Arrays.sort(order, (a, b) -> {
            final double av = values[a];
            final double bv = values[b];
            final boolean af = Double.isFinite(av);
            final boolean bf = Double.isFinite(bv);
            if (af != bf) {
                return af ? -1 : 1;
            }
            if (af) {
                final int cmp = Double.compare(bv, av);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return words[a].compareTo(words[b]);
        });

        final int[] ranks = new int[values.length];
        int rank = 1;
        for (final int row : order) {
            if (Double.isFinite(values[row])) {
                ranks[row] = rank++;
            }
        }
        return ranks;
    }

    /**
     * Reads an ASCII line terminated by LF from a binary input stream.
     *
     * @param in input stream
     * @return line without the trailing LF or CR
     * @throws IOException if the stream ends before a complete line is read
     */
    private static String readAsciiLine(
        final InputStream in
    ) throws IOException {
        final StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) c);
        }
        throw new EOFException("unexpected EOF while reading word2vec header");
    }

    /**
     * Reads one little-endian IEEE-754 float32 from a binary input stream.
     *
     * @param in input stream
     * @return decoded float
     * @throws IOException if four bytes cannot be read
     */
    private static float readFloatLe(
        final InputStream in
    ) throws IOException {
        final int b0 = in.read();
        final int b1 = in.read();
        final int b2 = in.read();
        final int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException("unexpected EOF in word2vec vector");
        }
        final int bits = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        return Float.intBitsToFloat(bits);
    }

    /**
     * Reads one UTF-8 word2vec token terminated by an ASCII space.
     *
     * @param in input stream positioned at the first token byte
     * @return decoded token
     * @throws IOException if EOF is reached before the separator
     */
    private static String readToken(
        final InputStream in
    ) throws IOException {
        byte[] bytes = new byte[32];
        int length = 0;
        int c;
        while ((c = in.read()) >= 0) {
            if (c == ' ') {
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            if (length == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytes.length * 2);
            }
            bytes[length++] = (byte) c;
        }
        throw new EOFException("unexpected EOF while reading word2vec token");
    }

    /**
     * Reads and normalises all rows of a word2vec binary file.
     *
     * @param path binary word2vec file
     * @return dimensions and normalised vectors keyed by their stored token form
     * @throws IOException if the file is malformed or cannot be read
     */
    private static Vectors readVectors(
        final Path path
    ) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            final String[] header = readAsciiLine(in).trim().split("\\s+");
            if (header.length != 2) {
                throw new IOException("invalid word2vec header: " + Arrays.toString(header));
            }
            final int count = Integer.parseInt(header[0]);
            final int dimensions = Integer.parseInt(header[1]);
            final Map<String, float[]> rows = new HashMap<>(count * 2);
            for (int row = 0; row < count; row++) {
                final String word = readToken(in);
                final float[] vector = new float[dimensions];
                for (int axis = 0; axis < dimensions; axis++) {
                    vector[axis] = readFloatLe(in);
                }
                normalize(vector);
                rows.put(word, vector);
                final int separator = in.read();
                if (separator != '\n') {
                    throw new IOException(
                        "expected LF after word2vec row " + row + ", got " + separator);
                }
            }
            return new Vectors(dimensions, rows);
        }
    }

    /**
     * Looks up corpus statistics for one canonical indexed term.
     *
     * @param terms field terms
     * @param word canonical indexed term form
     * @return document and total term frequency, both zero when the term is absent
     * @throws IOException if the term dictionary cannot be read
     */
    private static TermStats termStats(
        final Terms terms,
        final String word
    ) throws IOException {
        final TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(new BytesRef(word))) {
            return new TermStats(0, 0L);
        }
        return new TermStats(termsEnum.docFreq(), termsEnum.totalTermFreq());
    }

    /**
     * Returns the expected cell count under ordinary independence.
     *
     * @param rowMass directed co-occurrence mass of the row term
     * @param colMass directed co-occurrence mass of the column term
     * @param totalMass total directed co-occurrence mass
     * @return independence expectation, or zero when the total mass is zero
     */
    private static double expected(
        final long rowMass,
        final long colMass,
        final long totalMass
    ) {
        if (totalMass <= 0L) {
            return 0d;
        }
        return (double) rowMass * colMass / totalMass;
    }

    /**
     * Writes the complete diagnostic report.
     *
     * @param out report path
     * @param reader index reader
     * @param field indexed field name
     * @param terms Lucene terms for the field
     * @param pivot pivot word
     * @param targets explicit target words
     * @param focusWords pivot followed by explicit targets
     * @param focusRows selected row for each focus word
     * @param words selected word forms
     * @param totalFreqs selected corpus total frequencies
     * @param docFreqs selected document frequencies
     * @param counts rail-pass counts
     * @param distance maximum positional distance
     * @param minDocFreq selected-vocabulary minimum document frequency
     * @param maxTerms selected-vocabulary cap
     * @param top number of top rows written for each ranking
     * @param vectorsPath vector file path, or {@code null}
     * @param vectors loaded vectors, or {@code null}
     * @throws IOException if the report cannot be written
     */
    private static void writeReport(
        final Path out,
        final DirectoryReader reader,
        final String field,
        final Terms terms,
        final String pivot,
        final List<String> targets,
        final List<String> focusWords,
        final int[] focusRows,
        final String[] words,
        final long[] totalFreqs,
        final int[] docFreqs,
        final Counts counts,
        final int distance,
        final int minDocFreq,
        final int maxTerms,
        final int top,
        final Path vectorsPath,
        final Vectors vectors
    ) throws IOException {
        final long totalMass = counts.pairs() * 2L;
        final int pivotRow = focusRows[0];

        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write("# META\n");
            writer.write("key\tvalue\n");
            writer.write("field\t" + field + "\n");
            writer.write("pivot\t" + pivot + "\n");
            writer.write("documents\t" + reader.maxDoc() + "\n");
            writer.write("selected_terms\t" + words.length + "\n");
            writer.write("min_doc_freq\t" + minDocFreq + "\n");
            writer.write("max_terms\t" + maxTerms + "\n");
            writer.write("distance\t" + distance + "\n");
            writer.write("positional_pairs\t" + counts.pairs() + "\n");
            writer.write("directed_mass\t" + totalMass + "\n");
            writer.write("top\t" + top + "\n");
            writer.write("vectors\t" + (vectorsPath == null ? "" : vectorsPath) + "\n");
            writer.write("vector_dimensions\t" + (vectors == null ? 0 : vectors.dimensions()) + "\n");
            writer.write("\n");

            writer.write("# TERMS\n");
            writer.write("term\tselected\trow\ttotal_freq\tdoc_freq\tcooc_mass\n");
            for (int focus = 0; focus < focusWords.size(); focus++) {
                final String word = focusWords.get(focus);
                final int row = focusRows[focus];
                final TermStats stat = termStats(terms, word);
                writer.write(word);
                writer.write('\t');
                writer.write(row >= 0 ? "1" : "0");
                writer.write('\t');
                writer.write(Integer.toString(row));
                writer.write('\t');
                writer.write(Long.toString(stat.totalFreq()));
                writer.write('\t');
                writer.write(Integer.toString(stat.docFreq()));
                writer.write('\t');
                writer.write(row >= 0 ? Long.toString(counts.masses()[row]) : "");
                writer.write('\n');
            }
            writer.write("\n");

            final double[] pivotRaw = new double[words.length];
            final double[] pivotG2 = new double[words.length];
            final double[] pivotCos = new double[words.length];
            Arrays.fill(pivotCos, Double.NaN);
            for (int row = 0; row < words.length; row++) {
                pivotRaw[row] = counts.focusCounts()[0][row];
                final double exp = expected(counts.masses()[pivotRow], counts.masses()[row], totalMass);
                pivotG2[row] = g2(pivotRaw[row], exp);
            }
            if (vectors != null) {
                final float[] pivotVector = vectors.rows().get(key(pivot));
                for (int row = 0; row < words.length; row++) {
                    pivotCos[row] = cosine(
                        pivotVector,
                        vectors.rows().get(key(words[row])));
                }
            }
            final int[] pivotRawRanks = ranks(pivotRaw, words, pivotRow);
            final int[] pivotG2Ranks = ranks(pivotG2, words, pivotRow);
            final int[] pivotCosRanks = ranks(pivotCos, words, pivotRow);

            writer.write("# PAIRS\n");
            writer.write(
                "pivot\tterm\tselected\trow\ttotal_freq\tdoc_freq\tcooc_mass"
                    + "\traw_count\traw_rank\texpected\tg2\tg2_rank"
                    + "\tcosine\tcosine_rank\tdynamic_window_count\n");
            for (int target = 0; target < targets.size(); target++) {
                final String word = targets.get(target);
                final int row = focusRows[target + 1];
                final TermStats stat = termStats(terms, word);
                writer.write(pivot);
                writer.write('\t');
                writer.write(word);
                writer.write('\t');
                writer.write(row >= 0 ? "1" : "0");
                writer.write('\t');
                writer.write(Integer.toString(row));
                writer.write('\t');
                writer.write(Long.toString(stat.totalFreq()));
                writer.write('\t');
                writer.write(Integer.toString(stat.docFreq()));
                if (row >= 0) {
                    writer.write('\t');
                    final long raw = counts.focusCounts()[0][row];
                    final double exp = expected(
                        counts.masses()[pivotRow], counts.masses()[row], totalMass);
                    writer.write(Long.toString(counts.masses()[row]));
                    writer.write('\t');
                    writer.write(Long.toString(raw));
                    writer.write('\t');
                    writer.write(Integer.toString(pivotRawRanks[row]));
                    writer.write('\t');
                    writer.write(Double.toString(exp));
                    writer.write('\t');
                    writer.write(Double.toString(pivotG2[row]));
                    writer.write('\t');
                    writer.write(Integer.toString(pivotG2Ranks[row]));
                    writer.write('\t');
                    writer.write(Double.isFinite(pivotCos[row]) ? Double.toString(pivotCos[row]) : "");
                    writer.write('\t');
                    writer.write(pivotCosRanks[row] == 0 ? "" : Integer.toString(pivotCosRanks[row]));
                    writer.write('\t');
                    writer.write(Double.toString(
                        weightedWindowCount(counts.distances()[target], distance)));
                }
                else {
                    writer.write("\t\t\t\t\t\t\t\t\t");
                }
                writer.write('\n');
            }
            writer.write("\n");

            writer.write("# DISTANCES\n");
            writer.write("pivot\tterm\tdistance\tcount\tdynamic_window_weight\tweighted_count\n");
            for (int target = 0; target < targets.size(); target++) {
                for (int delta = 1; delta <= distance; delta++) {
                    final long count = counts.distances()[target][delta];
                    final double weight = windowWeight(delta, distance);
                    writer.write(pivot);
                    writer.write('\t');
                    writer.write(targets.get(target));
                    writer.write('\t');
                    writer.write(Integer.toString(delta));
                    writer.write('\t');
                    writer.write(Long.toString(count));
                    writer.write('\t');
                    writer.write(Double.toString(weight));
                    writer.write('\t');
                    writer.write(Double.toString(count * weight));
                    writer.write('\n');
                }
            }
            writer.write("\n");

            for (int focus = 0; focus < focusWords.size(); focus++) {
                final int focusRow = focusRows[focus];
                if (focusRow < 0) {
                    continue;
                }
                final double[] raw = new double[words.length];
                final double[] residual = new double[words.length];
                final double[] cosines = new double[words.length];
                Arrays.fill(cosines, Double.NaN);
                for (int row = 0; row < words.length; row++) {
                    raw[row] = counts.focusCounts()[focus][row];
                    residual[row] = g2(
                        raw[row],
                        expected(counts.masses()[focusRow], counts.masses()[row], totalMass));
                }
                if (vectors != null) {
                    final float[] focusVector = vectors.rows().get(key(focusWords.get(focus)));
                    for (int row = 0; row < words.length; row++) {
                        cosines[row] = cosine(
                            focusVector,
                            vectors.rows().get(key(words[row])));
                    }
                }

                writeTop(
                    writer, "RAW_TOP", focusWords.get(focus), focusRow,
                    raw, raw, residual, cosines, words, totalFreqs, docFreqs,
                    counts.masses(), totalMass, top);
                writeTop(
                    writer, "G2_TOP", focusWords.get(focus), focusRow,
                    residual, raw, residual, cosines, words, totalFreqs, docFreqs,
                    counts.masses(), totalMass, top);
                if (vectors != null) {
                    writeTop(
                        writer, "VECTOR_TOP", focusWords.get(focus), focusRow,
                        cosines, raw, residual, cosines, words, totalFreqs, docFreqs,
                        counts.masses(), totalMass, top);
                }
            }
        }
    }

    /**
     * Writes one top-neighbour section sorted by a supplied score vector.
     *
     * @param writer report writer
     * @param section section name
     * @param focus focus term form
     * @param focusRow selected focus row
     * @param sortValues score used for ranking
     * @param raw raw directed co-occurrence counts by selected row
     * @param residual G2 residuals by selected row
     * @param cosines vector cosine similarities by selected row
     * @param words selected word forms
     * @param totalFreqs selected corpus total frequencies
     * @param docFreqs selected document frequencies
     * @param masses selected directed co-occurrence masses
     * @param totalMass total directed co-occurrence mass
     * @param top maximum rows to write
     * @throws IOException if writing fails
     */
    private static void writeTop(
        final BufferedWriter writer,
        final String section,
        final String focus,
        final int focusRow,
        final double[] sortValues,
        final double[] raw,
        final double[] residual,
        final double[] cosines,
        final String[] words,
        final long[] totalFreqs,
        final int[] docFreqs,
        final long[] masses,
        final long totalMass,
        final int top
    ) throws IOException {
        final int[] rankByRow = ranks(sortValues, words, focusRow);
        int available = 0;
        for (final int rank : rankByRow) {
            available = Math.max(available, rank);
        }
        final int limit = Math.min(top, available);
        final int[] rowByRank = new int[limit + 1];
        for (int row = 0; row < rankByRow.length; row++) {
            final int rank = rankByRow[row];
            if (rank > 0 && rank <= limit) {
                rowByRank[rank] = row;
            }
        }

        writer.write("# " + section + "\tfocus=" + focus + "\n");
        writer.write(
            "rank\tterm\ttotal_freq\tdoc_freq\tcooc_mass\traw_count\texpected\tg2\tcosine\n");
        for (int rank = 1; rank <= limit; rank++) {
            final int row = rowByRank[rank];
            final double exp = expected(masses[focusRow], masses[row], totalMass);
            writer.write(Integer.toString(rank));
            writer.write('\t');
            writer.write(words[row]);
            writer.write('\t');
            writer.write(Long.toString(totalFreqs[row]));
            writer.write('\t');
            writer.write(Integer.toString(docFreqs[row]));
            writer.write('\t');
            writer.write(Long.toString(masses[row]));
            writer.write('\t');
            writer.write(Long.toString((long) raw[row]));
            writer.write('\t');
            writer.write(Double.toString(exp));
            writer.write('\t');
            writer.write(Double.toString(residual[row]));
            writer.write('\t');
            writer.write(Double.isFinite(cosines[row]) ? Double.toString(cosines[row]) : "");
            writer.write('\n');
        }
        writer.write("\n");
    }

    /**
     * Computes the expected number of retained word2vec training pairs represented
     * by an absolute-distance histogram under word2vec's stochastic effective
     * window, before subsampling and negative sampling.
     *
     * @param histogram pair counts indexed by absolute distance
     * @param window maximum window size
     * @return linearly distance-weighted expected pair count
     */
    private static double weightedWindowCount(
        final long[] histogram,
        final int window
    ) {
        double sum = 0d;
        for (int distance = 1; distance <= window; distance++) {
            sum += histogram[distance] * windowWeight(distance, window);
        }
        return sum;
    }

    /**
     * Returns the probability that the original word2vec dynamic-window rule
     * retains a context at one absolute distance.
     *
     * @param distance absolute token distance in {@code [1, window]}
     * @param window maximum word2vec window
     * @return expected inclusion weight
     */
    private static double windowWeight(
        final int distance,
        final int window
    ) {
        return (window - distance + 1d) / window;
    }
}
