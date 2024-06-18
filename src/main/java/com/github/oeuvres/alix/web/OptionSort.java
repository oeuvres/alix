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

import java.io.IOException;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.similarities.Similarity;

import static com.github.oeuvres.alix.Names.*;
import com.github.oeuvres.alix.lucene.search.SimilarityG;
import com.github.oeuvres.alix.lucene.search.SimilarityOccs;

public enum OptionSort implements Option {
    score("Score", null, null), occs("Occurrences", null, new SimilarityOccs()),
    year("Année (+ ancien)",
            new Sort(new SortField("year", SortField.Type.INT), new SortField(ALIX_ID, SortField.Type.STRING)), null),
    year_inv("Année (+ récent)",
            new Sort(new SortField("year", SortField.Type.INT, true),
                    new SortField(ALIX_ID, SortField.Type.STRING, true)),
            null),
    date("Date (+ ancien)",
            new Sort(new SortField("date", SortField.Type.INT), new SortField(ALIX_ID, SortField.Type.STRING)), null),
    date_inv("Date (+ récent)",
            new Sort(new SortField("date", SortField.Type.INT, true), new SortField(ALIX_ID, SortField.Type.STRING)),
            null),
    author("Auteur (A-Z)",
            new Sort(new SortField("author1", SortField.Type.STRING), new SortField("year", SortField.Type.INT)), null),
    author_inv("Auteur (Z-A)",
            new Sort(new SortField("author1", SortField.Type.STRING, true), new SortField("year", SortField.Type.INT)),
            null),
    id("Identifiant (A-Z)", new Sort(new SortField(ALIX_ID, SortField.Type.STRING)), null),
    id_inv("Identifiant (Z-A)", new Sort(new SortField(ALIX_ID, SortField.Type.STRING, true)), null),
    g("Score (G-Test)", null, new SimilarityG()),
    // freq("Fréquence"),
    // "tf-idf", "bm25", "dfi_chi2", "dfi_std", "dfi_sat",
    // "lmd", "lmd0.1", "lmd0.7", "dfr", "ib"
    // "tf-idf", "BM25", "DFI chi²", "DFI standard", "DFI saturé",
    // "LMD", "LMD λ=0.1", "LMD λ=0.7", "DFR", "IB"
    ;

    public final Sort sort;
    public final Similarity sim;
    public final String label;

    private OptionSort(final String label, final Sort sort, final Similarity sim) {
        this.label = label;
        this.sort = sort;
        this.sim = sim;
    }

    /**
     * Get a top docs with no limit (for paging)
     * 
     * @param searcher
     * @param query
     * @return
     * @throws IOException Lucene errors.
     */
    public TopDocs top(IndexSearcher searcher, Query query) throws IOException
    {
        final int totalHitsThreshold = Integer.MAX_VALUE;
        final int numHits = searcher.getIndexReader().maxDoc();
        TopDocsCollector<?> collector = null;
        if (sort != null) {
            collector = TopFieldCollector.create(sort, numHits, totalHitsThreshold);
        } else {
            collector = TopScoreDocCollector.create(numHits, totalHitsThreshold);
        }
        if (sim != null) {
            Similarity oldSim = searcher.getSimilarity();
            searcher.setSimilarity(sim);
            searcher.search(query, collector);
            TopDocs top = collector.topDocs();
            searcher.setSimilarity(oldSim);
            return top;
        }
        searcher.search(query, collector);
        return collector.topDocs();
    }

    /**
     * Get a topd docs with limit
     * 
     * @param searcher
     * @param query
     * @param limit
     * @return
     * @throws IOException Lucene errors.
     */
    public TopDocs top(IndexSearcher searcher, Query query, int limit) throws IOException
    {
        if (sort != null) {
            return searcher.search(query, limit, sort);
        } else if (sim != null) {
            Similarity oldSim = searcher.getSimilarity();
            searcher.setSimilarity(sim);
            TopDocs top = searcher.search(query, limit);
            searcher.setSimilarity(oldSim);
            return top;
        } else {
            return searcher.search(query, limit);
        }
    }

    public String label()
    {
        return label;
    }

    public String hint()
    {
        return "";
    }
}
