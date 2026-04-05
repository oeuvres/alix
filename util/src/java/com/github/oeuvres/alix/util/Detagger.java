package com.github.oeuvres.alix.util;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Extracts normalized text from an XML/HTML source string, optionally preserving
 * a configured set of element tags, and writes the result to any {@link Appendable}
 * ({@link java.io.Writer}, {@link StringBuilder}, {@link Chain}, etc.).
 *
 * <p>A single {@code Detagger} instance is configured once — with the set of tag
 * names to preserve — and then reused across many calls. Internal scratch buffers
 * ({@code tagBuf}, {@code nameBuf}, {@code openTags}) are allocated at construction
 * and reused, so no per-call heap allocation occurs beyond what the
 * {@link Appendable} itself may perform.</p>
 *
 * <h2>Text normalization</h2>
 * <ul>
 *   <li>All ASCII whitespace (space, tab, CR, LF) is collapsed to a single space.</li>
 *   <li>All element markup is stripped unless the element's local name appears in the
 *       include set.</li>
 *   <li>Broken excerpts are tolerated: if the slice begins inside a tag, the initial
 *       broken markup is discarded; if it ends inside a tag, the unterminated markup
 *       is discarded.</li>
 *   <li>Preserved tags left open at the end of a truncated snippet are automatically
 *       closed in reverse order, preventing markup leakage into surrounding HTML.</li>
 *   <li>Entities are not decoded; comments and processing instructions are treated as
 *       ordinary tags and stripped unless explicitly included by name.</li>
 * </ul>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * // configured once, e.g. to preserve italics
 * private final Detagger detagger = new Detagger("i", "em");
 *
 * // called many times in a hot loop, writes directly to the response writer
 * detagger.detag(content, leftBoundary, spanStart, writer);
 * }</pre>
 *
 * <p>The static convenience methods on {@link Markup} delegate to a shared
 * include-nothing instance for backward compatibility.</p>
 */
public class Detagger {

    /** Tag names whose markup is preserved; {@code null} means strip everything. */
    private final Set<String> include;

    /** Scratch buffer for the full {@code <...>} tag being scanned. */
    private final StringBuilder tagBuf = new StringBuilder(64);
    /** Scratch buffer for the element local name being scanned. */
    private final StringBuilder nameBuf = new StringBuilder(32);
    /**
     * Stack of preserved tag names opened but not yet closed in the current
     * {@link #detag} call. Drained after the main loop to emit auto-close tags.
     * Cleared at the start of every call; never shared across threads.
     */
    private final Deque<String> openTags = new ArrayDeque<>();

    /**
     * Creates a detagger that strips all element markup.
     */
    public Detagger()
    {
        this.include = null;
    }

    /**
     * Creates a detagger that preserves the markup of the named elements.
     * Names are case-sensitive local names (without namespace prefix).
     *
     * @param tags element local names whose markup should be kept
     */
    public Detagger(final String... tags)
    {
        this.include = new HashSet<>(Arrays.asList(tags));
    }

    /**
     * Creates a detagger that preserves the markup of the named elements.
     *
     * @param tags element local names whose markup should be kept
     */
    public Detagger(final Collection<String> tags)
    {
        this.include = new HashSet<>(tags);
    }

