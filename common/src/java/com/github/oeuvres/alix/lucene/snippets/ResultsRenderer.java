package com.github.oeuvres.alix.lucene.snippets;

import java.io.Closeable;
import java.io.IOException;

/**
 * Output-format-neutral sink for search results. The four sort modes of
 * {@code OpResults} drive this contract identically; only the concrete
 * implementation ({@code HtmlResults} or {@code DocxResults}) differs.
 *
 * <p>Document-centric modes (by date, by document score) call
 * {@link #docSnippets(int, DocSnippets)} once per document. The snippet-centric
 * mode (by snippet relevance) treats each ranked snippet as its own one-line
 * document: {@link #docOpen(int, String)}, {@link #snippet(int, SnippetView)},
 * {@link #docClose(int)}.</p>
 *
 * <p>The renderer owns stored-field access, context width and the detagger, so
 * it can build a {@link SnippetView} internally from a {@link DocSnippets}.
 * Implementations are stateful and not thread-safe. {@link #close()} finalises
 * the output (for docx, it streams the filled package; for HTML it is a no-op or
 * a flush).</p>
 */
public interface ResultsRenderer extends Closeable {
    /**
     * Closes the current document block.
     *
     * @param docId Lucene document id
     * @throws IOException on write failure
     */
    void docClose(int docId) throws IOException;

    /**
     * Opens a document block and emits its heading.
     *
     * @param docId Lucene document id
     * @param kind  extra styling hint (for example {@code "hassnippets"} or
     *              {@code "result-snippet"}); may be {@code null}
     * @throws IOException on write failure
     */
    void docOpen(int docId, String kind) throws IOException;

    /**
     * Renders a whole document: heading, optional count, its selected snippets,
     * and the closing block.
     *
     * @param docId    Lucene document id
     * @param snippets finished snippets in OFFSETS mode
     * @throws IOException on write failure
     */
    void docSnippets(int docId, DocSnippets snippets) throws IOException;

    /**
     * Renders one prepared concordance line inside the current document block.
     * Used by the snippet-centric mode, where each ranked hit is its own block.
     *
     * @param docId Lucene document id
     * @param view  prepared line
     * @throws IOException on write failure
     */
    void snippet(int docId, SnippetView view) throws IOException;

    /**
     * Renders the snippet list of a document without heading or block wrapper.
     *
     * @param docId    Lucene document id
     * @param snippets finished snippets in OFFSETS mode
     * @throws IOException on write failure
     */
    void snippets(int docId, DocSnippets snippets) throws IOException;
}
