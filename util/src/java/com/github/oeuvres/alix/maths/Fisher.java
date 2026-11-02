package com.github.oeuvres.alix.maths;

/* Please see the license information at the end of this file. */

/**
 * Computes Fisher's exact test and hypergeometric tail probabilities for a 2 x 2 table.
 *
 * <p>The logarithmic right tail uses a recurrence between consecutive probabilities
 * and remains finite when the corresponding probability underflows a double.</p>
 *
 * <p>Original implementation adapted from
 * https://github.com/s-andrews/SeqMonk/tree/master/edu/northwestern/at/utils.</p>
 */
public class Fisher
{
    /** Relative threshold for stopping a convergent tail sum. */
    private static final double EPSILON = 1e-16d;

    /**
     * Returns the log of the inclusive upper hypergeometric tail.
     *
     * <p>If {@code X ~ Hypergeom(corpusTokens, cf, focusTokens)}, returns
     * {@code log(P(X >= tf))}. The support is
     * {@code max(0, cf + focusTokens - corpusTokens) <= X <= min(cf, focusTokens)}.
     * A term whose count is at or below the support minimum has probability 1.
     * When {@code tf} lies below the mode, the shorter left tail is summed and
     * subtracted with {@link Math#log1p(double)}. The right tail is otherwise
     * summed directly, relative to its first probability.
     * </p>
     *
     * @param tf observed frequency in the focus
     * @param cf collection frequency of the term
     * @param focusTokens total number of tokens in the focus
     * @param corpusTokens total number of tokens in the corpus
     * @return natural logarithm of the inclusive upper-tail probability
     * @throws IllegalArgumentException if counts or margins are inconsistent
     */
    public static double logRightTail(final long tf, final long cf,
            final long focusTokens, final long corpusTokens)
    {
        if (corpusTokens <= 0L || focusTokens < 0L || focusTokens > corpusTokens
                || cf < 0L || cf > corpusTokens) {
            throw new IllegalArgumentException("invalid hypergeometric margins");
        }
        final long min = Math.max(0L, cf - (corpusTokens - focusTokens));
        final long max = Math.min(cf, focusTokens);
        if (tf < 0L || tf > max) {
            throw new IllegalArgumentException("tf outside hypergeometric support");
        }
        if (tf <= min) return 0d;

        // The mode determines which tail can be summed away from the peak.
        final double mode = Math.floor(((double) cf + 1d)
                * ((double) focusTokens + 1d) / ((double) corpusTokens + 2d));
        if (tf <= mode) {
            final double logLeft = logTail(tf - 1L, min, cf, focusTokens, corpusTokens, false);
            return Math.log1p(-Math.exp(logLeft));
        }
        return logTail(tf, max, cf, focusTokens, corpusTokens, true);
    }

    /**
     * Calculates Fisher's exact test from the four cell counts.
     *
     * @param n11 frequency for cell (1,1)
     * @param n12 frequency for cell (1,2)
     * @param n21 frequency for cell (2,1)
     * @param n22 frequency for cell (2,2)
     * @return an array containing the two-sided, left-tail and right-tail probabilities
     * @throws IllegalArgumentException if any cell is negative or the total exceeds int range
     */
    public static double[] test(final int n11, final int n12, final int n21, final int n22)
    {
        if (n11 < 0 || n12 < 0 || n21 < 0 || n22 < 0) {
            throw new IllegalArgumentException("Fisher cell counts cannot be negative");
        }
        final long total = (long) n11 + n12 + n21 + n22;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Fisher.test total exceeds Integer.MAX_VALUE");
        }
        final int r = n11 + n12;
        final int s = n21 + n22;
        final int m = n11 + n21;
        final int n = n12 + n22;
        final int min = Math.max(0, m - s);
        final int max = Math.min(m, r);
        final double cutoff = hypergeometricProbability(n11, r, s, m, n);
        final double[] result = new double[3];

        for (int i = min; ; i++) {
            final double probability = hypergeometricProbability(i, r, s, m, n);
            if (i <= n11) result[1] += probability;
            if (i >= n11) result[2] += probability;
            if (probability <= cutoff) result[0] += probability;
            if (i == max) break;
        }
        for (int i = 0; i < result.length; i++) {
            result[i] = Math.min(result[i], 1d);
        }
        return result;
    }

    /**
     * Returns the natural logarithm of a binomial coefficient.
     *
     * @param n population size
     * @param k number selected
     * @return {@code log(n choose k)}
     */
    protected static double logCombination(final long n, final long k)
    {
        final long small = Math.min(k, n - k);
        if (small < 0L) throw new IllegalArgumentException("invalid combination");
        // Avoid subtracting enormous log-factorials for a small selection.
        if (small <= 64L || (n > 1_000_000_000L && small <= 10_000L)) {
            double sum = 0d;
            for (long i = 1L; i <= small; i++) {
                sum += Math.log((double) (n - small + i)) - Math.log((double) i);
            }
            return sum;
        }
        return Factorial.logFactorial(n) - Factorial.logFactorial(k)
                - Factorial.logFactorial(n - k);
    }

    /**
     * Computes one hypergeometric probability using logarithmic combinations.
     *
     * @param x observed value
     * @param n1d first row total
     * @param n2d second row total
     * @param nd1 first column total
     * @param nd2 second column total
     * @return probability of exactly {@code x}
     */
    protected static double hypergeometricProbability(final int x, final int n1d,
            final int n2d, final int nd1, final int nd2)
    {
        return Math.exp(logCombination(n1d, x)
                + logCombination(n2d, nd1 - x)
                - logCombination((long) nd1 + nd2, nd1));
    }

    /**
     * Sums a hypergeometric tail from its outer endpoint toward the support boundary.
     *
     * @param start first count included in the tail
     * @param end last count included in the tail
     * @param cf collection frequency
     * @param focusTokens focus length
     * @param corpusTokens corpus length
     * @param upper true to sum upward, false to sum downward
     * @return natural logarithm of the tail probability
     */
    private static double logTail(final long start, final long end,
            final long cf, final long focusTokens, final long corpusTokens, final boolean upper)
    {
        final double logFirst = logCombination(cf, start)
                + logCombination(corpusTokens - cf, focusTokens - start)
                - logCombination(corpusTokens, focusTokens);
        double sum = 1d;
        double relative = 1d;
        if (upper) {
            for (long k = start; k < end; k++) {
                relative *= ((double) (cf - k) / (k + 1d))
                        * ((double) (focusTokens - k)
                                / (corpusTokens - cf - focusTokens + (double) k + 1d));
                if (relative <= EPSILON * sum) break;
                sum += relative;
            }
        } else {
            for (long k = start; k > end; k--) {
                relative *= ((double) k / (cf - (double) k + 1d))
                        * ((corpusTokens - cf - focusTokens + (double) k)
                                / (focusTokens - (double) k + 1d));
                if (relative <= EPSILON * sum) break;
                sum += relative;
            }
        }
        return Math.min(0d, logFirst + Math.log(sum));
    }

    /**
     * Prevents direct instantiation while permitting subclasses.
     */
    protected Fisher() {}
}

/*
 * <p> Copyright &copy; 2004-2011 Northwestern University. </p> <p> This program
 * is free software; you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation;
 * either version 2 of the License, or (at your option) any later version. </p>
 * <p> This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details. </p> <p> You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA. </p>
 */