    /**
     * Appends a normalized text view of {@code xml[begin, end)} to {@code dest}.
     *
     * <p>Nothing is cleared in {@code dest} before writing; content is appended.
     * If the slice begins inside a tag (a {@code >} is encountered before any {@code <}),
     * the broken leading fragment is silently discarded and the text that follows is kept.
     * If the slice ends inside an unclosed tag, that tag is silently discarded.</p>
     *
     * <p>Any preserved element tags that were opened within the slice but were not
     * closed before {@code end} are automatically closed in reverse order. This
     * prevents inline markup (e.g. {@code <i>}) from leaking out of the snippet
     * and corrupting the surrounding page.</p>
     *
     * <p>The method is not thread-safe: a single {@code Detagger} instance must not
     * be shared across threads without external synchronization.</p>
     *
     * @param xml   source text (may contain tag markup)
     * @param begin start index, inclusive; clamped to {@code [0, xml.length()]}
     * @param end   end index, exclusive; clamped to {@code [0, xml.length()]}
     * @param dest  destination; receives normalized text and any preserved tags
     * @throws IOException if {@code dest.append} throws
     */
    public void detag(final String xml, int begin, int end, final Appendable dest)
            throws IOException
    {
        if (xml == null || dest == null) return;
        if (begin < 0) begin = 0;
        if (end > xml.length()) end = xml.length();
        if (begin >= end) return;

        // Pre-scan: if the slice starts inside a tag (e.g. " attr>"), advance begin
        // past the closing '>' so the main loop never sees a stray '>' outside a tag.
        // Stop as soon as '<' is found (normal tag boundary, no broken fragment).
        for (int i = begin; i < end; i++) {
            final char c = xml.charAt(i);
            if (c == '<') break;
            if (c == '>') { begin = i + 1; break; }
        }
        if (begin >= end) return;

        boolean inTag = false;
        boolean recordName = false;
        tagBuf.setLength(0);
        nameBuf.setLength(0);
        openTags.clear();

        // Track the last character written to dest for whitespace collapsing.
        // We cannot read back from a generic Appendable, so we maintain it ourselves.
        char lastWritten = 'x';

        for (int i = begin; i < end; i++) {
            final char c = xml.charAt(i);

            if (!inTag) {
                switch (c) {
                    case ' ':
                    case '\t':
                    case '\r':
                    case '\n':
                        if (lastWritten != ' ') {
                            dest.append(' ');
                            lastWritten = ' ';
                        }
                        break;
                    case '<':
                        inTag = true;
                        recordName = true;
                        tagBuf.setLength(0);
                        nameBuf.setLength(0);
                        tagBuf.append(c);
                        break;
                    default:
                        dest.append(c);
                        lastWritten = c;
                }
            } else {
                tagBuf.append(c);

                if (recordName) {
                    switch (c) {
                        case ' ':
                        case '\t':
                        case '\r':
                        case '\n':
                        case '>':
                            recordName = false;
                            break;
                        case '/':
                            if (nameBuf.length() > 0) recordName = false;
                            break;
                        case '?':
                        case '!':
                            recordName = false;
                            break;
                        default:
                            nameBuf.append(c);
                    }
                }

                if (c == '>') {
                    inTag = false;
                    recordName = false;

                    if (include != null && !include.isEmpty() && nameBuf.length() > 0) {
                        final int colon = nameBuf.lastIndexOf(":");
                        final String local = (colon >= 0)
                                ? nameBuf.substring(colon + 1)
                                : nameBuf.toString();
                        if (include.contains(local)) {
                            dest.append(tagBuf);
                            trackOpenClose(local);
                        }
                    }

                    tagBuf.setLength(0);
                    nameBuf.setLength(0);
                }
            }
        }

        // Auto-close any preserved tags left open by a truncated snippet.
        // Iterate the stack top-to-bottom (LIFO) to emit well-nested closers.
        while (!openTags.isEmpty()) {
            dest.append("</").append(openTags.pop()).append('>');
        }
    }

    /**
     * Updates the {@link #openTags} stack when a preserved tag is emitted.
     *
     * <ul>
     *   <li>Self-closing ({@code />}) — no stack change.</li>
     *   <li>Closing ({@code tagBuf} second char is {@code /}) — removes the most
     *       recently opened matching entry from the stack.</li>
     *   <li>Opening — pushes the local name onto the stack.</li>
     * </ul>
     *
     * @param local the local name that was just emitted
     */
    private void trackOpenClose(final String local)
    {
        final int len = tagBuf.length();
        // Self-closing: ends with "/>"
        if (len >= 2 && tagBuf.charAt(len - 2) == '/') return;

        // Closing tag: second character is '/'  →  </name>
        if (len >= 2 && tagBuf.charAt(1) == '/') {
            // Remove the topmost matching open entry (handles mismatched nesting gracefully)
            final Iterator<String> it = openTags.iterator();
            while (it.hasNext()) {
                if (it.next().equals(local)) { it.remove(); break; }
            }
        } else {
            // Opening tag
            openTags.push(local);
        }
    }
}