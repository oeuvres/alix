package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

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

    static final List<AnalysisDemoSupport.Case> EN_CASES = List.of(
        new AnalysisDemoSupport.Case(
            "Digraph / learned spelling variants",
            "Paediatric anaemia Encyclopaedia foetus.",
            "IgnoreCase. Canonicalize ae/oe variants: paediatric→pediatric, anaemia→anemia, encyclopaedia→encyclopedia, foetus→fetus."
        ),
        new AnalysisDemoSupport.Case(
            "UK/US spelling normalization (lexicon-driven)",
            "The colour of the centre is grey; we organise the programme.",
            "Canonicalize to US spellings: colour→color, centre→center, organise→organize, programme→program."
        ),
        new AnalysisDemoSupport.Case(
            "Diacritics in loanwords and editorial spellings",
            "A naïve coöperate façade résumé.",
            "Canonicalize to plain ASCII: naïve→naive, coöperate→cooperate, façade→facade, résumé→resume."
        )
    );

    static final List<AnalysisDemoSupport.Case> FR_CASES = List.of(
        new AnalysisDemoSupport.Case(
            "French ligatures: ASCII fallback → canonical Unicode",
            "boeuf coeur soeur oeuvre oeuf foetus",
            "Canonicalize oe→œ only for listed forms: boeuf→bœuf, coeur→cœur, soeur→sœur, oeuvre→œuvre, oeuf→œuf, foetus→fœtus."
        ),
        new AnalysisDemoSupport.Case(
            "Missing diacritics (curated, non-algorithmic)",
            "aout noel etude",
            "Restore diacritics only when you are confident: aout→août, noel→noël, etude→étude. (Avoid ambiguous cases like pere/père/…)"
        )
    );

    static final List<AnalysisDemoSupport.Case> DE_CASES = List.of(
        new AnalysisDemoSupport.Case(
            "German orthography reform and ß/ss variants",
            "daß dass muß muss strasse straße gross groß fluß fluss",
            "Canonicalize to standard modern spellings: daß→dass, muß→muss, strasse→straße, gross→groß, fluß→fluss."
        ),
        new AnalysisDemoSupport.Case(
            "Umlaut transliterations: ue/oe/ae → ü/ö/ä (curated list)",
            "mueller müller goedel gödel schroeder schröder",
            "Canonicalize common transliterations: mueller→müller, goedel→gödel, schroeder→schröder."
        ),
        new AnalysisDemoSupport.Case(
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

    private static void run(final String lang, final Analyzer analyzer, final List<AnalysisDemoSupport.Case> cases)
        throws Exception
    {
        System.out.println("\n== " + lang + " ==\n");
        for (AnalysisDemoSupport.Case c : cases) {
            System.out.println("----? " + c.title() + " ----");
            if (c.notes() != null && !c.notes().isEmpty()) {
                System.out.println("Notes: " + c.notes());
            }
            System.out.println("Input: " + AnalysisDemoSupport.escape(c.input()));

            try {
                AnalysisDemoSupport.printTokens(analyzer, FIELD, c.input());
            }
            catch (RuntimeException ex) {
                System.out.println("[ERROR] " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            System.out.println();
        }
    }

    /** Minimal Analyzer for StandardTokenizer ->TermReplaceFilter. */
    private static Analyzer buildAnalyzer(final CharArrayMap<char[]> map) {
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


    private static CharArrayMap<char[]> buildEnglishMap() {
        final CharArrayMap<char[]> map = new CharArrayMap<>(64, true);

        // UK -> US
        put(map, "colour", "color");
        put(map, "centre", "center");
        put(map, "organise", "organize");
        put(map, "programme", "program");

        // Digraphs / learned spellings
        put(map, "paediatric", "pediatric");
        put(map, "anaemia", "anemia");
        put(map, "encyclopaedia", "encyclopedia");
        put(map, "foetus", "fetus");

        // Diacritics in common loanwords/editorial spellings
        put(map, "naïve", "naive");
        put(map, "coöperate", "cooperate");
        put(map, "façade", "facade");
        put(map, "résumé", "resume");

        return map;
    }

    private static CharArrayMap<char[]> buildFrenchMap() {
        final CharArrayMap<char[]> map = new CharArrayMap<>(64, true);

        // Ligatures (ASCII fallback -> canonical Unicode)
        put(map, "boeuf", "bœuf");
        put(map, "coeur", "cœur");
        put(map, "soeur", "sœur");
        put(map, "oeuvre", "œuvre");
        put(map, "oeuf", "œuf");
        put(map, "foetus", "fœtus");

        // Curated diacritics restoration
        put(map, "aout", "août");
        put(map, "noel", "noël");
        put(map, "etude", "étude");

        return map;
    }

    private static CharArrayMap<char[]> buildGermanMap() {
        final CharArrayMap<char[]> map = new CharArrayMap<>(96, true);

        // Pre-/post-reform and Swiss variants
        put(map, "daß", "dass");
        put(map, "muß", "muss");
        put(map, "strasse", "straße");
        put(map, "gross", "groß");
        put(map, "fluß", "fluss");

        // Umlaut transliterations (curated list)
        put(map, "mueller", "müller");
        put(map, "goedel", "gödel");
        put(map, "schroeder", "schröder");

        // House style example
        put(map, "photographie", "fotografie");

        return map;
    }

    private static void put(final CharArrayMap<char[]> map, final String key, final String value) {
        map.put(key, value.toCharArray());
    }
}
