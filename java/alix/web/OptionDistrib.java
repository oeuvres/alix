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
package alix.web;

/**
 * Distribution laws, to be used in sum
 * 
 * @author glorieux-f
 */
public enum OptionDistrib implements Option
{
    occs("Occurrences", "", new ScorerOccs()), g("G-test (vraisemblance log)", "G = 2 Σ(Oi.ln(Oi/Ei))", new ScorerG()),
    bm25("BM25 (genre de tf-idf)", "", new ScorerBm25()),
    chi2("Chi2 (vraisemblance carrée)", "Chi2 = Σ(Oi - Ei)²/Ei", new ScorerChi2()),
    tfidf("tf-idf", "Pondération entre “term frequency” et “inverse document frequency”", new ScorerTfidf()),
    /*
     * fisher("Fisher (hypergéométrique)",
     * "p = (AB + A¬B)!(¬AB + ¬A¬B)!(AB + ¬AB)!(A¬B + ¬A¬B) / (AB!A¬B!¬AB!¬A¬B!)") {
     * public double score(final double Oab, final double Oa, final double Ob, final
     * double N) { double[] res = Fisher.test((int)Oab, (int)(Oa - Oab), (int)(Ob -
     * Oab), (int)(N - Oa - Ob + Oab)); return - res[0]; } },
     */
    ;

    abstract static public class Scorer
    {
        protected double frq; // used to calculate expected

        public double idf(final double allOccs, final double allDocs, final double formOccs, final double formDocs)
        {
            this.frq = formOccs / allOccs;
            return 1;
        }

        abstract public double tf(final double freq, final double docLen);

        public double last(final double freq, final double docLen)
        {
            return 0;
        }
    }

    public static class ScorerOccs extends Scorer
    {
        @Override
        public double tf(final double freq, final double docLen)
        {
            return freq;
        }
    }

    public static class ScorerG extends Scorer
    {
        @Override
        public double tf(final double freq, final double docLen)
        {
            if (freq < 1 || docLen < 1)
                return 0;
            final double Ei = frq * docLen;
            // maybe negative
            return 2.0D * freq * Math.log(freq / Ei);
        }

        public double last(final double freq, final double docLen)
        {
            return tf(freq, docLen);
        }
    }

    public static class ScorerBm25 extends Scorer
    {
        /** Average of document length */
        protected double docAvg;
        /** Classical BM25 param */
        private final double k1 = 1.2f;
        /** Classical BM25 param */
        private final double b = 0.75f;
        /** Cache idf for a term */
        private double idf;

        @Override
        public double idf(final double allOccs, final double allDocs, final double formOccs, final double formDocs)
        {
            this.docAvg = allOccs / allDocs;
            this.idf = Math.log(1.0 + (allDocs - formDocs + 0.5D) / (formDocs + 0.5D));
            return idf;
        }

        @Override
        public double tf(final double freq, final double docLen)
        {
            return idf * (freq * (k1 + 1)) / (freq + k1 * (1 - b + b * docLen / docAvg));
        }
    }

    public static class ScorerTfidf extends Scorer
    {
        /** A traditional coefficient */
        final double k = 0.2F;
        /** Cache idf for a term */
        private double idf;

        @Override
        public double idf(final double allOccs, final double allDocs, final double formOccs, final double formDocs)
        {
            final double l = 1d; //
            final double toPow = 1d + Math.log((allDocs + l) / (formDocs + l));
            this.idf = toPow * toPow;
            return idf;
        }

        @Override
        public double tf(final double freq, final double docLen)
        {
            return idf * (k + (1 - k) * (double) freq / (double) docLen);
        }
    }

    public static class ScorerChi2 extends Scorer
    {
        @Override
        public double tf(final double freq, final double docLen)
        {
            // negative is not interesting here
            if (freq < 1 || docLen < 1)
                return 0;
            final double Ei = frq * docLen;
            double O_E = freq - Ei;
            return O_E * O_E / Ei;
        }

        public double last(final double freq, final double docLen)
        {
            return tf(freq, docLen);
        }
    }

    public static class ScorerLafon extends Scorer
    {

        @Override
        public double tf(double freq, double docLen)
        {
            /*
             * fisher("Fisher (hypergéométrique)",
             * "p = (AB + A¬B)!(¬AB + ¬A¬B)!(AB + ¬AB)!(A¬B + ¬A¬B) / (AB!A¬B!¬AB!¬A¬B!)") {
             * public double score(final double Oab, final double Oa, final double Ob, final
             * double N) { double[] res = Fisher.test((int)Oab, (int)(Oa - Oab), (int)(Ob -
             * Oab), (int)(N - Oa - Ob + Oab)); return - res[0]; } }
             */
            return 0;
        }

    }

    final public Scorer scorer;
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

    private OptionDistrib(final String label, final String hint, final Scorer scorer)
    {
        this.label = label;
        this.hint = hint;
        this.scorer = scorer;
    }

    public Scorer scorer()
    {
        return scorer;
    }

}