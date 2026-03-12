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
 * Filesystem utilities: path manipulation, cross-platform glob handling,
 * and directory operations.
 *
 * <h2>Glob handling</h2>
 * <ul>
 *   <li>Globs may be absolute or relative.</li>
 *   <li>Relative globs can be normalized against a config file or directory.</li>
 *   <li>Glob matching is done on absolute normalized paths.</li>
 *   <li>Expansion walks from the longest non-glob prefix to avoid scanning
 *       the whole filesystem.</li>
 *   <li>Blank lines and comment lines ({@code #}, {@code //}) are ignored.</li>
 * </ul>
 *
 * <p>
 * Matching is done on absolute normalized path strings converted to
 * {@code '/'} separators. This avoids Windows backslash issues with
 * {@code NIO PathMatcher("glob:...")}.
 * </p>
 */
public final class Dir
{
    private Dir() {
    }

    // =====================================================================
    // Public API — alphabetical order
    // =====================================================================

    /**
     * Remove from {@code paths} any path matched by the given glob.
     *
     * <p>
     * The glob is normalized against the current directory. Blank or
     * comment globs are silently ignored.
     * </p>
     *
     * @param paths mutable list to filter in place
     * @param glob  glob pattern (absolute or relative); blank/comment ignored
     * @return {@code paths}, for chaining
     * @throws IOException on resolution errors
     * @throws NullPointerException if {@code paths} is null
     */
    public static List<Path> exclude(List<Path> paths, String glob) throws IOException
    {
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
     * Normalize a possibly-relative glob against a base file or directory.
     *
     * <p>
     * Intended for configuration files where globs are written relative
     * to the config location. The returned glob is absolute and normalized,
     * with {@code '/'} separators for stable matching.
     * </p>
     *
     * @param glob a glob string (may include wildcards)
     * @param base a file or directory used as resolution anchor;
     *             if a regular file, its parent directory is used;
     *             if {@code null}, the current working directory is used
     * @return an absolute, normalized glob string with {@code '/'} separators,
     *         or {@code null} if the input is blank or a comment line
     * @throws IOException if {@code base} has no parent or cannot be resolved
     */
    public static String globNorm(String glob, File base) throws IOException
    {
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
     * Expand a glob and append matching paths to {@code out}.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>If the glob resolves to an existing regular file,
     *       it is added directly.</li>
     *   <li>Otherwise the filesystem is walked from the longest
     *       non-glob prefix, and matching entries are appended.</li>
     *   <li>Directories and files starting with {@code '.'} or {@code '_'}
     *       are skipped when {@code skipHidden} is {@code true}.</li>
     *   <li>Duplicates (by absolute normalized path) are suppressed,
     *       preserving encounter order.</li>
     * </ul>
     *
     * @param out        mutable list to populate
     * @param glob       glob pattern (absolute or relative)
     * @param skipHidden if {@code true}, prune entries starting with
     *                   {@code '.'} or {@code '_'}
     * @return {@code out}, for chaining
     * @throws IOException          on traversal errors
     * @throws NullPointerException if {@code out} is null
     */
    public static List<Path> include(List<Path> out, String glob, final boolean skipHidden)
        throws IOException
    {
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
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                if (!dir.equals(walkRoot) && skipHidden
                        && startsWithDotOrUnderscore(dir.getFileName())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String s = toSlash(dir.toAbsolutePath().normalize().toString());
                if (regex.matcher(s).matches()) {
                    seen.add(dir.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (skipHidden && startsWithDotOrUnderscore(file.getFileName())) {
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
     * Equivalent to {@code include(out, glob, true)}.
     *
     * @see #include(List, String, boolean)
     */
    public static List<Path> include(List<Path> out, String glob) throws IOException
    {
        return include(out, glob, true);
    }

    /**
     * Expand a glob and return matching paths as a new list.
     *
     * <p>Equivalent to {@code include(new ArrayList<>(), glob, true)}.</p>
     *
     * @param glob glob pattern (absolute or relative)
     * @return list of matching paths, possibly empty
     * @throws IOException on traversal errors
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
     * @param path file or directory to delete; if {@code null} or
     *             nonexistent, this method returns {@code false}
     * @return {@code true} if the path existed and was deleted
     * @throws IOException on deletion failure
     */
    public static boolean rm(Path path) throws IOException
    {
        if (path == null || !Files.exists(path)) return false;

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

    // =====================================================================
    // Private helpers
    // =====================================================================

    private static Path computeWalkRoot(String absoluteGlob)
    {
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

    private static boolean containsMeta(String s)
    {
        return firstMetaIndex(s) >= 0;
    }

    private static int firstMetaIndex(String s)
    {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') return i;
        }
        return -1;
    }

    private static String fromSlash(String s)
    {
        return File.separatorChar == '/' ? s : s.replace('/', '\\');
    }

    /**
     * Minimal glob → regex conversion on slash-normalized absolute paths.
     *
     * <p>Supported metacharacters:</p>
     * <ul>
     *   <li>{@code *}  — any characters except {@code '/'}</li>
     *   <li>{@code **} — any characters including {@code '/'}</li>
     *   <li>{@code ?}  — one character except {@code '/'}</li>
     *   <li>{@code [abc]}, {@code [a-z]}, {@code [!abc]}</li>
     *   <li><code>{a,b,c}</code></li>
     * </ul>
     */
    private static Pattern globToRegex(String glob)
    {
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

    private static boolean isAbsoluteGlob(String glob)
    {
        if (glob == null || glob.isEmpty()) return false;
        if (glob.startsWith("/") || glob.startsWith("\\")) return true;
        return glob.length() >= 3
            && Character.isLetter(glob.charAt(0))
            && glob.charAt(1) == ':'
            && isSep(glob.charAt(2));
    }

    private static boolean isSep(char c)
    {
        return c == '/' || c == '\\';
    }

    /**
     * Lexical normalization only. Keeps glob metacharacters intact.
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
        else if (s.length() >= 3
            && Character.isLetter(s.charAt(0))
            && s.charAt(1) == ':'
            && s.charAt(2) == '/') {
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
        if (joined.isEmpty()) {
            return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        }
        return prefix.endsWith("/") ? prefix + joined : prefix + "/" + joined;
    }

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
                if (glob.length() >= 3
                        && Character.isLetter(glob.charAt(0))
                        && glob.charAt(1) == ':'
                        && glob.charAt(2) == '/') {
                    return glob.substring(0, 3);
                }
                if (glob.startsWith("/")) return "/";
            }
            return toSlash(Path.of("").toAbsolutePath().normalize().toString());
        }

        return glob.substring(0, slash);
    }

    private static boolean startsWithDotOrUnderscore(Path name)
    {
        if (name == null) return false;
        String s = name.toString();
        return !s.isEmpty() && (s.charAt(0) == '.' || s.charAt(0) == '_');
    }

    /**
     * Strip blank lines and comment lines ({@code #}, {@code //}).
     *
     * @return trimmed string, or {@code null} if blank or comment
     */
    private static String stripConfigSyntax(String s)
    {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("#")) return null;
        if (t.startsWith("//")) return null;
        return t;
    }

    private static String toSlash(String s)
    {
        return s.replace('\\', '/');
    }

    private static Path tryLiteralPath(String glob)
    {
        if (containsMeta(glob)) return null;
        try {
            return Path.of(fromSlash(glob)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
