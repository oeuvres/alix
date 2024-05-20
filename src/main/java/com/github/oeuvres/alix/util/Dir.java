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
import java.util.List;

/**
 * Static tools to deal with directories (and files). Kept in java 7.
 */
public class Dir
{
    static { // maybe useful to decode file names, but has been not efficient 
        System.setProperty("file.encoding", "UTF-8");
    }

    /**
     * Delete a folder by path (use java.nio stream, should be faster then
     * {@link #rm(File)})
     */
    public static boolean rm(Path path) throws IOException
    {
        if (!Files.exists(path))
            return false;
        if (Files.isDirectory(path)) {
            DirectoryStream<Path> stream = Files.newDirectoryStream(path);
            for (Path entry : stream)
                rm(entry);
        }
        Files.delete(path);
        return true;
    }

    /**
     * Delete a folder by File object (maybe a bit slow for lots of big folders)
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
     * List files by glob
     * 
     * @param glob
     * @return
     * @throws IOException Lucene errors.
     */
    public static List<Path> ls(final String glob) throws IOException
    {
        return ls(glob, new ArrayList<Path>());
    }

    /**
     * List files with glob
     * 
     * @param glob
     * @param files
     * @return
     * @throws IOException Lucene errors.
     */
    public static List<Path> ls(final String glob, List<Path> paths) throws IOException
    {
        if (paths == null) {
            throw new IOException("List<Path> paths is null, a list is needed to add Path");
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
