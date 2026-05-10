package com.github.oeuvres.alix.util.fr;

import com.github.oeuvres.alix.util.CharsDic;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.WordTokenizer;

/**
 * Mutable tokenizer for short French character sequences.
 *
 * <p>The tokenizer extracts word tokens and applies a small French-specific
 * clitic split: apostrophe elisions such as {@code j'aime -> je aime}, and
 * hyphen suffixes such as {@code donne-le-moi -> donne le moi}. It is intended
 * for query tokenization and for loading multi-word-expression lexicons.</p>
 *
 * <p>This class is stateful, reusable, and not thread-safe. Returned words are
 * transient {@link CharSequence} values backed by internal {@link StringBuilder}
 * buffers. Callers must copy {@link #word()} if they need to retain it.</p>
 */
public final class FrenchCliticTokenizer implements WordTokenizer {
    private static final int MAX_SPLITS = 8;

    private static final CharsDic KEEP_AS_IS = new CharsDic(17, true);
    static {
        KEEP_AS_IS.add("c'est-à-dire");
        KEEP_AS_IS.add("d'abord");
        KEEP_AS_IS.add("d'accord");
        KEEP_AS_IS.add("d'ailleurs");
        KEEP_AS_IS.add("d'après");
        KEEP_AS_IS.add("d'autant");
        KEEP_AS_IS.add("d'autre");
        KEEP_AS_IS.add("d'autres");
        KEEP_AS_IS.add("d'avec");
        KEEP_AS_IS.add("d'emblée");
        KEEP_AS_IS.add("d'entre");
        KEEP_AS_IS.add("d'ici");
        KEEP_AS_IS.add("n'empêche");
        KEEP_AS_IS.add("n'est-ce");
        KEEP_AS_IS.add("n'importe");
        KEEP_AS_IS.add("qu'est-ce");
        KEEP_AS_IS.add("quelqu'un");
    }

    private static final CharsMap PREFIX = new CharsMap(15);
    static {
        PREFIX.put("c'", "ce");
        PREFIX.put("d'", "de");
        PREFIX.put("j'", "je");
        PREFIX.put("jusqu'", "jusque");
        PREFIX.put("l'", "l'");
        PREFIX.put("lorsqu'", "lorsque");
        PREFIX.put("m'", "me");
        PREFIX.put("n'", "ne");
        PREFIX.put("puisqu'", "puisque");
        PREFIX.put("qu'", "que");
        PREFIX.put("quoiqu'", "quoique");
        PREFIX.put("s'", "se");
        PREFIX.put("t'", "te");
    }

    private static final CharsMap SUFFIX = new CharsMap(25);
    static {
        SUFFIX.put("-ce", "ce");
        SUFFIX.put("-ci", "");
        SUFFIX.put("-elle", "elle");
        SUFFIX.put("-elles", "elles");
        SUFFIX.put("-en", "en");
        SUFFIX.put("-eux", "eux");
        SUFFIX.put("-il", "il");
        SUFFIX.put("-ils", "ils");
        SUFFIX.put("-je", "je");
        SUFFIX.put("-la", "la");
        SUFFIX.put("-là", "");
        SUFFIX.put("-le", "le");
        SUFFIX.put("-les", "les");
        SUFFIX.put("-leur", "leur");
        SUFFIX.put("-lui", "lui");
        SUFFIX.put("-me", "me");
        SUFFIX.put("-moi", "moi");
        SUFFIX.put("-nous", "nous");
        SUFFIX.put("-on", "on");
        SUFFIX.put("-t", "");
        SUFFIX.put("-te", "te");
        SUFFIX.put("-toi", "toi");
        SUFFIX.put("-tu", "tu");
        SUFFIX.put("-vous", "vous");
        SUFFIX.put("-y", "y");
    }

    private CharSequence text;
    private int offset;

    private final StringBuilder raw = new StringBuilder(32);
    private final StringBuilder key = new StringBuilder(16);
    private final StringBuilder[] pending = new StringBuilder[MAX_SPLITS + 1];

    private int pendingEnd;
    private int pendingStart;

    private StringBuilder current;

    /**
     * Constructs a reusable French clitic tokenizer.
     */
    public FrenchCliticTokenizer() {
        for (int i = 0; i < pending.length; i++) {
            pending[i] = new StringBuilder(16);
        }
    }

    /**
     * Clears the current input and scan state.
     *
     * <p>Internal buffers are retained for reuse.</p>
     */
    @Override
    public void clear() {
        text = null;
        offset = 0;
        current = null;
        raw.setLength(0);
        key.setLength(0);
        pendingStart = 0;
        pendingEnd = 0;
    }

    /**
     * Advances to the next word.
     *
     * @return true if a word is available, false otherwise
     */
    @Override
    public boolean next() {
        if (emitPending()) {
            return true;
        }

        while (readRawToken()) {
            if (fillPending()) {
                return emitPending();
            }
        }

        current = null;
        return false;
    }

    /**
     * Binds this tokenizer to a new input.
     *
     * @param text the input text
     */
    @Override
    public void reset(final CharSequence text) {
        clear();
        this.text = text;
    }

    /**
     * Returns the current word.
     *
     * @return the current word
     * @throws IllegalStateException if called before {@link #next()} returned true
     */
    @Override
    public CharSequence word() {
        if (current == null) {
            throw new IllegalStateException("No current word. Call next() first.");
        }
        return current;
    }

    /**
     * Appends a literal replacement word to the pending queue.
     *
     * @param value the literal replacement
     * @return true if the word was appended
     */
    private boolean appendLiteral(final String value) {
        if (pendingEnd >= pending.length) {
            return false;
        }

        final StringBuilder builder = pending[pendingEnd++];
        builder.setLength(0);
        builder.append(value);
        return true;
    }

