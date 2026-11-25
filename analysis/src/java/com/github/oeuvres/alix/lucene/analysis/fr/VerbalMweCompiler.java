package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.hunspell.AffixedWord;
import org.apache.lucene.analysis.hunspell.DictEntries;
import org.apache.lucene.analysis.hunspell.DictEntry;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.WordFormGenerator;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.MweLexicon;

/**
 * Compiles French MWE declarations from normalized surface tokens, optionally
 * expanding the initial verbal head using Lucene Hunspell on demand.
 *
 * <p>Only declarations tagged VERB are expanded. An initial sequence of
 * clitics may precede the head, but no other component is inflected. Explicit
 * declarations override generated keys, even across successive dictionary
 * loads; generated keys never overwrite any previously registered key.
 * Matching keys are case-insensitive, as in {@link MweLexicon}. Only requested
 * verbal paradigms are generated and cached; the cache stores raw forms so
 * later declarations can use the analyzer's current normalization resources.</p>
 *
 * <p>Use one compiler for all declarations of a mutable lexicon, before
 * freezing it. This class is not thread-safe.</p>
 */
final class VerbalMweCompiler
{
    /** Clitics allowed before the initial verbal head, after clitic splitting. */
    private static final Set<String> CLITICS = Set.of(
        "me", "te", "se", "nous", "vous", "le", "la", "les", "l'", "lui", "leur", "en", "y"
    );

    /** Surface-only entry analyzer shared by explicit and generated keys. */
    private final Analyzer analyzer;

    /** Immutable Hunspell dictionary shared between compiler instances. */
    private final Dictionary dictionary;

    /** Lucene generator, confined to this compiler. */
    private final WordFormGenerator generator;

    /** Raw verbal paradigms generated on demand, including empty results. */
    private final Map<String, List<String>> inflections = new HashMap<>();

    /** All registered keys, including explicit declarations and generated variants. */
    private final Set<List<String>> registered = new HashSet<>();

    /** Mutable target lexicon. */
    private final MweLexicon lexicon;

    /**
     * Creates a compiler for a new mutable MWE lexicon.
     *
     * @param analyzer normalized surface-form analyzer
     * @param dictionary immutable French Hunspell dictionary
     */
    VerbalMweCompiler(final Analyzer analyzer, final Dictionary dictionary)
    {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.generator = new WordFormGenerator(dictionary);
        this.lexicon = new MweLexicon(16000);
    }

    /**
     * Registers an explicit MWE and, for VERB declarations, its verbal variants.
     * The first non-clitic component must be a known verbal lemma. Other
     * components, including any subsequent verbs, are never expanded.
     *
     * @param expression source expression
     * @param pos declared grammatical category, or empty for exact matching
     * @param canonical canonical output
     * @return true when the explicit expression contains at least two tokens
     * @throws UncheckedIOException if token analysis fails
     */
    public boolean addExpression(final CharSequence expression,
        final CharSequence pos, final CharSequence canonical)
    {
        final StringBuilder source = new StringBuilder(expression);
        Char.trim(source);
        final StringBuilder output = new StringBuilder(canonical);
        Char.trim(output);
        if (source.length() == 0 || output.length() == 0) {
            return false;
        }
        Char.translate(output, "\u2019\u2018\u02BC\u2010\u2011\u00AD", "'''---");

        final List<String> forms = analyze(source);
        if (forms.size() < 2) {
            return false;
        }
        // Explicit declarations always win, including over earlier generated keys.
        lexicon.addExpression(forms, output);
        registered.add(key(forms));
        if (!"VERB".contentEquals(pos)) {
            return true;
        }

        int head = 0;
        while (head < forms.size() && CLITICS.contains(lower(forms.get(head)))) {
            head++;
        }
        if (head == forms.size()) {
            return true;
        }
        final String lemma = lower(forms.get(head));
        final List<String> variants = inflections.computeIfAbsent(lemma, this::generateForms);
        final List<String> candidate = new ArrayList<>(forms);
        for (String variant : variants) {
            // Apply the same normalization as to the original components.
            final List<String> normalized = analyze(variant);
            if (normalized.size() != 1) {
                continue;
            }
            candidate.set(head, normalized.get(0));
            if (registered.add(key(candidate))) {
                lexicon.addExpression(candidate, output);
            }
        }
        return true;
    }

