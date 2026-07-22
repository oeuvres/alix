package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import com.github.oeuvres.alix.util.Detagger;
import com.github.oeuvres.alix.util.Markup;

/**
 * Format-neutral decomposition of one concordance line into an ordered list of
 * {@link Seg} segments. Built once from a {@link DocSnippets} ordinal and then
 * consumed by any renderer, so the left/pivot/right offset walk lives in a
 * single place. This replaces the two divergent paths that previously produced
 * a concordance line: {@code ResultsSnippets.print} (HTML {@code <li>}) and the
 * snippets-sort branch of {@code OpResults} ({@code SnippetHit.write}).
 *
 * <p>A context segment carries the detagged HTML for its range (inline
 * {@code i}/{@code em} preserved, per {@link Detagger}); a pivot segment carries
 * the raw match substring. The HTML renderer writes context verbatim and wraps
 * pivots in {@code <mark>}; the docx renderer sends every segment through the
 * HTML tokenizer and marks pivots bold.</p>
 */
public final class SnippetView {
    /** Segment role. */
    public enum Kind { CONTEXT, PIVOT }

    /**
     * One segment.
     *
     * @param kind role
     * @param html inline HTML fragment (context) or match text (pivot)
     */
    public record Seg(Kind kind, String html) {}

    private final List<Seg> segs;
    private final int anchor;
    private String citationHtml;
    private String url;

    private SnippetView(final List<Seg> segs, final int anchor) {
        this.segs = segs;
        this.anchor = anchor;
    }

    /**
     * Builds the view for one snippet ordinal, replicating the original offset
     * walk: left context, then each pivot with the inter-pivot gap emitted as
     * context, then right context. Requires the snippet to hold at least one
     * match (an OFFSETS-mode invariant).
     *
     * @param content  decoded content field of the document
     * @param snippets finished snippets in OFFSETS mode
     * @param snipOrd  snippet ordinal
     * @param ctx      context width in words on each side
     * @param detagger normaliser applied to context ranges
     * @return a view ready to render
     * @throws IOException if the detagger fails
     */
    public static SnippetView of(
        final String content,
        final DocSnippets snippets,
        final int snipOrd,
        final int ctx,
        final Detagger detagger
    ) throws IOException {
        final int leftMatch = snippets.snipStartMatch(snipOrd);
        final int rightMatch = snippets.snipEndMatch(snipOrd);
        final int leftStart = snippets.matchStartOffset(leftMatch);
        final int rightEnd = snippets.matchEndOffset(rightMatch);
        final List<Seg> out = new ArrayList<>();

        final int leftOffset = Markup.leftBoundary(content, leftStart, ctx, -1);
        out.add(new Seg(Kind.CONTEXT, detag(detagger, content, leftOffset, leftStart)));
        for (int m = leftMatch; m <= rightMatch; m++) {
            if (m != leftMatch) {
                out.add(new Seg(Kind.CONTEXT,
                    detag(detagger, content, snippets.matchEndOffset(m - 1), snippets.matchStartOffset(m))));
            }
            final int s = snippets.matchStartOffset(m);
            final int e = snippets.matchEndOffset(m);
            out.add(new Seg(Kind.PIVOT, content.substring(s, e)));
        }
        final int rightOffset = Markup.rightBoundary(content, rightEnd, ctx, -1);
        out.add(new Seg(Kind.CONTEXT, detag(detagger, content, rightEnd, rightOffset)));
        return new SnippetView(out, snipOrd + 1);
    }

    /** @return 1-based snippet anchor used for links */
    public int anchor() {
        return anchor;
    }

    /** @return APA citation HTML for the docx footnote, or {@code null} */
    public String citationHtml() {
        return citationHtml;
    }

    /**
     * Sets the APA citation HTML rendered as a docx footnote (ignored by the
     * HTML renderer). Wire your existing HTML-APA builder here.
     *
     * @param citationHtml inline HTML fragment
     * @return this
     */
    public SnippetView citationHtml(final String citationHtml) {
        this.citationHtml = citationHtml;
        return this;
    }

    /** @return ordered segments */
    public List<Seg> segs() {
        return segs;
    }

    /** @return snippet URL, or {@code null} */
    public String url() {
        return url;
    }

    /**
     * Sets the snippet URL, appended to the footnote in docx.
     *
     * @param url absolute or app-relative URL
     * @return this
     */
    public SnippetView url(final String url) {
        this.url = url;
        return this;
    }

    private static String detag(
        final Detagger detagger,
        final String content,
        final int from,
        final int to
    ) throws IOException {
        final StringWriter w = new StringWriter();
        detagger.detag(w, content, from, to);
        return w.toString();
    }
}
