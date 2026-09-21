package com.github.oeuvres.alix.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;
import com.github.oeuvres.alix.util.Dir;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

/**
 * Convert Markdown files to TSV files containing Lucene terms, lemmas,
 * part-of-speech tags, and POS-tagging probabilities.
 *
 * <p>YAML front matter is copied to the TSV header as comment metadata.
 * The Markdown body is then passed directly to the Lucene analyzer.</p>
 */
public class Md2tsv
{
    /**
     * Run the Markdown to TSV conversion.
     *
     * @param args first argument is the output directory; following arguments
     *             are input file globs
     * @throws IOException if an input or output file cannot be processed
     */
    public static void main(String[] args) throws IOException
    {
        Report report = new ReportConsole();
        if (args.length < 2) {
            System.err.println("mandatory args: outdir folder/*.md");
            System.exit(1);
        }

        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);

        List<Path> files = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        for (int i = 1; i < args.length; i++) {
            String glob = args[i];
            List<Path> matches = Dir.ls(glob);
            if (matches.isEmpty()) {
                report.warn("‘" + glob + "’ has matched no files.");
                continue;
            }

            for (Path path : matches) {
                Path abs = path.toAbsolutePath().normalize();
                if (!seen.add(abs)) {
                    report.warn("‘" + path + "’ duplicate path (ignored)");
                    continue;
                }
                files.add(abs);
            }
        }

        try (FrenchAnalyzer analyzer = new FrenchAnalyzer(MarkupMode.NONE)) {
            for (Path md : files) {
                Path tsv = outputPath(outDir, md);

                try (
                    BufferedReader reader = Files.newBufferedReader(
                        md,
                        StandardCharsets.UTF_8
                    );
                    BufferedWriter out = Files.newBufferedWriter(
                        tsv,
                        StandardCharsets.UTF_8
                    )
                ) {
                    out.write("# columns = TERM LEMMA POS");
                    out.newLine();

                    writeMetadata(reader, out);
                    out.newLine();

                    writeTokens(reader, out, analyzer);
                }
            }
        }
    }

    /**
     * Build the output path for an input Markdown file.
     *
     * @param outDir output directory
     * @param md input Markdown file
     * @return corresponding TSV path
     */
    private static Path outputPath(Path outDir, Path md)
    {
        String name = md.getFileName().toString();
        if (name.endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        return outDir.resolve(name + ".tsv");
    }

    /**
     * Read YAML front matter from the current reader position and write it
     * as TSV comment metadata.
     *
     * <p>If the file does not start with a YAML front matter delimiter,
     * the reader is reset so that no text is lost before analysis.</p>
     *
     * @param reader Markdown reader
     * @param out TSV writer
     * @throws IOException if the metadata cannot be read or written
     */
    private static void writeMetadata(
        BufferedReader reader,
        BufferedWriter out
    ) throws IOException
    {
        reader.mark(65536);

        String line = reader.readLine();
        if (line == null) {
            return;
        }

        if (!line.trim().equals("---")) {
            reader.reset();
            return;
        }

        while ((line = reader.readLine()) != null) {
            if (line.trim().equals("---")) {
                return;
            }

            if (line.isBlank()) {
                continue;
            }

            int colon = line.indexOf(':');
            if (colon < 1) {
                out.write("# ");
                out.write(line);
                out.newLine();
                continue;
            }

            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();

            out.write("# ");
            out.write(key);
            out.write(" = ");
            out.write(value);
            out.newLine();
        }

        throw new IOException("Unclosed YAML front matter");
    }

    /**
     * Analyze the remaining Markdown content and write its terms, lemmas,
     * part-of-speech tags, and POS-tagging probabilities.
     *
     * @param reader reader positioned at the Markdown body
     * @param out TSV writer
     * @param analyzer Lucene French analyzer
     * @throws IOException if analysis or writing fails
     */
    private static void writeTokens(
        BufferedReader reader,
        BufferedWriter out,
        FrenchAnalyzer analyzer
    ) throws IOException
    {
        try (TokenStream tokens = analyzer.tokenStream("tsv", reader)) {
            CharTermAttribute termAtt =
                tokens.addAttribute(CharTermAttribute.class);
            PosAttribute posAtt =
                tokens.addAttribute(PosAttribute.class);
            LemmaAttribute lemmaAtt =
                tokens.addAttribute(LemmaAttribute.class);
            ProbAttribute probAtt =
                tokens.addAttribute(ProbAttribute.class);

            tokens.reset();

            while (tokens.incrementToken()) {
                String term = termAtt.toString();
                String lemma = lemmaAtt.isEmpty()
                    ? term
                    : lemmaAtt.toString();

                out.write(term);
                out.write('\t');
                out.write(lemma);
                out.write('\t');
                out.write(Upos.name(posAtt.getPos()));
                /*
                out.write('\t');
                out.write(Double.toString(probAtt.getProb()));
                */
                out.newLine();
            }

            tokens.end();
        }
    }
}
