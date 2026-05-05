package com.github.oeuvres.alix.lucene.fluc;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;

import com.github.oeuvres.alix.lucene.TopTerms;
import com.github.oeuvres.alix.lucene.terms.FieldStats;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.util.Report;

/**
 * A tokenized field with positions: the primary Alix field type.
 *
 * <p>
 * Lazily loads per-field analysis resources from sidecar files stored
 * alongside the Lucene index. If a sidecar file does not exist, it is
 * built from the frozen reader on first access.
 * </p>
 * <ul>
 *   <li>{@link FieldStats} — field-level term occurrence counts and corpus
 *       totals ({@code <field>.stats})</li>
 *   <li>{@link TermLexicon} — FST-based dense term-to-id mapping
 *       ({@code <field>.fst})</li>
 *   <li>{@link TermRail} — memory-mapped forward positional index
 *       ({@code <field>.rail.dat}, {@code <field>.rail.off})</li>
 * </ul>
 *
 * <p>
 * Each resource is loaded at most once on success. If building or opening
 * fails, the holder remains unresolved and a subsequent call will retry.
 * Build order respects dependencies: {@link TermRail} requires
 * {@link TermLexicon}.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * All resource accessors are synchronized on this instance. Once loaded,
 * resources are immutable or read-only and safe for concurrent access.
 * {@link #topTerms()} is not synchronized: it delegates to the synchronized
 * accessors and then constructs a fresh, caller-owned object.
 * </p>
 *
 * @see Fluc#inferFields
 */
public final class FlucText extends Fluc
{
    /** Frozen reader, held for sidecar building. */
    private final IndexReader reader;
    /** Directory where sidecar files are stored and read. */
    private final Path sideDir;
    /** Lucene index options for this field. */
    private final IndexOptions indexOptions;
    /** Whether term vectors are stored. */
    private final boolean hasTermVectors;
    /** Whether norms are present. */
    private final boolean hasNorms;

    private final LazyResource<FieldStats>  fieldStatsHolder = new LazyResource<>();
    private final LazyResource<TermLexicon> lexiconHolder    = new LazyResource<>();
    private final LazyResource<TermRail>    railHolder       = new LazyResource<>();

    /**
     * Creates a text-field handle.
     *
     * <p>
     * Called by {@link Fluc#inferFields} after stored-field probing.
     * No I/O is performed here; all resources are loaded lazily on
     * first access.
     * </p>
     *
     * @param fi      segment-level field metadata
     * @param reader  frozen index reader
     * @param sideDir directory for sidecar files (typically the index directory)
     * @throws IOException if probing stored values on the reader fails
     */
    protected FlucText(
        final FieldInfo   fi,
        final IndexReader reader,
        final Path        sideDir
    ) throws IOException {
        super(fi, probeStoredViaPostings(reader, fi.name), reader.getDocCount(fi.name));
        this.indexOptions = fi.getIndexOptions();
        description.put("indexOptions",
            this.indexOptions.toString().replace("_AND_", " ").toLowerCase().replace('_', ' '));
        this.hasTermVectors = fi.hasTermVectors();
        description.put("termVectors", this.hasTermVectors);
        this.hasNorms = fi.hasNorms();
        description.put("norms", this.hasNorms);
        final Terms terms = MultiTerms.getTerms(reader, fi.name);
        if (terms == null) {
            throw new IllegalStateException("Field '" + fi.name + "' has no terms; cannot be used by Alix");
        }
        description.put("terms", terms.size());
        this.reader  = reader;
        this.sideDir = sideDir;
    }

    /** Directory where sidecar files are stored and read. */
    public Path sideDir() { return sideDir; }

    /** True if norms are present (required for scoring). */
    public boolean hasNorms() { return hasNorms; }

