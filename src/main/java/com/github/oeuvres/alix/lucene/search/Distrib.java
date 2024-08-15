package com.github.oeuvres.alix.lucene.search;

/**
 * Distribution laws, could be used as scorer to sort {@link FormEnum}.
 * Is not a {@link org.apache.lucene.search.Scorer}, nor a {@link org.apache.lucene.search.similarities.Distribution}.
 */
public enum Distrib {
    /** Kind of tf-idf. */
    BM25
    {
        /** Average of document length */
        protected double docAvg;
        /** Classical BM25 param */
        private final double k1 = 1.2f;
        /** Classical BM25 param */
        private final double b = 0.75f;
        /** Cache idf for a form */
        private double idf;
    
        @Override
        public void idf(final double hits, final double docsAll, final double occsAll)
        {
            this.docAvg = occsAll / docsAll;
            this.idf = Math.log(1.0 + (docsAll - hits + 0.5D) / (hits + 0.5D));
        }
    
        @Override
        public double score(final double freq, final double docLen)
        {
            return idf * (freq * (k1 + 1)) / (freq + k1 * (1 - b + b * docLen / docAvg));
        }
    
    },
    /** Chi2 = Σ(Oi - Ei)²/Ei" */
    CHI2
    {
        @Override
        public double score(final double freq, final double docLen)
        {
            // negative is not interesting here
            if (freq < 1 || docLen < 1)
                return 0;
            // expectation for this form should have been set before
            final double Ei = expectation * docLen;
            // Oi = freq
            double O_E = freq - Ei;
            return O_E * O_E / Ei;
        }
    
        @Override
        public double last(final double freq, final double docLen)
        {
            return score(freq, docLen);
        }
    
    },
    /** occs found / occs by doc. */
    FREQ()
    {
        @Override
        public double score(final double freq, final double docLen)
        {
            return freq / docLen;
        }
    },
    /**
     * Log test. G = 2 Σ(Oi.ln(Oi/Ei))
     */
    G()
    {
        @Override
        public double score(final double freq, final double docLen)
        {
            if (freq < 1 || docLen < 1)
                return 0;
            final double Ei = expectation * docLen;

            // maybe negative
            return 2.0D * freq * Math.log(freq / Ei);
        }

        @Override
        public double last(final double freq, final double docLen)
        {
            return score(freq, docLen);
        }

    },
    /** Count of occs found. */
    OCCS
    {
        @Override
        public double score(final double freq, final double docLen)
        {
            return freq;
        }
    },
    /** Metric between “term frequency” and “inverse document frequency”. */
    TFIDF()
    {
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
    /*
     * fisher("Fisher (hypergéométrique)",
     * "p = (AB + A¬B)!(¬AB + ¬A¬B)!(AB + ¬AB)!(A¬B + ¬A¬B) / (AB!A¬B!¬AB!¬A¬B!)") {
     * public double score(final double Oab, final double Oa, final double Ob, final
     * double N) { double[] res = Fisher.test((int)Oab, (int)(Oa - Oab), (int)(Ob -
     * Oab), (int)(N - Oa - Ob + Oab)); return - res[0]; } },
     */
    ;

    /** A theoretical inverse document frequency. */
    protected double idf; 
    /** Freq expected for a form. */
    protected double expectation;

    /**
     * Set an expected probability for a form (to compare with observed in some distribution algorithm).
     *  
     * @param occsForm count of occurences for a form.
     * @param occsAll global count of occurrences.
     */
    public void expectation(final double occsForm, final double occsAll)
    {
        if (occsAll == 0)
            return;
        this.expectation = occsForm / occsAll;
    }

    /**
     * Set an inverse document frequency for tf-idf like scoring.
     * Variable common to all forms for a search.
     * 
     * @param hits count of documents with the form.
     * @param docsAll global count of documents.
     * @param occsAll global count of occurrences, used for document size average in BM25.
     */
    public void idf(final double hits, final double docsAll, final double occsAll)
    {
        return;
    }

    /**
     * Calculate a score for a form by doc.
     * 
     * @param freq form frequency.
     * @param docLen document size.
     * @return score for the form in this doc.
     */
    abstract public double score(final double freq, final double docLen);

    /**
     * For some scorer (not tf-idf like) like G-Test or Chi2, ΣOi = ΣEi = N.
     * If N is count of all occurrences (events), if Oi (Observed events) and
     * Ei (Expected events) concern only a part of occurrences (ex: set of 
     * documents), then the sum should be finished by a last count.
     * 
     * @param freq a frequence observed.
     * @param docLen a size of events.
     * @return last observation.
     */
    public double last(final double freq, final double docLen)
    {
        return 0;
    }

    /**
     * Do not freely instantiate.
     */
    private Distrib() {
    }

}
