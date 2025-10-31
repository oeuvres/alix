package com.github.oeuvres.alix.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.Random;

import com.github.oeuvres.alix.fr.French;

/**
 * WordGen: runtime sampler for a character 3‑gram (k=3) model.
 *
 * <p><b>Why you saw odd outputs</b>:
 * <ul>
 *   <li><b>Phantom final "è"</b>: source can contain decomposed sequences like <code>e</code> + U+0300 (COMBINING GRAVE).
 *       Rendering makes it look like U+00E8. This class normalizes to NFC when reading, so counts
 *       for decomposed vs precomposed forms are merged.</li>
 *   <li><b>Very long strings (no early EOS)</b>: ML trigrams can emit EOS only from contexts (a,b) that ended words in
 *       training. In a sparse lexicon (each entry count = 1), many internal contexts never precede EOS, so the chain
 *       can wander until it hits an end-capable context or the hard {@code maxLen}.</li>
 * </ul></p>
 *
 * <p>Design notes</p>
 * <ul>
 *   <li>Alphabet Σ includes BOS='^' and EOS='$'.</li>
 *   <li>Training reads <code>word{sep}count</code> lines, NFC-normalizes tokens, builds dense ML counts c3/c2/c1,
 *       then compacts nonzero rows to Walker–alias tables.</li>
 *   <li>Generation starts at (^,^). If trigram row (a,b) exists, we sample it; otherwise we back off to b's bigram row,
 *       or to the continuation unigram.</li>
 *   <li><b>final usage:</b> fields are final where immutability is intended; local references are final when they are not
 *       reassigned. This documents intent and helps with safe publication of immutable state under the JMM.</li>
 * </ul>
 */
public abstract class WordGen
{
    /** Begin of String char */
    public static final char BOS = '^';
    /** Begin of String id */
    public static final short BOS_ID = 0;
    /** End of String char */
    public static final char EOS = '$';
    /** End of String id */
    public static final short EOS_ID = 1;

    /** Alphabet size (includes BOS and EOS). */
    public final int S;
    /** id -> char (length S). */
    public final char[] idToChar;
    /** char (0..65535) -> id (or -1). Dense LUT for speed. */
    public final short[] charToId = new short[65536];

    /**
     * Primary trigram samplers per state (a,b) indexed by row = a*S + b. Row is
     * null if the state has no observed continuations (i.e., unseen ab). Each
     * AliasRow holds only the observed next symbols c with their ML mass.
     */
    public final AliasRow[] trigrams; // length S*S, may contain null rows

    /** Bigram samplers per previous symbol b (may be null). */
    public final AliasRow[] bigrams; // length S, may contain null rows

    /** Unigram continuation sampler P_cont(.), used when bigrams back off has no row. */
    public final AliasRow unigrams;

    /** RNG used for alias sampling. */
    public final Random rng;

    // ===================== Construction =====================

