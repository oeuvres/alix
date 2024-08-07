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

import java.util.Arrays;

/**
 * A mutable list of ints with useful metadata, for example to calculate
 * average. Could be used as a row or column in an array.
 */
public class IntSeries extends IntList
{
    /** A class */
    final private int cat;
    /** A code, maybe used for a collection of stack */
    final private int code;
    /** Row a name, useful for collection */
    final private String name;
    /** Row a name, useful for collection */
    final private String label;
    
    
    /** Deciles data */
    public int[] decile = new int[11];
    /** standard deviation */
    public double devstd;
    /** Max value */
    public int max;
    /** Median value */
    public double median;
    /** Arithmetic mean */
    public double mean;
    /** Min value */
    public int min;
    /** Mode value */
    public int mode;
    /** Quartile data */
    public int[] quartile = new int[5];
    /** Full sum, for average */
    public long sum;
    /**
     * Constructor for a statistic series with minimal metadata.
     * 
     * @param name identifying chars (usually ASCII), or null if not needed.
     * @param label displayable label, or null if not needed.
     * @param code identifying number, or .
     * @param cat a category, for series grouping.
     */
    public IntSeries(final String name, final String label, final int code, final int cat) {
        super();
        this.name = name;
        this.label = label;
        this.code = code;
        this.cat = cat;
    }



    /**
     * Cache statistic values for averages of deciles.
     */
    private void calcul()
    {
        // no modif
        if (!toHash) return;
        hashCode(); // set hash for current values
        final int size = this.size;
        if (size == 0) {
            min = 0;
            max = 0;
            sum = 0;
            mean = 0;
            devstd = 0;
            return;
        }
        if (size == 1) {
            mean = median = sum = min = max = data[0];
            Arrays.fill(decile, data[0]);
            Arrays.fill(quartile, data[0]);
            devstd = 0;
            return;
        }
        // for median and decile, copy data and sort
        int[] work = toArray();
        Arrays.sort(work);
        min = work[0];
        max = work[work.length - 1];
        // loop in sort order
        int valPrev = work[0] - 200;
        int modeCount = 0;
        int modeCountTemp = 0;
        sum = 0;
        for (int i = 0; i < size; i++) {
            final int val = work[i];
            sum += val;
            // new value, reset vars for mode
            if (val != valPrev) {
                if (modeCountTemp > modeCount) {
                    mode = valPrev;
                    modeCount = modeCountTemp;
                }
                modeCountTemp = 0;
            }
            modeCountTemp++;
            valPrev = val;
        }

        mean = (double) sum / (double) size;
        double dev = 0;
        for (int i = 0; i < size; i++) {
            long val2 = work[i];
            dev += (mean - val2) * (mean - val2);
        }
        dev = Math.sqrt(dev / size);
        this.devstd = dev;
        // median
        int half = (int)(size / 2.0);
        if (size % 2 == 1) { // odd
            median = work[half];
        }
        else {
            median = (work[half] + work[half+1]) / 2.0;
        }
        // decile
        double part = (work.length) / 10.0;
        decile[0] = min;
        for (int i = 1; i < 10; i++) {
            int index = (int)(Math.ceil(part * i - 1));
            decile[i] = work[index];
        }
        decile[10] = max;
        // quartile
        part = (work.length) / 4.0;
        quartile[0] = min;
        for (int i = 1; i < 4; i++) {
            final int index = (int)Math.ceil(part * i) - 1;
            quartile[i] = work[index];
        }
        quartile[4] = max;
    }

    /**
     * Get a decile.
     * 
     * @param n [0…10] number of a decile.
     * @return value at this decile.
     * @throws ArrayIndexOutOfBoundsException n ∉ [0…10]
     */
    public int decile(int n) throws ArrayIndexOutOfBoundsException
    {
        if (n < 0 || n > 11) throw new ArrayIndexOutOfBoundsException("A decile is between [0…10], no answer for: "+ n);
        calcul();
        return decile[n];
    }

    /**
     * Get a quartile.
     * 
     * @param n [0…4] number of a quartile.
     * @return value at this quartile.
     * @throws ArrayIndexOutOfBoundsException n ∉ [0…4]
     */
    public int quartile(int n) throws ArrayIndexOutOfBoundsException
    {
        if (n < 0 || n > 4) throw new ArrayIndexOutOfBoundsException("A decile is between [0…4], no answer for: "+ n);
        calcul();
        return quartile[n];
    }

    /**
     * Returns minimum value of the series.
     * 
     * @return min value.
     */
    public int min()
    {
        calcul();
        return min;
    }

    /**
     * Returns maximum value of the series.
     * 
     * @return max value.
     */
    public int max()
    {
        calcul();
        return max;
    }

    /**
     * Returns arithmetic mean of the series.
     * 
     * @return mean.
     */
    public double mean()
    {
        calcul();
        return mean;
    }

    /**
     * Returns median of the series.
     * 
     * @return median.
     */
    public double median()
    {
        calcul();
        return median;
    }

    /**
     * Returns mode of the series (most frequent value).
     * 
     * @return mode.
     */
    public double mode()
    {
        calcul();
        return mode;
    }

    /**
     * Returns the label set by constructor.
     * @return label of the series.
     */
    public String label()
    {
        return label;
    }

    /**
     * Returns the name set by constructor.
     * @return name of the series.
     */
    public String name()
    {
        return name;
    }

    /**
     * Returns the code set by constructor.
     * @return code of the series.
     */
    public int code()
    {
        return code;
    }

    /**
     * Returns the cat set by constructor.
     * @return cat of the series.
     */
    public int cat()
    {
        return cat;
    }
}
