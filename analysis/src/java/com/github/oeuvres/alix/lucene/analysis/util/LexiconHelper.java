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
        final List<String> forms = expressionForms(analyzer, expression);
        final String output = expressionOutput(expression, canonical);
        if (forms.size() < 2 || output.isEmpty()) return false;
        lexicon.addExpression(forms, output);
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
        final int columns = colCanonical < 0 ? maxRequiredCol(colExpression)
            : maxRequiredCol(colExpression, colCanonical);
        csv.cellMax(columns);
        forEachDataRow(csv, header, columns, Report.ReportNull.INSTANCE, row -> {
            final String form = row.getCellAsString(colExpression);
            final String output = colCanonical < 0 ? form : row.getCellAsString(colCanonical);
            try {
                addExpression(lexicon, analyzer, form, output);
            } catch (UncheckedIOException e) {
                throw new UncheckedIOException(new IOException(row.getSpec() + ":" + row.getLineNo()
                    + " cannot analyze MWE: " + form, e));
            }
        });
    }

    /**
     * Loads headered form, POS, canonical declarations using a caller-supplied compiler.
     * @param csv open CSV reader, owned by the caller
     * @param handler expression registration callback
     * @throws UncheckedIOException if reading or analysis fails
     */
    public static void loadExpressions(final CSVReader csv, final ExpressionHandler handler)
    {
        csv.cellMax(3);
        forEachDataRow(csv, CsvHeader.SKIP, 3, Report.ReportNull.INSTANCE, row -> {
            final String form = row.getCellAsString(0).strip();
            if (form.isEmpty()) return;
            final String pos = row.getCellAsString(1).strip();
            final String canonical = row.getCellAsString(2);
            try {
                handler.add(form, pos, canonical.isBlank() ? form : canonical);
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
         * @param pos declared POS, possibly empty
         * @param canonical canonical output
         */
        void add(String form, String pos, String canonical);
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

        final Consumer<CSVReader> handler = row -> {
            final StringBuilder key = row.getCell(keyCol);
            final int keyOrd = map.keyOrd(key);
            if (keyOrd >= 0) {
                switch (policy) {
                    case IGNORE:
                        return;
                    case REPLACE:
                        map.put(keyOrd, row.getCell(valueCol));
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
            map.put(key, row.getCell(valueCol));
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
     */
    public static void loadSet(
        final CharArraySet set,
        final Class<?> anchor,
        final String resourcePath)
    {
        loadSet(set, anchor, resourcePath, 0, CsvHeader.SKIP);
    }
    
    /**
     * Load a CSV resource into a {@link CharArraySet} from a classpath
     * resource.
     *
     * @param set          target set
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @param col          column index containing the form to add
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
        final CsvHeader csvHeader)
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (CSVReader csv = new CSVReader(anchor, resourcePath)) {
            csv.cellMax(col + 1);
            loadSet(set, csv, col, csvHeader);
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
     */
    public static void loadSet(final CharArraySet set, final Path file)
    {
        loadSet(set, file, 0, CsvHeader.SKIP);
    }
    
    /**
     * Load a CSV file into a {@link CharArraySet}.
     *
     * @param set        target set
     * @param file       CSV file path
     * @param col        column index containing the form to add
     */
    public static void loadSet(
        final CharArraySet set,
        final Path file,
        final int col,
        final CsvHeader header)
    {
        Objects.requireNonNull(file, "file");
        try (CSVReader csv = new CSVReader(file)) {
            csv.cellMax(col + 1);
            loadSet(set, csv, col, header);
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
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @param sep          CSV separator
     * @param formCol      column of inflected/surface form
     * @param posCol       column of POS name
     * @param lemmaCol     column of lemma form
     * @param csvHeader    header policy
     * @param posResolver  optional POS mapping; null uses Upos.get
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
        Function<String, Upos> posResolver)
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
        
        forEachDataRow(csv, csvHeader, maxRequiredCol(formCol, posCol, lemmaCol), Report.ReportNull.INSTANCE, handler);
        pr.report(csv.getSpec());
    }
    

    /**
     * Loads POS tags from a classpath CSV resource into a mutable OpenNLP tag
     * dictionary.
     *
     * @param tagDic       mutable tag dictionary to populate
     * @param anchor       class used to resolve the resource path
     * @param resourcePath classpath resource path
     * @param sep          CSV separator
     * @param csvHeader    header policy
     * @param formCol      column containing the surface form
     * @param tagCol       column containing the source POS tag
     * @param freqCol      frequency column; if negative, frequency is ignored
     * @param freqMin      minimum accepted frequency when {@code freqCol >= 0}
     * @param posResolver  POS resolver; {@code null} uses {@code Upos::get}
     * @throws UncheckedIOException on read error
     */
    public static void loadTags(
        final MutableTagDictionary tagDic,
        final Class<?> anchor,
        final String resourcePath,
        final char sep,
        final CsvHeader csvHeader,
        final int formCol,
        final int tagCol,
        final int freqCol,
        final double freqMin,
        final Function<String, Upos> posResolver)
        throws UncheckedIOException
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");

        final int maxCol = (freqCol < 0)
            ? maxRequiredCol(formCol, tagCol)
            : maxRequiredCol(formCol, tagCol, freqCol);

        try (CSVReader csv = new CSVReader(anchor, resourcePath)) {
            csv.separator(sep).cellMax(maxCol);
            loadTags(
                tagDic,
                csv,
                csvHeader,
                formCol,
                tagCol,
                freqCol,
                freqMin,
                posResolver,
                null
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        final Report rep = orSilent(report);
        csv.report(rep);
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
        forEachDataRow(csv, CsvHeader.KEEP, 1, rep, handler);
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
     * @param report       report sink; {@code null} is silent
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
        csv.report(rep);

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

        forEachDataRow(csv, csvHeader, minCols, rep, handler);
        pr.report(csv.getSpec());
    }

    /**
     * Convenience overload using {@code Upos::get}.
     */
    public static void loadTags(
        final MutableTagDictionary tagDic,
        final CSVReader csv,
        final CsvHeader csvHeader,
        final int formCol,
        final int tagCol,
        final int freqCol,
        final double freqMin,
        final Report report)
        throws UncheckedIOException
    {
        loadTags(
            tagDic,
            csv,
            csvHeader,
            formCol,
            tagCol,
            freqCol,
            freqMin,
            null,
            report
        );
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
        final Consumer<CSVReader> handler)
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
                if (count < minCols) {
                    report.warn(csv.getSpec() + ":" + csv.getLineNo()
                            + " expected at least " + minCols + " columns, got " + count);
                    continue;
                }
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
         */
        private void report(final String source)
        {
            if (unknown.isEmpty()) return;
            System.err.println(source + " unknown POS tags: " + unknown.size());
            unknown.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.err.println(e.getKey() + ": " + e.getValue()));
        }
    }
}
