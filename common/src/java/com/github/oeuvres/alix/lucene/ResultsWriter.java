package com.github.oeuvres.alix.lucene;

import java.io.IOException;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.spans.OffsetsCollector;


/**
 * Writes streamed query results.
 *
 * <p>This contract is intentionally broader than spans alone: the same result structure
 * can be reused by other walkers or result producers.</p>
 *
 * <p>The writer may stop traversal early by returning {@code false} from
 * {@link #startDoc(int)} or {@link #span(SpanWalker.SpanMatch)}.</p>
 */
public abstract class ResultsWriter
{
    /** Results displayed are from one field only */
    private final String field;
    /** Count of docs with at least one value for this field */
    private final int docs;
    /** Exact total number of matching documents, or {@code -1} if unknown */
    private int hits = -1;
    /** Count of docs visited during hits traversal */
    private int visitedDocs = 0;
    /** When relevant, count of spans actually emitted during traversal. */
    public long visitedSpans = 0L;

    /**
     * Exact total number of matching spans, or {@code -1} if unknown.
     *
     * <p>This walker does not precompute it.</p>
     */
    public long totalSpansExact = -1L;



    
    ResultsWriter(final String field, final int docs) {
        this.field = field;
        this.docs = docs;
    }
    
    public int hits() {
        return hits;
    }

    public void hits(final int hits) {
        this.hits = hits;
    }

    /**
     * Called once at the end.
     *
     * @param stats final stats for what was actually visited/emitted
     * @param completed {@code true} if traversal reached the end naturally,
     *                  {@code false} if the writer stopped it early
     */
    abstract public void end(boolean completed) throws IOException;

    /**
     * Called after the current document has been fully emitted.
     *
     * @param docId global Lucene doc ID
     */
    abstract public void endDoc(int docId) throws IOException;

    public void reset() {
        hits = -1;
        visitedDocs = 0;
        visitedSpans = 0;
    }

    /**
     * Called for each matching span of the current document.
     * @return {@code true} to continue, {@code false} to stop traversal
     */
    abstract public boolean span(OffsetsCollector match) throws IOException;

    /**
     * Called once before traversal starts.
     *
     * @param spanQuery rewritten span query actually used by the walker
     * @param filterQuery rewritten filter query, or {@code null} if none
     * @param stats initial stats; exact document count may already be known,
     *              exact span count is normally unknown here
     */
    abstract public void start(SpanQuery spanQuery, Query filterQuery, final int hits) throws IOException;

    /**
     * Called before emitting spans of one matching document.
     *
     * @param docId global Lucene doc ID
     */
    abstract public void startDoc(final int docId) throws IOException;

    public int visitedDocs() {
        return visitedDocs;
    }

    public void visitedDocsAdd(final int delta) {
        visitedDocs += delta;
    }
    
    public long visitedSpans() {
        return visitedSpans;
    }

    public void visitedSpansAdd(final long delta) {
        visitedSpans += delta;
    }
    
    /**
     * Called between documents.
     * Return false to stop before opening the next document.
     */
    abstract public boolean wantsMoreDocs();

}