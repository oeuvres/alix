package com.github.oeuvres.alix.lucene.analysis.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.MweLexicon;

import opennlp.tools.postag.MutableTagDictionary;
import opennlp.tools.postag.POSModel;

/**
 * Utility methods to load lexical resources from CSV-like files into
 * Lucene-oriented structures ({@link CharArrayMap}, {@link CharArraySet},
 * {@link LemmaLexicon}).
 * <p>
 * Conventions used by these loaders:
 * </p>
 * <ul>
 * <li>The first row is skipped when {@link CsvHeader#SKIP} is selected.</li>
 * <li>Rows whose selected "key" cell is blank are ignored.</li>
 * <li>Rows whose first cell starts with {@code '#'} are treated as
 * comments and ignored.</li>
 * <li>Rows with fewer columns than required are reported and skipped.</li>
 * </ul>
 * <p>The reader-based loaders take unread, caller-owned readers and set their
 * column limit. {@link #loadFiles} and {@link #loadResources} instead manage
 * the readers they open, including closure on failure. A configured instance
 * builds dictionaries from classpath resources.</p>
 */
public final class LexiconHelper
{
    /** Whether the first CSV row is a header. */
    public enum CsvHeader { SKIP, KEEP }

    /**
     * Duplicate handling policy.
     */
    public enum OnDuplicate
    {
        /**
         * Keep the existing mapping and ignore the new one.
         *
         * <p>
         * If the key is absent, insert the new mapping.
         * </p>
         */
        IGNORE,

        /**
         * Replace any existing mapping with the new one.
         */
        REPLACE,

        /**
         * Keep the existing mapping and report the duplicates.
         */
        REPORT,

        /**
         * Insert only if absent; if a mapping already exists for the same key, throw an
         * exception.
         */
        ERROR
    }

    private final Class<?> anchor;
    private final String directory;
    private final Report report;

    /**
     * Configures loading of lexical resources from a classpath directory.
     * @param anchor class used for resource lookup
     * @param directory classpath directory, absolute or relative to the anchor
     * @param report diagnostics sink; null suppresses warnings
     */
    public LexiconHelper(final Class<?> anchor, final String directory, final Report report)
    {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(directory, "directory");
        this.directory = directory.isEmpty() || directory.endsWith("/")
            ? directory : directory + "/";
        this.report = orSilent(report);
    }

    /**
     * Registers a lexical expression using only its analyzed surface tokens.
     * @param lexicon mutable target lexicon
     * @param analyzer entry analyzer
     * @param expression source expression
     * @param canonical canonical output (blank means the source expression)
     * @return true if at least two tokens were registered
     * @throws UncheckedIOException if analysis fails
     */
    public static boolean addExpression(final MweLexicon lexicon, final Analyzer analyzer,
        final CharSequence expression, final CharSequence canonical)
    {
        return addExpression(lexicon, analyzer, expression, canonical, Upos.TOKEN);
    }

    /**
     * Registers an expression and its declared lexical POS using surface tokens.
     * @param lexicon mutable target lexicon
     * @param analyzer entry analyzer
     * @param expression source expression
     * @param canonical canonical output (blank means the source expression)
     * @param pos lexical UPOS; TOKEN means unspecified
     * @return true if at least two tokens were registered
     * @throws UncheckedIOException if analysis fails
     * @throws IllegalArgumentException if the POS is unsupported
     */
    public static boolean addExpression(final MweLexicon lexicon, final Analyzer analyzer,
        final CharSequence expression, final CharSequence canonical, final Upos pos)
    {
        return addExpression(lexicon, analyzer, expression, canonical, pos,
            new Report.ReportConsole()::warn);
    }

    /**
     * Analyzes and registers a declaration with contextual diagnostics.
     * @param lexicon mutable target lexicon
     * @param analyzer entry analyzer
     * @param expression source expression
     * @param canonical canonical output
     * @param pos resolved lexical POS
     * @param warn diagnostic callback
     * @return true if an expression was registered
     */
    private static boolean addExpression(final MweLexicon lexicon, final Analyzer analyzer,
        final CharSequence expression, final CharSequence canonical, final Upos pos,
        final Consumer<String> warn)
    {
        final List<String> forms = expressionForms(analyzer, expression);
        final String output = expressionOutput(expression, canonical);
        if (forms.size() < 2 || output.isEmpty()) return false;
        registerExpression(lexicon, forms, output, pos, warn);
        return true;
    }

