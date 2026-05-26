package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.io.Writer;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;

import com.github.oeuvres.alix.lucene.spans.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.util.Detagger;
import com.github.oeuvres.alix.util.Markup;
import com.github.oeuvres.alix.util.TopArray;

import static com.github.oeuvres.alix.common.Names.*;

/**
 * A {@link SnippetsConsumer} that writes span search results as an HTML fragment.
 *
 * <p>
 * Each accepted document is emitted as one {@code <article>} block: an opening tag
 * carrying the document id, an optional {@code <h2>} heading linking to the full
 * document, and up to {@link #snipLimit()} concordance lines. Each line is rendered
 * as an {@code <li>} with a left context, one or more {@code <mark>} pivots,
 * inter-term text, and a right context. The {@code <li>} items are emitted directly
 * inside the {@code <article>}; the caller is responsible for wrapping them in an
 * outer {@code <ol>} or {@code <ul>} if a conforming HTML5 list element is required.
 * </p>
 *
 * <p>
 * Snippets selected for rendering are the top {@link #snipLimit()} by accumulated
 * term-weight score over a window of {@link #ctx()} positions on each side of the
 * snippet's position range. They are emitted in score-descending order, ties broken
 * by snippet ordinal (the natural tie-breaker of {@link TopArray}). Scoring uses a
 * {@link TermRail} and a parallel {@code double[]} weight array sized by the term
 * universe; term ids outside the weight array are silently skipped, and each term
 * contributes at most once per snippet thanks to a per-snippet dedup epoch.
 * </p>
 *
 * <p>
 * {@link #docOpen(int)} and {@link #docClose(int)} are called unconditionally for
 * every {@link #docSnippets(int, Snippets)} invocation, even when the document
 * yields no snippets. Empty articles are intentional: a document with zero snippets
 * is an upstream {@link SpanWalker} invariant violation worth surfacing rather than
 * hiding. The same logic applies to a snippet with zero matches in OFFSETS mode,
 * which will surface as an {@link IndexOutOfBoundsException} from
 * {@link Snippets#matchStartOffset(int)} inside {@code print}.
 * </p>
 *
 * <p>
 * Required {@link Snippets} mode: {@link Snippets.Usage#OFFSETS}. Document-level
 * pagination ({@code docLimit}) is the responsibility of {@link SpanWalker}, not
 * this renderer. This class is not thread-safe.
 * </p>
 */
public class ResultsSnippets implements SnippetsConsumer
{
    private final String contentFieldName;
    private final Detagger detagger = new Detagger(Set.of("i", "em"));
    private final TermRail rail;
    private final int snipLimit;
    private final StoredFields storedFields;
    private final int[] termDedup;
    private final double[] termWeights;
    private final TopArray topSnips;
    private final Writer writer;
    private final ResourceBundle messages;

    private int ctx = 10;
    private String doclineFieldName = "docline";
    private String hrefBase = "";
    private String hrefExt = "";
    private String hrefSearch = "";

    private String content;
    private Document doc;
    private String id;
    private int snippetId;

