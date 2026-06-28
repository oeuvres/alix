package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Build-time producer of a field-restricted Hunspell dictionary, written as the sidecar pair
 * {@code <field>.dic} / {@code <field>.aff} inside the Lucene index {@link Directory}, plus a reviewer's
 * listing of the field terms no dictionary covers.
 * <p>
 * For {@link #compile}, each input {@code dic} is streamed and a line is kept iff its headword is an indexed
 * term of {@code field}. The headword runs to the first affix-flag delimiter {@code '/'} or the first
 * whitespace that begins a Hunspell morphological field (a two-letter lowercase tag and a colon, such as
 * {@code po:}), so multi-word entries like {@code par rapport à} survive intact rather than being cut at their
 * first space. Apostrophe variants are folded to the ASCII apostrophe the analyzer indexes, which absorbs both
 * an index/dictionary mismatch and an internally inconsistent dictionary. Each written line carries the term's
 * field frequency in a {@code fr:} field, replacing any {@code fr:} the source dictionary held, so the compiled
 * resource is annotated with this corpus's counts. Kept lines from every input dic are concatenated — {@code
 * po:} tags included — under a single recomputed count header, and the {@code aff} is copied verbatim, since
 * affix classes are global to the field. The result is a Hunspell resource whose word list is exactly the
 * field's vocabulary, written in the index's apostrophe form so the consumer's harvest and lookups need no
 * apostrophe logic.
 * </p>
 * <p>
 * For {@link #unknowns}, the same streaming join is read the other way: the field terms that match no
 * dictionary headword are written, most frequent first, as a review aid for deciding what to add to a local
 * dictionary. That method writes no files.
 * </p>
 * <p>
 * This is a post-commit tool over a frozen reader, not a read-path operation. The canonical dictionaries and the
 * corpus configuration that names them are ingest concerns; they must not reach the query layer. The consumer
 * ({@link TermLexicon}) only ever sees the small {@code <field>.dic} this class emits, from which it harvests
 * part-of-speech flags and builds its runtime {@code Dictionary}; this class produces text and resolves no term
 * ids beyond a membership test.
 * </p>
 * <p>
 * The membership test is {@link TermsEnum#seekExact}, a raw unsigned-byte match against the field's terms, the
 * same comparison {@link TermLexicon#id(org.apache.lucene.util.BytesRef)} performs, so producer and consumer
 * agree on which lines survive. Duplicate headwords across input dics are not removed: that is a Hunspell
 * decision, left to the {@code Dictionary} parser the consumer runs. Input streams are read once and not closed.
 * </p>
 * <p>
 * As a command-line tool it has two modes: {@code compile} writes the sidecars, {@code unknowns} writes the
 * out-of-vocabulary listing to standard output.
 * </p>
 *
 * @see TermLexicon
 */
public final class HunspellCompiler {

    /** ASCII apostrophe the analyzer indexes; the fold target for apostrophe variants. */
    private static final char APOS = '\'';

    private HunspellCompiler() {
    }

    /**
     * Prunes one or more canonical Hunspell dictionaries to the indexed terms of {@code field} and writes the
     * field sidecars {@code <field>.dic} and {@code <field>.aff} into {@code out}, overwriting any prior pair.
     * Each written line carries the term's field frequency in a {@code fr:} field, replacing any {@code fr:} the
     * source held. If no headword from any input dic is indexed in the field, both sidecars are removed and none
     * written. The reader and all streams are consulted only here; the streams are read once and not closed.
     *
     * @param reader snapshot reader defining the field's term universe
     * @param field  indexed field name; also the sidecar base name
     * @param aff    canonical Hunspell {@code .aff}, copied verbatim to {@code <field>.aff}
     * @param out    index directory to receive the sidecars
     * @param dics   one or more canonical {@code .dic} streams; null elements are skipped
     * @return the number of dictionary lines kept (the field's dictionary coverage)
     * @throws IOException              on read, parse, or directory failure
     * @throws IllegalArgumentException if the field has no terms, or its name collides with Lucene's reserved
     *                                  file-name patterns
     * @throws NullPointerException     if {@code reader}, {@code field}, {@code aff}, {@code out} or
     *                                  {@code dics} is null
     */
    public static int compile(final IndexReader reader, final String field,
            final InputStream aff, final Directory out, final InputStream... dics) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(aff, "aff");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(dics, "dics");
        checkFieldName(field);

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }
        final TermsEnum te = terms.iterator();
        final BytesRefBuilder probe = new BytesRefBuilder();
        final StringBuilder body = new StringBuilder(1 << 20);
        int kept = 0;

        for (final InputStream dic : dics) {
            if (dic == null) {
                continue;
            }
            final BufferedReader in = new BufferedReader(new InputStreamReader(dic, StandardCharsets.UTF_8));
            in.readLine();                     // discard the per-dic approximate count header (line 1)
            String raw;
            while ((raw = in.readLine()) != null) {
                if (raw.isEmpty()) {
                    continue;
                }
                final String line = canonApos(raw);
                final int cut = headwordEnd(line);
                if (cut == 0) {
                    continue;
                }
                probe.copyChars(line, 0, cut);
                if (!te.seekExact(probe.get())) {
                    continue;                  // headword not indexed in this field
                }
                long freq = te.totalTermFreq();
                if (freq < 0) {
                    freq = te.docFreq();
                }
                body.append(stripFreq(line)).append(" fr:").append(freq).append('\n');
                kept++;
            }
        }

        final String dicName = field + ".dic";
        final String affName = field + ".aff";
        if (kept == 0) {
            delete(out, dicName);
            delete(out, affName);
            return 0;
        }
        write(out, dicName, (kept + "\n" + body).getBytes(StandardCharsets.UTF_8));
        write(out, affName, aff.readAllBytes());
        return kept;
    }

    /**
     * Writes the field terms that no input dictionary covers, most frequent first, as {@code term\tfrequency}
     * lines. The listing is the complement of {@link #compile}: a term is out of vocabulary when its exact
     * indexed form is the headword of no entry in any {@code dic}, headwords being extracted and apostrophe-
     * folded exactly as in {@link #compile} so the two agree on coverage. It is a review aid — the high-
     * frequency head of the list is where adding entries to a local dictionary buys the most coverage — and
     * writes no files. The reader and streams are read once and not closed.
     *
     * @param reader snapshot reader defining the field's term universe
     * @param field  indexed field name
     * @param out    sink for the {@code term\tfrequency} lines, newline-separated
     * @param dics   one or more canonical {@code .dic} streams; null elements are skipped
     * @return the number of out-of-vocabulary terms written
     * @throws IOException              on read or append failure
     * @throws IllegalArgumentException if the field has no terms
     * @throws NullPointerException     if {@code reader}, {@code field}, {@code out} or {@code dics} is null
     */
    public static int unknowns(final IndexReader reader, final String field,
            final Appendable out, final InputStream... dics) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(dics, "dics");

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }

        final TermsEnum te = terms.iterator();
        final BytesRefBuilder probe = new BytesRefBuilder();
        final Set<String> matched = new HashSet<>();
        for (final InputStream dic : dics) {
            if (dic == null) {
                continue;
            }
            final BufferedReader in = new BufferedReader(new InputStreamReader(dic, StandardCharsets.UTF_8));
            in.readLine();
            String raw;
            while ((raw = in.readLine()) != null) {
                if (raw.isEmpty()) {
                    continue;
                }
                final String line = canonApos(raw);
                final int cut = headwordEnd(line);
                if (cut == 0) {
                    continue;
                }
                probe.copyChars(line, 0, cut);
                if (te.seekExact(probe.get())) {
                    matched.add(line.substring(0, cut));
                }
            }
        }

        final TermsEnum scan = terms.iterator();
        final List<Unknown> unknowns = new ArrayList<>();
        BytesRef term;
        while ((term = scan.next()) != null) {
            final String form = term.utf8ToString();
            if (matched.contains(form)) {
                continue;
            }
            long freq = scan.totalTermFreq();
            if (freq < 0) {
                freq = scan.docFreq();
            }
            unknowns.add(new Unknown(form, freq));
        }
        unknowns.sort(Comparator.comparingLong(Unknown::freq).reversed().thenComparing(Unknown::term));
        for (final Unknown o : unknowns) {
            out.append(o.term()).append('\t').append(Long.toString(o.freq())).append('\n');
        }
        return unknowns.size();
    }

    /**
     * Command-line entry point with two modes over an already-committed index. {@code compile} writes the field
     * Hunspell sidecars; {@code unknowns} writes the out-of-vocabulary listing to standard output. Status lines go to
     * standard error so the {@code unknowns} data stream stays clean for redirection.
     *
     * @param args {@code compile <indexDir> <field> <aff> <dic> [<dic>...]} or
     *             {@code unknowns <indexDir> <field> <dic> [<dic>...]}
     * @throws IOException on directory, read, or write failure
     */
    public static void main(final String[] args) throws IOException {
        final String mode = (args.length > 0) ? args[0] : "";
        if (mode.equals("compile") && args.length >= 5) {
            runCompile(args);
        } else if (mode.equals("unknowns") && args.length >= 4) {
            runUnknowns(args);
        } else {
            System.err.println("usage:");
            System.err.println("  HunspellCompiler compile <indexDir> <field> <aff> <dic> [<dic>...]");
            System.err.println("  HunspellCompiler unknowns <indexDir> <field> <dic> [<dic>...]");
            System.exit(2);
        }
    }

    /**
     * Folds apostrophe variants to the ASCII apostrophe {@link #APOS} the analyzer indexes. French dictionaries
     * commonly use the typographic right single quotation mark and may be internally inconsistent; folding every
     * variant lets a headword match its indexed form and keeps the written dictionary consistent with the index.
     * Assumes the field's analyzer normalizes apostrophes to ASCII {@code '}; change {@link #APOS} otherwise.
     *
     * @param line raw dictionary line
     * @return the line with apostrophe variants folded to {@link #APOS}
     */
    private static String canonApos(final String line) {
        return line
            .replace('\u2019', APOS)   // RIGHT SINGLE QUOTATION MARK
            .replace('\u02BC', APOS)   // MODIFIER LETTER APOSTROPHE
            .replace('\u2018', APOS);  // LEFT SINGLE QUOTATION MARK
    }

    /**
     * Rejects a field name whose sidecars would collide with Lucene's own file-name patterns, where
     * {@code IndexFileDeleter} or codec tooling could silently remove or shadow them.
     *
     * @param field field name to validate
     * @throws IllegalArgumentException if the name is empty or begins with {@code '_'}, {@code "segments"} or
     *                                  {@code "pending"}
     */
    private static void checkFieldName(final String field) {
        if (field.isEmpty()) {
            throw new IllegalArgumentException("Empty field name");
        }
        if (field.charAt(0) == '_' || field.startsWith("segments") || field.startsWith("pending")) {
            throw new IllegalArgumentException(
                "Field name collides with Lucene reserved file patterns, unsafe as an index sidecar: " + field);
        }
    }

    /**
     * Removes a named file from a directory if it is present, leaving the directory unchanged otherwise.
     *
     * @param dir  directory to clear
     * @param name file name to remove
     * @throws IOException on directory failure
     */
    private static void delete(final Directory dir, final String name) throws IOException {
        for (final String existing : dir.listAll()) {
            if (existing.equals(name)) {
                dir.deleteFile(name);
                return;
            }
        }
    }

    /**
     * Tells whether a named morphological field begins at an index: the tag followed by a colon, as in
     * {@code fr:}.
     *
     * @param line line to inspect
     * @param j    index where the tag would start
     * @param tag  field name without the colon
     * @return true iff {@code line} has {@code tag:} at {@code j}
     */
    private static boolean fieldAt(final String line, final int j, final String tag) {
        final int end = j + tag.length();
        return end < line.length()
            && line.regionMatches(j, tag, 0, tag.length())
            && line.charAt(end) == ':';
    }

    /**
     * Returns the index of the whitespace that introduces the {@code fr:} field, or {@code -1} when the line has
     * none.
     *
     * @param line dictionary line to scan
     * @return index of the whitespace before {@code fr:}, or {@code -1}
     */
    private static int freqStart(final String line) {
        final int n = line.length();
        for (int i = 0; i < n; i++) {
            final char c = line.charAt(i);
            if ((c == ' ' || c == '\t') && fieldAt(line, i + 1, "fr")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the index just past a dictionary line's headword. A headword runs to the first affix-flag
     * delimiter {@code '/'} or the first whitespace that begins a morphological field — whitespace followed by a
     * two-letter lowercase tag and a colon, as in {@code po:} — whichever comes first, or the line length when
     * neither occurs. Whitespace inside a multi-word headword (a space not followed by such a tag) is retained,
     * so entries like {@code par rapport à\tpo:ADP} yield the whole expression rather than its first token.
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
     * Tells whether a Hunspell morphological field begins at an index: two lowercase ASCII letters followed by a
     * colon, as in {@code po:} or {@code st:}. The colon is what separates such a tag from an ordinary token of
     * a multi-word headword.
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

    /**
     * Opens a {@code .aff} or {@code .dic} location, accepting either a {@code classpath:} resource or a file
     * system path. A location beginning with {@code classpath:} is resolved against the class loader — the
     * remainder is a root-relative resource name, a leading slash tolerated — and anything else is a file path.
     * The returned stream is the caller's to close.
     *
     * @param location {@code classpath:<resource-name>} or a file system path
     * @return an open input stream over the resource
     * @throws IOException if a classpath resource is named but absent, or a file cannot be opened
     */
    private static InputStream open(final String location) throws IOException {
        final String prefix = "classpath:";
        if (location.startsWith(prefix)) {
            String name = location.substring(prefix.length());
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            final InputStream in = HunspellCompiler.class.getClassLoader().getResourceAsStream(name);
            if (in == null) {
                throw new IOException("Classpath resource not found: " + name);
            }
            return in;
        }
        return Files.newInputStream(Path.of(location));
    }

    /**
     * Runs the {@code compile} CLI mode: opens the index and streams, writes the field sidecars, and reports the
     * kept count on standard error. Opened streams are closed here.
     *
     * @param args full argument vector, {@code args[0]} being the mode token
     * @throws IOException on directory, read, or write failure
     */
    private static void runCompile(final String[] args) throws IOException {
        final Path indexDir = Path.of(args[1]);
        final String field = args[2];
        final int dicCount = args.length - 4;
        final InputStream[] dics = new InputStream[dicCount];
        try (Directory dir = FSDirectory.open(indexDir);
                IndexReader reader = DirectoryReader.open(dir);
                InputStream aff = open(args[3])) {
            try {
                for (int i = 0; i < dicCount; i++) {
                    dics[i] = open(args[4 + i]);
                }
                final int kept = compile(reader, field, aff, dir, dics);
                System.err.println(field + ": " + kept + " entries kept");
            } finally {
                for (final InputStream dic : dics) {
                    if (dic != null) {
                        dic.close();
                    }
                }
            }
        }
    }

    /**
     * Runs the {@code unknowns} CLI mode: opens the index and streams, writes the out-of-vocabulary listing to
     * standard output as UTF-8, and reports the count on standard error. Opened streams are closed here;
     * standard output is flushed but not closed.
     *
     * @param args full argument vector, {@code args[0]} being the mode token
     * @throws IOException on directory, read, or write failure
     */
    private static void runUnknowns(final String[] args) throws IOException {
        final Path indexDir = Path.of(args[1]);
        final String field = args[2];
        final int dicCount = args.length - 3;
        final InputStream[] dics = new InputStream[dicCount];
        try (Directory dir = FSDirectory.open(indexDir);
                IndexReader reader = DirectoryReader.open(dir)) {
            final Writer w = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
            try {
                for (int i = 0; i < dicCount; i++) {
                    dics[i] = open(args[3 + i]);
                }
                final int unknowns = unknowns(reader, field, w, dics);
                w.flush();
                System.err.println(field + ": " + unknowns + " terms out of vocabulary");
            } finally {
                for (final InputStream dic : dics) {
                    if (dic != null) {
                        dic.close();
                    }
                }
            }
        }
    }

    /**
     * Removes the {@code fr:} field, and the single whitespace that introduces it, from a dictionary line,
     * leaving the line unchanged when it carries none. Only the first {@code fr:} field is removed.
     *
     * @param line apostrophe-folded dictionary line
     * @return the line without its {@code fr:} field
     */
    private static String stripFreq(final String line) {
        final int start = freqStart(line);
        if (start < 0) {
            return line;
        }
        final int n = line.length();
        int end = start + 1;
        while (end < n && line.charAt(end) != ' ' && line.charAt(end) != '\t') {
            end++;
        }
        return line.substring(0, start) + line.substring(end);
    }

    /**
     * Writes a byte payload to a named file in a directory, overwriting any prior file of that name.
     *
     * @param dir   directory to write into
     * @param name  file name
     * @param bytes payload
     * @throws IOException on directory failure
     */
    private static void write(final Directory dir, final String name, final byte[] bytes) throws IOException {
        delete(dir, name);
        try (IndexOutput out = dir.createOutput(name, IOContext.DEFAULT)) {
            out.writeBytes(bytes, bytes.length);
        }
    }

    /**
     * One out-of-vocabulary field term and its corpus frequency, the unit of the review listing.
     *
     * @param term indexed term form absent from every input dictionary
     * @param freq total corpus frequency, or document frequency when term frequencies are unavailable
     */
    private record Unknown(String term, long freq) {
    }
}
