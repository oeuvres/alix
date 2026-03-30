package com.github.oeuvres.alix.lucene;

import java.io.IOException;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.spans.OffsetsCollector;


/**
 * Receives streamed query results from a walker.
 *
 * <p>This contract is intentionally broader than spans alone: the same listener can be
 * implemented once per output format (HTML, JSON…) and reused across different walker types.</p>
 *
 * <p>Methods are called in this lifecycle order for each walk:</p>
 * <ol>
 *   <li>{@link #start(SpanQuery, Query, int)}</li>
 *   <li>For each matching document:
 *     <ol>
 *       <li>{@link #wantsMoreDocs()} — returning {@code false} stops traversal</li>
 *       <li>{@link #startDoc(int)}</li>
 *       <li>{@link #span(OffsetsCollector)} — once per span match, returning {@code false}
 *           stops span enumeration for this document</li>
 *       <li>{@link #endDoc(int)}</li>
 *     </ol>
 *   </li>
 *   <li>{@link #end(boolean)} — {@code completed} is {@code true} if the index was fully
 *       exhausted, {@code false} if {@link #wantsMoreDocs()} cut traversal short</li>
 * </ol>
 */
public abstract class ResultsListener
{


    /**
     * Polled before the walker enters each matching document.
     * Return {@code false} to stop traversal; {@link #end(boolean) end(false)} will be called.
     */
    abstract public boolean wantsMoreDocs();

    /**
     * Called before the spans of a matching document are emitted.
     *
     * @param docId global Lucene doc ID
     */
    abstract public void startDoc(int docId) throws IOException;

    /**
     * Called for each span match within the current document, in start-position order.
     *
     * <p>The {@link OffsetsCollector} instance is reused across calls; do not retain it.</p>
     *
     * <p>Return {@code false} to stop span enumeration for this document; the walker still
     * calls {@link #endDoc(int)} and then moves to the next document.</p>
     *
     * @param collector the current span's leaf terms, in ascending token-position order
     * @return {@code true} to continue to the next span
     */
    abstract public boolean span(OffsetsCollector collector) throws IOException;

    /**
     * Called after all spans of a document have been emitted (or span enumeration was
     * stopped early by {@link #span} returning {@code false}).
     */
    abstract public void endDoc(final int spanCount) throws IOException;

}
