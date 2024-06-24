/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Callable;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;

import com.github.oeuvres.alix.lucene.Alix;
import com.github.oeuvres.alix.lucene.XMLIndexer;
import com.github.oeuvres.alix.lucene.analysis.AlixAnalyzer;
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.util.Dir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "com.github.oeuvres.alix.cli.Load", description = "Load an XML/TEI corpus in a custom Lucene index for Alix.")
public class Load implements Callable<Integer>
{
    public static String APP = "Alix";

    static {
        // System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT]
        // [%4$-7s] %5$s %n");
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%4$-7s] %5$s %n");
    }

    @Parameters(arity = "1..*", paramLabel = "base.xml", description = "1 or more Java/XML/properties describing a document base (label, src…)")
    File[] conflist;
    @Option(names = { "-u", "--unsafe" }, description = "For windows filesystem, no temp lucene index")
    boolean unsafe;
    @Option(names = { "-t", "--threads" }, description = "Number of threads fo indexation")
    int threads;
    /** File globs to index, populated by parsing base properties */
    ArrayList<Path> paths = new ArrayList<>();
    /** Destination directory of index of a base */
    File dstdir;
    /** Destination name of base */
    String dstname;
    /** Possible local transformation for pre-indexation */
    String xsl;

    /**
     * Return true if a directory does not exist or if it has been removed.
     *
     * @param dir A directory of files.
     * @return true if directory has been nicely deleted or do not exists.
     * @throws IOException I/O file system errors.
     */
    private boolean ask4rmdir(File dir) throws IOException
    {
        if (!dir.exists()) return true;
        long modified = dir.lastModified();
        Duration duration = Duration.ofMillis(System.currentTimeMillis() - modified);
        System.out
                .println("[" + APP + "] Folder exists and was modified " + duration.toSeconds() + " s. ago\n  " + dir);
        System.out.println("A tmp folder is not yet deleted, a process may be indexing, would you like to remove? y/n");
        Scanner in = new Scanner(System.in);
        String yes = in.nextLine();
        in.close();
        if (!"y".equals(yes) && !"Y".equals(yes)) {
            System.out.println("[" + APP + "] Nothing remove");
            return false;
        }
        Dir.rm(dir);
        if (dir.exists()) {
            throw new IOException("\n  [" + APP + "] Impossible to delete temp index\n" + dir);
        }
        return true;
    }

    @Override
    public Integer call() throws Exception
    {
        String os = System.getProperty("os.name");
        // no system properties allow to detect a linux running inside windows on
        // windows filesystem
        unsafe = os.startsWith("Windows");
        for (final File conf : conflist) {
            dstname = conf.getName().replaceFirst("\\..+$", "");
            // in case of glob, avoid some things
            // test here if it's folder ?
            if (conf.getCanonicalPath().endsWith("WEB-INF/web.xml") || conf.getName().startsWith(".")
                    || conf.getName().startsWith("_")) {
                continue;
            }
            long time = System.nanoTime();
            parse(conf); // populate variables
            // write index with collected base properties
            if (unsafe) writeUnsafe(dstdir, dstname);
            else writeSafe(dstdir, dstname);
            System.out.println(
                    "[" + APP + "] " + dstname + " indexed in " + ((System.nanoTime() - time) / 1000000) + " ms.");
        }
        System.out.println("Thats all folks.");
        return 0;
    }

    public String globNorm(String glob, File base) throws IOException
    {
        glob = glob.trim();
        if (glob.equals("")) {
            return null;
        }
        if (glob.startsWith("#")) {
            return null;
        }
        // File.separator regularisation needed
        if (File.separatorChar == '\\') {
            glob = glob.replaceAll("[/\\\\]", File.separator + File.separator);
        }
        else {
            glob = glob.replaceAll("[/\\\\]", File.separator);
        }
        if (!new File(glob).isAbsolute()) {
            File dir = base.getAbsoluteFile();
            if (glob.startsWith("." + File.separator)) {
                glob = glob.substring(2);
            }
            while (glob.startsWith(".." + File.separator)) {
                dir = dir.getParentFile();
                glob = glob.substring(3);
            }
            File f = new File(dir, glob);
            return f.getAbsolutePath();
        }
        else {
            return glob;
        }
    }

    /**
     * Parse properties to produce an alix lucene index
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

        String prop;
        String key;

        key = "name";
        prop = props.getProperty(key);
        if (prop != null) {
            dstname = prop;
        }

        // link to a separate file list
        key = "srcfile";
        prop = props.getProperty(key);
        if (prop != null) {
            File file = new File(prop);
            if (!file.isAbsolute()) file = new File(propsFile.getParentFile(), prop);
            if (!file.exists()) {
                throw new FileNotFoundException("File list <entry key=\"" + key + "\">" + prop
                        + "</entry>, resolved as " + file.getAbsolutePath());
            }
            File base = file.getCanonicalFile().getParentFile();
            List<String> lines = Files.readAllLines(file.toPath());
            for (int i = 0; i < lines.size(); i++) {
                String glob = lines.get(i);
                glob = globNorm(glob, base);
                Dir.include(paths, glob);
            }
        }
        // direct list
        else {
            String src = props.getProperty("src");

            if (src == null) throw new NoSuchFieldException(
                    "\n  [" + APP + "] " + propsFile + "\nan src entry is needed, to have path to index"
                            + "\n<entry key=\"src\">../corpus1/*.xml;../corpus2/*.xml</entry>");
            String[] blurf = src.split(" *[;] *|[\t ]*[\n\r]+[\t ]*");
            // resolve globs relative to the folder of the properties field
            final File base = propsFile.getCanonicalFile().getParentFile();
            for (String glob : blurf) {
                // System.out.println("[" + APP + "] process " + glob );
                glob = globNorm(glob, base);
                Dir.include(paths, glob);
            }
        }

        key = "exclude";
        prop = props.getProperty(key);
        if (prop != null) {
            // resolve globs relative to the folder of the properties field
            final File base = propsFile.getCanonicalFile().getParentFile();
            String[] globs = prop.split(" *[;] *|[\t ]*[\n\r]+[\t ]*");
            for (String glob : globs) {
                glob = globNorm(glob, base);
                Dir.exclude(paths, glob);
            }
        }
        
        key = "dicfile";
        prop = props.getProperty(key);
        if (prop != null) {
            File dicfile = new File(prop);
            if (!dicfile.isAbsolute()) dicfile = new File(propsFile.getParentFile(), prop);
            if (!dicfile.exists()) {
                throw new FileNotFoundException("Local dictionary file not found <entry key=\"" + key + "\">" + prop
                        + "</entry>, resolved as " + dicfile.getAbsolutePath());
            }
            FrDics.load(dicfile);
        }

        // set a local xsl to generate alix:document
        key = "xsl";
        prop = props.getProperty(key);
        if (prop != null) {
            File file = new File(prop);
            if (!file.isAbsolute()) file = new File(propsFile.getParentFile(), prop);
            if (!file.exists()) {
                throw new FileNotFoundException("XSLT file not found <entry key=\"" + key + "\">" + prop
                        + "</entry>, resolved as " + file.getAbsolutePath());
            }
            xsl = file.toString();
        }

        prop = props.getProperty("dstdir");
        if (prop != null) {
            dstdir = new File(prop);
            if (!dstdir.isAbsolute()) dstdir = new File(propsFile.getParentFile(), prop);
        }
        else {
            dstdir = propsFile.getParentFile();
        }

    }

    /**
     * Factor construction of old index name.
     * 
     * @param name An index name.
     * @return Name of an old index.
     */
    public static String nameOld(final String name)
    {
        return name + "_old";
    }

    /**
     * Factor construction of temp index name.
     * 
     * @param name An index name.
     * @return Name of temp index.
     */
    public static String nameTmp(final String name)
    {
        return name + "_tmp";
    }

    /**
     * 
     * @param name Name of the base.
     * @param path Path where to write the path index.
     * @throws Exception Errors in the XML parsing.
     */
    public void write(String name, Path path) throws Exception
    {
        Alix alix = Alix.instance(name, path, new AlixAnalyzer(), null);
        // Alix alix = Alix.instance(name, path, new StandardAnalyzer(), null);
        IndexWriter writer = alix.writer();
        XMLIndexer.index(writer, paths, threads, "tei", xsl);
        System.out.println("[" + APP + "] " + name + " Merging");
        writer.commit();
        writer.close(); // close lucene index before indexing rail (for coocs)
        // pre index text fields for co-occurrences, so that index could stay read only
        // by server
        Collection<String> fields = FieldInfos.getIndexedFields(alix.reader());
        for (String field : fields) {
            FieldInfo info = alix.info(field);
            IndexOptions options = info.getIndexOptions();
            // non text fields, facets
            if (options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
                    && options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
                // System.out.println("["+APP+"] "+name+". Field \""+field+"\" has no positions
                // indexed for cooc");
                continue;
            }

            System.out.println("[" + APP + "] " + name + ". Cooc indexation for field: " + field);
            alix.fieldRail(field);
        }
    }

    /**
     * A logic to write safely an index in a temp directory before affecting a
     * running index.
     * 
     * @param dstDir Destination parent file directory.
     * @param name   Name of the index to write.
     * @throws Exception Errors during XML process and Lucene indexation.
     */
    public void writeSafe(final File dstDir, final String name) throws Exception
    {

        // Use a tmp dir to not overwrite a working index on server
        final File tmpDir = new File(dstdir, nameTmp(name));
        if (!ask4rmdir(tmpDir)) return;
        File oldDir = new File(dstdir, nameOld(name));
        File theDir = new File(dstdir, name);
        /* Register thing to do at the end */
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run()
            {
                if (!tmpDir.exists()) return;
                System.out.println("[" + APP + "] ERROR Something went wrong, old index is kept.");
            }
        });
        write(name, tmpDir.toPath());
        /*
         * TimeZone tz = TimeZone.getTimeZone("UTC"); DateFormat df = new
         * SimpleDateFormat("yyyy-MM-dd_HH:mm:ss"); df.setTimeZone(tz);
         */
        // test if rename works
        File testDir = new File(dstDir, name + "_test");
        if (!tmpDir.renameTo(testDir)) {
            throw new IOException("\n[" + APP + "] Impossible to rename tmp index.");
        }
        if (theDir.exists()) {
            if (oldDir.exists()) Dir.rm(oldDir);
            theDir.renameTo(oldDir);
            System.out.println("[" + APP + "] For safety, you old index is preserved in folder :\n" + oldDir);
        }
        testDir.renameTo(theDir);
    }

    /**
     * Because a bug in Microsoft.Windows filesystem with Java, impossible to have
     * the same safe indexation like for unix.
     *
     * @param dstdir Parent destination file system directory.
     * @param name   Name of a lucene index.
     * @throws Exception XML or Lucene erros.
     */
    public void writeUnsafe(final File dstdir, final String name) throws Exception
    {
        File theDir = new File(dstdir, name);
        File oldDir = new File(dstdir, nameOld(name));
        File tmpDir = new File(dstdir, nameTmp(name));
        if (!ask4rmdir(tmpDir)) return;
        if (oldDir.exists()) {
            if (!oldDir.renameTo(tmpDir))
                throw new IOException("\n[" + APP + "] Impossible to rename old index to\n  " + tmpDir);
        }
        if (theDir.exists()) {
            if (!theDir.renameTo(oldDir))
                throw new IOException("\n[" + APP + "] Impossible to rename old index to\n  " + oldDir);
        }
        try {
            // only one thread
            // threads = 1;
            write(name, theDir.toPath());
        }
        catch (Exception e) {
            // try to restore old index
            Dir.rm(theDir);
            if (theDir.exists()) {
                System.out.println("\n[" + APP + "] Impossible to restore old index (filesystem pb)");
            }
            oldDir.renameTo(theDir);
            tmpDir.renameTo(oldDir);
            throw e;
        }
        // we are OK
        if (tmpDir.exists()) Dir.rm(tmpDir);
    }

    /**
     * Send index loading.
     * 
     * @param args Command line arguments.
     * @throws Exception XML or Lucene errors.
     */
    public static void main(String[] args) throws Exception
    {
        int exitCode = new CommandLine(new Load()).execute(args);
        System.exit(exitCode);
    }

}