    /**
     * Creates a renderer.
     *
     * @param writer destination for the HTML fragment
     * @param storedFields access to stored document fields
     * @param contentFieldName stored field holding the HTML source from which snippets
     * are extracted
     * @param rail term rail used to score snippet windows
     * @param termWeights per-term weights, indexed by term id; sized to the term
     * universe of {@code rail}. Term ids outside this range are silently ignored.
     * @param snipLimit maximum number of snippets per document; {@code 0} disables
     * snippet rendering while still emitting article shells
     * @throws IllegalArgumentException if {@code snipLimit < 0}
     * @throws NullPointerException if any non-primitive argument is {@code null}
     */
    public ResultsSnippets(
        final Writer writer,
        final StoredFields storedFields,
        final String contentFieldName,
        final TermRail rail,
        final double[] termWeights,
        final int snipLimit,
        final Locale locale
    ) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.storedFields = Objects.requireNonNull(storedFields, "storedFields");
        this.contentFieldName = Objects.requireNonNull(contentFieldName, "contentFieldName");
        this.rail = Objects.requireNonNull(rail, "rail");
        this.termWeights = Objects.requireNonNull(termWeights, "termWeights");
        this.snipLimit = snipLimit;
        this.termDedup = new int[termWeights.length];
        if (snipLimit > 0) {
            this.topSnips = new TopArray(snipLimit);
        }
        else {
            this.topSnips = null; // should be OK for docSnippets()
        }
        this.messages = ResourceBundle.getBundle("com.github.oeuvres.alix.common.messages", locale);
    }

    /**
     * Returns the number of words of context shown on each side of a pivot. Also
     * used as the position-window radius for snippet scoring.
     *
     * @return context width in words
     */
    public int ctx()
    {
        return ctx;
    }

    /**
     * Sets the number of words of context shown on each side of a pivot.
     *
     * @param ctx context width in words
     * @return this instance
     */
    public ResultsSnippets ctx(final int ctx)
    {
        this.ctx = ctx;
        return this;
    }

    /**
     * Emits the closing {@code </article>} tag and flushes the writer. May be
     * called directly when reusing this renderer to produce query-less document
     * cards without snippet lines.
     *
     * @param docId Lucene document id of the document being closed; currently unused
     * but kept for symmetry with {@link #docOpen(int)}
     * @throws IOException if the writer fails
     */
    public void docClose(final int docId) throws IOException
    {
        writer.append("</article>\n\n");
        writer.flush();
    }

    /**
     * Returns the name of the stored field used as the document heading.
     *
     * @return stored-field name, or {@code null} when the heading is suppressed
     */
    public String doclineFieldName()
    {
        return doclineFieldName;
    }

    /**
     * Sets the name of the stored field used as the document heading. When
     * {@code null}, no {@code <h2>} is emitted.
     *
     * @param doclineFieldName stored-field name, or {@code null}
     * @return this instance
     */
    public ResultsSnippets doclineFieldName(final String doclineFieldName)
    {
        this.doclineFieldName = doclineFieldName;
        return this;
    }

    /**
     * Emits the opening {@code <article>} tag and the optional {@code <h2>}
     * heading. May be called directly when reusing this renderer to produce
     * query-less document cards.
     *
     * @param docId Lucene document id
     * @throws IOException if the writer or stored-fields access fails
     */
    public void docOpen(final int docId, String css) throws IOException
    {
        if (css == null || css.isBlank()) {css=""; }
        else { css = " " + css;};
        doc = storedFields.document(docId);
        id = doc.get(ALIX_ID);
        writer.append("<article")
        .append(" id=\"").append(id).append("\"")
        .append(" data-docid=\"").append(String.valueOf(docId)).append("\"")
        .append(" class=\"result").append(css).append("\"")
        .append(">\n");

        String url = hrefBase + id + hrefExt + hrefSearch;
        if (doclineFieldName != null) {
            final String docline = doc.get(doclineFieldName);
            if (docline != null) {
                writer.append("<h4")
                .append(" class=\"result-title\"")
                .append(">\n")
                .append("<a")
                .append(" href=\"").append(url).append("\"")
                .append(" draggable=\"false\"")
                .append(" class=\"selectable\"")
                .append(">").append(docline)
                .append("</a>\n")
                .append("</h4>\n");
            }
        }
    }

    /**
     * Renders one document's accepted snippets as an {@code <article>} block.
     *
     * <p>
     * {@link #docOpen(int)} and {@link #docClose(int)} run unconditionally. When the
     * content field is missing or {@link #snipLimit()} is {@code 0}, the scoring
     * loop is skipped and only the article shell is emitted.
     * </p>
     *
     * @param docId Lucene document id
     * @param snippets finished snippets for {@code docId}; must be in
     * {@link Snippets.Usage#OFFSETS}
     * @throws IOException if the writer or stored-fields access fails
     */
    @Override
    public void docSnippets(final int docId, final Snippets snippets) throws IOException
    {
        final int snipCount = snippets.snips4doc();
        docOpen(docId, "hassnippets");
        content = doc.get(contentFieldName);
        if (content == null) {
            writer.append("<!-- No text stored for field: '" + contentFieldName + "' -->");
        }
        else if (snipCount <= 0) {
            writer.append("<!-- No snippets found -->");
        }
        else if (snipLimit <  0) {
            // list all snippets in document order
            writer.append("<ol class=\"snippets\">\n");
            for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
                print(snippets, snipOrd);
            }
            writer.append("</ol>\n");
        }
        else if (snipLimit == 0 || snipCount == 0) {
            
        }
        else if (snipCount <= snipLimit) {
            // no sort
            writer.append("<ol class=\"snippets\">\n");
            for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
                print(snippets, snipOrd);
            }
            writer.append("</ol>\n");
        }
        else {
            topSnips.clear();
            for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
                final int startPos = Math.max(0, snippets.snipStartPosition(snipOrd) - ctx);
                final int endPos = snippets.snipEndPosition(snipOrd) + ctx;
                topSnips.push(snipOrd, scoreSnippet(docId, startPos, endPos));
            }
            final String key;
            if (snipCount == 1) {
                key = "results.snippets.count.one";
            }
            else {
                key = "results.snippets.count";
            }
            String html = MessageFormat.format(messages.getString(key), snipLimit, snipCount);
            writer.append("<p>")
            .append(html)
            .append(" <button")
            .append("")
            .append(">")
            .append(" </button>")
            .append("</p>");
            writer.append("<ol class=\"snippets\">\n");
            for (final TopArray.IdScore pair : topSnips) {
                print(snippets, pair.id());
            }
            writer.append("</ol>\n");
        }
        docClose(docId);
    }

    /**
     * Returns the URL prefix prepended to document ids when building href values.
     *
     * @return URL prefix
     */
    public String hrefBase()
    {
        return hrefBase;
    }

    /**
     * Sets the URL prefix prepended to document ids.
     *
     * @param hrefBase URL prefix
     * @return this instance
     */
    public ResultsSnippets hrefBase(final String hrefBase)
    {
        this.hrefBase = hrefBase;
        return this;
    }

    /**
     * Returns the URL suffix appended to document ids when building href values.
     *
     * @return URL suffix
     */
    public String hrefExt()
    {
        return hrefExt;
    }

    /**
     * Sets the URL suffix appended to document ids.
     *
     * @param hrefExt URL suffix
     * @return this instance
     */
    public ResultsSnippets hrefExt(final String hrefExt)
    {
        this.hrefExt = hrefExt;
        return this;
    }

    /**
     * Returns the query-string fragment appended to all generated hrefs.
     *
     * @return query-string fragment
     */
    public String hrefSearch()
    {
        return hrefSearch;
    }

    /**
     * Sets the query-string fragment appended to all generated hrefs.
     *
     * @param hrefSearch query-string fragment
     * @return this instance
     */
    public ResultsSnippets hrefSearch(final String hrefSearch)
    {
        this.hrefSearch = hrefSearch;
        return this;
    }

    /**
     * Returns the maximum number of snippets rendered per document.
     *
     * @return snippet cap; {@code 0} means no snippet lines are rendered
     */
    public int snipLimit()
    {
        return snipLimit;
    }

    /**
     * Emits one concordance line as an {@code <li>}: left context, pivot
     * {@code <mark>}s with interleaved text, right context.
     *
     * <p>
     * Requires the snippet to contain at least one match. A snippet without
     * matches in OFFSETS mode is an upstream invariant violation and will surface
     * as an {@link IndexOutOfBoundsException} from
     * {@link Snippets#matchStartOffset(int)} via the {@code -1} returned by
     * {@link Snippets#snipStartMatch(int)}; this is intentional.
     * </p>
     *
     * @param snippets finished snippets in OFFSETS mode
     * @param snipOrd snippet ordinal in {@code [0, snippets.snips4doc())}
     * @throws IOException if the writer fails
     */
    private void print(final Snippets snippets, final int snipOrd) throws IOException
    {
        final int leftMatchOrd = snippets.snipStartMatch(snipOrd);
        final int rightMatchOrd = snippets.snipEndMatch(snipOrd);
        final int leftMatchStartOffset = snippets.matchStartOffset(leftMatchOrd);
        final int rightMatchEndOffset = snippets.matchEndOffset(rightMatchOrd);

        final int snipAnchor = snipOrd + 1;
        final String url = hrefBase + id + hrefExt + hrefSearch + "#snippet-" + snipAnchor;
        writer
            .append("<li")
            .append(" class=\"snippet\"")
            .append(" data-href=\"").append(url).append("\"")
            .append(">")
            .append("<p>");
        

        final int leftOffset = Markup.leftBoundary(content, leftMatchStartOffset, ctx, -1);
        detagger.detag(writer, content, leftOffset, leftMatchStartOffset);

        for (int matchOrd = leftMatchOrd; matchOrd <= rightMatchOrd; matchOrd++) {
            if (matchOrd != leftMatchOrd) {
                detagger.detag(
                    writer,
                    content,
                    snippets.matchEndOffset(matchOrd - 1),
                    snippets.matchStartOffset(matchOrd)
                );
            }
            final int startOffset = snippets.matchStartOffset(matchOrd);
            final int endOffset = snippets.matchEndOffset(matchOrd);
            writer.append("<mark class=\"hit pivot\">");
            writer.write(content, startOffset, endOffset - startOffset);
            writer.append("</mark>");
        }

        final int rightOffset = Markup.rightBoundary(content, rightMatchEndOffset, ctx, -1);
        detagger.detag(writer, content, rightMatchEndOffset, rightOffset);
        // gutter snippet link
        writer.append("</p>")
        .append("\n<a")
        .append(" href=\"").append(url).append("\"")
        .append(" class=\"snippet-open\"")
        .append(">→</a>");
        writer.append("</li>\n");
    }

    /**
     * Computes the score of a snippet window as the sum of term weights for the
     * distinct terms appearing in {@code [startPosition, endPosition)} on the rail
     * for {@code docId}. Each term contributes at most once per snippet thanks to
     * a monotonically incremented epoch counter that stamps {@link #termDedup}.
     *
     * @param docId Lucene document id
     * @param startPosition first position to include, inclusive
     * @param endPosition first position to exclude
     * @return accumulated score
     */
    private double scoreSnippet(final int docId, final int startPosition, final int endPosition)
    {
        final double[] acc = {0d};
        snippetId++;
        rail.scanWindow(docId, startPosition, endPosition, termId -> {
            if (termId < termDedup.length && termDedup[termId] != snippetId) {
                termDedup[termId] = snippetId;
                acc[0] += termWeights[termId];
            }
        });
        return acc[0];
    }
}
