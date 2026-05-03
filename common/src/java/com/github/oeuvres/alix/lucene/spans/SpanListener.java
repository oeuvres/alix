package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;

/**
 * Receives streamed span-match events from a {@link SpanWalker}.
 *
 * <p>Lifecycle, per call to {@link SpanWalker#walk(int)}:</p>
 * <ol>
 *   <li>{@link #start()}</li>
 *   <li>For each matching document, in natural index order:
 *     <ol>
 *       <li>{@link #wantsMoreDocs()} — return {@code false} to stop the walk</li>
 *       <li>{@link #startDoc(int)}</li>
 *       <li>{@link #span(SpanMatch)} — once per match; return {@code false} to skip the remaining matches of the current document</li>
 *       <li>{@link #endDoc(int)}</li>
 *     </ol>
 *   </li>
 *   <li>{@link #end(boolean)}</li>
 * </ol>
 *
 * <p>The contract is shared across output formats (HTML, JSON, term aggregation…) and across walker types.</p>
 */
public interface SpanListener
{
    /**
     * Called once at the end of every walk, after the last document.
     *
     * @param exhausted {@code true} if the index was traversed to its end; {@code false} if {@link #wantsMoreDocs()} cut the walk short
     */
    default void end(final boolean exhausted) throws IOException {}

    /**
     * Called after all matches of the current document have been delivered to {@link #span(SpanMatch)}, or after {@code span} returned {@code false}.
     *
     * @param spanCount total number of matches in the document, including those skipped after {@code span} returned {@code false}
     */
    void endDoc(int spanCount) throws IOException;

    /**
     * Called once per match of the current document, in ascending start-position order.
     *
     * <p>The {@link SpanMatch} is reused across calls; do not retain a reference.</p>
     *
     * @param collector match data (term offsets, ordinal within the document) for the current span
     * @return {@code true} to continue receiving matches in the current document; {@code false} to skip the remainder
     */
    boolean span(SpanMatch collector) throws IOException;

    /**
     * Called once at the start of every walk, before any document is visited.
     */
    default void start() throws IOException {}
    
    /**
     * Called before the matches of a document are delivered.
     *
     * @param docId global Lucene document id
     */
    void startDoc(int docId) throws IOException;

    /**
     * Polled before each candidate document is opened.
     *
     * @return {@code true} to continue, {@code false} to stop the walk; {@link #end(boolean) end(false)} is then called
     */
    default boolean wantsMoreDocs()
    {
        return true;
    }
}