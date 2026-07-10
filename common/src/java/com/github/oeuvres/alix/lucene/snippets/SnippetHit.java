package com.github.oeuvres.alix.lucene.snippets;

/**
 * ??
 * SnippetHit life cycle
 * 1. Loop spanQuery results in docId order, merge spans, create SnippetHit with enough to score
 * docId, startPosition = firstSpanStartPosition - contextLeft, endPosition = endSpanLastPosition + contextRight 
 * 2. Scorer set score from the termIds between start and en position
 * 3. Consumer select and sort Snippet(s) by score
 * 4. Consumer display top snippet(s)
 * 
 */
public class SnippetHit
{
    // needed to score
    int docId;
    int startPosition;
    int endPosition;
    // score
    double score;
    // is it needed to collect all offsets when most of Snippets will not diplayed?
    int startOffset;
    int endOffset;
    int matchCount;
    int[] matchOffsets; // packed start/end pairs, copied only for kept top snippets    
}
