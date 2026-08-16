package com.github.oeuvres.alix.lucene.vecs;

import java.util.Arrays;

/**
 * Classical MDS on cosine (chord) distance for a small word set, computed as a
 * mass-weighted PCA of the L2-normalised vectors restricted to that set.
 */
public final class WordPlane
{
    private final double[][] coords;      // n x 2
    private final double[][] contrib;     // n x 2, percent of axis inertia
    private final double[] eigenvalues;   // all positive eigenvalues, descending
    private final double totalInertia;
    private final double stress1;

    private WordPlane(final double[][] coords, final double[][] contrib,
            final double[] eigenvalues, final double totalInertia, final double stress1)
    {
        this.coords = coords;
        this.contrib = contrib;
        this.eigenvalues = eigenvalues;
        this.totalInertia = totalInertia;
        this.stress1 = stress1;
    }

    /**
     * Fits the plane.
     *
     * @param rows L2-normalised vectors, one row per word
     * @param mass non-negative mass per word; normalised internally to sum 1
     * @return fitted plane
     */
    public static WordPlane fit(final double[][] rows, final double[] mass)
    {
        final int n = rows.length;
        final int dim = rows[0].length;

        final double[] m = new double[n];
        double massSum = 0d;
        for (int i = 0; i < n; i++) {
            massSum += mass[i];
        }
        for (int i = 0; i < n; i++) {
            m[i] = mass[i] / massSum;
        }

        // weighted centroid, then centre
        final double[] centroid = new double[dim];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < dim; a++) {
                centroid[a] += m[i] * rows[i][a];
            }
        }
        final double[][] y = new double[n][dim];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < dim; a++) {
                y[i][a] = rows[i][a] - centroid[a];
            }
        }

        // weighted Gram: B_ij = sqrt(m_i m_j) <y_i, y_j>
        final double[] sqrtM = new double[n];
        for (int i = 0; i < n; i++) {
            sqrtM[i] = Math.sqrt(m[i]);
        }
        final double[][] b = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double dot = 0d;
                for (int a = 0; a < dim; a++) {
                    dot += y[i][a] * y[j][a];
                }
                final double v = sqrtM[i] * sqrtM[j] * dot;
                b[i][j] = v;
                b[j][i] = v;
            }
        }

        final double[][] vectors = new double[n][n];
        final double[] values = new double[n];
        jacobi(b, values, vectors);

        double total = 0d;
        int positive = 0;
        for (final double v : values) {
            if (v > 1e-12) {
                total += v;
                positive++;
            }
        }
        final double[] eigenvalues = Arrays.copyOf(values, Math.max(positive, 1));

        // coord_ik = sqrt(lambda_k) * v_ik / sqrt(m_i);  contrib_ik = v_ik^2
        final double[][] coords = new double[n][2];
        final double[][] contrib = new double[n][2];
        for (int k = 0; k < 2; k++) {
            final double scale = Math.sqrt(Math.max(values[k], 0d));
            for (int i = 0; i < n; i++) {
                coords[i][k] = scale * vectors[i][k] / sqrtM[i];
                contrib[i][k] = 100d * vectors[i][k] * vectors[i][k];
            }
        }
        fixSigns(coords);

        return new WordPlane(coords, contrib, eigenvalues, total, stress1(y, coords));
    }

    /**
     * Rotates and reflects this configuration onto a reference one, so that maps
     * of overlapping word sets stay comparable.
     *
     * @param coords configuration to align, modified in place
     * @param reference reference configuration with the same row count
     */
    public static void procrustes(final double[][] coords, final double[][] reference)
    {
        final int n = coords.length;
        // M = coords^T * reference  (2 x 2)
        final double[][] mm = new double[2][2];
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < 2; p++) {
                for (int q = 0; q < 2; q++) {
                    mm[p][q] += coords[i][p] * reference[i][q];
                }
            }
        }
        // R = U V^T from the SVD of M, via the EVD of M^T M
        final double[][] mtm = new double[2][2];
        for (int p = 0; p < 2; p++) {
            for (int q = 0; q < 2; q++) {
                for (int r = 0; r < 2; r++) {
                    mtm[p][q] += mm[r][p] * mm[r][q];
                }
            }
        }
        final double[][] v = new double[2][2];
        final double[] s2 = new double[2];
        jacobi(mtm, s2, v);

        final double[][] u = new double[2][2];
        for (int k = 0; k < 2; k++) {
            final double s = Math.sqrt(Math.max(s2[k], 0d));
            if (s < 1e-12) {
                u[0][k] = (k == 0) ? 1d : 0d;
                u[1][k] = (k == 0) ? 0d : 1d;
                continue;
            }
            for (int p = 0; p < 2; p++) {
                double acc = 0d;
                for (int q = 0; q < 2; q++) {
                    acc += mm[p][q] * v[q][k];
                }
                u[p][k] = acc / s;
            }
        }
        final double[][] r = new double[2][2];
        for (int p = 0; p < 2; p++) {
            for (int q = 0; q < 2; q++) {
                double acc = 0d;
                for (int k = 0; k < 2; k++) {
                    acc += u[p][k] * v[q][k];
                }
                r[p][q] = acc;
            }
        }
        for (int i = 0; i < n; i++) {
            final double x = coords[i][0];
            final double yy = coords[i][1];
            coords[i][0] = x * r[0][0] + yy * r[1][0];
            coords[i][1] = x * r[0][1] + yy * r[1][1];
        }
    }

    public double[][] coords() { return coords; }

    public double[][] contrib() { return contrib; }

    public double dimPct(final int k)
    {
        return (totalInertia <= 0d) ? 0d : 100d * eigenvalues[k] / totalInertia;
    }

    public double stress1() { return stress1; }

    /** Deterministic orientation: the largest |coordinate| on each axis is positive. */
    private static void fixSigns(final double[][] coords)
    {
        for (int k = 0; k < 2; k++) {
            int best = 0;
            for (int i = 1; i < coords.length; i++) {
                if (Math.abs(coords[i][k]) > Math.abs(coords[best][k])) {
                    best = i;
                }
            }
            if (coords[best][k] < 0d) {
                for (final double[] row : coords) {
                    row[k] = -row[k];
                }
            }
        }
    }

    /** Cyclic Jacobi EVD of a symmetric matrix; outputs eigenvalues descending. */
    private static void jacobi(final double[][] input, final double[] values, final double[][] vectors)
    {
        final int n = input.length;
        final double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = input[i].clone();
        }
        for (int i = 0; i < n; i++) {
            Arrays.fill(vectors[i], 0d);
            vectors[i][i] = 1d;
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0d;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += a[p][q] * a[p][q];
                }
            }
            if (off < 1e-24) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-18) {
                        continue;
                    }
                    final double theta = (a[q][q] - a[p][p]) / (2d * a[p][q]);
                    final double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1d));
                    final double c = 1d / Math.sqrt(t * t + 1d);
                    final double s = t * c;
                    for (int k = 0; k < n; k++) {
                        final double akp = a[k][p];
                        final double akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        final double apk = a[p][k];
                        final double aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        final double vkp = vectors[k][p];
                        final double vkq = vectors[k][q];
                        vectors[k][p] = c * vkp - s * vkq;
                        vectors[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        final double[] diag = new double[n];
        for (int i = 0; i < n; i++) {
            diag[i] = a[i][i];
        }
        Arrays.sort(order, (x, yy) -> Double.compare(diag[yy], diag[x]));
        final double[][] sorted = new double[n][n];
        for (int k = 0; k < n; k++) {
            values[k] = diag[order[k]];
            for (int i = 0; i < n; i++) {
                sorted[i][k] = vectors[i][order[k]];
            }
        }
        for (int i = 0; i < n; i++) {
            System.arraycopy(sorted[i], 0, vectors[i], 0, n);
        }
    }

    /** Kruskal stress-1 of the plane against the true high-dimensional distances. */
    private static double stress1(final double[][] y, final double[][] coords)
    {
        final int n = y.length;
        double num = 0d;
        double den = 0d;
        double sxy = 0d;
        double sxx = 0d;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                sxy += hi(y, i, j) * lo(coords, i, j);
                sxx += hi(y, i, j) * hi(y, i, j);
            }
        }
        final double beta = (sxx <= 0d) ? 0d : sxy / sxx;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final double d = lo(coords, i, j) - beta * hi(y, i, j);
                num += d * d;
                den += lo(coords, i, j) * lo(coords, i, j);
            }
        }
        return (den <= 0d) ? 0d : Math.sqrt(num / den);
    }

    private static double hi(final double[][] y, final int i, final int j)
    {
        double sum = 0d;
        for (int a = 0; a < y[i].length; a++) {
            final double d = y[i][a] - y[j][a];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    private static double lo(final double[][] coords, final int i, final int j)
    {
        final double dx = coords[i][0] - coords[j][0];
        final double dy = coords[i][1] - coords[j][1];
        return Math.sqrt(dx * dx + dy * dy);
    }
}
