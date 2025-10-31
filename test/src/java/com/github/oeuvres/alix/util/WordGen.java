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
 * WordGen: runtime sampler for a character 3‑gram (k=3) model with
 * Maximum‑Likelihood (ML) and Modified Kneser–Ney (KN) smoothing variants.
 *
 * <p><b>Data model</b>:
 * <ul>
 *   <li>Alphabet Σ includes BOS='^' (id 0) and EOS='$' (id 1).</li>
 *   <li>Training file format: <code>word{sep}count</code> per line. Tokens are trimmed and NFC‑normalized.</li>
 *   <li>We collect dense ML counts c3[a,b,c], c2[b,c], c1[c]; totals tot3[a,b]=Σ_c c3 and tot2[b]=Σ_c c2.</li>
 *   <li>For ML, we compact non‑zero rows into Walker–alias samplers.</li>
 *   <li>For KN (subclass <code>KN</code>), we compute per‑row discounted weights and back‑off masses λ, and sample by
 *       interpolation: P(c|ab) = (1−λ_ab)·P_disc(c|ab) + λ_ab·P_KN(c|b); and P_KN(c|b) = (1−λ_b)·P_disc(c|b) + λ_b·P_cont(c).</li>
 * </ul>
 *
 * <p><b>Why long strings can occur under ML</b>:</p>
 * EOS can be emitted only from bigram states (a,b) that ended words in training. If the chain
 * roams inside end‑free contexts, it stops only when it reaches an end‑capable state or hits a max length cap.
 * KN interpolation fixes this by giving EOS non‑zero mass more broadly via back‑off.
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
     * ML trigram samplers per state (a,b) indexed by row = a*S + b. Nullable rows.
     */
    public final AliasRow[] trigrams; // length S*S, may contain null rows

    /** ML bigram samplers per previous symbol b (nullable). */
    public final AliasRow[] bigrams; // length S, may contain null rows

    /** ML unigram sampler (frequency of symbols). */
    public final AliasRow unigrams;

    /** RNG used for alias sampling. */
    public final Random rng;

    // ========= Retained counts (for KN and diagnostics) =========
    /** Dense trigram counts c3[a,b,c]. */
    protected final long[] triCounts; // size S*S*S
    /** Dense bigram counts c2[b,c]. */
    protected final long[] biCounts;  // size S*S
    /** Dense unigram counts c1[c]. */
    protected final long[] uniCounts; // size S
    /** Row totals: tot3[a,b] = Σ_c c3[a,b,c]. */
    protected final long[] tot3;      // size S*S
    /** Row totals: tot2[b]   = Σ_c c2[b,c]. */
    protected final long[] tot2;      // size S

    // ===================== Construction =====================

    /**
     * Build the model from a CSV/TSV-like reader (word{sep}count).
     *
     * <p>Parsing policy:
     * <ul>
     *   <li>Skip empty or '#' comment lines.</li>
     *   <li>Tokens are trimmed and normalized to NFC via {@link Normalizer}.</li>
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
                String word = line.substring(0, tab).strip();
                if (word.isEmpty()) continue;
                word = Normalizer.normalize(word, Normalizer.Form.NFC);
                final String num = line.substring(tab + 1).trim();

                final long cnt;
                try { cnt = Long.parseLong(num); }
                catch (final NumberFormatException e) { continue; }
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
        this.triCounts = new long[S * S * S]; // c3[a,b,c]
        this.biCounts  = new long[S * S];     // c2[b,c]
        this.uniCounts = new long[S];         // c1[c]
        this.tot3      = new long[S * S];     // Σ_c c3[a,b,c]
        this.tot2      = new long[S];         // Σ_c c2[b,c]

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
                triCounts[idx3] += cnt;
                tot3[a * S + b] += cnt;

                final int idx2 = b * S + c;
                biCounts[idx2] += cnt;
                tot2[b] += cnt;

                uniCounts[c] += cnt;
            }
        }

        // 4) Build ML alias rows from dense tables
        this.trigrams = new AliasRow[S * S];
        for (int ab = 0; ab < S * S; ab++) {
            if (tot3[ab] == 0) { trigrams[ab] = null; continue; }
            final int base = ab * S;
            int nz = 0; for (int c = 0; c < S; c++) if (triCounts[base + c] != 0) nz++;
            final char[] sym = new char[nz];
            final long[] cnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                final long v = triCounts[base + c];
                if (v != 0) { sym[k] = idToChar[c]; cnt[k++] = v; }
            }
            trigrams[ab] = AliasRow.fromCounts(sym, cnt);
        }

        this.bigrams = new AliasRow[S];
        for (int b = 0; b < S; b++) {
            if (tot2[b] == 0) { bigrams[b] = null; continue; }
            final int base = b * S;
            int nz = 0; for (int c = 0; c < S; c++) if (biCounts[base + c] != 0) nz++;
            final char[] sym = new char[nz];
            final long[] cnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                final long v = biCounts[base + c];
                if (v != 0) { sym[k] = idToChar[c]; cnt[k++] = v; }
            }
            bigrams[b] = AliasRow.fromCounts(sym, cnt);
        }

        int nz = 0; for (int c = 0; c < S; c++) if (uniCounts[c] != 0) nz++;
        if (nz == 0) { // defensive: EOS-only dist
            this.unigrams = new AliasRow(new char[] { EOS }, new float[] { 1f }, new short[] { 0 });
        } else {
            final char[] usym = new char[nz];
            final long[] ucnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) {
                final long v = uniCounts[c];
                if (v != 0) { usym[k] = idToChar[c]; ucnt[k++] = v; }
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
        final String word; final long count; WC(final String w, final long c) { this.word = w; this.count = c; }
    }

    public static final class AliasRow
    {
        public final char[] sym;
        public final float[] prob;  // bucket probability (scaled so that Σ prob / n = 1)
        public final short[] alias; // alias bucket index
        public final int size;

        public AliasRow(final char[] sym, final float[] prob, final short[] alias)
        { this.sym = sym; this.prob = prob; this.alias = alias; this.size = sym.length; }

        /** Build alias table from integral counts. */
        public static AliasRow fromCounts(final char[] sym, final long[] cnt)
        {
            final int n = sym.length;
            if (n == 0) throw new IllegalArgumentException("empty row");
            long tot = 0; for (final long v : cnt) tot += v;
            if (tot <= 0) { // uniform fallback
                final float[] p = new float[n]; final short[] a = new short[n];
                Arrays.fill(p, 1f); for (short i = 0; i < n; i++) a[i] = i; return new AliasRow(sym, p, a);
            }
            final double[] weights = new double[n];
            for (int i = 0; i < n; i++) weights[i] = (double) cnt[i];
            return fromWeights(sym, weights);
        }

        /** Build alias table from arbitrary non‑negative weights. */
        public static AliasRow fromWeights(final char[] sym, final double[] w)
        {
            final int n = sym.length;
            if (n == 0) throw new IllegalArgumentException("empty row");
            double tot = 0.0; for (double x : w) tot += x;
            final float[] prob = new float[n];
            final short[] alias = new short[n];
            if (tot <= 0.0) {
                Arrays.fill(prob, 1f); for (short i = 0; i < n; i++) alias[i] = i; return new AliasRow(sym, prob, alias);
            }
            final double[] g = new double[n];
            final Deque<Integer> small = new ArrayDeque<>();
            final Deque<Integer> large = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                final double p = w[i] / tot;
                g[i] = p * n;
                if (g[i] < 1.0) small.add(i); else large.add(i);
            }
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
            else c = sampleBackoff(b);

            if (id(c) == EOS_ID) {
                if (len >= minLen) break;
                // Enforce minLen: try a back-off draw from the same context b
                final char forced = sampleBackoff(b);
                if (id(forced) == EOS_ID) continue; // try again next loop
                if (len == out.length) out = Arrays.copyOf(out, out.length << 1);
                out[len++] = forced;
                a = b; b = id(forced);
                continue;
            }

            if (len == out.length) out = Arrays.copyOf(out, out.length << 1);
            out[len++] = c;
            if (len >= maxLen) break;
            a = b; b = id(c);
        }
        return Arrays.copyOf(out, len);
    }

    // ----- Hooks -----
    /** Decide next char for context (a,b) when a trigram row exists. */
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
    protected char sampleBackoff(final short bId)
    {   // ML default: bigram if present else unigram
        final AliasRow row = (bigrams[bId] != null) ? bigrams[bId] : unigrams;
        return sample(row);
    }

    /** Walker–alias sampling. */
    protected final char sample(final AliasRow row)
    {
        final int n = row.size;
        final double u = rng.nextDouble() * n;
        int i = (int) u;
        final double frac = u - i;
        if (frac >= row.prob[i]) i = row.alias[i];
        return row.sym[i];
    }

    /** Maximum-likelihood (no smoothing). */
    public static final class ML extends WordGen
    {
        public ML(final Reader in, final char sep) throws IOException { super(in, sep); }

        @Override protected char sampleNext(final short aId, final short bId)
        {
            AliasRow r = trigrams[aId * S + bId];
            if (r == null) return sampleBackoff(bId);
            return sample(r);
        }
    }

    /**
     * Modified Kneser–Ney trigram model with bigram back‑off and continuation unigrams.
     *
     * <p>Discounts are estimated per order via Chen–Goodman (1999):
     * D_r = r − (r+1)·Y·(n_{r+1}/n_r) for r∈{1,2,3}, with Y = n1/(n1+2 n2).
     * We clamp to [0, r) and fall back to 0.75 if ill‑defined.</p>
     */
    public static final class KN extends WordGen
    {
        /** Back-off weight λ₂(ab) per trigram state (a,b). */
        public final float[] lambda2; // length S*S
        /** Back-off weight λ₁(b) per bigram state b. */
        public final float[] lambda1; // length S

        /** Discounted trigram and bigram rows (P_disc). */
        private final AliasRow[] triDisc; // length S*S
        private final AliasRow[] biDisc;  // length S
        /** Continuation unigram P_cont(.). */
        private final AliasRow cont;

        /** Mass of the discounted component (1−λ) cached per row. */
        private final float[] triMass; // 1 - lambda2
        private final float[] biMass;  // 1 - lambda1

        public KN(final Reader in, final char sep) throws IOException
        {
            super(in, sep);

            // --- Estimate discounts per order ---
            final Discounts d3 = estimateDiscounts(triCounts);
            final Discounts d2 = estimateDiscounts(biCounts);

            // --- Continuation counts for P_cont(c) ---
            final long[] contCount = new long[S];
            long bigramTypeTotal = 0;
            for (int b = 0; b < S; b++) {
                final int base = b * S;
                for (int c = 0; c < S; c++) {
                    if (biCounts[base + c] > 0) { contCount[c]++; bigramTypeTotal++; }
                }
            }
            // contCount[c] / bigramTypeTotal = P_cont(c)
            this.cont = buildAliasFromCounts(contCount);

            // --- Build discounted bigram rows and λ₁(b) ---
            this.lambda1 = new float[S];
            this.biDisc = new AliasRow[S];
            this.biMass = new float[S];
            for (int b = 0; b < S; b++) {
                final long rowTot = tot2[b];
                if (rowTot == 0) { lambda1[b] = 1f; biDisc[b] = null; biMass[b] = 0f; continue; }
                final int base = b * S;
                int nz = 0, n1 = 0, n2 = 0, n3p = 0;
                for (int c = 0; c < S; c++) {
                    final long v = biCounts[base + c];
                    if (v == 0) continue; nz++;
                    if (v == 1) n1++; else if (v == 2) n2++; else n3p++;
                }
                final double D1 = d2.D1, D2 = d2.D2, D3 = d2.D3;
                final double backMass = (D1 * n1 + D2 * n2 + D3 * n3p) / (double) rowTot;
                this.lambda1[b] = (float) clamp01(backMass);
                this.biMass[b] = (float) clamp01(1.0 - backMass);

                final char[] sym = new char[nz];
                final double[] w = new double[nz];
                for (int c = 0, k = 0; c < S; c++) {
                    final long v = biCounts[base + c];
                    if (v == 0) continue;
                    final double Dc = (v == 1) ? D1 : (v == 2) ? D2 : D3;
                    final double ww = v - Dc; // discounted weight
                    sym[k] = idToChar[c];
                    w[k++] = Math.max(0.0, ww);
                }
                this.biDisc[b] = AliasRow.fromWeights(sym, w);
            }

            // --- Build discounted trigram rows and λ₂(ab) ---
            this.lambda2 = new float[S * S];
            this.triDisc = new AliasRow[S * S];
            this.triMass = new float[S * S];
            for (int ab = 0; ab < S * S; ab++) {
                final long rowTot = tot3[ab];
                if (rowTot == 0) { lambda2[ab] = 1f; triDisc[ab] = null; triMass[ab] = 0f; continue; }
                final int base = ab * S;
                int nz = 0, n1 = 0, n2 = 0, n3p = 0;
                for (int c = 0; c < S; c++) {
                    final long v = triCounts[base + c];
                    if (v == 0) continue; nz++;
                    if (v == 1) n1++; else if (v == 2) n2++; else n3p++;
                }
                final double D1 = d3.D1, D2 = d3.D2, D3 = d3.D3;
                final double backMass = (D1 * n1 + D2 * n2 + D3 * n3p) / (double) rowTot;
                this.lambda2[ab] = (float) clamp01(backMass);
                this.triMass[ab] = (float) clamp01(1.0 - backMass);

                final char[] sym = new char[nz];
                final double[] w = new double[nz];
                for (int c = 0, k = 0; c < S; c++) {
                    final long v = triCounts[base + c];
                    if (v == 0) continue;
                    final double Dc = (v == 1) ? D1 : (v == 2) ? D2 : D3;
                    final double ww = v - Dc; // discounted weight
                    sym[k] = idToChar[c];
                    w[k++] = Math.max(0.0, ww);
                }
                this.triDisc[ab] = AliasRow.fromWeights(sym, w);
            }
        }

        private static final class Discounts { final double D1,D2,D3; Discounts(double d1,double d2,double d3){D1=d1;D2=d2;D3=d3;} }

        /** Estimate Chen–Goodman discounts (per order), with robust fallbacks. */
        private Discounts estimateDiscounts(final long[] counts)
        {
            long n1=0,n2=0,n3=0,n4=0;
            for (final long v : counts) {
                if (v==0) continue;
                if (v==1) n1++; else if (v==2) n2++; else if (v==3) n3++; else n4++;
            }
            if (n1==0 || (n1+2.0*n2)==0) {
                // Fallback to a conventional single discount 0.75
                return new Discounts(0.75, 0.75, 0.75);
            }
            final double Y = n1 / (n1 + 2.0 * n2);
            double D1 = 1.0 - 2.0 * Y * (n2 / (double) Math.max(1L,n1));
            double D2 = 2.0 - 3.0 * Y * (n3 / (double) Math.max(1L,n2));
            double D3 = 3.0 - 4.0 * Y * (n4 / (double) Math.max(1L,n3));
            // Clamp and sanitize
            D1 = clamp(D1, 0.0, 1.0);
            D2 = clamp(D2, 0.0, 2.0);
            D3 = clamp(D3, 0.0, 3.0);
            // Ensure monotone-ish (not required, but helps degenerate cases)
            if (D2 < D1) D2 = D1;
            if (D3 < D2) D3 = D2;
            // Convert to per-count discounts (used as v - D(count)) where count>=3 uses D3
            // but D must be < 1 for stable positive weights; if not, fallback
            if (D1>=1.0 || D2>=2.0 || D3>=3.0) return new Discounts(0.75,0.75,0.75);
            return new Discounts(D1,D2,D3);
        }

        private static double clamp(final double x, final double lo, final double hi) { return Math.max(lo, Math.min(hi, x)); }
        private static double clamp01(final double x) { return clamp(x, 0.0, 1.0); }

        private AliasRow buildAliasFromCounts(final long[] counts)
        {
            int nz = 0; for (int c = 0; c < S; c++) if (counts[c] > 0) nz++;
            if (nz == 0) return new AliasRow(new char[]{EOS}, new float[]{1f}, new short[]{0});
            final char[] sym = new char[nz];
            final long[] cnt = new long[nz];
            for (int c = 0, k = 0; c < S; c++) if (counts[c] > 0) { sym[k] = idToChar[c]; cnt[k++] = counts[c]; }
            return AliasRow.fromCounts(sym, cnt);
        }

        @Override protected char sampleNext(final short aId, final short bId)
        {
            final int ab = aId * S + bId;
            final AliasRow tri = triDisc[ab];
            if (tri != null) {
                final double u = rng.nextDouble();
                if (u < triMass[ab]) return sample(tri);        // discounted trigram part
                return sampleBigramKN(bId);                     // back-off
            }
            return sampleBigramKN(bId);
        }

        @Override protected char sampleBackoff(final short bId)
        {   // for constructor-time generate() or missing tri rows
            return sampleBigramKN(bId);
        }

        private char sampleBigramKN(final short bId)
        {
            final AliasRow bi = biDisc[bId];
            if (bi != null) {
                final double u = rng.nextDouble();
                if (u < biMass[bId]) return sample(bi);         // discounted bigram part
                return sample(cont);                            // continuation unigram
            }
            return sample(cont);
        }
    }

    public static void main(final String[] args) throws Exception
    {
        // final String lexicon = "fr-grammalecte.csv";
        final String lexicon = "fr-google1gram.csv";
        Reader reader = new InputStreamReader(French.class.getResourceAsStream(lexicon), StandardCharsets.UTF_8);
        System.out.println("== ML");
        final WordGen wordML = new WordGen.ML(reader, ',');
        for (int i = 0; i < 20; i++) System.out.println(wordML.generate());
        // KN demo^
        reader = new InputStreamReader(French.class.getResourceAsStream(lexicon), StandardCharsets.UTF_8);
        System.out.println("== KN");
        final WordGen wordKN = new WordGen.KN(reader, ',');
        for (int i = 0; i < 20; i++) System.out.println(wordKN.generate());
    }
}
