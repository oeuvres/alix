package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

public final class ProbAttributeImpl extends AttributeImpl implements ProbAttribute {
    private static double UNKNOWN = -1;
    private double prob = UNKNOWN;
    
    

    @Override
    public double getProb() {
        return prob;
    }

    @Override
    public void setProb(final double prob) {
        this.prob = prob;
    }

    @Override
    public void clear() {
        prob = UNKNOWN;
    }

    @Override
    public void copyTo(final AttributeImpl target) {
        ((ProbAttribute) target).setProb(prob);
    }

    @Override
    public void reflectWith(final AttributeReflector reflector) {
        reflector.reflect(ProbAttribute.class, "prob", prob);
    }
}
