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
import java.util.regex.Pattern;

/**
 * Filesystem utilities with cross-platform glob handling.
 *
 * <ul>
 *   <li>Globs may be absolute or relative.</li>
 *   <li>Relative globs can be normalized against a config file or directory.</li>
 *   <li>Glob matching is done on absolute normalized paths.</li>
 *   <li>Expansion walks from the longest non-glob prefix to avoid scanning the whole filesystem.</li>
 *   <li>Blank lines and comment lines are ignored.</li>
 * </ul>
 * 
 * <p>Matching is done on absolute normalized path strings converted to '/'.
 * This avoids Windows backslash issues with NIO PathMatcher("glob:...").</p>
 */
public final class Dir {
    private Dir() {
    }

    /**
     * Normalize a possibly-relative glob against a base file/directory.
     *
     * <p>
     * Intended for config files where globs may be written relative to the config location.
     * Returned glob is absolute and normalized, with '/' separators for stable matching.
     * </p>
     *
     * @param glob a glob string (may include wildcards).
     * @param base a file or directory used as resolution anchor; if a file, its parent is used.
     * @return an absolute, normalized glob string using '/' separators; or {@code null} if blank/comment.
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

        String abs;
        if (isAbsoluteGlob(g)) {
            abs = normalizeGlobLexically(g);
        } else {
            abs = normalizeGlobLexically(anchor.toString() + File.separator + g);
        }
        return toSlash(abs);
    }

    /**
     * Equivalent to {@code include(new ArrayList<Path>(), glob, true)}.
     */
    public static List<Path> ls(String glob) throws IOException {
        return include(new ArrayList<Path>(), glob, true);
    }

    
    /**
     * Equivalent to {@code include(out, glob, true)}.
     */
    public static List<Path> include(List<Path> out, String glob) throws IOException {
        return include(out, glob, true);
    }

