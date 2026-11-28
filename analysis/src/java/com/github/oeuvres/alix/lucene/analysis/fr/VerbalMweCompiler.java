package com.github.oeuvres.alix.lucene.analysis.fr;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import java.util.Set;
import java.util.function.Consumer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.hunspell.AffixedWord;
import org.apache.lucene.analysis.hunspell.DictEntries;
import org.apache.lucene.analysis.hunspell.DictEntry;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.WordFormGenerator;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.MweLexicon;
import com.github.oeuvres.alix.util.Report;

/**
 * Compiles French lexical MWE declarations, optionally
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

    /** Pre-MWE entry analyzer shared by explicit and generated keys. */
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
     * @return true when the explicit expression contains at least two nonempty tokens
     * @throws UncheckedIOException if token analysis fails
     */
    public boolean addExpression(final CharSequence expression,
        final CharSequence pos, final CharSequence canonical)
    {
        final Report report = new Report.ReportConsole();
        final Consumer<String> warn = message -> report.warn(expression + ": " + message);
        return addExpression(expression, LexiconHelper.resolveMwePos(pos.toString(), warn),
            canonical, warn);
    }

    /**
     * Registers a resolved declaration and expands its verbal head when applicable.
     * @param expression source expression
     * @param tag resolved lexical POS
     * @param canonical canonical output
     * @param warn diagnostic callback with source location
     * @return true when the expression was registered
     */
    private boolean addExpression(final CharSequence expression, final Upos tag,
        final CharSequence canonical, final Consumer<String> warn)
    {
        final List<String> forms = LexiconHelper.expressionForms(analyzer, expression);
        final String output = LexiconHelper.expressionOutput(expression, canonical);
        if (forms.size() < 2 || output.isEmpty()) return false;
        // Explicit declarations always win, including over earlier generated keys.
        LexiconHelper.registerExpression(lexicon, forms, output, tag, warn);
        registered.add(key(forms));
        if (tag != VERB) {
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
            final List<String> normalized = LexiconHelper.expressionForms(analyzer, variant);
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
     * Loads headered expression declarations with caller-selected diagnostics.
     * @param csv unread, caller-owned reader
     * @param report diagnostic sink; null suppresses warnings
     * @throws UncheckedIOException if reading or analysis fails
     */
    public void loadExpressions(final CSVReader csv, final Report report)
    {
        LexiconHelper.loadExpressions(csv, this::addExpression, report);
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
