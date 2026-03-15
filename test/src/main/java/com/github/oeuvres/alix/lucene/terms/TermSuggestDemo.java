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
 * {@link TermLexicon} and {@link FieldStats} with ANSI bold+underline
 * markup, then loops reading queries from stdin.
 * </p>
 *
 * <pre>
 * Usage: TermSuggestDemo &lt;indexDir&gt; &lt;field&gt; [limit]
 * </pre>
 */
public final class TermSuggestDemo
{
    private static final String ANSI_HL_ON = "\033[1;4m";
    private static final String ANSI_HL_OFF = "\033[0m";

    private TermSuggestDemo() {}

    public static void main(final String[] args) throws IOException
    {

        final Path indexPath = Path.of("D:\\code\\alix\\web\\lucene\\piaget");
        final String field = "content";
        final int limit = (args.length >= 3) ? Integer.parseInt(args[2]) : 20;

        System.err.println("Opening lexicon and stats for field '" + field + "' …");

        try (TermLexicon lexicon = TermLexicon.open(indexPath, field)) {
            final FieldStats stats = FieldStats.open(indexPath, field);

            System.err.printf("Lexicon: %,d terms, FST heap %,d bytes%n",
                lexicon.vocabSize(), lexicon.fstRamBytesUsed());
            System.err.printf("Stats:   %,d terms, %,d docs, %,d tokens%n",
                stats.vocabSize(), stats.fieldDocs(), stats.fieldTokens());

            final long t0 = System.nanoTime();
            final TermSuggest suggest = new TermSuggest(
                lexicon, stats, ANSI_HL_ON, ANSI_HL_OFF);
            final long buildMs = (System.nanoTime() - t0) / 1_000_000;

            System.err.printf("Suggest index built in %,d ms for %,d terms%n",
                buildMs, suggest.vocabSize());
            System.err.println();
            System.err.println("Type a query, empty line or Ctrl-D to quit.");
            System.err.printf("  1–%d chars → prefix search%n", TermSuggest.INFIX_THRESHOLD - 1);
            System.err.printf("  %d+  chars → infix (substring) search%n", TermSuggest.INFIX_THRESHOLD);
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
                final List<TermRow> results = suggest.suggest(query, limit);
                final long elapsedUs = (System.nanoTime() - qt0) / 1_000;

                final String mode = TermSuggest.asciiFold(query).length() < TermSuggest.INFIX_THRESHOLD
                    ? "prefix" : "infix";

                System.out.printf("%d hit%s in %,d µs [%s]%n",
                    results.size(),
                    results.size() == 1 ? "" : "s",
                    elapsedUs,
                    mode);

                for (final TermRow row : results) {
                    System.out.printf("  %,12d  %s%n", row.count(), row.hilite());
                }
                System.out.println();
            }
        }
    }
}