    /**
     * Build the model from a CSV/TSV-like reader (word{sep}count).
     *
     * <p>Parsing policy:
     * <ul>
     *   <li>Skip empty or '#' comment lines.</li>
     *   <li>Tokens are trimmed with {@code String.strip()} and normalized to NFC via {@link Normalizer}.</li>
     *   <li>No case folding. Adjust upstream if desired.</li>
     *   <li>Negative / non-numeric counts are ignored.</li>
     * </ul>
     * </p>
     */
    protected WordGen(final Reader in, final char sep) throws IOException
    {
        this.rng = new Random(0xC0FFEE);

        // 1) Load rows; accumulate alphabet
        final ArrayList<WC> rows = new ArrayList<>(1 << 20);
        final BitSet alphaBits = new BitSet(65536);
        alphaBits.set(BOS);
        alphaBits.set(EOS);

        try (final BufferedReader br = toBuffered(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                final int tab = line.indexOf(sep);
                if (tab <= 0 || tab == line.length() - 1) continue;
                final String raw = line.substring(0, tab).strip();
                if (raw.isEmpty()) continue;
                final String word = Normalizer.normalize(raw, Normalizer.Form.NFC);
                if (!word.equals(raw)) {
                    System.out.println("--  source <> norm: " + word + " " + raw);
                }
                final String num = line.substring(tab + 1).trim();

                final long cnt;
                try {
                    cnt = Long.parseLong(num);
                } catch (final NumberFormatException e) {
                    continue;
                }
                if (cnt <= 0) continue;

                rows.add(new WC(word, cnt));
                for (int i = 0; i < word.length(); i++) alphaBits.set(word.charAt(i));
            }
        }

        // 2) Freeze alphabet (BOS_ID=0, EOS_ID=1, then others by codepoint)
        final int distinct = alphaBits.cardinality();
        final ArrayList<Character> alph = new ArrayList<>(distinct);
        alph.add(BOS); // id 0
        alph.add(EOS); // id 1
        for (int cp = alphaBits.nextSetBit(0); cp >= 0; cp = alphaBits.nextSetBit(cp + 1)) {
            final char c = (char) cp;
            if (c == BOS || c == EOS) continue;
            alph.add(c);
        }
        this.S = alph.size();
        this.idToChar = new char[S];
        Arrays.fill(charToId, (short) -1);
        for (short id = 0; id < S; id++) {
            final char ch = alph.get(id);
            idToChar[id] = ch;
            charToId[ch] = id;
        }
        if (charToId[BOS] != BOS_ID || charToId[EOS] != EOS_ID) {
            throw new IllegalStateException("BOS/EOS ids mismatch: expected " + BOS_ID + "/" + EOS_ID + " but got "
                    + charToId[BOS] + "/" + charToId[EOS]);
        }

        // 3) Dense ML counts: tri[S*S*S], bi[S*S], uni[S]
        final long[] tri = new long[S * S * S]; // c3[a,b,c]
        final long[] bi = new long[S * S];      // c2[b,c]
        final long[] uni = new long[S];         // c1[c]
        final long[] tot3 = new long[S * S];    // Σ_c c3[a,b,c]
        final long[] tot2 = new long[S];        // Σ_c c2[b,c]

        for (final WC r : rows) {
            final String w = r.word;
            final long cnt = r.count;

            // seq = ^^ + w + $
            final int L = w.length();
            final char[] seq = new char[L + 3];
            seq[0] = BOS;
            seq[1] = BOS;
            w.getChars(0, L, seq, 2);
            seq[L + 2] = EOS;

            for (int i = 0; i + 2 < seq.length; i++) {
                final short a = id(seq[i]);
                final short b = id(seq[i + 1]);
                final short c = id(seq[i + 2]);

                final int idx3 = ((a * S) + b) * S + c;
                tri[idx3] += cnt;
                tot3[a * S + b] += cnt;

                final int idx2 = b * S + c;
                bi[idx2] += cnt;
                tot2[b] += cnt;

                uni[c] += cnt;
            }
        }

        // 4) Build alias rows
        this.trigrams = new AliasRow[S * S];
        for (int ab = 0; ab < S * S; ab++) {
            if (tot3[ab] == 0) {
                trigrams[ab] = null;
                continue;
            }
            final int base = ab * S;

            int nz = 0;
            for (int c = 0; c < S; c++) if (tri[base + c] != 0) nz++;

            final char[] sym = new char[nz];
            final long[] cnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                final long v = tri[base + c];
                if (v != 0) {
                    sym[k] = idToChar[c];
                    cnt[k++] = v;
                }
            }
            trigrams[ab] = AliasRow.fromCounts(sym, cnt);
        }

