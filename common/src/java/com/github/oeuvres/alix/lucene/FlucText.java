package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;

import com.github.oeuvres.alix.lucene.terms.FieldStats;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.ThemeTerms;

/**
 * A tokenized field with positions: the primary Alix field type.
 *
 * <p>
 * Lazily loads per-field analysis resources from sidecar files stored
 * alongside the Lucene index. If a sidecar file does not exist, it is
 * built from the frozen reader on first access.
 * </p>
 * <ul>
 *   <li>{@link FieldStats} — immutable reference statistics
 *       ({@code <field>.stats})</li>
 *   <li>{@link TermLexicon} — FST-based dense term-to-id mapping
 *       ({@code <field>.fst})</li>
 *   <li>{@link TermRail} — memory-mapped forward positional index
 *       ({@code <field>.rail.dat}, {@code <field>.rail.off})</li>
 *   <li>{@link ThemeTerms} — corpus-level keyword scorer
 *       (constructed from reader + lexicon + fieldStats)</li>
 * </ul>
 *
 * <p>
 * Each resource is loaded at most once. Build order respects dependencies:
 * TermRail requires TermLexicon; ThemeTerms requires both FieldStats
 * and TermLexicon.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * Lazy initialization is synchronized on this instance. Once loaded,
 * all resources are immutable or read-only and safe for concurrent access.
 * </p>
 *
 * @see Fluc#inferFields
 */
public final class FlucText extends Fluc
{
    /** Frozen reader, held for sidecar building and ThemeTerms construction. */
    private final IndexReader reader;

    // ---- lazy resources ----

    private FieldStats fieldStats;
    private boolean fieldStatsResolved;

    private TermLexicon lexicon;
    private boolean lexiconResolved;

    private TermRail rail;
    private boolean railResolved;

    private ThemeTerms themeTerms;
    private boolean themeTermsResolved;

    // ================================================================
    // Constructor
    // ================================================================

    /**
     * Creates a text-field handle.
     *
     * <p>
     * Called by {@link Fluc#inferFields} after stored-field probing.
     * No I/O is performed here; all resources are loaded lazily on
     * first access.
     * </p>
     *
     * @param fi       segment-level field metadata
     * @param stored   whether the field has stored values
     * @param docs     number of documents with at least one indexed term
     * @param indexDir Lucene index directory
     * @param reader   frozen index reader
     */
    public FlucText(
        final FieldInfo fi,
        final boolean stored,
        final int docs,
        final Path indexDir,
        final IndexReader reader
    ) {
        super(fi, stored, docs, indexDir);
        this.reader = reader;
    }

    // ================================================================
    // Resource accessors
    // ================================================================

    /**
     * Immutable reference statistics for this field.
     * Built from postings if the sidecar file does not exist.
     *
     * @return field statistics, never {@code null}
     * @throws UncheckedIOException if building or reading fails
     */
    public synchronized FieldStats fieldStats()
    {
        if (!fieldStatsResolved) {
            fieldStatsResolved = true;
            fieldStats = ensureOpen(
                FieldStats.exists(indexDir, name()),
                () -> FieldStats.write(indexDir, reader, name()),
                () -> FieldStats.open(indexDir, name())
            );
        }
        return fieldStats;
    }

    /**
     * Dense term lexicon (FST + memory-mapped term data).
     * Built from postings if the sidecar files do not exist.
     *
     * @return lexicon, never {@code null}
     * @throws UncheckedIOException if building or reading fails
     */
    public synchronized TermLexicon termLexicon()
    {
        if (!lexiconResolved) {
            lexiconResolved = true;
            lexicon = ensureOpen(
                TermLexicon.exists(indexDir, name()),
                () -> TermLexicon.write(indexDir, reader, name()),
                () -> TermLexicon.open(indexDir, name())
            );
        }
        return lexicon;
    }

    /**
     * Forward positional rail (memory-mapped).
     * Built from postings if the sidecar files do not exist.
     * Ensures the {@link #termLexicon()} is available first.
     *
     * @return rail, never {@code null}
     * @throws UncheckedIOException if building or reading fails
     */
    public synchronized TermRail termRail()
    {
        if (!railResolved) {
            railResolved = true;
            final TermLexicon lex = termLexicon();
            rail = ensureOpen(
                TermRail.exists(indexDir, name()),
                () -> TermRail.write(indexDir, reader, name(), lex),
                () -> TermRail.open(indexDir, name())
            );
        }
        return rail;
    }

    /**
     * Corpus-level keyword scorer.
     * Ensures both {@link #fieldStats()} and {@link #termLexicon()}
     * are available first.
     *
     * @return theme terms scorer, never {@code null}
     * @throws UncheckedIOException if underlying resources cannot be loaded
     */
    public synchronized ThemeTerms themeTerms()
    {
        if (!themeTermsResolved) {
            themeTermsResolved = true;
            themeTerms = new ThemeTerms(reader, termLexicon(), fieldStats());
        }
        return themeTerms;
    }

    // ================================================================
    // Closeable
    // ================================================================

    /**
     * Releases memory-mapped resources (lexicon and rail).
     *
     * <p>
     * After close, all accessors on this instance are invalid.
     * {@link ThemeTerms} and {@link FieldStats} hold no native
     * resources and do not need explicit cleanup.
     * </p>
     */
    @Override
    public synchronized void close() throws IOException
    {
        if (rail != null) {
            rail.close();
            rail = null;
        }
        railResolved = false;

        if (lexicon != null) {
            lexicon.close();
            lexicon = null;
        }
        lexiconResolved = false;

        themeTerms = null;
        themeTermsResolved = false;

        fieldStats = null;
        fieldStatsResolved = false;
    }

    // ================================================================
    // Internal helper
    // ================================================================

    @FunctionalInterface
    private interface IORunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface IOSupplier<T> {
        T get() throws IOException;
    }

    /**
     * If the resource does not exist, build it, then open it.
     */
    private static <T> T ensureOpen(
        final boolean exists,
        final IORunnable writer,
        final IOSupplier<T> opener
    ) {
        try {
            if (!exists) {
                writer.run();
            }
            return opener.get();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
