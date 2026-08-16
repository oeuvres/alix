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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
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
 * <b>Optional enrichment.</b> The enriching constructor accepts three independent sidecar streams and
 * caches the artifacts they yield. Every artifact is heap state, so the instance stays frozen and
 * concurrent-safe once it escapes; each source is read once and never closed (the caller keeps ownership).
 * </p>
 * <ul>
 *   <li><b>Hunspell dictionary</b> — from a {@code dic} plus an {@code aff}. The {@code dic} is the
 *       {@code <field>.dic} that {@link HunspellCompiler} emits, already pruned to this field's vocabulary
 *       and written in the index's apostrophe form; it is filtered again to the entries whose headword is
 *       actually indexed here and paired with the unchanged {@code aff}. Retrieve it with {@link #hunspell()};
 *       this class never spells or suggests on its own. The {@link Dictionary} is immutable and
 *       concurrent-safe; the non-thread-safe {@code Hunspell} runtime is the caller's to create per thread.</li>
 *   <li><b>Term-flag membership</b> — one {@link BitSet} of term ids per {@link TermFlag} actually set,
 *       drawn from two sources. Most flags come from the {@code dic}'s Hunspell morphological tags: each
 *       {@link TermFlag} declares the {@code key:value} tags that set it (for example {@code po:ADJ} or
 *       {@code ne:place}); {@code po:} tags carry primary-POS semantics, so only the first {@code po:} tag of
 *       a resolved term id may set a POS flag, while other tags remain cumulative. One flag instead comes
 *       from a plain stop-word list: {@link TermFlag#STOPWORD} is set for every listed word indexed in this
 *       field. Query with {@link #flags(int)}, {@link #bits(TermFlag)} or {@link #has(int, TermFlag)}; the
 *       query surface is uniform and does not expose which source set a flag.</li>
 * </ul>
 * <p>
 * The join between a dictionary headword (or a stop-word line) and an indexed term is a raw byte match
 * through {@link #id(String)}: no analysis or normalization runs here, so supply forms in exactly the shape
 * the analyzer produced or coverage silently drops. Dictionary headwords are parsed as {@link HunspellCompiler}
 * parses them, so multi-word headwords survive intact and producer and consumer agree on which lines match.
 * </p>
 *
 * @see TermRail
 * @see TermFlag
 * @see HunspellCompiler
 */
public final class TermLexicon {

    /**
     * Membership flags attachable to the term ids of a {@link TermLexicon}.
     * <p>
     * Most flags are harvested from the Hunspell morphological tags of the field's dictionary and declare
     * the {@code key:value} tags that set them — part of speech through {@code po:} (e.g. {@code po:ADJ}),
     * named-entity type through {@code ne:} (e.g. {@code ne:place}). These are independent membership bits,
     * except that {@code po:} forms a single ordered POS axis: only the first {@code po:} tag encountered for
     * one resolved term id may set a POS flag. A term may still carry several flags at once, for example one
     * POS flag and one named-entity flag. The tag-driven model extends by a local edit: add a constant with
     * its tags, or add a tag to an existing constant. Tags present in the dictionary but declared by no
     * constant (for example {@code po:ADV}, {@code ne:org}) are ignored.
     * </p>
     * <p>
     * A flag may instead be set by a mechanism other than the dictionary scan, in which case it declares no
     * tags. {@link #STOPWORD} is such a flag, set from an external word list; {@link #NULL} is the no-flag
     * sentinel, never set. Both declare no tags and so are never touched by the dictionary scan.
     * </p>
     */
    public static enum TermFlag {
        /**
         * No-flag sentinel. It declares no tags, so it is never set on any term, and it is the one value for
         * which {@link TermLexicon#bits(TermFlag)} returns {@code null} rather than a BitSet. It is a usable
         * "no axis selected" marker that, unlike a {@code null} reference, passes
         * {@code Objects.requireNonNull} and compares with {@code ==}. Placed first, ordinal 0, paralleling
         * the reserved term id 0 of {@link TermLexicon}.
         */
        NULL,
        /** Adjective; set by {@code po:ADJ}. */
        ADJ("po:ADJ"),
        /** Dictionary of auctors; set by {@code ne:auctor}. */
        AUCTOR("ne:auctor"),
        /** Common noun; set by {@code po:NOUN}. */
        NOUN("po:NOUN"),
        /** Named entity, place; set by {@code ne:place}. */
        PLACE("ne:place"),
        /** Proper noun; set by {@code po:PROPN}. */
        PROPN("po:PROPN"),
        /**
         * Stop word. Declares no tags: it is not harvested from the dictionary but set from the external
         * stop-word list passed to the enriching constructor (one word per line, UTF-8). Each listed word is
         * resolved through {@link TermLexicon#id(String)} and, when indexed in this field, its id gets the
         * flag; words absent from the field are dropped.
         */
        STOPWORD,
        /** Verb; set by {@code po:VERB}. */
        VERB("po:VERB");

        /** Hunspell morphological tags that set this flag; harvested by {@link TermLexicon}. */
        private final String[] tags;

        TermFlag(final String... tags) {
            this.tags = tags;
        }

        /**
         * Returns the {@code key:value} morphological tags that set this flag.
         *
         * @return tags, never null; empty for a flag not set by any dictionary tag
         */
        String[] tags() {
            return tags;
        }
    }

    /** Reverse index from a Hunspell morphological tag to the flags it sets; built from {@link TermFlag}. */
    private static final Map<String, List<TermFlag>> FLAGS_BY_TAG = flagsByTag();

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
     * Builds the lexicon for one field, optionally enriched with a field-restricted Hunspell dictionary and
     * the tag-driven {@link TermFlag} BitSets harvested from it. Equivalent to the five-argument constructor
     * with no stop-word list.
     *
     * @param reader snapshot reader
     * @param field  indexed field name
     * @param dic    field Hunspell {@code .dic}, or {@code null}
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
     * Builds the lexicon for one field with the full set of optional sidecars. The reader and all streams are
     * consulted only here and not retained; each stream is read once and not closed (the caller keeps
     * ownership). The three sources are independent, so any subset may be {@code null}.
     * <p>
     * Effect of the nullable sources:
     * </p>
     * <ul>
     *   <li>{@code dic == null}: no dictionary and no tag-driven flags; an {@code aff} alone is ignored.</li>
     *   <li>{@code dic != null}, {@code aff == null}: tag-driven flags harvested from the {@code dic}'s
     *       morphological fields; {@link #hunspell()} is {@code null}.</li>
     *   <li>{@code dic != null}, {@code aff != null}: flags harvested and {@link #hunspell()} returns the
     *       restricted dictionary (or {@code null} if no headword is indexed here).</li>
     *   <li>{@code stopwords != null}: each line is resolved through {@link #id(String)} and, when indexed in
     *       this field, its id gets {@link TermFlag#STOPWORD}. Independent of the dictionary; the list is
     *       headerless, so its first line is a word, not a count.</li>
     * </ul>
     * <p>
     * The {@code dic} is expected to be the field sidecar {@link HunspellCompiler} emits: already pruned to the
     * field and already apostrophe-folded, so no apostrophe logic runs here. A line's headword is located with
     * the same rule the compiler uses, so multi-word headwords are matched whole, not at their first space.
     * </p>
     *
     * @param reader    snapshot reader
     * @param field     indexed field name
     * @param dic       field Hunspell {@code .dic}, or {@code null}
     * @param aff       Hunspell {@code .aff}, or {@code null}
     * @param stopwords UTF-8 stop-word list, one word per line, or {@code null}
     * @throws IOException              on read or parse failure, or if 32-bit limits are exceeded
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if {@code reader} or {@code field} is null
     */
    public TermLexicon(final IndexReader reader, final String field,
            final InputStream dic, final InputStream aff, final InputStream stopwords) throws IOException {
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
        this.hunspell = (dic != null) ? harvestDictionary(dic, aff, bits) : null;
        if (stopwords != null) {
            harvestStopwords(stopwords, bits);
        }
        this.flagBits = bits;
    }

    /**
     * Returns the term ids carrying one flag.
     * <p>
     * The result is a defensive copy: the caller may read, iterate or mutate it without affecting the
     * lexicon. An empty BitSet is returned when a real flag was never set. {@link TermFlag#NULL} alone returns
     * {@code null} — the "no axis selected" marker, distinct from the empty BitSet an unset real flag yields.
     * For pivot queries, intersect this with a co-occurrence candidate set (e.g. {@code bits(TermFlag.PLACE)}
     * AND the cooc terms of a pivot), or subtract {@code bits(TermFlag.STOPWORD)} to drop function words.
     * </p>
     *
     * @param flag membership flag
     * @return a fresh {@link BitSet} of term ids; {@code null} for {@link TermFlag#NULL}, otherwise never null
     *         and empty when the flag was never set
     * @throws NullPointerException if {@code flag} is null
     */
    public BitSet bits(final TermFlag flag) {
        Objects.requireNonNull(flag, "flag");
        if (flag == TermFlag.NULL) {
            return null;
        }
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
     * @return term ids sorted with no duplicates; unknown terms omitted
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
     * Resolves the terms to their term ids, restricted to this lexicon's field. Terms the
     * lexicon does not know are silently dropped.
     *
     * @param terms terms to resolve
     * @return term ids sorted with no duplicates; unknown terms omitted
     */
    public int[] termIds(final String[] terms) {
        final IntList ids = new IntList();
        for (String t : terms) {
            t = t.strip();
            if (t.isBlank()) continue;
            final int termId = id(t);
            if (termId >= 0) {
                ids.push(termId);
            }
        }
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
     * Builds the reverse index from each {@link TermFlag}'s declared {@code key:value} tags to the flag, so a
     * dictionary tag can be dispatched to the flags it sets in one map lookup. A tag may set more than one
     * flag. Flags declaring no tags (for example {@link TermFlag#STOPWORD}, {@link TermFlag#NULL}) contribute
     * nothing here and are never reached by the dictionary scan.
     *
     * @return tag-to-flags index
     */
    private static Map<String, List<TermFlag>> flagsByTag() {
        final Map<String, List<TermFlag>> index = new HashMap<>();
        for (final TermFlag flag : TermFlag.values()) {
            for (final String tag : flag.tags()) {
                index.computeIfAbsent(tag, k -> new ArrayList<>()).add(flag);
            }
        }
        return index;
    }

    /**
     * Scans the field {@code dic}, harvesting the tag-driven {@link TermFlag} BitSets and, when an {@code aff}
     * is supplied, building the field-restricted Hunspell {@link Dictionary} from the lines whose headword is
     * indexed here. The count header on line 1 is discarded (the {@code dic} carries one); the join with the
     * index is a raw byte match via {@link #id(BytesRef)} on the headword.
     *
     * @param dic  field Hunspell {@code .dic}
     * @param aff  Hunspell {@code .aff}, or {@code null} to harvest flags without building a dictionary
     * @param bits flag sets being filled
     * @return the restricted dictionary, or {@code null} when {@code aff} is null or no headword matched
     * @throws IOException on read or parse failure
     */
    private Dictionary harvestDictionary(final InputStream dic, final InputStream aff,
            final EnumMap<TermFlag, BitSet> bits) throws IOException {
        final StringBuilder body = (aff != null) ? new StringBuilder(1 << 20) : null;
        final BytesRefBuilder probe = new BytesRefBuilder();
        final BitSet posClaimed = new BitSet(vocabSize);
        final BufferedReader in = new BufferedReader(new InputStreamReader(dic, StandardCharsets.UTF_8));
        int kept = 0;
        in.readLine();                         // discard the dic count header (line 1)
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
                continue;                      // headword not indexed in this field
            }
            kept++;
            if (body != null) {
                body.append(line).append('\n');
            }
            harvestFlags(line, cut, tid, bits, posClaimed);
        }
        if (aff == null || kept == 0) {
            return null;
        }
        final ByteArrayInputStream filtered = new ByteArrayInputStream(
            (kept + "\n" + body).getBytes(StandardCharsets.UTF_8));
        final Directory tmp = new ByteBuffersDirectory();
        try {
            return new Dictionary(tmp, "hunspell", aff, filtered);
        } catch (final ParseException e) {
            throw new IOException("Hunspell dictionary parse failed for field: " + field, e);
        } finally {
            tmp.close();
        }
    }

    /**
     * Sets every flag declared by one kept dictionary line, scanning its Hunspell morphological tags from the
     * headword end. Whitespace-delimited non-POS tags are cumulative. POS is a single axis: only the first
     * {@code po:} tag seen for one resolved term id may set a POS flag, and once that slot is claimed later
     * {@code po:} tags for the same id — even on another line — are ignored. Tags matching no flag (affix
     * codes, {@code fr:}, unmodelled tags) are ignored, but an unmodelled primary {@code po:} still claims the
     * term's POS slot.
     *
     * @param line       raw dictionary line
     * @param from       index where the morphological fields begin (the headword end)
     * @param termId     resolved term id for the line's headword
     * @param bits       flag sets being filled
     * @param posClaimed term ids whose primary POS tag has already been consumed
     */
    private void harvestFlags(final String line, final int from, final int termId,
            final EnumMap<TermFlag, BitSet> bits, final BitSet posClaimed) {
        final int n = line.length();
        int i = from;
        while (i < n) {
            while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            final int start = i;
            while (i < n && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
                i++;
            }
            if (i <= start) {
                continue;
            }

            final String tag = line.substring(start, i);
            if (tag.startsWith("po:")) {
                if (posClaimed.get(termId)) {
                    continue;
                }
                posClaimed.set(termId);
            }

            final List<TermFlag> flags = FLAGS_BY_TAG.get(tag);
            if (flags != null) {
                for (final TermFlag flag : flags) {
                    bits.computeIfAbsent(flag, k -> new BitSet(vocabSize)).set(termId);
                }
            }
        }
    }

    /**
     * Sets {@link TermFlag#STOPWORD} for every word of a UTF-8 stop-word list that is indexed in this field.
     * The list is headerless — one word per line, read from line 1 — each line stripped; blank lines and
     * words absent from the field are dropped. The {@link BitSet} is allocated only if at least one word
     * matches, so an empty or wholly-unknown list leaves the flag unset (and {@link #bits(TermFlag)} empty),
     * consistent with tag-driven flags.
     *
     * @param stopwords UTF-8 stop-word list, one word per line
     * @param bits      flag sets being filled
     * @throws IOException on read failure
     */
    private void harvestStopwords(final InputStream stopwords,
            final EnumMap<TermFlag, BitSet> bits) throws IOException {
        final BytesRefBuilder probe = new BytesRefBuilder();
        final BufferedReader in = new BufferedReader(new InputStreamReader(stopwords, StandardCharsets.UTF_8));
        BitSet bs = null;
        String line;
        while ((line = in.readLine()) != null) {
            final String word = line.strip();
            if (word.isEmpty()) {
                continue;
            }
            probe.copyChars(word);
            final int tid = id(probe.get());
            if (tid < 1) {
                continue;                      // stop word not indexed in this field
            }
            if (bs == null) {
                bs = bits.computeIfAbsent(TermFlag.STOPWORD, k -> new BitSet(vocabSize));
            }
            bs.set(tid);
        }
    }

    /**
     * Returns the index just past a dictionary line's headword, matching {@link HunspellCompiler} so the two
     * agree on which lines join. A headword runs to the first affix-flag delimiter {@code '/'} or the first
     * whitespace that begins a morphological field — whitespace followed by a two-letter lowercase tag and a
     * colon, as in {@code po:} — whichever comes first, or the line length when neither occurs. Whitespace
     * inside a multi-word headword (a space not followed by such a tag) is retained, so {@code von Albertini}
     * yields the whole name rather than {@code von}.
     *
     * @param line raw dictionary line
     * @return headword length in chars
     */
    private static int headwordEnd(final String line) {
        final int n = line.length();
        for (int i = 0; i < n; i++) {
            final char c = line.charAt(i);
            if (c == '/') {
                return i;
            }
            if ((c == ' ' || c == '\t') && morphFieldAt(line, i + 1)) {
                return i;
            }
        }
        return n;
    }

    /**
     * Tells whether a character is an ASCII lowercase letter.
     *
     * @param c character to test
     * @return true iff {@code c} is in {@code [a-z]}
     */
    private static boolean isAsciiLower(final char c) {
        return c >= 'a' && c <= 'z';
    }

    /**
     * Tells whether a Hunspell morphological field begins at an index: two lowercase ASCII letters followed by
     * a colon, as in {@code po:} or {@code ne:}. The colon is what separates such a tag from an ordinary token
     * of a multi-word headword.
     *
     * @param line line to inspect
     * @param j    index where the field marker would start
     * @return true iff {@code line} has {@code [a-z][a-z]:} at {@code j}
     */
    private static boolean morphFieldAt(final String line, final int j) {
        return j + 2 < line.length()
            && isAsciiLower(line.charAt(j))
            && isAsciiLower(line.charAt(j + 1))
            && line.charAt(j + 2) == ':';
    }
}
