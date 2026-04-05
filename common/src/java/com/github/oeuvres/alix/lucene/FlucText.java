package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;

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
 * Each resource is loaded at most once on success. If building or opening
 * fails, the holder remains unresolved and a subsequent call will retry.
 * Build order respects dependencies: TermRail requires TermLexicon;
 * ThemeTerms requires both FieldStats and TermLexicon.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * All resource accessors are synchronized on this instance. Once loaded,
 * resources are immutable or read-only and safe for concurrent access.
 * </p>
 *
 * @see Fluc#inferFields
 */
public final class FlucText extends Fluc
{
    /** Frozen reader, held for sidecar building and rail construction. */
    private final IndexReader reader;
    /** Directory for sidecar file access in subclasses. */
    protected final Path sideDir;

    private final LazyResource<FieldStats> fieldStatsHolder = new LazyResource<>();
    private final LazyResource<TermLexicon> lexiconHolder = new LazyResource<>();
    private final LazyResource<TermRail> railHolder = new LazyResource<>();

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
     * @param sideDir Lucene index directory
     * @param reader   frozen index reader
     * @throws IOException 
     */
    public FlucText(
        final IndexReader reader,
        final FieldInfo fi,
        final Path sideDir
    ) throws IOException {
        super(fi, probeStored(reader, fi.name), reader.getDocCount(fi.name));
        this.reader = reader;
        this.sideDir = sideDir;
    }

    /** Lucene index directory. */
    public Path sideDir() { return sideDir; }

    /**
     * Immutable reference statistics for this field.
     * Built from postings if the sidecar file does not exist.
     *
     * @return field statistics, never {@code null}
     * @throws java.io.UncheckedIOException if building or reading fails
     */
    public synchronized FieldStats fieldStats()
    {
        return fieldStatsHolder.get(
            () -> FieldStats.exists(sideDir, name()),
            () -> FieldStats.build(reader, sideDir, name(), Report.ReportNull.INSTANCE),
            () -> FieldStats.open(reader, sideDir, name())
        );
    }

    /**
     * Dense term lexicon (FST + memory-mapped term data).
     * Built from postings if the sidecar files do not exist.
     *
     * @return lexicon, never {@code null}
     * @throws java.io.UncheckedIOException if building or reading fails
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
     * Built from postings if the sidecar files do not exist.
     * Ensures the {@link #termLexicon()} is available first.
     *
     * @return rail, never {@code null}
     * @throws java.io.UncheckedIOException if building or reading fails
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
     * Releases memory-mapped resources (lexicon and rail).
     *
     * <p>
     * After close, all accessors on this instance are invalid.
     * {@link ThemeTerms} and {@link FieldStats} hold no native
     * resources; their holders are simply reset.
     * </p>
     */
    @Override
    public synchronized void close() throws IOException
    {
        railHolder.close();
        lexiconHolder.close();
        fieldStatsHolder.close();
    }
    
    /**
     * Find the first document with a posting for this field,
     * then check if it also has a stored value.
     */
    static boolean probeStored(
        final IndexReader reader, final String fieldName
    ) throws IOException
    {
        for (LeafReaderContext ctx : reader.leaves()) {
            final LeafReader leaf = ctx.reader();
            final Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            final TermsEnum te = terms.iterator();
            if (te.next() == null) continue;
            final PostingsEnum pe = te.postings(null, PostingsEnum.NONE);
            final int localDoc = pe.nextDoc();
            if (localDoc == PostingsEnum.NO_MORE_DOCS) continue;
            return isFieldStored(reader, ctx.docBase + localDoc, fieldName);
        }
        return false;
    }
}
