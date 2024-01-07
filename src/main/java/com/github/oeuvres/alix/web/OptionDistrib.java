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
package com.github.oeuvres.alix.web;

/**
 * Distribution laws, to be used in sum
 */
public enum OptionDistrib implements Option
{
    OCCS("Occurrences", "") {
        @Override
        public double score(final double freq, final double docLen)
        {
            return freq;
        }
    },
    FREQ("Fréquence", "occurrences trouvée / occurrences document") {
        @Override
        public double score(final double freq, final double docLen)
        {
            return freq / docLen;
        }
    },
    G("G-test", "Vraisemblance log, G = 2 Σ(Oi.ln(Oi/Ei))") {
        // don’t try to get variation 
        @Override
        public double score(final double freq, final double docLen)
        {
            if (freq < 1 || docLen < 1)
                return 0;
            final double Ei = expectation * docLen;
            
            // maybe negative
            return 2.0D * freq * Math.log(freq / Ei);
        }
        /**
         * Because ΣOi = ΣEi = N, G-test need a last addition (not tf-idf like)
         */
        public double last(final double freq, final double docLen)
        {
            return score(freq, docLen);
        }

    },
    BM25("BM25", "Genre de tf-idf") {
        /** Average of document length */
        protected double docAvg;
        /** Classical BM25 param */
        private final double k1 = 1.2f;
        /** Classical BM25 param */
        private final double b = 0.75f;
        /** Cache idf for a term */
        private double idf;

        @Override
        public void idf(final double hits, final double docs, final double occs)
        {
            this.docAvg = occs / docs;
            this.idf = Math.log(1.0 + (docs - hits + 0.5D) / (hits + 0.5D));
        }

        @Override
        public double score(final double freq, final double docLen)
        {
            return idf * (freq * (k1 + 1)) / (freq + k1 * (1 - b + b * docLen / docAvg));
        }

    },
    TFIDF("tf-idf", "Pondération entre “term frequency” et “inverse document frequency”") {
        /** A traditional coefficient */
        final double k = 0.2F;
        /** Cache idf for a term */
        private double idf;

        @Override
        public void idf(final double hits, final double docs, final double occs)
        {
            final double l = 1d; //
            final double toPow = 1d + Math.log((docs + l) / (hits + l));
            this.idf = toPow * toPow;
        }

        @Override
        public double score(final double freq, final double docLen)
        {
            return idf * (k + (1 - k) * (double) freq / (double) docLen);
        }

    },
    CHI2("Chi2", "Vraisemblance carrée, Chi2 = Σ(Oi - Ei)²/Ei") {

        @Override
        public double score(final double freq, final double docLen)
        {
            // negative is not interesting here
            if (freq < 1 || docLen < 1)
                return 0;
            final double Ei = expectation * docLen;
            double O_E = freq - Ei;
            return O_E * O_E / Ei;
        }

        public double last(final double freq, final double docLen)
        {
            return score(freq, docLen);
        }

    },
    /*
     * fisher("Fisher (hypergéométrique)",
     * "p = (AB + A¬B)!(¬AB + ¬A¬B)!(AB + ¬AB)!(A¬B + ¬A¬B) / (AB!A¬B!¬AB!¬A¬B!)") {
     * public double score(final double Oab, final double Oa, final double Ob, final
     * double N) { double[] res = Fisher.test((int)Oab, (int)(Oa - Oab), (int)(Ob -
     * Oab), (int)(N - Oa - Ob + Oab)); return - res[0]; } },
     */
    ;

    protected double idf; // a theoretical idf
    protected double expectation; // global freq expected for a term

    
    public void expectation(final double formOccs, final double occs)
    {
        if (occs == 0) return;
        this.expectation = formOccs / occs;
    }
    
    public void idf(final double hits, final double docs, final double occs)
    {
        return;
    }

    abstract public double score(final double freq, final double docLen);

    public double last(final double freq, final double docLen)
    {
        return 0;
    }


    final public String label;

    public String label()
    {
        return label;
    }

    final public String hint;

    public String hint()
    {
        return hint;
    }

    private OptionDistrib(final String label, final String hint)
    {
        this.label = label;
        this.hint = hint;
    }


}