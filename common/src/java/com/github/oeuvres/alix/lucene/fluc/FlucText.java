package com.github.oeuvres.alix.lucene.fluc;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;

import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermSuggest;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.util.Report;

/**
 * Tokenized Lucene field with positional resources used by Alix operations.
 *
 * <p>
 * A {@code FlucText} represents one indexed text field. It exposes lazily loaded
 * field resources:
 * </p>
 *
 * <ul>
 *   <li>{@link TermStats}: field-level term and document statistics;</li>
 *   <li>{@link TermLexicon}: dense term-id mapping and term display strings;</li>
 *   <li>{@link TermRail}: forward positional rail for spans and co-occurrences;</li>
 *   <li>{@link TermSuggest}: folded term-suggestion index.</li>
 * </ul>
 *
 * <p>
 * Sidecar-backed resources are built on first access if their sidecar files are
 * absent, then opened and cached. Failed builds or opens do not poison the
 * object; a later call retries because the corresponding field remains
 * {@code null}.
 * </p>
 *
 * <p>
 * Public resource accessors are synchronized. Loaded resources are shared by
 * requests and are expected to be immutable or read-only. {@link #topTerms()}
 * returns a fresh mutable {@link TopTerms} object for each call.
 * </p>
 *
 * @see Fluc#inferFields
 */
public final class FlucText extends Fluc
{

    /** Whether norms are available for this field. */
    private final boolean hasNorms;

    /** Whether term vectors are stored for this field. */
    private final boolean hasTermVectors;

    /** Lucene index options for this field. */
    private final IndexOptions indexOptions;

    /** Frozen reader used to build sidecar resources. */
    private final IndexReader reader;

    /** Directory where sidecar resources are stored. */
    private final Path sideDir;

    /** Dense term lexicon, loaded lazily. */
    private TermLexicon termLexicon;

    /** Forward positional rail, loaded lazily. */
    private TermRail termRail;

    /** Field statistics, loaded lazily. */
    private TermStats termStats;
    
    /** Term suggester, built lazily from the lexicon and field statistics. */
    private TermSuggest termSuggest;

    /**
     * Creates a text-field handle.
     *
     * <p>
     * The constructor validates that the field has indexed terms but does not
     * load sidecar resources. Resource loading is deferred to the corresponding
     * accessor methods.
     * </p>
     *
     * @param fi Lucene field metadata
     * @param reader frozen index reader
     * @param sideDir directory where sidecar files are stored
     * @throws IOException if stored-field probing or term metadata access fails
     * @throws IllegalStateException if the field has no indexed terms
     */
    protected FlucText(
        final FieldInfo fi,
        final IndexReader reader,
        final Path sideDir
    ) throws IOException {
        super(fi, probeStoredViaPostings(reader, fi.name), reader.getDocCount(fi.name));

        this.reader = reader;
        this.sideDir = sideDir;
        this.indexOptions = fi.getIndexOptions();
        this.hasNorms = fi.hasNorms();
        this.hasTermVectors = fi.hasTermVectors();

        description.put(
            "indexOptions",
            indexOptions.toString().replace("_AND_", " ").toLowerCase().replace('_', ' ')
        );
        description.put("norms", hasNorms);
        description.put("termVectors", hasTermVectors);

        final Terms terms = MultiTerms.getTerms(reader, fi.name);
        if (terms == null) {
            throw new IllegalStateException(
                "Field '" + fi.name + "' has no terms; cannot be used by Alix"
            );
        }
        description.put("terms", terms.size());
    }

