package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

public interface ProbAttribute extends Attribute {



    /**
     * Return the prob of the pos for the current token.
     *
     * @return prob
     */
    double getProb();

    /**
     * Set the prob of the pos for the current token.
     *
     * @param pos POS code, or {@link #UNKNOWN} to clear.
     */
    void setProb(double prob);
}
