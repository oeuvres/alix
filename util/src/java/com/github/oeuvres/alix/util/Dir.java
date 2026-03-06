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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Small filesystem utilities with cross-platform glob handling.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>Globs may be absolute or relative.</li>
 *   <li>Relative globs can be normalized against a config file or directory.</li>
 *   <li>Glob matching is done on absolute normalized paths.</li>
 *   <li>Expansion walks from the longest non-glob prefix to avoid scanning the whole filesystem.</li>
 *   <li>Blank lines and comment lines are ignored.</li>
 * </ul>
 */
public final class Dir {
    private Dir() {
    }

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
    public static String globNorm(String glob, File base) throws IOException {
        String g = stripConfigSyntax(glob);
        if (g == null) return null;

        final Path anchor;
        if (base == null) {
            anchor = Path.of("").toAbsolutePath().normalize();
        } else {
            File absBase = base.getAbsoluteFile();
            if (absBase.isDirectory()) {
                anchor = absBase.toPath().toAbsolutePath().normalize();
            } else {
                File parent = absBase.getParentFile();
                if (parent == null) {
                    throw new IOException("Base file has no parent: " + base);
                }
                anchor = parent.toPath().toAbsolutePath().normalize();
            }
        }

        String normalized = isAbsoluteGlob(g)
            ? normalizeGlobString(g)
            : normalizeGlobString(anchor.toString() + File.separator + g);

        return normalized;
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
    public static List<Path> include(List<Path> out, String glob) throws IOException {
        return include(out, glob, true);
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
     * <li>Returned list is de-duplicated while preserving encounter order.</li>
     * </ul>
     *
     * @param out  list to populate (must be mutable).
     * @param glob glob pattern (absolute or relative).
     * @param skipDotOrUnderscore if true, prunes directories/files starting with '.' or '_'.
     * @return {@code out} for chaining.
     * @throws IOException on traversal errors.
     */
    public static List<Path> include(List<Path> out, String glob, final boolean skipDotOrUnderscore) throws IOException {
        Objects.requireNonNull(out, "out");

        String g = globNorm(glob, new File("."));
        if (g == null) return out;

        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (Path p : out) {
            if (p != null) seen.add(p.toAbsolutePath().normalize());
        }

        Path direct = tryLiteralPath(g);
        if (direct != null && Files.isRegularFile(direct)) {
            seen.add(direct.toAbsolutePath().normalize());
            out.clear();
            out.addAll(seen);
            return out;
        }

        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + g);
        final Path walkRoot = computeWalkRoot(g);

        if (!Files.exists(walkRoot)) {
            out.clear();
            out.addAll(seen);
            return out;
        }

        Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(walkRoot) && skipDotOrUnderscore && startsWithDotOrUnderscore(dir.getFileName())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (matcher.matches(dir.toAbsolutePath().normalize())) {
                    seen.add(dir.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (skipDotOrUnderscore && startsWithDotOrUnderscore(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                Path abs = file.toAbsolutePath().normalize();
                if (matcher.matches(abs)) {
                    seen.add(abs);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        out.clear();
        out.addAll(seen);
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
    public static List<Path> exclude(List<Path> paths, String glob) throws IOException {
        Objects.requireNonNull(paths, "paths");

        String g = globNorm(glob, new File("."));
        if (g == null) return paths;

        Path literal = tryLiteralPath(g);
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + g);

        paths.removeIf(p -> {
            if (p == null) return true;
            Path abs = p.toAbsolutePath().normalize();
            if (literal != null && abs.equals(literal)) return true;
            return matcher.matches(abs);
        });

        return paths;
    }

    /**
     * Recursively delete a file or directory.
     *
     * @param path file/directory to delete
     * @return true if it existed and was deleted, false if it did not exist.
     */
    public static boolean rm(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return false;

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Returns null for blank/comment lines.
     * Supports leading/trailing whitespace and trailing comments introduced by '#'.
     * This is intentionally conservative: a line starting with '#' or '//' is ignored.
     */
    private static String stripConfigSyntax(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("#")) return null;
        if (t.startsWith("//")) return null;
        return t;
    }

    /**
     * Absolute detection for both Unix and Windows globs.
     */
    private static boolean isAbsoluteGlob(String glob) {
        if (glob == null || glob.isEmpty()) return false;

        // Unix absolute
        if (glob.startsWith("/")) return true;

        // Windows UNC: \\server\share\...
        if (glob.startsWith("\\\\") || glob.startsWith("//")) return true;

        // Windows drive absolute: C:\... or C:/...
        return glob.length() >= 3
            && Character.isLetter(glob.charAt(0))
            && glob.charAt(1) == ':'
            && isSep(glob.charAt(2));
    }

    /**
     * Normalize separators and lexical "." / ".." segments while preserving glob chars.
     *
     * <p>This does not touch wildcard semantics. It only normalizes the path string.</p>
     */
    private static String normalizeGlobString(String input) {
        final char sep = File.separatorChar;
        String s = input.replace('\\', sep).replace('/', sep);

        String prefix = "";
        int start = 0;

        // UNC
        if (s.length() >= 2 && isSep(s.charAt(0)) && isSep(s.charAt(1))) {
            prefix = "" + sep + sep;
            start = 2;
        }
        // Windows drive root
        else if (s.length() >= 3
            && Character.isLetter(s.charAt(0))
            && s.charAt(1) == ':'
            && isSep(s.charAt(2))) {
            prefix = s.substring(0, 2) + sep;
            start = 3;
        }
        // Windows drive-relative form: C:foo
        else if (s.length() >= 2
            && Character.isLetter(s.charAt(0))
            && s.charAt(1) == ':') {
            prefix = s.substring(0, 2);
            start = 2;
        }
        // Unix root
        else if (s.length() >= 1 && isSep(s.charAt(0))) {
            prefix = "" + sep;
            start = 1;
        }

        String rest = s.substring(start);
        String[] raw = rest.split(Patterns.SEP_CLASS, -1);
        Deque<String> stack = new ArrayDeque<>();

        for (String part : raw) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.peekLast())) {
                    stack.removeLast();
                } else if (prefix.isEmpty()) {
                    stack.addLast(part);
                }
            } else {
                stack.addLast(part);
            }
        }

        String joined = String.join(String.valueOf(sep), stack);

        if (prefix.isEmpty()) return joined;
        if (joined.isEmpty()) return prefix;
        if (prefix.endsWith(String.valueOf(sep))) return prefix + joined;
        return prefix + sep + joined;
    }

    /**
     * Longest non-glob existing ancestor used as walk root.
     */
    private static Path computeWalkRoot(String absoluteGlob) {
        String prefix = nonGlobPrefixDir(absoluteGlob);
        Path p = Path.of(prefix).toAbsolutePath().normalize();

        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        if (p == null) {
            return Path.of("").toAbsolutePath().getRoot();
        }
        return Files.isDirectory(p) ? p : p.getParent();
    }

    /**
     * Returns the directory prefix before the first glob meta.
     * Example:
     * D:\code\piaget_xml\piaget1946*.xml -> D:\code\piaget_xml
     */
    private static String nonGlobPrefixDir(String glob) {
        int firstMeta = firstMetaIndex(glob);
        if (firstMeta < 0) {
            Path p = Path.of(glob).toAbsolutePath().normalize();
            Path parent = p.getParent();
            return (parent != null ? parent : p).toString();
        }

        int slash = lastSeparatorBefore(glob, firstMeta);
        if (slash < 0) {
            if (isAbsoluteGlob(glob)) {
                Path root = absoluteRootOf(glob);
                return root.toString();
            }
            return Path.of("").toAbsolutePath().normalize().toString();
        }

        String prefix = glob.substring(0, slash);
        if (prefix.isEmpty()) {
            Path root = absoluteRootOf(glob);
            return root.toString();
        }
        return normalizeGlobString(prefix);
    }

    /**
     * Try to interpret as literal path only when there is no glob meta.
     */
    private static Path tryLiteralPath(String glob) {
        if (containsMeta(glob)) return null;
        try {
            return Path.of(glob).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean containsMeta(String s) {
        return firstMetaIndex(s) >= 0;
    }

    private static int firstMetaIndex(String s) {
        boolean inClass = false;
        boolean inGroup = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (c == '{') inGroup = true;
            else if (c == '}') inGroup = false;

            if (c == '*' || c == '?') return i;
            if (c == '[' || c == '{') return i;

            // no special escaping logic here:
            // on Windows '\' is a separator, not treated as an escape by this scanner.
            if (inClass || inGroup) {
                // already handled by return above on opening bracket
            }
        }
        return -1;
    }

    private static int lastSeparatorBefore(String s, int endExclusive) {
        for (int i = endExclusive - 1; i >= 0; i--) {
            if (isSep(s.charAt(i))) return i;
        }
        return -1;
    }

    private static boolean isSep(char c) {
        return c == '/' || c == '\\';
    }

    private static boolean startsWithDotOrUnderscore(Path name) {
        if (name == null) return false;
        String s = name.toString();
        return !s.isEmpty() && (s.charAt(0) == '.' || s.charAt(0) == '_');
    }

    private static Path absoluteRootOf(String s) {
        String n = normalizeGlobString(s);
        Path p;
        try {
            p = Path.of(n);
        } catch (InvalidPathException e) {
            p = Path.of("").toAbsolutePath();
        }

        Path abs = p.isAbsolute() ? p : p.toAbsolutePath();
        Path root = abs.getRoot();
        if (root != null) return root;

        Path cwd = Path.of("").toAbsolutePath().normalize();
        return Objects.requireNonNullElse(cwd.getRoot(), cwd);
    }

    private static final class Patterns {
        private static final String SEP_CLASS = "[/\\\\]+";
    }
}