        this.bigrams = new AliasRow[S];
        for (int b = 0; b < S; b++) {
            if (tot2[b] == 0) {
                bigrams[b] = null;
                continue;
            }
            final int base = b * S;
            int nz = 0;
            for (int c = 0; c < S; c++) if (bi[base + c] != 0) nz++;
            final char[] sym = new char[nz];
            final long[] cnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                final long v = bi[base + c];
                if (v != 0) {
                    sym[k] = idToChar[c];
                    cnt[k++] = v;
                }
            }
            bigrams[b] = AliasRow.fromCounts(sym, cnt);
        }

        int nz = 0;
        for (int c = 0; c < S; c++) if (uni[c] != 0) nz++;
        if (nz == 0) { // defensive: EOS-only dist
            this.unigrams = new AliasRow(new char[] { EOS }, new float[] { 1f }, new short[] { 0 });
        } else {
            final char[] usym = new char[nz];
            final long[] ucnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                final long v = uni[c];
                if (v != 0) {
                    usym[k] = idToChar[c];
                    ucnt[k++] = v;
                }
            }
            this.unigrams = AliasRow.fromCounts(usym, ucnt);
        }
    }

    /** char -> id via dense LUT; throws if unseen (shouldn’t happen after training). */
    protected final short id(final char ch)
    {
        final short id = charToId[ch];
        if (id < 0) throw new IllegalArgumentException("Unseen char: U+" + Integer.toHexString(ch));
        return id;
    }

    protected static BufferedReader toBuffered(final Reader r)
    {
        return (r instanceof BufferedReader br) ? br : new BufferedReader(r, 1 << 16);
    }

    // ----- Simple (word, count) holder -----
    private static final class WC
    {
        final String word;
        final long count;
        WC(final String w, final long c) { this.word = w; this.count = c; }
    }

    public static final class AliasRow
    {
        public final char[] sym;
        public final float[] prob;
        public final short[] alias;
        public final int size;

        public AliasRow(final char[] sym, final float[] prob, final short[] alias)
        {
            this.sym = sym;
            this.prob = prob;
            this.alias = alias;
            this.size = sym.length;
        }

        public static AliasRow fromCounts(final char[] sym, final long[] cnt)
        {
            final int n = sym.length;
            if (n == 0) throw new IllegalArgumentException("empty row");
            long tot = 0;
            for (final long v : cnt) tot += v;
            final double[] g = new double[n];
            final Deque<Integer> small = new ArrayDeque<>();
            final Deque<Integer> large = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                final double p = (tot == 0) ? 0.0 : (cnt[i] / (double) tot);
                g[i] = p * n;
                if (g[i] < 1.0) small.add(i); else large.add(i);
            }

            final float[] prob = new float[n];
            final short[] alias = new short[n];
            while (!small.isEmpty() && !large.isEmpty()) {
                final int s = small.removeFirst();
                final int l = large.removeFirst();
                prob[s] = (float) g[s];
                alias[s] = (short) l;
                g[l] = (g[l] + g[s]) - 1.0;
                if (g[l] < 1.0) small.add(l); else large.add(l);
            }
            for (final int i : small) { prob[i] = 1.0f; alias[i] = (short) i; }
            for (final int i : large) { prob[i] = 1.0f; alias[i] = (short) i; }
            return new AliasRow(sym, prob, alias);
        }
    }

    /**
     * Generate one word as a freshly sized char[]. Starts from ('^','^'), samples
     * until '$'. Ensures at least one character (minLen=1). Uses a conservative
     * maxLen to avoid pathological loops in degenerate models.
     *
     * @return generated word characters (without BOS/EOS)
     */
    public char[] generate() { return generate(1, 64); }

    /** Convenience: generate and return as a Java String. */
    public final String generateString() { return new String(generate()); }

    /**
     * Generate one word with explicit length clamp.
     *
     * @param minLen minimum number of emitted characters (>=0)
     * @param maxLen hard cap on emitted characters (>0)
     * @return generated word characters (without BOS/EOS)
     */
    public char[] generate(int minLen, int maxLen)
    {
        if (minLen < 0) minLen = 0;
        if (maxLen < 1) maxLen = 1;
        if (minLen > maxLen) minLen = maxLen;

        short a = BOS_ID, b = BOS_ID;
        char[] out = new char[16];
        int len = 0;

        while (true) {
            final int ab = a * S + b;
            final char c;
            if (trigrams[ab] != null) c = sampleNext(a, b);
            else c = sample(bigrams[b] != null ? bigrams[b] : unigrams);

            if (id(c) == EOS_ID) {
                if (len >= minLen) break;
                // Enforce minLen: try a back-off draw from the same context b
                final char forced = sample(bigrams[b] != null ? bigrams[b] : unigrams);
                if (id(forced) == EOS_ID) continue; // try again next loop
                if (len == out.length) out = Arrays.copyOf(out, out.length << 1);
                out[len++] = forced;
                a = b;
                b = id(forced);
                continue;
            }

            if (len == out.length) out = Arrays.copyOf(out, out.length << 1);
            out[len++] = c;
            if (len >= maxLen) break;
            a = b;
            b = id(c);
        }
        return Arrays.copyOf(out, len);
    }

    // ----- Hook: decide next char for context (a,b) where trigrams[ab] != null
    protected abstract char sampleNext(final short aId, final short bId);

    /**
     * Walker-alias sampling step.
     *
     * @param row alias table to sample
     * @return one char sampled according to row’s multinomial
     *
     * <p>Implementation notes: O(1) expected time. Draw u in [0,1), i=floor(u*n), r=u-i.
     * Pick bucket i if r < prob[i], else alias[i].</p>
     */
    protected final char sample(final AliasRow row)
    {
        final int n = row.size;
        final double u = rng.nextDouble() * n;
        int i = (int) u;
        final double frac = u - i;
        if (frac >= row.prob[i]) i = row.alias[i];
        return row.sym[i];
    }

    /** Maximum-likelihood (no smoothing): next ~ trigrams[a*S + b] directly. */
    public static final class ML extends WordGen
    {
        public ML(final Reader in, final char sep) throws IOException { super(in, sep); }

        @Override
        protected char sampleNext(final short aId, final short bId)
        {
            AliasRow r = trigrams[aId * S + bId];
            if (r == null) r = (bigrams[bId] != null) ? bigrams[bId] : unigrams;
            return sample(r);
        }
    }

    /**
     * Interpolated Kneser–Ney variant (stub for future work).
     */
    public static final class KN extends WordGen
    {
        /** Back-off weight λ₂(ab) per trigram state (a,b), in [0,1]. */
        public final float[] lambda2; // length S*S

        protected KN(final Reader in, final char sep) throws IOException
        {
            super(in, sep);
            lambda2 = null; // TODO
        }

        @Override
        protected char sampleNext(final short aId, final short bId)
        {
            AliasRow r = trigrams[aId * S + bId];
            if (r != null) return sample(r);
            r = (bigrams[bId] != null) ? bigrams[bId] : unigrams;
            return sample(r);
        }
    }

    public static void main(final String[] args) throws Exception
    {
        final InputStream is = French.class.getResourceAsStream("fr-google1gram.csv");
        final Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
        final WordGen wordML = new WordGen.ML(reader, ',');
        for (int i = 0; i < 100; i++) System.out.println(wordML.generate());
    }
}
