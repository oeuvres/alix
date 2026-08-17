package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
 * finite and has unit Euclidean length, up to floating-point rounding.
 * </p>
 */
public final class VecModel
{
    /** Flat row-major {@code size x dim} coordinates, each row L2-normalised. */
    private final float[] dat;

    /** Number of coordinates per vector. */
    private final int dim;

    /** Dense vector id for each form. */
    private final Map<String, Integer> idByWord;

    /** Term forms in vector-id order. */
    private final String[] words;

    /**
     * Constructs an already validated model.
     */
    private VecModel(
        final String[] words,
        final float[] dat,
        final int dim,
        final Map<String, Integer> idByWord
    ) {
        this.words = words;
        this.dat = dat;
        this.dim = dim;
        this.idByWord = idByWord;
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
            final String[] words = new String[count];
            final Map<String, Integer> idByWord =
                new HashMap<>(hashCapacity(count));
            final byte[] row = new byte[rowBytes];
            final ByteBuffer buffer =
                ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);

            for (int id = 0; id < count; id++) {
                final String word = readWord(in);
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

                final double inverse = 1d / Math.sqrt(norm2);
                for (int axis = 0; axis < dim; axis++) {
                    dat[base + axis] = (float) (dat[base + axis] * inverse);
                }

                words[id] = word;
                idByWord.put(word, id);
                readRowSeparator(in, word, id, path);
            }

            return new VecModel(words, dat, dim, idByWord);
        }
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
     * Reads one UTF-8 word2vec token terminated by one ASCII space.
     */
    private static String readWord(
        final InputStream in
    ) throws IOException {
        byte[] bytes = new byte[32];
        int length = 0;

        while (true) {
            final int c = in.read();
            if (c < 0) {
                throw new EOFException(
                    "truncated word2vec entry while reading token");
            }
            if (c == ' ') {
                return decodeUtf8(bytes, length);
            }
            if (c == '\n' || c == '\r') {
                throw new IOException(
                    "unexpected line break inside word2vec token");
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
