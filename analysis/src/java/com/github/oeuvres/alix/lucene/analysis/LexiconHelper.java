package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.MweLexicon;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.WordTokenizer;

import opennlp.tools.postag.POSModel;

/**
 * Utility methods to load lexical resources from CSV-like files into
 * Lucene-oriented structures ({@link CharArrayMap}, {@link CharArraySet},
 * {@link LemmaLexicon}).
 * <p>
 * Conventions used by these loaders:
 * </p>
 * <ul>
 * <li>By default, the first row is assumed to be a header and is skipped.</li>
 * <li>Rows whose selected "key" cell is blank are ignored.</li>
 * <li>Rows whose selected "key" cell starts with {@code '#'} are treated as
 * comments and ignored.</li>
 * <li>Rows with fewer columns than required are ignored.</li>
 * </ul>
 * <p>
 * The class is intentionally stateless; all methods are static.
 * </p>
 */
public final class LexiconHelper
{
    public enum CsvHeader
    {
        SKIP, KEEP
    };
    
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
     * Functional interface used internally to process a validated CSV row.
     */
    private abstract static class CsvRowHandler
    {
        int read = 0;
        int skipped = 0;
        int accepted = 0;
        
        /**
         * Consume the current row of {@code csv}. The row has already passed
         * the common checks performed by
         * {@link #forEachDataRow(CSVReader, int, int, boolean, CsvRowHandler)}.
         *
         * @param csv CSV reader positioned on the current row
         * @throws UncheckedIOException if processing needs to propagate an I/O error
         */
        protected abstract boolean accept(CSVReader csv) throws UncheckedIOException;
    }
    
