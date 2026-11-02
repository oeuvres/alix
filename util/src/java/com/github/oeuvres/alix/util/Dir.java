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
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Filesystem utilities: path manipulation, cross-platform glob handling,
 * and directory operations.
 *
 * <h2>Glob handling</h2>
 * <ul>
 *   <li>Globs may be absolute or relative; relative globs are resolved
 *       against the current directory, or against a config file or
 *       directory with {@link #globNorm(String, Path)}.</li>
 *   <li>Glob matching is done on absolute normalized paths with
 *       {@code '/'} separators. This avoids Windows backslash issues with
 *       {@code NIO PathMatcher("glob:...")}.</li>
 *   <li>Glob and candidate paths are compared in Unicode NFC, so a glob
 *       typed {@code Émile*.xml} matches a file whose name was stored
 *       decomposed (NFD), as macOS HFS+ does.</li>
 *   <li>Expansion walks from the longest non-glob prefix, no deeper than
 *       the number of remaining glob segments unless the glob contains
 *       {@code **}.</li>
 *   <li>A glob without metacharacters that names a directory stands for
 *       the whole subtree: {@link #include(List, String, boolean)} expands
 *       it to the files below, {@link #exclude(List, String)} removes
 *       everything below.</li>
 *   <li>{@code ..} is collapsed lexically; a {@code ..} that follows a
 *       wildcard segment (as in {@code a/**}{@code /../b}) is rejected,
 *       because it cannot be resolved without the filesystem.</li>
 *   <li>Blank lines and comment lines ({@code #}, {@code //}) are ignored;
 *       paths of the form {@code //server/share} are treated as UNC paths.</li>
 * </ul>
 *
 * <p>Supported metacharacters:</p>
 * <ul>
 *   <li>{@code *}  — any characters except {@code '/'}</li>
 *   <li>{@code **} — any characters including {@code '/'}</li>
 *   <li>{@code **}{@code /} — zero or more complete directory levels</li>
 *   <li>{@code ?}  — one character except {@code '/'}</li>
 *   <li>{@code [abc]}, {@code [a-z]}, {@code [!abc]} — never match {@code '/'}</li>
 *   <li><code>{a,b,c}</code> — alternatives, which may themselves contain
 *       metacharacters and nested braces</li>
 * </ul>
 */
public final class Dir
{
    /**
     * Static utility class, not instantiable.
     */
    private Dir() {
    }

    /**
     * Remove from {@code paths} every path matched by the given glob, and
     * every path below a matched path.
     *
     * <p>
     * A path is removed when the path itself or one of its ancestors
     * matches, so {@code exclude(paths, "corpus/drafts")} and
     * {@code exclude(paths, "corpus/draft*")} also remove the files inside
     * the matched directories. The test is purely lexical: the filesystem
     * is not consulted. Null entries are removed. The glob is normalized
     * against the current directory; blank or comment globs leave
     * {@code paths} unchanged.
     * </p>
     *
     * @param paths mutable list to filter in place
     * @param glob  glob pattern (absolute or relative); blank/comment ignored
     * @return {@code paths}, for chaining
     * @throws IOException not thrown by the current implementation;
     *         declared for source compatibility
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     * @throws NullPointerException if {@code paths} is null
     */
    public static List<Path> exclude(List<Path> paths, String glob) throws IOException
    {
        Objects.requireNonNull(paths, "paths");

        String g = globNormAgainstDir(glob, Path.of(""));
        if (g == null) return paths;

        Path literal = tryLiteralPath(g);
        Pattern regex = globToRegex(g, true);

        paths.removeIf(p -> {
            if (p == null) return true;
            Path abs = p.toAbsolutePath().normalize();
            if (literal != null && abs.startsWith(literal)) return true;
            String s = nfc(toSlash(abs.toString()));
            return regex.matcher(s).matches();
        });

        return paths;
    }

    /**
     * Return the file extension (without the leading dot), or the empty
     * string if the filename has no extension.
     *
     * <p>
     * A dot at position 0 is not treated as an extension separator
     * (e.g. {@code .gitignore} returns {@code ""}).
     * </p>
     *
     * <pre>
     * Dir.extension(Path.of("report.pdf"))     → "pdf"
     * Dir.extension(Path.of("archive.tar.gz")) → "gz"
     * Dir.extension(Path.of("Makefile"))       → ""
     * Dir.extension(Path.of(".gitignore"))     → ""
     * </pre>
     *
     * @param path a file path (only the last component is examined)
     * @return the extension without dot, or {@code ""} if none
     * @throws NullPointerException if {@code path} is null or has no filename
     */
    public static String extension(final Path path)
    {
        final String name = path.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(dot + 1) : "";
    }

    /**
     * Return the lexicographically first regular file in a directory matching
     * a filename glob (direct children only).
     *
     * <p>
     * Ordering is case-sensitive and uses {@link String#compareTo(String)}
     * on the raw filenames; matching compares the glob and the filename in
     * Unicode NFC. Names beginning with {@code '.'} or {@code '_'} are
     * skipped, as are directories. The glob must not contain directory
     * separators. Blank and comment globs return {@code null}.
     * </p>
     *
     * @param dir directory to search
     * @param glob filename glob, such as {@code "*.xml"}
     * @return absolute normalized path of the first matching regular file,
     *         or {@code null} if none matches
     * @throws IOException if the directory cannot be listed
     * @throws IllegalArgumentException if {@code glob} contains a separator
     * @throws NullPointerException if {@code dir} is null
     */
    public static Path firstFile(Path dir, String glob) throws IOException
    {
        Objects.requireNonNull(dir, "dir");
        String g = stripConfigSyntax(glob);
        if (g == null) return null;
        if (g.indexOf('/') >= 0 || g.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Expected filename glob: " + glob);
        }

        Pattern regex = globToRegex(g, false);
        Path first = null;
        String firstName = null;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir.toAbsolutePath().normalize())) {
            for (Path file : files) {
                Path namePath = file.getFileName();
                if (startsWithDotOrUnderscore(namePath) || !Files.isRegularFile(file)) continue;
                String name = namePath.toString();
                if (regex.matcher(nfc(name)).matches()
                        && (firstName == null || name.compareTo(firstName) < 0)) {
                    first = file;
                    firstName = name;
                }
            }
        }
        return first;
    }

    /**
     * Equivalent to {@link #globNorm(String, Path)} with
     * {@code base.toPath()}.
     *
     * @param glob a glob string (may include wildcards)
     * @param base a file or directory used as resolution anchor, or
     *             {@code null} for the current working directory
     * @return an absolute, normalized glob string with {@code '/'} separators,
     *         or {@code null} if the input is blank or a comment line
     * @throws IOException if {@code base} is not a directory and has no parent
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     * @see #globNorm(String, Path)
     */
    public static String globNorm(String glob, File base) throws IOException
    {
        return globNorm(glob, (base == null) ? null : base.toPath());
    }

    /**
     * Normalize a possibly-relative glob against a base file or directory.
     *
     * <p>
     * Intended for configuration files where globs are written relative
     * to the config location. The returned glob is absolute and lexically
     * normalized, with {@code '/'} separators for stable matching. Unicode
     * normalization is not applied here; it happens at match time.
     * </p>
     *
     * @param glob a glob string (may include wildcards)
     * @param base a file or directory used as resolution anchor;
     *             if an existing directory, it is used as is;
     *             otherwise (regular file or nonexistent) its parent is used;
     *             if {@code null}, the current working directory is used
     * @return an absolute, normalized glob string with {@code '/'} separators,
     *         or {@code null} if the input is blank or a comment line
     * @throws IOException if {@code base} is not a directory and has no parent
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     */
    public static String globNorm(String glob, Path base) throws IOException
    {
        if (stripConfigSyntax(glob) == null) return null;

        final Path anchor;
        if (base == null) {
            anchor = Path.of("").toAbsolutePath().normalize();
        } else {
            Path absBase = base.toAbsolutePath();
            if (Files.isDirectory(absBase)) {
                anchor = absBase.normalize();
            } else {
                Path parent = absBase.getParent();
                if (parent == null) {
                    throw new IOException("Base file has no parent: " + base);
                }
                anchor = parent.normalize();
            }
        }
        return globNormAgainstDir(glob, anchor);
    }

    /**
     * Expand a glob and merge the matching files into {@code out}.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Only files are returned, never directories. A symbolic link is
     *       returned when its target is not a directory.</li>
     *   <li>If the glob has no metacharacters and names an existing regular
     *       file, that file is added directly, without walking.</li>
     *   <li>If the glob has no metacharacters and names an existing
     *       directory, it is read as {@code dir/**}: all files below.</li>
     *   <li>Otherwise the filesystem is walked from the longest non-glob
     *       prefix, no deeper than the number of remaining glob segments
     *       unless the glob contains {@code **}.</li>
     *   <li>When {@code skipHidden} is {@code true}, files and directories
     *       below the walk root whose name starts with {@code '.'} or
     *       {@code '_'} are skipped (directories with their subtree).
     *       Components of the non-glob prefix are not tested.</li>
     *   <li>Symbolic links to directories are not followed.</li>
     * </ul>
     *
     * <p>
     * {@code out} is rewritten, not only appended to: its existing entries
     * are converted to absolute normalized paths, null entries are dropped,
     * and duplicates are suppressed, preserving first-encounter order. New
     * matches follow the existing entries in walk order, which is not
     * sorted.
     * </p>
     *
     * @param out        mutable list to populate
     * @param glob       glob pattern (absolute or relative); blank/comment
     *                   leaves {@code out} unchanged
     * @param skipHidden if {@code true}, prune entries starting with
     *                   {@code '.'} or {@code '_'}
     * @return {@code out}, for chaining
     * @throws IOException on traversal errors, including a directory that
     *         cannot be read; {@code out} is then left unchanged
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     * @throws NullPointerException if {@code out} is null
     */
    public static List<Path> include(List<Path> out, String glob, final boolean skipHidden)
        throws IOException
    {
        Objects.requireNonNull(out, "out");

        String g = globNormAgainstDir(glob, Path.of(""));
        if (g == null) return out;

        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (Path p : out) {
            if (p != null) seen.add(p.toAbsolutePath().normalize());
        }

        Path literal = tryLiteralPath(g);
        if (literal != null && Files.isRegularFile(literal)) {
            if (!skipHidden || !startsWithDotOrUnderscore(literal.getFileName())) {
                seen.add(literal);
            }
            out.clear();
            out.addAll(seen);
            return out;
        }
        if (literal != null && Files.isDirectory(literal)) {
            g = g.endsWith("/") ? g + "**" : g + "/**";
        }

        final Pattern regex = globToRegex(g, false);
        final Path walkRoot = computeWalkRoot(g);

        if (walkRoot == null || !Files.exists(walkRoot)) {
            out.clear();
            out.addAll(seen);
            return out;
        }

        Files.walkFileTree(walkRoot, EnumSet.noneOf(FileVisitOption.class), walkDepth(g),
            new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                if (!dir.equals(walkRoot) && skipHidden
                        && startsWithDotOrUnderscore(dir.getFileName())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (attrs.isDirectory()
                        || (attrs.isSymbolicLink() && Files.isDirectory(file))) {
                    return FileVisitResult.CONTINUE;
                }
                if (skipHidden && startsWithDotOrUnderscore(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                Path abs = file.toAbsolutePath().normalize();
                if (regex.matcher(nfc(toSlash(abs.toString()))).matches()) {
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
     * Equivalent to {@code include(out, glob, true)}.
     *
     * @param out mutable list to populate
     * @param glob glob pattern (absolute or relative)
     * @return {@code out}, for chaining
     * @throws IOException on traversal errors
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     * @throws NullPointerException if {@code out} is null
     * @see #include(List, String, boolean)
     */
    public static List<Path> include(List<Path> out, String glob) throws IOException
    {
        return include(out, glob, true);
    }

    /**
     * Expand a glob and return the matching files as a new list.
     *
     * <p>Equivalent to {@code include(new ArrayList<>(), glob, true)}.</p>
     *
     * @param glob glob pattern (absolute or relative)
     * @return mutable list of absolute normalized file paths, possibly empty
     * @throws IOException on traversal errors
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     * @see #include(List, String, boolean)
     */
    public static List<Path> ls(String glob) throws IOException
    {
        return include(new ArrayList<>(), glob, true);
    }

    /**
     * Resolve a possibly-relative path string against a base directory.
     *
     * <p>
     * If the path is already absolute it is normalized and returned.
     * Otherwise it is resolved against {@code baseDir}, then normalized.
     * Leading and trailing whitespace in {@code relOrAbs} is stripped.
     * </p>
     *
     * @param baseDir  the directory to resolve against
     * @param relOrAbs a path string, absolute or relative
     * @return the resolved, absolute, normalized path
     * @throws NullPointerException if either argument is null
     */
    public static Path resolve(final Path baseDir, final String relOrAbs)
    {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(relOrAbs, "relOrAbs");
        Path p = Path.of(relOrAbs.trim());
        if (!p.isAbsolute()) {
            p = baseDir.resolve(p);
        }
        return p.toAbsolutePath().normalize();
    }

    /**
     * Recursively delete a file or directory tree.
     *
     * <p>
     * Symbolic links are deleted, never followed; a dangling link is
     * deleted too. As a guard against blank or mistyped configuration
     * values, the method refuses an empty path, the filesystem root, the
     * current working directory and any of its ancestors. Deletion is not
     * atomic: on failure, part of the tree may already be gone.
     * </p>
     *
     * @param path file or directory to delete; if {@code null} or
     *             nonexistent, this method returns {@code false}
     * @return {@code true} if the path existed and was deleted
     * @throws IOException on deletion failure
     * @throws IllegalArgumentException if {@code path} is empty, a root, or
     *         the current working directory or one of its ancestors
     */
    public static boolean rm(Path path) throws IOException
    {
        if (path == null) return false;
        Path abs = path.toAbsolutePath().normalize();
        if (path.toString().isEmpty() || abs.getNameCount() == 0
                || Path.of("").toAbsolutePath().normalize().startsWith(abs)) {
            throw new IllegalArgumentException("Refusing to delete: '" + path + "' (" + abs + ")");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException
            {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }

    /**
     * Return the filename without its extension (the "stem").
     *
     * <p>
     * A dot at position 0 is not treated as an extension separator:
     * {@code .gitignore} has stem {@code ".gitignore"}, not
     * {@code ""}.
     * </p>
     *
     * <pre>
     * Dir.stem(Path.of("alix-piaget.xml"))  → "alix-piaget"
     * Dir.stem(Path.of("archive.tar.gz"))   → "archive.tar"
     * Dir.stem(Path.of("Makefile"))         → "Makefile"
     * Dir.stem(Path.of(".gitignore"))       → ".gitignore"
     * </pre>
     *
     * <p>Analogous to Python's {@code pathlib.PurePath.stem}.</p>
     *
     * @param path a file path (only the last component is examined)
     * @return the filename without extension
     * @throws NullPointerException if {@code path} is null or has no filename
     */
    public static String stem(final Path path)
    {
        final String name = path.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    /**
     * Append the regex form of a glob bracket expression.
     *
     * <p>
     * A negated class also excludes {@code '/'}, so that it cannot match
     * across segments. Characters with a special meaning inside a Java
     * regex class ({@code \ [ ] & ^}, and {@code -} outside a range) are
     * escaped, which also neutralizes Java class intersection
     * ({@code &&}) and nested classes.
     * </p>
     *
     * @param rx  regex under construction
     * @param cls content between the brackets, possibly starting with
     *            {@code '!'}
     */
    private static void appendClass(StringBuilder rx, String cls)
    {
        boolean negate = cls.startsWith("!");
        if (negate) cls = cls.substring(1);
        rx.append(negate ? "[^/" : "[");
        for (int k = 0; k < cls.length(); k++) {
            char c = cls.charAt(k);
            if (c == '-' && k > 0 && k < cls.length() - 1) {
                rx.append('-');
                continue;
            }
            if ("\\[]&^-".indexOf(c) >= 0) rx.append('\\');
            rx.append(c);
        }
        rx.append(']');
    }

    /**
     * Append the regex form of a glob (or of one brace alternative).
     *
     * <p>
     * Brace alternatives are converted recursively, so they may contain
     * wildcards, classes and nested braces. Unbalanced {@code '['} or
     * <code>'{'</code> are matched literally.
     * </p>
     *
     * @param rx           regex under construction
     * @param glob         glob text to convert
     * @param segmentStart whether position 0 of {@code glob} is at the start
     *                     of a path segment (needed to recognize
     *                     {@code **}{@code /} inside alternatives)
     */
    private static void appendGlob(StringBuilder rx, String glob, boolean segmentStart)
    {
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            boolean atSegmentStart = (i == 0) ? segmentStart : glob.charAt(i - 1) == '/';

            switch (c) {
                case '*': {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        if (atSegmentStart
                                && i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                            rx.append("(?:[^/]+/)*");
                            i += 3;
                        } else {
                            rx.append(".*");
                            i += 2;
                        }
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
                        appendClass(rx, glob.substring(i + 1, j));
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
                        List<String> alts = splitAlternatives(glob.substring(i + 1, j - 1));
                        rx.append("(?:");
                        for (int k = 0; k < alts.size(); k++) {
                            if (k > 0) rx.append("|");
                            appendGlob(rx, alts.get(k), atSegmentStart);
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
    }

    /**
     * Resolve the directory from which a glob is expanded.
     *
     * @param absoluteGlob normalized absolute slash-separated glob
     * @return the existing directory named by the glob's non-glob prefix,
     *         or {@code null} if it does not exist or is not a valid path
     */
    private static Path computeWalkRoot(String absoluteGlob)
    {
        String prefix = nonGlobPrefixDir(absoluteGlob);
        try {
            Path p = Path.of(fromSlash(prefix)).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Tell whether a string contains a glob metacharacter.
     *
     * @param s string to test
     * @return {@code true} if {@code s} contains {@code * ? [} or <code>{</code>
     */
    private static boolean containsMeta(String s)
    {
        return firstMetaIndex(s) >= 0;
    }

    /**
     * Find the first glob metacharacter in a string.
     *
     * @param s string to scan
     * @return index of the first {@code * ? [} or <code>{</code>, or -1
     */
    private static int firstMetaIndex(String s)
    {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') return i;
        }
        return -1;
    }

    /**
     * Convert {@code '/'} separators to the platform separator.
     *
     * @param s slash-separated path string
     * @return {@code s} with the platform separator
     */
    private static String fromSlash(String s)
    {
        return File.separatorChar == '/' ? s : s.replace('/', '\\');
    }

    /**
     * Normalize a glob relative to a known directory, without guessing
     * whether that directory exists or represents a file.
     *
     * @param glob absolute or relative glob, possibly blank or a comment
     * @param dir directory used to resolve relative globs
     * @return normalized absolute slash-separated glob, or {@code null}
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     */
    private static String globNormAgainstDir(String glob, Path dir)
    {
        String g = stripConfigSyntax(glob);
        if (g == null) return null;
        if (isAbsoluteGlob(g)) return normalizeGlobLexically(g);
        return normalizeGlobLexically(dir.toAbsolutePath().normalize() + "/" + g);
    }

    /**
     * Convert a normalized slash-separated glob to an anchored regex.
     *
     * <p>
     * The glob is converted to Unicode NFC first; candidate strings must be
     * converted with {@link #nfc(String)} before matching. See the class
     * documentation for the supported metacharacters.
     * </p>
     *
     * @param glob    glob to convert
     * @param subtree if {@code true}, the regex also matches any path below
     *                a matching path ({@code ^glob(?:/.*)?$})
     * @return compiled pattern
     */
    private static Pattern globToRegex(String glob, boolean subtree)
    {
        String g = nfc(glob);
        StringBuilder rx = new StringBuilder(g.length() * 2);
        rx.append("^");
        appendGlob(rx, g, true);
        if (subtree) rx.append("(?:/.*)?");
        rx.append("$");
        return Pattern.compile(rx.toString());
    }

    /**
     * Tell whether a glob is absolute: a Unix root, a UNC or backslash
     * root, or a drive letter followed by a separator.
     *
     * @param glob glob to test, possibly {@code null}
     * @return {@code true} if {@code glob} is absolute
     */
    private static boolean isAbsoluteGlob(String glob)
    {
        if (glob == null || glob.isEmpty()) return false;
        if (glob.startsWith("/") || glob.startsWith("\\")) return true;
        return isDrivePrefix(glob);
    }

    /**
     * Tell whether a string starts with a drive letter, a colon and a
     * separator, as in {@code C:/} or {@code C:\}.
     *
     * @param s string to test
     * @return {@code true} if {@code s} starts with a drive prefix
     */
    private static boolean isDrivePrefix(String s)
    {
        return s.length() >= 3
            && Character.isLetter(s.charAt(0))
            && s.charAt(1) == ':'
            && isSep(s.charAt(2));
    }

    /**
     * Tell whether a character is a path separator ({@code '/'} or
     * {@code '\\'}).
     *
     * @param c character to test
     * @return {@code true} if {@code c} is a separator
     */
    private static boolean isSep(char c)
    {
        return c == '/' || c == '\\';
    }

    /**
     * Convert a string to Unicode NFC, without allocating when it is
     * already normalized.
     *
     * @param s string to normalize
     * @return {@code s} in NFC
     */
    private static String nfc(String s)
    {
        return Normalizer.isNormalized(s, Normalizer.Form.NFC)
            ? s
            : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /**
     * Return the longest leading directory of a glob that contains no
     * metacharacter. For a glob without metacharacters, return its parent.
     *
     * @param glob normalized absolute slash-separated glob
     * @return slash-separated directory string, a prefix of {@code glob}
     *         except for globs without metacharacters, whose parent is
     *         computed through {@link Path}
     */
    private static String nonGlobPrefixDir(String glob)
    {
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
                if (isDrivePrefix(glob)) return glob.substring(0, 3);
                if (glob.startsWith("/")) return "/";
            }
            return toSlash(Path.of("").toAbsolutePath().normalize().toString());
        }

        if (slash == 0) return "/";
        if (slash == 2 && isDrivePrefix(glob)) return glob.substring(0, 3);

        return glob.substring(0, slash);
    }

    /**
     * Lexical normalization only: unify separators to {@code '/'}, drop
     * empty and {@code .} segments, collapse {@code ..}. Keeps glob
     * metacharacters intact.
     *
     * <p>
     * A {@code ..} above the root of an absolute glob is dropped. A
     * {@code ..} that follows a segment containing a metacharacter is
     * rejected: collapsing it would change the meaning of the glob, and
     * keeping it would never match, since candidate paths are normalized.
     * </p>
     *
     * @param input glob, absolute or relative, with any separators
     * @return normalized slash-separated glob
     * @throws IllegalArgumentException if {@code ..} follows a wildcard segment
     */
    private static String normalizeGlobLexically(String input)
    {
        String s = input.replace('\\', '/');

        String prefix = "";
        int start = 0;

        // UNC //server/share/...
        if (s.startsWith("//")) {
            prefix = "//";
            start = 2;
        }
        // Drive absolute C:/...
        else if (isDrivePrefix(s)) {
            prefix = s.substring(0, 3);
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
                String top = stack.peekLast();
                if (top != null && containsMeta(top)) {
                    throw new IllegalArgumentException(
                        "'..' after a wildcard segment is not supported: " + input);
                }
                if (top != null && !"..".equals(top)) {
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
        if (joined.isEmpty()) return prefix;
        return prefix.endsWith("/") ? prefix + joined : prefix + "/" + joined;
    }

    /**
     * Split the body of a brace expression on its top-level commas.
     * Commas inside nested braces belong to the nested expression. Empty
     * alternatives are kept, so <code>{a,}</code> matches {@code a} or
     * nothing.
     *
     * @param body text between the outer braces
     * @return alternatives, at least one
     */
    private static List<String> splitAlternatives(String body)
    {
        List<String> alts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int k = 0; k < body.length(); k++) {
            char c = body.charAt(k);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ',' && depth == 0) {
                alts.add(body.substring(from, k));
                from = k + 1;
            }
        }
        alts.add(body.substring(from));
        return alts;
    }

    /**
     * Tell whether a filename starts with {@code '.'} or {@code '_'}.
     *
     * @param name filename component, possibly {@code null}
     * @return {@code true} if {@code name} is hidden by this convention
     */
    private static boolean startsWithDotOrUnderscore(Path name)
    {
        if (name == null) return false;
        String s = name.toString();
        return !s.isEmpty() && (s.charAt(0) == '.' || s.charAt(0) == '_');
    }

    /**
     * Strip blank lines and comment lines ({@code #}, or {@code //}
     * followed by whitespace). UNC paths starting with {@code //server/share}
     * are not comments. Legacy {@code //comment} lines are ignored unless
     * they resemble a UNC path.
     *
     * @param s glob or configuration line
     * @return trimmed string, or {@code null} if blank or comment
     */
    private static String stripConfigSyntax(String s)
    {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("#")) return null;
        if (t.startsWith("//")) {
            if (t.length() == 2 || Character.isWhitespace(t.charAt(2))) return null;
            int share = t.indexOf('/', 2);
            if (share < 0 || share == t.length() - 1) return null;
        }
        return t;
    }

    /**
     * Convert backslash separators to {@code '/'}.
     *
     * @param s path string
     * @return {@code s} with {@code '/'} separators
     */
    private static String toSlash(String s)
    {
        return s.replace('\\', '/');
    }

    /**
     * Interpret a glob without metacharacters as a path.
     *
     * @param glob normalized slash-separated glob
     * @return absolute normalized path, or {@code null} if {@code glob}
     *         contains a metacharacter or is not a valid path
     */
    private static Path tryLiteralPath(String glob)
    {
        if (containsMeta(glob)) return null;
        try {
            return Path.of(fromSlash(glob)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Compute the maximum walk depth below the walk root that can contain
     * a match. The number of segments after the non-glob prefix is an upper
     * bound, also for brace alternatives containing {@code '/'}, because it
     * counts every separator in the remaining glob.
     *
     * @param absoluteGlob normalized absolute slash-separated glob
     * @return depth for {@link Files#walkFileTree(Path, Set, int, FileVisitor)},
     *         or {@link Integer#MAX_VALUE} if the glob contains {@code **}
     *         or the prefix cannot be related to the glob
     */
    private static int walkDepth(String absoluteGlob)
    {
        String prefix = nonGlobPrefixDir(absoluteGlob);
        if (!absoluteGlob.startsWith(prefix)) return Integer.MAX_VALUE;
        String rest = absoluteGlob.substring(prefix.length());
        if (rest.contains("**")) return Integer.MAX_VALUE;
        int depth = 0;
        for (String part : rest.split("/")) {
            if (!part.isEmpty()) depth++;
        }
        return (depth == 0) ? Integer.MAX_VALUE : depth;
    }
}
