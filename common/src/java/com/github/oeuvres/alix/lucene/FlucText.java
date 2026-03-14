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
 * Lazily loads and caches per-field analysis resources from sidecar
 * files stored alongside the Lucene index:
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
 * Each resource is loaded at most once. If the sidecar files do not
 * exist, the accessor returns {@code null}. A frozen index guarantees
 * that presence is stable across the lifetime of this object.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * Lazy initialization is synchronized. Once loaded, all resources
 * are immutable or read-only and safe for concurrent access.
 * </p>
 *
 * @see Fluc#inferFields
 */
public final class FlucText extends Fluc
{
    /** Frozen reader, held for ThemeTerms construction. */
    private final IndexReader reader;

    // ---- lazy resources (guarded by synchronized) ----

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
     * @param reader   frozen index reader (retained for ThemeTerms)
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
    // Resource accessors (lazy, synchronized)
    // ================================================================

    /**
     * Immutable reference statistics for this field.
     *
     * @return field statistics, or {@code null} if no
     *         {@code <field>.stats} sidecar file exists
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    public synchronized FieldStats fieldStats()
    {
        if (!fieldStatsResolved) {
            fieldStatsResolved = true;
            try {
                if (FieldStats.exists(indexDir, name())) {
                    fieldStats = FieldStats.open(indexDir, name());
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return fieldStats;
    }

    /**
     * Dense term lexicon (FST + memory-mapped term data).
     *
     * @return lexicon, or {@code null} if no lexicon sidecar files exist
     * @throws UncheckedIOException if the files exist but cannot be read
     */
    public synchronized TermLexicon termLexicon()
    {
        if (!lexiconResolved) {
            lexiconResolved = true;
            try {
                if (TermLexicon.exists(indexDir, name())) {
                    lexicon = TermLexicon.open(indexDir, name());
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return lexicon;
    }

    /**
     * Forward positional rail (memory-mapped).
     *
     * @return rail, or {@code null} if no rail sidecar files exist
     * @throws UncheckedIOException if the files exist but cannot be read
     */
    public synchronized TermRail termRail()
    {
        if (!railResolved) {
            railResolved = true;
            try {
                if (TermRail.exists(indexDir, name())) {
                    rail = TermRail.open(indexDir, name());
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return rail;
    }

    /**
     * Corpus-level keyword scorer.
     *
     * <p>
     * Requires both {@link #fieldStats()} and {@link #termLexicon()}
     * to be available. Returns {@code null} if either is absent.
     * </p>
     *
     * @return theme terms scorer, or {@code null} if prerequisites are absent
     * @throws UncheckedIOException if underlying resources cannot be loaded
     */
    public synchronized ThemeTerms themeTerms()
    {
        if (!themeTermsResolved) {
            themeTermsResolved = true;
            final FieldStats fs = fieldStats();
            final TermLexicon lex = termLexicon();
            if (fs != null && lex != null) {
                themeTerms = new ThemeTerms(reader, lex, fs);
            }
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
}
