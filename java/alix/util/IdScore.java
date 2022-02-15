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
package alix.util;


/**
 * A mutable pair (id, score), sortable on score only, used as cells in arrays.
 * 
 * @author glorieux-f
 */
public class IdScore implements Comparable<IdScore>
{
    /** Object id */
    private int id;
    /** Score to compare values */
    private double score;

    /**
     * Constructor
     * 
     * @param score
     * @param value
     */
    IdScore(final int id, final double score)
    {
        this.id = id;
        this.score = score;
    }

    /**
     * Modify value
     * 
     * @param score
     * @param value
     */
    protected void set(final int id, final double score)
    {
        this.id = id;
        this.score = score;
    }

    public int id()
    {
        return id;
    }

    public double score()
    {
        return score;
    }

    @Override
    public int compareTo(IdScore item)
    {
        return Double.compare(item.score, score);
    }

    @Override
    public String toString()
    {
        return score + "[" + id + "]";
    }

}