    /**
     * Releases loaded resources and clears cached handles.
     *
     * <p>
     * {@link TermRail} and {@link TermLexicon} may hold closeable resources.
     * {@link TermSuggest} is cleared because it depends on the current lexicon
     * and statistics handles.
     * </p>
     *
     * @throws IOException if closing a loaded resource fails
     */
    @Override
    public synchronized void close() throws IOException
    {
        IOException failure = null;

        try {
            failure = closeResource(termRail, failure);
            failure = closeResource(termLexicon, failure);
        }
        finally {
            termSuggest = null;
            termRail = null;
            termLexicon = null;
            termStats = null;
        }

        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Reports whether norms are present.
     *
     * @return {@code true} if norms are available
     */
    public boolean hasNorms()
    {
        return hasNorms;
    }

    /**
     * Reports whether character offsets are indexed.
     *
     * @return {@code true} if offsets are available
     */
    public boolean hasOffsets()
    {
        return indexOptions.compareTo(
            IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS
        ) >= 0;
    }

    /**
     * Reports whether token positions are indexed.
     *
     * @return {@code true} if positions are available
     */
    public boolean hasPositions()
    {
        return indexOptions.compareTo(
            IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
        ) >= 0;
    }

    /**
     * Reports whether term vectors are stored.
     *
     * @return {@code true} if term vectors are stored
     */
    public boolean hasTermVectors()
    {
        return hasTermVectors;
    }

    /**
     * Returns the sidecar directory.
     *
     * @return sidecar directory
     */
    public Path sideDir()
    {
        return sideDir;
    }

    /**
     * Returns the dense term lexicon for this field.
     *
     * @return dense term lexicon
     * @throws UncheckedIOException if building or opening the lexicon fails
     */
    public synchronized TermLexicon termLexicon()
    {
        if (termLexicon != null) {
            return termLexicon;
        }

        try {
            termLexicon = new TermLexicon(reader, name());
            return termLexicon;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the forward positional rail for this field.
     *
     * <p>
     * The term lexicon is loaded first because rail construction requires dense
     * term ids.
     * </p>
     *
     * @return forward positional rail
     * @throws UncheckedIOException if building or opening the rail fails
     */
    public synchronized TermRail termRail()
    {
        if (termRail != null) {
            return termRail;
        }

        final TermLexicon lexicon = termLexicon();

        try {
            if (!TermRail.exists(sideDir, name())) {
                TermRail.build(reader, sideDir, name(), lexicon, Report.ReportNull.INSTANCE);
            }
            termRail = TermRail.open(sideDir, name());
            return termRail;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns field-level term occurrence counts and corpus totals.
     *
     * <p>
     * If the statistics sidecar does not exist, it is built from the frozen
     * reader before opening.
     * </p>
     *
     * @return field statistics
     * @throws UncheckedIOException if building or opening statistics fails
     */
    public synchronized TermStats termStats()
    {
        if (termStats != null) {
            return termStats;
        }
    
        try {
            if (!TermStats.exists(sideDir, name())) {
                TermStats.build(reader, sideDir, name(), Report.ReportNull.INSTANCE);
            }
            termStats = TermStats.open(reader, sideDir, name(), null);
            return termStats;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the term suggester for this field.
     *
     * <p>
     * The suggester is an in-memory folded term index built from
     * {@link #termLexicon()} and {@link #termStats()}. It is cached because it
     * scans all vocabulary terms during construction. The returned object should
     * be treated as read-only and shared.
     * </p>
     *
     * @return term suggester
     * @throws UncheckedIOException if loading the lexicon or statistics fails
     */
    public synchronized TermSuggest termSuggest()
    {
        if (termSuggest != null) {
            return termSuggest;
        }

        termSuggest = new TermSuggest(termLexicon(), termStats());
        return termSuggest;
    }

    /**
     * Creates a fresh ranked-term container for this field.
     *
     * <p>
     * The returned object is not cached. It is mutable and belongs to the caller.
     * It is initialized with this field's shared statistics and lexicon.
     * </p>
     *
     * @return fresh term-list container
     * @throws UncheckedIOException if loading the lexicon or statistics fails
     */
    public TopTerms topTerms()
    {
        return new TopTerms(termStats(), termLexicon());
    }

    /**
     * Closes one resource and records the first failure.
     *
     * @param resource resource to close, ignored if it is not closeable
     * @param failure previous failure, or {@code null}
     * @return the first failure, or {@code null} if no close failed
     */
    private static IOException closeResource(
        final Object resource,
        final IOException failure
    ) {
        if (!(resource instanceof Closeable closeable)) {
            return failure;
        }

        try {
            closeable.close();
            return failure;
        }
        catch (IOException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
            return failure;
        }
    }
}