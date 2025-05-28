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
package com.github.oeuvres.alix.lucene.index;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;

import org.apache.lucene.index.IndexWriter;

import com.github.oeuvres.alix.lucene.analysis.AnalyzerAlix;
import com.github.oeuvres.alix.util.Dir;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Load an XML/TEI corpus in a custom Lucene index for Alix.
 */
@Command(name = "com.github.oeuvres.alix.cli.Load", description = "Load an XML/TEI corpus in a custom Lucene index for Alix.")
public class Load  extends Cli implements Callable<Integer>
{
    /** Prefix for log lines. */
    public static String APP = "Alix";
    @Option(names = { "-u", "--unsafe" }, description = "For windows filesystem, no temp lucene index")
    boolean unsafe;
    @Option(names = { "-t", "--threads" }, description = "Number of threads for indexation")
    int threads;
    /** Parent directory for lucene indexes */
    File lucenedir;
    
    /**
     * Default constructor.
     */
    public Load()
    {
        super();
    }

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
        /*
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
            if (unsafe) writeUnsafe(lucenedir, dstname);
            else writeSafe(lucenedir, dstname);
            System.out.println(
                    "[" + APP + "] " + dstname + " indexed in " + ((System.nanoTime() - time) / 1000000) + " ms.");
        } */
        long time = System.nanoTime();
        properties(conf); // populate variables
        // write index with collected base properties
        if (unsafe) writeUnsafe(lucenedir, properties.getProperty("name"));
        else writeSafe(lucenedir, properties.getProperty("name"));
        System.out.println(
                "[" + APP + "] " + properties.getProperty("name") + " indexed in " + ((System.nanoTime() - time) / 1000000) + " ms.");
        System.out.println("Thats all folks.");
        return 0;
    }

    public void properties(File propsFile) throws NoSuchFieldException, IOException
    {
        parse(propsFile);
        List<String> globs = globs("lucenedir");
        if (globs.size() > 0) {
            lucenedir = new File(globs.getFirst());
            System.out.println(lucenedir);
        }
        else {
            lucenedir = propsFile.getParentFile();
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
     * Write documents to a lucene index.
     * 
     * @param name Name of the base.
     * @param path Path where to write the path index.
     * @throws Exception Unknowns in the XML parsing.
     */
    public void write(String name, Path path) throws Exception
    {
        IndexWriter writer = AlixWriter.writer(path, new AnalyzerAlix());
        XMLIndexer.index(writer, paths, xsl);
        System.out.println("[" + APP + "] " + name + " Merging");
        writer.commit();
        writer.close(); // close lucene index before indexing rail (for coocs)
        // co-occurrences indexation requires searcher OK, see later
        /*
        IndexReader reader = DirectoryReader.open(writer);
        FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(reader);
        Collection<String> fields = FieldInfos.getIndexedFields(reader);
        for (String field : fields) {
            FieldInfo info = fieldInfos.fieldInfo(field);
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
        */
    }

    /**
     * A logic to write safely an index in a temp directory before affecting a
     * running index.
     * 
     * @param dstDir Destination parent file directory.
     * @param name   Name of the index to write.
     * @throws Exception Unknowns during XML process and Lucene indexation.
     */
    public void writeSafe(final File dstDir, final String name) throws Exception
    {

        // Use a tmp dir to not overwrite a working index on server
        final File tmpDir = new File(lucenedir, nameTmp(name));
        if (!ask4rmdir(tmpDir)) return;
        File oldDir = new File(lucenedir, nameOld(name));
        File theDir = new File(lucenedir, name);
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
     * @param lucenedir Parent destination file system directory.
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