    /**
     * Expand a glob and append matching paths to {@code out}.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>If {@code glob} points to an existing file, it is added directly.</li>
     *   <li>Otherwise the filesystem is walked from a computed base directory, and matches are added.</li>
     *   <li>By default, directories/files starting with '.' or '_' are skipped.</li>
     *   <li>Returned list is de-duplicated while preserving encounter order.</li>
     * </ul>
     *
     * @param out list to populate (must be mutable).
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

        Path literal = tryLiteralPath(g);
        if (literal != null && Files.isRegularFile(literal)) {
            seen.add(literal.toAbsolutePath().normalize());
            out.clear();
            out.addAll(seen);
            return out;
        }

        final Pattern regex = globToRegex(g);
        final Path walkRoot = computeWalkRoot(g);

        if (walkRoot == null || !Files.exists(walkRoot)) {
            out.clear();
            out.addAll(seen);
            return out;
        }

        Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(walkRoot) && skipDotOrUnderscore && startsWithDotOrUnderscore(dir.getFileName())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String s = toSlash(dir.toAbsolutePath().normalize().toString());
                if (regex.matcher(s).matches()) {
                    seen.add(dir.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (skipDotOrUnderscore && startsWithDotOrUnderscore(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                String s = toSlash(file.toAbsolutePath().normalize().toString());
                if (regex.matcher(s).matches()) {
                    seen.add(file.toAbsolutePath().normalize());
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
     * @param glob glob pattern; blank/comment is ignored.
     * @return {@code paths}.
     * @throws IOException if {@code paths} is null.
     */
    public static List<Path> exclude(List<Path> paths, String glob) throws IOException {
        Objects.requireNonNull(paths, "paths");

        String g = globNorm(glob, new File("."));
        if (g == null) return paths;

        Path literal = tryLiteralPath(g);
        Pattern regex = globToRegex(g);

        paths.removeIf(p -> {
            if (p == null) return true;
            Path abs = p.toAbsolutePath().normalize();
            if (literal != null && abs.equals(literal)) return true;
            String s = toSlash(abs.toString());
            return regex.matcher(s).matches();
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

    private static String stripConfigSyntax(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("#")) return null;
        if (t.startsWith("//")) return null;
        return t;
    }

    private static boolean isAbsoluteGlob(String glob) {
        if (glob == null || glob.isEmpty()) return false;
        if (glob.startsWith("/") || glob.startsWith("\\")) return true;
        return glob.length() >= 3
            && Character.isLetter(glob.charAt(0))
            && glob.charAt(1) == ':'
            && isSep(glob.charAt(2));
    }

    /**
     * Lexical normalization only. Keeps glob metacharacters intact.
     */
    private static String normalizeGlobLexically(String input) {
        String s = input.replace('\\', '/');

        String prefix = "";
        int start = 0;

        // UNC //server/share/...
        if (s.startsWith("//")) {
            prefix = "//";
            start = 2;
        }
        // Drive absolute C:/...
        else if (s.length() >= 3
            && Character.isLetter(s.charAt(0))
            && s.charAt(1) == ':'
            && s.charAt(2) == '/') {
            prefix = s.substring(0, 3); // "C:/"
            start = 3;
        }
        // Unix root
        else if (s.startsWith("/")) {
            prefix = "/";
            start = 1;
        }

        String rest = s.substring(start);
        String[] raw = rest.split("/+");
        Deque<String> stack = new ArrayDeque<>();

        for (String part : raw) {
            if (part.isEmpty() || ".".equals(part)) continue;
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

        String joined = String.join("/", stack);
        if (prefix.isEmpty()) return joined;
        if (joined.isEmpty()) return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return prefix.endsWith("/") ? prefix + joined : prefix + "/" + joined;
    }

    private static Path tryLiteralPath(String glob) {
        if (containsMeta(glob)) return null;
        try {
            return Path.of(fromSlash(glob)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static Path computeWalkRoot(String absoluteGlob) {
        String prefix = nonGlobPrefixDir(absoluteGlob);
        if (prefix == null || prefix.isEmpty()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        Path p;
        try {
            p = Path.of(fromSlash(prefix)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }

        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        if (p == null) return null;
        return Files.isDirectory(p) ? p : p.getParent();
    }

    private static String nonGlobPrefixDir(String glob) {
        int firstMeta = firstMetaIndex(glob);
        if (firstMeta < 0) {
            Path p = Path.of(fromSlash(glob)).toAbsolutePath().normalize();
            Path parent = p.getParent();
            return toSlash((parent != null ? parent : p).toString());
        }

        int slash = glob.lastIndexOf('/', firstMeta);
        if (slash < 0) {
            if (isAbsoluteGlob(glob)) {
                if (glob.startsWith("//")) return "//";
                if (glob.length() >= 3 && Character.isLetter(glob.charAt(0)) && glob.charAt(1) == ':' && glob.charAt(2) == '/') {
                    return glob.substring(0, 3);
                }
                if (glob.startsWith("/")) return "/";
            }
            return toSlash(Path.of("").toAbsolutePath().normalize().toString());
        }

        return glob.substring(0, slash);
    }

    private static boolean containsMeta(String s) {
        return firstMetaIndex(s) >= 0;
    }

    private static int firstMetaIndex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') return i;
        }
        return -1;
    }

    private static boolean startsWithDotOrUnderscore(Path name) {
        if (name == null) return false;
        String s = name.toString();
        return !s.isEmpty() && (s.charAt(0) == '.' || s.charAt(0) == '_');
    }

    private static boolean isSep(char c) {
        return c == '/' || c == '\\';
    }

    private static String toSlash(String s) {
        return s.replace('\\', '/');
    }

    private static String fromSlash(String s) {
        return File.separatorChar == '/' ? s : s.replace('/', '\\');
    }

    /**
     * Minimal glob -> regex conversion on slash-normalized absolute paths.
     *
     * Supported:
     * - *  : any chars except '/'
     * - ** : any chars including '/'
     * - ?  : one char except '/'
     * - [abc], [a-z], [!abc]
     * - {a,b,c}
     *
     * This is enough for normal filesystem use and your tests.
     */
    private static Pattern globToRegex(String glob) {
        StringBuilder rx = new StringBuilder(glob.length() * 2);
        rx.append("^");

        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);

            switch (c) {
                case '*': {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        rx.append(".*");
                        i += 2;
                    } else {
                        rx.append("[^/]*");
                        i++;
                    }
                    break;
                }
                case '?': {
                    rx.append("[^/]");
                    i++;
                    break;
                }
                case '[': {
                    int j = i + 1;
                    if (j < glob.length() && glob.charAt(j) == '!') j++;
                    if (j < glob.length() && glob.charAt(j) == ']') j++;
                    while (j < glob.length() && glob.charAt(j) != ']') j++;
                    if (j >= glob.length()) {
                        rx.append("\\[");
                        i++;
                    } else {
                        String cls = glob.substring(i + 1, j);
                        if (cls.startsWith("!")) cls = "^" + cls.substring(1);
                        cls = cls.replace("\\", "\\\\");
                        rx.append("[").append(cls).append("]");
                        i = j + 1;
                    }
                    break;
                }
                case '{': {
                    int depth = 1;
                    int j = i + 1;
                    while (j < glob.length() && depth > 0) {
                        char cj = glob.charAt(j);
                        if (cj == '{') depth++;
                        else if (cj == '}') depth--;
                        j++;
                    }
                    if (depth != 0) {
                        rx.append("\\{");
                        i++;
                    } else {
                        String body = glob.substring(i + 1, j - 1);
                        String[] alts = body.split(",");
                        rx.append("(?:");
                        for (int k = 0; k < alts.length; k++) {
                            if (k > 0) rx.append("|");
                            rx.append(Pattern.quote(alts[k]));
                        }
                        rx.append(")");
                        i = j;
                    }
                    break;
                }
                default: {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) rx.append('\\');
                    rx.append(c);
                    i++;
                }
            }
        }

        rx.append("$");
        return Pattern.compile(rx.toString());
    }
}

