package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Immutable in-memory word2vec binary model for cosine comparison.
 *
 * <p>
 * Coordinates are stored in one flat row-major {@code float[]} and every row
 * is L2-normalised while loading. Consequently, the dot product returned by
 * {@link #cosine(int, int)} is the cosine similarity between the original
 * vectors. Products are accumulated in {@code double} precision.
 * </p>
 *
 * <p>
 * The complete model is loaded eagerly and is immutable afterward, so one
 * instance may be shared safely by concurrent readers such as servlet request
 * threads. The vocabulary lookup map and coordinate storage are never exposed
 * for mutation.
 * </p>
 *
 * <p>
 * Loading validates the word2vec header, UTF-8 vocabulary entries, row
 * separators, duplicate forms, finite coordinates, and non-zero vector norms.
 * A successfully loaded model therefore guarantees that every stored vector is
 * finite and has unit Euclidean length, up to floating-point rounding. The
 * Euclidean length of each vector as written is kept, see {@link #norm(int)}.
 * </p>
 *
 * <p>
 * A vocabulary file beside the model, {@code <name>.vocab}, is read when it
 * exists: tab-separated, a header naming the columns, the first one
 * {@code word}, then one line per word. Its numeric columns are available by
 * name, see {@link #attribute(int, String)}; {@code Coocs2vec} writes
 * {@code tf}, {@code df} and {@code quality}. They give the factors of
 * {@link #nearestShrunk(int[], int, Shrink)}. The cache of
 * {@link #get(Path)} follows the {@code .bin} only: a vocabulary file
 * rewritten alone is not read again.
 * </p>
 */
public final class VecModel
{

    /**
     * One nearest vector and its mean cosine distance to the query pivots.
     *
     * @param word term form
     * @param id vector id
     * @param distance {@code 1 - mean(cosine)} to the pivots
     */
    public record Neighbor(
        String word,
        int id,
        double distance
    ) {}

    /**
     * Factor by which {@link #nearestShrunk(int[], int, Shrink)} multiplies
     * the cosine of a candidate, a measure of how much of the word its vector
     * holds.
     */
    public enum Shrink
    {
        /**
         * {@code |c| / sqrt(|c|^2 + tau^2)}, |c| the length of the vector as
         * written and tau the median length of the vectors of the words seen
         * once ({@code tf} = 1 in the vocabulary file), see {@link #tau()}.
         */
        TAU,
        /**
         * {@code sqrt(quality)}, the {@code quality} column of the vocabulary
         * file: the share of the row of the word kept by the axes of the
         * model.
         */
        QUALITY
    }

    /** Numeric columns of the vocabulary file by name, indexed by vector id, NaN where absent. */
    private final Map<String, double[]> attributes;

    /** Flat row-major {@code size x dim} coordinates, each row L2-normalised. */
    private final float[] dat;

    /** Number of coordinates per vector. */
    private final int dim;

    /** Dense vector id for each form. */
    private final Map<String, Integer> idByWord;

    /** Euclidean length of each vector as written, before normalisation. */
    private final float[] norms;

    /** Factors of {@link Shrink#QUALITY} by vector id, or null without a quality column. */
    private final float[] shrinkQuality;

    /** Factors of {@link Shrink#TAU} by vector id, or null without tau. */
    private final float[] shrinkTau;

    /** Median length of the vectors of the words seen once, or NaN. */
    private final double tau;

    /** Term forms in vector-id order. */
    private final String[] words;

    /**
     * Successfully loaded models, keyed by normalised absolute path.
     *
     * <p>Failures are deliberately not cached: experimental model files may be
     * created or replaced while the JVM is alive.</p>
     */
    private static final Map<Path, VecModel> MODELS = new HashMap<>();

    /**
     * Modification time and size of the file of each cached model, keyed like
     * {@link #MODELS}: a model file rewritten under the same name is loaded
     * again.
     */
    private static final Map<Path, String> STAMPS = new HashMap<>();
    /**
     * Constructs an already validated model.
     */
    private VecModel(
        final String[] words,
        final float[] dat,
        final float[] norms,
        final int dim,
        final Map<String, Integer> idByWord,
        final Map<String, double[]> attributes
    ) {
        this.words = words;
        this.dat = dat;
        this.norms = norms;
        this.dim = dim;
        this.idByWord = idByWord;
        this.attributes = attributes;

        final double[] tf = attributes.get("tf");
        double median = Double.NaN;
        if (tf != null) {
            final double[] once = IntStream.range(0, words.length)
                .filter(id -> tf[id] == 1d).mapToDouble(id -> norms[id]).sorted().toArray();
            if (once.length > 0) {
                final int mid = once.length / 2;
                median = once.length % 2 == 1 ? once[mid] : (once[mid - 1] + once[mid]) / 2d;
            }
        }
        tau = median;
        if (Double.isNaN(tau)) {
            shrinkTau = null;
        }
        else {
            shrinkTau = new float[words.length];
            for (int id = 0; id < words.length; id++) {
                final double norm = norms[id];
                shrinkTau[id] = (float) (norm / Math.sqrt(norm * norm + tau * tau));
            }
        }
        final double[] quality = attributes.get("quality");
        if (quality == null) {
            shrinkQuality = null;
        }
        else {
            shrinkQuality = new float[words.length];
            for (int id = 0; id < words.length; id++) {
                // a word without a quality is not shrunk
                shrinkQuality[id] = Double.isNaN(quality[id]) ? 1f : (float) Math.sqrt(Math.max(0d, quality[id]));
            }
        }
    }

    /**
     * Returns the value of a column of the vocabulary file for a vector id.
     *
     * @param id vector id
     * @param column column name, as in the header of the vocabulary file
     * @return value, or NaN without that column or without a value for the word
     * @throws IndexOutOfBoundsException if {@code id} is outside
     *         {@code [0, size())}
     */
    public double attribute(final int id, final String column)
    {
        checkId(id);
        final double[] values = attributes.get(column);
        return values == null ? Double.NaN : values[id];
    }

    /**
     * Returns the names of the numeric columns of the vocabulary file.
     *
     * @return column names, empty without a vocabulary file
     */
    public Set<String> attributes()
    {
        return attributes.keySet();
    }

    /**
     * Tells whether {@link #nearestShrunk(int[], int, Shrink)} can use a
     * factor: the vocabulary file has the column it needs.
     *
     * @param shrink factor
     * @return true if available
     */
    public boolean canShrink(final Shrink shrink)
    {
        return (shrink == Shrink.TAU ? shrinkTau : shrinkQuality) != null;
    }

    /**
     * Returns the cosine similarity between two vector ids.
     *
     * <p>
     * Because all stored rows are L2-normalised, this is their dot product.
     * The returned value is clamped to {@code [-1, 1]} to absorb tiny
     * floating-point overshoot from accumulation.
     * </p>
     *
     * @param a first vector id
     * @param b second vector id
     * @return cosine similarity in {@code [-1, 1]}
     * @throws IndexOutOfBoundsException if either id is outside
     *         {@code [0, size())}
     */
    public double cosine(
        final int a,
        final int b
    ) {
        checkId(a);
        checkId(b);

        final int ba = a * dim;
        final int bb = b * dim;
        double sum = 0d;
        for (int axis = 0; axis < dim; axis++) {
            sum += (double) dat[ba + axis] * dat[bb + axis];
        }

        if (sum > 1d) {
            return 1d;
        }
        if (sum < -1d) {
            return -1d;
        }
        return sum;
    }

    /**
     * Returns the number of coordinates per vector.
     *
     * @return vector dimension
     */
    public int dim()
    {
        return dim;
    }

    /**
     * Copies one normalised vector into a caller-owned buffer.
     *
     * @param id vector id
     * @param destination destination buffer
     * @throws IllegalArgumentException if {@code destination.length < dim()}
     * @throws IndexOutOfBoundsException if {@code id} is outside
     *         {@code [0, size())}
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public void get(
        final int id,
        final double[] destination
    ) {
        checkId(id);
        Objects.requireNonNull(destination, "destination");
        if (destination.length < dim) {
            throw new IllegalArgumentException(
                "destination length " + destination.length
                    + " < vector dimension " + dim);
        }

        final int base = id * dim;
        for (int axis = 0; axis < dim; axis++) {
            destination[axis] = dat[base + axis];
        }
    }

    /**
     * Returns a cached model, loading it on the first successful request, and
     * again when its file has changed.
     *
     * <p>The cache keeps, with each model, the modification time and the size
     * of its file; when either differs, the file was rewritten, for example by
     * a new run of {@code Coocs2vec} under the same name, and is loaded again.
     * The word2vec format holds no date, so the file system gives it. Failed
     * loads are not cached: a file read while it is being written fails to
     * load, and the next request tries again. Use {@link #load(Path)} to
     * bypass the cache.</p>
     *
     * @param path word2vec binary model
     * @return cached or newly loaded model
     * @throws IOException if the model cannot be loaded
     */
    public static synchronized VecModel get(final Path path) throws IOException
    {
        final Path key = cacheKey(path);
        final String stamp = Files.getLastModifiedTime(key).toMillis() + ":" + Files.size(key);
        final VecModel cached = MODELS.get(key);
        if (cached != null && stamp.equals(STAMPS.get(key))) {
            return cached;
        }

        final VecModel model = load(key);
        MODELS.put(key, model);
        STAMPS.put(key, stamp);
        return model;
    }

    /**
     * Returns the vector id of a form.
     *
     * @param word term form
     * @return vector id, or {@code -1} if absent
     * @throws NullPointerException if {@code word} is {@code null}
     */
    public int id(
        final String word
    ) {
        Objects.requireNonNull(word, "word");
        final Integer id = idByWord.get(word);
        return id == null ? -1 : id;
    }

    /**
     * Loads a word2vec binary model and L2-normalises every vector.
     *
     * <p>
     * The expected format is the classical word2vec binary layout:
     * {@code "<count> <dim>\\n"}, followed by {@code count} rows containing a
     * UTF-8 token terminated by one ASCII space, {@code dim} little-endian
     * IEEE-754 float32 coordinates, and an LF row separator. CRLF separators
     * are also accepted.
     * </p>
     *
     * @param path word2vec {@code .bin} file
     * @return immutable in-memory model
     * @throws IOException on read failure or malformed content
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static VecModel load(
        final Path path
    ) throws IOException {
        Objects.requireNonNull(path, "path");

        try (
            InputStream raw = Files.newInputStream(path);
            BufferedInputStream buffered = new BufferedInputStream(raw, 1 << 16);
            DataInputStream in = new DataInputStream(buffered)
        ) {
            final String header = readLine(in);
            final String[] fields = header.trim().split("\\s+");
            if (fields.length != 2) {
                throw new IOException(
                    "bad word2vec header in " + path + ": \"" + header + "\"");
            }

            final int count;
            final int dim;
            try {
                count = Integer.parseInt(fields[0]);
                dim = Integer.parseInt(fields[1]);
            }
            catch (NumberFormatException e) {
                throw new IOException(
                    "non-integer word2vec header in " + path + ": \"" + header + "\"",
                    e);
            }

            if (count < 1 || dim < 1) {
                throw new IOException(
                    "bad word2vec header values in " + path
                        + ": count=" + count + ", dim=" + dim);
            }

            final long coordinateCount = (long) count * dim;
            if (coordinateCount > Integer.MAX_VALUE) {
                throw new IOException(
                    "model too large for a single float[] in " + path
                        + ": " + coordinateCount + " coordinates");
            }

            final int rowBytes;
            try {
                rowBytes = Math.multiplyExact(dim, Float.BYTES);
            }
            catch (ArithmeticException e) {
                throw new IOException(
                    "vector row byte size overflows in " + path + ": dim=" + dim,
                    e);
            }

            final float[] dat = new float[(int) coordinateCount];
            final float[] norms = new float[count];
            final String[] words = new String[count];
            final Map<String, Integer> idByWord =
                new HashMap<>(hashCapacity(count));
            final byte[] row = new byte[rowBytes];
            final ByteBuffer buffer =
                ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);

            for (int id = 0; id < count; id++) {
                final String word = readWord(in, id, count, path);
                if (word.isEmpty()) {
                    throw new IOException(
                        "empty word at vector row " + id + " in " + path);
                }
                if (idByWord.containsKey(word)) {
                    throw new IOException(
                        "duplicate word \"" + word + "\" at vector row "
                            + id + " in " + path);
                }

                in.readFully(row);
                buffer.clear();

                final int base = id * dim;
                double norm2 = 0d;
                for (int axis = 0; axis < dim; axis++) {
                    final float value = buffer.getFloat();
                    if (!Float.isFinite(value)) {
                        throw new IOException(
                            "non-finite coordinate for \"" + word
                                + "\" at axis " + axis + " in " + path);
                    }
                    dat[base + axis] = value;
                    norm2 += (double) value * value;
                }

                if (!Double.isFinite(norm2) || norm2 <= 0d) {
                    throw new IOException(
                        "zero or invalid vector norm for \"" + word
                            + "\" in " + path);
                }

                final double norm = Math.sqrt(norm2);
                norms[id] = (float) norm;
                final double inverse = 1d / norm;
                for (int axis = 0; axis < dim; axis++) {
                    dat[base + axis] = (float) (dat[base + axis] * inverse);
                }

                words[id] = word;
                idByWord.put(word, id);
                readRowSeparator(in, word, id, path);
            }

            return new VecModel(words, dat, norms, dim, idByWord, readVocab(path, idByWord, count));
        }
    }

    /**
     * Returns the closest non-pivot vectors to one or more query pivots.
     *
     * <p>Distance is {@code 1 - mean(cosine)} over the supplied pivots. The
     * result is sorted by increasing distance, then by vector id. Query pivots
     * themselves are excluded. Exactly {@code min(limit, size - pivots)}
     * neighbours are returned when available.</p>
     *
     * @param pivotIds query pivot vector ids
     * @param limit maximum number of neighbours to return
     * @return immutable nearest-neighbour list
     * @throws IllegalArgumentException if no pivot is supplied or limit is less
     *         than one
     * @throws IndexOutOfBoundsException if a pivot id is invalid
     * @throws NullPointerException if {@code pivotIds} is {@code null}
     */
    public List<Neighbor> nearest(
        final int[] pivotIds,
        final int limit
    ) {
        Objects.requireNonNull(pivotIds, "pivotIds");
        if (pivotIds.length == 0) {
            throw new IllegalArgumentException("at least one pivot is required");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }

        final boolean[] excluded = new boolean[words.length];
        int excludedCount = 0;
        for (final int pivotId : pivotIds) {
            checkId(pivotId);
            if (!excluded[pivotId]) {
                excluded[pivotId] = true;
                excludedCount++;
            }
        }

        final int capacity = Math.min(limit, words.length - excludedCount);
        if (capacity == 0) {
            return List.of();
        }

        final double[] query = new double[dim];
        for (final int pivotId : pivotIds) {
            final int base = pivotId * dim;
            for (int axis = 0; axis < dim; axis++) {
                query[axis] += dat[base + axis];
            }
        }
        for (int axis = 0; axis < dim; axis++) {
            query[axis] /= pivotIds.length;
        }

        final Comparator<Neighbor> order = Comparator
            .comparingDouble(Neighbor::distance)
            .thenComparingInt(Neighbor::id);
        final PriorityQueue<Neighbor> heap =
            new PriorityQueue<>(capacity, order.reversed());

        for (int candidate = 0; candidate < words.length; candidate++) {
            if (excluded[candidate]) {
                continue;
            }
            final int base = candidate * dim;
            double cosine = 0d;
            for (int axis = 0; axis < dim; axis++) {
                cosine += dat[base + axis] * query[axis];
            }
            final Neighbor neighbor = new Neighbor(
                words[candidate],
                candidate,
                1d - cosine);

            if (heap.size() < capacity) {
                heap.add(neighbor);
            }
            else if (order.compare(neighbor, heap.peek()) < 0) {
                heap.poll();
                heap.add(neighbor);
            }
        }

        final List<Neighbor> result = new ArrayList<>(heap);
        result.sort(order);
        return List.copyOf(result);
    }

    /**
     * Returns the closest non-pivot vectors to one or more query pivots,
     * ranked by the cosine multiplied by a factor of the candidate that
     * measures how much of the word its vector holds.
     *
     * <pre>
     * score(c) = mean(cosine(p, c)) * f(c)
     * TAU      f(c) = |c| / sqrt(|c|^2 + tau^2)
     * QUALITY  f(c) = sqrt(quality(c))
     * </pre>
     *
     * <p>A word seen once has a vector made of its few partners, of which the
     * axes keep only the part it shares with frequent words: its direction is
     * that of a typical context, close to the frequent words of its topic,
     * while its length, and the quality of its row, are small. The cosine
     * ignores both. Giving every vector one more axis of its own, of length
     * tau for {@link Shrink#TAU}, leaves dot products unchanged and lengthens
     * every vector, so that the cosine of a and b becomes
     * {@code cosine(a, b) * f(a) * f(b)}: a symmetric similarity. Within the
     * list of a query, f of the query is common to every candidate, so the
     * ranking is that of the score above: a word seen once falls in the list
     * of a frequent word, while that word keeps its place in the list of the
     * word seen once. With {@link Shrink#QUALITY}, the axis of each word is
     * the part of its row the model leaves out, and f is the cosine of the row
     * with its projection. The vectors themselves are unchanged, so that a
     * placement (PCoA) still reads them as written.</p>
     *
     * <p>Shrinking a similarity by the evidence behind it is the practice of
     * neighbourhood methods in collaborative filtering (Koren 2008), where the
     * shrinkage is a tuned constant; here it is read from the model. The
     * result is sorted by decreasing score, then by vector id. The
     * {@link Neighbor#distance()} stays {@code 1 - mean(cosine)}, as in
     * {@link #nearest(int[], int)}, so it does not always grow down the
     * list.</p>
     *
     * <p>Koren, Y. (2008). "Factorization Meets the Neighborhood: a
     * Multifaceted Collaborative Filtering Model." KDD 2008: 426-434.</p>
     *
     * @param pivotIds query pivot vector ids
     * @param limit maximum number of neighbours to return
     * @param shrink factor of the candidates
     * @return immutable nearest-neighbour list
     * @throws IllegalArgumentException if no pivot is supplied or limit is less
     *         than one
     * @throws IllegalStateException if the vocabulary file lacks the column of
     *         the factor, see {@link #canShrink(Shrink)}
     * @throws IndexOutOfBoundsException if a pivot id is invalid
     * @throws NullPointerException if {@code pivotIds} or {@code shrink} is {@code null}
     */
    public List<Neighbor> nearestShrunk(
        final int[] pivotIds,
        final int limit,
        final Shrink shrink
    ) {
        Objects.requireNonNull(pivotIds, "pivotIds");
        Objects.requireNonNull(shrink, "shrink");
        if (pivotIds.length == 0) {
            throw new IllegalArgumentException("at least one pivot is required");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
        final float[] factor = shrink == Shrink.TAU ? shrinkTau : shrinkQuality;
        if (factor == null) {
            throw new IllegalStateException("vocabulary file without the column of " + shrink);
        }

        final boolean[] excluded = new boolean[words.length];
        int excludedCount = 0;
        for (final int pivotId : pivotIds) {
            checkId(pivotId);
            if (!excluded[pivotId]) {
                excluded[pivotId] = true;
                excludedCount++;
            }
        }

        final int capacity = Math.min(limit, words.length - excludedCount);
        if (capacity == 0) {
            return List.of();
        }

        final double[] query = new double[dim];
        for (final int pivotId : pivotIds) {
            final int base = pivotId * dim;
            for (int axis = 0; axis < dim; axis++) {
                query[axis] += dat[base + axis];
            }
        }
        for (int axis = 0; axis < dim; axis++) {
            query[axis] /= pivotIds.length;
        }

        final double[] cosines = new double[words.length];
        final double[] scores = new double[words.length];
        final Comparator<Integer> order = (a, b) -> {
            final int cmp = Double.compare(scores[b], scores[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        };
        final PriorityQueue<Integer> heap = new PriorityQueue<>(capacity, order.reversed());

        for (int candidate = 0; candidate < words.length; candidate++) {
            if (excluded[candidate]) {
                continue;
            }
            final int base = candidate * dim;
            double cosine = 0d;
            for (int axis = 0; axis < dim; axis++) {
                cosine += dat[base + axis] * query[axis];
            }
            cosines[candidate] = cosine;
            scores[candidate] = cosine * factor[candidate];
            if (heap.size() < capacity) {
                heap.add(candidate);
            }
            else if (order.compare(candidate, heap.peek()) < 0) {
                heap.poll();
                heap.add(candidate);
            }
        }

        final List<Integer> ids = new ArrayList<>(heap);
        ids.sort(order);
        final List<Neighbor> result = new ArrayList<>(ids.size());
        for (final int id : ids) {
            result.add(new Neighbor(words[id], id, 1d - cosines[id]));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the Euclidean length of a vector as written in the file,
     * before normalisation. With axes weighted by a power of the singular
     * values, it measures how much of the decomposition the word carries.
     *
     * @param id vector id
     * @return positive vector length
     * @throws IndexOutOfBoundsException if {@code id} is outside
     *         {@code [0, size())}
     */
    public double norm(final int id)
    {
        checkId(id);
        return norms[id];
    }

    /**
     * Returns the number of vectors.
     *
     * @return vector count
     */
    public int size()
    {
        return words.length;
    }

    /**
     * Returns tau of {@link Shrink#TAU}: the median length, as written, of the
     * vectors of the words seen once in the field ({@code tf} = 1 in the
     * vocabulary file), the length that one occurrence gives a word.
     *
     * @return tau, or NaN without a {@code tf} column or without such a word
     */
    public double tau()
    {
        return tau;
    }

    /**
     * Removes one path from the model cache.
     *
     * @param path model path
     * @return the previously cached model, or {@code null}
     */
    public static synchronized VecModel uncache(final Path path)
    {
        STAMPS.remove(cacheKey(path));
        return MODELS.remove(cacheKey(path));
    }

    /**
     * Returns the form of a vector id.
     *
     * @param id vector id
     * @return term form
     * @throws IndexOutOfBoundsException if {@code id} is outside
     *         {@code [0, size())}
     */
    public String word(
        final int id
    ) {
        checkId(id);
        return words[id];
    }

    /**
     * Writes dense vectors in the classical word2vec binary format.
     *
     * <p>The vector dimension is inferred from the first row. Rows must be
     * rectangular, finite, and non-zero so a file written here is guaranteed
     * to satisfy this class's {@link #load(Path)} invariants. Coordinates are
     * written unchanged; normalisation is performed when a model is loaded.</p>
     *
     * @param path output file
     * @param words words in vector-row order
     * @param vectors dense vectors in row-major order
     * @throws IOException if the file cannot be written
     * @throws IllegalArgumentException if words/vectors are inconsistent
     */
    public static void write(
        final Path path,
        final String[] words,
        final double[][] vectors
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(words, "words");
        Objects.requireNonNull(vectors, "vectors");

        if (words.length == 0 || vectors.length == 0) {
            throw new IllegalArgumentException("empty vector model");
        }
        if (words.length != vectors.length) {
            throw new IllegalArgumentException(
                "word/vector count mismatch: " + words.length + " != " + vectors.length);
        }

        final double[] first = Objects.requireNonNull(vectors[0], "vectors[0]");
        final int dim = first.length;
        if (dim < 1) {
            throw new IllegalArgumentException("vector dimension must be positive");
        }

        final ByteBuffer buffer = ByteBuffer
            .allocate(Math.multiplyExact(dim, Float.BYTES))
            .order(ByteOrder.LITTLE_ENDIAN);

        try (
            OutputStream raw = Files.newOutputStream(path);
            BufferedOutputStream out = new BufferedOutputStream(raw, 1 << 16)
        ) {
            out.write((words.length + " " + dim + "\n")
                .getBytes(StandardCharsets.US_ASCII));

            for (int id = 0; id < words.length; id++) {
                final String word = Objects.requireNonNull(words[id], "words[" + id + "]");
                if (word.isEmpty()) {
                    throw new IllegalArgumentException("empty word at row " + id);
                }
                final double[] vector =
                    Objects.requireNonNull(vectors[id], "vectors[" + id + "]");
                if (vector.length != dim) {
                    throw new IllegalArgumentException(
                        "ragged vector row " + id + ": " + vector.length + " != " + dim);
                }

                double norm2 = 0d;
                buffer.clear();
                for (int axis = 0; axis < dim; axis++) {
                    final double value = vector[axis];
                    if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
                        throw new IllegalArgumentException(
                            "invalid coordinate at row " + id + ", axis " + axis + ": " + value);
                    }
                    norm2 += value * value;
                    buffer.putFloat((float) value);
                }
                if (!Double.isFinite(norm2) || norm2 <= 0d) {
                    throw new IllegalArgumentException("zero or invalid vector at row " + id);
                }

                out.write(word.replaceAll("\\s", "_").getBytes(StandardCharsets.UTF_8));
                out.write(' ');
                out.write(buffer.array(), 0, dim * Float.BYTES);
                out.write('\n');
            }
        }
    }

    /**
     * Canonical cache key without filesystem I/O.
     */
    private static Path cacheKey(final Path path)
    {
        Objects.requireNonNull(path, "path");
        return path.toAbsolutePath().normalize();
    }

    /**
     * Checks that one vector id is valid.
     */
    private void checkId(
        final int id
    ) {
        if (id < 0 || id >= words.length) {
            throw new IndexOutOfBoundsException(
                "vector id " + id + " outside [0, " + words.length + ")");
        }
    }

    /**
     * Decodes one vocabulary token as strict UTF-8.
     */
    private static String decodeUtf8(
        final byte[] bytes,
        final int length
    ) throws IOException {
        try {
            return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString();
        }
        catch (CharacterCodingException e) {
            throw new IOException("invalid UTF-8 in word2vec vocabulary", e);
        }
    }

    /**
     * Returns a HashMap initial capacity suitable for the expected entry count.
     */
    private static int hashCapacity(
        final int expected
    ) {
        if (expected < 3) {
            return expected + 1;
        }
        final long capacity = (long) Math.ceil(expected / 0.75d);
        return (int) Math.min(capacity, 1L << 30);
    }

    /**
     * Reads one ASCII header line terminated by LF.
     */
    private static String readLine(
        final InputStream in
    ) throws IOException {
        final StringBuilder line = new StringBuilder(32);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                if (!line.isEmpty()
                        && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) c);
        }

        if (line.isEmpty()) {
            throw new EOFException("empty word2vec file");
        }
        throw new EOFException(
            "word2vec header is not terminated by LF");
    }

    /**
     * Reads and validates one binary-row separator.
     */
    private static void readRowSeparator(
        final InputStream in,
        final String word,
        final int id,
        final Path path
    ) throws IOException {
        final int separator = in.read();
        if (separator == '\n') {
            return;
        }
        if (separator == '\r') {
            final int lf = in.read();
            if (lf == '\n') {
                return;
            }
            throw new IOException(
                "CR not followed by LF after \"" + word + "\" at row "
                    + id + " in " + path);
        }
        if (separator < 0) {
            throw new EOFException(
                "missing row separator after \"" + word + "\" at row "
                    + id + " in " + path);
        }
        throw new IOException(
            "bad row separator 0x"
                + Integer.toHexString(separator)
                + " after \"" + word + "\" at row "
                + id + " in " + path);
    }

    /**
     * Reads the vocabulary file beside a model, {@code <name>.vocab}, if it
     * exists: a tab-separated header whose first column is {@code word}, then
     * one line per word. Every other column is read as numbers, an empty cell
     * as NaN. Words absent from the model are skipped; words of the model
     * absent from the file get NaN.
     *
     * @param path model file
     * @param idByWord vector id of each word of the model
     * @param count number of vectors
     * @return columns by name, unmodifiable, empty without a vocabulary file
     * @throws IOException if the file cannot be read or holds a non-numeric value
     */
    private static Map<String, double[]> readVocab(
        final Path path,
        final Map<String, Integer> idByWord,
        final int count
    ) throws IOException {
        final String fileName = path.getFileName().toString();
        final int dot = fileName.lastIndexOf('.');
        final Path vocab = path.resolveSibling((dot < 0 ? fileName : fileName.substring(0, dot)) + ".vocab");
        if (!Files.isRegularFile(vocab)) {
            return Map.of();
        }
        final List<String> lines = Files.readAllLines(vocab, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return Map.of();
        }
        final String[] header = lines.get(0).split("\t");
        if (!"word".equals(header[0])) {
            throw new IOException("vocabulary file without a first column word: " + vocab);
        }
        final double[][] columns = new double[header.length][];
        for (int col = 1; col < header.length; col++) {
            columns[col] = new double[count];
            Arrays.fill(columns[col], Double.NaN);
        }
        for (int line = 1; line < lines.size(); line++) {
            final String[] cells = lines.get(line).split("\t", -1);
            final Integer id = idByWord.get(cells[0]);
            if (id == null) {
                continue;
            }
            for (int col = 1; col < header.length && col < cells.length; col++) {
                if (cells[col].isEmpty()) {
                    continue;
                }
                try {
                    columns[col][id] = Double.parseDouble(cells[col]);
                }
                catch (NumberFormatException e) {
                    throw new IOException("non-numeric value \"" + cells[col] + "\" in column "
                        + header[col] + " at line " + (line + 1) + " of " + vocab, e);
                }
            }
        }
        final Map<String, double[]> byName = new LinkedHashMap<>();
        for (int col = 1; col < header.length; col++) {
            byName.put(header[col], columns[col]);
        }
        return Collections.unmodifiableMap(byName);
    }

    /**
     * Reads one UTF-8 word2vec token terminated by one ASCII space.
     *
     * @param in input stream positioned at the start of a token
     * @param id zero-based vector row being read
     * @param count number of vectors declared in the header
     * @param path model file path for diagnostic messages
     * @return decoded token
     * @throws IOException if the token is malformed or cannot be read
     */
    private static String readWord(
        final InputStream in,
        final int id,
        final int count,
        final Path path
    ) throws IOException {
        byte[] bytes = new byte[32];
        int length = 0;

        while (true) {
            final int c = in.read();
            if (c < 0) {
                throw new EOFException(
                    "EOF reading word2vec token at vector " + id
                        + " (row " + (id + 1) + "/" + count + ") in " + path
                        + ": " + (length == 0
                            ? "no token bytes available (missing entry or incorrect header count)"
                            : length + " token bytes read without a terminating space"));
            }
            if (c == ' ') {
                return decodeUtf8(bytes, length);
            }
            if (c == '\n' || c == '\r') {
                throw new IOException(
                    "unexpected " + (c == '\n' ? "LF" : "CR")
                        + " inside word2vec token at vector " + id
                        + " (row " + (id + 1) + "/" + count + ") in " + path
                        + " after " + length + " token bytes");
            }

            if (length == bytes.length) {
                final byte[] grown = new byte[bytes.length * 2];
                System.arraycopy(bytes, 0, grown, 0, length);
                bytes = grown;
            }
            bytes[length++] = (byte) c;
        }
    }
}
