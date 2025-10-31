package com.github.oeuvres.alix.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.Random;
import java.text.Normalizer;

import com.github.oeuvres.alix.fr.French;

/**
 * WordGen: runtime sampler for a character 3‑gram (k=3) model.
 *
 * <p><b>Notes on surprises you may see</b>:
 * <ul>
 *   <li><b>Words ending with unexpected characters (e.g., 'è'):</b> your
 *       training list may contain decomposed Unicode sequences like <code>e</code> +
 *       U+0300 COMBINING GRAVE. When rendered, they look like a precomposed
 *       'è'. This class now normalizes input to NFC, which merges such sequences
 *       into a single code point, making statistics consistent.</li>
 *   <li><b>Very long outputs with no early termination:</b> with the maximum‑likelihood
 *       trigram policy (used by {@link ML}), the model can only emit EOS ('$')
 *       from a context (a,b) that actually ended a word in training. If the chain
 *       keeps visiting purely internal contexts, it will only stop at the hard
 *       {@code maxLen} cap. Use the {@link KN} variant (interpolated back‑off)
 *       when available to make endings more likely across contexts.</li>
 * </ul></p>
 *
 * <p>Model and data representation:</p>
 * <ul>
 *   <li>Alphabet Σ includes a begin-of-string (BOS='^') and end-of-string (EOS='$') marker.</li>
 *   <li>Training reads <code>word{sep}count</code> lines, builds dense count tables for
 *       trigrams c3[a,b,c], bigrams c2[b,c], unigrams c1[c], then converts non‑zero rows
 *       to Walker‑alias samplers.</li>
 *   <li>Generation starts from (^,^), samples next symbols from trigrams if the state was seen;
 *       otherwise backs off to bigrams of <em>b</em>, and if even that is unseen, to the continuation unigram.</li>
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
     * AliasRow holds only the observed next symbols c with their KN-adjusted mass
     * (after subtracting discount and normalizing by the trigram denominator).
     */
    public final AliasRow[] trigrams; // length S*S, may contain null rows
    /**
     * Bigram Kneser–Ney samplers per previous symbol b. Row is null if b has no
     * observed continuations.
     */
    public final AliasRow[] bigrams; // length S, may contain null rows

    /**
     * Unigram continuation sampler P_cont(.), used when bigrams back-off has no
     * row.
     */
    public final AliasRow unigrams;

    /** RNG used for alias sampling and the trigram/back-off coin flip. */
    public final Random rng;

    // ===================== Construction =====================

    /**
     * Build the model from a CSV/TSV-like reader (word{sep}count).
     *
     * <p>Parsing policy:
     * <ul>
     *   <li>Skip empty or '#' comment lines.</li>
     *   <li>Tokens are <b>trimmed</b> with {@code String.strip()} and normalized to NFC via
     *       {@link Normalizer} to canonicalize any decomposed diacritics.</li>
     *   <li>Keep characters as-is otherwise (no case folding; add if you need it).</li>
     *   <li>Negative / non-numeric counts are ignored.</li>
     * </ul>
     * </p>
     */
    protected WordGen(final Reader in, final char sep) throws IOException
    {
        this.rng = new Random(0xC0FFEE);

        // 1) Read TSV into memory (simple and robust), accumulate alphabet
        final ArrayList<WC> rows = new ArrayList<>(1 << 20);
        final BitSet alphaBits = new BitSet(65536);
        alphaBits.set(BOS);
        alphaBits.set(EOS);

        try (BufferedReader br = toBuffered(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf(sep);
                if (tab <= 0 || tab == line.length() - 1) continue;
                String word = line.substring(0, tab);
                // Normalize and sanitize the token: trim and NFC to merge any combining marks
                word = word.strip();
                if (word.isEmpty()) continue;
                word = Normalizer.normalize(word, Normalizer.Form.NFC);
                String num = line.substring(tab + 1).trim();

                long cnt;
                try {
                    cnt = Long.parseLong(num);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (cnt <= 0) continue;

                rows.add(new WC(word, cnt));
                for (int i = 0; i < word.length(); i++)
                    alphaBits.set(word.charAt(i));
            }
        }

        // 2) Freeze alphabet (BOS_ID=0, EOS_ID=1, then others by codepoint for
        // determinism)
        int distinct = alphaBits.cardinality();
        ArrayList<Character> alph = new ArrayList<>(distinct);
        alph.add(BOS); // id 0
        alph.add(EOS); // id 1
        for (int cp = alphaBits.nextSetBit(0); cp >= 0; cp = alphaBits.nextSetBit(cp + 1)) {
            char c = (char) cp;
            if (c == BOS || c == EOS) continue;
            alph.add(c);
        }
        this.S = alph.size();
        this.idToChar = new char[S];
        Arrays.fill(charToId, (short) -1);
        for (short id = 0; id < S; id++) {
            char ch = alph.get(id);
            idToChar[id] = ch;
            charToId[ch] = id;
        }
        if (charToId[BOS] != BOS_ID || charToId[EOS] != EOS_ID) {
            throw new IllegalStateException("BOS/EOS ids mismatch: expected " + BOS_ID + "/" + EOS_ID + " but got "
                    + charToId[BOS] + "/" + charToId[EOS]);
        }

        // 3) Dense ML counts: tri[S*S*S], bi[S*S], uni[S]
        final long[] tri = new long[S * S * S]; // c3[a,b,c]
        final long[] bi = new long[S * S]; // c2[b,c]
        final long[] uni = new long[S]; // c1[c]
        final long[] tot3 = new long[S * S]; // Σ_c c3[a,b,c]
        final long[] tot2 = new long[S]; // Σ_c c2[b,c]

        for (WC r : rows) {
            final String w = r.word;
            final long cnt = r.count;

            // seq = ^^ + w + $
            int L = w.length();
            char[] seq = new char[L + 3];
            seq[0] = BOS;
            seq[1] = BOS;
            w.getChars(0, L, seq, 2);
            seq[L + 2] = EOS;

            for (int i = 0; i + 2 < seq.length; i++) {
                short a = id(seq[i]), b = id(seq[i + 1]), c = id(seq[i + 2]);

                final int idx3 = ((a * S) + b) * S + c;
                tri[idx3] += cnt;
                tot3[a * S + b] += cnt;

                int idx2 = b * S + c;
                bi[idx2] += cnt;
                tot2[b] += cnt;

                uni[c] += cnt;
            }
        }

        // ---------- 4) Build alias rows from dense tables ----------
        // Trigram rows per (a,b)
        this.trigrams = new AliasRow[S * S];
        for (int ab = 0; ab < S * S; ab++) {
            if (tot3[ab] == 0) {
                trigrams[ab] = null;
                continue;
            }
            int base = ab * S;

            // count nonzeros
            int nz = 0;
            for (int c = 0; c < S; c++)
                if (tri[base + c] != 0) nz++;

            char[] sym = new char[nz];
            long[] cnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                long v = tri[base + c];
                if (v != 0) {
                    sym[k] = idToChar[c];
                    cnt[k++] = v;
                }
            }
            trigrams[ab] = AliasRow.fromCounts(sym, cnt);
        }

        // Bigram rows per b
        this.bigrams = new AliasRow[S];
        for (int b = 0; b < S; b++) {
            if (tot2[b] == 0) {
                bigrams[b] = null;
                continue;
            }
            int base = b * S;
            int nz = 0;
            for (int c = 0; c < S; c++)
                if (bi[base + c] != 0) nz++;
            char[] sym = new char[nz];
            long[] cnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                long v = bi[base + c];
                if (v != 0) {
                    sym[k] = idToChar[c];
                    cnt[k++] = v;
                }
            }
            bigrams[b] = AliasRow.fromCounts(sym, cnt);
        }

        // Unigram row
        int nz = 0;
        for (int c = 0; c < S; c++)
            if (uni[c] != 0) nz++;
        if (nz == 0) { // defensive: create EOS-only dist
            this.unigrams = new AliasRow(new char[] { EOS }, new float[] { 1f }, new short[] { 0 });
        } else {
            char[] usym = new char[nz];
            long[] ucnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                long v = uni[c];
                if (v != 0) {
                    usym[k] = idToChar[c];
                    ucnt[k++] = v;
                }
            }
            this.unigrams = AliasRow.fromCounts(usym, ucnt);
        }
    }

    /**
     * char -> id via dense LUT; throws if unseen (shouldn’t happen after training).
     */
    protected short id(char ch)
    {
        short id = charToId[ch];
        if (id < 0) throw new IllegalArgumentException("Unseen char: U+" + Integer.toHexString(ch));
        return id;
    }

    protected static BufferedReader toBuffered(Reader r)
    {
        return (r instanceof BufferedReader br) ? br : new BufferedReader(r, 1 << 16);
    }

    // ----- Simple (word, count) holder -----
    private static final class WC
    {
        final String word;
        final long count;

        WC(String w, long c)
        {
            this.word = w;
            this.count = c;
        }
    }

    public static final class AliasRow
    {
        public final char[] sym;
        public final float[] prob;
        public final short[] alias;
        public final int size;

        public AliasRow(char[] sym, float[] prob, short[] alias)
        {
            this.sym = sym;
            this.prob = prob;
            this.alias = alias;
            this.size = sym.length;
        }

        public static AliasRow fromCounts(char[] sym, long[] cnt)
        {
            int n = sym.length;
            if (n == 0) throw new IllegalArgumentException("empty row");
            long tot = 0;
            for (long v : cnt)
                tot += v;
            double[] g = new double[n];
            Deque<Integer> small = new ArrayDeque<>();
            Deque<Integer> large = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                double p = (tot == 0) ? 0.0 : (cnt[i] / (double) tot);
                g[i] = p * n;
                if (g[i] < 1.0) small.add(i);
                else large.add(i);
            }

            float[] prob = new float[n];
            short[] alias = new short[n];
            while (!small.isEmpty() && !large.isEmpty()) {
                int s = small.removeFirst();
                int l = large.removeFirst();
                prob[s] = (float) g[s];
                alias[s] = (short) l;
                g[l] = (g[l] + g[s]) - 1.0;
                if (g[l] < 1.0) small.add(l);
                else large.add(l);
            }
            for (int i : small) {
                prob[i] = 1.0f;
                alias[i] = (short) i;
            }
            for (int i : large) {
                prob[i] = 1.0f;
                alias[i] = (short) i;
            }
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
    public char[] generate()
    {
        return generate(1, 64); // minLen=1, maxLen=64 by default
    }

    /**
     * Convenience: generate and return as a Java String.
     */
    public String generateString()
    {
        return new String(generate());
    }

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
            int ab = a * S + b;
            char c;
            if (trigrams[ab] != null) {
                c = sampleNext(a, b); // delegate policy (ML or KN)
            } else {
                c = sample(bigrams[b] != null ? bigrams[b] : unigrams);
            }

            if (id(c) == EOS_ID) {
                if (len >= minLen) break;
                // Enforce minLen: try to force one non‑EOS char using a back‑off row
                // Note: if 'forced' comes back EOS again, we simply try again in the next loop
                char forced = sample(bigrams[b] != null ? bigrams[b] : unigrams);
                if (id(forced) == EOS_ID) continue;
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
    protected abstract char sampleNext(short aId, short bId);

    /**
     * Walker-alias sampling step.
     *
     * @param row alias table to sample
     * @return one char sampled according to row’s multinomial
     *
     * <p>Implementation notes: O(1) expected time.
     * Algorithm: - Let row have n buckets. Draw u in [0,1), i = floor(u*n), r = frac(u*n).
     * - Pick bucket i if r < prob[i], else pick alias[i].
     * - Return the symbol id at the chosen bucket.
     */
    protected char sample(AliasRow row)
    {
        final int n = row.size;
        final double u = rng.nextDouble() * n;
        int i = (int) u;
        double frac = u - i;
        if (frac >= row.prob[i]) i = row.alias[i];
        return row.sym[i];
    }

    /** Maximum-likelihood (no smoothing): next ~ trigrams[a*S + b] directly. */
    public static final class ML extends WordGen
    {
        public ML(Reader in, final char sep) throws IOException
        {
            super(in, sep);
        }

        @Override
        protected char sampleNext(short aId, short bId)
        {
            AliasRow row = trigrams[aId * S + bId];
            if (row == null) { // defensive
                row = (bigrams[bId] != null) ? bigrams[bId] : unigrams;
            }
            return sample(row);
        }
    }

    /**
     * Interpolated Kneser–Ney variant (stub for future work).
     * Currently not implemented; kept to document the intended design.
     */
    public static final class KN extends WordGen
    {

        /**
         * Back-off weight λ₂(ab) per trigram state (a,b), i.e. the mass reserved to
         * back-off to the bigrams distribution P_KN(. | b). Stored as float in [0,1].
         * For unseen states, value is ignored.
         */
        public final float[] lambda2; // length S*S

        protected KN(final Reader in, final char sep) throws IOException
        {
            super(in, sep);
            lambda2 = null;
            // TODO Auto-generated constructor stub
        }

        @Override
        protected char sampleNext(short aId, short bId)
        {
            // TODO KN
            AliasRow row = trigrams[aId * S + bId];
            if (row != null) return sample(row);
            row = (bigrams[bId] != null) ? bigrams[bId] : unigrams;
            return sample(row);
        }

        // TODO: reserveDiscountMass() etc.

        /**
         * KN mixing: sample next symbol with probability (1-λ₂) from trigram, with
         * λ₂ from bigram/unigram backoff; use RNG to flip the coin.
         */
        protected char sampleInterpolate(short aId, short bId)
        {
            // TODO
            return 0;
        }

    }

    public static void main(String[] args) throws Exception
    {
        InputStream is = French.class.getResourceAsStream("fr-grammalecte.csv");
        Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
        WordGen wordML = new WordGen.ML(reader, ',');
        for (int i=0; i < 100; i++) System.out.println(wordML.generate());
    }

}
