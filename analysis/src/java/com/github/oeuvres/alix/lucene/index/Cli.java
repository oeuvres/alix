package com.github.oeuvres.alix.lucene.index;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.io.FileOutputStream;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Properties;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import com.github.oeuvres.alix.lucene.analysis.FilterAposHyphenFr;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPos;
import com.github.oeuvres.alix.lucene.analysis.FilterHTML;
import com.github.oeuvres.alix.lucene.analysis.FilterLemmatize;
import com.github.oeuvres.alix.lucene.analysis.FilterLocution;
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.lucene.analysis.TokenizerML;
import com.github.oeuvres.alix.util.Dir;

import picocli.CommandLine.Parameters;

/**
 * Tools to process a corpus config file.
 */
public abstract class Cli
{
    @Parameters(index = "0", arity = "0..1", paramLabel = "corpus.xml", description = "1 Java/XML/properties describing a document collection (src file…)")
    /** configuration files */
    File conf;
    /** Loaded properties */
    Properties properties = new Properties();
    /** For log, name of calling class */
    final String APP;
    /** File globs to parse, populated by parsing corpus properties */
    ArrayList<Path> paths = new ArrayList<>();
    /** Current path processed */
    Path path;
    /** Possible local transformation for pre-indexation */
    String xsl;

    static {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
    
    public Cli()
    {
        APP = MethodHandles.lookup().lookupClass().getSimpleName();

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
        String key;
        if (!propsFile.exists()) throw new FileNotFoundException(
                "\n  [" + APP + "] " + propsFile.getAbsolutePath() + "\nProperties file not found");
        
        try {
            properties.loadFromXML(new FileInputStream(propsFile));
        }
        catch (InvalidPropertiesFormatException e) {
            throw new InvalidPropertiesFormatException(
                    "\n  [" + APP + "] " + propsFile + "\nXML error in properties file\n"
                            + "cf. https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Properties.html");
        }
        catch (IOException e) {
            throw new IOException(
                    "\n  [" + APP + "] " + propsFile.getAbsolutePath() + "\nProperties file not readable");
        }

        key = "name";
        // if no name properties 
        if (properties.getProperty(key) == null) {
            properties.setProperty(key, conf.getName().replaceFirst("\\..+$", ""));
        }
        
        for (final String glob : globs("src", true)) Dir.include(paths, glob);
        for (final String glob : globs("exclude")) Dir.exclude(paths, glob);
        for (final String dic: globs("dicfile")) {
            File dicAbs = new File(dic);
            if (!dicAbs.exists()) {
                System.err.println("Local dictionary file not found: " + dic
                        + " (resolved as: " + dicAbs.getAbsolutePath() + ")");
                continue;
            }
            FrDics.load(dicAbs.getCanonicalPath(), dicAbs);
            System.err.println("Local dictionary loaded: " + dicAbs);
        }
        for (final String dic: globs("stopfile")) {
            File dicAbs = new File(dic);
            if (!dicAbs.exists()) {
                System.err.println("Local dictionary file not found: " + dic
                        + " (resolved as: " + dicAbs.getAbsolutePath() + ")");
                continue;
            }
            FrDics.load(dicAbs.getCanonicalPath(), dicAbs);
            System.err.println("Local dictionary loaded: " + dicAbs);
        }
        
        key = "xsl";
        List<String> values = globs(key);
        int n = 0;
        for (String xsl: values) {
            n++;
            if (n == 1) {
                File file = new File(xsl);
                if (!file.exists()) {
                    System.err.println("XSLT file not found <entry key=\"" + key + "\">" + xsl
                            + "</entry>, resolved as " + file.getAbsolutePath());
                    continue;
                }
                this.xsl = xsl;
                continue;
            }
            System.err.println("xsl[" + n +"]=\"" + xsl +"\" xsl transformations pipeline unsupported.");
        }
    }

    public List<String> globs(final String key) throws IOException
    {
        return globs(key, false);
    }
    
    public List<String> globs(final String key, boolean required) throws IOException
    {
        ArrayList<String> globs = new ArrayList<String>();
        final String value = properties.getProperty(key);
        if (value == null) {
            if (required) {
                System.err.println(
                    "[" + APP + "] " + conf + "\n<entry key=\"" + key + "\">...</entry> is required."
                );
            }
            return globs;
        }
        String[] split = value.split("[\n\r]+");
        for (String glob: split) {
            glob = glob.trim();
            if (glob.isBlank()) continue;
            glob = Dir.globNorm(glob, conf.getParentFile());
            globs.add(glob);
        }
        return globs;
    }

    public class AnaCli extends Analyzer
    {
        /**
         * Default constructor.
         */
        public AnaCli()
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
            
            return new TokenStreamComponents(tokenizer, ts);
        }
    }

}
