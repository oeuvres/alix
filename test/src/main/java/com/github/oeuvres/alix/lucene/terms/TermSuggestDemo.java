package com.github.oeuvres.alix.lucene.terms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Interactive CLI for testing {@link TermSuggest}.
 * <p>
 * Opens a frozen Lucene index, builds the suggest index from
 * {@link TermLexicon} and {@link FieldStats}, then loops reading
 * queries from stdin. Results are printed with ANSI bold+underline
 * highlighting on the matched substring.
 * </p>
 *
 * <pre>
 * Usage: TermSuggestDemo &lt;indexDir&gt; &lt;field&gt; [limit]
 * </pre>
 */
public final class TermSuggestDemo
{
    /** ANSI escape: bold + underline on. */
    private static final String HL_ON = "\033[1;4m";

    /** ANSI escape: reset. */
    private static final String HL_OFF = "\033[0m";

    private TermSuggestDemo() {}

    public static void main(final String[] args) throws IOException
    {
        if (args.length < 2) {
            System.err.println("Usage: TermSuggestDemo <indexDir> <field> [limit]");
            System.err.println("  indexDir  path to frozen Lucene directory");
            System.err.println("  field     indexed field name");
            System.err.println("  limit     max results per query (default 20)");
            System.exit(1);
        }

        final Path indexDir = Path.of(args[0]);
        final String field = args[1];
        final int limit = (args.length >= 3) ? Integer.parseInt(args[2]) : 20;

        System.err.println("Opening lexicon and stats for field '" + field + "' …");

        try (TermLexicon lexicon = TermLexicon.open(indexDir, field)) {
            final FieldStats stats = FieldStats.open(indexDir, field);

            System.err.printf("Lexicon: %,d terms, FST heap %,d bytes%n",
                lexicon.vocabSize(), lexicon.fstRamBytesUsed());
            System.err.printf("Stats:   %,d terms, %,d docs, %,d tokens%n",
                stats.vocabSize(), stats.fieldDocs(), stats.fieldTokens());

            final long t0 = System.nanoTime();
            final TermSuggest suggest = new TermSuggest(lexicon, stats);
            final long buildMs = (System.nanoTime() - t0) / 1_000_000;

            System.err.printf("Suggest index built in %,d ms for %,d terms%n",
                buildMs, suggest.vocabSize());
            System.err.println();
            System.err.println("Type a query, empty line to quit.");
            System.err.println("  1–2 chars → prefix search");
            System.err.println("  3+  chars → infix (substring) search");
            System.err.println();

            final BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

            while (true) {
                System.out.print("> ");
                System.out.flush();

                final String line = reader.readLine();
                if (line == null || line.isBlank()) {
                    break;
                }
                final String query = line.strip();

                final long qt0 = System.nanoTime();
                final List<TermSuggest.SuggestRow> results = suggest.suggest(query, limit);
                final long elapsed = (System.nanoTime() - qt0) / 1_000;

                final String mode = TermSuggest.asciiFold(query).length() < 3
                    ? "prefix" : "infix";

                System.out.printf("%d hit%s in %,d µs [%s]%n",
                    results.size(),
                    results.size() == 1 ? "" : "s",
                    elapsed,
                    mode);

                for (final TermSuggest.SuggestRow row : results) {
                    final String marked = highlight(row);
                    System.out.printf("  %,12d  %s%n", row.count(), marked);
                }
                System.out.println();
            }
        }
    }

    /**
     * Renders one suggest row with ANSI highlight on the matched span.
     */
    private static String highlight(final TermSuggest.SuggestRow row)
    {
        final String t = row.term();
        final int from = row.hlFrom();
        final int to = Math.min(row.hlTo(), t.length());
        if (from >= to || from < 0) {
            return t;
        }
        return t.substring(0, from)
            + HL_ON + t.substring(from, to) + HL_OFF
            + t.substring(to);
    }
}
