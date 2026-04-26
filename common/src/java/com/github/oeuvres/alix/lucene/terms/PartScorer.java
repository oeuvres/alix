package com.github.oeuvres.alix.lucene.terms;

public class PartScorer
{
    public static double g2PartsDominant(
        final long[] partTermFreq,
        final long[] partTokens,
        final int targetPart)
    {
        if (partTermFreq.length != partTokens.length) {
            throw new IllegalArgumentException("partTermFreq.length != partTokens.length");
        }
        if (targetPart < 0 || targetPart >= partTokens.length) {
            throw new IllegalArgumentException("targetPart out of range: " + targetPart);
        }
        
        final long targetTermFreq = partTermFreq[targetPart];
        final long targetTokens = partTokens[targetPart];
        if (targetTokens <= 0L)
            return Double.NaN;
        if (targetTermFreq < 0L || targetTermFreq > targetTokens)
            return Double.NaN;
        
        double minScore = Double.POSITIVE_INFINITY;
        boolean seenCompetitor = false;
        
        for (int p = 0; p < partTokens.length; p++) {
            if (p == targetPart)
                continue;
            
            final long otherTokens = partTokens[p];
            if (otherTokens <= 0L)
                continue;
            
            final long otherTermFreq = partTermFreq[p];
            if (otherTermFreq < 0L || otherTermFreq > otherTokens)
                return Double.NaN;
            
            final double pairScore = signedG2(
                    targetTermFreq,
                    targetTokens,
                    otherTermFreq,
                    otherTokens);
            
            if (Double.isNaN(pairScore))
                continue;
            
            if (pairScore < minScore) {
                minScore = pairScore;
            }
            seenCompetitor = true;
        }
        
        return seenCompetitor ? minScore : Double.NaN;
    }
    
    /**
     * Signed log-likelihood G² for one term in one part
     * against all other accepted parts.
     *
     * @param partTermFreq term frequency per part for the current term
     * @param partTokens   total token count per part
     * @param targetPart   part to score
     * @return positive if the term is over-represented in targetPart
     */
    public static double signedG2OneVsOther(
        final long[] partTermFreq,
        final long[] partTokens,
        final int targetPart)
    {
        if (partTermFreq.length != partTokens.length) {
            throw new IllegalArgumentException(
                    "partTermFreq.length != partTokens.length");
        }
        if (targetPart < 0 || targetPart >= partTokens.length) {
            throw new IllegalArgumentException("targetPart out of range: " + targetPart);
        }
        
        long allTermCount = 0L;
        long allTokens = 0L;
        
        for (int p = 0; p < partTokens.length; p++) {
            final long tf = partTermFreq[p];
            final long n = partTokens[p];
            
            if (tf < 0L) {
                throw new IllegalArgumentException("negative term freq at part " + p);
            }
            if (n < 0L) {
                throw new IllegalArgumentException("negative token count at part " + p);
            }
            if (tf > n) {
                throw new IllegalArgumentException(
                        "term freq > tokens at part " + p + ": " + tf + " > " + n);
            }
            
            allTermCount += tf;
            allTokens += n;
        }
        
        final long focusTermCount = partTermFreq[targetPart];
        final long focusTokens = partTokens[targetPart];
        
        final long otherTermCount = allTermCount - focusTermCount;
        final long otherTokens = allTokens - focusTokens;
        
        return signedG2(
                focusTermCount,
                focusTokens,
                otherTermCount,
                otherTokens);
    }
    
    /**
     * Signed 2x2 log-likelihood G².
     *
     * focus/other must be disjoint.
     */
    public static double signedG2(
        final long focusTermCount,
        final long focusTokens,
        final long otherTermCount,
        final long otherTokens)
    {
        if (focusTokens <= 0L || otherTokens <= 0L)
            return Double.NaN;
        if (focusTermCount < 0L || otherTermCount < 0L)
            return Double.NaN;
        if (focusTermCount > focusTokens || otherTermCount > otherTokens)
            return Double.NaN;
        
        final long focusNonTermCount = focusTokens - focusTermCount;
        final long otherNonTermCount = otherTokens - otherTermCount;
        
        final long allTokens = focusTokens + otherTokens;
        final long allTermCount = focusTermCount + otherTermCount;
        final long allNonTermCount = focusNonTermCount + otherNonTermCount;
        
        if (allTokens <= 0L || allTermCount <= 0L || allNonTermCount <= 0L) {
            return 0d;
        }
        
        final double eFocusTerm = (double) allTermCount * focusTokens / allTokens;
        final double eOtherTerm = (double) allTermCount * otherTokens / allTokens;
        final double eFocusNonTerm = (double) allNonTermCount * focusTokens / allTokens;
        final double eOtherNonTerm = (double) allNonTermCount * otherTokens / allTokens;
        
        double g2 = 0d;
        g2 += g2Cell(focusTermCount, eFocusTerm);
        g2 += g2Cell(otherTermCount, eOtherTerm);
        g2 += g2Cell(focusNonTermCount, eFocusNonTerm);
        g2 += g2Cell(otherNonTermCount, eOtherNonTerm);
        
        final double focusRate = (double) focusTermCount / focusTokens;
        final double otherRate = (double) otherTermCount / otherTokens;
        
        return focusRate >= otherRate ? g2 : -g2;
    }
    
    private static double g2Cell(final long observed, final double expected)
    {
        if (observed <= 0L || expected <= 0d)
            return 0d;
        return 2d * observed * Math.log(observed / expected);
    }
}
