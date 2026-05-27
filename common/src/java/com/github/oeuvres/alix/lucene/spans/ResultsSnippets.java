package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.io.Writer;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
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
 * <p>
 * Each accepted document is emitted as one {@code <article>} block: an opening tag
 * carrying the document id, an optional {@code 
 * <h2>} heading linking to the full
 * document, and up to {@link #snipLimit()} concordance lines. Each line is rendered
 * as an {@code 
 * <li>} with a left context, one or more {@code <mark>} pivots,
 * inter-term text, and a right context. The {@code 
 * <li>} items are emitted directly
 * inside the {@code <article>}; the caller is responsible for wrapping them in an
 * outer {@code 
 * <ol>
 * } or {@code 
 * <ul>
 * } if a conforming HTML5 list element is required.
 * </p>
 * <p>
 * Snippets selected for rendering are the top {@link #snipLimit()} by accumulated
 * term-weight score over a window of {@link #ctx()} positions on each side of the
 * snippet's position range. They are emitted in score-descending order, ties broken
 * by snippet ordinal (the natural tie-breaker of {@link TopArray}). Scoring uses a
 * {@link TermRail} and a parallel {@code double[]} weight array sized by the term
 * universe; term ids outside the weight array are silently skipped, and each term
 * contributes at most once per snippet thanks to a per-snippet dedup epoch.
 * </p>
 * <p>
 * {@link #docOpen(int)} and {@link #docClose(int)} are called unconditionally for
 * every {@link #docSnippets(int, Snippets)} invocation, even when the document
 * yields no snippets. Empty articles are intentional: a document with zero snippets
 * is an upstream {@link SpanWalker} invariant violation worth surfacing rather than
 * hiding. The same logic applies to a snippet with zero matches in OFFSETS mode,
 * which will surface as an {@link IndexOutOfBoundsException} from
 * {@link Snippets#matchStartOffset(int)} inside {@code print}.
 * </p>
 * <p>
 * Required {@link Snippets} mode: {@link Snippets.Usage#OFFSETS}. Document-level
 * pagination ({@code docLimit}) is the responsibility of {@link SpanWalker}, not
 * this renderer. This class is not thread-safe.
 * </p>
 */
public class ResultsSnippets implements SnippetsConsumer
{
    private final Detagger detagger = new Detagger(Set.of("i", "em"));
    // Required at construction
    private final Writer writer;
    private final Locale locale;
    private final int snipLimit;
    private final StoredFields storedFields;
    // Build at construction
    private final ResourceBundle messages;
    private final NumberFormat numForm;
    // Optional other params
    private TermRail rail;
    private double[] termWeights;
    private int[] termDedup;
    private final TopArray topSnips;
    private int ctx = 10;
    private String doclineField = "docline";
    private String contentField = "content";
    private String urlFormat = "";
    // Current document cached
    private int cachedDocId = -1;
    private Document doc;
    private String content;
    private String docname;
    /** Snippet counter */
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
     * @throws NullPointerException if any non-primitive argument is {@code null}
     */
    public ResultsSnippets(
        final Writer writer,
        final StoredFields storedFields,
        final int snipLimit,
        final Locale locale
    ) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.storedFields = Objects.requireNonNull(storedFields, "storedFields");
        
        this.snipLimit = snipLimit;
        if (snipLimit > 0) {
            this.topSnips = new TopArray(snipLimit);
        } else {
            this.topSnips = null; // should be OK for docSnippets()
        }
        if (locale == null) this.locale = Locale.getDefault();
        else this.locale = locale;
        this.numForm = NumberFormat.getInstance(this.locale);
        this.messages = ResourceBundle.getBundle("com.github.oeuvres.alix.common.messages", this.locale);
    }

    /**
     * Returns the number of words of context shown on each side of a pivot. Also
     * used as the position-window radius for snippet scoring.
     *
     * @return context width in words
     */
    public int ctx() {
        return ctx;
    }

    /**
     * Sets the number of words of context shown on each side of a pivot.
     *
     * @param ctx context width in words
     * @return this instance
     */
    public ResultsSnippets ctx(
        final int ctx
    ) {
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
    public void docClose(
        final int docId
    )
        throws IOException {
        writer.append("</article>\n\n");
        writer.flush();
    }

    /**
     * Returns the name of the stored field used as the document heading.
     *
     * @return stored-field name, or {@code null} when the heading is suppressed
     */
    public String doclineFieldName() {
        return doclineField;
    }

    /**
     * Sets the name of the stored field used as the document heading. When
     * {@code null}, no {@code 
     * <h2>} is emitted.
     *
     * @param doclineField stored-field name, or {@code null}
     * @return this instance
     */
    public ResultsSnippets doclineField(
        final String doclineField
    ) {
        this.doclineField = doclineField;
        return this;
    }
    
    /**

     */
    public ResultsSnippets contentField(
        final String contentField
    ) {
        this.contentField = contentField;
        return this;
    }

    /**
     * Emits the opening {@code <article>} tag and the optional {@code 
     * <h4>}
     * heading. May be called directly when reusing this renderer to produce
     * query-less document cards.
     *
     * @param css a special css className
     * @param docId Lucene document id
     * @throws IOException if the writer or stored-fields access fails
     */
    public void docOpen(
        final int docId,
        String css
    )
        throws IOException {
        ensureDoc(docId);
        if (css == null || css.isBlank()) {
            css = "";
        } else {
            css = " " + css;
        }
        ;
        doc = storedFields.document(docId);
        docname = doc.get(ALIX_ID);
        writer.append("<article").append(" id=\"").append(docname).append("\"").append(" data-docid=\"")
                .append(String.valueOf(docId)).append("\"").append(" class=\"result").append(css).append("\"")
                .append(">\n");

        String url = String.format(urlFormat, docname);
        if (doclineField != null) {
            final String docline = doc.get(doclineField);
            if (docline != null) {
                writer
                .append("<h4")
                .append(" class=\"result-title\"").append(">\n")
                .append("<a")
                .append(" href=\"").append(url).append("\"")
                .append(" draggable=\"false\"")
                .append(" class=\"selectable\"")
                .append(">")
                .append(docline)
                .append("</a>\n")
                .append("</h4>\n");
            }
        }
    }

    /**
     * Renders one document's accepted snippets as an {@code <article>} block.
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
    public void docSnippets(
        final int docId,
        final Snippets snippets
    )
        throws IOException {
        ensureDoc(docId);
        docOpen(docId, "hassnippets");
        final int snipCount = snippets.count();
        if (snipCount == 0) {
        }
        else if (snipCount > snipLimit) {
            // show all snippets as a link
            writer
            .append("<p class=\"snippets-count\">")
            .append(numForm.format(snipLimit))
            .append(" / ")
            .append("<a")
            .append(" class=\"snippets-more\"")
            .append(" href=\"").append(String.format(urlFormat, docname)).append("&amp;snippets=-1")
            .append(">")
            .append(numForm.format(snipLimit))
            .append("</a> ")
            .append(messages.getString("results.snippets"))
            .append("</p>");
        }
        snippets(docId, snippets);
        docClose(docId);
    }
    
    /**
     * TODO Javadoc
     * @param docId
     * @param snippets
     * @throws IOException
     */
    public void snippets(final int docId, Snippets snippets) throws IOException
    {
        ensureDoc(docId);
        final int snipCount = snippets.count();
        if (content == null) {
            writer.append("<!-- No text stored for field: '" + contentField + "' -->");
        } else if (snipCount <= 0) {
            writer.append("<!-- No snippets found -->");
        } else if (snipLimit < 0) {
            // list all snippets in document order
            writer.append("<ol class=\"snippets\">\n");
            for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
                print(snippets, snipOrd);
            }
            writer.append("</ol>\n");
        } else if (snipLimit == 0 || snipCount == 0) {

        } else if (snipCount <= snipLimit) {
            // no sort
            writer.append("<ol class=\"snippets\">\n");
            for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
                print(snippets, snipOrd);
            }
            writer.append("</ol>\n");
        } else {
            topSnips.clear();
            for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
                final int startPos = Math.max(0, snippets.snipStartPosition(snipOrd) - ctx);
                final int endPos = snippets.snipEndPosition(snipOrd) + ctx;
                topSnips.push(snipOrd, scoreSnippet(docId, startPos, endPos));
            }
            writer.append("<ol class=\"snippets\">\n");
            for (final TopArray.IdScore pair : topSnips) {
                print(snippets, pair.id());
            }
            writer.append("</ol>\n");
        }

    }

    /**
     * {@link String#format(String, Object...)} where %s is the id 
     * of the document stored as "alix.id".
     *
     * @param urlFormat URL pattern used for results
     * @return this instance
     */
    public ResultsSnippets urlFormat(
        final String urlFormat
    ) {
        this.urlFormat = urlFormat;
        return this;
    }



    /**
     * Returns the maximum number of snippets rendered per document.
     *
     * @return snippet cap; {@code 0} means no snippet lines are rendered
     */
    public int snipLimit() {
        return snipLimit;
    }

    /**
     * TODO JavaDoc
     * @param rail
     */
    public ResultsSnippets rail(
        final TermRail rail
    ) {
        this.rail = rail;
        return this;
    }
    
    /**
     * TODO JavaDoc 
     * @param termWeights
     */
    public ResultsSnippets termWeights(
        final double[] termWeights
    ) {
        this.termWeights = Objects.requireNonNull(termWeights, "termWeights");
        return this;
    }

    /**
     * TODO Javadoc
     * @param docId
     * @throws IOException
     */
    private void ensureDoc(final int docId) throws IOException {
        if (cachedDocId == docId) return;
        this.doc     = storedFields.document(docId);
        this.docname = doc.get(ALIX_ID);
        this.content = doc.get(contentField);
        this.cachedDocId = docId;
    }

    /**
     * Emits one concordance line as an {@code 
     * <li>}: left context, pivot
     * {@code <mark>}s with interleaved text, right context.
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
    private void print(
        final Snippets snippets,
        final int snipOrd
    )
        throws IOException {
        final int leftMatchOrd = snippets.snipStartMatch(snipOrd);
        final int rightMatchOrd = snippets.snipEndMatch(snipOrd);
        final int leftMatchStartOffset = snippets.matchStartOffset(leftMatchOrd);
        final int rightMatchEndOffset = snippets.matchEndOffset(rightMatchOrd);

        final int snipAnchor = snipOrd + 1;
        final String url = urlFormat.formatted(docname) + "#snippet-" + snipAnchor;
        writer.append("<li").append(" class=\"snippet\"").append(" data-href=\"").append(url).append("\"").append(">")
                .append("<p>");

        final int leftOffset = Markup.leftBoundary(content, leftMatchStartOffset, ctx, -1);
        detagger.detag(writer, content, leftOffset, leftMatchStartOffset);

        for (int matchOrd = leftMatchOrd; matchOrd <= rightMatchOrd; matchOrd++) {
            if (matchOrd != leftMatchOrd) {
                detagger.detag(
                        writer, content, snippets.matchEndOffset(matchOrd - 1), snippets.matchStartOffset(matchOrd)
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
        writer.append("</p>").append("\n<a").append(" href=\"").append(url).append("\"")
                .append(" class=\"snippet-open\"").append(">→</a>");
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
    private double scoreSnippet(
        final int docId,
        final int startPosition,
        final int endPosition
    ) {
        
        if (rail == null || termWeights == null) {
            final List<String> message = new ArrayList<>(2);
            if (rail == null)
                message.add("Score the snippets needs a TermRail to get terms to score, see #rail(TermRail).");
            if (termWeights == null)
                message.add("Score the snippets needs a by term score array, see #termWeights(double[]).");
            throw new IllegalArgumentException(String.join("\n", message));
        }
        if (termDedup == null) {
            termDedup = new int[termWeights.length];
        }
        final double[] acc = { 0d };
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
