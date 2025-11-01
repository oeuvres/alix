package com.github.oeuvres.alix.fr;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * AffixMiner — unsupervised extraction of prefixes, suffixes, and stems
 * from a (lemma, UDPOS, count[, ...]) CSV, with basic French‑oriented heuristics.
 *
 * <p>Input CSV requirements ( UTF‑8 ):
 * <ul>
 *   <li>Must contain headers: <b>lemma</b>, <b>udpos</b>, <b>count</b> (case‑insensitive).</li>
 *   <li>Comma‑separated; quotes allowed ("..."), doubled quotes for escaping.</li>
 * </ul>
 *
 * <p>Outputs (TSV, UTF‑8) written to the output directory:</p>
 * <ul>
 *   <li><b>prefixes.tsv</b> — columns: prefix, types, distinct_remainders, productivity, next_variety, H_forward_bits</li>
 *   <li><b>suffixes.tsv</b> — columns: suffix, types, distinct_remainders, productivity, prev_variety, H_backward_bits</li>
 *   <li><b>stems.tsv</b> — columns: stem, lemmas, family_prefixes, family_suffixes, sample_lemmas</li>
 *   <li><b>compat.tsv</b> — columns: prefix, suffix, pairs (how often a lemma segmented as prefix+stem+suffix)</li>
 * </ul>
 *
 * <p>Method (summary):</p>
 * <ol>
 *   <li>Read lemmas filtered by UDPOS whitelist (default: NOUN, ADJ, VERB). Normalize NFC, lowercase for mining.</li>
 *   <li>Enumerate prefix candidates (length in [pMin,pMax]) and suffix candidates (length in [sMin,sMax]).
 *       For each candidate, count types and distinct remainders, and collect boundary character distributions.</li>
 *   <li>Compute per‑candidate productivity = distinctRemainders / types, forward/backward branching entropy.</li>
 *   <li>Keep candidates passing thresholds (minTypes, minProductivity).</li>
 *   <li>For each lemma, try all kept (prefix,suffix) that fit; emit stems with len≥minStem.
 *       Aggregate stem families (distinct prefixes/suffixes and example lemmas).</li>
 * </ol>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>By default, lemmas containing hyphens/apostrophes are excluded from segmentation (keep for a later compound pass).</li>
 *   <li>Short lemmas are kept, but stems shorter than minStem are not emitted.</li>
 *   <li>This is orthographic mining; no rewrite rules are applied here. Those are learned in a second pass.</li>
 * </ul>
 */
public final class AffixMiner
{
    // ------------ Configuration (overridable via CLI flags) ------------
    int pMin = 2, pMax = 5;      // prefix length range
    int sMin = 2, sMax = 6;      // suffix length range
    int minStem = 3;             // minimum middle length to accept a stem
    int minTypes = 50;           // minimum lemma types per affix candidate
    double minProductivity = 0.50; // distinct remainders / types
    boolean allowHyphen = false; // include lemmas with '-'
    boolean allowApos = false;   // include lemmas with apostrophes

    final Set<String> posWhitelist = new LinkedHashSet<>(Arrays.asList("NOUN","ADJ","VERB"));

    // ------------ Data structures ------------
    static final class PrefixStats {
        final String p;
        int types = 0;
        final Map<String, Integer> remainderTypes = new HashMap<>(); // only keys matter
        final Map<Integer, Integer> nextChar = new HashMap<>();       // codepoint after prefix
        PrefixStats(String p){this.p=p;}
    }
    static final class SuffixStats {
        final String s;
        int types = 0;
        final Map<String, Integer> remainderTypes = new HashMap<>(); // only keys matter
        final Map<Integer, Integer> prevChar = new HashMap<>();       // codepoint before suffix
        SuffixStats(String s){this.s=s;}
    }

    static final class StemFamily {
        final String stem;
        final Set<String> prefixes = new LinkedHashSet<>();
        final Set<String> suffixes = new LinkedHashSet<>();
        final Deque<String> examples = new ArrayDeque<>();
        int lemmaTypes = 0;
        StemFamily(String stem){this.stem=stem;}
        void addExample(String lemma){ if (examples.size()<8) examples.addLast(lemma); }
    }

    final Map<String, PrefixStats> P = new HashMap<>();
    final Map<String, SuffixStats> S = new HashMap<>();
    final Map<String, StemFamily> ST = new HashMap<>();
    final Map<String, Integer> compat = new HashMap<>(); // key = prefix + "\t" + suffix

    // ------------ Entry point ------------
    public static void main(String[] args) throws Exception
    {
        final Path outDir = Paths.get("stemming");
        Files.createDirectories(outDir);


        final AffixMiner miner = new AffixMiner();
        final String lexicon = "fr-lemma-ud.csv";
        BufferedReader br = new BufferedReader(new InputStreamReader(French.class.getResourceAsStream(lexicon), StandardCharsets.UTF_8));
        final List<Row> rows = readCSV(br);
        miner.scan(rows);
        System.out.println(outDir.toAbsolutePath());
        miner.writeOutputs(outDir);
    }


