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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * File/directory utilities used by Alix CLI tools.
 *
 * <p>
 * Scope: Java 7 compatible; uses {@code java.nio.file} for traversal and glob matching.
 * </p>
 *
 * <h3>Glob conventions</h3>
 * <ul>
 * <li>Blank lines and lines starting with {@code #} are ignored (return {@code null}).</li>
 * <li>Globs follow the {@code FileSystem.getPathMatcher("glob:...")} syntax.</li>
 * <li>Relative globs can be normalized against a base directory.</li>
 * </ul>
 */
public final class Dir
{
    
    private Dir()
    {
        /* no instances */ }
        
    /**
     * Normalize a possibly-relative glob against a base file/directory.
     *
     * <p>
     * This is intended for config files where globs may be written relative to the config location.
     * </p>
     *
     * @param glob a glob string (may include wildcards).
     * @param base a file or directory used as resolution anchor; if a file, its parent is used.
     * @return an absolute, normalized glob string; or {@code null} if blank/comment.
     * @throws IOException if {@code base} has no parent or cannot be resolved.
     */
    public static String globNorm(String glob, File base) throws IOException
    {
        glob = sanitizeGlobLine(glob);
        if (glob == null)
            return null;
        
        final Path g = new File(glob).toPath();
        if (g.isAbsolute()) {
            return g.normalize().toString();
        }
        
        if (base == null) {
            // Fall back to current working directory.
            return new File(glob).toPath().toAbsolutePath().normalize().toString();
        }
        
        File anchor = base.isDirectory() ? base : base.getParentFile();
        if (anchor == null)
            throw new IOException("Cannot resolve glob against base with no parent: " + base);
        
        return anchor.toPath().toAbsolutePath().normalize().resolve(glob).normalize().toString();
    }
    
    /**
     * Expand a glob and append matching paths to {@code out}.
     *
     * <p>
     * Rules:
     * </p>
     * <ul>
     * <li>If {@code glob} points to an existing file, it is added directly.</li>
     * <li>Otherwise the filesystem is walked from a computed base directory, and matches are added.</li>
     * <li>By default, directories/files starting with '.' or '_' are skipped (common corpus hygiene).</li>
     * <li>Returned list is de-duplicated while preserving encounter order.</li>
     * </ul>
     *
     * @param out  list to populate (must be mutable).
     * @param glob glob pattern (absolute or relative).
     * @return {@code out} for chaining.
     * @throws IOException on traversal errors.
     */
    public static List<Path> include(List<Path> out, String glob) throws IOException
    {
        return include(out, glob, true);
    }
    
    /**
     * Variant of {@link #include(List, String)} allowing control of "hidden" pruning.
     *
     * @param out                 list to populate.
     * @param glob                glob pattern (absolute or relative).
     * @param skipDotOrUnderscore if true, prunes directories/files starting with '.' or '_'.
     */
    public static List<Path> include(List<Path> out, String glob, final boolean skipDotOrUnderscore) throws IOException
    {
        if (out == null)
            throw new IOException("out == null (need a mutable List<Path>)");
        glob = sanitizeGlobLine(glob);
        if (glob == null)
            return out;
        
        // If it is a concrete existing file, add it and return.
        final File direct = new File(glob);
        if (direct.exists() && direct.isFile()) {
            dedupAdd(out, direct.toPath());
            return out;
        }
        
        // Walk from the directory that precedes the first glob metacharacter.
        final Path baseDir = inferWalkBase(glob).toAbsolutePath().normalize();
        if (!Files.exists(baseDir)) {
            throw new IOException("Glob base directory does not exist: " + baseDir + " (glob=" + glob + ")");
        }
        
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + toMatcherPattern(glob));
        
        Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                if (!skipDotOrUnderscore)
                    return FileVisitResult.CONTINUE;
                Path name = dir.getFileName();
                if (name != null) {
                    String s = name.toString();
                    if (s.startsWith(".") || s.startsWith("_"))
                        return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (skipDotOrUnderscore) {
                    Path name = file.getFileName();
                    if (name != null) {
                        String s = name.toString();
                        if (s.startsWith(".") || s.startsWith("_"))
                            return FileVisitResult.CONTINUE;
                    }
                }
                if (matcher.matches(file))
                    dedupAdd(out, file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
            {
                // Indexing should usually be best-effort across large corpora.
                return FileVisitResult.CONTINUE;
            }
        });
        
        // Ensure de-dup stable ordering even if caller pre-populated the list.
        stableDedup(out);
        return out;
    }
    
    /**
     * Remove from {@code paths} any path matched by {@code glob}.
     *
     * @param paths list to mutate.
     * @param glob  glob pattern; blank/comment is ignored.
     * @return {@code paths}.
     * @throws IOException if {@code paths} is null.
     */
    public static List<Path> exclude(List<Path> paths, String glob) throws IOException
    {
        if (paths == null)
            throw new IOException("paths == null");
        glob = sanitizeGlobLine(glob);
        if (glob == null)
            return paths;
        
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + toMatcherPattern(glob));
        
        // Keep order; filter in-place.
        for (int i = paths.size() - 1; i >= 0; i--) {
            Path p = paths.get(i);
            if (p != null && matcher.matches(p))
                paths.remove(i);
        }
        return paths;
    }
    
    /**
     * Convenience: list files matching a glob.
     */
    public static List<Path> ls(String glob) throws IOException
    {
        return include(new ArrayList<Path>(), glob);
    }
    
    /**
     * Recursively delete a file or directory.
     *
     * @param path file/directory to delete
     * @return true if it existed and was deleted, false if it did not exist.
     */
    public static boolean rm(Path path) throws IOException
    {
        if (path == null || !Files.exists(path))
            return false;
        
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream)
                    rm(entry);
            }
        }
        Files.delete(path);
        return true;
    }
    
    /**
     * Recursively delete a file or directory (legacy {@link File} API).
     */
    public static boolean rm(File file)
    {
        if (file == null || !file.exists())
            return false;
        if (file.isFile())
            return file.delete();
        
        File[] ls = file.listFiles();
        if (ls != null) {
            for (File f : ls)
                rm(f);
        }
        return file.delete();
    }
    
    // ---------------- internal helpers ----------------
    
    private static String sanitizeGlobLine(String glob)
    {
        if (glob == null)
            return null;
        glob = glob.trim();
        if (glob.isEmpty())
            return null;
        if (glob.startsWith("#"))
            return null;
        return glob;
    }
    
    private static void stableDedup(List<Path> paths)
    {
        Set<Path> set = new LinkedHashSet<Path>(paths);
        paths.clear();
        paths.addAll(set);
    }
    
    private static void dedupAdd(List<Path> out, Path p)
    {
        if (p == null)
            return;
        // Small corpora: contains() is ok. If you expect millions, switch callers to a Set<Path>.
        if (!out.contains(p))
            out.add(p);
    }
    
    /**
     * Infer a directory to start walking, by cutting the glob at the first metacharacter and taking its parent.
     */
    private static Path inferWalkBase(String glob)
    {
        int cut = indexOfGlobMeta(glob);
        String prefix = (cut < 0) ? glob : glob.substring(0, cut);
        
        File f = new File(prefix);
        if (f.isDirectory())
            return f.toPath();
        
        File parent = f.getParentFile();
        if (parent != null)
            return parent.toPath();
        
        // Covers patterns like "*.xml" or "C:\*.xml" where parent may be null.
        return new File(".").toPath();
    }
    
    private static int indexOfGlobMeta(String s)
    {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '*':
                case '?':
                case '[':
                case '{':
                    return i;
                default:
                    // continue
            }
        }
        return -1;
    }
    
    /**
     * Normalize separators for the platform matcher. On Windows, backslash is both separator and glob escape,
     * so callers should prefer forward slashes in config; we still accept both.
     */
    private static String toMatcherPattern(String glob)
    {
        if (File.separatorChar == '\\') {
            // Convert / and \ runs to a single escaped backslash sequence for the glob syntax.
            // (Yes: "glob:" treats '\' as escape; the matcher expects literal separators.)
            return glob.replaceAll("[/\\\\]+", "\\\\\\\\");
        }
        // Unix-like: normalize any backslashes to '/' separator.
        return glob.replace('\\', '/');
    }
}