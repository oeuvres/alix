package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable in-memory word2vec model, L2-normalised at load so that a dot
 * product is a cosine.
 */
public final class VecModel
{
    /** Flat row-major {@code count x dim} coordinates, each row L2-normalised. */
    private final float[] dat;

    /** Number of coordinates per vector. */
    private final int dim;

    /** Dense vector id for each form. */
    private final Map<String, Integer> idByWord;

    /** Term forms in vector-id order. */
    private final String[] words;

    private VecModel(final String[] words, final float[] dat, final int dim,
            final Map<String, Integer> idByWord)
    {
        this.words = words;
        this.dat = dat;
        this.dim = dim;
        this.idByWord = idByWord;
    }

    /**
     * Loads a word2vec binary model and L2-normalises every row.
     *
     * @param path word2vec {@code .bin} file
     * @return frozen model
     * @throws IOException on read failure or malformed content
     */
    public static VecModel load(final Path path) throws IOException
    {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 16)) {
            final String[] header = readLine(in).trim().split("\\s+");
            if (header.length != 2) {
                throw new IOException("bad word2vec header in " + path);
            }
            final int count = Integer.parseInt(header[0]);
            final int dim = Integer.parseInt(header[1]);
            if (count < 1 || dim < 1) {
                throw new IOException("bad word2vec header values in " + path);
            }
            if ((long) count * dim > Integer.MAX_VALUE) {
                throw new IOException("model too large for a single float[]: " + path);
            }

            final float[] dat = new float[count * dim];
            final String[] words = new String[count];
            final Map<String, Integer> idByWord = new HashMap<>(count * 2);
            final byte[] row = new byte[dim * Float.BYTES];
            final ByteBuffer buf = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
            final DataInputStream data = new DataInputStream(in);

            for (int id = 0; id < count; id++) {
                final String word = readWord(in);
                data.readFully(row);
                buf.clear();
                final int base = id * dim;
                double norm = 0d;
                for (int axis = 0; axis < dim; axis++) {
                    final float v = buf.getFloat();
                    dat[base + axis] = v;
                    norm += (double) v * v;
                }
                norm = Math.sqrt(norm);
                if (norm > 0d) {
                    final float inverse = (float) (1d / norm);
                    for (int axis = 0; axis < dim; axis++) {
                        dat[base + axis] *= inverse;
                    }
                }
                words[id] = word;
                idByWord.putIfAbsent(word, id);
                final int c = in.read();          // trailing newline, tolerated if absent
                if (c != '\n' && c != -1 && c != '\r') {
                    throw new IOException("missing row separator after \"" + word + "\"");
                }
            }
            return new VecModel(words, dat, dim, idByWord);
        }
    }

    /**
     * Returns the cosine between two vector ids.
     *
     * @param a first vector id
     * @param b second vector id
     * @return cosine in {@code [-1, 1]}
     */
    public double cosine(final int a, final int b)
    {
        final int ba = a * dim;
        final int bb = b * dim;
        double sum = 0d;
        for (int axis = 0; axis < dim; axis++) {
            sum += (double) dat[ba + axis] * dat[bb + axis];
        }
        return sum;
    }

    /**
     * Returns the number of coordinates per vector.
     *
     * @return dimension
     */
    public int dim()
    {
        return dim;
    }

    /**
     * Returns the vector id of a form.
     *
     * @param word term form
     * @return vector id, or {@code -1} if absent
     */
    public int id(final String word)
    {
        Objects.requireNonNull(word, "word");
        final Integer id = idByWord.get(word);
        return (id == null) ? -1 : id;
    }

    /**
     * Copies one normalised vector into a caller-owned buffer.
     *
     * @param id vector id
     * @param destination buffer of length at least {@link #dim()}
     */
    public void get(final int id, final double[] destination)
    {
        final int base = id * dim;
        for (int axis = 0; axis < dim; axis++) {
            destination[axis] = dat[base + axis];
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
     */
    public String word(final int id)
    {
        return words[id];
    }

    private static String readLine(final InputStream in) throws IOException
    {
        final StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            sb.append((char) c);
        }
        if (c == -1 && sb.isEmpty()) {
            throw new EOFException("empty word2vec file");
        }
        return sb.toString();
    }

    private static String readWord(final InputStream in) throws IOException
    {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(32);
        int c;
        while ((c = in.read()) != -1 && c != ' ') {
            if (c == '\n') {
                continue;
            }
            bytes.write(c);
        }
        if (c == -1) {
            throw new EOFException("truncated word2vec entry");
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
