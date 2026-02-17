package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import java.io.StringReader;
import java.util.List;

// adjust imports to your packages
import com.github.oeuvres.alix.lucene.analysis.TokenizerML;
import com.github.oeuvres.alix.lucene.analysis.TokenizerML2;
import com.github.oeuvres.alix.lucene.analysis.TokenizerML3;

import static com.github.oeuvres.alix.common.Upos.*;

public class TokenizerPlayground {

    // --- case model ---
    record Case(String id, String title, String input, String notes) {}

    // --- curated cases (edit freely) ---
    static final List<Case> CASES = List.of(
        new Case("pb", 
                "Page break", 
                "<a class=\"pb\" role=\"doc-pagebreak\" aria-hidden=\"true\" tabindex=\"-1\" href=\"#p137\" id=\"p137\">[p. 137]</a>", 
                ""),
            
        new Case("apos", "Curly apostrophe (French elision)",
            "Lorsque l’on reconnaît, par exemple, que l’on n’a pas. ",
            "Check whether ’ stays inside token (is handled by a secific filter)."),

        new Case("C02", "Guillemets + nested quotes",
            "Guyénot parle de « fonctionnement prophétique » et d’« ontogenèse préparant le futur ».",
            "Quote marks should be punctuation tokens; words should not absorb them."),

        new Case("C03", "Em dash separators",
            "— et personne ne songe à en nier la nécessité —",
            "Dash should be punctuation token(s); check spaces around."),

        new Case("dots", "Abbreviation dot",
            "M. Dupont rencontre Dr. Martin, etc. U.S.A. fin. C. H. Waddington, par exemple.",
            "Decide policy: keep 'M.' as one token; internal dots in initialisms."),

        new Case("Numbers", "Numbers + decimal separators + trailing punctuation",
            "3,14 2.718 12, 12. 1,234 1.234 99... fin",
            "Ensure trailing '.'/',' after a number becomes punctuation, not part of number."),

        new Case("C06", "Sentence punct adjacency",
            "Hello!World What?!No ... …?!",
            "Ensure '!World' does not occur; sentence punct runs should not absorb letters."),

        new Case("C07", "Tags + entities",
            "<a href=\"x&y\">A&amp;B</a> &lt;p&gt;Texte&lt;/p&gt;",
            "Check XML tokenization and decoding of &amp; / &lt; / &gt;."),

        new Case("C08", "Broken/truncated tag (EOF)",
            "<a href=\"x\" Texte",
            "Decide behavior for unterminated tags; offsets must remain consistent.")
    );

    public static void main(String[] args) throws Exception {
        String which = (args.length == 0) ? "v3" : args[0]; // orig|v2|v3|diff12|diff13|diff23|all
        switch (which) {
            case "orig" -> runAll("TokenizerML (orig)", TokenizerML::new);
            case "v2"   -> runAll("TokenizerML2", TokenizerML2::new);
            case "v3"   -> runAll("TokenizerML3", TokenizerML3::new);

            // "human diff": prints first divergence, no asserts
            case "diff12" -> diffAll("orig", TokenizerML::new, "v2", TokenizerML2::new);
            case "diff13" -> diffAll("orig", TokenizerML::new, "v3", TokenizerML3::new);
            case "diff23" -> diffAll("v2", TokenizerML2::new, "v3", TokenizerML3::new);

            // prints outputs for all three (long)
            case "all" -> {
                runAll("TokenizerML (orig)", TokenizerML::new);
                runAll("TokenizerML2", TokenizerML2::new);
                runAll("TokenizerML3", TokenizerML3::new);
            }
            default -> {
                System.err.println("Usage: TokenizerPlayground [orig|v2|v3|diff12|diff13|diff23|all]");
                System.exit(2);
            }
        }
    }

    interface TokFactory { Tokenizer get(); }

    static void runAll(String name, TokFactory f) throws Exception {
        System.out.println("\n==== " + name + " ====\n");
        for (Case c : CASES) {
            System.out.println("---- " + c.id + " | " + c.title + " ----");
            System.out.println("Notes: " + c.notes);
            System.out.println("Input: " + showInput(c.input));
            printTokens(f.get(), c.input);
            System.out.println();
        }
    }

