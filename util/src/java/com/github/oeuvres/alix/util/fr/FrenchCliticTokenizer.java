package com.github.oeuvres.alix.util.fr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tokenizes short French query strings and splits French clitics without Lucene.
 *
 * <p>The tokenizer is intended for query rewriting and MWE-trie construction.
 * It does not preserve offsets, positions, punctuation, or Lucene token-stream
 * attributes.</p>
 */
public final class FrenchCliticTokenizer {
    private static final int MAX_SPLITS = 8;

    private static final Set<String> KEEP_AS_IS = Set.of(
            "c'est-à-dire",
            "d'abord",
            "d'accord",
            "d'ailleurs",
            "d'après",
            "d'autant",
            "d'autre",
            "d'autres",
            "d'avec",
            "d'emblée",
            "d'entre",
            "d'ici",
            "n'empêche",
            "n'est-ce",
            "n'importe",
            "qu'est-ce",
            "quelqu'un"
    );

    private static final Map<String, String> PREFIX = Map.ofEntries(
            Map.entry("c'", "ce"),
            Map.entry("d'", "de"),
            Map.entry("j'", "je"),
            Map.entry("jusqu'", "jusque"),
            Map.entry("l'", "l'"),
            Map.entry("lorsqu'", "lorsque"),
            Map.entry("m'", "me"),
            Map.entry("n'", "ne"),
            Map.entry("puisqu'", "puisque"),
            Map.entry("qu'", "que"),
            Map.entry("quoiqu'", "quoique"),
            Map.entry("s'", "se"),
            Map.entry("t'", "te")
    );

    private static final Map<String, String> SUFFIX = Map.ofEntries(
            Map.entry("-ce", "ce"),
            Map.entry("-ci", ""),
            Map.entry("-elle", "elle"),
            Map.entry("-elles", "elles"),
            Map.entry("-en", "en"),
            Map.entry("-eux", "eux"),
            Map.entry("-il", "il"),
            Map.entry("-ils", "ils"),
            Map.entry("-je", "je"),
            Map.entry("-la", "la"),
            Map.entry("-là", ""),
            Map.entry("-le", "le"),
            Map.entry("-les", "les"),
            Map.entry("-leur", "leur"),
            Map.entry("-lui", "lui"),
            Map.entry("-me", "me"),
            Map.entry("-moi", "moi"),
            Map.entry("-nous", "nous"),
            Map.entry("-on", "on"),
            Map.entry("-t", ""),
            Map.entry("-te", "te"),
            Map.entry("-toi", "toi"),
            Map.entry("-tu", "tu"),
            Map.entry("-vous", "vous"),
            Map.entry("-y", "y")
    );

    private FrenchCliticTokenizer() {
    }

    /**
     * Splits a French query string into normalized tokens.
     *
     * @param text the query text
     * @return the token list
     */
    public static List<String> split(final String text) {
        final ArrayList<String> out = new ArrayList<>(Math.min(16, Math.max(4, text.length() / 4)));
        split(text, out);
        return out;
    }

    /**
     * Splits a French query string into a caller-provided reusable output list.
     *
     * <p>The output list is cleared before tokens are added.</p>
     *
     * @param text the query text
     * @param out the reusable output token list
     */
    public static void split(final String text, final List<String> out) {
        out.clear();

        if (text == null || text.isEmpty()) {
            return;
        }

        final StringBuilder token = new StringBuilder(Math.min(32, text.length()));

        for (int i = 0; i < text.length(); i++) {
            final char c = normalizeChar(text.charAt(i));

            if (isTokenChar(c)) {
                token.append(c);
            } else {
                flush(token, out);
            }
        }

        flush(token, out);
    }

    /**
     * Tokenizes a French query string and returns space-separated tokens.
     *
     * @param text the query text
     * @return the space-separated token string
     */
    public static String tokens(final String text) {
        return String.join(" ", split(text));
    }

    /**
     * Adds a token after clitic splitting.
     *
     * @param token the token to split
     * @param out the output token list
     */
    private static void addToken(final String token, final List<String> out) {
        if (token.isEmpty()) {
            return;
        }

        if (KEEP_AS_IS.contains(key(token)) || tooManyHyphens(token)) {
            out.add(token);
            return;
        }

        final int mark = out.size();

        if (!splitToken(token, out, 0)) {
            rollback(out, mark);
            out.add(token);
        }
    }

    /**
     * Appends the current token buffer to the output list.
     *
     * @param token the current token buffer
     * @param out the output token list
     */
    private static void flush(final StringBuilder token, final List<String> out) {
        if (token.isEmpty()) {
            return;
        }

        addToken(token.toString(), out);
        token.setLength(0);
    }

    /**
     * Tells whether a character is allowed inside a token.
     *
     * @param c the character
     * @return true if the character is part of a token
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
     * Builds a lowercase lookup key.
     *
     * @param text the text
     * @return the lowercase key
     */
    private static String key(final String text) {
        return text.toLowerCase(Locale.ROOT);
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
     * Removes all tokens added after a list position.
     *
     * @param out the output list
     * @param mark the rollback position
     */
    private static void rollback(final List<String> out, final int mark) {
        for (int i = out.size() - 1; i >= mark; i--) {
            out.remove(i);
        }
    }

    /**
     * Splits one token into clitic components.
     *
     * @param token the token to split
     * @param out the output token list
     * @param depth the current split depth
     * @return true if splitting succeeded
     */
    private static boolean splitToken(final String token, final List<String> out, final int depth) {
        if (depth > MAX_SPLITS) {
            return false;
        }

        if (token.length() <= 1) {
            out.add(token);
            return true;
        }

        final int apos = token.indexOf('\'');
        final int hyphen = token.lastIndexOf('-');

        if (apos < 0 && hyphen < 0) {
            out.add(token);
            return true;
        }

        if (apos == token.length() - 1 || hyphen == 0 || hyphen == token.length() - 1) {
            out.add(token);
            return true;
        }

        if (apos > 0) {
            final int prefixLen = apos + 1;
            final String prefix = key(token.substring(0, prefixLen));
            final String value = PREFIX.get(prefix);

            if (value != null && prefixLen < token.length()) {
                final char next = token.charAt(prefixLen);

                if (!(isUpperOrTitle(next) && isUpperOrTitle(token.charAt(0)))) {
                    out.add(value);
                    return splitToken(token.substring(prefixLen), out, depth + 1);
                }
            }
        }

        if (hyphen > 0) {
            final String suffix = key(token.substring(hyphen));
            final String value = SUFFIX.get(suffix);

            if (value != null) {
                if (!splitToken(token.substring(0, hyphen), out, depth + 1)) {
                    return false;
                }

                if (!value.isEmpty()) {
                    out.add(value);
                }

                return true;
            }
        }

        out.add(token);
        return true;
    }

    /**
     * Tells whether a token has too many hyphens to be safely split.
     *
     * @param token the token
     * @return true if the token should be left untouched
     */
    private static boolean tooManyHyphens(final String token) {
        int count = 0;

        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '-' && ++count > MAX_SPLITS) {
                return true;
            }
        }

        return false;
    }
}
