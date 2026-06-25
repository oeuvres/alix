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
import org.apache.lucene.util.CharsRefBuilder;

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
 * and are dense, assigned in the lexicographic iteration order of the field's merged {@link TermsEnum};
 * id 0 is a zero-length phantom entry so that {@link #form(int) form(0)} returns {@code ""}.
 * </p>
 * <p>
 * Because ids follow lexicographic byte order, {@code dat}/{@code off} is itself a sorted table, so the
 * forward direction {@code term -> id} is an unsigned-byte binary search over it ({@link #id(BytesRef)}),
 * performed directly on the stored UTF-8 with no charset conversion and no auxiliary index.
 * </p>
 * <p>
 * The lexicon is <b>not persisted</b>. It is rebuilt from the reader each time it is constructed. The
 * reader is used only during construction and is not retained; the resulting instance is plain immutable
 * heap state, safe for concurrent reads without locking, and holds no native resources to release. The
 * term ids are meaningful only against the term ordering of the reader they were built from, which is the
 * same property a {@link TermRail} encodes.
 * </p>
 * <p>
 * String lookup assumes the caller provides the field's canonical indexed form. No analysis,
 * normalization, stemming or lower-casing is applied here.
 * </p>
 * <p>
 * <b>Optional enrichment.</b> Two further artifacts may be cached, both built from this field's own
 * vocabulary:
 * </p>
 * <ul>
 *   <li>a field-restricted {@link Dictionary} (Hunspell), when both a {@code dic} and an {@code aff} stream
 *       are supplied: the canonical {@code dic} filtered to the entries whose lemma headword is indexed in
 *       this field, paired with the unchanged {@code aff}. Retrieve it with {@link #hunspell()}; this class
 *       never spells or suggests on its own. The {@link Dictionary} is immutable and concurrent-safe; the
 *       non-thread-safe {@code Hunspell} runtime is the caller's to create per thread.</li>
 *   <li>term-flag membership: one {@link BitSet} of term ids per {@link TermFlag} actually set.
 *       Part-of-speech flags (ADJ/NOUN/VERB/X) are harvested from the {@code dic}'s {@code po:} fields
 *       during the dictionary scan; all other flags are set by a {@link TermFlagger} the caller supplies,
 *       which is offered every term once after the table is built. A term may carry several flags. Query
 *       with {@link #flags(int)}, {@link #bits(TermFlag)} or {@link #has(int, TermFlag)}.</li>
 * </ul>
 * <p>
 * The enrichment preserves every guarantee above: the cached {@link Dictionary} is heap state (its
 * build-time temporary {@link Directory} is in-memory and closed during construction), the BitSets are
 * heap state, and the flagger runs synchronously within the constructor, so the instance is frozen once it
 * escapes. The join between an external form (dictionary headword) and an indexed term is a raw byte match
 * through {@link #id(String)}; supply forms in the same normalized shape the analyzer produced or coverage
 * silently drops.
 * </p>
 *
 * @see TermRail
 * @see TermFlag
 * @see TermFlagger
 */
public final class TermLexicon {
    
    /**
     * Independent membership flags attachable to the term ids of a {@link TermLexicon}.
     * <p>
     * These are not the values of one axis. A term may carry several at once — a proper noun that is also a
     * person, a noun that is also a stopword — and each constant backs one orthogonal {@link java.util.BitSet}
     * of term ids. Part of speech is only one of the axes represented here, which is why this is a flat flag
     * set and not a part-of-speech enum.
     * </p>
     * <ul>
     *   <li>{@link #ADJ}, {@link #NOUN}, {@link #VERB}, {@link #X} — part of speech, harvested from the
     *       Hunspell {@code po:} fields. A {@code po:} value is used only when its name matches a constant
     *       here, so extending this enum extends what is harvested.</li>
     *   <li>{@link #PROPN} — proper-noun candidate.</li>
     *   <li>{@link #PERS}, {@link #PLACE} — named-entity types.</li>
     *   <li>{@link #MWE} — multi-word expression; meaningful only when such expressions are indexed as single
     *       tokens.</li>
     *   <li>{@link #STOP} — stopword.</li>
     * </ul>
     * <p>
     * Apart from the part-of-speech flags, the rest are set by a {@link TermFlagger} the caller supplies.
     * </p>
     */
    public static enum TermFlag {
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
     * Field-restricted Hunspell dictionary, or {@code null} when {@code dic} or {@code aff} was absent, or
     * no headword matched. Immutable and safe to share across threads.
     */
    private final Dictionary hunspell;

    /**
     * Term-flag membership: for each {@link TermFlag} actually set, the term ids carrying it. Only flags
     * that were set ever get a BitSet; never {@code null}, empty when nothing was flagged.
     */
    private final EnumMap<TermFlag, BitSet> flagBits;

    /**
     * Builds the bare lexicon for one field: no Hunspell dictionary, no flags.
     *
     * @param reader snapshot reader that defines the term universe and its lexicographic order
     * @param field  indexed field name
     * @throws IOException              if reading the term dictionary fails, or 32-bit limits are exceeded
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if {@code reader} or {@code field} is null
     */
    public TermLexicon(final IndexReader reader, final String field) throws IOException {
        this(reader, field, null, null, null);
    }

    /**
     * Builds the lexicon with a Hunspell dictionary and part-of-speech flags, but no flagger.
     *
     * @param reader snapshot reader
     * @param field  indexed field name
     * @param dic    canonical Hunspell {@code .dic}, or {@code null}
     * @param aff    Hunspell {@code .aff}, or {@code null}
     * @throws IOException              on read or parse failure, or if 32-bit limits are exceeded
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if {@code reader} or {@code field} is null
     */
    public TermLexicon(final IndexReader reader, final String field,
            final InputStream dic, final InputStream aff) throws IOException {
        this(reader, field, dic, aff, null);
    }

    /**
     * Builds the lexicon for one field, optionally enriched with a field-restricted Hunspell dictionary,
     * part-of-speech flags from the dictionary, and flagger-set flags. The reader and both streams are
     * consulted only here and not retained; the streams are read once and not closed (the caller keeps
     * ownership). The flagger, if any, is offered every term once after the table is built.
     * <p>
     * Effect of the nullable Hunspell sources:
     * </p>
     * <ul>
     *   <li>{@code dic == null}: no dictionary, no part-of-speech flags; an {@code aff} alone is ignored.
     *       The flagger still runs.</li>
     *   <li>{@code dic != null}, {@code aff == null}: part-of-speech flags harvested from {@code po:};
     *       {@link #hunspell()} is {@code null}.</li>
     *   <li>{@code dic != null}, {@code aff != null}: part-of-speech flags harvested and {@link #hunspell()}
     *       returns the restricted dictionary (or {@code null} if no headword is indexed here).</li>
     * </ul>
     *
     * @param reader  snapshot reader
     * @param field   indexed field name
     * @param dic     canonical Hunspell {@code .dic}, or {@code null}
     * @param aff     Hunspell {@code .aff}, or {@code null}
     * @param flagger term flagger, or {@code null}
     * @throws IOException              on read or parse failure, or if 32-bit limits are exceeded
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if {@code reader} or {@code field} is null
     */
    public TermLexicon(final IndexReader reader, final String field,
            final InputStream dic, final InputStream aff, final TermFlagger flagger) throws IOException {
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

        final EnumMap<TermFlag, BitSet> bits = new EnumMap<>(TermFlag.class);

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
        this.flagBits = bits;

        if (flagger != null) {
            flagger.bind(bits, vocabSize);
            final CharsRefBuilder chars = new CharsRefBuilder();
            for (int id = 1; id < vocabSize; id++) {
                chars.copyUTF8Bytes(dat, off[id], off[id + 1] - off[id]);   // one decode, reused buffer
                flagger.offer(id, chars.get());
            }
        }
    }

    /**
     * Returns the term ids carrying one flag.
     * <p>
     * The result is a defensive copy: the caller may read, iterate or mutate it without affecting the
     * lexicon. An empty BitSet is returned when the flag was never set. For pivot queries, intersect this
     * with a co-occurrence candidate set (e.g. {@code bits(TermFlag.PERS)} AND the cooc terms of a pivot).
     * </p>
     *
     * @param flag membership flag
     * @return a fresh {@link BitSet} of term ids, never null
     * @throws NullPointerException if {@code flag} is null
     */
    public BitSet bits(final TermFlag flag) {
        Objects.requireNonNull(flag, "flag");
        final BitSet bs = flagBits.get(flag);
        return (bs == null) ? new BitSet(0) : (BitSet) bs.clone();
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
     * Returns the flags carried by one term id.
     * <p>
     * Empty when the term was never flagged. A term may carry several flags.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return a fresh, mutable {@link EnumSet} of flags; empty if none
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public EnumSet<TermFlag> flags(final int termId) {
        checkTermId(termId);
        final EnumSet<TermFlag> set = EnumSet.noneOf(TermFlag.class);
        for (final Map.Entry<TermFlag, BitSet> e : flagBits.entrySet()) {
            if (e.getValue().get(termId)) {
                set.add(e.getKey());
            }
        }
        return set;
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
     * Copies the raw UTF-8 bytes of one term into a caller-provided reusable buffer, avoiding allocation
     * in a loop.
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
     * Tells whether one term id carries one flag, without allocating.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @param flag   membership flag
     * @return true iff the term is flagged
     * @throws IllegalArgumentException if {@code termId} is out of range
     * @throws NullPointerException     if {@code flag} is null
     */
    public boolean has(final int termId, final TermFlag flag) {
        checkTermId(termId);
        Objects.requireNonNull(flag, "flag");
        final BitSet bs = flagBits.get(flag);
        return bs != null && bs.get(termId);
    }

    /**
     * Returns the field-restricted Hunspell dictionary, or {@code null} when it was not built (both
     * {@code dic} and {@code aff} required, and at least one headword indexed in this field). Immutable and
     * shareable across threads; wrap in a fresh {@code Hunspell} per thread for spelling or suggestion.
     *
     * @return restricted immutable dictionary, or {@code null}
     */
    public Dictionary hunspell() {
        return hunspell;
    }

    /**
     * Looks up the dense term id for a canonical indexed term given as raw UTF-8 bytes.
     * <p>
     * Binary search over the lexicographically sorted term table with unsigned-byte semantics matching
     * {@link BytesRef#compareTo(BytesRef)}. The reserved id 0 is never returned for a non-empty query.
     * </p>
     *
     * @param term canonical indexed term as UTF-8 bytes
     * @return dense term id in {@code [1, vocabSize)}, or {@code -1} if absent
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
     * Looks up the dense term id for a canonical indexed term given as a Java string. The string is encoded
     * to UTF-8 and delegated to {@link #id(BytesRef)}. No analysis or normalization is applied.
     *
     * @param term canonical indexed term form, must match analyzer output exactly
     * @return dense term id in {@code [1, vocabSize)}, or {@code -1} if absent
     * @throws NullPointerException if {@code term} is null
     */
    public int id(final String term) {
        Objects.requireNonNull(term, "term");
        final BytesRefBuilder bytes = new BytesRefBuilder();
        bytes.copyChars(term);
        return id(bytes.get());
    }

    /**
     * Resolves the terms in a query to their term ids, restricted to this lexicon's field. Terms the
     * lexicon does not know are silently dropped. The query must already be rewritten via
     * {@code IndexSearcher.rewrite(Query)}.
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
     * Returns the number of entries in the lexicon, including the reserved id 0. Real terms occupy ids
     * {@code [1, vocabSize)}; the count of real terms is {@code vocabSize() - 1}.
     *
     * @return vocabulary size (always &gt; 1 for a valid lexicon)
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Checks that a term id falls within {@code [0, vocabSize)}.
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
     * order, matching {@link BytesRef#compareTo(BytesRef)} and the order ids were assigned in.
     *
     * @param termId stored term id to compare
     * @param term   query term as UTF-8 bytes
     * @return negative, zero, or positive as the stored term is less than, equal to, or greater than
     *         {@code term}
     */
    private int compareToTerm(final int termId, final BytesRef term) {
        final int start = off[termId];
        final int end = off[termId + 1];
        return Arrays.compareUnsigned(
            dat, start, end,
            term.bytes, term.offset, term.offset + term.length);
    }

    /**
     * Sets part-of-speech flags for one kept dictionary line, reading every {@code po:} field on it. A
     * value is used only when it names a {@link TermFlag} constant, so the enum decides what is harvested.
     *
     * @param line   raw dictionary line
     * @param termId resolved term id for the line's headword
     * @param bits   flag sets being filled
     */
    private void harvestPos(final String line, final int termId, final EnumMap<TermFlag, BitSet> bits) {
        final int n = line.length();
        int i = line.indexOf(" po:");
        while (i >= 0) {
            final int start = i + 4;
            int end = start;
            while (end < n && line.charAt(end) != ' ' && line.charAt(end) != '\t') {
                end++;
            }
            final TermFlag flag = termFlagOf(line.substring(start, end));
            if (flag != null) {
                bits.computeIfAbsent(flag, k -> new BitSet(vocabSize)).set(termId);
            }
            i = line.indexOf(" po:", end);
        }
    }

    /**
     * Returns the index just past a dictionary line's headword: the first {@code '/'} or ASCII whitespace,
     * or the line length.
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
     * Maps a {@code po:} value to a {@link TermFlag}, returning {@code null} for any value that is not a
     * constant name, so unmodelled parts of speech are skipped.
     *
     * @param po the value after {@code po:}
     * @return matching flag, or {@code null}
     */
    private static TermFlag termFlagOf(final String po) {
        try {
            return TermFlag.valueOf(po);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * Flags terms of one {@link TermLexicon}. A flagger is offered each term in turn by the lexicon and may
     * set zero or more {@link TermFlag}s on it. All logic and all resources (stopword sets, person and place
     * lists, language rules) live inside the subclass: the lexicon owns the single decode loop and hands over
     * one already-decoded form per term, the subclass decides.
     * <p>
     * Subclasses implement {@link #flag(int, CharSequence)} and call {@link #set(TermFlag)} for each flag the
     * term should carry. Because flags accumulate as the method runs, a later test can depend on an earlier
     * one in the same body, e.g. {@code if capitalized then PROPN, then refine to PERS or PLACE}.
     * </p>
     * <p>
     * The {@code form} passed to {@link #flag(int, CharSequence)} is a buffer reused across terms and is valid
     * only for the duration of that call; a subclass must not retain it. One flagger instance is bound to one
     * lexicon build and is not reusable across builds.
     * </p>
     */
    public static abstract class TermFlagger {
     
        /** Flag sets being filled for the current build; shared with the owning lexicon. */
        private EnumMap<TermFlag, BitSet> bits;
     
        /** Vocabulary size, for sizing a flag's BitSet on first use. */
        private int vocabSize;
     
        /** Term id currently offered, target of {@link #set(TermFlag)}. */
        private int termId;
     
        /**
         * Flags one term. Called once per term by the lexicon. Implementations call {@link #set(TermFlag)} for
         * each flag the term should carry, zero or more times.
         *
         * @param termId dense term id in {@code [1, vocabSize())}
         * @param form   decoded term form; a reused buffer, valid only for this call
         */
        public abstract void flag(int termId, CharSequence form);
     
        /**
         * Sets one flag on the term currently being flagged.
         *
         * @param flag flag to set
         */
        protected final void set(final TermFlag flag) {
            bits.computeIfAbsent(flag, k -> new BitSet(vocabSize)).set(termId);
        }
     
        /**
         * Binds this flagger to a lexicon build. Called by {@link TermLexicon} before the term loop.
         *
         * @param bits      flag sets to fill
         * @param vocabSize vocabulary size
         */
        final void bind(final EnumMap<TermFlag, BitSet> bits, final int vocabSize) {
            this.bits = bits;
            this.vocabSize = vocabSize;
        }
     
        /**
         * Offers one term to {@link #flag(int, CharSequence)}, after setting it as the {@link #set(TermFlag)}
         * target. Called by {@link TermLexicon} once per term.
         *
         * @param termId dense term id
         * @param form   decoded term form (reused buffer)
         */
        final void offer(final int termId, final CharSequence form) {
            this.termId = termId;
            flag(termId, form);
        }
    }
}
