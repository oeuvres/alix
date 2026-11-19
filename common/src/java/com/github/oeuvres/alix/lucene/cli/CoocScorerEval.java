package com.github.oeuvres.alix.lucene.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.SpanQueryParser;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.snippets.TopCoocSnippets;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;

/**
 * Exports directly indexed concordance rankings for comparing Simple Maths,
 * residual IDF, empirical dispersion, and occupancy-bound scorers.
 *
 * <p>Each pivot is collected once, then the same {@link TopTerms} population is
 * ranked four times. The focus is one virtual document for scoring purposes;
 * source-document and snippet counts are exported for diagnostics.</p>
 *
 * <p>Example:</p>
 * <pre>
 * java com.github.oeuvres.alix.lucene.cli.CoocScorerEval \
 *     --index /path/to/piaget --out cooc-eval.tsv \
 *     --pivots enfant,connaissance,pensée,instrument --window 30 --top 100
 * </pre>
 *
 * <p>Optional {@code --k 5000} applies the same Simple Maths smoothing centre
 * to all four scorers. Without it, each scorer retains its own default.</p>
 */
public final class CoocScorerEval
{
    /** Scorers evaluated in output order. */
    private static final String[] SCORERS = {
        "simple", "fisher", "simpledisp", "simpleocc"
    };

    /** Default pivots spanning several Piaget research themes. */
    private static final List<String> DEFAULT_PIVOTS = List.of(
        "enfant", "connaissance", "pensée", "instrument", "instrumental", "outil", "assimilation",
        "égocentrisme", "langage", "morale", "bâton", "société"
    );

    /** Prevents instantiation of this CLI. */
    private CoocScorerEval() {}