    /**
     * Builds a map from headered resources using columns zero and one.
     * @param capacity expected number of entries
     * @param ignoreCase whether keys are case-insensitive
     * @param policy duplicate handling policy
     * @param filenames resource filenames in loading order
     * @return populated map
     * @throws UncheckedIOException if a resource cannot be read
     */
    public CharArrayMap<char[]> buildMap(final int capacity, final boolean ignoreCase,
        final OnDuplicate policy, final String... filenames)
    {
        final CharArrayMap<char[]> map = new CharArrayMap<>(capacity, ignoreCase);
        loadResources((csv, diagnostics) -> loadMap(map, csv, policy, CsvHeader.SKIP, 0, 1, diagnostics), filenames);
        return map;
    }

    /**
     * Builds a set from the first columns of headered resources.
     * @param capacity expected number of entries
     * @param ignoreCase whether entries are case-insensitive
     * @param filenames resource filenames in loading order
     * @return populated set
     * @throws UncheckedIOException if a resource cannot be read
     */
    public CharArraySet buildSet(final int capacity, final boolean ignoreCase,
        final String... filenames)
    {
        final CharArraySet set = new CharArraySet(capacity, ignoreCase);
        loadResources((csv, diagnostics) -> loadSet(set, csv, 0, CsvHeader.SKIP, diagnostics), filenames);
        return set;
    }

