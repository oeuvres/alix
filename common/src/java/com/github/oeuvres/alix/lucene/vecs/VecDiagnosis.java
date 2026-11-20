package com.github.oeuvres.alix.lucene.vecs;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Diagnoses one word2vec binary model: neighbours of test words, and
 * measures of how the space treats words of different frequencies.
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.VecDiagnosis <model.bin> \
 *     [--freqs terms.tsv] [--words enfant,outil|@words.txt] [--pairs enfant:adulte,...|@pairs.txt] \
 *     [--rank cos|tau|quality] [--top 20] [--sample 200] [--seed 1]
 * }</pre>
 *
 * <p>The model is read by {@link VecModel#load(Path)}, and neighbours and
 * cosines come from {@link VecModel#nearest(int[], int)}, or with
 * {@code --rank tau|quality} from
 * {@link VecModel#nearestShrunk(int[], int, VecModel.Shrink)}, and from
 * {@link VecModel#cosine(int, int)}: the lists are those served from the same
 * model.</p>
 *
 * <p>Frequencies come from {@code --freqs}, a tab-separated file with a
 * header naming the columns {@code term} and {@code tf} (other columns are
 * ignored), exported from the field of the model. Words of the model absent
 * from the file have no frequency and are left out of the frequency
 * classes. Without {@code --freqs}, the {@code tf} column of the vocabulary
 * file of the model is used, see {@link VecModel#attribute(int, String)};
 * without either, the row order of the model stands for the frequency rank,
 * as written by {@link Coocs2vec}; classes are then classes of rank, and the
 * hapax shares are not available.</p>
 *
 * <p>Output, plain text with tab-separated columns, one block per measure:</p>
 *
 * <ul>
 * <li>by frequency class (powers of 2, as in the inertia log of
 * {@link Coocs2vec}): number of rows, median vector norm, share of the
 * squared norm of all vectors ({@link VecModel#norm(int)}). The SVD fits the
 * rows of largest norm first, so this share tells which words define the
 * axes.</li>
 * <li>by frequency class of a query word: median frequency of its top
 * neighbours, and share of hapaxes among them, over a sample of query words
 * per class drawn with a fixed seed.</li>
 * <li>the share of hapaxes among the top neighbours of the words of 20 to
 * 200 occurrences, and the mean cosine of random pairs, two measures used in
 * the Javadoc of {@link Coocs2vec}.</li>
 * <li>the neighbours of each test word, with cosine and frequency, and the
 * cosine of each test pair.</li>
 * </ul>
 *
 * <p>Lists of words or pairs given with {@code @file} are read as UTF-8,
 * one entry per line: command-line arguments are decoded in the encoding of
 * the system, which may lose accents. The output is written in UTF-8.</p>
 *
 * <p>{@code --top} is the length of the neighbour lists, 20 by default as in
 * the measures of {@link Coocs2vec}. {@code --sample} is the number of query
 * words drawn per class: it sets the precision of the class figures, not the
 * model.</p>
 */
public final class VecDiagnosis
{
    /** Number of random pairs for the mean cosine. */
    private static final int RANDOM_PAIRS = 100_000;

    /** Command-line usage. */
    private static final String USAGE =
        "usage: VecDiagnosis <model.bin> [--freqs terms.tsv] [--words w1,w2|@file]"
            + " [--pairs a:b,c:d|@file] [--rank cos|tau|quality] [--top 20] [--sample 200] [--seed 1]";

    /** Corpus frequency of each vector, or -1 when unknown; the rank without a frequency file. */
    private final long[] freqs;

    /** Whether frequencies come from a file, not from row order. */
    private final boolean hasFreqs;

    /** The model diagnosed. */
    private final VecModel model;

    /** Factor of the shrunk ranking, or null to rank by cosine. */
    private final VecModel.Shrink shrink;

    /**
     * Loads a model and its frequencies.
     *
     * @param path word2vec binary file
     * @param freqFile frequency file, or null for the vocabulary file of the
     *        model, or else the row order
     * @param shrink factor of the shrunk ranking, or null to rank by cosine
     * @throws IOException if a file cannot be read
     * @throws IllegalArgumentException if the model cannot give the factor
     */
    private VecDiagnosis(final Path path, final Path freqFile, final VecModel.Shrink shrink) throws IOException
    {
        model = VecModel.load(path);
        this.shrink = shrink;
        if (shrink != null && !model.canShrink(shrink)) {
            throw new IllegalArgumentException("--rank " + shrink.name().toLowerCase()
                + " needs the vocabulary file of the model");
        }
        freqs = new long[model.size()];
        final boolean vocab = freqFile == null && model.attributes().contains("tf");
        hasFreqs = freqFile != null || vocab;
        if (vocab) {
            for (int id = 0; id < freqs.length; id++) {
                final double tf = model.attribute(id, "tf");
                freqs[id] = Double.isNaN(tf) ? -1L : (long) tf;
            }
            return;
        }
        if (!hasFreqs) {
            for (int id = 0; id < freqs.length; id++) {
                freqs[id] = id + 1L;
            }
            return;
        }
        Arrays.fill(freqs, -1L);
        final List<String> lines = Files.readAllLines(freqFile, StandardCharsets.UTF_8);
        final List<String> columns = Arrays.asList(lines.get(0).split("\t"));
        final int termCol = columns.indexOf("term");
        final int tfCol = columns.indexOf("tf");
        if (termCol < 0 || tfCol < 0) {
            throw new IllegalArgumentException("frequency file needs the columns term and tf: " + freqFile);
        }
        for (int i = 1; i < lines.size(); i++) {
            final String[] cells = lines.get(i).split("\t");
            if (cells.length <= Math.max(termCol, tfCol)) {
                continue;
            }
            final int id = model.id(cells[termCol]);
            if (id >= 0) {
                freqs[id] = Long.parseLong(cells[tfCol].trim());
            }
        }
    }

    /**
     * Runs the diagnosis.
     *
     * @param args model file, then options
     * @throws IOException if a file cannot be read
     */
    public static void main(final String[] args) throws IOException
    {
        if (args.length < 1) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Path freqFile = null;
        String wordsArg = null;
        String pairsArg = null;
        int top = 20;
        int sample = 200;
        long seed = 1L;
        VecModel.Shrink shrink = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--freqs" -> freqFile = Paths.get(args[++i]);
                case "--words" -> wordsArg = args[++i];
                case "--pairs" -> pairsArg = args[++i];
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--sample" -> sample = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--rank" -> {
                    final String rank = args[++i];
                    shrink = "cos".equals(rank) ? null : VecModel.Shrink.valueOf(rank.toUpperCase(Locale.ROOT));
                }
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }
        if (top < 1 || sample < 1) {
            throw new IllegalArgumentException("top and sample must be >= 1");
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        final Path path = Paths.get(args[0]);
        final VecDiagnosis diagnosis = new VecDiagnosis(path, freqFile, shrink);
        System.out.printf(Locale.ROOT, "model\t%s%nrows\t%d%ndims\t%d%nfrequencies\t%s%nrank\t%s%ntau\t%.4f%n",
            path.getFileName(), diagnosis.model.size(), diagnosis.model.dim(),
            freqFile != null ? freqFile.getFileName() + ", " + diagnosis.known() + " rows with a frequency"
                : diagnosis.hasFreqs ? "vocabulary file, " + diagnosis.known() + " rows with a frequency"
                : "row rank",
            shrink == null ? "cosine" : shrink.name().toLowerCase(), diagnosis.model.tau());
        diagnosis.printClasses(top, sample, seed);
        diagnosis.printGlobal(top, seed);
        if (wordsArg != null) {
            diagnosis.printWords(list(wordsArg), top);
        }
        if (pairsArg != null) {
            diagnosis.printPairs(list(pairsArg));
        }
    }

    /**
     * Returns the frequency class of a vector: the power of 2 of its
     * frequency, or -1 when the frequency is unknown.
     *
     * @param id vector id
     * @return class index
     */
    private int frequencyClass(final int id)
    {
        final long freq = freqs[id];
        return freq < 1L ? -1 : 63 - Long.numberOfLeadingZeros(freq);
    }

    /**
     * Returns the hapax share and the median frequency of the top neighbours
     * of a vector, over the neighbours with a known frequency.
     *
     * @param query vector id
     * @param top number of neighbours
     * @return median frequency and hapax share, NaN when no neighbour has a frequency
     */
    private double[] neighbourFigures(final int query, final int top)
    {
        final List<VecModel.Neighbor> near = neighbours(query, top);
        final double[] f = new double[near.size()];
        int counted = 0;
        int hapax = 0;
        for (final VecModel.Neighbor neighbor : near) {
            final long freq = freqs[neighbor.id()];
            if (freq < 1L) {
                continue;
            }
            f[counted++] = freq;
            if (freq == 1L) {
                hapax++;
            }
        }
        return new double[] {
            median(Arrays.copyOf(f, counted)),
            counted > 0 ? (double) hapax / counted : Double.NaN
        };
    }

    /**
     * Returns the top neighbours of a vector with the ranking of the run.
     *
     * @param query vector id
     * @param top number of neighbours
     * @return neighbours, nearest first
     */
    private List<VecModel.Neighbor> neighbours(final int query, final int top)
    {
        final int[] pivot = {query};
        return shrink == null ? model.nearest(pivot, top) : model.nearestShrunk(pivot, top, shrink);
    }

    /**
     * Returns the number of vectors with a known frequency.
     *
     * @return vector count
     */
    private int known()
    {
        int count = 0;
        for (final long freq : freqs) {
            if (freq > 0L) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the label of a frequency class.
     *
     * @param c class index
     * @return label such as {@code 16-31}
     */
    private static String label(final int c)
    {
        return (1L << c) + "-" + ((1L << (c + 1)) - 1);
    }

    /**
     * Reads a list argument: comma-separated entries, or {@code @file} with
     * one entry per line, read as UTF-8.
     *
     * @param arg argument
     * @return entries, blank ones removed
     * @throws IOException if the file cannot be read
     */
    private static List<String> list(final String arg) throws IOException
    {
        final List<String> raw = arg.startsWith("@")
            ? Files.readAllLines(Paths.get(arg.substring(1)), StandardCharsets.UTF_8)
            : Arrays.asList(arg.split(","));
        final List<String> out = new ArrayList<>();
        for (final String entry : raw) {
            final String e = entry.strip();
            if (!e.isEmpty()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Returns the median of values.
     *
     * @param values values, not modified
     * @return median, or NaN for no value
     */
    private static double median(final double[] values)
    {
        if (values.length == 0) {
            return Double.NaN;
        }
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        final int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2d;
    }

    /**
     * Prints, by frequency class, the norm figures of the vectors and the
     * neighbour figures of a sample of query vectors.
     *
     * @param top neighbours per query
     * @param sample query vectors drawn per class
     * @param seed random seed
     */
    private void printClasses(final int top, final int sample, final long seed)
    {
        final List<List<Integer>> classes = new ArrayList<>();
        for (int c = 0; c < 64; c++) {
            classes.add(new ArrayList<>());
        }
        double total = 0d;
        for (int id = 0; id < model.size(); id++) {
            final double norm = model.norm(id);
            total += norm * norm;
            final int c = frequencyClass(id);
            if (c >= 0) {
                classes.get(c).add(id);
            }
        }
        System.out.println();
        System.out.println(hasFreqs
            ? "freq class\trows\tmedian norm\tnorm² %\tqueries\tmedian neighbour freq\tneighbour hapax %"
            : "rank class\trows\tmedian norm\tnorm² %\tqueries\tmedian neighbour rank");
        final Random random = new Random(seed);
        for (int c = 0; c < 64; c++) {
            final List<Integer> members = classes.get(c);
            if (members.isEmpty()) {
                continue;
            }
            final double[] memberNorms = new double[members.size()];
            double norm2 = 0d;
            for (int i = 0; i < members.size(); i++) {
                memberNorms[i] = model.norm(members.get(i));
                norm2 += memberNorms[i] * memberNorms[i];
            }
            final List<Integer> queries = new ArrayList<>(members);
            Collections.shuffle(queries, random);
            final int[] drawn = queries.subList(0, Math.min(sample, queries.size()))
                .stream().mapToInt(Integer::intValue).toArray();
            final double[][] figures = IntStream.of(drawn).parallel()
                .mapToObj(query -> neighbourFigures(query, top)).toArray(double[][]::new);
            final double[] medianFreqs = Arrays.stream(figures).mapToDouble(s -> s[0])
                .filter(v -> !Double.isNaN(v)).toArray();
            final double hapaxShare = Arrays.stream(figures).mapToDouble(s -> s[1])
                .filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
            if (hasFreqs) {
                System.out.printf(Locale.ROOT, "%s\t%d\t%.3f\t%.2f\t%d\t%.0f\t%.1f%n",
                    label(c), members.size(), median(memberNorms), 100d * norm2 / total,
                    drawn.length, median(medianFreqs), 100d * hapaxShare);
            }
            else {
                System.out.printf(Locale.ROOT, "%s\t%d\t%.3f\t%.2f\t%d\t%.0f%n",
                    label(c), members.size(), median(memberNorms), 100d * norm2 / total,
                    drawn.length, median(medianFreqs));
            }
        }
    }

    /**
     * Prints the measures of the whole model: hapaxes among the neighbours of
     * the words of 20 to 200 occurrences, and the mean cosine of random pairs.
     *
     * @param top neighbours per query
     * @param seed random seed
     */
    private void printGlobal(final int top, final long seed)
    {
        System.out.println();
        if (hasFreqs) {
            final int[] mid = IntStream.range(0, model.size())
                .filter(id -> freqs[id] >= 20L && freqs[id] <= 200L).toArray();
            final double share = IntStream.of(mid).parallel()
                .mapToDouble(query -> neighbourFigures(query, top)[1])
                .filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
            System.out.printf(Locale.ROOT,
                "hapax %% among top-%d neighbours of words of 20-200 occurrences (%d words)\t%.2f%n",
                top, mid.length, 100d * share);
        }
        final Random random = new Random(seed);
        double sum = 0d;
        int pairs = 0;
        while (pairs < RANDOM_PAIRS) {
            final int a = random.nextInt(model.size());
            final int b = random.nextInt(model.size());
            if (a == b) {
                continue;
            }
            sum += model.cosine(a, b);
            pairs++;
        }
        System.out.printf(Locale.ROOT, "mean cosine of %d random pairs\t%.4f%n", RANDOM_PAIRS, sum / RANDOM_PAIRS);
    }

    /**
     * Prints the cosine of test pairs {@code a:b}.
     *
     * @param pairs test pairs
     */
    private void printPairs(final List<String> pairs)
    {
        System.out.println();
        System.out.println("pair\tcosine");
        for (final String pair : pairs) {
            final String[] ab = pair.split(":", 2);
            if (ab.length < 2) {
                continue;
            }
            final int a = model.id(ab[0].strip());
            final int b = model.id(ab[1].strip());
            if (a < 0 || b < 0) {
                System.out.printf("%s\tabsent%n", pair);
                continue;
            }
            System.out.printf(Locale.ROOT, "%s\t%.4f%n", pair, model.cosine(a, b));
        }
    }

    /**
     * Prints the neighbours of each test word: rank, word, cosine, frequency.
     *
     * @param test test words
     * @param top neighbours per word
     */
    private void printWords(final List<String> test, final int top)
    {
        for (final String word : test) {
            System.out.println();
            final int query = model.id(word);
            if (query < 0) {
                System.out.printf("%s\tabsent%n", word);
                continue;
            }
            System.out.printf(Locale.ROOT, "%s\t%s %d\tnorm %.3f%n",
                word, hasFreqs ? "freq" : "rank", freqs[query], model.norm(query));
            int rank = 0;
            for (final VecModel.Neighbor neighbor : neighbours(query, top)) {
                final long freq = freqs[neighbor.id()];
                System.out.printf(Locale.ROOT, "%d\t%s\t%.4f\t%s%n", ++rank, neighbor.word(),
                    1d - neighbor.distance(), freq > 0L ? Long.toString(freq) : "");
            }
        }
    }
}
