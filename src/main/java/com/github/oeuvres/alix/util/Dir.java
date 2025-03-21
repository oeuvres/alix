/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
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
package com.github.oeuvres.alix.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Static tools to deal with directories (and files). Kept in java 7.
 */
public class Dir
{
    static { // maybe useful to decode file names
        System.setProperty("file.encoding", "UTF-8");
    }
    
    /**
     * Avoid instantiation.
     */
    private Dir()
    {
        
    }

    /**
     * Resolve relative links for glob.
     * 
     * @param glob requested path as a glob.
     * @param base file from which resolve relative links.
     * @return an absolute glob.
     * @throws IOException file exceptions.
     */
    public static String globNorm(String glob, File base) throws IOException
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
     * Add files to a list with a glob.
     *
     * @param paths list of paths to populate.
     * @param glob pattern of file to append.
     * @return the list of paths completed.
     * @throws IOException file errors.
     */
    public static List<Path> include(List<Path> paths, final String glob) throws IOException
    {
        if (paths == null) {
            throw new IOException("List<Path> paths is null, a list is needed to add Path");
        }
        if (glob == null) {
            return paths;
        }
        // name encoding problem in linux WSL, with File or Path
        Path basedir = new File(glob.replaceFirst("[\\[\\*\\?\\{].*", "") + "DUMMY").getParentFile().toPath();
        File globFile = new File(glob);
        if (globFile.exists()) {
            paths.add(globFile.toPath());
            return paths;
        }
    
        String pattern = glob;
        if (File.separator.equals("\\")) { // for Windows
            pattern = new File(glob).toString().replaceAll("[/\\\\]+", "\\\\\\\\"); // yes all those '\' needed
        }
        final PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern); // new File(glob)
    
        Files.walkFileTree(basedir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException
            {
                String dirname = path.getFileName().toString();
                if (dirname.startsWith(".") || dirname.startsWith("_")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
    
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException
            {
                String filename = path.getFileName().toString();
                if (filename.startsWith(".") || filename.startsWith("_")) {
                    return FileVisitResult.CONTINUE;
                }
                if (pathMatcher.matches(path)) {
                    paths.add(path);
                }
                return FileVisitResult.CONTINUE;
            }
    
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
            {
                return FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    /**
     * Delete files from a list by glob.
     * 
     * @param paths list of files.
     * @param glob pattern to select files.
     * @return list of selected paths.
     * @throws IOException file errors.
     */
    static public  List<Path> exclude(final List<Path> paths, final String glob) throws IOException
    {
        if (glob == null) {
            return paths;
        }
        String pattern = glob;
        if (File.separator.equals("\\")) { // for Windows
            pattern = new File(glob).toString().replaceAll("[/\\\\]+", "\\\\\\\\"); // yes all those '\' needed
        }
        final PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        
        Iterator<Path> it = paths.iterator();
        while (it.hasNext()) {
            Path path = it.next();
            if (pathMatcher.matches(path)) {
                it.remove();
            }
        }
        return paths;
    }

    /**
     * List files by glob.
     * 
     * @param glob pattern to select files.
     * @return list of selected paths.
     * @throws IOException file errors.
     */
    public static List<Path> ls(final String glob) throws IOException
    {
        return include(new ArrayList<Path>(), glob);
    }

    /**
     * Delete a folder by path (use java.nio stream, should be faster then
     * {@link #rm(File)})
     * 
     * @param path file to delete.
     * @return true if exists and deleted, false otherwise.
     * @throws IOException file error.
     */
    public static boolean rm(Path path) throws IOException
    {
        if (!Files.exists(path))
            return false;
        if (Files.isDirectory(path)) {
            DirectoryStream<Path> stream = Files.newDirectoryStream(path);
            for (Path entry : stream) {
                rm(entry);
            }
            stream.close();
        }
        Files.delete(path);
        return true;
    }

    /**
     * Delete a folder by File object (maybe a bit slow for lots of big folders).
     * 
     * @param file folder to delete.
     * @return true if exists and deleted, false otherwise.
     */
    public static boolean rm(File file)
    {
        if (!file.exists())
            return false;
        if (file.isFile())
            return file.delete();
        File[] ls = file.listFiles();
        if (ls != null) {
            for (File f : ls) {
                rm(f);
            }
        }
        return file.delete();
    }

    /**
     * Private collector of files to index.
     * 
     * @param dir
     * @param pattern
     * @return
     */
    @SuppressWarnings("unused")
    private static void collect(File dir, PathMatcher matcher, int depth, final List<File> files)
    {
        File[] ls = dir.listFiles();
        for (File entry : ls) {
            String name = entry.getName();
            if (name.startsWith("."))
                continue; // ? find a way to work around ?
            else if (entry.isDirectory()) {
                if (depth > 1 || depth < 0)
                    collect(entry, matcher, depth - 1, files);
            } else if (!matcher.matches(entry.toPath()))
                continue;
            else
                files.add(entry);
        }
    }
}
