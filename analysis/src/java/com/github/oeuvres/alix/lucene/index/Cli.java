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
import java.util.Properties;

import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.util.Dir;

import picocli.CommandLine.Parameters;

/**
 * Tools to process a 
 */
public abstract class Cli
{
    static {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
    
    @Parameters(index = "0", arity = "0..1", paramLabel = "corpus.xml", description = "1 Java/XML/properties describing a document collection (src file…)")
    /** configuration files */
    File conf;
    /** File globs to parse, populated by parsing corpus properties */
    ArrayList<Path> paths = new ArrayList<>();

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
        final String APP = MethodHandles.lookup().lookupClass().getSimpleName();
        if (!propsFile.exists()) throw new FileNotFoundException(
                "\n  [" + APP + "] " + propsFile.getAbsolutePath() + "\nProperties file not found");
        Properties props = new Properties();
        try {
            props.loadFromXML(new FileInputStream(propsFile));
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


}