    // ------------ Scan & compute ------------
    void scan(List<Row> rows)
    {
        // 1) Enumerate prefix/suffix candidates with boundary stats
        for (Row r : rows) {
            if (!posWhitelist.contains(r.upos)) continue;
            final String lemma = normalizeLemma(r.lemma);
            if (lemma == null) continue; // excluded by punctuation policy
            final int L = lemma.length();

            // prefixes
            for (int k = pMin; k <= pMax && k < L; k++) {
                final String p = lemma.substring(0, k);
                PrefixStats ps = P.get(p);
                if (ps == null) P.put(p, ps = new PrefixStats(p));
                ps.types++;
                ps.remainderTypes.put(lemma.substring(k), 1);
                if (k < L) {
                    final int cp = lemma.codePointAt(k);
                    ps.nextChar.merge(cp, 1, Integer::sum);
                }
            }
            // suffixes
            for (int k = sMin; k <= sMax && k < L; k++) {
                final String s = lemma.substring(L - k);
                SuffixStats ss = S.get(s);
                if (ss == null) S.put(s, ss = new SuffixStats(s));
                ss.types++;
                ss.remainderTypes.put(lemma.substring(0, L - k), 1);
                if (L - k - 1 >= 0) {
                    final int cp = lemma.codePointAt(L - k - 1);
                    ss.prevChar.merge(cp, 1, Integer::sum);
                }
            }
        }

        // 2) Filter affix candidates by thresholds
        final Set<String> keptP = new HashSet<>();
        final Set<String> keptS = new HashSet<>();
        for (PrefixStats ps : P.values()) {
            final double prod = productivity(ps.remainderTypes.size(), ps.types);
            if (ps.types >= minTypes && prod >= minProductivity) keptP.add(ps.p);
        }
        for (SuffixStats ss : S.values()) {
            final double prod = productivity(ss.remainderTypes.size(), ss.types);
            if (ss.types >= minTypes && prod >= minProductivity) keptS.add(ss.s);
        }

        // 3) Stem induction with kept affixes
        for (Row r : rows) {
            if (!posWhitelist.contains(r.upos)) continue;
            final String lemma = normalizeLemma(r.lemma);
            if (lemma == null) continue;
            final int L = lemma.length();

            for (String p : candidatesFor(lemma, keptP, true)) {
                for (String s : candidatesFor(lemma, keptS, false)) {
                    final int k = p.length();
                    final int m = L - s.length();
                    if (m - k < minStem) continue;
                    final String stem = lemma.substring(k, m);
                    StemFamily f = ST.get(stem);
                    if (f == null) ST.put(stem, f = new StemFamily(stem));
                    f.lemmaTypes++;
                    f.prefixes.add(p);
                    f.suffixes.add(s);
                    f.addExample(lemma);
                    final String key = p + "\t" + s;
                    compat.merge(key, 1, Integer::sum);
                }
            }
        }
    }

    // helper: list all kept affixes that fit this lemma (prefix or suffix)
    static Collection<String> candidatesFor(String lemma, Set<String> kept, boolean prefix)
    {
        final List<String> out = new ArrayList<>();
        if (prefix) {
            for (String a : kept) if (lemma.startsWith(a)) out.add(a);
        } else {
            for (String a : kept) if (lemma.endsWith(a)) out.add(a);
        }
        return out;
    }

    static double productivity(int distinctRemainders, int types)
    { return (types <= 0) ? 0.0 : (distinctRemainders / (double) types); }

    static double entropyBits(Map<Integer,Integer> counts)
    {
        if (counts.isEmpty()) return 0.0;
        long tot = 0; for (int v : counts.values()) tot += v;
        double H = 0.0;
        for (int v : counts.values()) {
            final double p = v / (double) tot;
            H -= p * (Math.log(p) / Math.log(2));
        }
        return H;
    }

    String normalizeLemma(String s)
    {
        if (s == null) return null;
        s = s.strip();
        if (s.isEmpty()) return null;
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        // policy on hyphens/apostrophes
        if (!allowHyphen && containsAny(s, "-", "‑", "–", "—")) return null;
        if (!allowApos   && containsAny(s, "'", "’", "ʼ")) return null;
        // exclude whitespace internal
        for (int i=0;i<s.length();i++) if (Character.isWhitespace(s.charAt(i))) return null;
        // lowercase for mining
        s = s.toLowerCase(Locale.ROOT);
        // keep only lemmas with at least one letter
        boolean hasLetter=false;
        for (int i=0;i<s.length();i++) if (Character.isLetter(s.charAt(i))) { hasLetter=true; break; }
        if (!hasLetter) return null;
        return s;
    }

    static boolean containsAny(String s, String... needles)
    {
        for (String n : needles) if (s.indexOf(n) >= 0) return true;
        return false;
    }

