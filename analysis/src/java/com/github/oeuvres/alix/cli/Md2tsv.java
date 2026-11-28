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
import com.github.oeuvres.alix.ingest.IngestConfig;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.Dir;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

import static com.github.oeuvres.alix.ingest.IngestConfig.KeyGlob.*;

/**
 * Convert Markdown files to TSV files containing Lucene terms, lemmas,
 * part-of-speech tags.
 *
 * <p>YAML front matter is copied to the TSV header as comment metadata.
 * The Markdown body is then passed directly to the Lucene analyzer.
 * Structural punctuation, including source line-break events, is exported
 * as ordinary TSV rows with its own UPOS tag.
 * With {@code --config}, corpus normalizations, expressions, and stopwords
 * are loaded before processing any files.</p>
 */
public class Md2tsv
{
    /**
     * Run the Markdown to TSV conversion.
     *
     * @param args output directory, input file globs, and optional
     *             {@code --config corpus-properties.xml}
     * @throws IOException if a configuration, input, or output file cannot be processed
     */
    public static void main(String[] args) throws IOException
    {
        Report report = new ReportConsole();
        Path configPath = null;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i])) {
                if (configPath != null || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--config requires one configuration file");
                }
                configPath = Path.of(args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() < 2) {
            System.err.println("usage: Md2tsv [--config corpus-properties.xml] outdir folder/*.md ...");
            return;
        }

        Path outDir = Path.of(positional.get(0));
        Files.createDirectories(outDir);

        List<Path> files = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        for (String glob : positional.subList(1, positional.size())) {
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

        int processed = 0;
        int total = files.size();
        try (FrenchAnalyzer analyzer = new FrenchAnalyzer(MarkupMode.NONE)) {
            if (configPath != null) {
                IngestConfig cfg = IngestConfig.load(configPath, report);
                analyzer.addNormalizations(cfg.files(NORMALIZATIONS));
                analyzer.addExpressions(cfg.files(EXPRESSIONS));
                analyzer.addStopwords(cfg.files(STOPWORDS));
            }
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
                processed++;
                System.err.printf("\rConverted %d/%d files (%d%%)",
                    processed, total, 100 * processed / total);
            }
        } finally {
            if (processed > 0) {
                System.err.println();
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
     * part-of-speech tags. Structural punctuation is retained.
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

            tokens.reset();

            while (tokens.incrementToken()) {
                String term = termAtt.toString();
                String lemma = lemmaAtt.isEmpty()
                    ? term
                    : lemmaAtt.value();

                out.write(term);
                out.write('\t');
                out.write(lemma);
                out.write('\t');
                out.write(Upos.get(posAtt.getPos()).name());
                out.newLine();
            }

            tokens.end();
        }
    }
}