    static void diffAll(String aName, TokFactory a, String bName, TokFactory b) throws Exception {
        System.out.println("\n==== DIFF " + aName + " vs " + bName + " ====\n");
        for (Case c : CASES) {
            System.out.println("---- " + c.id + " | " + c.title + " ----");
            System.out.println("Input: " + showInput(c.input));
            firstDiff(aName, a.get(), bName, b.get(), c.input);
            System.out.println();
        }
    }

    static void printTokens(Tokenizer tok, String input) throws Exception {
        CharTermAttribute term = tok.addAttribute(CharTermAttribute.class);
        FlagsAttribute flags = tok.addAttribute(FlagsAttribute.class);
        OffsetAttribute off = tok.addAttribute(OffsetAttribute.class);

        tok.setReader(new StringReader(input));
        tok.reset();
        try {
            int i = 0;
            while (tok.incrementToken()) {
                int s = off.startOffset();
                int e = off.endOffset();
                int f = flags.getFlags();
                System.out.printf("%5d  [%d,%d)  %-10s  %s |%s|%n",
                        i++, s, e, flagName(f), escape(term.toString()), input.substring(s, e));
            }
            tok.end();
        } finally {
            tok.close();
        }
    }

    static void firstDiff(String aName, Tokenizer aTok, String bName, Tokenizer bTok, String input) throws Exception {
        var a = collect(aTok, input);
        var b = collect(bTok, input);

        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            Tok ta = a.get(i), tb = b.get(i);
            if (!ta.equals(tb)) {
                System.out.println("First diff at token #" + i);
                System.out.println("  " + aName + ": " + ta);
                System.out.println("  " + bName + ": " + tb);
                System.out.println("  ctx: " + context(input,
                        Math.min(ta.start, tb.start),
                        Math.max(ta.end, tb.end)));
                return;
            }
        }
        if (a.size() != b.size()) {
            System.out.println("Different token counts: " + aName + "=" + a.size() + " " + bName + "=" + b.size());
        } else {
            System.out.println("No diff (same stream)");
        }
    }

    // --- token snapshot for diff mode ---
    record Tok(String term, int flags, int start, int end) {
        @Override public String toString() {
            return escape(term) + "  " + flagName(flags) + "  [" + start + "," + end + ")";
        }
    }

    static java.util.ArrayList<Tok> collect(Tokenizer tok, String input) throws Exception {
        CharTermAttribute term = tok.addAttribute(CharTermAttribute.class);
        FlagsAttribute flags = tok.addAttribute(FlagsAttribute.class);
        OffsetAttribute off = tok.addAttribute(OffsetAttribute.class);

        tok.setReader(new StringReader(input));
        tok.reset();
        try {
            var out = new java.util.ArrayList<Tok>(1024);
            while (tok.incrementToken()) {
                out.add(new Tok(term.toString(), flags.getFlags(), off.startOffset(), off.endOffset()));
            }
            tok.end();
            return out;
        } finally {
            tok.close();
        }
    }

    // --- helpers ---
    static String flagName(int f) {
        if (f == XML.code) return "XML";
        if (f == DIGIT.code) return "DIGIT";
        if (f == PUNCTclause.code) return "PUNCTcl";
        if (f == PUNCTsent.code) return "PUNCTst";
        if (f == 0) return "X/0";
        return "F" + f;
    }

    static String showInput(String s) {
        return escape(s);
    }

    static String escape(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static String context(String input, int start, int end) {
        int lo = Math.max(0, start - 30);
        int hi = Math.min(input.length(), end + 30);
        String ctx = input.substring(lo, hi);
        ctx = ctx.replace("\n", "\\n").replace("\r", "\\r");
        // mark token span roughly
        int a = start - lo;
        int b = Math.max(a, end - lo);
        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(ctx).append("\"");
        sb.append("\n       ");
        for (int i = 0; i < a; i++) sb.append(' ');
        sb.append('^');
        for (int i = a + 1; i < b; i++) sb.append('-');
        sb.append('^');
        return sb.toString();
    }
}
