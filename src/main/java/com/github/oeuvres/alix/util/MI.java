/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.util;

/**
 * Some algorithms to score co-occurrency.
 */
public enum MI {
    /** Oab */
    OCCS() {
        @Override
        public double score(final double Oab, final double Oa, final double Ob, final double N)
        {
            return Oab;
        }
    },
    /** m11 / (m10 + m01 + m11) */
    JACCARD() {
        @Override
        public double score(final double Oab, final double Oa, final double Ob, final double N)
        {
            double m10 = Oa - Oab;
            double m01 = Ob - Oab;
            return Oab / (m10 + m01 + Oab);
        }
    },
    /** 2*m11 / (m10² + m01²) */
    DICE() {
        @Override
        public double score(final double Oab, final double Oa, final double Ob, final double N)
        {
            double m10 = Oa - Oab;
            double m01 = Ob - Oab;
            return 2 * Oab / (m10 * m10 + m01 * m01);
        }
    },
    /** Normalized Mutual Information. */
    PPMI() {
        // double log2 = Math.log(2);
        double k = 4;

        @Override
        public double score(final double Oab, final double Oa, final double Ob, final double N)
        {
            /*
             * // read in litterature but not very efficient double N11 = Oab; double N10 =
             * Ob - Oab; double N01 = Ob - Oab; double N00 = N - Oa - Ob + Oab; double S =
             * 0; S += N11 / N * Math.log(N11 * N / ((N11 + N10) * (N11 + N01))); S += N10 /
             * N * Math.log(N10 * N / ((N10 + N00) * (N10 + N11))); S += N01 / N *
             * Math.log(N01 * N / ((N01 + N00) * (N01 + N00))); S += N00 / N * Math.log(N00
             * * N / ((N00 + N01) * (N00 + N10))); // return S;
             */
            // strip rare cases
            if (Oa <= k || Ob <= k || Oab <= k) {
                return 0;
            }
            double pmi = Math.log(((Oab + k) / N) / ((Oa / N) * (Ob / N)));
            if (pmi < 0)
                return 0;
            return pmi / -Math.log(Oab / N);
        }
    },
    /** Chi2 = Σ(Oi - Ei)²/Ei */
    CHI2() {
        public double score(final double Oab, final double Oa, final double Ob, final double N)
        {
            // events : m11, m10, m01, m00 ; expected

            double[] E = E(Oab, Oa, Ob, N);
            double[] O = O(Oab, Oa, Ob, N);
            int n = 4;
            double sum = 0d;
            for (int i = 0; i < n; i++) {
                if (O[i] == 0)
                    continue; // is it gut ?
                double O_E = O[i] - E[i];
                sum += O_E * O_E / E[i];
            }
            if (Oab < E[0])
                return -sum;
            return sum;
        }
    },
    /** G = 2 Σ(Oi.ln(Oi/Ei)) */
    G() {
        public double score(final double Oab, final double Oa, final double Ob, final double N)
        {
            double[] E = E(Oab, Oa, Ob, N);
            double[] O = O(Oab, Oa, Ob, N);
            double sum = 0d;
            for (int i = 0; i < 4; i++) {
                if (O[i] == 0)
                    continue; // is it gut ?
                sum += O[i] * Math.log(O[i] / E[i]);
            }
            if (Oab < E[0])
                return sum * -2;
            return sum * 2;
        }
    },
    /*
     * fisher("Fisher (hypergéométrique)",
     * "p = (AB + A¬B)!(¬AB + ¬A¬B)!(AB + ¬AB)!(A¬B + ¬A¬B) / (AB!A¬B!¬AB!¬A¬B!)") {
     * public double score(final double Oab, final double Oa, final double Ob, final
     * double N) { double[] res = Fisher.test((int)Oab, (int)(Oa - Oab), (int)(Ob -
     * Oab), (int)(N - Oa - Ob + Oab)); return - Math.log(res[0]); } },
     */
    ;

    abstract public double score(final double Oab, final double Oa, final double Ob, final double N);

    protected double[] E(final double Oab, final double Oa, final double Ob, final double N)
    {
        // (Oa.Ob + (N-Oa) Ob + Oa (N-Ob) + (N-Oa).(N-Ob)) /N
        // = (Oa.Ob + N.Ob - Oa.Ob + N.Oa - Oa.Ob + N^2 - N.Oa - N.Ob + Oa.Ob) / N
        // = N^2 / N = N
        double[] E = { Oa * Ob / N, (N - Oa) * Ob / N, Oa * (N - Ob) / N, (N - Oa) * (N - Ob) / N };
        return E;
    }

    protected double[] O(final double Oab, final double Oa, final double Ob, final double N)
    {
        // Oab + Ob - Oab + Oa - Oab + N - Oa - Ob + Oab = N
        double[] O = { Oab, Ob - Oab, Oa - Oab, N - Oa - Ob + Oab };
        return O;
    }

    private MI() {
    }

}