    /**
     * Returns the mutable target lexicon.
     *
     * @return the compiled lexicon
     */
    public MweLexicon lexicon()
    {
        return lexicon;
    }

    /**
     * Loads a classpath MWE CSV resource (form, POS, canonical form).
     *
     * @param anchor class used to locate the resource
     * @param resourcePath classpath path
     * @throws UncheckedIOException on read or analysis failure
     */
    public void loadExpressions(final Class<?> anchor, final String resourcePath)
    {
        try (CSVReader csv = new CSVReader(anchor, resourcePath)) {
            loadExpressions(csv);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Loads a corpus-specific MWE CSV file (form, POS, canonical form).
     *
     * @param file dictionary file
     * @throws UncheckedIOException on read or analysis failure
     */
    public void loadExpressions(final Path file)
    {
        try (CSVReader csv = new CSVReader(file)) {
            loadExpressions(csv);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Analyzes a declaration or a generated component without reading lemmas.
     *
     * @param expression text to analyze
     * @return nonempty normalized tokens
     * @throws UncheckedIOException if token-stream consumption fails
     */
    private List<String> analyze(final CharSequence expression)
    {
        final List<String> forms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("mwe", expression.toString())) {
            final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                if (term.length() != 0) {
                    forms.add(term.toString());
                }
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return forms;
    }

    /**
     * Generates the distinct inflections of a requested verbal lemma.
     * Dictionary lookup is exact; select verbal entries before generation
     * because the flag-specific generation overload does not retain POS data.
     *
     * @param lemma normalized lowercase verbal head
     * @return immutable distinct inflected forms, or an empty list
     */
    private List<String> generateForms(final String lemma)
    {
        final DictEntries entries = dictionary.lookupEntries(lemma);
        if (entries == null) {
            return List.of();
        }
        final Set<String> forms = new LinkedHashSet<>();
        for (DictEntry entry : entries) {
            if (!entry.getMorphologicalValues("po:").contains("VERB")) {
                continue;
            }
            for (AffixedWord form : generator.getAllWordForms(
                    lemma, entry.getFlags(), () -> {})) {
                forms.add(form.getWord());
            }
        }
        return List.copyOf(forms);
    }

    /**
     * Copies a case-insensitive key, independent of mutable candidate lists.
     *
     * @param forms component tokens
     * @return immutable normalized key
     */
    private static List<String> key(final List<String> forms)
    {
        return forms.stream().map(VerbalMweCompiler::lower).toList();
    }

    /**
     * Loads CSV rows in declaration order, skipping the header and comments.
     *
     * @param csv open CSV reader
     * @throws IOException if the reader fails
     */
    private void loadExpressions(final CSVReader csv) throws IOException
    {
        csv.cellMax(3);
        if (!csv.readRow()) {
            return;
        }
        while (csv.readRow()) {
            if (csv.getCellCount() < 3) {
                continue;
            }
            final String expression = csv.getCell(0).toString().strip();
            if (expression.isEmpty() || expression.startsWith("#")) {
                continue;
            }
            final String pos = csv.getCell(1).toString().strip();
            final String canonical = csv.getCell(2).toString();
            try {
                addExpression(expression, pos, canonical.isBlank() ? expression : canonical);
            } catch (UncheckedIOException e) {
                throw new UncheckedIOException(new IOException(
                    csv.getSpec() + ":" + csv.getLineNo() + " cannot analyze MWE: " + expression, e));
            }
        }
    }

    /**
     * Folds a French lexical component for case-insensitive matching.
     *
     * @param form component text
     * @return lowercased form
     */
    private static String lower(final String form)
    {
        return form.toLowerCase(Locale.ROOT);
    }
}