    /**
     * Analyzes an expression into nonempty surface tokens, without lemma aliases.
     * @param analyzer entry analyzer
     * @param expression source expression
     * @return analyzed forms, or an empty list if a token was suppressed
     * @throws UncheckedIOException if analysis fails
     */
    public static List<String> expressionForms(final Analyzer analyzer, final CharSequence expression)
    {
        final String source = expression.toString().strip();
        if (source.isEmpty()) return List.of();
        final List<String> forms = new ArrayList<>();
        boolean suppressed = false;
        try (TokenStream stream = analyzer.tokenStream("mwe", source)) {
            final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                if (term.length() == 0) suppressed = true;
                else forms.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return suppressed ? List.of() : forms;
    }

    /**
     * Normalizes the declared output's apostrophes and hyphens, preserving case.
     * @param expression source expression
     * @param canonical optional canonical output; blank means source expression
     * @return normalized output
     */
    public static String expressionOutput(final CharSequence expression, final CharSequence canonical)
    {
        final StringBuilder output = new StringBuilder(canonical.toString().isBlank() ? expression : canonical);
        Char.trim(output);
        Char.translate(output, "\u2019\u2018\u02BC\u2010\u2011\u00AD", "'''---");
        return output.toString();
    }

    /**
     * Loads simple lexical MWEs from caller-selected CSV columns, without inflection.
     * @param lexicon mutable target lexicon
     * @param analyzer entry analyzer
     * @param csv open CSV reader, owned by the caller
     * @param colExpression source expression column
     * @param colCanonical canonical output column; use -1 for no separate output column
     * @param header header policy
     * @throws UncheckedIOException if reading or analysis fails
     */
    public static void loadExpressions(final MweLexicon lexicon, final Analyzer analyzer,
        final CSVReader csv, final int colExpression, final int colCanonical, final CsvHeader header)
    {
        loadExpressions(lexicon, analyzer, csv, colExpression, -1, colCanonical, header);
    }

    /**
     * Loads lexical MWEs with an optional UPOS column, without inflection.
     * Unsupported POS labels are reported and reduced to a standard UPOS prefix
     * when possible, or TOKEN otherwise.
     * @param lexicon mutable target lexicon
     * @param analyzer entry analyzer
     * @param csv unread, caller-owned CSV reader
     * @param colExpression source expression column
     * @param colPos lexical UPOS column; -1 means unspecified
     * @param colCanonical canonical output column; -1 means use source expression
     * @param header header policy
     * @throws UncheckedIOException if reading or analysis fails
     * @throws IllegalArgumentException if a column index is invalid
     */
    public static void loadExpressions(final MweLexicon lexicon, final Analyzer analyzer,
        final CSVReader csv, final int colExpression, final int colPos, final int colCanonical,
        final CsvHeader header)
    {
        loadExpressions(lexicon, analyzer, csv, colExpression, colPos, colCanonical, header,
            new Report.ReportConsole());
    }

    /**
     * Loads lexical MWEs using the supplied diagnostic sink.
     * @param lexicon mutable target lexicon
     * @param analyzer entry analyzer
     * @param csv unread, caller-owned reader
     * @param colExpression source expression column
     * @param colPos lexical UPOS column; -1 means unspecified
     * @param colCanonical canonical output column; -1 means source expression
     * @param header header policy
     * @param report diagnostic sink; null suppresses warnings
     * @throws UncheckedIOException if reading or analysis fails
     */
    public static void loadExpressions(final MweLexicon lexicon, final Analyzer analyzer,
        final CSVReader csv, final int colExpression, final int colPos, final int colCanonical,
        final CsvHeader header, final Report report)
    {
        checkColumnIndex(colExpression, "colExpression");
        if (colCanonical < -1 || colPos < -1) {
            throw new IllegalArgumentException("Optional column indices must be >= -1");
        }
        final int columns = Math.max(colExpression, Math.max(colPos, colCanonical)) + 1;
        final Report rep = orSilent(report);
        forEachDataRow(csv, header, columns, colExpression, rep, row -> {
            final String form = row.getCellAsString(colExpression);
            final String output = colCanonical < 0 ? form : row.getCellAsString(colCanonical);
            final String label = colPos < 0 ? "" : row.getCellAsString(colPos).strip();
            final Consumer<String> warn = mweWarnings(row, form, rep);
            final Upos pos = resolveMwePos(label, warn);
            try {
                addExpression(lexicon, analyzer, form, output, pos, warn);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(row.getSpec() + ":" + row.getLineNo()
                    + " " + form + ": " + e.getMessage(), e);
            } catch (UncheckedIOException e) {
                throw new UncheckedIOException(new IOException(row.getSpec() + ":" + row.getLineNo()
                    + " cannot analyze MWE: " + form, e));
            }
        });
    }

    /**
     * Loads headered form, POS, canonical declarations using a caller-supplied compiler.
     * Unsupported POS labels are reported and reduced to a standard UPOS prefix
     * when possible, or left unspecified otherwise.
     * @param csv open CSV reader, owned by the caller
     * @param handler expression registration callback
     * @throws UncheckedIOException if reading or analysis fails
     */
    public static void loadExpressions(final CSVReader csv, final ExpressionHandler handler)
    {
        loadExpressions(csv, handler, new Report.ReportConsole());
    }

    /**
     * Loads headered expression declarations with caller-selected diagnostics.
     * @param csv unread, caller-owned reader
     * @param handler expression registration callback
     * @param report diagnostic sink; null suppresses warnings
     * @throws UncheckedIOException if reading or analysis fails
     */
    public static void loadExpressions(final CSVReader csv, final ExpressionHandler handler,
        final Report report)
    {
        final Report rep = orSilent(report);
        forEachDataRow(csv, CsvHeader.SKIP, 3, 0, rep, row -> {
            final String form = row.getCellAsString(0).strip();
            if (form.isEmpty()) return;
            final Consumer<String> warn = mweWarnings(row, form, rep);
            final Upos pos = resolveMwePos(row.getCellAsString(1), warn);
            final String canonical = row.getCellAsString(2);
            try {
                handler.add(form, pos, canonical.isBlank() ? form : canonical, warn);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(row.getSpec() + ":" + row.getLineNo()
                    + " " + form + ": " + e.getMessage(), e);
            } catch (UncheckedIOException e) {
                throw new UncheckedIOException(new IOException(row.getSpec() + ":" + row.getLineNo()
                    + " cannot analyze MWE: " + form, e));
            }
        });
    }

    /** Callback for registering a lexical declaration. */
    @FunctionalInterface
    public interface ExpressionHandler
    {
        /**
         * Registers a declaration.
         * @param form source expression
         * @param pos resolved UPOS, or TOKEN for an unspecified POS
         * @param canonical canonical output
         * @param warn diagnostic callback including dictionary location
         */
        void add(String form, Upos pos, String canonical, Consumer<String> warn);
    }

    /**
     * Opens, loads and closes external CSV files in declaration order.
     * The callback must not close the reader.
     * @param files files in loading order
     * @param report diagnostic sink; null suppresses warnings
     * @param loader operation on an unread CSV reader and its diagnostic sink
     * @throws IOException if a file cannot be opened, read or closed
     */
    public static void loadFiles(final Iterable<Path> files, final Report report,
        final BiConsumer<CSVReader, Report> loader) throws IOException
    {
        Objects.requireNonNull(loader, "loader");
        final Report rep = orSilent(report);
        for (Path path : files) {
            try (CSVReader csv = new CSVReader(path)) {
                csv.report(rep);
                loader.accept(csv, rep);
            } catch (UncheckedIOException e) {
                throw new IOException("Cannot load lexical file " + path + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new IOException("Cannot open or close lexical file " + path, e);
            }
        }
    }

    /**
     * Loads a lemma dictionary with optional POS-specific entries. Every
     * accepted form has a POS-independent entry, even if its POS is unknown.
     *
     * @param lex lemma lexicon to populate
     * @param csv CSV reader
     * @param csvHeader header policy
     * @param formCol column of surface form
     * @param posCol column of POS name
     * @param lemmaCol column of lemma form
     * @param posResolver POS resolver, or null for the default resolver
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if lex or csv is null
     * @throws IllegalArgumentException if a column index is negative
     */
    public static void loadLemma(
        final LemmaLexicon lex,
        final CSVReader csv,
        final CsvHeader csvHeader,
        final int formCol,
        final int posCol,
        final int lemmaCol,
        final Function<String, Upos> posResolver)
        throws UncheckedIOException
    {
        loadLemma(lex, csv, csvHeader, formCol, posCol, lemmaCol, posResolver,
            new Report.ReportConsole());
    }

    /**
     * Loads a lemma dictionary with caller-selected diagnostics.
     * @param lex target lemma lexicon
     * @param csv unread, caller-owned reader
     * @param csvHeader header policy
     * @param formCol surface form column
     * @param posCol POS column
     * @param lemmaCol lemma column
     * @param posResolver POS resolver; null uses Upos.get
     * @param report diagnostic sink; null suppresses warnings
     * @throws UncheckedIOException on read error
     */
    public static void loadLemma(final LemmaLexicon lex, final CSVReader csv,
        final CsvHeader csvHeader, final int formCol, final int posCol, final int lemmaCol,
        final Function<String, Upos> posResolver, final Report report)
    {
        Objects.requireNonNull(lex, "lex");
        Objects.requireNonNull(csv, "csv");

        checkColumnIndex(formCol, "formCol");
        checkColumnIndex(posCol, "posCol");
        checkColumnIndex(lemmaCol, "lemmaCol");
        final PosResolver pr = new PosResolver(posResolver);

        final Consumer<CSVReader> handler = row -> {
            // normalize some chars before input entries
            StringBuilder form = row.getCell(formCol);
            Char.translate(form, "’", "'");
            Char.trim(form);
            if (form.isEmpty()) return;
            StringBuilder lemma = row.getCell(lemmaCol);
            if (!lemma.isEmpty()) {
                Char.translate(lemma, "’", "'");
                Char.trim(lemma);
            }
            // form=lemma
            else {
                lemma = form;
            }
            lex.put(form, lemma);
            final String rawPosName = row.getCellAsString(posCol);
            final Upos tag = pr.resolve(rawPosName, row.getLineNo());
            if (tag != null) {
                lex.put(form, tag.ordinal(), lemma);
            }
        };

        forEachDataRow(csv, csvHeader, maxRequiredCol(formCol, posCol, lemmaCol), formCol, report, handler);
        pr.report(csv.getSpec(), orSilent(report));
    }

    /**
     * Loads CSV mappings into a {@link CharArrayMap}.
     * <p>
     * Key and value columns are caller-selected.
     * </p>
     *
     * @param map        target map (key -&gt; char[] value)
     * @param csv        CSV reader
     * @param policy duplicate handling policy
     * @param csvHeader header policy
     * @param keyCol key column
     * @param valueCol value column
     * @param report optional diagnostic sink
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if {@code map} or {@code csv} is null
     */
    public static void loadMap(
        final CharArrayMap<char[]> map,
        final CSVReader csv,
        final OnDuplicate policy,
        final CsvHeader csvHeader,
        final int keyCol,
        final int valueCol,
        final Report report)
        throws UncheckedIOException
    {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(csv, "csv");
        final Report rep = orSilent(report);

        final Consumer<CSVReader> handler = row -> {
            final StringBuilder key = row.getCell(keyCol);
            if (map.containsKey(key)) {
                switch (policy) {
                    case IGNORE:
                        return;
                    case REPLACE:
                        map.put(key, row.getCellToCharArray(valueCol));
                        return;
                    case ERROR:
                        throw new RuntimeException("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                + " duplicate key=" + key);
                    case REPORT:
                        rep.warn("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                + " duplicate key=" + key + " oldValue=" + new String(map.get(key))
                                + " newValue=" + row.getCell(valueCol));
                        return;
                }
            }
            map.put(key, row.getCellToCharArray(valueCol));
        };

        forEachDataRow(csv, csvHeader, maxRequiredCol(keyCol, valueCol), keyCol, report, handler);
    }

    /**
     * Loads CSV mappings into a {@link CharsMap}.
     * <p>
     * Key and value columns are caller-selected.
     * </p>
     *
     * @param map        target map (key -&gt; char[] value)
     * @param csv        CSV reader
     * @param policy duplicate handling policy
     * @param csvHeader header policy
     * @param keyCol key column
     * @param valueCol value column
     * @param report optional diagnostic sink
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if {@code map} or {@code csv} is null
     */
    public static void loadMap(
        final CharsMap map,
        final CSVReader csv,
        final OnDuplicate policy,
        final CsvHeader csvHeader,
        final int keyCol,
        final int valueCol,
        final Report report)
        throws UncheckedIOException
    {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(csv, "csv");
        final Report rep = orSilent(report);

        final Consumer<CSVReader> handler = row -> {
            final StringBuilder key = row.getCell(keyCol);
            if (map.valueOrd(key) >= 0) {
                switch (policy) {
                    case IGNORE:
                        return;
                    case REPLACE:
                        map.put(key, row.getCell(valueCol));
                        return;
                    case ERROR:
                        throw new RuntimeException("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                + " duplicate key=" + key);
                    case REPORT:
                        rep.warn("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                + " duplicate key=" + key + " oldValue=" + map.get(key)
                                + " newValue=" + row.getCell(valueCol));
                        return;
                }
            }
            map.put(key, row.getCell(valueCol));
        };

        forEachDataRow(csv, csvHeader, maxRequiredCol(keyCol, valueCol), keyCol, report, handler);
    }

    /**
     * Loads an OpenNLP POS model from a classpath resource.
     * @param anchor class used for resource lookup
     * @param path resource path
     * @return loaded POS model
     * @throws IllegalStateException if the resource is missing
     * @throws ExceptionInInitializerError if the resource cannot be read
     */
    public static POSModel loadPosModel(final Class<?> anchor, final String path)
    {
        try (InputStream in = anchor.getResourceAsStream(path)) {
            if (in == null)
                throw new IllegalStateException("Missing resource: " + path);
            return new POSModel(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Loads proper-name forms from the first column of a headerless CSV.
     * @param tagDic target tag dictionary
     * @param csv open CSV reader
     * @param report optional report sink
     * @throws UncheckedIOException on read error
     */
    public static void loadPROPN(
        final MutableTagDictionary tagDic,
        final CSVReader csv,
        final Report report)
        throws UncheckedIOException
    {
        Objects.requireNonNull(tagDic, "tagDic");
        Objects.requireNonNull(csv, "csv");
        final Consumer<CSVReader> handler = row -> {
            final String tag = Upos.PROPN.name();
            final StringBuilder form = row.getCell(0);
            Char.translate(form, "’", "'");
            Char.trim(form);
            if (form.isEmpty()) {
                return;
            }
            addTag(tagDic, form.toString(), tag);
        };
        forEachDataRow(csv, CsvHeader.KEEP, 1, 0, report, handler);
    }

    /**
     * Opens, loads and closes classpath resources in declaration order.
     * The callback must not close the reader.
     * @param loader operation on an unread CSV reader and its diagnostic sink
     * @param filenames resource filenames in loading order
     * @throws UncheckedIOException on open, read or close failure
     */
    public void loadResources(final BiConsumer<CSVReader, Report> loader, final String... filenames)
    {
        Objects.requireNonNull(loader, "loader");
        for (String filename : filenames) {
            final String path = directory + filename;
            try (CSVReader csv = new CSVReader(anchor, path)) {
                csv.report(report);
                loader.accept(csv, report);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot open or close lexical resource " + path, e);
            }
        }
    }

    /**
     * Load a CSV reader into a {@link CharArraySet}.
     *
     * @param set        target set
     * @param csv        CSV reader
     * @param col        column index containing the form to add
     * @param csvHeader header policy
     */
    public static void loadSet(
        final CharArraySet set,
        final CSVReader csv,
        final int col,
        final CsvHeader csvHeader)
        throws UncheckedIOException
    {
        loadSet(set, csv, col, csvHeader, new Report.ReportConsole());
    }

    /**
     * Loads a set with caller-selected diagnostics.
     * @param set target set
     * @param csv unread, caller-owned reader
     * @param col form column
     * @param csvHeader header policy
     * @param report diagnostic sink; null suppresses warnings
     * @throws UncheckedIOException on read error
     */
    public static void loadSet(final CharArraySet set, final CSVReader csv,
        final int col, final CsvHeader csvHeader, final Report report)
    {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(csv, "csv");
        checkColumnIndex(col, "col");

        final Consumer<CSVReader> handler = row -> {
            final StringBuilder form = row.getCell(col);
            Char.trim(form);
            if (form.length() == 0) {
                return; // rtrim may empty the cell
            }
            set.add(form);
        };

        forEachDataRow(csv, csvHeader, col + 1, col, report, handler);
    }

    /**
     * Loads POS tags from a CSV reader into a mutable OpenNLP tag dictionary.
     * If {@code freqCol < 0}, every otherwise valid row is considered. Otherwise
     * the row must contain a numeric frequency greater than or equal to
     * {@code freqMin}. A frequency of {@code 0.0} is accepted when
     * {@code freqMin == 0.0}.
     *
     * @param tagDic       mutable tag dictionary to populate
     * @param csv          CSV reader
     * @param csvHeader    header policy
     * @param formCol      column containing the surface form
     * @param tagCol       column containing the source POS tag
     * @param freqCol      frequency column; if negative, frequency is ignored
     * @param freqMin      minimum accepted frequency when {@code freqCol >= 0}
     * @param posResolver  POS resolver; {@code null} uses {@code Upos::get}
     * @param report       diagnostic sink; null suppresses warnings
     * @throws UncheckedIOException on read error
     */
    public static void loadTags(
        final MutableTagDictionary tagDic,
        final CSVReader csv,
        final CsvHeader csvHeader,
        final int formCol,
        final int tagCol,
        final int freqCol,
        final double freqMin,
        final Function<String, Upos> posResolver,
        final Report report)
        throws UncheckedIOException
    {
        Objects.requireNonNull(tagDic, "tagDic");
        Objects.requireNonNull(csv, "csv");
        Objects.requireNonNull(csvHeader, "csvHeader");
        checkColumnIndex(formCol, "formCol");
        checkColumnIndex(tagCol, "tagCol");

        final PosResolver pr = new PosResolver(posResolver);

        final Report rep = orSilent(report);

        final int minCols = (freqCol < 0)
            ? maxRequiredCol(formCol, tagCol)
            : maxRequiredCol(formCol, tagCol, freqCol);

        final Consumer<CSVReader> handler = row -> {
            if (freqCol >= 0) {
                final String freqText = row.getCellAsString(freqCol);
                if (freqText == null || freqText.isBlank()) {
                    return;
                }

                final double frequency;
                try {
                    frequency = Double.parseDouble(freqText.trim());
                } catch (NumberFormatException e) {
                    rep.warn(
                        row.getSpec() + ":" + row.getLineNo()
                        + " invalid frequency=" + freqText
                    );
                    return;
                }

                if (!Double.isFinite(frequency)) {
                    rep.warn(row.getSpec() + ":" + row.getLineNo()
                        + " invalid frequency=" + freqText);
                    return;
                }
                if (frequency < freqMin) return;
            }

            final Upos pos = pr.resolve(row.getCellAsString(tagCol), row.getLineNo());
            if (pos == null) {
                return;
            }
            final String tag = pos.name();

            final StringBuilder form = row.getCell(formCol);
            Char.translate(form, "’", "'");
            Char.trim(form);
            if (form.isEmpty()) {
                return;
            }

            addTag(tagDic, form.toString(), tag);
        };

        forEachDataRow(csv, csvHeader, minCols, formCol, report, handler);
        pr.report(csv.getSpec(), rep);
    }

    /**
     * Registers already-tokenized forms under a shared canonical POS.
     * Ancestor/descendant declarations retain the more specific tag; other
     * conflicts retain the latest explicit tag and report the change.
     * @param lexicon mutable target lexicon
     * @param forms analyzed component tokens
     * @param canonical canonical output
     * @param pos resolved lexical POS
     * @param warn diagnostic callback
     * @throws IllegalArgumentException if the POS is unsupported
     */
    public static void registerExpression(final MweLexicon lexicon,
        final List<? extends CharSequence> forms, final CharSequence canonical,
        final Upos pos, final Consumer<String> warn)
    {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(warn, "warn");
        if (pos != Upos.TOKEN && pos.base() == null) {
            throw new IllegalArgumentException("Unsupported MWE POS: " + pos);
        }
        lexicon.addExpression(forms, canonical, pos.ordinal(), (previous, incoming) -> {
            final Upos oldTag = Upos.get(previous);
            if (isAncestor(oldTag, pos)) return incoming;
            if (isAncestor(pos, oldTag)) return previous;
            warn.accept("conflicting POS for " + canonical + ": " + oldTag
                + " / " + pos + "; retained " + pos);
            return incoming;
        });
    }

    /**
     * Resolves a source MWE tag, preserving supported refinements and aliases.
     * Unknown refinements use their standard UPOS prefix when recognizable;
     * other unsupported labels become TOKEN. Approximation is reported.
     * @param raw source POS label
     * @param warn diagnostic callback
     * @return lexical UPOS, or TOKEN when unspecified
     */
    public static Upos resolveMwePos(final String raw, final Consumer<String> warn)
    {
        final String label = raw.strip();
        if (label.isEmpty()) return Upos.TOKEN;
        Upos tag = Upos.get(label);
        if (tag == Upos.TOKEN || (tag != null && tag.base() != null)) return tag;

        // Recognized composite notations are aliases, not approximations.
        tag = Upos.get(label.replace('+', '_'));
        if (tag != null && tag.base() != null) return tag;
        if (label.equals("ADVconj")) return Upos.ADV;

        tag = Upos.TOKEN;
        for (Upos base : Upos.values()) {
            if (base.base() != base) continue;
            final String prefix = base.name();
            if (!label.startsWith(prefix) || label.length() == prefix.length()) continue;
            final char suffix = label.charAt(prefix.length());
            if (Character.isLowerCase(suffix) || suffix == '_' || suffix == ':' || suffix == '+') {
                tag = base;
                break;
            }
        }
        warn.accept("unsupported MWE POS " + label + " -> " + tag.name());
        return tag;
    }

    /**
     * Tests whether a tag is an ancestor of another tag.
     * @param ancestor candidate ancestor
     * @param tag candidate descendant
     * @return true if the ancestor occurs in the parent chain
     */
    private static boolean isAncestor(final Upos ancestor, final Upos tag)
    {
        if (ancestor == null) return false;
        for (Upos parent = tag == null ? null : tag.parent(); parent != null;
                parent = parent.parent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    /**
     * Captures a dictionary row location for warnings during registration.
     * @param row current dictionary row
     * @param form source expression
     * @param report diagnostic sink
     * @return callback that prefixes messages with the dictionary location
     */
    private static Consumer<String> mweWarnings(final CSVReader row, final String form,
        final Report report)
    {
        final String location = row.getSpec() + ":" + row.getLineNo() + " " + form + ": ";
        return message -> report.warn(location + message);
    }

    /**
     * Tests a selected key for emptiness or whitespace without allocating a String.
     * @param key selected CSV cell
     * @return true if the key is blank
     */
    private static boolean isBlank(final CharSequence key)
    {
        for (int i = 0; i < key.length(); i++) {
            if (!Char.isSpace(key.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Validate a single zero-based column index.
     *
     * @param col  column index
     * @param name parameter name for error messages
     * @throws IllegalArgumentException if {@code col < 0}
     */
    private static void checkColumnIndex(final int col, final String name)
    {
        if (col < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got: " + col);
        }
    }

    /**
     * Returns {@code report} if non-null, otherwise the silent sink, so callers never have
     * to null-check before reporting.
     *
     * @param report a report sink, possibly {@code null}
     * @return a non-null report sink
     */
    private static Report orSilent(final Report report)
    {
        return (report == null) ? Report.ReportNull.INSTANCE : report;
    }

    /**
     * Iterates over the data rows of {@code csv}, applying shared filtering before handing
     * each surviving row to {@code handler}.
     * <p>
     * With {@link CsvHeader#SKIP} the first row is consumed as a header. For every subsequent
     * row, in order: a blank line ({@code getCellCount() == 0}) is skipped; a row whose first
     * cell starts with {@code '#'} is treated as a comment and skipped; a row with fewer than
     * {@code minCols} cells is reported through {@code report} and skipped (this is what keeps
     * a short row from reaching {@code handler} and throwing on a missing column); any
     * row with a blank selected key is skipped; every remaining row reaches the handler.
     *
     * @param csv       CSV reader
     * @param csvHeader whether to consume the first row as a header
     * @param minCols   minimum number of cells a row must have to reach the handler
     * @param keyCol selected key column, whose blank cells are skipped
     * @param report optional diagnostic sink for short-row warnings
     * @param handler   row consumer for accepted rows
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if any argument is null
     */
    private static void forEachDataRow(
        final CSVReader csv,
        final CsvHeader csvHeader,
        final int minCols,
        final int keyCol,
        final Report report,
        final Consumer<CSVReader> handler)
    {
        Objects.requireNonNull(csv, "csv");
        Objects.requireNonNull(csvHeader, "csvHeader");
        Objects.requireNonNull(handler, "handler");
        final Report rep = orSilent(report);
        csv.cellMax(minCols);
        csv.report(rep);
        try {
            if (csvHeader == CsvHeader.SKIP && !csv.readRow()) {
                return; // empty file
            }
            while (csv.readRow()) {
                final int count = csv.getCellCount();
                if (count == 0) {
                    continue; // blank line
                }
                final StringBuilder first = csv.getCell(0);
                if (first.length() > 0 && first.charAt(0) == '#') {
                    continue; // comment
                }
                if (count < minCols) {
                    rep.warn(csv.getSpec() + ":" + csv.getLineNo()
                            + " expected at least " + minCols + " columns, got " + count);
                    continue;
                }
                if (isBlank(csv.getCell(keyCol))) continue;
                handler.accept(csv);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(csv.getSpec() + ":" + csv.getLineNo()
                + " cannot read lexical row", e);
        }
    }

    /**
     * Compute the minimum required column count from zero-based column indices.
     *
     * @param cols zero-based column indices
     * @return {@code max(cols) + 1}
     * @throws IllegalArgumentException if any column index is negative
     */
    private static int maxRequiredCol(final int... cols)
    {
        int max = -1;
        for (int c : cols) {
            if (c < 0) {
                throw new IllegalArgumentException("Column index must be >= 0, got: " + c);
            }
            if (c > max)
                max = c;
        }
        return max + 1;
    }

    /**
     * Adds one distinct tag to a word's dictionary entry.
     * @param dictionary target tag dictionary
     * @param word normalized word
     * @param tag tag to add
     */
    private static void addTag(final MutableTagDictionary dictionary, final String word,
        final String tag)
    {
        final String[] old = dictionary.getTags(word);
        if (old == null || old.length == 0) {
            dictionary.put(word, tag);
            return;
        }
        for (String value : old) {
            if (tag.equals(value)) return;
        }
        final String[] tags = java.util.Arrays.copyOf(old, old.length + 1);
        tags[old.length] = tag;
        dictionary.put(word, tags);
    }

    /** Resolves POS names and counts unknown labels for one resource load. */
    private static final class PosResolver
    {
        private final Function<String, Upos> mapping;
        private final Map<String, Integer> unknown = new HashMap<>();
        private final Map<String, Integer> firstLine = new HashMap<>();

        /**
         * Creates a resolver local to one load.
         * @param mapping optional source-specific mapping; null uses Upos.get
         */
        private PosResolver(final Function<String, Upos> mapping)
        {
            this.mapping = mapping == null ? Upos::get : mapping;
        }

        /**
         * Resolves a source POS label and counts unsupported labels.
         * @param raw source label
         * @param line physical starting line of the CSV row
         * @return resolved tag, or null
         */
        private Upos resolve(final String raw, final int line)
        {
            final Upos tag = raw == null || raw.isBlank() ? null : mapping.apply(raw.strip());
            if (tag == null) {
                final String label = raw == null ? "<null>" : raw.isBlank() ? "<blank>" : raw.strip();
                unknown.merge(label, 1, Integer::sum);
                firstLine.putIfAbsent(label, line);
            }
            return tag;
        }

        /**
         * Reports unsupported labels once per file.
         * @param source source description
         * @param report diagnostic sink
         */
        private void report(final String source, final Report report)
        {
            if (unknown.isEmpty()) return;
            report.warn(source + " unknown POS tags: " + unknown.size());
            unknown.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> report.warn(source + ":" + firstLine.get(e.getKey())
                    + " unknown POS=" + e.getKey() + " (" + e.getValue() + " occurrence(s))"));
        }
    }
}
