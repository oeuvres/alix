package com.github.oeuvres.alix.lucene.search;

/**
 * Distribution laws, to be used in sum
 */
public enum Scorer {
    /** Count of occs found. */
    OCCS() {
        @Override
        public double score(final double freq, final double docLen)
        {
            return freq;
        }
    },
    /** occs found / occs by doc. */
    FREQ() {
        @Override
        public double score(final double freq, final double docLen)
        {
            return freq / docLen;
        }
    },
    /**
     * Log test. G = 2 Σ(Oi.ln(Oi/Ei))
     */
    G() {
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
    /** Kind of tf-idf. */
    BM25() {
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
    /** Metric between “term frequency” and “inverse document frequency”. */
    TFIDF() {
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
    /** Chi2 = Σ(Oi - Ei)²/Ei" */
    CHI2() {

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
        if (occs == 0)
            return;
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

    private Scorer() {
    }

}