    void writeOutputs(Path outDir) throws IOException
    {
        // prefixes.tsv
        try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("prefixes.tsv"), StandardCharsets.UTF_8)) {
            w.write("prefix\ttypes\tdistinct_remainders\tproductivity\tnext_variety\tH_forward_bits\n");
            P.values().stream()
             .filter(ps -> ps.types >= minTypes && productivity(ps.remainderTypes.size(), ps.types) >= minProductivity)
             .sorted(Comparator.comparingInt((PrefixStats ps) -> ps.types).reversed())
             .forEach(ps -> {
                 try {
                     final double prod = productivity(ps.remainderTypes.size(), ps.types);
                     final double H = entropyBits(ps.nextChar);
                     w.write(ps.p + "\t" + ps.types + "\t" + ps.remainderTypes.size() + "\t" + fmt(prod) + "\t" + ps.nextChar.size() + "\t" + fmt(H) + "\n");
                 } catch (IOException e) { throw new UncheckedIOException(e); }
             });
        }
        // suffixes.tsv
        try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("suffixes.tsv"), StandardCharsets.UTF_8)) {
            w.write("suffix\ttypes\tdistinct_remainders\tproductivity\tprev_variety\tH_backward_bits\n");
            S.values().stream()
             .filter(ss -> ss.types >= minTypes && productivity(ss.remainderTypes.size(), ss.types) >= minProductivity)
             .sorted(Comparator.comparingInt((SuffixStats ss) -> ss.types).reversed())
             .forEach(ss -> {
                 try {
                     final double prod = productivity(ss.remainderTypes.size(), ss.types);
                     final double H = entropyBits(ss.prevChar);
                     w.write(ss.s + "\t" + ss.types + "\t" + ss.remainderTypes.size() + "\t" + fmt(prod) + "\t" + ss.prevChar.size() + "\t" + fmt(H) + "\n");
                 } catch (IOException e) { throw new UncheckedIOException(e); }
             });
        }
        // stems.tsv
        try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("stems.tsv"), StandardCharsets.UTF_8)) {
            w.write("stem\tlemmas\tfamily_prefixes\tfamily_suffixes\tsample_lemmas\n");
            ST.values().stream()
              .sorted(Comparator.comparingInt((StemFamily f) -> f.lemmaTypes).reversed())
              .forEach(f -> {
                  try {
                      w.write(f.stem + "\t" + f.lemmaTypes + "\t" + f.prefixes.size() + "\t" + f.suffixes.size() + "\t" + String.join(" ", f.examples) + "\n");
                  } catch (IOException e) { throw new UncheckedIOException(e); }
              });
        }
        // compat.tsv
        try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("compat.tsv"), StandardCharsets.UTF_8)) {
            w.write("prefix\tsuffix\tpairs\n");
            compat.entrySet().stream()
                  .sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
                  .forEach(e -> {
                      try {
                          w.write(e.getKey() + "\t" + e.getValue() + "\n");
                      } catch (IOException ex) { throw new UncheckedIOException(ex); }
                  });
        }
    }

    static String fmt(double x){ return String.format(Locale.ROOT, "%.6f", x); }

    // ------------ CSV reading ------------
    static final class Row { final String lemma, upos; final long count; Row(String l,String u,long c){lemma=l; upos=u; count=c;} }

    static List<Row> readCSV(BufferedReader br) throws IOException
    {
        final List<Row> out = new ArrayList<>(8192);
        final String headerLine = br.readLine();
        if (headerLine == null) throw new IOException("empty file");
        final List<String> header = parseCSVLine(headerLine);
        final int idxLemma = indexOfIgnoreCase(header, "lemma");
        final int idxPos   = indexOfIgnoreCase(header, "udpos");
        final int idxCnt   = indexOfIgnoreCase(header, "count");
        if (idxLemma < 0 || idxPos < 0 || idxCnt < 0) {
            throw new IOException("Missing required columns: lemma, udpos, count");
        }
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) continue;
            final List<String> cols = parseCSVLine(line);
            if (cols.size() <= Math.max(idxCnt, Math.max(idxLemma, idxPos))) continue;
            final String lemma = cols.get(idxLemma);
            final String upos = cols.get(idxPos);
            final String cstr = cols.get(idxCnt);
            if (lemma == null || upos == null || cstr == null) continue;
            long count = 0L;
            try { count = Long.parseLong(cstr.trim()); } catch (NumberFormatException ignore) {}
            out.add(new Row(lemma, upos, count));
        }
        return out;
    }

    static int indexOfIgnoreCase(List<String> cols, String name)
    {
        for (int i=0;i<cols.size();i++)
            if (cols.get(i) != null && cols.get(i).trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** Minimal CSV parser: handles commas, quotes, doubled quotes. */
    static List<String> parseCSVLine(String line)
    {
        final ArrayList<String> out = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i=0;i<line.length();i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i+1 < line.length() && line.charAt(i+1) == '"') { sb.append('"'); i++; }
                    else { inQuotes = false; }
                } else {
                    sb.append(ch);
                }
            } else {
                if (ch == '"') { inQuotes = true; }
                else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); }
                else { sb.append(ch); }
            }
        }
        out.add(sb.toString());
        return out;
    }
}
