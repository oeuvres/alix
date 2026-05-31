package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.io.Writer;
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
 * A {@link SnippetsConsumer} that writes span search results as an HTML
 * fragment. The renderer is a stateful, fluent-configured object: required
 * inputs are passed to the constructor, optional inputs via chained setters,
 * then the caller invokes either {@link #docSnippets(int, Snippets)} per
 * document inside a {@link SpanWalker} loop, or the lower-level
 * {@link #docOpen(int, String)} / {@link #snippets(int, Snippets)} /
 * {@link #docClose(int)} triple for finer control.
 *
 * <h2>Snippet selection</h2>
 * <p>
 * When more snippets are produced than {@link #snipLimit()} permits, snippets
 * are ranked by the accumulated weight of distinct terms appearing in a
 * window of {@link #ctx()} positions on each side of the snippet, computed
 * via a {@link TermRail} and a parallel {@code double[]} weight array; the
 * top {@code snipLimit} are emitted in score-descending order, ties broken
 * by snippet ordinal. Term ids outside the weight array are silently
 * skipped, and each term contributes at most once per snippet via a
 * monotonic dedup epoch. Scoring requires both {@link #rail(TermRail)} and
 * {@link #termWeights(double[])} to have been set; {@link #hasScoring()}
 * reports configuration completeness.
 * </p>
 *
 * <h2>Stored-field caching</h2>
 * <p>
 * The renderer caches the {@link Document} and its decoded fields for the
 * current {@code docId}. The cache assumes the bound {@link StoredFields}
 * instance is stable for the lifetime of the renderer; reusing a renderer
 * across an index reopen requires constructing a new instance. The set of
 * loaded stored fields depends on {@link #snipLimit()}: when
 * {@code snipLimit == 0}, the heavy content field is not loaded, since no
 * snippet text will be emitted.
 * </p>
 *
 * <h2>Constraints</h2>
 * <p>
 * Required {@link Snippets} mode: {@link Snippets.Usage#OFFSETS}.
 * Document-level pagination is the responsibility of {@link SpanWalker},
 * not this renderer. This class is not thread-safe.
 * </p>
 */
public class ResultsSnippets implements SnippetsConsumer
{
    private final Detagger detagger = new Detagger(Set.of("i", "em"));
    private final Writer writer;
    private final Locale locale;
    private final int snipLimit;
    private final StoredFields storedFields;
    private final ResourceBundle messages;
    private final NumberFormat numForm;
    private TermRail rail;
    private double[] termWeights;
    private int[] termDedup;
    private final TopArray topSnips;
    private int ctx = 10;
    private String fieldDocline = "docline";
    private String contentField = "content";
    private String urlTemplate = "";
    private int cachedDocId = -1;
    private Set<String> storedFieldNames;
    private Document doc;
    private String content;
    private String docname;
    /** Monotonic stamp for per-snippet term dedup in {@link #scoreSnippet}; not a snippet ordinal. */
    private int dedupEpoch;

    /**
     * Creates a renderer with the four required inputs. Optional
     * configuration (content field name, docline field name, URL template,
     * context width, scoring rail and weights) is applied via the fluent
     * setters.
     *
     * @param writer       destination for the HTML fragment
     * @param storedFields access to stored document fields; must remain
     *                     valid for the lifetime of this renderer
     * @param snipLimit    maximum number of snippets per document;
     *                     {@code 0} emits the article shell without any
     *                     snippet lines and skips loading the content
     *                     field; a negative value emits every snippet in
     *                     document order without ranking
     * @param locale       locale for number formatting and message
     *                     resources; falls back to
     *                     {@link Locale#getDefault()} when {@code null}
     * @throws NullPointerException if {@code writer} or {@code storedFields}
     *         is {@code null}
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
            this.topSnips = null;
        }
        if (locale == null) this.locale = Locale.getDefault();
        else this.locale = locale;
        this.numForm = NumberFormat.getInstance(this.locale);
        this.messages = ResourceBundle.getBundle("com.github.oeuvres.alix.common.messages", this.locale);
    }

    /**
     * Sets the number of words of context shown on each side of a pivot.
     * Also used as the position-window radius for snippet scoring.
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
     * Emits the closing {@code </article>} tag and flushes the writer.
     * Intended to follow a matching {@link #docOpen(int, String)} call; no
     * guard enforces that pairing.
     *
     * @param docId Lucene document id of the document being closed; unused,
     *              kept for symmetry with {@link #docOpen(int, String)}
     * @throws IOException if the writer fails
     */
    public void docClose(
        final int docId
    ) throws IOException {
        writer.append("</article>\n\n");
        writer.flush();
    }

    /**
     * Emits the opening {@code <article>} tag and, when {@code fieldDocline}
     * is set and the field is present on the document, a heading link to
     * the full document. Loads and caches the document if not already
     * cached for {@code docId}.
     *
     * @param docId Lucene document id
     * @param css   extra CSS class names appended to the article; may be
     *              {@code null} or blank
     * @throws IOException if the writer or stored-fields access fails
     */
    public void docOpen(
        final int docId,
        String css
    ) throws IOException {
        ensureDoc(docId);
        if (css == null || css.isBlank()) {
            css = "";
        } else {
            css = " " + css;
        }
        writer
            .append("<article")
            .append(" id=\"").append(docname).append("\"")
            .append(" data-docid=\"").append(String.valueOf(docId)).append("\"")
            .append(" class=\"result").append(css).append("\"")
            .append(">\n");
        if (fieldDocline != null) {
            final String docline = doc.get(fieldDocline);
            if (docline != null) {
                final String url = docUrl();
                writer
                    .append("<h4 class=\"result-title\">\n")
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
     * Renders one document as a full {@code <article>} block: opening tag,
     * optional snippet count or "more" link, the snippet list, and the
     * closing tag. Intended as the entry point used by {@link SpanWalker}
     * during its document loop.
     *
     * @param docId    Lucene document id
     * @param snippets finished snippets for {@code docId}; must be in
     *                 {@link Snippets.Usage#OFFSETS}
     * @throws IOException if the writer or stored-fields access fails
     */
    @Override
    public void docSnippets(
        final int docId,
        final Snippets snippets
    ) throws IOException {
        docOpen(docId, "hassnippets");
        final int snipCount = snippets.count();
        if (snipCount > snipLimit) {
            final String url = docUrl();
            writer
                .append("<p class=\"snippets-count\">")
                .append("<a class=\"snippets-more\"")
                .append(" href=\"").append(url).append("&amp;snippets=-1\"")
                .append(">")
                .append("<span class=\"snippets-limit\">")
                .append(numForm.format(snipLimit))
                .append(" / ")
                .append("</span>")
                .append(numForm.format(snipCount))
                .append(" ").append(messages.getString("results.snippets"))
                .append("</a> ")
                .append("</p>\n");
        }
        snippets(docId, snippets);
        docClose(docId);
    }

    /**
     * Sets the stored field name that holds the HTML source from which
     * snippets are extracted. Default {@code "content"}. Read only when
     * {@code snipLimit != 0}.
     *
     * @param fieldContent stored-field name
     * @return this instance
     */
    public ResultsSnippets fieldContent(
        final String fieldContent
    ) {
        this.contentField = fieldContent;
        return this;
    }

    /**
     * Sets the stored field name used as the document heading inside
     * {@code <article>}. When {@code null}, no heading is emitted.
     * Default {@code "docline"}.
     *
     * @param fieldDocline stored-field name, or {@code null}
     * @return this instance
     */
    public ResultsSnippets fieldDocline(
        final String fieldDocline
    ) {
        this.fieldDocline = fieldDocline;
        return this;
    }

    /**
     * Reports whether snippet ranking is fully configured. Scoring requires
     * both a {@link TermRail} (set via {@link #rail(TermRail)}) and a term
     * weight array (set via {@link #termWeights(double[])}); without both,
     * any code path that would invoke ranking fails with
     * {@link IllegalArgumentException}.
     *
     * @return {@code true} when ranking is fully configured
     */
    public boolean hasScoring()
    {
        return rail != null && termWeights != null;
    }

    /**
     * Sets the term rail used to score snippet windows. Required for
     * ranking, together with {@link #termWeights(double[])}. Without both,
     * ranking branches throw {@link IllegalArgumentException}.
     *
     * @param rail term rail aligned with the index used by this renderer
     * @return this instance
     */
    public ResultsSnippets rail(
        final TermRail rail
    ) {
        this.rail = rail;
        return this;
    }

    /**
     * Returns the maximum number of snippets rendered per document, as
     * fixed at construction. {@code 0} disables snippet rendering and the
     * loading of the content field; a negative value lifts the cap and
     * emits every snippet in document order.
     *
     * @return snippet cap
     */
    public int snipLimit()
    {
        return snipLimit;
    }

    /**
     * Emits the snippet list for a document, without article shell. Loads
     * and caches the document if not already cached for {@code docId}.
     * Three rendering paths apply:
     * <ul>
     *   <li>{@code snipLimit < 0}: every snippet, document order;</li>
     *   <li>{@code snipCount <= snipLimit}: every snippet, document order,
     *       no ranking;</li>
     *   <li>otherwise: top {@code snipLimit} by {@link #scoreSnippet},
     *       score-descending.</li>
     * </ul>
     * When the content field is missing or {@code snipCount} is zero, an
     * HTML comment is emitted instead of a list. When {@code snipLimit}
     * is zero, nothing is emitted.
     *
     * @param docId    Lucene document id
     * @param snippets finished snippets for {@code docId}; must be in
     *                 {@link Snippets.Usage#OFFSETS}
     * @throws IOException if the writer or stored-fields access fails
     */
    public void snippets(
        final int docId,
        final Snippets snippets
    ) throws IOException {
        ensureDoc(docId);
        final int snipCount = snippets.count();
        if (content == null) {
            writer.append("<!-- No text stored for field: '" + contentField + "' -->");
        } else if (snipCount <= 0) {
            writer.append("<!-- No snippets found -->");
        } else if (snipLimit < 0) {
            writer.append("<ol class=\"snippets\">\n");
            for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
                print(snippets, snipOrd);
            }
            writer.append("</ol>\n");
        } else if (snipLimit == 0) {
            // shells only
        } else if (snipCount <= snipLimit) {
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
     * Sets the per-term weight array used by snippet scoring. Indexed by
     * term id; sized to the term universe of the configured {@link TermRail}.
     * Term ids outside this range are silently ignored at scoring time.
     * Required for ranking, together with {@link #rail(TermRail)}.
     *
     * @param termWeights per-term weights, indexed by term id
     * @return this instance
     * @throws NullPointerException if {@code termWeights} is {@code null}
     */
    public ResultsSnippets termWeights(
        final double[] termWeights
    ) {
        this.termWeights = Objects.requireNonNull(termWeights, "termWeights");
        return this;
    }

    /**
     * Sets the URL template used for links to a document and its snippets.
     * Two placeholders are substituted at render time: {@code {docname}}
     * (the value of the {@code ALIX_ID} field) and {@code {docid}} (the
     * Lucene internal document id). Default empty.
     *
     * @param urlTemplate URL pattern; placeholders {@code {docname}} and
     *                    {@code {docid}}
     * @return this instance
     */
    public ResultsSnippets urlTemplate(
        final String urlTemplate
    ) {
        this.urlTemplate = urlTemplate;
        return this;
    }

    /**
     * Returns the URL of the currently cached document by substituting
     * {@code {docname}} and {@code {docid}} in {@link #urlTemplate}.
     * Requires {@link #ensureDoc(int)} to have run for the current docId.
     *
     * @return URL string
     */
    private String docUrl()
    {
        return urlTemplate.replace("{docname}", docname).replace("{docid}", Integer.toString(cachedDocId));
    }

    /**
     * Loads the document for {@code docId} into the cache if not already
     * present, reading only the stored fields the renderer needs. The
     * content field is loaded only when {@code snipLimit != 0}; the
     * docline field is loaded only when {@link #fieldDocline} is non-null.
     * When the content field is skipped, {@link #content} remains
     * {@code null} and the rendering paths that depend on it become
     * inactive.
     *
     * @param docId Lucene document id
     * @throws IOException if stored-fields access fails
     */
    private void ensureDoc(
        final int docId
    ) throws IOException {
        if (cachedDocId == docId) return;
        if (storedFieldNames == null) {
            storedFieldNames = storedFieldNames();
        }
        this.doc = storedFields.document(docId, storedFieldNames);
        this.docname = doc.get(ALIX_ID);
        this.content = (snipLimit == 0) ? null : doc.get(contentField);
        this.cachedDocId = docId;
    }

    /**
     * Emits one concordance line as an {@code <li>}: left context, pivot
     * {@code <mark>}s with interleaved text, right context, gutter link.
     * <p>
     * Requires the snippet to contain at least one match. A snippet without
     * matches in OFFSETS mode is an upstream invariant violation and will
     * surface as an {@link IndexOutOfBoundsException} from
     * {@link Snippets#matchStartOffset(int)} via the {@code -1} returned by
     * {@link Snippets#snipStartMatch(int)}; this is intentional.
     * </p>
     *
     * @param snippets finished snippets in OFFSETS mode
     * @param snipOrd  snippet ordinal in {@code [0, snippets.count())}
     * @throws IOException if the writer fails
     */
    private void print(
        final Snippets snippets,
        final int snipOrd
    ) throws IOException {
        final int leftMatchOrd = snippets.snipStartMatch(snipOrd);
        final int rightMatchOrd = snippets.snipEndMatch(snipOrd);
        final int leftMatchStartOffset = snippets.matchStartOffset(leftMatchOrd);
        final int rightMatchEndOffset = snippets.matchEndOffset(rightMatchOrd);

        final int snipAnchor = snipOrd + 1;
        final String url = docUrl();
        writer
            .append("\n<li class=\"snippet\"")
            .append(" data-href=\"").append(url).append("#snippet-").append(Integer.toString(snipAnchor)).append("\"")
            .append(">\n");
        writer.append("<span class=\"snippet-no\">").append(Integer.toString(snipAnchor)).append("</span>");

        writer.append("<p>");
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
        writer
            .append("</p>\n")
            .append("<a")
            .append(" href=\"").append(url).append("#snippet-").append(Integer.toString(snipAnchor)).append("\"")
            .append(" class=\"snippet-open\"")
            .append(">")
            .append("→</a>\n");
        writer.append("</li>\n");
    }

    /**
     * Computes the score of a snippet window as the sum of term weights for
     * the distinct terms appearing in {@code [startPosition, endPosition)}
     * on the rail for {@code docId}. Each term contributes at most once per
     * snippet via {@link #dedupEpoch}.
     *
     * @param docId         Lucene document id
     * @param startPosition first position to include, inclusive
     * @param endPosition   first position to exclude
     * @return accumulated score
     * @throws IllegalArgumentException if either {@link #rail(TermRail)} or
     *         {@link #termWeights(double[])} has not been set
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
        dedupEpoch++;
        rail.scanWindow(docId, startPosition, endPosition, termId -> {
            if (termId < termDedup.length && termDedup[termId] != dedupEpoch) {
                termDedup[termId] = dedupEpoch;
                acc[0] += termWeights[termId];
            }
        });
        return acc[0];
    }

    /**
     * Builds the stored-field set to load in {@link #ensureDoc(int)}.
     * Always includes {@code ALIX_ID}; includes the content field only
     * when {@code snipLimit != 0}; includes the docline field only when
     * {@link #fieldDocline} is non-null at the time of first cache load.
     *
     * @return immutable set of stored field names to load
     */
    private Set<String> storedFieldNames()
    {
        final boolean wantContent = (snipLimit != 0);
        final boolean wantDocline = (fieldDocline != null);
        if (wantContent && wantDocline) return Set.of(ALIX_ID, contentField, fieldDocline);
        if (wantContent) return Set.of(ALIX_ID, contentField);
        if (wantDocline) return Set.of(ALIX_ID, fieldDocline);
        return Set.of(ALIX_ID);
    }
}