    /**
     * Appends a range from the raw buffer to the pending queue.
     *
     * @param start the inclusive raw-buffer start offset
     * @param end the exclusive raw-buffer end offset
     * @return true if the range was appended
     */
    private boolean appendRange(final int start, final int end) {
        if (pendingEnd >= pending.length) {
            return false;
        }

        final StringBuilder builder = pending[pendingEnd++];
        builder.setLength(0);

        for (int i = start; i < end; i++) {
            builder.append(raw.charAt(i));
        }

        return true;
    }

    /**
     * Emits one pending word.
     *
     * @return true if a pending word was emitted
     */
    private boolean emitPending() {
        if (pendingStart >= pendingEnd) {
            return false;
        }

        current = pending[pendingStart++];
        return true;
    }

    /**
     * Expands the current raw token into pending words.
     *
     * @return true if at least one word was queued
     */
    private boolean fillPending() {
        pendingStart = 0;
        pendingEnd = 0;

        if (raw.length() == 0) {
            return false;
        }

        if (KEEP_AS_IS.contains(raw, 0, raw.length()) || tooManyHyphens(0, raw.length())) {
            return appendRange(0, raw.length());
        }

        if (!splitRange(0, raw.length(), 0)) {
            pendingStart = 0;
            pendingEnd = 0;
            return appendRange(0, raw.length());
        }

        return pendingEnd > 0;
    }

    /**
     * Finds the first apostrophe in a raw-buffer range.
     *
     * @param start the inclusive start offset
     * @param end the exclusive end offset
     * @return the apostrophe offset, or -1
     */
    private int firstApostrophe(final int start, final int end) {
        for (int i = start; i < end; i++) {
            if (raw.charAt(i) == '\'') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tells whether a character belongs to a raw token.
     *
     * @param c the character
     * @return true if the character belongs to a raw token
     */
    private static boolean isTokenChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '-';
    }

    /**
     * Tells whether a character is uppercase or titlecase.
     *
     * @param c the character
     * @return true if the character is uppercase or titlecase
     */
    private static boolean isUpperOrTitle(final char c) {
        return Character.isUpperCase(c) || Character.isTitleCase(c);
    }

    /**
     * Finds the last hyphen in a raw-buffer range.
     *
     * @param start the inclusive start offset
     * @param end the exclusive end offset
     * @return the hyphen offset, or -1
     */
    private int lastHyphen(final int start, final int end) {
        for (int i = end - 1; i >= start; i--) {
            if (raw.charAt(i) == '-') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Normalizes apostrophe and hyphen variants.
     *
     * @param c the source character
     * @return the normalized character
     */
    private static char normalizeChar(final char c) {
        return switch (c) {
            case '\u2019', '\u2018', '\u02BC' -> '\'';
            case '\u2010', '\u2011', '\u00AD' -> '-';
            default -> c;
        };
    }



    /**
     * Reads the next raw token into the reusable raw buffer.
     *
     * @return true if a raw token was read
     */
    private boolean readRawToken() {
        raw.setLength(0);

        if (text == null) {
            return false;
        }

        final int len = text.length();

        while (offset < len) {
            final char c = normalizeChar(text.charAt(offset++));
            if (isTokenChar(c)) {
                raw.append(c);
                break;
            }
        }

        while (offset < len) {
            final char c = normalizeChar(text.charAt(offset));
            if (!isTokenChar(c)) {
                offset++;
                break;
            }
            raw.append(c);
            offset++;
        }

        return raw.length() > 0;
    }

    /**
     * Splits one raw-buffer range into pending words.
     *
     * @param start the inclusive raw-buffer start offset
     * @param end the exclusive raw-buffer end offset
     * @param depth the current split depth
     * @return true if splitting succeeded
     */
    private boolean splitRange(final int start, final int end, final int depth) {
        if (depth > MAX_SPLITS) {
            return false;
        }

        final int len = end - start;

        if (len <= 1) {
            return appendRange(start, end);
        }

        final int apostrophe = firstApostrophe(start, end);
        final int hyphen = lastHyphen(start, end);

        if (apostrophe < 0 && hyphen < 0) {
            return appendRange(start, end);
        }

        if (apostrophe == end - 1 || hyphen == start || hyphen == end - 1) {
            return appendRange(start, end);
        }

        if (apostrophe > start) {
            final int prefixEnd = apostrophe + 1;
            // lower case prefix
            raw.setCharAt(start, Character.toLowerCase(raw.charAt(start)));
            final String value = PREFIX.get(raw, start, prefixEnd - start);

            if (value != null && prefixEnd < end) {
                final char next = raw.charAt(prefixEnd);

                if (!(isUpperOrTitle(next) && isUpperOrTitle(raw.charAt(start)))) {
                    if (!appendLiteral(value)) {
                        return false;
                    }
                    return splitRange(prefixEnd, end, depth + 1);
                }
            }
        }

        if (hyphen > start) {
            final String value = SUFFIX.get(raw, hyphen, end - hyphen);

            if (value != null) {
                if (!splitRange(start, hyphen, depth + 1)) {
                    return false;
                }

                if (!value.isEmpty()) {
                    return appendLiteral(value);
                }

                return true;
            }
        }

        return appendRange(start, end);
    }

    /**
     * Tells whether a raw-buffer range contains too many hyphens to split safely.
     *
     * @param start the inclusive raw-buffer start offset
     * @param end the exclusive raw-buffer end offset
     * @return true if the range should be kept unsplit
     */
    private boolean tooManyHyphens(final int start, final int end) {
        int count = 0;

        for (int i = start; i < end; i++) {
            if (raw.charAt(i) == '-' && ++count > MAX_SPLITS) {
                return true;
            }
        }

        return false;
    }
}