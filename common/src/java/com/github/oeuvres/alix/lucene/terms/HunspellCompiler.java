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
import org.apache.lucene.util.BytesRefBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Build-time producer of a field-restricted Hunspell dictionary, written as the sidecar pair
 * {@code <field>.dic} / {@code <field>.aff} inside the Lucene index {@link Directory}.
 * <p>
 * Each input {@code dic} is streamed and a line is kept iff its headword (the text before the first
 * {@code '/'} or ASCII space or tab) is an indexed term of {@code field}. Kept lines from every input dic
 * are concatenated unchanged — {@code po:} tags included — under a single recomputed count header. The
 * {@code aff} is copied verbatim, since affix classes are global to the field and must stay intact for the
 * kept entries to expand. The result is a Hunspell resource whose word list is exactly the field's
 * vocabulary: a speller built from it can only ever suggest forms that, once lemmatised, retrieve in this
 * field, with no query-time post-filter.
 * </p>
 * <p>
 * This is a post-commit pass over a frozen reader, not a read-path operation. The canonical dictionaries and
 * the corpus configuration that names them are ingest concerns; they must not reach the query layer. The
 * consumer ({@link TermLexicon}) only ever sees the small {@code <field>.dic} this class emits, from which it
 * harvests part-of-speech flags and builds its runtime {@code Dictionary}. Flag harvesting and BitSet
 * construction are the consumer's job; this class produces text and nothing else — it builds no
 * {@code Dictionary} and resolves no term ids beyond a membership test.
 * </p>
 * <p>
 * The membership test is {@link TermsEnum#seekExact}, a raw unsigned-byte match against the field's terms.
 * This is the same comparison {@link TermLexicon#id(org.apache.lucene.util.BytesRef)} performs, so the lines
 * this compiler keeps are exactly the lines the consumer would resolve. If the two ever used different byte
 * forms (re-normalisation on one side only), the pruned dic and the term table would disagree silently; using
 * {@code seekExact} on the same field forecloses that.
 * </p>
 * <p>
 * Duplicate headwords across input dics are not removed: that is a Hunspell-semantics decision (a repeated
 * headword merges or layers affix classes), left to the {@code Dictionary} parser the consumer runs.
 * </p>
 * <p>
 * Input streams are read once and not closed; the caller retains ownership. Output semantics are idempotent:
 * any prior sidecar of the same name is overwritten, and a field with zero coverage removes the pair and
 * writes nothing, so the on-disk state always reflects the latest run.
 * </p>
 *
 * @see TermLexicon
 */
public final class HunspellCompiler {

    private HunspellCompiler() {
    }

    /**
     * Prunes one or more canonical Hunspell dictionaries to the indexed terms of {@code field} and writes the
     * field sidecars {@code <field>.dic} and {@code <field>.aff} into {@code out}, overwriting any prior pair.
     * If no headword from any input dic is indexed in the field, both sidecars are removed and none written.
     * The reader and all streams are consulted only here; the streams are read once and not closed.
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
                if (!te.seekExact(probe.get())) {
                    continue;                  // headword not indexed in this field
                }
                body.append(line).append('\n');
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
     * Command-line entry point for recompiling a field's Hunspell sidecars against an already-committed index,
     * without reindexing — the recompile path for an edited dictionary. The index directory receives the new
     * {@code <field>.dic} / {@code <field>.aff}.
     *
     * @param args {@code <indexDir> <field> <aff> <dic> [<dic>...]}
     * @throws IOException on directory, read, or write failure
     */
    public static void main(final String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: HunspellCompiler <indexDir> <field> <aff> <dic> [<dic>...]");
            System.exit(2);
        }
        final Path indexDir = Path.of(args[0]);
        final String field = args[1];
        final Path affPath = Path.of(args[2]);
        final int dicCount = args.length - 3;
        final InputStream[] dics = new InputStream[dicCount];
        try (Directory dir = FSDirectory.open(indexDir);
                IndexReader reader = DirectoryReader.open(dir);
                InputStream aff = Files.newInputStream(affPath)) {
            try {
                for (int i = 0; i < dicCount; i++) {
                    dics[i] = Files.newInputStream(Path.of(args[3 + i]));
                }
                final int kept = compile(reader, field, aff, dir, dics);
                System.out.println(field + ": " + kept + " entries kept");
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
     * Returns the index just past a dictionary line's headword: the first {@code '/'} or ASCII space or tab, or
     * the line length when none occurs.
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
}