    /**
     * True if character offsets are indexed.
     * Required for highlight and concordance operations.
     */
    public boolean hasOffsets()
    {
        return indexOptions.compareTo(
            IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
    }

    /**
     * True if positions are indexed.
     * Required for phrase queries, KWIC, and co-occurrence analysis.
     */
    public boolean hasPositions()
    {
        return indexOptions.compareTo(
            IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0;
    }

    /** True if term vectors are stored. */
    public boolean hasTermVectors() { return hasTermVectors; }

    /**
     * Field-level term occurrence counts and corpus totals.
     * Read from a sidecar file, or built from postings on first access.
     *
     * <p>
     * The returned instance is immutable and shared. Callers must not
     * modify its contents.
     * </p>
     *
     * @return field statistics, never {@code null}
     * @throws java.io.UncheckedIOException if building or reading the sidecar fails
     */
    public synchronized FieldStats fieldStats()
    {
        return fieldStatsHolder.get(
            () -> FieldStats.exists(sideDir, name()),
            () -> FieldStats.build(reader, sideDir, name(), Report.ReportNull.INSTANCE),
            () -> FieldStats.open(reader, sideDir, name(), null)
        );
    }

    /**
     * Dense term lexicon (FST + memory-mapped term data).
     * Read from sidecar files, or built from postings on first access.
     *
     * <p>
     * The returned instance is immutable and shared. Callers must not
     * modify its contents.
     * </p>
     *
     * @return lexicon, never {@code null}
     * @throws java.io.UncheckedIOException if building or reading the sidecar fails
     */
    public synchronized TermLexicon termLexicon()
    {
        return lexiconHolder.get(
            () -> TermLexicon.exists(sideDir, name()),
            () -> TermLexicon.build(reader, sideDir, name()),
            () -> TermLexicon.open(sideDir, name())
        );
    }

    /**
     * Forward positional rail (memory-mapped).
     * Read from sidecar files, or built from postings on first access.
     * Ensures {@link #termLexicon()} is loaded first.
     *
     * @return rail, never {@code null}
     * @throws java.io.UncheckedIOException if building or reading the sidecar fails
     */
    public synchronized TermRail termRail()
    {
        final TermLexicon lex = termLexicon();
        return railHolder.get(
            () -> TermRail.exists(sideDir, name()),
            () -> TermRail.build(reader, sideDir, name(), lex, Report.ReportNull.INSTANCE),
            () -> TermRail.open(sideDir, name())
        );
    }

    /**
     * Creates a fresh {@link TopTerms} instance for this field, ready to
     * receive focus-subset statistics and be scored.
     *
     * <p>
     * The returned object holds array references from {@link FieldStats}
     * (field-level term counts) and {@link TermLexicon} (term display),
     * both loaded lazily if not already available. Focus arrays are
     * pre-allocated but empty; call
     * {@code TermCollector.collect(focusDocs, topTerms)} to populate them,
     * then {@code topTerms.score(scorer, ...)} to rank.
     * </p>
     *
     * <p>
     * The returned instance is <em>not</em> cached. Each call allocates
     * fresh focus arrays, so concurrent requests for different subsets
     * do not share mutable state.
     * </p>
     *
     * <h2>Typical usage</h2>
     * <pre>{@code
     * TopTerms topTerms = fluc.topTerms();
     * collector.collect(focusDocs, topTerms);
     * topTerms.score(new KeynessScorer.LogRatio(), 10.83, 3, topK);
     * for (TopTerms.TermEntry e : topTerms) { ... }
     * }</pre>
     *
     * @return a new, uncollected {@code TopTerms} for this field
     * @throws java.io.UncheckedIOException if loading {@link FieldStats} or
     *         {@link TermLexicon} fails
     */
    public TopTerms topTerms()
    {
        return new TopTerms(fieldStats(), termLexicon());
    }

    /**
     * Releases memory-mapped resources (lexicon and rail).
     *
     * <p>
     * After close, all accessors on this instance will fail or return stale
     * data. {@link FieldStats} holds no native resources; its holder is
     * simply reset.
     * </p>
     */
    @Override
    public synchronized void close() throws IOException
    {
        railHolder.close();
        lexiconHolder.close();
        fieldStatsHolder.close();
    }

}
