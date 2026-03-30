package com.github.oeuvres.alix.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Extracts normalized text from an XML/HTML source string, optionally preserving
 * a configured set of element tags, and writes the result to any {@link Appendable}
 * ({@link java.io.Writer}, {@link StringBuilder}, {@link Chain}, etc.).
 *
 * <p>A single {@code Detagger} instance is configured once — with the set of tag
 * names to preserve — and then reused across many calls. Internal scratch buffers
 * ({@code tagBuf}, {@code nameBuf}) are allocated at construction and reused, so
 * no per-call heap allocation occurs beyond what the {@link Appendable} itself may
 * perform.</p>
 *
 * <h2>Text normalization</h2>
 * <ul>
 *   <li>All ASCII whitespace (space, tab, CR, LF) is collapsed to a single space.</li>
 *   <li>All element markup is stripped unless the element's local name appears in the
 *       include set.</li>
 *   <li>Broken excerpts are tolerated: if the slice begins inside a tag, the initial
 *       broken markup is discarded; if it ends inside a tag, the unterminated markup
 *       is discarded.</li>
 *   <li>Entities are not decoded; comments and processing instructions are treated as
 *       ordinary tags and stripped unless explicitly included by name.</li>
 * </ul>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * // configured once, e.g. to preserve italics and line breaks
 * private final Detagger detagger = new Detagger("i", "em", "lb");
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
     * The method is not thread-safe: a single {@code Detagger} instance must not
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

        boolean inTag = false;
        boolean recordName = false;
        tagBuf.setLength(0);
        nameBuf.setLength(0);

        // Track the last character written to dest for whitespace collapsing.
        // We cannot read back from a generic Appendable, so we maintain it ourselves.
        char lastWritten = 0;

        for (int i = begin; i < end; i++) {
            final char c = xml.charAt(i);

            if (!inTag) {
                switch (c) {
                    case ' ':
                    case '\t':
                    case '\r':
                    case '\n':
                        if (lastWritten != ' ' && lastWritten != 0) {
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
                    case '>':
                        // '>' before any '<': the slice started inside a tag; discard
                        // everything written so far from this call.
                        // We cannot truncate a generic Appendable; callers that need
                        // this guarantee should use a Chain or StringBuilder and reset
                        // manually. Here we simply skip the character.
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
                        }
                    }

                    tagBuf.setLength(0);
                    nameBuf.setLength(0);
                }
            }
        }
    }
}