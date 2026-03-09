package com.github.oeuvres.alix.lucene.terms;

/**
 * Built-in implementations of {@link TermScorer}.
 */
public final class TermScorers {
    private TermScorers() {
    }

    public static final TermScorer SIGNED_G = new SignedG();
    public static final TermScorer G = new G();
    public static final TermScorer FREQ = new Freq();
    public static final TermScorer JACCARD = new Jaccard();

    public static TermScorer ppmi() {
        return new Ppmi(0.0);
    }

    public static TermScorer ppmi(final double alpha) {
        return new Ppmi(alpha);
    }

    /**
     * Signed G-test.
     * Positive when the term is over-represented in target,
     * negative when under-represented.
     */
    public static final class SignedG implements TermScorer {
        @Override
        public String name() {
            return "SIGNED_G";
        }

        @Override
        public double score(final int a, final long N1, final long b, final long N0) {
            if (N1 <= 0L || N0 <= 0L) return 0d;

            final long c = N1 - a;
            final long d = N0 - b;
            if (a < 0 || b < 0 || c < 0 || d < 0) return 0d;

            final long row1 = (long) a + b;
            final long row2 = c + d;
            final long total = N1 + N0;
            if (row1 <= 0L || row2 <= 0L || total <= 0L) return 0d;

            final double eA = (double) N1 * row1 / total;
            final double eB = (double) N0 * row1 / total;
            final double eC = (double) N1 * row2 / total;
            final double eD = (double) N0 * row2 / total;

            final double g = 2d * (
                cell(a, eA) +
                cell(b, eB) +
                cell(c, eC) +
                cell(d, eD)
            );

            final double p1 = (double) a / (double) N1;
            final double p0 = (double) b / (double) N0;
            return (p1 >= p0) ? g : -g;
        }

        private double cell(final long observed, final double expected) {
            if (observed <= 0L || expected <= 0d) return 0d;
            return observed * Math.log(observed / expected);
        }
    }
    
    /**
     * Unsigned G-test (log-likelihood ratio) on a 2x2 token contingency table.
     *
     * <pre>
     *           term      not term
     * target      a        N1 - a
     * ref         b        N0 - b
     * </pre>
     *
     * <p>
     * The returned statistic is always non-negative.
     * Larger values mean a stronger deviation from independence.
     * </p>
     *
     * <p>
     * This scorer does not encode direction. It makes no distinction between
     * over-representation and under-representation in the target.
     * </p>
     */
    public static final class G implements TermScorer {
        @Override
        public String name() {
            return "G";
        }

        @Override
        public double score(final int a, final long N1, final long b, final long N0) {
            if (N1 <= 0L || N0 <= 0L) return 0d;

            final long c = N1 - a;
            final long d = N0 - b;
            if (a < 0 || b < 0 || c < 0 || d < 0) return 0d;

            final long row1 = (long) a + b;
            final long row2 = c + d;
            final long total = N1 + N0;
            if (row1 <= 0L || row2 <= 0L || total <= 0L) return 0d;

            final double eA = (double) N1 * row1 / total;
            final double eB = (double) N0 * row1 / total;
            final double eC = (double) N1 * row2 / total;
            final double eD = (double) N0 * row2 / total;

            return 2d * (
                cell(a, eA) +
                cell(b, eB) +
                cell(c, eC) +
                cell(d, eD)
            );
        }

        private static double cell(final long observed, final double expected) {
            if (observed <= 0L || expected <= 0d) return 0d;
            return observed * Math.log(observed / expected);
        }
    }

    /**
     * Raw target frequency.
     * Baseline only.
     */
    public static final class Freq implements TermScorer {
        @Override
        public String name() {
            return "FREQ";
        }

        @Override
        public double score(final int a, final long N1, final long b, final long N0) {
            return a;
        }
    }

    /**
     * Token-based Jaccard coefficient on the 2x2 table.
     *
     * union = a + b + c = N1 + b
     *
     * This is mathematically valid but usually crude as a theme score.
     */
    public static final class Jaccard implements TermScorer {
        @Override
        public String name() {
            return "JACCARD";
        }

        @Override
        public double score(final int a, final long N1, final long b, final long N0) {
            final long union = N1 + b;
            if (a <= 0 || union <= 0L) return 0d;
            return (double) a / (double) union;
        }
    }

    /**
     * Positive PMI between term and target population.
     *
     * score = max(0, log( p(term,target) / (p(term) p(target)) ))
     *
     * Optional alpha is additive smoothing on the term counts.
     */
    public static final class Ppmi implements TermScorer {
        private final double alpha;

        public Ppmi(final double alpha) {
            if (alpha < 0d) {
                throw new IllegalArgumentException("alpha=" + alpha + ", expected >= 0");
            }
            this.alpha = alpha;
        }

        @Override
        public String name() {
            return "PPMI";
        }

        @Override
        public double score(final int a, final long N1, final long b, final long N0) {
            if (N1 <= 0L || N0 <= 0L) return 0d;

            final double aa = a + alpha;
            final double bb = b + alpha;
            final double termTotal = aa + bb;
            final double total = (double) N1 + (double) N0;

            if (aa <= 0d || termTotal <= 0d || total <= 0d) return 0d;

            final double pTermAndTarget = aa / total;
            final double pTerm = termTotal / total;
            final double pTarget = (double) N1 / total;

            if (pTermAndTarget <= 0d || pTerm <= 0d || pTarget <= 0d) return 0d;

            final double pmi = Math.log(pTermAndTarget / (pTerm * pTarget));
            return Math.max(0d, pmi);
        }
    }
}