    /**
     * Opens the index, collects concordances once per pivot, and writes a TSV.
     *
     * @param args command-line options (see the class example and usage text)
     * @throws IOException if the index or output cannot be read or written
     */
    public static void main(final String[] args) throws IOException
    {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            usage();
            return;
        }
        final Options options = parse(args);
        try (LuceneIndex index = LuceneIndex.open(options.index());
                BufferedWriter out = Files.newBufferedWriter(options.out(), StandardCharsets.UTF_8)) {
            final String fieldName = options.field() == null ? index.content() : options.field();
            final FlucText field = index.flucText(fieldName);
            if (field == null) {
                throw new IllegalArgumentException("Not a tokenized text field: " + fieldName);
            }
            final SpanQueryParser parser = field.spanQueryParser();
            final TermLexicon lexicon = field.termLexicon();
            final TermStats stats = field.termStats();
            writeHeader(out);

            for (final String pivot : options.pivots()) {
                final SpanQueryParser.ParseResult parsed = parser.parse(pivot, 0);
                final SpanQuery query = parsed.spanQuery();
                if (query == null) {
                    System.err.println("Skipping unresolved pivot: " + pivot);
                    continue;
                }

                final TopTerms terms = field.topTerms();
                final TopCoocSnippets collector = new TopCoocSnippets(
                        stats, field.termRail(), options.window(), options.window())
                        .bindTo(terms.beginPopulation());
                final SpanWalker walker = new SpanWalker(
                        index.searcher(), query,
                        new DocSnippets(DocSnippets.Usage.POSITIONS, options.window()));
                walker.walk(collector);
                collector.complete();
                terms.populationExclude(lexicon.termIds(query));

                if (terms.tokens() == 0) {
                    System.err.println("Skipping empty concordance: " + pivot);
                    continue;
                }

                for (final String scorerName : SCORERS) {
                    final KeynessScorer scorer = scorer(scorerName, options.k());
                    scorer.specif(options.specif());
                    terms.rank(scorer, options.top());
                    int rank = 0;
                    for (final TermEntry entry : terms) {
                        writeRow(out, pivot, scorerName, ++rank, entry, terms, collector,
                                stats, options);
                    }
                }
                System.err.println("Completed " + pivot + ": " + collector.contextCount()
                        + " contexts, " + collector.documentCount() + " documents");
            }
        }
    }

    /**
     * Parses CLI options, retaining sensible defaults for comparison runs.
     *
     * @param args raw command-line arguments
     * @return parsed and validated options
     * @throws IllegalArgumentException if a required argument is missing or invalid
     */
    private static Options parse(final String[] args)
    {
        Path index = null;
        Path out = null;
        String field = null;
        List<String> pivots = DEFAULT_PIVOTS;
        int window = 30;
        int top = 100;
        double specif = 0.5d;
        double k = Double.NaN;
        for (int i = 0; i < args.length; i++) {
            final String option = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            final String value = args[++i];
            switch (option) {
                case "--index" -> index = Path.of(value);
                case "--out" -> out = Path.of(value);
                case "--field" -> field = value;
                case "--pivots" -> pivots = Arrays.stream(value.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                case "--window" -> window = Integer.parseInt(value);
                case "--top" -> top = Integer.parseInt(value);
                case "--specif" -> specif = Double.parseDouble(value);
                case "--k" -> k = Double.parseDouble(value);
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        if (index == null || out == null) {
            throw new IllegalArgumentException("Both --index and --out are required");
        }
        if (pivots.isEmpty() || window < 0 || top < 1
                || !Double.isFinite(specif) || specif < 0d || specif > 1d
                || (!Double.isNaN(k) && (!Double.isFinite(k) || k <= 0d))) {
            throw new IllegalArgumentException("Invalid pivots, window, top, specif, or k");
        }
        return new Options(index, out, field, pivots, window, top, specif, k);
    }

    /**
     * Constructs one fresh scorer for a ranking; no scorer state is reused.
     *
     * @param name output scorer name
     * @param k fixed smoothing centre, or NaN for the scorer default
     * @return new scorer
     */
    private static KeynessScorer scorer(final String name, final double k)
    {
        return switch (name) {
            case "fisher" -> new KeynessScorer.FisherAlpha();
            case "simple" -> new KeynessScorer.SimpleMaths(k);
            case "simpledisp" -> new KeynessScorer.SimpleDispersion(k);
            case "simpleocc" -> new KeynessScorer.SimpleOccupancyAlpha(k);
            default -> throw new IllegalArgumentException("Unsupported scorer: " + name);
        };
    }

    /**
     * Removes newlines and tabs from a TSV text cell.
     *
     * @param value source text
     * @return one-line TSV-safe text
     */
    private static String tsv(final String value)
    {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /** Prints the supported command-line options. */
    private static void usage()
    {
        System.err.println("Usage: CoocScorerEval --index DIR --out FILE [--field FIELD]"
                + " [--pivots a,b,c] [--window 30] [--top 100] [--specif 0.5] [--k 5000]");
    }

    /**
     * Writes one TSV header.
     *
     * @param out output writer
     * @throws IOException if writing fails
     */
    private static void writeHeader(final BufferedWriter out) throws IOException
    {
        out.write("pivot\tscorer\trank\tterm\tscore\ttf\tcf\tdf"
                + "\tfocusDocs\tfocusSnippets\tfocusTokens\tpivotCount"
                + "\tcorpusTokens\tcorpusDocs\twindow\tspecif\tk\n");
    }

    /**
     * Writes one ranked term with full-field statistics and focus metadata.
     *
     * @param out output writer
     * @param pivot original pivot text
     * @param scorerName scorer identifier
     * @param rank one-based rank
     * @param entry current term entry
     * @param terms current focus population
     * @param collector current snippet collector
     * @param stats whole-field term statistics
     * @param options CLI settings
     * @throws IOException if writing fails
     */
    private static void writeRow(
            final BufferedWriter out,
            final String pivot,
            final String scorerName,
            final int rank,
            final TermEntry entry,
            final TopTerms terms,
            final TopCoocSnippets collector,
            final TermStats stats,
            final Options options) throws IOException
    {
        out.write(tsv(pivot) + "\t" + scorerName + "\t" + rank + "\t"
                + tsv(entry.form()) + "\t" + entry.score() + "\t"
                + entry.freq() + "\t" + entry.fieldFreq() + "\t" + entry.fieldDocs()
                + "\t" + collector.documentCount() + "\t" + collector.contextCount()
                + "\t" + terms.tokens() + "\t" + collector.pivotCount()
                + "\t" + stats.fieldTokens() + "\t" + stats.fieldDocs()
                + "\t" + options.window() + "\t" + options.specif()
                + "\t" + (Double.isNaN(options.k()) ? "auto" : options.k()) + "\n");
    }

    /**
     * Immutable CLI settings.
     *
     * @param index index directory
     * @param out TSV output path
     * @param field text field, or null for index default
     * @param pivots pivot expressions
     * @param window context words on each side
     * @param top ranked terms retained per scorer
     * @param specif scorer specificity
     * @param k smoothing centre, or NaN for individual scorer defaults
     */
    private record Options(
            Path index, Path out, String field, List<String> pivots,
            int window, int top, double specif, double k) {}
}