    /**
     * Load one column of expressions
     *
     * @param lexicon
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if {@code lexicon}, {@code anchor}, or
     *                              {@code resourcePath} is null
     */
    public static void loadExpressions(
        final MweLexicon lexicon,
        final WordTokenizer tokenizer,
        final Path file)
    {
        Objects.requireNonNull(file, "file");
        try (CSVReader csv = new CSVReader(file)) {
            csv.cellMax(2);
            loadExpressions(lexicon, tokenizer, csv, 0, 1, CsvHeader.SKIP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * Load one column of expressions
     *
     * @param lexicon
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if {@code lexicon}, {@code anchor}, or
     *                              {@code resourcePath} is null
     */
    public static void loadExpressions(
        final MweLexicon lexicon,
        final WordTokenizer tokenizer,
        final Class<?> anchor,
        final String resourcePath)
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (CSVReader csv = new CSVReader(anchor, resourcePath)) {
            csv.cellMax(2);
            loadExpressions(lexicon, tokenizer, csv, 0, 1, CsvHeader.SKIP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * Load a CSV reader into a {@link MweLexicon}.
     *
     * @param lexicon
     * @param csv        CSV reader
     * @param col        column index containing the form to add
     * @param skipHeader if {@code true}, the first row is skipped
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if {@code lexicon} or {@code csv} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadExpressions(
        final MweLexicon lexicon,
        final WordTokenizer tokenizer,
        final CSVReader csv,
        final int colExpression,
        final int colCanonical,
        final CsvHeader csvHeader)
    {
        Objects.requireNonNull(lexicon, "lexicon");
        Objects.requireNonNull(csv, "csv");
        checkColumnIndex(colExpression, "colExpression");
        checkColumnIndex(colCanonical, "colCanonical");
        
        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws UncheckedIOException
            {
                final StringBuilder expression = row.getCell(colExpression);
                if (expression.length() == 0)
                    return false;
                List<String> words = tokenizer.tokenize(expression);
                final StringBuilder canonical = row.getCell(colCanonical).length() > 0?
                    row.getCell(colCanonical):
                    expression;
                for (int i = 0; i < canonical.length(); i++) canonical.setCharAt(i, tokenizer.normalizeChar(canonical.charAt(i)));
                lexicon.addExpression(words, canonical);
                return true;
            }
        };
        
        forEachDataRow(csv, csvHeader, maxRequiredCol(colExpression, colCanonical), Report.ReportNull.INSTANCE, handler);
    }
    
    /**
     * Load a 2-column CSV reader into a {@link CharArrayMap}.
     * <p>
     * Column 0 = key, column 1 = value.
     * </p>
     *
     * @param map        target map (key -&gt; char[] value)
     * @param csv        CSV reader
     * @param replace    if {@code true}, overwrite existing keys; otherwise
     *                   keep existing entries
     * @param skipHeader if {@code true}, the first row is skipped
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
        csv.report(rep);

        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws UncheckedIOException
            {
                final StringBuilder key = row.getCell(keyCol);
                if (map.containsKey(key)) {
                    switch (policy) {
                        case IGNORE:
                            return false;
                        case REPLACE:
                            map.put(key, row.getCellToCharArray(valueCol));
                            return true;
                        case ERROR:
                            throw new RuntimeException("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                    + " duplicate key=" + key);
                        case REPORT:
                            rep.warn("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                    + " duplicate key=" + key + " oldValue=" + new String(map.get(key))
                                    + " newValue=" + row.getCell(valueCol));
                            return false;
                    }
                }
                map.put(key, row.getCellToCharArray(valueCol));
                return true;
            }
        };

        forEachDataRow(csv, csvHeader, maxRequiredCol(keyCol, valueCol), rep, handler);
    }
    
    /**
     * Load a 2-column CSV resource into a {@link CharArrayMap} from a classpath
     * resource.
     * <p>
     * Column 0 = key, column 1 = value.
     * </p>
     *
     * @param map          target map (key -&gt; char[] value)
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @param replace      if {@code true}, overwrite existing keys; otherwise keep existing entries
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if {@code map}, {@code anchor}, or {@code resourcePath} is null
     */
    public static void loadMap(
        final CharsMap map,
        final Class<?> anchor,
        final String resourcePath,
        final OnDuplicate policy)
        throws UncheckedIOException
    {
        loadMap(map, anchor, resourcePath, policy, CsvHeader.SKIP, 0, 1, null);
    }
    
    public static void loadMap(
        final CharsMap map,
        final Class<?> anchor,
        final String resourcePath,
        final OnDuplicate policy,
        final CsvHeader csvHeader,
        final int keyCol,
        final int valueCol,
        final Report report)
        throws UncheckedIOException
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (CSVReader csv = new CSVReader(anchor, resourcePath)) {
            csv.cellMax(maxRequiredCol(keyCol, valueCol));
            loadMap(map, csv, policy, csvHeader, keyCol, valueCol, report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * Load a 2-column CSV file into a {@link CharArrayMap}.
     * <p>
     * Column 0 = key, column 1 = value.
     * </p>
     *
     * @param map     target map (key -&gt; char[] value)
     * @param file    CSV file path
     * @param replace if {@code true}, overwrite existing keys; otherwise keep
     *                existing entries
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if {@code map} or {@code file} is null
     */
    public static void loadMap(
        final CharsMap map,
        final Path file,
        final OnDuplicate policy) throws UncheckedIOException
    {
        loadMap(map, file, policy, CsvHeader.SKIP, 0, 1, null);
    }
    
    public static void loadMap(
        final CharsMap map,
        final Path file,
        final OnDuplicate policy,
        final CsvHeader csvHeader,
        final int keyCol,
        final int valueCol,
        Report report)
        throws UncheckedIOException
    {
        Objects.requireNonNull(file, "file");
        try (CSVReader csv = new CSVReader(file)) {
            csv.cellMax(maxRequiredCol(keyCol, valueCol));
            loadMap(map, csv, policy, csvHeader, keyCol, valueCol, report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * Load a 2-column CSV reader into a {@link CharArrayMap}.
     * <p>
     * Column 0 = key, column 1 = value.
     * </p>
     *
     * @param map        target map (key -&gt; char[] value)
     * @param csv        CSV reader
     * @param replace    if {@code true}, overwrite existing keys; otherwise
     *                   keep existing entries
     * @param skipHeader if {@code true}, the first row is skipped
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
        csv.report(rep);

        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws UncheckedIOException
            {
                final StringBuilder key = row.getCell(keyCol);
                final int keyOrd = map.keyOrd(key);
                if (keyOrd >= 0) {
                    switch (policy) {
                        case IGNORE:
                            return false;
                        case REPLACE:
                            map.put(keyOrd, row.getCell(valueCol));
                            return true;
                        case ERROR:
                            throw new RuntimeException("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                    + " duplicate key=" + key);
                        case REPORT:
                            rep.warn("LexiconHelper.loadMap " + row.getSpec() + ":" + row.getLineNo()
                                    + " duplicate key=" + key + " oldValue=" + new String(map.get(key))
                                    + " newValue=" + row.getCell(valueCol));
                            return false;
                    }
                }
                map.put(key, row.getCell(valueCol));
                return true;
            }
        };

        forEachDataRow(csv, csvHeader, maxRequiredCol(keyCol, valueCol), rep, handler);
    }
    
    /**
     * Load a CSV resource into a {@link CharArraySet} from a classpath
     * resource.
     *
     * @param set          target set
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @param col          column index containing the form to add
     * @param rtrimChars   characters to trim on the right of the selected cell
     *                     before insertion (may be {@code null} for no
     *                     trimming)
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if {@code set}, {@code anchor}, or
     *                                  {@code resourcePath} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(
        final CharArraySet set,
        final Class<?> anchor,
        final String resourcePath)
    {
        loadSet(set, anchor, resourcePath, 0, CsvHeader.SKIP, null);
    }
    
    /**
     * Load a CSV resource into a {@link CharArraySet} from a classpath
     * resource.
     *
     * @param set          target set
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @param col          column index containing the form to add
     * @param rtrimChars   characters to trim on the right of the selected cell
     *                     before insertion (may be {@code null} for no
     *                     trimming)
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if {@code set}, {@code anchor}, or
     *                                  {@code resourcePath} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(
        final CharArraySet set,
        final Class<?> anchor,
        final String resourcePath,
        final int col,
        final CsvHeader csvHeader,
        final String rtrimChars)
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (CSVReader csv = new CSVReader(anchor, resourcePath)) {
            csv.cellMax(col + 1);
            loadSet(set, csv, col, csvHeader, rtrimChars);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * Load a CSV file into a {@link CharArraySet}.
     *
     * @param set        target set
     * @param file       CSV file path
     * @param col        column index containing the form to add
     * @param rtrimChars characters to trim on the right of the selected cell
     *                   before insertion (may be {@code null} for no trimming)
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if {@code set} or {@code file} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(final CharArraySet set, final Path file)
        throws UncheckedIOException
    {
        loadSet(set, file, 0, CsvHeader.SKIP, null);
    }
    
    /**
     * Load a CSV file into a {@link CharArraySet}.
     *
     * @param set        target set
     * @param file       CSV file path
     * @param col        column index containing the form to add
     * @param rtrimChars characters to trim on the right of the selected cell
     *                   before insertion (may be {@code null} for no trimming)
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if {@code set} or {@code file} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(
        final CharArraySet set,
        final Path file,
        final int col,
        final CsvHeader header,
        final String rtrimChars)
        throws UncheckedIOException
    {
        Objects.requireNonNull(file, "file");
        try (CSVReader csv = new CSVReader(file)) {
            csv.cellMax(col + 1);
            loadSet(set, csv, col, header, rtrimChars);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * Load a CSV reader into a {@link CharArraySet}.
     *
     * @param set        target set
     * @param csv        CSV reader
     * @param col        column index containing the form to add
     * @param rtrimChars characters to trim on the right of the selected cell
     *                   before insertion (may be {@code null} for no trimming)
     * @param skipHeader if {@code true}, the first row is skipped
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if {@code set} or {@code csv} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(
        final CharArraySet set,
        final CSVReader csv,
        final int col,
        final CsvHeader csvHeader,
        final String rtrimChars)
        throws UncheckedIOException
    {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(csv, "csv");
        checkColumnIndex(col, "col");
        
        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws UncheckedIOException
            {
                final StringBuilder form = row.getCell(col);
                Char.rtrim(form, rtrimChars);
                if (form.length() == 0)
                    return false; // rtrim may empty the cell
                final boolean added = set.add(form);
                /* TODO report
                if (!added) {
                    System.out.printf(
                        "added=%-5s size=%d length=%d value=[%s]%n",
                        added,
                        set.size(),
                        form.length(),
                        form
                    );
                }
                */
                return true;
            }
        };
        
        forEachDataRow(csv, csvHeader, col + 1, Report.ReportNull.INSTANCE, handler);
    }
    
    /**
     * Load a lemma dictionary from a classpath resource, using default POS
     * normalization and unknown-POS handling (warn once per unknown tag, then
     * skip row).
     * <p>
     * This overload preserves a simple call site while enabling safer behavior
     * for unknown POS tags than silently inserting {@code -1}.
     * </p>
     *
     * @param lex          lemma lexicon to populate
     * @param policy       duplicate handling policy for {@link LemmaLexicon#putEntry}
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @param sep          CSV separator
     * @param formCol      column of inflected/surface form
     * @param posCol       column of POS name
     * @param lemmaCol     column of lemma form
     * 
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if any required argument is null
     * @throws IllegalArgumentException if any column index is negative
     */
    public static void loadLemma(
        final LemmaLexicon lex,
        final Class<?> anchor,
        final String resourcePath,
        final char sep,
        final CsvHeader csvHeader,
        final int formCol,
        final int posCol,
        final int lemmaCol,
        PosResolver posResolver)
        throws UncheckedIOException
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");

        final int maxCol = maxRequiredCol(formCol, posCol, lemmaCol);
        try (CSVReader csv = new CSVReader(anchor, resourcePath)) {
            csv.separator(sep).cellMax(maxCol);
            loadLemma(lex, csv, csvHeader, formCol, posCol, lemmaCol, posResolver);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * Load a lemma dictionary from a CSV reader with configurable POS
     * normalization and handling.
     * <p>
     * Processing order for the POS column:
     * </p>
     * <ol>
     * <li>Read raw POS name from {@code posCol}</li>
     * <li>Apply {@code posNormalizer} if non-null (e.g. trim, uppercase, strip
     * prefix)</li>
     * <li>Apply alias map ({@code posAliases}) if non-null and a match
     * exists</li>
     * <li>Resolve via {@link Upos#code(String)}</li>
     * </ol>
     * <p>
     * Unknown POS behavior:
     * </p>
     * <ul>
     * <li>If POS cannot be resolved, {@code unknownPosConsumer} is called (if
     * non-null)</li>
     * <li>If {@code unknownPosFallback >= 0}, that fallback is used</li>
     * <li>Otherwise (fallback &lt; 0), the row is skipped</li>
     * </ul>
     *
     * @param lex           lemma lexicon to populate
     * @param policy        duplicate handling policy for {@link LemmaLexicon#putEntry}
     * @param csv           CSV reader
     * @param skipHeader    if {@code true}, skip the first row
     * @param formCol       column of inflected/surface form
     * @param posCol        column of POS name
     * @param lemmaCol      column of lemma form
     * @param posNormalizer optional POS normalization function applied before aliasing and Upos
     *                      lookup
     *
     * @throws UncheckedIOException     on read error
     * @throws NullPointerException     if {@code lex}, {@code policy}, or {@code csv} is null
     * @throws IllegalArgumentException if a column index is negative
     */
    public static void loadLemma(
        final LemmaLexicon lex,
        final CSVReader csv,
        final CsvHeader csvHeader,
        final int formCol,
        final int posCol,
        final int lemmaCol,
        final PosResolver posResolver)
        throws UncheckedIOException
    {
        Objects.requireNonNull(lex, "lex");
        Objects.requireNonNull(csv, "csv");
        
        checkColumnIndex(formCol, "formCol");
        checkColumnIndex(posCol, "posCol");
        checkColumnIndex(lemmaCol, "lemmaCol");
        // put the default posResolver
        
        final PosResolver pr;
        if (posResolver == null)
            pr = DEFAULT_POS_RESOLVER;
        else
            pr = posResolver;
        pr.reset();
        
        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws UncheckedIOException
            {
                final String rawPosName = row.getCellAsString(posCol);
                int posId = pr.posInt(rawPosName);
                // normalize some chars before input entries
                StringBuilder form = row.getCell(formCol);
                Char.translate(form, "’", "'");
                StringBuilder lemma = row.getCell(lemmaCol);
                Char.translate(lemma, "’", "'");
                if (form.isEmpty() || lemma.isEmpty())
                    return false;
                lex.put(form, lemma);
                if (posId >= 0) {
                    lex.put(form, posId, lemma);
                }
                return true;
            }
        };
        
        forEachDataRow(csv, csvHeader, maxRequiredCol(formCol, posCol, lemmaCol), Report.ReportNull.INSTANCE, handler);
        pr.endFile(null);
    }
    
    public static POSModel loadPosModel(final Class<?> anchor, String path)
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
     * remaining row is passed to {@code handler}.
     *
     * @param csv       CSV reader
     * @param csvHeader whether to consume the first row as a header
     * @param minCols   minimum number of cells a row must have to reach the handler
     * @param report    non-null sink for short-row warnings
     * @param handler   row consumer for accepted rows
     * @throws UncheckedIOException on read error
     * @throws NullPointerException if any argument is null
     */
    private static void forEachDataRow(
        final CSVReader csv,
        final CsvHeader csvHeader,
        final int minCols,
        final Report report,
        final CsvRowHandler handler)
    {
        Objects.requireNonNull(csv, "csv");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(handler, "handler");
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
                handler.read++;
                if (count < minCols) {
                    report.warn(csv.getSpec() + ":" + csv.getLineNo()
                            + " expected at least " + minCols + " columns, got " + count);
                    handler.skipped++;
                    continue;
                }
                if (handler.accept(csv)) {
                    handler.accepted++;
                } else {
                    handler.skipped++;
                }
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
     * Resolves part-of-speech labels (read from a lexical CSV resource) into
     * integer POS ids.
     * <p>
     * This class is designed as a small template-method framework for lexicon
     * loading:
     * </p>
     * <ul>
     * <li>{@link #posInt(String)} is the final entry point used by loaders</li>
     * <li>subclasses customize behavior through hooks such as
     * {@link #posRewrite(String)}</li>
     * <li>unknown POS labels are counted by default and can be reported at end
     * of file</li>
     * </ul>
     *
     * <h2>Resolution contract</h2>
     * <ul>
     * <li>Return value {@code >= 0}: valid POS id, row can be loaded</li>
     * <li>Return value {@code < 0}: unknown/unsupported POS, row should be
     * skipped (unless the caller chooses another interpretation)</li>
     * </ul>
     *
     * <h2>Lifecycle</h2>
     * <p>
     * A resolver instance may be reused across files. Call {@link #reset()}
     * before a new parsing session if you want to clear the accumulated
     * unknown-tag counters.
     * </p>
     *
     * <h2>Thread safety</h2>
     * <p>
     * This class is <strong>not thread-safe</strong>. It maintains mutable
     * counters intended for single-threaded resource loading.
     * </p>
     */
    public static abstract class PosResolver
    {
        /**
         * Unknown tag frequencies accumulated during the current parsing
         * session. Keys are normalized display labels produced by
         * {@link #reportUnknown(String)}.
         */
        private final Map<String, Integer> unknownCounts = new HashMap<>();
        
        /**
         * Reset internal counters so this resolver can be reused for another
         * file/resource.
         * <p>
         * This does not change any subclass configuration; it only clears
         * accumulated unknown-tag counts.
         * </p>
         */
        public final void reset()
        {
            unknownCounts.clear();
        }
        
        /**
         * Resolve a raw POS label (as read from the CSV file) into a POS id.
         * <p>
         * This method is the stable contract used by lexicon loaders. It
         * performs the standard steps:
         * </p>
         * <ol>
         * <li>reject {@code null} or blank raw values</li>
         * <li>trim and rewrite via {@link #posRewrite(String)}</li>
         * <li>reject {@code null} or blank rewritten values</li>
         * <li>lookup via {@link #posLookup(String)}</li>
         * <li>on failure, report via {@link #reportUnknown(String)} and return
         * {@link #fallbackPosId()}</li>
         * </ol>
         *
         * @param rawPosName raw POS label from the CSV cell (may be
         *                   {@code null})
         * @return a POS id ({@code >= 0}) if resolved, or a negative value to
         *         signal "skip row"
         */
        public final int posInt(final String rawPosName)
        {
            if (rawPosName == null || rawPosName.isBlank()) {
                reportUnknown(rawPosName);
                return fallbackPosId();
            }
            
            final String posName = posRewrite(rawPosName.trim());
            if (posName == null || posName.isBlank()) {
                // Report the raw source value; this is usually what users need to diagnose the dictionary.
                reportUnknown(rawPosName);
                return fallbackPosId();
            }
            
            final int posId = posLookup(posName);
            if (posId >= 0) {
                return posId;
            }
            
            reportUnknown(rawPosName);
            return fallbackPosId();
        }
        
        /**
         * Rewrite / normalize a POS label before lookup.
         * <p>
         * Input is already trimmed and non-blank.
         * </p>
         * <p>
         * Typical uses:
         * </p>
         * <ul>
         * <li>alias mapping (e.g. {@code "Nc" -> "NOUN"})</li>
         * <li>case normalization</li>
         * <li>regex cleanup</li>
         * <li>prefix/suffix stripping</li>
         * </ul>
         *
         * @param posName trimmed non-blank POS label
         * @return rewritten POS label; may return {@code null} or blank to
         *         force unknown/fallback handling
         */
        protected String posRewrite(final String posName)
        {
            return posName;
        }
        
        /**
         * Map a rewritten POS label to an integer POS id.
         * <p>
         * Default implementation delegates to {@link Upos#code(String)}.
         * </p>
         *
         * @param posName rewritten POS label (typically non-blank)
         * @return POS id ({@code >= 0}) if known; negative otherwise
         */
        protected int posLookup(final String posName)
        {
            return Upos.code(posName);
        }
        
        /**
         * Fallback POS id used when the POS label is unknown or rejected.
         * <p>
         * Default is {@code -1}, which signals "skip row" to the loader.
         * Override to return a non-negative default POS id if your lexicon
         * policy prefers a fallback.
         * </p>
         *
         * @return fallback POS id (negative by default)
         */
        protected int fallbackPosId()
        {
            return -1;
        }
        
        /**
         * Report an unknown POS label.
         * <p>
         * Default behavior: normalize the label to a display key
         * ({@code "<null>"} / {@code "<blank>"} for missing values) and
         * increment a frequency counter.
         * </p>
         * <p>
         * Subclasses may override to suppress reporting, emit logs, or route
         * data elsewhere. If you still want the default counting behavior, call
         * {@code super.reportUnknown(rawPosName)}.
         * </p>
         *
         * @param rawPosName raw source POS label; may be {@code null} or blank
         */
        protected void reportUnknown(String rawPosName)
        {
            if (rawPosName == null) {
                rawPosName = "<null>";
            } else if (rawPosName.isBlank()) {
                rawPosName = "<blank>";
            }
            unknownCounts.merge(rawPosName, 1, Integer::sum);
        }
        
        /**
         * End-of-file hook called by the loader once after parsing a
         * file/resource.
         * <p>
         * The default implementation prints a concise report to
         * {@link System#err} when at least one unknown POS label was observed.
         * </p>
         * <p>
         * Loaders should preferably call this method in a {@code finally} block
         * so it also runs on partial parses (with partial stats).
         * </p>
         *
         * @param stats load statistics for the parsed resource (must not be
         *              {@code null})
         */
        public void endFile(final LoadStats stats)
        {
            
            final Map<String, Integer> unknowns = unknownCounts();
            if (unknowns.isEmpty()) {
                return;
            }
            
            if (stats != null)
                System.out.println(
                        (stats.path() == null ? "<unknown source>" : stats.path())
                                + " rows read=" + stats.rowsRead()
                                + ", loaded=" + stats.rowsLoaded()
                                + ", skipped=" + stats.rowsSkipped());
            
            final int totalUnknownOccurrences = unknowns.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
            
            System.err.println(
                    "Unknown POS tags: distinct=" + unknowns.size()
                            + ", occurrences=" + totalUnknownOccurrences);
            
            unknowns.entrySet().stream()
                    .sorted((a, b) -> {
                        final int byFreq = Integer.compare(b.getValue(), a.getValue()); // descending frequency
                        return (byFreq != 0) ? byFreq : a.getKey().compareTo(b.getKey()); // ascending label
                    })
                    .forEach(e -> System.err.println(e.getKey() + ": " + e.getValue()));
        }
        
        /**
         * Return an immutable snapshot of unknown POS frequencies.
         * <p>
         * The returned map is a defensive copy: subsequent resolver updates do
         * not affect the snapshot.
         * </p>
         *
         * @return immutable snapshot of unknown POS frequencies
         */
        public final Map<String, Integer> unknownCounts()
        {
            return Collections.unmodifiableMap(new HashMap<>(unknownCounts));
        }
        
        /**
         * Number of distinct unknown POS labels seen in the current session.
         *
         * @return distinct unknown label count
         */
        public final int unknownDistinctCount()
        {
            return unknownCounts.size();
        }
        
        /**
         * Total number of unknown POS occurrences seen in the current session.
         * <p>
         * This is the sum of all values in {@link #unknownCounts()}.
         * </p>
         *
         * @return total unknown occurrence count
         */
        public final int unknownTotalCount()
        {
            int total = 0;
            for (int count : unknownCounts.values()) {
                total += count;
            }
            return total;
        }
    }
    
    public static final PosResolver DEFAULT_POS_RESOLVER = new PosResolver()
    {
    };
    
    /**
     * Immutable statistics collected while loading a lexical resource.
     * <p>
     * Instances are typically created once at end of parsing and passed to
     * {@link PosResolver#endFile(LoadStats)}.
     * </p>
     *
     * @param path        source identifier (file path, resource name, etc.);
     *                    may be {@code null} if unknown
     * @param rowsRead    total number of CSV rows read/considered after header
     *                    handling
     * @param rowsLoaded  number of rows successfully inserted into the target
     *                    lexicon
     * @param rowsSkipped number of rows skipped for any reason
     */
    public record LoadStats(
            String path,
            int rowsRead,
            int rowsLoaded,
            int rowsSkipped)
    {
        /**
         * Validate non-negative counters.
         *
         * @throws IllegalArgumentException if any counter is negative
         */
        public LoadStats {
            if (rowsRead < 0)
                throw new IllegalArgumentException("rowsRead < 0: " + rowsRead);
            if (rowsLoaded < 0)
                throw new IllegalArgumentException("rowsLoaded < 0: " + rowsLoaded);
            if (rowsSkipped < 0)
                throw new IllegalArgumentException("rowsSkipped < 0: " + rowsSkipped);
        }
    }
    
}