package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.util.CSVReader;

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
         * @throws IOException if processing needs to propagate an I/O error
         */
        protected abstract boolean accept(CSVReader csv) throws IOException;
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
     * @throws IOException          on read error
     * @throws NullPointerException if {@code map}, {@code anchor}, or {@code resourcePath} is null
     */
    public static void loadMap(
        final CharArrayMap<char[]> map,
        final Class<?> anchor,
        final String resourcePath,
        final boolean replace)
        throws IOException
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (CSVReader csv = new CSVReader(anchor, resourcePath, ',', 2)) {
            loadMap(map, csv, replace);
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
     * @throws IOException          on read error
     * @throws NullPointerException if {@code map} or {@code file} is null
     */
    public static void loadMap(final CharArrayMap<char[]> map, final Path file, final boolean replace)
        throws IOException
    {
        Objects.requireNonNull(file, "file");
        try (CSVReader csv = new CSVReader(file, ',', 2)) {
            loadMap(map, csv, replace);
        }
    }
    
    /**
     * Load a 2-column CSV reader into a {@link CharArrayMap}, skipping the
     * first row (header).
     * <p>
     * Column 0 = key, column 1 = value.
     * </p>
     *
     * @param map     target map (key -&gt; char[] value)
     * @param csv     CSV reader
     * @param replace if {@code true}, overwrite existing keys; otherwise keep
     *                existing entries
     * @throws IOException          on read error
     * @throws NullPointerException if {@code map} or {@code csv} is null
     */
    public static void loadMap(final CharArrayMap<char[]> map, final CSVReader csv, final boolean replace)
        throws IOException
    {
        loadMap(map, csv, replace, true);
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
     * @throws IOException          on read error
     * @throws NullPointerException if {@code map} or {@code csv} is null
     */
    public static void loadMap(
        final CharArrayMap<char[]> map,
        final CSVReader csv,
        final boolean replace,
        final boolean skipHeader)
        throws IOException
    {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(csv, "csv");
        
        final int keyCol = 0;
        final int valueCol = 1;
        final int minCols = 2;
        
        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws IOException
            {
                final StringBuilder form = row.getCell(keyCol);
                if (!replace && map.containsKey(form))
                    return false;
                map.put(form, row.getCellToCharArray(valueCol));
                return true;
            }
        };
        
        forEachDataRow(csv, minCols, keyCol, skipHeader, handler);
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
     * @throws IOException              on read error
     * @throws NullPointerException     if {@code set}, {@code anchor}, or
     *                                  {@code resourcePath} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(
        final CharArraySet set,
        final Class<?> anchor,
        final String resourcePath,
        final int col,
        final String rtrimChars)
        throws IOException
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (CSVReader csv = new CSVReader(anchor, resourcePath, ',', Math.max(2, col + 1))) {
            loadSet(set, csv, col, rtrimChars);
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
     * @throws IOException              on read error
     * @throws NullPointerException     if {@code set} or {@code file} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(final CharArraySet set, final Path file, final int col, final String rtrimChars)
        throws IOException
    {
        Objects.requireNonNull(file, "file");
        try (CSVReader csv = new CSVReader(file, ',', Math.max(2, col + 1))) {
            loadSet(set, csv, col, rtrimChars);
        }
    }
    
    /**
     * Load a CSV reader into a {@link CharArraySet}, skipping the first row
     * (header).
     *
     * @param set        target set
     * @param csv        CSV reader
     * @param col        column index containing the form to add
     * @param rtrimChars characters to trim on the right of the selected cell
     *                   before insertion (may be {@code null} for no trimming)
     * @throws IOException              on read error
     * @throws NullPointerException     if {@code set} or {@code csv} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(final CharArraySet set, final CSVReader csv, final int col, final String rtrimChars)
        throws IOException
    {
        loadSet(set, csv, col, rtrimChars, true);
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
     * @throws IOException              on read error
     * @throws NullPointerException     if {@code set} or {@code csv} is null
     * @throws IllegalArgumentException if {@code col < 0}
     */
    public static void loadSet(
        final CharArraySet set,
        final CSVReader csv,
        final int col,
        final String rtrimChars,
        final boolean skipHeader)
        throws IOException
    {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(csv, "csv");
        checkColumnIndex(col, "col");
        
        final int minCols = col + 1;
        final int keyCol = col; // blank/comment checks are applied to the selected column
        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws IOException
            {
                final StringBuilder form = row.getCell(col);
                rtrim(form, rtrimChars);
                if (form.length() == 0)
                    return false; // rtrim may empty the cell
                set.add(form);
                return true;
            }
        };
        
        forEachDataRow(csv, minCols, keyCol, skipHeader, handler);
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
     * @throws IOException              on read error
     * @throws NullPointerException     if any required argument is null
     * @throws IllegalArgumentException if any column index is negative
     */
    public static void loadLemma(
        final LemmaLexicon lex,
        final LemmaLexicon.OnDuplicate policy,
        final Class<?> anchor,
        final String resourcePath,
        final char sep,
        final boolean skipHeader,
        final int formCol,
        final int posCol,
        final int lemmaCol,
        PosResolver posResolver)
        throws IOException
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        
        final int maxCol = maxRequiredCol(formCol, posCol, lemmaCol);
        try (CSVReader csv = new CSVReader(anchor, resourcePath, sep, maxCol)) {
            loadLemma(lex, policy, csv, skipHeader, formCol, posCol, lemmaCol, posResolver);
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
     * @param posNormalizer optional POS normalization function applied before aliasing and Upos lookup
     *
     * @throws IOException              on read error
     * @throws NullPointerException     if {@code lex}, {@code policy}, or {@code csv} is null
     * @throws IllegalArgumentException if a column index is negative
     */
    public static void loadLemma(
        final LemmaLexicon lex,
        final LemmaLexicon.OnDuplicate policy,
        final CSVReader csv,
        final boolean skipHeader,
        final int formCol,
        final int posCol,
        final int lemmaCol,
        final PosResolver posResolver)
        throws IOException
    {
        Objects.requireNonNull(lex, "lex");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(csv, "csv");
        
        checkColumnIndex(formCol, "formCol");
        checkColumnIndex(posCol, "posCol");
        checkColumnIndex(lemmaCol, "lemmaCol");
        // put the default posResolver
        
        final PosResolver pr;
        if (posResolver == null) pr = DEFAULT_POS_RESOLVER;
        else pr = posResolver;
        pr.reset();
        
        final int minCols = maxRequiredCol(formCol, posCol, lemmaCol);
        final int keyCol = formCol; // ignore blank/comment rows based on the form column
        
        final CsvRowHandler handler = new CsvRowHandler()
        {
            @Override
            protected boolean accept(final CSVReader row) throws IOException
            {
                final String rawPosName = row.getCellAsString(posCol);
                int posId = pr.posInt(rawPosName);
                CharSequence form = row.getCell(formCol);
                CharSequence lemma = row.getCell(lemmaCol);
                if (form.isEmpty() || lemma.isEmpty()) return false;
                lex.putEntry(form, lemma, policy);
                if (posId >= 0) {
                    lex.putEntry(form, posId, lemma, policy);
                }
                return true;
            }
        };
        
        forEachDataRow(csv, minCols, keyCol, skipHeader, handler);
        pr.endFile(null);
    }
    
    /**
     * Trim characters from the right of a mutable string.
     *
     * @param sb         mutable text to trim (modified in place)
     * @param stripChars characters to remove from the right edge; if null or
     *                   empty, nothing is done
     * @throws NullPointerException if {@code sb} is null
     */
    public static void rtrim(final StringBuilder sb, final String stripChars)
    {
        Objects.requireNonNull(sb, "sb");
        if (stripChars == null || stripChars.isEmpty())
            return;
        
        int len = sb.length();
        while (len > 0) {
            final char c = sb.charAt(len - 1);
            if (stripChars.indexOf(c) < 0)
                break;
            len--;
        }
        sb.setLength(len);
    }
    
    /**
     * Iterate over CSV rows with shared boilerplate checks.
     * <p>
     * The method optionally skips the first row (header), then for each
     * subsequent row:
     * </p>
     * <ul>
     * <li>requires at least {@code minCols} cells</li>
     * <li>applies blank/comment filtering on {@code keyCol}</li>
     * <li>delegates to {@code handler}</li>
     * </ul>
     *
     * @param csv        CSV reader
     * @param minCols    minimum required cell count
     * @param keyCol     column used to detect blank/comment rows
     * @param skipHeader if {@code true}, skip the first row
     * @param handler    row consumer called for accepted rows
     * @throws IOException              on read error
     * @throws NullPointerException     if {@code csv} or {@code handler} is null
     * @throws IllegalArgumentException if {@code minCols < 1} or {@code keyCol < 0}
     */
    private static void forEachDataRow(
        final CSVReader csv,
        final int minCols,
        final int keyCol,
        final boolean skipHeader,
        final CsvRowHandler handler)
        throws IOException
    {
        Objects.requireNonNull(csv, "csv");
        Objects.requireNonNull(handler, "handler");
        if (minCols < 1)
            throw new IllegalArgumentException("minCols must be >= 1: " + minCols);
        checkColumnIndex(keyCol, "keyCol");
        
        if (skipHeader && !csv.readRow()) {
            return; // empty file
        }
        
        while (csv.readRow()) {
            handler.read++;
            if (csv.getCellCount() < minCols) {
                
                continue;
            }
            
            final StringBuilder key = csv.getCell(keyCol);
            if (isBlankOrComment(key))
                continue;
            
            handler.accept(csv);
        }
    }
    
    /**
     * Return whether a cell is blank or starts with {@code '#'} (comment
     * marker).
     *
     * @param cell CSV cell content
     * @return {@code true} if null, empty, or comment
     */
    private static boolean isBlankOrComment(final CharSequence cell)
    {
        return cell == null || cell.length() == 0 || cell.charAt(0) == '#';
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
            
            if (stats != null) System.out.println(
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
    public static final PosResolver DEFAULT_POS_RESOLVER = new PosResolver() {};
    
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
