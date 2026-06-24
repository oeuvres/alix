package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;

import com.github.oeuvres.alix.util.IntList;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable in-memory lookup table for one indexed field of one frozen Lucene directory.
 * <p>
 * The lexicon holds two heap arrays:
 * </p>
 * <ul>
 *   <li>{@code dat} — concatenated UTF-8 bytes of all terms in {@code termId} order;</li>
 *   <li>{@code off} — {@code int} offsets into {@code dat}, length {@code vocabSize + 1}.</li>
 * </ul>
 * <p>
 * Term id 0 is reserved and represents the absence of a term (empty position). Real term ids start at 1
 * and are dense. They are assigned in the lexicographic iteration order of the field's merged
 * {@link TermsEnum}; id 0 is a zero-length phantom entry ({@code off[0] == off[1] == 0}) so that
 * {@link #form(int) form(0)} returns {@code ""}.
 * </p>
 * <p>
 * Because ids follow lexicographic byte order, {@code dat}/{@code off} is itself a sorted table, so the
 * forward direction {@code term -> id} is an unsigned-byte binary search over it ({@link #id(BytesRef)}),
 * performed directly on the stored UTF-8 with no charset conversion and no auxiliary index.
 * </p>
 * <p>
 * The lexicon is <b>not persisted</b>. It is rebuilt from the reader each time it is constructed, in a
 * single {@link TermsEnum} pass. The reader is used only during construction and is not retained; the
 * resulting instance is plain immutable heap state, safe for concurrent reads without locking, and holds
 * no native resources to release.
 * </p>
 * <p>
 * The term ids produced here are meaningful only against the exact term ordering of the reader they were
 * built from. The same property is what a {@link TermRail} encodes. Keeping lexicon and rail consistent is
 * therefore a matter of building both against the same index, and of validating the rail against the
 * index it belongs to — not against the lexicon. This class deliberately stores nothing that would let it
 * be paired with the wrong index.
 * </p>
 * <p>
 * String lookup assumes that the caller provides the field's canonical indexed form. No analysis,
 * normalization, stemming or lower-casing is applied here.
 * </p>
 * <p>
 * <b>Optional enrichment.</b> When a Hunspell dictionary ({@code dic}) and affix file ({@code aff}) are
 * supplied to {@link #TermLexicon(IndexReader, String, InputStream, InputStream)}, two extra artifacts are
 * cached, both built from the lexicon's own vocabulary so that they describe <em>this</em> field and no
 * more:
 * </p>
 * <ul>
 *   <li>a field-restricted {@link Dictionary}: the canonical {@code dic} filtered to the entries whose
 *       lemma headword is indexed in this field, paired with the unchanged {@code aff}. The affix rules are
 *       kept on purpose, so a query parser can still resolve a copied inflected form back to the indexed
 *       lemmas. Retrieve it with {@link #hunspell()} and drive it as you wish; this class never spells,
 *       suggests, parses or corrects on its own. The {@code Dictionary} is immutable and concurrent-safe;
 *       the (non-thread-safe) {@code Hunspell} runtime, if needed, is the caller's to create per thread.</li>
 *   <li>part-of-speech membership: one {@link BitSet} of term ids per {@link Upos} class actually present,
 *       harvested from the {@code po:} morphological fields of the kept lines. A term id may belong to
 *       several classes at once (the source marks many lemmas as both NOUN and ADJ). Query it with
 *       {@link #pos(int)} or {@link #pos(Upos)}.</li>
 * </ul>
 * <p>
 * The enrichment changes none of the guarantees above: the cached {@code Dictionary} is heap state (its
 * build-time temporary {@link Directory} is in-memory and closed during construction), the BitSets are
 * heap state, and the instance still holds no native resources. The join between dictionary headword and
 * indexed term is a raw byte match through {@link #id(String)}; supply headwords in the same normalized
 * form your analyzer produced (apostrophe and accent normalization in particular) or coverage silently
 * drops.
 * </p>
 *
 * @see TermRail
 */
public final class TermLexicon {
    
    /**
     * Universal POS tags, named exactly as the {@code po:} values in the Hunspell dictionary so that lookup
     * is a direct {@link #valueOf(String)}. Only a limited set of pos is used for querying.
     */
    public enum Upos {
        ADJ, MWE, NOUN, PERS, PLACE, PROPN, STOP, VERB, X;
    }

    /** Indexed field for which this lexicon was built. */
    private final String field;

    /** Concatenation of all term bytes in term-id order. */
    private final byte[] dat;

    /**
     * Offsets into {@link #dat}; length is {@code vocabSize + 1}. For a term id {@code i}, the term bytes
     * occupy {@code dat[off[i] .. off[i + 1])}.
     */
    private final int[] off;

    /** Number of entries including the reserved id 0; valid ids span {@code [0, vocabSize)}. */
    private final int vocabSize;

    /**
     * Field-restricted Hunspell dictionary, or {@code null} when {@code dic} or {@code aff} was absent (or
     * no headword matched). Immutable and safe to share across threads.
     */
    private final Dictionary hunspell;

    /**
     * Part-of-speech membership: for each {@link Upos} class observed, the set of term ids carrying it.
     * Only classes actually present get a BitSet; never {@code null} (empty when no {@code dic} was given).
     */
    private final EnumMap<Upos, BitSet> posBits;

    /**
     * Builds the bare lexicon for one field, with no Hunspell dictionary and no part-of-speech data.
     * Equivalent to {@link #TermLexicon(IndexReader, String, InputStream, InputStream)} with both streams
     * {@code null}.
     *
     * @param reader snapshot reader that defines the term universe and its lexicographic order
     * @param field  indexed field name
     * @throws IOException              if reading the term dictionary fails, or 32-bit limits are exceeded
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if {@code reader} or {@code field} is null
     */
    public TermLexicon(final IndexReader reader, final String field) throws IOException {
        this(reader, field, null, null);
    }

    /**
     * Builds the lexicon for one field and, when the Hunspell sources are supplied, the field-restricted
     * dictionary and the part-of-speech BitSets. The reader and both streams are consulted only here and
     * are not retained; the streams are read once and are not closed (the caller keeps ownership).
     * <p>
     * Effect of the nullable Hunspell sources:
     * </p>
     * <ul>
     *   <li>{@code dic == null} (any {@code aff}): bare table; {@link #hunspell()} is {@code null} and every
     *       part-of-speech set is empty. An {@code aff} given without a {@code dic} is ignored.</li>
     *   <li>{@code dic != null}, {@code aff == null}: part-of-speech BitSets are populated from the
     *       {@code po:} fields of the indexed lemmas; {@link #hunspell()} is {@code null}, because a
     *       {@link Dictionary} needs the affix rules.</li>
     *   <li>{@code dic != null}, {@code aff != null}: part-of-speech BitSets are populated and
     *       {@link #hunspell()} returns the restricted dictionary (or {@code null} if no dictionary headword
     *       is indexed in this field).</li>
     * </ul>
     *
     * @param reader snapshot reader that defines the term universe and its lexicographic order
     * @param field  indexed field name
     * @param dic    canonical Hunspell {@code .dic} (count header on line one, one entry per line, optional
     *               {@code po:} fields), or {@code null}
     * @param aff    Hunspell {@code .aff} matching {@code dic}, or {@code null}
     * @throws IOException              if reading the term dictionary or {@code dic} fails, if 32-bit limits
     *                                  are exceeded, or if the Hunspell dictionary cannot be parsed
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if {@code reader} or {@code field} is null
     */
    public TermLexicon(final IndexReader reader, final String field,
            final InputStream dic, final InputStream aff) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        this.field = field;

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }

        final IntList offsets = new IntList();
        offsets.push(0);                       // off[0]: start of phantom empty-term slot
        offsets.push(0);                       // off[1]: end of phantom — zero length
        final ByteArrayOutputStream datOut = new ByteArrayOutputStream(1 << 20);

        int termId = 1;
        int datPos = 0;
        final TermsEnum te = terms.iterator();
        BytesRef term;
        while ((term = te.next()) != null) {
            if (termId == Integer.MAX_VALUE) {
                throw new IOException("Too many terms for int term ids");
            }
            if (datPos > Integer.MAX_VALUE - term.length) {
                throw new IOException("Term bytes exceed 2 GiB; 32-bit offsets insufficient");
            }
            datOut.write(term.bytes, term.offset, term.length);
            datPos += term.length;
            offsets.push(datPos);
            termId++;
        }
        if (termId == 1) {
            throw new IllegalArgumentException("Field has no terms: " + field);
        }

        this.dat = datOut.toByteArray();
        this.off = offsets.toArray();
        this.vocabSize = off.length - 1;

        final EnumMap<Upos, BitSet> bits = new EnumMap<>(Upos.class);
        Dictionary dict = null;
        if (dic != null) {
            final StringBuilder body = (aff != null) ? new StringBuilder(1 << 20) : null;
            final BytesRefBuilder probe = new BytesRefBuilder();
            final BufferedReader in = new BufferedReader(new InputStreamReader(dic, StandardCharsets.UTF_8));
            int kept = 0;
            in.readLine();                     // discard the dic count header (line 1)
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                final int cut = headwordEnd(line);
                if (cut == 0) {
                    continue;
                }
                probe.copyChars(line, 0, cut);
                final int tid = id(probe.get());
                if (tid < 1) {
                    continue;          // headword not indexed in this field
                }
                kept++;
                if (body != null) {
                    body.append(line).append('\n');
                }
                harvestPos(line, tid, bits);
            }
            // TODO authority list: append reviewed proper-name lines to body and set their PROPN bits here.
            if (aff != null && kept > 0) {
                final ByteArrayInputStream filtered = new ByteArrayInputStream(
                    (kept + "\n" + body).getBytes(StandardCharsets.UTF_8));
                final Directory tmp = new ByteBuffersDirectory();
                try {
                    dict = new Dictionary(tmp, "hunspell", aff, filtered);
                } catch (final ParseException e) {
                    throw new IOException("Hunspell dictionary parse failed for field: " + field, e);
                } finally {
                    tmp.close();
                }
            }
        }
        this.hunspell = dict;
        this.posBits = bits;
    }

    /**
     * Returns the indexed field name covered by this lexicon.
     *
     * @return field name, never null
     */
    public String field() {
        return field;
    }

    /**
     * Returns the term string for one dense term id.
     * <p>
     * {@code form(0)} returns the empty string (reserved absent-term slot). For tight loops over the full
     * vocabulary that can avoid {@link String} allocation, prefer
     * {@link #formBytes(int, BytesRefBuilder)} with a caller-owned buffer.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return decoded UTF-8 term string, never null; empty for the reserved id 0
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public String form(final int termId) {
        checkTermId(termId);
        final int start = off[termId];
        final int length = off[termId + 1] - start;
        return new String(dat, start, length, StandardCharsets.UTF_8);
    }

    /**
     * Copies the raw UTF-8 bytes of one term into a caller-provided reusable buffer.
     * <p>
     * This avoids allocation when called in a loop. The bytes are copied directly from the in-heap
     * {@code dat} array.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @param reuse  destination buffer that will receive the term bytes; grown automatically if needed
     * @return {@code reuse.get()} after the copy, valid until the next call on the same buffer
     * @throws IllegalArgumentException if {@code termId} is out of range
     * @throws NullPointerException     if {@code reuse} is null
     */
    public BytesRef formBytes(final int termId, final BytesRefBuilder reuse) {
        checkTermId(termId);
        Objects.requireNonNull(reuse, "reuse");
        final int start = off[termId];
        final int length = off[termId + 1] - start;
        reuse.grow(length);
        System.arraycopy(dat, start, reuse.bytes(), 0, length);
        reuse.setLength(length);
        return reuse.get();
    }

    /**
     * Returns the field-restricted Hunspell dictionary, or {@code null} when it was not built.
     * <p>
     * It is built only when both {@code dic} and {@code aff} were supplied at construction and at least one
     * dictionary headword is indexed in this field. The returned {@link Dictionary} is immutable and may be
     * shared across threads; wrap it in a fresh {@code org.apache.lucene.analysis.hunspell.Hunspell} per
     * thread for spelling or suggestion, since that runtime is not thread-safe.
     * </p>
     *
     * @return restricted immutable dictionary, or {@code null} if unavailable
     */
    public Dictionary hunspell() {
        return hunspell;
    }

    /**
     * Looks up the dense term id for a canonical indexed term given as raw UTF-8 bytes.
     * <p>
     * Binary search over the lexicographically sorted term table, comparing {@code term} against the
     * stored bytes with unsigned-byte semantics matching {@link BytesRef#compareTo(BytesRef)} and the
     * order in which ids were assigned. The reserved id 0 (empty term) is never returned for a non-empty
     * query.
     * </p>
     *
     * @param term canonical indexed term as UTF-8 bytes
     * @return dense term id in {@code [1, vocabSize)}, or {@code -1} if the term is absent
     * @throws NullPointerException if {@code term} is null
     */
    public int id(final BytesRef term) {
        Objects.requireNonNull(term, "term");
        int lo = 1;
        int hi = vocabSize - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int c = compareToTerm(mid, term);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /**
     * Looks up the dense term id for a canonical indexed term given as a Java string.
     * <p>
     * The string is encoded to UTF-8 and delegated to {@link #id(BytesRef)}. No analysis or normalization
     * is applied.
     * </p>
     *
     * @param term canonical indexed term form, must match the analyzer output exactly
     * @return dense term id in {@code [1, vocabSize)}, or {@code -1} if the term is absent
     * @throws NullPointerException if {@code term} is null
     */
    public int id(final String term) {
        Objects.requireNonNull(term, "term");
        final BytesRefBuilder bytes = new BytesRefBuilder();
        bytes.copyChars(term);
        return id(bytes.get());
    }

    /**
     * Returns the part-of-speech classes carried by one term id.
     * <p>
     * The set is empty when no {@code dic} was supplied, when the term's headword was absent from the
     * dictionary, or when the term is an out-of-dictionary form (proper name, technical token, number).
     * A term may carry more than one class.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return a fresh, mutable {@link EnumSet} of classes; empty if none
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public EnumSet<Upos> pos(final int termId) {
        checkTermId(termId);
        final EnumSet<Upos> set = EnumSet.noneOf(Upos.class);
        for (final Map.Entry<Upos, BitSet> e : posBits.entrySet()) {
            if (e.getValue().get(termId)) {
                set.add(e.getKey());
            }
        }
        return set;
    }

    /**
     * Returns the set of term ids carrying one part-of-speech class.
     * <p>
     * The result is a defensive copy: the caller may read, iterate or mutate it freely without affecting
     * the lexicon. An empty BitSet is returned when the class is absent or when no {@code dic} was given.
     * </p>
     *
     * @param tag part-of-speech class
     * @return a fresh {@link BitSet} of term ids, never null
     * @throws NullPointerException if {@code tag} is null
     */
    public BitSet pos(final Upos tag) {
        Objects.requireNonNull(tag, "tag");
        final BitSet bs = posBits.get(tag);
        return (bs == null) ? new BitSet(0) : (BitSet) bs.clone();
    }

    /**
     * Resolves the terms in a query to their term ids, restricted to this lexicon's field.
     * <p>
     * Terms the lexicon does not know (e.g. never indexed, or belonging to another field) are silently
     * dropped. The query must already be rewritten via {@code IndexSearcher.rewrite(Query)}.
     * </p>
     *
     * @param query a rewritten {@link Query}
     * @return distinct term ids in query-visit order; unknown terms omitted
     */
    public int[] termIds(final Query query) {
        final IntList ids = new IntList();
        query.visit(new QueryVisitor() {
            @Override
            public void consumeTerms(final Query q, final Term... ts) {
                for (final Term t : ts) {
                    final int termId = id(t.bytes());
                    if (termId >= 0) {
                        ids.push(termId);
                    }
                }
            }
        });
        return ids.toUniq();
    }

    /**
     * Returns the number of entries in the lexicon, including the reserved id 0.
     * <p>
     * Real terms occupy ids {@code [1, vocabSize)}; the count of real terms is {@code vocabSize() - 1}.
     * </p>
     *
     * @return vocabulary size (always &gt; 1 for a valid lexicon)
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Checks that a term id falls within the valid range {@code [0, vocabSize)}.
     *
     * @param termId dense term id to validate
     * @throws IllegalArgumentException if the id is negative or &ge; {@link #vocabSize}
     */
    private void checkTermId(final int termId) {
        if (termId < 0 || termId >= vocabSize) {
            throw new IllegalArgumentException(
                "termId out of range: " + termId + " (vocabSize=" + vocabSize + ")");
        }
    }

    /**
     * Compares the stored term at {@code termId} against {@code term} with unsigned-byte lexicographic
     * order, matching {@link BytesRef#compareTo(BytesRef)} and the order in which ids were assigned.
     *
     * @param termId stored term id to compare
     * @param term   query term as UTF-8 bytes
     * @return negative, zero, or positive if the stored term is respectively less than, equal to, or
     *         greater than {@code term}
     */
    private int compareToTerm(final int termId, final BytesRef term) {
        final int start = off[termId];
        final int end = off[termId + 1];
        return Arrays.compareUnsigned(
            dat, start, end,
            term.bytes, term.offset, term.offset + term.length);
    }

    /**
     * Sets the part-of-speech bits for one kept dictionary line, reading every {@code po:} field on it.
     * A BitSet is created on first use of a class, so only classes actually present are allocated.
     *
     * @param line   raw dictionary line
     * @param termId resolved term id for the line's headword
     * @param bits   destination map of class to term-id set
     */
    private void harvestPos(final String line, final int termId, final EnumMap<Upos, BitSet> bits) {
        final int n = line.length();
        int i = line.indexOf(" po:");
        while (i >= 0) {
            final int start = i + 4;
            int end = start;
            while (end < n && line.charAt(end) != ' ' && line.charAt(end) != '\t') {
                end++;
            }
            final Upos u = uposOf(line.substring(start, end));
            if (u != null) {
                bits.computeIfAbsent(u, k -> new BitSet(vocabSize)).set(termId);
            }
            i = line.indexOf(" po:", end);
        }
    }

    /**
     * Returns the index just past a dictionary line's headword: the first {@code '/'} (affix-flag
     * separator) or ASCII whitespace, or the line length if neither occurs.
     *
     * @param line raw dictionary line
     * @return headword length in chars
     */
    private static int headwordEnd(final String line) {
        for (int i = 0, n = line.length(); i < n; i++) {
            final char c = line.charAt(i);
            if (c == '/' || c == ' ' || c == '\t') {
                return i;
            }
        }
        return line.length();
    }

    /**
     * Maps a {@code po:} tag value to a {@link Upos} constant, returning {@code null} for any value outside
     * the enum so that unexpected tags are skipped rather than throwing.
     *
     * @param tag tag value (the part after {@code po:})
     * @return matching constant, or {@code null} if unknown
     */
    private static Upos uposOf(final String tag) {
        try {
            return Upos.valueOf(tag);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }


}
