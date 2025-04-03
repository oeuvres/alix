package com.github.oeuvres.alix.lucene.index;

import static com.github.oeuvres.alix.common.Flags.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.InvalidPropertiesFormatException;
import java.util.Properties;
import java.util.concurrent.Callable;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import com.github.oeuvres.alix.lucene.analysis.FilterAposHyphenFr;
import com.github.oeuvres.alix.lucene.analysis.FilterCloud;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPos;
import com.github.oeuvres.alix.lucene.analysis.FilterHTML;
import com.github.oeuvres.alix.lucene.analysis.FilterLemmatize;
import com.github.oeuvres.alix.lucene.analysis.FilterLocution;
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.lucene.analysis.TokenizerML;
import com.github.oeuvres.alix.util.Dir;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Analyse an XML/TEI corpus to output a custom text designed for a word2vec training.
 */
@Command(name = "Analyze", description = "Analyse an XML/TEI corpus to output a custom text designed for a word2vec training.")
public class Analyze4vec implements Callable<Integer>
{
    final static String APP = "alix.corpus4vec";
    @Parameters(index = "0", arity = "1", paramLabel = "corpus.xml", description = "1 Java/XML/properties describing a document collection (src file…)")
    /** configuration files */
    File conf;
    @Parameters(index = "1", arity = "1", paramLabel = "corpus.txt", description = "1 destination text file for analyzed corpus.")
    /** Destination text file. */
    File dstFile;
    /** File globs to parse, populated by parsing corpus properties */
    ArrayList<Path> paths = new ArrayList<>();
    @Override
    public Integer call() throws Exception
    {
        long time = System.nanoTime();
        parse(conf);
        paths.sort(null);
        Analyzer analyzer = new Analyzer4vec();
        Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dstFile), "UTF-8"));
        dstFile.getCanonicalFile().getParentFile().mkdirs();
        for (final Path path: paths) {
            System.out.println(path);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(path.toFile())
                , "UTF-8")
            );
            unroll(analyzer.tokenStream("", reader), writer);
        }
        analyzer.close();
        writer.close();
        System.out.println(
                "[" + APP + "] " + dstFile + " written in " + ((System.nanoTime() - time) / 1000000) + " ms.");
        return 0;
    }
    
    private static void unroll(final TokenStream tokenStream, final Writer writer) throws IOException
    {
        

        final CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAtt = tokenStream.addAttribute(FlagsAttribute.class);
        tokenStream.reset();
        int startLast = 0;
        while(tokenStream.incrementToken()) {
            final int flags = flagsAtt.getFlags();
            if (flags == PUNsection.code) {
                writer.write('\n');
                writer.write('\n');
                continue;
            }
            else if (flags == PUNpara.code) {
                writer.write('\n');
                writer.write('\n');
                continue;
            }
            else if (flags == PUNsent.code) {
                // out.write(10);
                continue;
            }
            else if (PUN.isPun(flags)) {
                continue;
            }
            else {
                char[] chars = termAtt.buffer();
                final int len = termAtt.length();
                for (int i = 0; i < len; i++) {
                    if (chars[i] == ' ') chars[i] = '_';
                }
                writer.write(termAtt.buffer(), 0, termAtt.length());
                writer.write(' ');
            }
        }
        tokenStream.close();
    }

    
    /**
     * Parse properties to output the corpus
     * 
     * @param propsFile A properties file in XML format
     *                  {@link Properties#loadFromXML(java.io.InputStream)}.
     * @throws IOException          I/O file system error, or required files not
     *                              found.
     * @throws NoSuchFieldException Properties errors.
     */
    public void parse(File propsFile) throws IOException, NoSuchFieldException
    {
        if (!propsFile.exists()) throw new FileNotFoundException(
                "\n  [" + APP + "] " + propsFile.getAbsolutePath() + "\nProperties file not found");
        Properties props = new Properties();
        try {
            props.loadFromXML(new FileInputStream(propsFile));
        }
        catch (InvalidPropertiesFormatException e) {
            throw new InvalidPropertiesFormatException(
                    "\n  [" + APP + "] " + propsFile + "\nXML error in properties file\n"
                            + "cf. https://docs.oracle.com/javase/8/docs/api/java/util/Properties.html");
        }
        catch (IOException e) {
            throw new IOException(
                    "\n  [" + APP + "] " + propsFile.getAbsolutePath() + "\nProperties file not readable");
        }

        final File base = propsFile.getCanonicalFile().getParentFile();

        final String src = props.getProperty("src");
        if (src == null) throw new NoSuchFieldException(
                "\n  [" + APP + "] " + propsFile + "\nan src entry is needed, to have path to index"
                        + "\n<entry key=\"src\">../corpus1/*.xml;../corpus2/*.xml</entry>");
        String[] blurf = src.split(" *[;] *|[\t ]*[\n\r]+[\t ]*");
        // resolve globs relative to the folder of the properties field
        for (String glob : blurf) {
            glob = Dir.globNorm(glob, base);
            Dir.include(paths, glob);
        }
        
        final String exclude = props.getProperty("exclude");
        if (exclude != null) {
            String[] globs = exclude.split(" *[;] *|[\t ]*[\n\r]+[\t ]*");
            for (String glob : globs) {
                glob = Dir.globNorm(glob, base);
                Dir.exclude(paths, glob);
            }
        }
        final String dicfile = props.getProperty("dicfile");
        if (dicfile != null) {
            File dicAbs = new File(dicfile);
            if (!dicAbs.isAbsolute()) dicAbs = new File(base, dicfile);
            if (!dicAbs.exists()) {
                throw new FileNotFoundException("Local dictionary file not found <entry key=\"dicfile\">" + dicfile
                        + "</entry>, resolved as " + dicAbs.getAbsolutePath());
            }
            FrDics.load(dicAbs.getCanonicalPath(), dicAbs);
        }
    }
    
    public class Analyzer4vec extends Analyzer
    {
        /**
         * Default constructor.
         */
        public Analyzer4vec()
        {
            super();
        }

        @SuppressWarnings("resource")
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML();
            TokenStream ts = tokenizer; // segment words
            // interpret html tags as token events like para or section
            ts = new FilterHTML(ts);
            // fr split on ’ and -
            ts = new FilterAposHyphenFr(ts);
            // pos tagging before lemmatize
            ts = new FilterFrPos(ts);
            // provide lemma+pos
            ts = new FilterLemmatize(ts);
            // group compounds after lemmatization for verbal compounds
            ts = new FilterLocution(ts);
            // last filter èrepare term to index
            ts = new FilterCloud(ts);
            return new TokenStreamComponents(tokenizer, ts);
        }

    }
    
    /**
     * Send index loading.
     * 
     * @param args Command line arguments.
     * @throws Exception XML or Lucene errors.
     */
    public static void main(String[] args) throws Exception
    {
        int exitCode = new CommandLine(new Analyze4vec()).execute(args);
        System.exit(exitCode);
    }
}
