package com.github.oeuvres.alix.lucene.analysis.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Consumer;

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
 * <li>Rows with fewer columns than required are ignored.</li>
 * </ul>
 * <p>Every CSV loader takes an unread, caller-owned reader. The caller opens
 * and closes it and configures the separator if necessary; the loader sets the
 * column limit. The class is stateless.</p>
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

    /**
     * Utility class; no instance.
     */
    private LexiconHelper()
    {
        // no-op
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
     * @throws IllegalArgumentException if the POS is unsupported or conflicts
     */
    public static boolean addExpression(final MweLexicon lexicon, final Analyzer analyzer,
        final CharSequence expression, final CharSequence canonical, final Upos pos)
    {
        Objects.requireNonNull(pos, "pos");
        if (pos != Upos.TOKEN && pos.base() == null) {
            throw new IllegalArgumentException("Unsupported MWE POS: " + pos);
        }
        final List<String> forms = expressionForms(analyzer, expression);
        final String output = expressionOutput(expression, canonical);
        if (forms.size() < 2 || output.isEmpty()) return false;
        lexicon.addExpression(forms, output, pos.ordinal());
        return true;
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
     * @throws IllegalArgumentException if a column index is invalid or POS conflicts
     */
    public static void loadExpressions(final MweLexicon lexicon, final Analyzer analyzer,
        final CSVReader csv, final int colExpression, final int colPos, final int colCanonical,
        final CsvHeader header)
    {
        checkColumnIndex(colExpression, "colExpression");
        if (colCanonical < -1 || colPos < -1) {
            throw new IllegalArgumentException("Optional column indices must be >= -1");
        }
        final int columns = Math.max(colExpression, Math.max(colPos, colCanonical)) + 1;
        final Report report = new Report.ReportConsole();
        forEachDataRow(csv, header, columns, colExpression, report, row -> {
            final String form = row.getCellAsString(colExpression);
            final String output = colCanonical < 0 ? form : row.getCellAsString(colCanonical);
            final String label = colPos < 0 ? "" : row.getCellAsString(colPos).strip();
            final Upos pos = mwePos(label, row, form, report);
            try {
                addExpression(lexicon, analyzer, form, output, pos);
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
        final Report report = new Report.ReportConsole();
        forEachDataRow(csv, CsvHeader.SKIP, 3, 0, report, row -> {
            final String form = row.getCellAsString(0).strip();
            if (form.isEmpty()) return;
            final Upos pos = mwePos(row.getCellAsString(1).strip(), row, form, report);
            final String canonical = row.getCellAsString(2);
            try {
                handler.add(form, pos == Upos.TOKEN ? "" : pos.name(),
                    canonical.isBlank() ? form : canonical);
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
         * @param pos resolved UPOS name, or empty for an unspecified POS
         * @param canonical canonical output
         */
        void add(String form, String pos, String canonical);
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
            final Upos tag = pr.resolve(rawPosName);
            if (tag != null) {
                lex.put(form, tag.ordinal(), lemma);
            }
        };

        forEachDataRow(csv, csvHeader, maxRequiredCol(formCol, posCol, lemmaCol), formCol, null, handler);
        pr.report(csv.getSpec(), new Report.ReportConsole());
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

        forEachDataRow(csv, csvHeader, col + 1, col, null, handler);
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
     * @param report       diagnostic sink; null suppresses row warnings but reports unknown POS to the console
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

                if (!Double.isFinite(frequency) || frequency < freqMin) {
                    return;
                }
            }

            final Upos pos = pr.resolve(row.getCellAsString(tagCol));
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
        pr.report(csv.getSpec(), report == null ? new Report.ReportConsole() : report);
    }

    /**
     * Resolves an MWE POS declaration, preserving supported refinements.
     * Unsupported refinements fall back to their standard UPOS prefix; labels
     * with no recognized category become TOKEN. Every fallback is reported.
     * A blank declaration is intentionally untagged and needs no warning.
     *
     * @param label trimmed source POS label
     * @param row current dictionary row, for diagnostics
     * @param form source expression
     * @param report diagnostic sink
     * @return supported lexical UPOS, or TOKEN when unspecified
     */
    private static Upos mwePos(final String label, final CSVReader row,
        final String form, final Report report)
    {
        if (label.isEmpty()) return Upos.TOKEN;
        Upos tag = Upos.get(label);
        if (tag == Upos.TOKEN || (tag != null && tag.base() != null)) return tag;

        // Legacy composite labels such as ADP+DET correspond to ADP_DET.
        tag = Upos.get(label.replace('+', '_'));
        if (tag == null || tag.base() == null) {
            tag = Upos.TOKEN;
            for (Upos base : Upos.values()) {
                if (base.base() != base) continue;
                final String prefix = base.name();
                if (!label.startsWith(prefix) || label.length() == prefix.length()) continue;
                // Refinements begin with a lowercase suffix or a separator.
                // Do not interpret an arbitrary uppercase label such as XYZ as X.
                final char suffix = label.charAt(prefix.length());
                if (Character.isLowerCase(suffix) || suffix == '_' || suffix == ':' || suffix == '+') {
                    tag = base;
                    break;
                }
            }
        }
        report.warn(row.getSpec() + ":" + row.getLineNo() + " " + form
            + ": unsupported MWE POS " + label + " -> " + tag.name());
        return tag;
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
        if (report != null) csv.report(report);
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
            throw new UncheckedIOException(e);
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
         * @return resolved tag, or null
         */
        private Upos resolve(final String raw)
        {
            final Upos tag = raw == null || raw.isBlank() ? null : mapping.apply(raw.strip());
            if (tag == null) {
                final String label = raw == null ? "<null>" : raw.isBlank() ? "<blank>" : raw.strip();
                unknown.merge(label, 1, Integer::sum);
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
                .forEach(e -> report.warn(e.getKey() + ": " + e.getValue()));
        }
    }
}
