package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import com.github.oeuvres.alix.util.CharsMap;

import java.util.List;

/**
 * Demo driver for a dictionary-based term replacement filter (TermReplaceFilter).
 *
 * <p>This demonstrates <b>orthographic normalization</b> (not lemmatization): mapping known spelling
 * variants to a single canonical form via exact, whole-token lookup.
 *
 * <p>Typical inputs: OCR/legacy spelling, ASCII transliterations, locale variants (UK/US), and
 * German pre-/post-reform spellings.
 *
 * <h2>Pipeline</h2>
 * <pre>
 *   StandardTokenizer -> TermReplaceFilter
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>StandardTokenizer avoids punctuation-as-token artefacts; however it will split on hyphens
 *       and apostrophes, so this demo avoids such examples on purpose.</li>
 *   <li>LowerCaseFilter makes the demo stable for sentence-initial capitalization and German nouns.
 *       If you need to preserve case, remove LowerCaseFilter and adapt the map accordingly.</li>
 *   <li>If your filter class is still named {@code TermMappingFilter}, replace
 *       {@code new TermReplaceFilter(...)} with {@code new TermMappingFilter(...)}.</li>
 * </ul>
 */
public final class TermReplaceFilterDemo {

    private TermReplaceFilterDemo() {}

    private static final String FIELD = "f";

    // ---------------------------------------------------------------------
    // Curated cases (orthography, not lemmatization)
    // ---------------------------------------------------------------------

    static final List<AnalysisDemoHelper.Case> EN_CASES = List.of(
        new AnalysisDemoHelper.Case(
            "Digraph / learned spelling variants",
            "Paediatric anaemia Encyclopaedia foetus.",
            "IgnoreCase. Canonicalize ae/oe variants: paediatric→pediatric, anaemia→anemia, encyclopaedia→encyclopedia, foetus→fetus."
        ),
        new AnalysisDemoHelper.Case(
            "UK/US spelling normalization (lexicon-driven)",
            "The colour of the centre is grey; we organise the programme.",
            "Canonicalize to US spellings: colour→color, centre→center, organise→organize, programme→program."
        ),
        new AnalysisDemoHelper.Case(
            "Diacritics in loanwords and editorial spellings",
            "A naïve coöperate façade résumé.",
            "Canonicalize to plain ASCII: naïve→naive, coöperate→cooperate, façade→facade, résumé→resume."
        )
    );

    static final List<AnalysisDemoHelper.Case> FR_CASES = List.of(
        new AnalysisDemoHelper.Case(
            "French ligatures: ASCII fallback → canonical Unicode",
            "boeuf coeur soeur oeuvre oeuf foetus",
            "Canonicalize oe→œ only for listed forms: boeuf→bœuf, coeur→cœur, soeur→sœur, oeuvre→œuvre, oeuf→œuf, foetus→fœtus."
        ),
        new AnalysisDemoHelper.Case(
            "Missing diacritics (curated, non-algorithmic)",
            "aout noel etude",
            "Restore diacritics only when you are confident: aout→août, noel→noël, etude→étude. (Avoid ambiguous cases like pere/père/…)"
        )
    );

    static final List<AnalysisDemoHelper.Case> DE_CASES = List.of(
        new AnalysisDemoHelper.Case(
            "German orthography reform and ß/ss variants",
            "daß dass muß muss strasse straße gross groß fluß fluss",
            "Canonicalize to standard modern spellings: daß→dass, muß→muss, strasse→straße, gross→groß, fluß→fluss."
        ),
        new AnalysisDemoHelper.Case(
            "Umlaut transliterations: ue/oe/ae → ü/ö/ä (curated list)",
            "mueller müller goedel gödel schroeder schröder",
            "Canonicalize common transliterations: mueller→müller, goedel→gödel, schroeder→schröder."
        ),
        new AnalysisDemoHelper.Case(
            "Other lexicalized spelling variants",
            "photographie fotografie",
            "Sometimes you just pick a house style: photographie→fotografie (if your corpus mixes both)."
        )
    );

    public static void main(String[] args) throws Exception {
        try (
            Analyzer en = buildAnalyzer(buildEnglishMap());
            Analyzer fr = buildAnalyzer(buildFrenchMap());
            Analyzer de = buildAnalyzer(buildGermanMap())
        ) {
            System.out.println("\n==== TermReplaceFilterDemo ====\n");

            run("EN", en, EN_CASES);
            run("FR", fr, FR_CASES);
            run("DE", de, DE_CASES);
        }
    }

    private static void run(final String lang, final Analyzer analyzer, final List<AnalysisDemoHelper.Case> cases)
        throws Exception
    {
        System.out.println("\n== " + lang + " ==\n");
        for (AnalysisDemoHelper.Case c : cases) {
            System.out.println("----? " + c.title() + " ----");
            if (c.notes() != null && !c.notes().isEmpty()) {
                System.out.println("Notes: " + c.notes());
            }
            System.out.println("Input: " + AnalysisDemoHelper.escape(c.input()));

            try {
                AnalysisDemoHelper.printTokens(analyzer, FIELD, c.input());
            }
            catch (RuntimeException ex) {
                System.out.println("[ERROR] " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            System.out.println();
        }
    }

    /** Minimal Analyzer for StandardTokenizer ->TermReplaceFilter. */
    private static Analyzer buildAnalyzer(final CharsMap map) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new StandardTokenizer();
                TokenStream stream = tokenizer;
                stream = new TermReplaceFilter(stream, map);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }


    private static CharsMap buildEnglishMap() {
        final CharsMap map = new CharsMap(64, true);

        // UK -> US
        map.put("colour", "color");
        map.put("centre", "center");
        map.put("organise", "organize");
        map.put("programme", "program");

        // Digraphs / learned spellings
        map.put("paediatric", "pediatric");
        map.put("anaemia", "anemia");
        map.put("encyclopaedia", "encyclopedia");
        map.put("foetus", "fetus");

        // Diacritics in common loanwords/editorial spellings
        map.put("naïve", "naive");
        map.put("coöperate", "cooperate");
        map.put("façade", "facade");
        map.put("résumé", "resume");

        return map;
    }

    private static CharsMap buildFrenchMap() {
        final CharsMap map = new CharsMap(64, true);

        // Ligatures (ASCII fallback -> canonical Unicode)
        map.put("boeuf", "bœuf");
        map.put("coeur", "cœur");
        map.put("soeur", "sœur");
        map.put("oeuvre", "œuvre");
        map.put("oeuf", "œuf");
        map.put("foetus", "fœtus");

        // Curated diacritics restoration
        map.put("aout", "août");
        map.put("noel", "noël");
        map.put("etude", "étude");

        return map;
    }

    private static CharsMap buildGermanMap() {
        final CharsMap map = new CharsMap(96, true);

        // Pre-/post-reform and Swiss variants
        map.put("daß", "dass");
        map.put("muß", "muss");
        map.put("strasse", "straße");
        map.put("gross", "groß");
        map.put("fluß", "fluss");

        // Umlaut transliterations (curated list)
        map.put("mueller", "müller");
        map.put("goedel", "gödel");
        map.put("schroeder", "schröder");

        // House style example
        map.put("photographie", "fotografie");

        return map;
    }


}
